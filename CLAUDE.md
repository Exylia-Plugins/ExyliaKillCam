# ExyliaKillCam

The seconds before a death, watched again through the killer's eyes. Free and open source (GPL-3.0), public at `Exylia-Plugins/ExyliaKillCam`, mirrored to Modrinth.

## Releasing

- Every finished change is released, without asking and without `/exylia-release`: bump `version` in `build.gradle` (patch for fixes, minor for features, major for breaking commands or config), commit `chore: release <version>`, tag that commit `v<version>`, and push the branch and the tag.
- The release workflow runs only on `v*` tags and expects the tag to match the `build.gradle` version; it publishes the GitHub release, the fixed-name `ExyliaKillCam.jar` that servers update from, and the Modrinth version (`MODRINTH_PROJECT_ID` repo variable). A push to `main` without a tag only builds.

## ExyliaLib

- Always the newest: `build.gradle` resolves the latest GitHub release (or a newer one in mavenLocal) at build time, and that version becomes plugin.yml's `exylia-lib`. `-PexyliaLibVersion` pins one for a single build.
- `ExyliaKillCamPlugin` is the Bukkit entry point and touches no ExyliaLib type; without ExyliaLib, `bootstrap/ExyliaLibMissing` logs where to download it and the plugin stays off.
- The killcam is ExyliaLib's replay module end to end: the black box captures, `Replays.stage` lays the ground on the temporary world, `ReplayViewer` moves and restores the viewer. Fix replay behaviour in the lib, never here.
- ExyliaKillEffect is reached only through `KillEffectService` and `KillEffectPlayEvent` in the Exylia API; `KillEffectService.show` (Exylia API 3.3.0, ExyliaKillEffect 1.9.0) draws the effect for the viewer alone.
- Other plugins are respected through `Sessions` and `DeathDrops`: a held player gets no killcam, and a viewer is held by the claim `killcam`.
