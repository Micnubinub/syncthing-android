package main

import (
	"archive/zip"
	"bufio"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"syncthing.sh/scripts/utils"
)

// getTomlValue reads the value of the given key from gradle/libs.versions.toml.
// root is the project root directory.
func getTomlValue(root, key string) string {
	libsPath := filepath.Join(root, "gradle", "libs.versions.toml")
	f, err := os.Open(libsPath)
	if err != nil {
		utils.Fail("Cannot open %s: %v", libsPath, err)
	}
	defer f.Close()

	prefix := key + " = "
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(strings.TrimSpace(line), prefix) {
			// Expects format: key = "value"
			parts := strings.SplitN(line, `"`, 3)
			if len(parts) >= 2 {
				return parts[1]
			}
		}
	}
	if err := scanner.Err(); err != nil {
		utils.Fail("Error reading %s: %v", libsPath, err)
	}
	utils.Fail("%s version not found in %s", key, libsPath)
	return ""
}

// readLocalProperty reads the value of the given key from local.properties in
// the project root. It returns "" if the file or the key is not present.
func readLocalProperty(root, key string) string {
	propsPath := filepath.Join(root, "local.properties")
	f, err := os.Open(propsPath)
	if err != nil {
		return ""
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		if strings.TrimSpace(parts[0]) == key {
			value := strings.TrimSpace(parts[1])
			// Expand a leading ~ to the user's home directory.
			if value == "~" || strings.HasPrefix(value, "~/") || strings.HasPrefix(value, "~\\") {
				if home, err := os.UserHomeDir(); err == nil {
					value = filepath.Join(home, value[2:])
				}
			}
			return value
		}
	}
	return ""
}

// parseVersion splits a dotted version string into its numeric components.
// It returns nil if the string does not look like a version.
func parseVersion(version string) []int {
	parts := strings.Split(version, ".")
	nums := make([]int, 0, len(parts))
	for _, part := range parts {
		n, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil {
			return nil
		}
		nums = append(nums, n)
	}
	return nums
}

// versionLess reports whether version a is lower than version b, comparing
// dotted numeric components (e.g. "29.0.13" < "29.0.142").
func versionLess(a, b string) bool {
	av, bv := parseVersion(a), parseVersion(b)
	for i := 0; i < len(av) && i < len(bv); i++ {
		if av[i] != bv[i] {
			return av[i] < bv[i]
		}
	}
	return len(av) < len(bv)
}

// orUnknown returns s if non-empty, otherwise "unknown".
func orUnknown(s string) string {
	if s == "" {
		return "unknown"
	}
	return s
}

// readSourcePropertiesRevision reads the Pkg.Revision from the source.properties
// file in dir (e.g. an NDK or cmdline-tools installation).
func readSourcePropertiesRevision(dir string) (string, error) {
	f, err := os.Open(filepath.Join(dir, "source.properties"))
	if err != nil {
		return "", err
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, "Pkg.Revision") {
			parts := strings.SplitN(line, "=", 2)
			if len(parts) == 2 {
				return strings.TrimSpace(parts[1]), nil
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return "", err
	}
	return "", fmt.Errorf("Pkg.Revision not found in %s", filepath.Join(dir, "source.properties"))
}

// isExecutable returns true if the given path points to a regular file that is executable.
func isExecutable(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	if info.IsDir() {
		return false
	}
	// Check executable bits. On Windows this is less meaningful; we only check extension later.
	if runtime.GOOS == "windows" {
		// On Windows, just check it's a file.
		return true
	}
	// Unix-like: check owner execute bit (at least one execute bit set)
	mode := info.Mode()
	return mode&0111 != 0
}

// sdkManagerFromDir returns the path to the sdkmanager binary inside the given
// SDK root, or "" if the SDK root does not exist or contains no cmdline-tools
// installation.
func sdkManagerFromDir(sdkDir string) string {
	if sdkDir == "" {
		return ""
	}
	name := "sdkmanager"
	if runtime.GOOS == "windows" {
		name = "sdkmanager.bat"
	}
	toolsDir := filepath.Join(sdkDir, "cmdline-tools")
	entries, err := os.ReadDir(toolsDir)
	if err != nil {
		return ""
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		bin := filepath.Join(toolsDir, entry.Name(), "bin", name)
		if isExecutable(bin) {
			return bin
		}
	}
	return ""
}

// changePermissionsRecursive sets the given permission mode on all files and directories under root.
func changePermissionsRecursive(root string, mode os.FileMode) error {
	return filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		return os.Chmod(path, mode)
	})
}

