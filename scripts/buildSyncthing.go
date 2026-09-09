package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"

	"syncthing.sh/scripts/utils"
)

var platformDirs = map[string]string{
	"windows": "windows-x86_64",
	"linux":   "linux-x86_64",
	"darwin":  "darwin-x86_64",
}

// Leave empty to auto-detect version by 'git describe'.
const forceDisplaySyncthingVersion = ""
const filenameSyncthingBinary = "libsyncthingnative.so"

type buildTarget struct {
	Arch   string
	GoArch string
	JNIDir string
	CC     string
}

var buildTargets = []buildTarget{
	{"arm", "arm", "armeabi-v7a", "armv7a-linux-androideabi%d-clang"},
	{"arm64", "arm64", "arm64-v8a", "aarch64-linux-android%d-clang"},
	{"x86", "386", "x86", "i686-linux-android%d-clang"},
	{"x86_64", "amd64", "x86_64", "x86_64-linux-android%d-clang"},
}

var (
	moduleDir         string
	projectDir        string
	syncthingDir      string
	prereqToolsDir    string
	minSDK            int
	gitBin            string
	goBin             string
	versionNameRe     = regexp.MustCompile(`^\s*version-name\s*=\s*"?([^"\s]+)"?`)
	minSDKRe          = regexp.MustCompile(`^\s*min-sdk\s*=\s*"?(\d+)"?`)
	goVersionRe       = regexp.MustCompile(`^\s*go_version\s*=\s*"?([^"\s]+)"?`)
	nativeVersionRe   = regexp.MustCompile(`v?(\d+)\.(\d+)\.(\d+)`)
	remoteOwnerRepoRe = regexp.MustCompile(`[:/]([^/]+)/([^/]+?)(?:\.git)?$`)
	goVersionOutputRe = regexp.MustCompile(`go version go(\S+)`)
)

func versionsCatalogPath() string {
	return filepath.Join(projectDir, "gradle", "libs.versions.toml")
}

// scanCatalog returns the first capture group matched by re in the catalog.
func scanCatalog(re *regexp.Regexp) (string, error) {
	data, err := os.ReadFile(versionsCatalogPath())
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(string(data), "\n") {
		if m := re.FindStringSubmatch(line); m != nil {
			return m[1], nil
		}
	}
	return "", fmt.Errorf("no match for %s in %s", re, versionsCatalogPath())
}

func getMinSDK() int {
	v, err := scanCatalog(minSDKRe)
	if err != nil {
		utils.Fail("Failed to find min-sdk: %v", err)
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		utils.Fail("Invalid min-sdk value %q: %v", v, err)
	}
	return n
}

func getAppVersion() string {
	v, err := scanCatalog(versionNameRe)
	if err != nil {
		utils.Fail("version-name not found in versions catalog: %v", err)
	}
	return v
}

func getExpectedGoVersion() string {
	v, err := scanCatalog(goVersionRe)
	if err != nil {
		utils.Fail("Could not find go_version in gradle/libs.versions.toml: %v", err)
	}
	return v
}

func extractNativeVersion(describeOutput string) string {
	m := nativeVersionRe.FindStringSubmatch(describeOutput)
	if m == nil {
		utils.Fail("Could not extract version from git describe output: %s", describeOutput)
	}
	return fmt.Sprintf("%s.%s.%s", m[1], m[2], m[3])
}

func verifyNativeVersionMatchesApp(syncthingVersionRaw string) {
	nativeVersion := extractNativeVersion(syncthingVersionRaw)

	parts := strings.Split(getAppVersion(), ".")
	if len(parts) > 3 {
		parts = parts[:3]
	}
	appVersion := strings.Join(parts, ".")

	if nativeVersion != appVersion {
		utils.Fail(
			"SyncthingNative version (%s) differs from App version (%s). "+
				"Please update gradle/libs.versions.toml.",
			nativeVersion, appVersion,
		)
	}
}

var checksumRetried bool

func cleanGoModuleCache() {
	cmd := exec.Command(goBin, "clean", "-modcache")
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	_ = cmd.Run()
}

