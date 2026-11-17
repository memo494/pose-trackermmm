# Save diagnostics to the device (plan)

**Lead:** main session · **Branch:** `diag-save` · approved in advance (owner, 2026-11-15 rules).
**Why:** owner, 2026-11-17: the share sheet of **Share diagnostics** has no option to save the file on the phone
(so the zip could not be kept and sent later).

## T1 — Save diagnostics · S
- Control screen: a second button **Save diagnostics** next to (below) **Share diagnostics**, same section,
  same enabled rules.
- It opens the system "save as" dialog (`ActivityResultContracts.CreateDocument("application/zip")`, works on
  minSdk 26, no storage permission) with the suggested name `pose-tracker-diagnostics-<yyyyMMdd-HHmmss>.zip`.
- When the owner picks a place: build the zip exactly as for sharing (`DiagnosticsZip.write`) on a background
  thread (as `shareDiagnostics` does today), copy it to the returned `Uri` through `contentResolver
  .openOutputStream`, then show a short notice "Saved" or, on any failure (`Throwable`, incl. `null` stream),
  the existing failure notice style ("Could not save diagnostics") and a log line. Cancel = nothing happens.
  Never on the main thread; never crash (AGENTS rule 2).
- Strings in `strings.xml`; no new dependency (`androidx.activity` is already used).
- Tests (JUnit 4): the file-name builder (pure, fixed clock); the copy helper (pure: input stream → output
  stream, returns false on an exception or a `null` stream) with fakes; existing tests unchanged.
- Docs: ARCHITECTURE §6.7 one line; TEST_CHECKLIST "What to send back" says Save or Share.

## Acceptance on the phone
Press **Save diagnostics**, choose Downloads, Save → a notice "Saved"; the zip is in Downloads (Files app) and
opens; it has `summary.txt`.

## Progress
| Task | Status | Commit | Notes |
|---|---|---|---|
| T1 | — | | |