// installSdkTools downloads and installs the Android command-line tools.
// prerequisiteToolsDir is the base directory where the tools will be placed.
func installSdkTools(prerequisiteToolsDir, cmdlineToolsVersion string) {
	// Create the directory if needed.
	if err := os.MkdirAll(prerequisiteToolsDir, 0755); err != nil {
		utils.Fail("Failed to create directory %s: %v", prerequisiteToolsDir, err)
	}

	zipFileName := filepath.Join(prerequisiteToolsDir, "sdk-tools.zip")

	// Determine download URL.
	var url string
	switch runtime.GOOS {
	case "windows":
		url = fmt.Sprintf("https://dl.google.com/android/repository/commandlinetools-win-%s_latest.zip",
			cmdlineToolsVersion)
	case "darwin":
		arch := "x86_64"
		if runtime.GOARCH == "arm64" {
			arch = "arm64"
		}
		url = fmt.Sprintf("https://dl.google.com/android/repository/commandlinetools-mac_%s-%s_latest.zip",
			arch, cmdlineToolsVersion)
	default:
		url = fmt.Sprintf("https://dl.google.com/android/repository/commandlinetools-linux-%s_latest.zip",
			cmdlineToolsVersion)
	}

	// Download if not already present.
	if _, err := os.Stat(zipFileName); os.IsNotExist(err) {
		fmt.Println("Downloading sdk-tools to:", zipFileName)
		if err := utils.DownloadFile(url, zipFileName); err != nil {
			utils.Fail("Failed to download %s: %v", url, err)
		}
	} else {
		fmt.Println("sdk-tools already downloaded:", zipFileName)
	}

	// Extract if the installed revision does not match the required version.
	sdkToolsPath := filepath.Join(prerequisiteToolsDir, "cmdline-tools")
	latestPath := filepath.Join(sdkToolsPath, "latest")
	if rev, err := readSourcePropertiesRevision(latestPath); err != nil || rev != cmdlineToolsVersion {
		fmt.Println("Extracting sdk-tools ...")
		if err := extractZip(zipFileName, prerequisiteToolsDir); err != nil {
			utils.Fail("Failed to extract %s: %v", zipFileName, err)
		}

		// Move contents one level deeper: cmdline-tools/latest
		if _, err := os.Stat(latestPath); err == nil {
			// Remove existing latest directory.
			if err := os.RemoveAll(latestPath); err != nil {
				utils.Fail("Failed to remove existing latest directory: %v", err)
			}
		}
		if err := os.MkdirAll(latestPath, 0755); err != nil {
			utils.Fail("Failed to create latest directory: %v", err)
		}

		itemsToMove := []string{"NOTICE.txt", "source.properties", "bin", "lib"}
		for _, item := range itemsToMove {
			src := filepath.Join(sdkToolsPath, item)
			dst := filepath.Join(latestPath, item)
			if _, err := os.Stat(src); err == nil {
				if err := os.Rename(src, dst); err != nil {
					utils.Fail("Failed to move %s to %s: %v", src, dst, err)
				}
			}
		}
	} else {
		fmt.Println("sdk-tools already extracted:", latestPath)
	}

	// On Unix, make everything executable.
	if runtime.GOOS == "linux" || runtime.GOOS == "darwin" {
		fmt.Println("Setting permissions on sdk-tools executables ...")
		if err := changePermissionsRecursive(sdkToolsPath, 0755); err != nil {
			utils.Fail("Failed to set permissions: %v", err)
		}
	}

	// Add latest/bin to PATH and set environment variables.
	prependPath(filepath.Join(latestPath, "bin"))

	absToolsDir, err := filepath.Abs(prerequisiteToolsDir)
	if err != nil {
		utils.Fail("Cannot determine absolute path of %s: %v", prerequisiteToolsDir, err)
	}
	ensureAndroidEnv(absToolsDir)
}