func run(env []string, dir string, name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = env
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func mustRun(env []string, dir string, name string, args ...string) {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = env
	cmd.Stdout = os.Stdout

	var stderrBuf bytes.Buffer
	cmd.Stderr = io.MultiWriter(os.Stderr, &stderrBuf)

	err := cmd.Run()
	if err != nil && !checksumRetried && strings.Contains(stderrBuf.String(), "checksum mismatch") {
		fmt.Println("\nChecksum mismatch detected, cleaning module cache and retrying once...")
		cleanGoModuleCache()
		checksumRetried = true
		cmd2 := exec.Command(name, args...)
		cmd2.Dir = dir
		cmd2.Env = env
		cmd2.Stdout = os.Stdout
		cmd2.Stderr = os.Stderr
		if err = cmd2.Run(); err != nil {
			utils.Fail("Command failed (after retry): %s %s: %v", name, strings.Join(args, " "), err)
		}
		return
	}

	if err != nil {
		utils.Fail("Command failed: %s %s: %v", name, strings.Join(args, " "), err)
	}
}

func output(dir string, name string, args ...string) (string, error) {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Stderr = os.Stderr
	out, err := cmd.Output()
	return strings.TrimSpace(string(out)), err
}

func getGoVersion(goBinary string) string {
	cmd := exec.Command(goBinary, "version")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return ""
	}
	if m := goVersionOutputRe.FindStringSubmatch(string(out)); m != nil {
		return m[1]
	}
	return ""
}

func extractTarGz(archive, destDir string) error {
	f, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer f.Close()

	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}

		// Guard against path traversal.
		target := filepath.Join(destDir, filepath.Clean("/"+hdr.Name))
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}

			mode := hdr.FileInfo().Mode().Perm()
			if mode == 0 {
				mode = 0o644
			}

			out, err := os.OpenFile(
				target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode,
			)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, tr); err != nil {
				out.Close()
				return err
			}
			out.Close()
		case tar.TypeLink:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			src := filepath.Join(destDir, filepath.Clean("/"+hdr.Linkname))
			os.Remove(target)
			if err := os.Link(src, target); err != nil {
				return err
			}
		case tar.TypeSymlink:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			// Reject links escaping destDir.
			if filepath.IsAbs(hdr.Linkname) {
				return fmt.Errorf("absolute symlink %s rejected", hdr.Name)
			}
			resolved := filepath.Clean(filepath.Join(filepath.Dir(target), hdr.Linkname))
			if !strings.HasPrefix(resolved, filepath.Clean(destDir)+string(os.PathSeparator)) {
				return fmt.Errorf("symlink %s escapes destination", hdr.Name)
			}
			os.Remove(target)
			if err := os.Symlink(hdr.Linkname, target); err != nil {
				return err
			}
		}
	}
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	info, err := in.Stat()
	if err != nil {
		return err
	}

	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, info.Mode())
	if err != nil {
		return err
	}
	defer out.Close()

	_, err = io.Copy(out, in)
	return err
}

func copyTree(src, dst string) error {
	return filepath.WalkDir(src, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)

		switch {
		case info.IsDir():
			return os.MkdirAll(target, info.Mode())
		case info.Mode()&os.ModeSymlink != 0:
			link, err := os.Readlink(path)
			if err != nil {
				return err
			}
			os.Remove(target)
			return os.Symlink(link, target)
		default:
			return copyFile(path, target)
		}
	})
}

