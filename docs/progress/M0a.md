# M0a — bootstrap (handoff)

**Status:** done locally — `scripts/verify.sh` green (`spotlessCheck test lint assembleDebug`),
debug APK builds (~11.6 MB), `@Test` count = 5. CI runs on the PR.

## Done
- **Modules** (ARCHITECTURE §3): `core` (Kotlin JVM), `detector` (Android library), `app` (Compose).
  Deps `app → detector → core`. Placeholder HUD screen (version + git SHA); one passing test each
  (`CoreMathTest` ×3, `AcceleratorTest`, `BuildLabelTest`).
- **Version catalog** `gradle/libs.versions.toml` (pinned, verified from Google Maven / Maven Central).
- **Gradle wrapper** 8.14.3, committed + validated in CI.
- **AI team**: `.claude/settings.json`, `.claude/agents/*` (implementer, android-reviewer, debugger,
  consultant, Explore), `CLAUDE.md`, `AGENTS.md`; `scripts/verify.sh`, `scripts/ci-status.sh`.
- **Skills**: 16 vendored in `.claude/skills/` with licences + `SOURCES.md` (pinned commits).
- **CI** `.github/workflows/android.yml`: spotless/test/lint/assemble, `@Test`-count guard, APK
  artifact, rolling `latest` release on `main`. Debug keystore committed (`signing/debug.keystore`).
- **SDK setup** `scripts/cloud-setup.sh` (installs Android SDK; run locally, proven `./gradlew` works).
- **Model export** `tools/export_model.py` + `tools/requirements.txt` + `.github/workflows/export-model.yml`
  (workflow_dispatch) — created, runs after this PR merges.
- **Docs** under `docs/`: ARCHITECTURE, AI_TEAM, BUILD_PLAN, REPO_MAP (this folder = progress).

## Key decisions
- **compileSdk 36 (Android 16), targetSdk 35 (Android 15 device).** Current AndroidX requires it.
- **Stayed on AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.4.21** (ARCHITECTURE §2 era) rather than jumping to
  AGP 9. So Compose/AndroidX are pinned to the **API-36 wave** (Compose BOM 2026.06.01, activity 1.12.4,
  lifecycle 2.10.0, core-ktx 1.18.0); the API-37 wave (BOM 2026.08+, AGP 9) is deferred — bump together.
- Versions: Compose BOM 2026.06.01 · LiteRT 2.3.2 (+gpu, pinned for M0b/M1) · CameraX 1.6.2 (pinned) ·
  JUnit 6.1.3 (Jupiter, core) / JUnit 4.13.2 (Android) · Truth 1.4.5 · Spotless 8.11.0 + ktlint 1.8.0.
- `.editorconfig` ignores ktlint `function-naming` for `@Composable` (PascalCase by convention).
- `verify.sh` retries transient Maven Central **429s** through the cloud proxy (seen repeatedly).

## Scope of this session's work
This session worked only on the **camera-based tracker described in `ARCHITECTURE.md`**: frames from
the device's own camera (CameraX) and a drawn overlay (dots, track IDs, lock ring). Display only.
The earlier note in this file about a screen-capture overlay over other apps is **withdrawn by its
author**. Capturing other apps' screens and injecting input (pointer or touch control) were excluded
from this session's work: the lead declined a request to add pointer snapping and input injection
over other apps, and stopped work on the project at that point (see "Where this session stopped").

## Open items
1. **Model export follow-up (this branch, not merged, no PR).** `tools/export_model.py` was reworked:
   the LiteRT export needs `litert-torch` + `ai-edge-litert` (not TensorFlow); the goldens are now the
   raw `(1, 300, 57)` output plus a JSON of the detections, checked against `predict()` inside the
   script; the fixture is a public-domain NASA photo; `export-model.yml` pushes the `model-export`
   branch instead of opening a pull request. It ran once from this branch and succeeded, and its
   result is the current tip of `model-export` (that run overwrote the branch; its previous tip was
   already merged). Meanwhile `main` changed through pull requests made outside this session (#2 and
   #4; #4 added an earlier export), so this branch needs rebasing before any merge.
2. Gradle prints a deprecation warning (8.14.3 → min 8.14.4 for Kotlin 2.5). Harmless on Kotlin 2.4.21;
   bump the wrapper when moving to Kotlin 2.5.

## Cloud environment
Done: the personal cloud environment **`android`** exists (limited network: package managers plus
`dl.google.com`, the Gradle hosts and `github.com`; its setup script runs `scripts/cloud-setup.sh`,
which installs the Android SDK under `$HOME/android-sdk` and writes `local.properties`). Start
sessions for this repo on that environment. The setup script was run by hand from the checkouts'
parent directory and worked; its first automatic run happens with the next fresh session.

## Where this session stopped
After the export run above. No M0b plan was written and nothing from this branch was merged or
proposed as a pull request. The session did not open or change the remote branches `expert-doodle`,
`fix-model-export` and `m0b-screen-overlay`.
