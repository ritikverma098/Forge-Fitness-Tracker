# Built-in updater lifecycle (1.4.2)

This lifecycle was documented before implementation and is now implemented. It reuses GitHubReleaseSource, normalized version comparison, UpdateViewModel's safe-screen offers and existing DataStore check/Later/Skip bookkeeping. No Room or portable-backup changes. Actual execution results and limitations are in [candidate verification](updater-verification-1.4.2.md).

## Download and trust

Only an explicit Download & Install action starts a transfer. Preserve the selected official release asset's size and GitHub API `sha256:` digest. Missing/invalid digest fails closed; release text and filenames do not supply trust. Revalidate the configured repository/tag/asset HTTPS URL at download time.

Stream on IO into `noBackupFilesDir/updates/download.part`, with bounded buffers, timeouts, a 128 MiB maximum, exact byte-count checks and SHA-256. Follow at most five HTTPS redirects to exact approved GitHub/CDN hosts; never persist or log signed CDN query strings. No browser, external download manager, storage permission or new network library.

After transfer, require matching digest, package `com.forge.workout`, increasing Android versionCode, a normalized versionName matching the offered newer version, and the same complete current signer set as the installed application. Android's installer enforces final signature validity/compatibility. No signing-key rotation in this update.

## State and recovery

An application-scoped coordinator owns one operation and exposes immutable StateFlow: idle, preparing, downloading, verifying, ready, source permission required, staging, handed to installer, approval ready, installed (only after installed-version confirmation), cancelled, failed. Frequent progress is read only by the update UI. Each explicit download/retry has an attempt identity so a rapid failure still reopens a previously hidden modal. Activity/ViewModel recreation does not start another transfer. Background process death may interrupt a transfer; no automatic network resume is promised.

A small atomic private journal records only version/tag, original official URL, expected size/digest, verified target versionCode, creation time and optional owned installer session ID. It is independent of Room, DataStore user preferences and portable backups. Startup deletes abandoned partials, validates journal structure, rechecks retained APKs, and reconciles the installed version before exposing install actions. Cancel/failed transfers delete partial output and allow a clean retry.

## Installer choice

Use PackageInstaller.Session rather than FileProvider. Copy and fsync verified bytes into Android's own full-install session on IO. Persist the owned session ID before commit; explicitly require user action on API31+. The callback is an explicit, mutable PendingIntent on API31+, accepted only for the persisted session. Android8+ unknown-source access uses REQUEST_INSTALL_PACKAGES and the package-specific Settings screen. Recheck permission on resume and return to an explicit Install action.

Android's pending-user-action Intent is presented only while Forge is foreground/resumed. No background launch, silent installation or privileged workaround. If process death loses that Intent, an explicit retry abandons the old owned session and creates a new one from the retained verified APK. An inactive session is not treated as completed. Terminal callbacks, installed-version checks and explicit session abandonment govern retries/discard.

## Cleanup

Only the dedicated private update directory is inspected. Fixed filenames and bounded journal size keep cleanup predictable. Installer reads its own staged copy, never the private source APK. Source APK/journal remain pending across process replacement, then are automatically removed when startup confirms installed versionCode and versionName reached the target. Successful cleanup reports actual retained bytes. A cancelled/failed install can retry or discard after abandoning its owned session. Expired pending files (24 hours) are abandoned/removed; unrelated files, personal databases, backups and catalogs are never traversed or deleted.

## Verification sequence

1. Implement metadata/transport validation and focused failure/cancellation tests; build.
2. Implement private journal/coordinator, APK inspection and Android installer; test recovery, duplicates, callbacks and cleanup; build.
3. Integrate compact Material3 progress/actions in the existing dialog and Settings, retaining safe-screen/Later/Skip behavior; test/build.
4. Real emulator: use a separate synthetic-data AVD so installed package replacement never touches existing personal data. Published1.4.1 cannot use a downloader it does not contain. Bootstrap the new updater under1.4.1/code10 with controlled unpublished-candidate metadata/transport, then approve actual Android installation of the genuine signed1.4.2/code11 candidate. Measure private-directory bytes after next launch. Test live official GitHub transport/CDN independently. Clearly report this prepublication limitation.
5. Only after updater verification, implement [CI](ci.md) and run complete regression/lint/build checks. No push/tag/release without separate approval.

Official references: [GitHub asset digest](https://docs.github.com/en/rest/releases/assets), [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller), [Session](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session), [session parameters](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams), [SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo).