func isDir(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

func isFile(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.Mode().IsRegular()
}

// installGo ensures a Go toolchain of the pinned version is on PATH,
// bootstrapping a source build with the system Go if needed.
func installGo() {
	if err := os.MkdirAll(prereqToolsDir, 0o755); err != nil {
		utils.Fail("Cannot create %s: %v", prereqToolsDir, err)
	}

	expectedVersion := getExpectedGoVersion()
	fmt.Println("Required Go version:", expectedVersion)

	systemGo := utils.Which("go")
	if systemGo == "" {
		utils.Fail("Error: No system Go found for bootstrap. golang-go package " +
			"should be installed.")
	}

	systemVersion := getGoVersion(systemGo)
	fmt.Println("Found system Go version:", systemVersion, "at:", systemGo)

	if systemVersion == expectedVersion {
		fmt.Println("System Go version matches required version. No build needed.")
		return
	}

	fmt.Println("System Go version differs from required. Building Go",
		expectedVersion, "from source using system Go as bootstrap...")

	goSourceURL := "https://github.com/golang/go/archive/go" + expectedVersion + ".tar.gz"
	goSourceTar := filepath.Join(prereqToolsDir, "go-source-"+expectedVersion+".tar.gz")

	if !isFile(goSourceTar) {
		fmt.Println("Downloading Go source to:", goSourceTar)
		if err := utils.DownloadFile(goSourceURL, goSourceTar); err != nil {
			utils.Fail("Download failed: %v", err)
		}
	}
	fmt.Println("Downloaded Go source to:", goSourceTar)

	goExtractDir := filepath.Join(prereqToolsDir, "go-go"+expectedVersion)
	if !isDir(goExtractDir) {
		fmt.Println("Extracting Go source ...")
		if err := extractTarGz(goSourceTar, prereqToolsDir); err != nil {
			utils.Fail("Extraction failed: %v", err)
		}
	}

	goBuildDir := filepath.Join(prereqToolsDir, "go")

	existingGo := filepath.Join(goBuildDir, "bin", "go")
	if runtime.GOOS == "windows" {
		existingGo += ".exe"
	}
	if isFile(existingGo) && getGoVersion(existingGo) == expectedVersion {
		fmt.Println("Reusing previously built Go", expectedVersion)
		os.Setenv("PATH", filepath.Dir(existingGo)+string(os.PathListSeparator)+os.Getenv("PATH"))
		os.Setenv("GOROOT", goBuildDir)
		return
	}
	if isDir(goBuildDir) {
		if err := os.RemoveAll(goBuildDir); err != nil {
			utils.Fail("Cannot remove %s: %v", goBuildDir, err)
		}
	}
	if err := copyTree(goExtractDir, goBuildDir); err != nil {
		utils.Fail("Cannot copy Go source tree: %v", err)
	}

	fmt.Println("Building Go from source using system Go as bootstrap...")
	bootstrap := filepath.Dir(filepath.Dir(systemGo))
	if !isDir(filepath.Join(bootstrap, "src", "encoding")) && runtime.GOOS != "windows" {
		bootstrap = "/usr/lib/go"
		fmt.Println("Override build_env:GOROOT_BOOTSTRAP using ", bootstrap)
	}
	buildEnv := append(os.Environ(), "GOROOT_BOOTSTRAP="+bootstrap)

	srcDir := filepath.Join(goBuildDir, "src")
	if runtime.GOOS == "windows" {
		mustRun(buildEnv, srcDir, "cmd", "/c", filepath.Join(srcDir, "make.bat"))
	} else {
		script := filepath.Join(srcDir, "make.bash")
		if err := os.Chmod(script, 0o755); err != nil {
			utils.Fail("Cannot chmod %s: %v", script, err)
		}
		mustRun(buildEnv, srcDir, "bash", script)
	}

	goBinPath := filepath.Join(goBuildDir, "bin")
	builtGo := filepath.Join(goBinPath, "go")
	if runtime.GOOS == "windows" {
		builtGo += ".exe"
	}

	if !isFile(builtGo) {
		utils.Fail("Go build failed: go binary not found at %s", builtGo)
	}

	builtVersion := getGoVersion(builtGo)
	if builtVersion == "" {
		utils.Fail("Built Go is not working")
	}
	fmt.Println("Successfully built Go:", builtVersion)
	if builtVersion != expectedVersion {
		utils.Fail("Built Go version %s does not match expected %s",
			builtVersion, expectedVersion)
	}

	fmt.Println("Adding built Go to PATH:", goBinPath)
	fmt.Println("Setting GOROOT to:", goBuildDir)
	os.Setenv("PATH", goBinPath+string(os.PathListSeparator)+os.Getenv("PATH"))
	os.Setenv("GOROOT", goBuildDir)
}

func expandTilde(path string) string {
	if strings.HasPrefix(path, "~") {
		home, err := os.UserHomeDir()
		if err == nil {
			return filepath.Join(home, path[1:])
		}
	}
	return path
}

func getNDKReady() {
	if ndk := os.Getenv("ANDROID_NDK_HOME"); ndk != "" {
		ndk = expandTilde(ndk)
		os.Setenv("ANDROID_NDK_HOME", ndk)
		return
	}

	ok := true
	if os.Getenv("NDK_VERSION") == "" {
		fmt.Println("NDK_VERSION is NOT defined.")
		ok = false
	}
	if os.Getenv("ANDROID_HOME") == "" {
		fmt.Println("ANDROID_HOME is NOT defined.")
		ok = false
	}
	if !ok {
		utils.Fail("Error: ANDROID_NDK_HOME or NDK_VERSION and ANDROID_HOME " +
			"environment variable must be defined.")
	}

	os.Setenv("ANDROID_NDK_HOME", expandTilde(filepath.Join(
		os.Getenv("ANDROID_HOME"), "ndk", os.Getenv("NDK_VERSION"),
	)))
}

// getRepository returns '<owner>-<repo>' from the git origin remote.
func getRepository() (string, error) {
	remote, err := output("", gitBin, "-C", projectDir, "remote", "get-url", "origin")
	if err != nil {
		return "", fmt.Errorf("cannot read git origin remote: %w", err)
	}
	m := remoteOwnerRepoRe.FindStringSubmatch(remote)
	if m == nil {
		return "", fmt.Errorf("cannot parse owner/repo from remote: %s", remote)
	}
	return m[1] + "-" + m[2], nil
}

// checkAndCopyPrebuiltLibraries copies cached libraries for the current
// syncthing commit, returning true when all targets were satisfied.
func checkAndCopyPrebuiltLibraries() bool {
	syncthingCommit, err := output("", gitBin, "-C", syncthingDir, "rev-parse", "HEAD")
	if err != nil {
		fmt.Println("Error checking for prebuilt libraries:", err)
		fmt.Println("Will build from scratch")
		return false
	}

	prebuiltBaseDir := os.Getenv("SYNCTHING_PREBUILT_DIR")
	if prebuiltBaseDir == "" && runtime.GOOS == "windows" {
		prebuiltBaseDir = filepath.Join(prereqToolsDir, "prebuilt-jnilibs")
	} else if prebuiltBaseDir == "" {
		prebuiltBaseDir = "/opt/syncthing-android-prereq/prebuilt-jnilibs"
	}
	prebuiltDir := filepath.Join(prebuiltBaseDir, syncthingCommit)

	if !isDir(prebuiltDir) {
		fmt.Println("No prebuilt libraries found for syncthing commit",
			syncthingCommit, "- will build from scratch")
		return false
	}
	fmt.Println("Found prebuilt libraries directory for syncthing commit", syncthingCommit)

	var missing []string
	for _, t := range buildTargets {
		if !isFile(filepath.Join(prebuiltDir, t.JNIDir, filenameSyncthingBinary)) {
			missing = append(missing, t.JNIDir)
		}
	}
	if len(missing) > 0 {
		fmt.Println("Missing architectures:", strings.Join(missing, ", "))
		fmt.Println("Will build all architectures from scratch to ensure consistency")
		return false
	}

	targetLibsDir := filepath.Join(projectDir, "app", "src", "main", "jniLibs")
	for _, t := range buildTargets {
		dstArchDir := filepath.Join(targetLibsDir, t.JNIDir)
		if err := os.MkdirAll(dstArchDir, 0o755); err != nil {
			fmt.Println("Error checking for prebuilt libraries:", err)
			return false
		}
		src := filepath.Join(prebuiltDir, t.JNIDir, filenameSyncthingBinary)
		dst := filepath.Join(dstArchDir, filenameSyncthingBinary)
		if err := copyFile(src, dst); err != nil {
			fmt.Println("Error checking for prebuilt libraries:", err)
			return false
		}
		fmt.Println("Copied prebuilt library for", t.JNIDir)
	}

	fmt.Println("Copied prebuilt libraries from cache")
	return true
}

func main() {
	if _, ok := platformDirs[runtime.GOOS]; !ok {
		keys := make([]string, 0, len(platformDirs))
		for k := range platformDirs {
			keys = append(keys, k)
		}
		utils.Fail("Unsupported platform %s. Supported platforms: %s",
			runtime.GOOS, strings.Join(keys, ", "))
	}

	moduleDir = os.Getenv("SYNCTHING_MODULE_DIR")
	if moduleDir == "" {
		start, err := os.Executable()
		if err != nil {
			start, _ = os.Getwd()
		} else {
			start = filepath.Dir(start)
		}
		// Under 'go run' the binary is in a temp dir; fall back to CWD.
		if strings.HasPrefix(filepath.Clean(start), filepath.Clean(os.TempDir())) {
			start, _ = os.Getwd()
		}
		// Locate the project root (contains gradle/libs.versions.toml),
		// then point at the native module.
		for projectDir = start; !isFile(filepath.Join(projectDir, "gradle", "libs.versions.toml")); {
			parent := filepath.Dir(projectDir)
			if parent == projectDir {
				utils.Fail("Cannot locate project root containing gradle/libs.versions.toml")
			}
			projectDir = parent
		}
		moduleDir = filepath.Join(projectDir, "syncthing")
	}
	moduleDir, _ = filepath.Abs(moduleDir)

	projectDir, _ = filepath.Abs(filepath.Join(moduleDir, ".."))
	syncthingDir = filepath.Join(moduleDir, "src", "github.com", "syncthing", "syncthing")
	prereqToolsDir, _ = filepath.Abs(
		filepath.Join(moduleDir, "..", "..", "syncthing-android-prereq"),
	)
	minSDK = getMinSDK()

	gitBin = utils.Which("git")
	if gitBin == "" {
		utils.Fail("Error: git is not available on the PATH.")
	}
	fmt.Printf("git_bin='%s'\n", gitBin)

	if checkAndCopyPrebuiltLibraries() {
		fmt.Println("Using prebuilt libraries, skipping native build")
		return
	}

	if utils.Which("go") == "" {
		utils.Fail("Error: go is not available on the PATH. Please install go to build go itself.")
	}
	installGo()

	goBin = utils.Which("go")
	if goBin == "" {
		utils.Fail("Error: go is not available on the PATH after installation.")
	}
	fmt.Printf("go_bin='%s'\n", goBin)

	getNDKReady()
	if os.Getenv("ANDROID_NDK_HOME") == "" {
		utils.Fail("Error: ANDROID_NDK_HOME environment variable not defined")
	}
	fmt.Printf("ANDROID_NDK_HOME='%s'\n", os.Getenv("ANDROID_NDK_HOME"))

	var syncthingVersion string
	if forceDisplaySyncthingVersion != "" {
		syncthingVersion = strings.ReplaceAll(forceDisplaySyncthingVersion, "rc", "preview")
	} else {
		out := ""
		if b, err := os.ReadFile(filepath.Join(syncthingDir, ".version")); err == nil {
			out = strings.TrimSpace(string(b))
		}
		if out == "" {
			fmt.Println("Vendored .version missing; using version from libs.versions.toml")
			parts := strings.Split(getAppVersion(), ".")
			if len(parts) > 3 {
				parts = parts[:3]
			}
			out = "v" + strings.Join(parts, ".")
		}
		syncthingVersion = strings.ReplaceAll(out, "rc", "preview")
	}

	verifyNativeVersionMatchesApp(syncthingVersion)

	repository, err := getRepository()
	if err != nil {
		utils.Fail("%v", err)
	}
	fmt.Println("Parent repository:", repository)

	fmt.Println("Building syncthing version", syncthingVersion)
// 	downloadEnv := append(os.Environ(), "GOPATH="+moduleDir, "GOFLAGS=-buildvcs=false")

	buildEnv := append(os.Environ(),
		"BUILD_HOST="+repository,
		"BUILD_USER=reproducible-build",
		"CGO_ENABLED=1",
		"EXTRA_LDFLAGS=-checklinkname=0",
		"GOFLAGS=-buildvcs=false",
		"GOPATH="+moduleDir,
		"GO111MODULE=on",
		"SOURCE_DATE_EPOCH=0",
		"STTRACE=",
	)
	mustRun(buildEnv, syncthingDir, goBin, "version")

	for _, t := range buildTargets {
		fmt.Println()
		fmt.Println("*** Building for", t.Arch)

		cc := filepath.Join(
			os.Getenv("ANDROID_NDK_HOME"),
			"toolchains", "llvm", "prebuilt",
			platformDirs[runtime.GOOS], "bin",
			fmt.Sprintf(t.CC, minSDK),
		)

		if runtime.GOOS == "windows" {
			cc += ".cmd"
		}
		if !isFile(cc) {
			utils.Fail("NDK compiler not found: %s", cc)
		}

		buildArgs := []string{
			"run", "build.go",
			"-goos", "android",
			"-goarch", t.GoArch,
			"-cc", cc,
			"-version", syncthingVersion,
			"-no-upgrade", "build",
		}
		if os.Getenv("SYNCTHING_DEBUG_BUILD") == "1" {
			// The Go race detector does not support android GOOS, so use
			// checkptr instrumentation for debug builds instead.
			fmt.Println("Debug build: enabling checkptr instrumentation")
			buildArgs = append(buildArgs, "-gcflags", "-d=checkptr=2")
		}

		mustRun(buildEnv, syncthingDir, goBin, buildArgs...)
		sourceArtifact := filepath.Join(syncthingDir, "syncthing")

		targetDir := filepath.Join(
			projectDir, "app", "src", "main", "jniLibs", t.JNIDir,
		)
		if err := os.MkdirAll(targetDir, 0o755); err != nil {
			utils.Fail("Cannot create %s: %v", targetDir, err)
		}
		targetArtifact := filepath.Join(targetDir, filenameSyncthingBinary)
		os.Remove(targetArtifact)
		if err := os.Rename(sourceArtifact, targetArtifact); err != nil {
			if cerr := copyFile(sourceArtifact, targetArtifact); cerr != nil {
				utils.Fail("Cannot move artifact: %v (copy fallback: %v)", err, cerr)
			}
			if rmErr := os.Remove(sourceArtifact); rmErr != nil {
				fmt.Println("Warning: could not remove source artifact:", rmErr)
			}
		}

		fmt.Println("*** Finished build for", t.Arch)
	}

	fmt.Println("All builds finished")
}