// extractZip extracts a zip archive into the specified directory.
func extractZip(zipPath, destDir string) error {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer r.Close()

	for _, f := range r.File {
		path := filepath.Join(destDir, f.Name)

		// Ensure path is within destDir to avoid zip slip.
		if !strings.HasPrefix(path, filepath.Clean(destDir)+string(os.PathSeparator)) {
			return fmt.Errorf("illegal file path: %s", path)
		}

		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(path, f.Mode()); err != nil {
				return err
			}
			continue
		}

		// Create parent directories.
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			return err
		}

		outFile, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
		if err != nil {
			return err
		}

		rc, err := f.Open()
		if err != nil {
			outFile.Close()
			return err
		}

		_, err = io.Copy(outFile, rc)
		rc.Close()
		outFile.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

// prependPath adds dir to the front of PATH if it is not already present.
func prependPath(dir string) {
	for _, entry := range filepath.SplitList(os.Getenv("PATH")) {
		if entry == dir {
			return
		}
	}
	fmt.Println("Adding to PATH:", dir)
	os.Setenv("PATH", dir+string(filepath.ListSeparator)+os.Getenv("PATH"))
}

// ensureAndroidEnv sets ANDROID_HOME and ANDROID_SDK_ROOT to sdkRoot if they are
// not already set in the environment.
func ensureAndroidEnv(sdkRoot string) {
	if os.Getenv("ANDROID_HOME") == "" {
		os.Setenv("ANDROID_HOME", sdkRoot)
		fmt.Println("Set ANDROID_HOME:", sdkRoot)
	}
	if os.Getenv("ANDROID_SDK_ROOT") == "" {
		os.Setenv("ANDROID_SDK_ROOT", sdkRoot)
		fmt.Println("Set ANDROID_SDK_ROOT:", sdkRoot)
	}
}

// runSdkManager runs sdkmanager with the given arguments.
func runSdkManager(sdkManagerBin string, args ...string) error {
	fmt.Println("[INFO] sdkmanager " + strings.Join(args, " "))
	cmd := exec.Command(sdkManagerBin, args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// adbVersion returns the version reported by the given adb binary.
func adbVersion(adbBin string) string {
	out, err := exec.Command(adbBin, "version").Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "Version ") {
			return strings.TrimPrefix(line, "Version ")
		}
	}
	return ""
}

// checkPlatformTools verifies that Android platform-tools (adb) are available,
// installing them into the SDK if missing.
func checkPlatformTools(sdkRoot, sdkManagerBin string) {
	adbName := "adb"
	if runtime.GOOS == "windows" {
		adbName = "adb.exe"
	}

	if adbBin := utils.Which("adb"); adbBin != "" {
		fmt.Printf("[check] adb (platform-tools): found (version %s)\n", orUnknown(adbVersion(adbBin)))
		return
	}
	if adbInSdk := filepath.Join(sdkRoot, "platform-tools", adbName); isExecutable(adbInSdk) {
		fmt.Printf("[check] adb (platform-tools): found (version %s)\n", orUnknown(adbVersion(adbInSdk)))
		prependPath(filepath.Join(sdkRoot, "platform-tools"))
		return
	}

	fmt.Println("[check] adb (platform-tools): missing")
	if err := runSdkManager(sdkManagerBin, "platform-tools"); err != nil {
		utils.Fail("Failed to install platform-tools: %v", err)
	}
	platformToolsDir := filepath.Join(sdkRoot, "platform-tools")
	prependPath(platformToolsDir)
	fmt.Printf("[install] platform-tools: installed (version %s)\n",
		orUnknown(adbVersion(filepath.Join(platformToolsDir, adbName))))
}

