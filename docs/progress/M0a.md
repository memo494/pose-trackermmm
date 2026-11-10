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

## ⚠️ Product direction — resolve before M0b
The owner clarified the product is a **screen-capture overlay over other apps** (MediaProjection),
**not** a camera-preview app. M0a tooling is frame-source-agnostic, so nothing here is blocked, but
ARCHITECTURE needs revising in M0b: **frame source = screen capture (not CameraX §6.8), UI = overlay
service (not a Compose camera screen), and §4 coordinate spaces simplify.** The detection + tracking +
smoothing + lock core (§6.1–§6.6) and the AI-team/CI workflow are unchanged. CameraX stays pinned in
the catalog but is likely dropped. **Decide the M0b architecture edit with the owner first.**

## Open items
1. **Model export not run yet** — `export-model.yml` is `workflow_dispatch`, which only works once it is
   on `main`. After merging this PR: trigger it, review the PR it opens, and record the exact input/output
   tensor shapes (needed for the M1 golden decode test). The `yolo26n-pose` model name is taken from the
   architecture and is verified only when the workflow runs.
2. Gradle prints a deprecation warning (8.14.3 → min 8.14.4 for Kotlin 2.5). Harmless on Kotlin 2.4.21;
   bump the wrapper when moving to Kotlin 2.5.

## Owner: cloud environment "Setup script"
The env must allow `dl.google.com` (BUILD_PLAN §1.2). For the **Setup script** field, paste the full
contents of `scripts/cloud-setup.sh` (it installs the Android SDK under `$HOME/android-sdk`, accepts
licences, sets the Gradle proxy, and exports `ANDROID_HOME`). It is idempotent and finishes in a few
minutes.

## Next step
Merge this PR → trigger `export-model` (open item 1) → start **M0b** with the architecture decision above.
