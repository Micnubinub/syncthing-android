package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"syncthing.sh/scripts/utils"
)

const (
	targetPath = "syncthing/src/github.com/syncthing/syncthing"
	remoteURL  = "https://github.com/syncthing/syncthing.git"
)

var versionNameRe = regexp.MustCompile(`^\s*version-name\s*=\s*"([0-9]+(?:\.[0-9]+)*)"`)

func main() {
	if utils.Which("git") == "" {
		utils.Fail("git is not available on the PATH")
	}

	cwd, err := os.Getwd()
	if err != nil {
		utils.Fail("cannot get working directory: %v", err)
	}
	root, err := findRepoRoot(cwd)
	if err != nil {
		utils.Fail("%v", err)
	}
	versionsFile := filepath.Join(root, "gradle", "libs.versions.toml")
	targetDir := filepath.Join(root, filepath.FromSlash(targetPath))

	versionName, err := readVersionName(versionsFile)
	if err != nil {
		utils.Fail("%v", err)
	}
	tag := "v" + versionName
	if i := strings.LastIndex(versionName, "."); i >= 0 {
		tag = "v" + versionName[:i]
	}
	fmt.Println("Installing release:", tag)

	if b, err := os.ReadFile(filepath.Join(targetDir, ".version")); err == nil &&
		strings.TrimSpace(string(b)) == tag {
		fmt.Printf("Already vendored syncthing %s; skipping clone\n", tag)
		return
	}

	// Clone into a temp dir first so a failed clone leaves the target
	// directory untouched.
	parentDir := filepath.Dir(targetDir)
	if err := os.MkdirAll(parentDir, 0o755); err != nil {
		utils.Fail("cannot create %s: %v", parentDir, err)
	}
	tmpDir, err := os.MkdirTemp(parentDir, ".syncthing-clone.")
	if err != nil {
		utils.Fail("cannot create temp dir: %v", err)
	}
	cleanup := true
	defer func() {
		if cleanup {
			os.RemoveAll(tmpDir)
		}
	}()

	if err := utils.Run(root, "git", "clone", "--depth", "1", "--branch", tag,
		"--single-branch", remoteURL, tmpDir); err != nil {
		utils.Fail("clone failed: %v", err)
	}

	// Drop the cloned repo's git metadata so the sources are vendored.
	if err := os.RemoveAll(filepath.Join(tmpDir, ".git")); err != nil {
		utils.Fail("cannot remove vendored .git: %v", err)
	}

	// Record which version was vendored.
	if err := os.WriteFile(filepath.Join(tmpDir, ".version"),
		[]byte(tag+"\n"), 0o644); err != nil {
		utils.Fail("cannot write .version: %v", err)
	}

	if err := os.RemoveAll(targetDir); err != nil {
		utils.Fail("cannot remove old vendored sources: %v", err)
	}
	if err := os.Rename(tmpDir, targetDir); err != nil {
		utils.Fail("cannot move sources into place: %v", err)
	}
	cleanup = false

	fmt.Printf("Vendored syncthing %s into %s\n", tag, targetPath)
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

func readVersionName(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("versions file not found: %s", path)
	}
	for _, line := range strings.Split(string(data), "\n") {
		if m := versionNameRe.FindStringSubmatch(line); m != nil {
			return m[1], nil
		}
	}
	return "", fmt.Errorf("could not read version-name from %s", path)
}