// installedBuildTools lists the build-tools versions installed in the SDK root.
func installedBuildTools(sdkRoot string) []string {
	var versions []string
	entries, err := os.ReadDir(filepath.Join(sdkRoot, "build-tools"))
	if err != nil {
		return versions
	}
	for _, entry := range entries {
		if entry.IsDir() {
			versions = append(versions, entry.Name())
		}
	}
	return versions
}

// latestBuildToolsVersion queries the SDK repository for the newest build-tools
// version whose major version matches targetSdk (e.g. "37.x.y"). It returns an
// empty string if the list cannot be obtained.
func latestBuildToolsVersion(sdkManagerBin, targetSdk string) string {
	out, err := exec.Command(sdkManagerBin, "--list").Output()
	if err != nil {
		return ""
	}
	prefix := "build-tools;"
	latest := ""
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, prefix) {
			continue
		}
		version := strings.TrimPrefix(line, prefix)
		if end := strings.IndexAny(version, " |\t"); end >= 0 {
			version = version[:end]
		}
		// Only consider versions whose major matches the target SDK.
		if !strings.HasPrefix(version, targetSdk+".") {
			continue
		}
		if parseVersion(version) == nil {
			continue
		}
		if latest == "" || versionLess(latest, version) {
			latest = version
		}
	}
	return latest
}

// checkBuildTools verifies that Android SDK build-tools matching the target SDK
// version are available, installing the latest matching version if missing.
func checkBuildTools(sdkRoot, sdkManagerBin, targetSdk string) {
	if aapt2Bin := utils.Which("aapt2"); aapt2Bin != "" {
		version := filepath.Base(filepath.Dir(aapt2Bin))
		if strings.HasPrefix(version, targetSdk+".") {
			fmt.Printf("[check] aapt2 (build-tools): found (version %s)\n", version)
			return
		}
	}

	// Look for a build-tools directory matching the target SDK in the SDK root.
	best := ""
	for _, version := range installedBuildTools(sdkRoot) {
		if strings.HasPrefix(version, targetSdk+".") && (best == "" || versionLess(best, version)) {
			best = version
		}
	}
	if best != "" {
		fmt.Printf("[check] build-tools: found (version %s)\n", best)
		prependPath(filepath.Join(sdkRoot, "build-tools", best))
		return
	}

	fmt.Printf("[check] build-tools: missing (required %s.x)\n", targetSdk)
	version := latestBuildToolsVersion(sdkManagerBin, targetSdk)
	if version == "" {
		version = targetSdk + ".0.0"
		fmt.Printf("Warning: cannot determine the latest build-tools version, falling back to %s\n", version)
	}
	if err := runSdkManager(sdkManagerBin, "build-tools;"+version); err != nil {
		utils.Fail("Failed to install build-tools %s: %v", version, err)
	}
	prependPath(filepath.Join(sdkRoot, "build-tools", version))
	fmt.Printf("[install] build-tools: installed (version %s)\n", version)
}

