package main

import (
	"os"
	"strings"
	"syncthing.sh/scripts/utils"
)

func main() {
	args := os.Args[1:]
	versionArg := ""
	messageArg := ""
	changelogArg := ""

	if len(args) > 0 {
		versionArg = args[0]
	}
	if len(args) > 1 {
		messageArg = args[1]
	}
	if len(args) > 2 {
		changelogArg = args[2]
	}

	version := utils.Ask(versionArg, "Version (e.g. v1.2.3): ")
	message := utils.Ask(messageArg, "Title: ")
	changelog := utils.Ask(changelogArg, "Changelog: ")
	changelog = strings.ReplaceAll(changelog, "\\n", "\n")

	if version == "" || message == "" || changelog == "" {
		utils.Fail("Version, title, and changelog are required.")
	}

	branch := utils.RunGit([]string{"rev-parse", "--abbrev-ref", "HEAD"}, true)
	if branch != "main" {
		utils.Fail("Releases must be pushed from main (currently on %q).", branch)
	}

	dirty := utils.RunGit([]string{"status", "--porcelain"}, true)
	if dirty != "" {
		utils.Fail("Working tree has uncommitted changes. Commit or stash first.")
	}

    if !strings.HasPrefix(version, "v") {
        version = "v" + version
    }

    existing := utils.RunGit([]string{"tag", "-l", version}, true)
    if strings.TrimSpace(existing) != "" {
        utils.Fail("Tag %s already exists.", version)
    }

	utils.RunGit([]string{"tag", "-a", version, "-m", message, "-m", changelog}, false)
	utils.RunGit([]string{"push", "origin", version}, false)
}
