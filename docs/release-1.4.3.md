# Forge 1.4.3 verification

Forge 1.4.3 refreshes the workout, nutrition and tracking screens with the Forge Dark design and updated Light and System themes. On Android 13 and newer, Home can offer rest-alert notification access on the first eligible startup. The user can decline and return to the permission controls later in Settings.

## Compatibility and signing

The package is `com.forge.workout`, version `1.4.3` (code `12`), with Android 8.0/API 26 as the minimum and API 37 as the target. Room schema 4 and portable backup format 3 are unchanged. The production certificate SHA-256 remains `7845655dd1614ae614f9cf0b1386f1c215fe3a8b96b0c16415116460cb425019`.

Production-signed 1.4.2 installations can update in place. Development-signed installations need the one-time portable backup, reinstall and restore procedure. [Signing and migration instructions](signing.md)

The release notes identify the source commit, GitHub Actions run and complete-file APK SHA-256. The APK checksum identifies the downloaded bytes. The certificate fingerprint identifies the signer. [Forge v1.4.3 release](https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/tag/v1.4.3)

## Local verification

The compatibility tests use synthetic records and separate emulators. In-place upgrades start from the public production-signed 1.4.2 APK, keep the original Android install time and compare real portable backup exports before and after the upgrade. The comparison excludes the export timestamp.

| Check | Result |
| --- | --- |
| Host unit and Robolectric | 750 passed across 102 suites, with no failures, errors or skips. Includes Room, backup/restore, both pinned public food packs, update verification and permission lifecycle cases. |
| Food catalog importer | 21 passed. |
| Build and lint | Debug and instrumentation APKs built. Android lint reported no issues. The optimized production candidate passed APK signature, package metadata, integrity and 16 KiB alignment checks. |
| Android 17 instrumentation | 54 passed on a separate QA installation. Covers workout entry and keyboard behavior, routines, nutrition, history and progress, predictive Back, backups, update UI and rest alerts. |
| Android 8.0/API 26 | Production-to-production upgrade retained the original install time and all portable backup data. The fixture included 10 workouts, 184 sets, four routines, five diary entries, recipes, body weight and preferences. |
| Android 17/API 37 | Production-to-production upgrade on a 16 KiB emulator retained the original install time and the same portable fixture. The first eligible Home visit showed the rest-alert explanation. Not Now let the user continue. |

## Limits

The emulator checks do not measure sound or haptic strength on physical phones, and real-world delivery remains subject to device and Android notification settings. Initial bundled food import can leave searches empty until the catalog is ready. Forge retains manual notification controls, separate precise-alarm access and the existing inexact-alarm fallback.
