# Smart Screenshots — notes for agents

## Versioning (every build that goes to the player gets a new version)

Version lives in `gradle.properties` (`version=MAJOR.MINOR.PATCH`); the jar and `fabric.mod.json`
get `+mc<minecraft_version>` appended (e.g. `smart-screenshots-1.1.0+mc26.3.jar`).

- **PATCH** (1.1.0 → 1.1.1): bug fixes, tweaks, text/visual polish; no new settings or data.
- **MINOR** (1.1.x → 1.2.0): new features, settings or metadata fields that stay backward compatible
  (old screenshots and configs still load). Resets PATCH to 0.
- **MAJOR** (1.x → 2.0.0): incompatible changes to stored data (`.smartshots.json`, masks,
  folder layout) that need migration, or dropping a Minecraft version. Resets MINOR and PATCH.
- Several changes in one build: bump by the largest one only.
- Bump before building a jar for the player, and add a line to `CHANGELOG.md`.
- Commit every version without being asked: once the jar for a new version is built and deployed, commit
  everything on `main` with the message `v<version>: <short summary>` and tag it `v<version>` (no push unless asked).

## Build & deploy

- Java 25 is needed: `JAVA_HOME=/nix/store/59hn3lxp64cgh3498l98bja99jcgn1q6-openjdk-25.0.5.0.3+1/lib/openjdk ./gradlew build`
- Jar: `build/libs/smart-screenshots-<version>.jar` (not the `-sources` one).
- Test instance: Prism Launcher instance `freeze`,
  `~/.local/share/PrismLauncher/instances/freeze/minecraft/mods/`. Remove the previous
  `smart-screenshots-*.jar` there before copying the new one (two copies = crash).