// checkNdk verifies that the Android NDK is available at the required version,
// installing it into the SDK if missing. localNdkDir is the ndk.dir property
// from local.properties and is checked before downloading anything.
func checkNdk(sdkRoot, sdkManagerBin, ndkVersion, localNdkDir string) {
	if ndkBuildBin := utils.Which("ndk-build"); ndkBuildBin != "" {
		installedVersion, verr := readSourcePropertiesRevision(filepath.Dir(ndkBuildBin))
		if verr == nil && !versionLess(installedVersion, ndkVersion) {
			fmt.Printf("[check] ndk-build (NDK): found (version %s)\n", installedVersion)
			return
		}
		fmt.Printf("[check] ndk-build (NDK): found but too old (version %s, required %s)\n",
			orUnknown(installedVersion), ndkVersion)
	} else {
		fmt.Println("[check] ndk-build (NDK): missing")
	}

	// Fall back to the ndk.dir property from local.properties, but only if it
	// points to an existing directory before considering a download.
	if localNdkDir != "" {
		ndkBuildName := "ndk-build"
		if runtime.GOOS == "windows" {
			ndkBuildName = "ndk-build.cmd"
		}
		if isExecutable(filepath.Join(localNdkDir, ndkBuildName)) {
			installedVersion, verr := readSourcePropertiesRevision(localNdkDir)
			if verr == nil && !versionLess(installedVersion, ndkVersion) {
				fmt.Printf("[check] NDK in ndk.dir: found (version %s)\n", installedVersion)
				prependPath(localNdkDir)
				return
			}
			fmt.Printf("[check] NDK in ndk.dir: found but too old (version %s, required %s)\n",
				orUnknown(installedVersion), ndkVersion)
		} else {
			fmt.Printf("[check] ndk.dir %s does not exist, ignoring\n", localNdkDir)
		}
	}

	ndkDir := filepath.Join(sdkRoot, "ndk", ndkVersion)
	if _, err := os.Stat(filepath.Join(ndkDir, "source.properties")); err == nil {
		fmt.Printf("[check] NDK: found (version %s)\n", ndkVersion)
		prependPath(ndkDir)
		return
	}
	if err := runSdkManager(sdkManagerBin, "ndk;"+ndkVersion); err != nil {
		utils.Fail("Failed to install NDK %s: %v", ndkVersion, err)
	}
	prependPath(ndkDir)
	fmt.Printf("[install] NDK: installed (version %s)\n", ndkVersion)
}

