# Changelog

## 1.4.3 — Premium UI/UX Refresh

- Introduce Forge Dark across Home, workout logging, exercise library and routines, nutrition, History, Progress, and Settings. Apply the same design system to Light and System themes, including forms, dialogs, empty states and feedback.
- Make active workout and rest timing easier to scan. Refresh set editors, completion controls, exercise categories, workout shortcuts and summaries while keeping saved records and existing logging flows.
- Refresh the nutrition dashboard, food logging and serving forms, catalog pack management, recipes, goals, and body-weight and energy settings. Retain offline catalogs, nutritional snapshots and data-source attribution.
- Offer an optional rest-notification prompt on the first eligible startup on Android 13 and newer. Keep notifications configurable later in Settings and keep precise-alarm special access separate. Android 8–12L use app and channel notification settings.
- Keep `com.forge.workout`, version code 12, Room schema 4, portable backup format 3, the verified GitHub updater and the 1.4.2 production certificate. Production-signed 1.4.2 can update in place. Development-signed builds require the [one-time backup, reinstall and restore](docs/signing.md).
- Refresh the README and GitHub Pages website with genuine app screenshots. [Release verification](docs/release-1.4.3.md)

## 1.4.2 — 2026-11-18

**Signing transition:** 1.4.2 uses a dedicated production release certificate. Existing development-signed installations, including published 1.4.1, require a one-time **verified local backup → uninstall → production install → restore**. Do not uninstall until the backup is stored outside Forge and checked. Optional food packs may need downloading/importing again. See [migration instructions](docs/signing.md).

- Download selected compatible app updates inside Forge with progress, Cancel and Retry. Retain release notes, Later, Skip This Version, manual checks and the 24-hour automatic-check interval.
- Stream validated official GitHub APK assets over approved HTTPS redirects. Require the GitHub API asset SHA-256 digest and exact size, enforce a 128 MiB limit, and verify package identity, increasing version and the complete current signing-certificate set before installation.
- Use Android's PackageInstaller with explicit confirmation. Add only `REQUEST_INSTALL_PACKAGES`; return from source-permission settings to an explicit Install action. Do not interrupt active workouts or backup operations with installation.
- Retain verified downloads for a safe Retry/Discard after installation cancellation. Remove incomplete downloads; reconcile installed versions at startup and clean the private APK/metadata after confirmed installation or 24-hour expiry.
- Separate standard debug signing from private production release signing. Release tasks fail without complete production inputs; CI must explicitly request unsigned verification. Production signing uses a long-term owner-controlled PKCS12/RSA identity with v2/v3 APK signatures, without the legacy debug signer.
- Keep signature equality strict; different signing identities require the documented manual migration. Android 8 cannot use proof-of-rotation, so this release does not claim a seamless signing-lineage transition on supported devices.
- Add read-only GitHub Actions verification for host/Robolectric and importer tests, lint, debug and optimized unsigned release builds. Pin actions and catalog fixture checksums. CI never signs or publishes automatically.
- Keep application ID `com.forge.workout`, version code 11, Room schema 4, portable backup format 3, historical nutrition snapshots and the independently versioned [1.4.0 food-pack assets](https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/tag/v1.4.0).
- Pass 739 host unit/Robolectric tests with no failures/errors/skips, 54 Android 17 instrumentation tests, 21 importer tests, lint with zero issues, and debug/optimized builds. Verify actual development-to-production backup/reinstall/restore on API 26/37, followed by real production-to-production system-installer updates with exact data/catalog preservation and 0 retained update files/bytes. Details are in the [production release report](docs/release-1.4.2.md). Existing small-viewport Android 8 UI-test failures were reproduced against 1.4.1 and remain documented in the [earlier verification record](docs/updater-verification-1.4.2.md).

## 1.4.1 — 2026-11-12

- Reject contradictory recipe backups before changing stored data. Validate serving conversions and nutrition against frozen ingredient totals, preserving unknown nutrients, supported legacy backups and historical diary snapshots.
- Prevent workout edits captured before a backup restore from replaying after Android recreates a stopped Activity/process. Bind saved numeric/text drafts to a durable transactional restore generation while preserving normal autosaving, failed-save Retry and ordinary process-death recovery.
- Reproduce the stale-draft defect twice on the Android 17/API 37 emulator through actual background restore completion and saved-task process recreation. Both corrected trials retained the restored 60 kg value in Room and the UI, with zero obsolete writes; ordinary pending-edit recovery remained functional.
- Pass 654 unit/Robolectric tests, 51 Android instrumentation tests, 61 focused Android regressions and 21 food-importer tests, plus Android lint and debug/optimized release builds. See the [verification report](docs/reliability-1.4.1.md) for scope and remaining physical-device limitations.
- Version code 10 for `com.forge.workout`; Room schema 4, portable backup format 3, food catalogs, update checking and existing navigation remain unchanged. The optimized downloadable APK uses the same development/test certificate as 1.4.0 for compatible sideload updates.

## 1.4.0 — 2026-11-09

- Add two optional offline food packs with 631 India-market packaged foods and 13,041 USDA packaged foods from the US and New Zealand.
- Add Food Catalog cards with pack coverage, version, download size, source, license and installation status. Download the official packs with pinned SHA-256 checksums or import a local pack through the same atomic installer.
- Rank food search by name and brand relevance with favorite and recent tie-breakers. Add selected Indian synonyms, suppress duplicate matches by food ID, and show measured serving equivalents in Food Details.
- Preserve personal nutrition and training data through catalog changes and backup restore. Keep `com.forge.workout`, Room schema 4, backup format 3, and the GitHub update checker. The optimized APK uses the 1.3.3 development/test certificate and version code 9.
- Pass 635 host tests, 51 Android emulator tests, 21 importer tests, Android lint, and debug and optimized release builds. Verify the in-place upgrade from the published 1.3.3 APK and offline use of both packs on the Android 17 emulator.

## 1.3.3 — 2026-11-04

### Added

- Check for stable Forge updates on GitHub at startup and from Settings. Automatic checks run at most once per 24 hours.
- Read the release changelog in a scrollable dialog with Download Now, Later and Skip This Version. The dialog waits for a safe screen during workouts and backup operations.
- Settings shows the installed version, manual check results and links to the GitHub repository and latest release. Offline checks and failed responses leave training and nutrition available.

### Compatibility

- Version code 8 for `com.forge.workout`. Room schema 4 and portable backup format 3 continue to support existing data.
- The optimized APK uses the same development/test certificate as the published 1.3.2 APK for in-place sideload updates. Production signing is a separate transition.

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
