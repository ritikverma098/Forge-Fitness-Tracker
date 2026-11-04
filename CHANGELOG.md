# Changelog

## Unreleased

### Added

- Check for stable Forge updates on GitHub at startup and from Settings. Automatic checks run at most once per 24 hours.
- Read release notes, choose Later or Skip This Version, and open the official APK link through Android. Prompts wait until workouts and backup operations are out of the way.

## 1.3.2 — 2026-11-01

### Fixed

- Recover unacknowledged workout field assignments after process recreation, preserving exact canonical weights and reliable Finish/Retry behavior.
- Preserve the gram/mL nutrient basis and label servings of branded USDA foods. Correct the known retired bundled catalog without rewriting historical diary/recipe snapshots or replacing independently imported revisions.
- Prevent a delayed ±15 adjustment from recreating a consumed or skipped rest timer.
- Reject inconsistent native-unit nutrition quantities and factors before restoring a backup, while preserving historical named-portion compatibility.
- Complete a pending timer claim independently of receiver/foreground cancellation. Retry the original deadline through a separate bounded Android alarm after transient delivery failure.
- Recover interrupted restores coherently across Room and DataStore using a durable internal journal and shared startup recovery. Failed recovery retains its instructions and offers Retry.

### Backup policy and compatibility

- Explicitly support portable local backup files; disable unsupported Android automatic backup/device transfer of the catalog-bearing database. Settings explains the policy.
- Version code 7; Room schema 4, existing migrations and portable backup format 3 remain unchanged. No new dependencies or permissions.
- R-03/R-04 performance risks remain deferred pending measurements. No architecture, navigation-animation or feature redesign.
- Verification: 601 unit/Robolectric tests, 47 Android functional tests and 10 importer tests passed, plus debug/test and optimized unsigned builds and lint. Native fault probes verified process-death restore recovery and timer delivery; physical-phone/OEM timing and interaction feel still need checking.
- Verification details and remaining device limitations: [1.3.2 reliability report](docs/reliability-1.3.2.md).

### Distribution

- The downloadable APK is an optimized release build signed with the existing development/debug key for sideload testing. Its verified certificate matches 1.3.1; it is not a production Play Store key. Source builds keep `assembleRelease` unsigned, and signing credentials remain outside the repository.
- The signed optimized APK installed over the existing emulator app without clearing data. Home, active set values/completion, History, Routines and selected settings matched the captured baseline; About showed 1.3.2. Scoped Logcat had no fatal exception, ANR or SQLite error. The 47 functional regressions used the debug build.
- Android automatic cloud backup and device transfer are unsupported. Create a portable local backup in Settings before reinstalling or moving devices.

## 1.3.1 — 2026-10-29

### Fixed

- Restored workout weight fields use the selected kg/lb unit correctly. Pending valid edits retain their exact weight across unit changes, focus changes and saved-tab restoration without saving rounded conversions.
- Clearing a completed set's quantity and then unchecking Done persists zero instead of retaining stale repetitions, duration or distance.
- Failed workout saves remain unresolved until a correction or explicit Retry succeeds. Repeated Finish cannot bypass an unsaved change; successful operations are not replayed. Selecting an exercise already present does not duplicate it or block Finish.
- Automatic food catalog initialization preserves a complete explicitly imported revision, including packs with arbitrary version labels. Incomplete external packs remain intact and show reimport guidance.
- Backup validation follows SQLite's stored exercise-name uniqueness rules, allowing valid existing Unicode names to round-trip without renaming or deleting data.
- Restore rejects unsupported extreme row IDs before changing data. Valid restores transactionally repair exhausted AUTOINCREMENT sequences while preserving IDs, relationships, retained reference foods and the scope of older workout-only backups. Failed restores roll back sequence repairs with the data.

### Compatibility and verification

- Version code 6; Room schema 4 and backup format 3 remain unchanged. No new permissions, dependencies or destructive migrations.
- 513 unit/Robolectric tests, 14 Android 17 emulator tests and 6 catalog pipeline tests passed. Debug/test and optimized release builds and lint passed; installed emulator data was preserved.
- Physical-phone checks remain necessary for scrolling feel, Back transitions and background/lock-screen rest-alert timing.
- Numeric backup row IDs support 1 through 9,007,199,254,740,991. A hand-edited import at this ceiling leaves no portable ID capacity for subsequent records in that table, although SQLite can allocate larger values. Ordinary generated IDs are far below the ceiling.

### Distribution

- The downloadable APK is an optimized release build signed with the existing development/debug key for sideload testing. It is not signed with a production Play Store key. Normal source builds keep `assembleRelease` unsigned; no signing credentials are published.
- Existing screenshots illustrate the unchanged Forge 1.3.0 layouts.

## 1.3.0 — 2026-10-28

- Published the complete native Android Studio workout and nutrition tracker, with offline Room storage, routines, progress, food logging and local backup/restore.
- Corrected Home navigation after starting a routine and kept catalog import progress/Cancel accessible while initial reads wait.
- Reduced large workout composition stalls with individually keyed lazy headers and set rows.
- Retained the bottom navigation bar during animated/predictive Back and updated Android 17 test compatibility.
- Verified 463 unit/Robolectric tests, debug/instrumentation and optimized application builds, lint, and focused emulator workflows.