func main() {
	// Parse flags.
	toolsDirFlag := flag.String("tools-dir", "", "override prerequisite tools directory")
	flag.Parse()

	// Check supported platform.
	supported := map[string]bool{"windows": true, "linux": true, "darwin": true}
	if !supported[runtime.GOOS] {
		utils.Fail("Unsupported OS %s. Supported: windows, linux, darwin", runtime.GOOS)
	}

	// Determine the prerequisite tools directory.
	var prerequisiteToolsDir string
	if *toolsDirFlag != "" {
		prerequisiteToolsDir = *toolsDirFlag
	} else {
		exePath, err := os.Getwd()
		if err != nil {
			utils.Fail("Cannot get executable path: %v", err)
		}
		exePath, err = filepath.EvalSymlinks(exePath)
		if err != nil {
			utils.Fail("Cannot resolve executable symlinks: %v", err)
		}
		exeDir := filepath.Dir(exePath)

		// Expect binary to be in scripts/ (like the Python script).
		// Navigate up two levels and into syncthing-android-prereq.
		prerequisiteToolsDir = filepath.Join(exeDir, "prereq")
		prerequisiteToolsDir, err = filepath.Abs(prerequisiteToolsDir)
		if err != nil {
			utils.Fail("Cannot get absolute path for tools dir: %v", err)
		}
	}
	fmt.Println("Prerequisite tools directory:", prerequisiteToolsDir)

	// Read the required versions from the Gradle version catalog.
	cwd, err := os.Getwd()
	if err != nil {
		utils.Fail("cannot get working directory: %v", err)
	}
	root, err := findRepoRoot(cwd)
	if err != nil {
		utils.Fail("%v", err)
	}
	cmdlineToolsVersion := getTomlValue(root, "android-cmdline-tools")
	ndkVersion := getTomlValue(root, "ndk-version")
	targetSdk := getTomlValue(root, "target-sdk")

	// Read the SDK and NDK locations from local.properties (if present).
	localSdkDir := readLocalProperty(root, "sdk.dir")
	localNdkDir := readLocalProperty(root, "ndk.dir")

	// Resolve the SDK root, preferring an existing ANDROID_HOME, then the
	// sdk.dir property from local.properties, and finally the prerequisite
	// tools directory. Only use a candidate if it points to an existing
	// directory before downloading anything.
	sdkRoot := os.Getenv("ANDROID_HOME")
	if sdkRoot == "" {
		sdkRoot = localSdkDir
	}
	if sdkRoot == "" {
		sdkRoot = prerequisiteToolsDir
	}
	if info, err := os.Stat(sdkRoot); err != nil || !info.IsDir() {
		fmt.Printf("Warning: SDK root %s does not exist, falling back to %s\n", sdkRoot, prerequisiteToolsDir)
		sdkRoot = prerequisiteToolsDir
	}
	ensureAndroidEnv(sdkRoot)

	// Verify or install sdkmanager (cmdline-tools). Check the PATH first, then
	// the sdk.dir from local.properties if it contains a usable cmdline-tools
	// installation, before downloading.
	sdkManagerBin := utils.Which("sdkmanager")
	if sdkManagerBin == "" {
		sdkManagerBin = sdkManagerFromDir(localSdkDir)
		if sdkManagerBin != "" {
			fmt.Println("[check] sdkmanager (cmdline-tools): found in sdk.dir")
		}
	}
	if sdkManagerBin != "" {
		installedVersion, verr := readSourcePropertiesRevision(filepath.Dir(filepath.Dir(sdkManagerBin)))
		if verr != nil || versionLess(installedVersion, cmdlineToolsVersion) {
			fmt.Printf("[check] sdkmanager (cmdline-tools): found but too old (version %s, required %s)\n",
				orUnknown(installedVersion), cmdlineToolsVersion)
			installSdkTools(prerequisiteToolsDir, cmdlineToolsVersion)
			sdkManagerBin = utils.Which("sdkmanager")
		} else {
			fmt.Printf("[check] sdkmanager (cmdline-tools): found (version %s)\n", installedVersion)
		}
	} else {
		fmt.Println("[check] sdkmanager (cmdline-tools): missing")
		installSdkTools(prerequisiteToolsDir, cmdlineToolsVersion)
		sdkManagerBin = utils.Which("sdkmanager")
	}
	if sdkManagerBin == "" {
		utils.Fail("Error: sdkmanager from sdk-tools package is not available on PATH.")
	}
	fmt.Println("sdkmanager binary:", sdkManagerBin)

	// Update SDK repository.
	if err := runSdkManager(sdkManagerBin, "--update"); err != nil {
		utils.Fail("sdkmanager --update failed: %v", err)
	}

	// Accept licenses.
	if runtime.GOOS == "windows" {
		powershellBin := utils.Which("powershell")
		if powershellBin == "" {
			utils.Fail("powershell not found")
		}
		psCmd := `for($i=0;$i -lt 50;$i++) { $response += "y` + "`n" + `"}; $response | sdkmanager --licenses`
		cmd := exec.Command(powershellBin, "-Command", psCmd)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			utils.Fail("sdkmanager --licenses failed: %v", err)
		}
	} else {
		fmt.Println("[INFO] yes | sdkmanager --licenses")
		// Use sh to pipe yes.
		yesCmd := exec.Command("sh", "-c", "yes | sdkmanager --licenses")
		yesCmd.Stdout = os.Stdout
		yesCmd.Stderr = os.Stderr
		if err := yesCmd.Run(); err != nil {
			// The original script uses os.system and does not fail on error.
			// We print a warning but do not exit.
			fmt.Fprintf(os.Stderr, "Warning: sdkmanager --licenses returned an error: %v\n", err)
		}
	}

	// Verify/install the remaining Android build toolchain components.
	checkPlatformTools(sdkRoot, sdkManagerBin)
	checkBuildTools(sdkRoot, sdkManagerBin, targetSdk)
	checkNdk(sdkRoot, sdkManagerBin, ndkVersion, localNdkDir)

	fmt.Println("Done.")
}

// findRepoRoot walks up from start locating the directory containing
// gradle/libs.versions.toml.
func findRepoRoot(start string) (string, error) {
	for dir := start; ; dir = filepath.Dir(dir) {
		if isFile(filepath.Join(dir, "gradle", "libs.versions.toml")) {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("cannot locate project root containing "+
				"gradle/libs.versions.toml from %s", start)
		}
	}
}

func isFile(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.Mode().IsRegular()
}
