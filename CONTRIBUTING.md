# Contributing

Thanks for your interest in contributing. This project is a wrapper of
[Syncthing Android](https://github.com/syncthing/syncthing) for Android, forked from
[researchxxl/syncthing-android](https://github.com/researchxxl/syncthing-android), which was forked
from [Syncthing/syncthing-android](https://github.com/syncthing/syncthing-android).

## Getting help first

Before opening an issue, please check the [wiki](../wiki) and seek help on the
forum or social media. See [SUPPORT.md](SUPPORT.md).

## Reporting bugs

- Search the [issue tracker](https://github.com/Micnubinub/syncthing-android/issues) for existing
  reports.
- Include app version, Android version, and device model.
- Attach logs from the in-app "View logs" screen, with any personal data removed.
- One issue per report.

## Suggesting features

Explain the problem you are trying to solve and why the current behavior is
not sufficient. Small, focused proposals get discussed and merged faster than
large ones.

## Development setup

See the [building and development notes](../wiki#readme) in the wiki. In short:

- Kotlin and Jetpack Compose are used for the wrapper.
- NDK and SDK must be in `$PATH` or declared in `local.properties`.
- Build with `./gradlew assembleDebug`, test with `./gradlew testDebugUnitTest`.

## Pull requests

- Keep changes small and focused on one thing.
- Match the existing code style and follow the guidelines in [AGENTS.md](AGENTS.md).
- Do not bump version numbers or add dependencies unless required.
- Reference the issue your change addresses.
- Title the PR with a concise summary of the change.
- Include a brief description of what changed and why.
- Attach screenshots or screen recordings for UI changes.
- All CI checks must pass; fix lint warnings your change introduced.
- Maintainers may squash-merge to keep history clean.

## Branches and commits

- Create a topic branch from `main` (or the default branch) for each change.
- Use imperative mood in the commit subject line (e.g. "Add folder picker").
- Reference the issue number in the commit body (e.g. `Fixes #123`).
- Keep commits atomic — one logical change per commit.

## Releases

Version numbers and codes are defined in `gradle/libs.versions.toml`.
Run `./gradlew :syncthing:release` and follow the prompts. This creates and pushes an annotated
tag; CI builds the release artifacts. Do not manually edit `versionCode` or `versionName` without
updating both fields consistently.

## Translations

Translations are handled on [Weblate](https://hosted.weblate.org/projects/syncthing-fork/app/).
Please contribute translations there rather than editing string resources directly.

## License

By contributing, you agree that your work is licensed under the
[MPLv2](LICENSE). No DCO sign-off or CLA is required — submitting a pull
request is sufficient.
