package main

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"

	"syncthing.sh/scripts/utils"
)

const remoteURL = "https://github.com/syncthing/syncthing.git"

var (
	tagRe         = regexp.MustCompile(`^v(\d+)\.(\d+)\.(\d+)$`)
	versionNameRe = regexp.MustCompile(
		`(?m)^version-name = "(\d+\.\d+\.\d+\.\d+)"`,
	)
	versionCodeRe = regexp.MustCompile(`(?m)^version-code = "\d+"`)
)

type semver [3]int

func (a semver) less(b semver) bool {
	for i := range a {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return false
}

func (a semver) String() string {
	return fmt.Sprintf("v%d.%d.%d", a[0], a[1], a[2])
}

func parseTag(s string) (semver, bool) {
	m := tagRe.FindStringSubmatch(s)
	if m == nil {
		return semver{}, false
	}
	var v semver
	for i := range v {
		v[i], _ = strconv.Atoi(m[i+1])
	}
	return v, true
}

func latestRemoteTag() (semver, bool) {
	out, err := exec.Command(
		"git", "ls-remote", "--tags", "--refs", remoteURL,
	).Output()
	if err != nil {
		return semver{}, false
	}
	var best semver
	found := false
	sc := bufio.NewScanner(bytes.NewReader(out))
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 2 {
			continue
		}
		name := fields[1][strings.LastIndex(fields[1], "/")+1:]
		v, ok := parseTag(name)
		if ok && (!found || best.less(v)) {
			best, found = v, true
		}
	}
	return best, found
}

func runInstall(scriptDir string) {
	script := filepath.Join(scriptDir, "installSyncthing.go")
	if _, err := os.Stat(script); err != nil {
		utils.Fail("install script not found: %s", script)
	}
	if err := utils.Run(scriptDir, "go", "run", script); err != nil {
		utils.Fail("install script failed: %v", err)
	}
}


func main() {
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		utils.Fail("could not determine script location")
	}
	scriptDir := filepath.Dir(file)
	repoRoot := filepath.Dir(scriptDir)
	versionsFile := filepath.Join(repoRoot, "gradle", "libs.versions.toml")

	content, err := os.ReadFile(versionsFile)
	if err != nil {
		utils.Fail("versions file not found: %s", versionsFile)
	}

	tag, ok := latestRemoteTag()
	if !ok {
		utils.Fail("could not determine latest release tag")
	}
	fmt.Printf("Latest release: %s\n", tag)

	m := versionNameRe.FindSubmatch(content)
	if m == nil {
		utils.Fail("could not read version-name (expected x.y.z.b) from %s",
			versionsFile)
	}
	currentVersion := string(m[1])
	currentTag, _ := parseTag(
		"v" + currentVersion[:strings.LastIndex(currentVersion, ".")],
	)

	if !currentTag.less(tag) {
		versionFile := filepath.Join(repoRoot, "syncthing", "src",
			"github.com", "syncthing", "syncthing", ".version")
		if _, err := os.Stat(versionFile); err != nil {
			fmt.Println("Vendored sources missing; running install...")
			runInstall(scriptDir)
		} else {
			fmt.Printf("Already at latest version (%s), nothing to do.\n",
				currentTag)
		}
		return
	}
	fmt.Printf("New version available: %s -> %s\n", currentTag, tag)

	newVersion := fmt.Sprintf("%d.%d.%d.0", tag[0], tag[1], tag[2])
	versionCode := tag[0]*1000000 + tag[1]*10000 + tag[2]*100

	updated := versionNameRe.ReplaceAll(content,
		[]byte(fmt.Sprintf(`version-name = "%s"`, newVersion)))
	updated = versionCodeRe.ReplaceAll(updated,
		[]byte(fmt.Sprintf(`version-code = "%d"`, versionCode)))

	if !bytes.Contains(updated,
		[]byte(fmt.Sprintf(`version-name = "%s"`, newVersion))) ||
		!bytes.Contains(updated,
			[]byte(fmt.Sprintf(`version-code = "%d"`, versionCode))) {
		utils.Fail("failed to update %s", versionsFile)
	}
	if err := os.WriteFile(versionsFile, updated, 0o644); err != nil {
		utils.Fail("failed to write %s: %v", versionsFile, err)
	}

	if err := utils.Run(repoRoot, "git", "add", versionsFile); err != nil {
		utils.Fail("git add failed: %v", err)
	}
	fmt.Printf("Updated version to: %s (%d)\n", newVersion, versionCode)

	runInstall(scriptDir)
}

