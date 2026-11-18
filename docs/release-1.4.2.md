# Forge 1.4.2 production release verification

The release payload passed the local checks below on 17–18 November 2026. Application ID `com.forge.workout`, version name `1.4.2`, version code `11`, Room schema 4 and portable backup format 3 are preserved. Publication also requires the exact-commit production rebuild, signature/checksum checks and successful hosted CI; the final commit, APK checksum and hosted run URL are recorded in the [v1.4.2 release](https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/tag/v1.4.2).

## Signing and one-time migration

| Public identity | Certificate SHA-256 |
| --- | --- |
| Legacy/current local debug | `ebf2faa1b45b6a6eea4742e7c31f8144415f5748067af1b9e4159a988ce32adc` |
| Dedicated production | `7845655dd1614ae614f9cf0b1386f1c215fe3a8b96b0c16415116460cb425019` |

The production PKCS12/RSA3072 key is in durable owner-controlled private storage outside Git. The owner explicitly confirmed secure backups of the keystore and passwords on 17 November; private backup contents were not inspected. Debug and production identities differ. Release signing requires complete external inputs and never falls back to debug signing. CI explicitly builds unsigned verification outputs.

This new-key-only APK has no signing lineage. API 26 cannot consume proof-of-rotation; preserving its legacy in-place compatibility would require the old signer. Modern Android supports rotation in principle, but that route is not implemented for this transition. Previously published development-signed installations require **verified external backup → manual uninstall → production install → restore**. Optional food packs require reimport/download. [Instructions and official references](signing.md)

## Executed checks

| Check | Actual result |
| --- | --- |
| Signing guards | Five expected outcomes passed: missing/partial/debug inputs rejected; explicit unsigned verification and private production identity accepted |
| API 26 migration | Android 8.0/x86/4 KiB owned QA emulator: actual published 1.4.1 SAF export validated; unrelated production in-place install rejected specifically with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; old identity/data unchanged; guarded QA-only reinstall of genuine production 1.4.2, actual exported-file restore and full comparison passed |
| API 37 migration | Android 17/sdk_gphone16k_x86_64/16 KiB owned QA emulator: same actual export, incompatible-install rejection, manual reinstall/restore and full comparison passed |
| Personal-data preservation | On both APIs all 17 semantic business-table hashes and every portable field except export timestamp match: workout/history 73.125 kg × 9, routine, custom exercise/food, recipe/ingredients, frozen 153.75 kcal diary, goals, favorites, 81.375 kg body weight, DARK/LB/rest 120/delete-confirmation false and nutrition settings |
| Optional/offline catalogs | Both APIs reimported India 631 after bundled USDA 13,324; exact catalog hashes match the old installation. API 37 custom recipe and India branded-food searches also passed with Wi-Fi/mobile data disabled. Existing 1.4.0 pack assets/links are unchanged |
| Production updater | Both APIs used the actual Forge downloader, SHA-256/APK inspector and Android system installer, with the production identity. Compatible future candidate installed through Forge without ADB acceptance installation. Permission/cancellation/retry and configuration/recovery checks are recorded below |
| Automatic cleanup | Verified APK plus journal measured 6,625,661 bytes before installation. Genuine updated app normal startup left **0 files / 0 bytes** in its dedicated update directory on both APIs; no partial or QA input APK remained. Read-only observer never invoked cleanup |
| Unit/Robolectric | 739 passed; 0 failures/errors/skips across 100 test classes, including Room/backup, nutrition, timers, navigation and update regressions |
| Food importer | 21 passed; pinned real pack/report fixture hashes verified |
| Android instrumentation | Clean API 37 complete suite: 54 passed, 0 failed/ignored; 374.710 seconds |
| Lint/builds | Zero lint issues. Debug, optimized unsigned CI-equivalent release and private production-signed optimized build passed. Full host check 6m 2s; production build 2m 19s |
| Source/security inventory | 339 source/support files, 45 changed; complete Git object/history plus staged-source checks found no unresolved secrets, signing files, personal backups/databases or unwanted binaries. Licensed bundled data/notices unchanged |
| CI | Local actionlint and CI-equivalent job passed; exact publication commit must pass [GitHub Actions](https://github.com/ritikverma098/Forge-Fitness-Tracker/actions/workflows/android.yml) before tag/release publication |
| Final artifact gate | Rebuild from committed release source; verify one production signer/v2/v3, package/version, 16 KiB alignment and independent full-file checksum. Compare all ZIP payloads with the native-tested candidate; only Git revision metadata may differ. Final evidence is in release notes/`SHA256SUMS.txt` |

## Installer evidence and scope

Tests used a separately identified nonpublishable production-signed 1.4.2 bootstrap with controlled future-release metadata/transport, then a genuine optimized 1.4.3/code 12 candidate containing no QA hooks. This verifies production-certificate installation, data preservation and startup cleanup before a public future release exists. It is not a claim that 1.4.3 was downloaded from a published GitHub release. Neither bootstrap nor future candidate is a release asset. The final published 1.4.2 APK contains no QA hooks/assets.

API 37 exercised download progress, Cancel (0 retained bytes/no sessions), Retry, rotation, independent downloaded-APK SHA-256, process recreation with verified state, source permission denial/return and explicit grant, installer Cancel/Retry and real Android Update. The installer package recorded by Android was `com.forge.workout`; normal startup preserved business/catalog hashes and removed the downloaded APK/journal. Play Protect offered its standard Install without scanning link; no APK upload or security-setting disable was used.

API 26 also passed Cancel/Retry/rotation, independent downloaded-file hashing, verified-state process recovery and source-permission denial/grant. Cancelling its trusted installer returned Forge to safe **READY** with the APK retained, no installer session and version still 1.4.2; explicit retry then installed the genuine future candidate. Android 17 instead reported CANCELLED. Neither path claimed installation success before Android actually updated the package.

The strict signature-mismatch message was established with a failing regression test and corrected without relaxing signer equality. The focused 47-test identity/coordinator/transfer-UI group then passed, including zero temporary bytes/no installer session on mismatch and backup/reinstall guidance without an Install or ineffective Retry action.

## Failed attempts and remaining limits

The first hosted CI run stopped before tests because the runner did not expose `sdkmanager`. The workflow was corrected to install Android command-line tools and the required SDK packages explicitly through a pinned setup action. A successful full hosted run of the corrected release commit remains mandatory before publication.

The initial API 37 functional run had three failures behind an Android System UI ANR dialog. After dismissing only that system dialog, those three passed and a clean complete 54-test run passed. No app correction was needed.

On API 26, repeated `uiautomator dump` accessibility reconnections during restore-dialog teardown triggered an Android framework `ViewRootImpl` null-view callback in the published 1.4.1 baseline. The work-only driver switched to a non-accessibility window/focus checkpoint; database and actual portable export comparisons established the result independently. No production behavior was changed to mask the platform automation failure.

Other work-only fixture corrections normalized Android's `/data/user/0` to `/data/data` alias and made a cancelled test stream throw `IOException` like the real network transport. Failed fixture evidence was retained; production download policy was unchanged.

The [earlier development-certificate record](updater-verification-1.4.2.md) remains historical. Its broader small-viewport API 26 UI suite had 12 failures reproduced against 1.4.1; this update does not claim that whole Android 8 suite is green. Current production migration/updater tests are targeted API 26 checks, alongside the complete API 37 suite. Physical-device/OEM installer behavior, idle-then-drag scrolling, Back feel and background rest-alert timing still need phone checks.

An additional API 26 post-upgrade offline UI check was not completed: `adb shell svc wifi disable` failed because the Android 8 shell lacks `CHANGE_WIFI_STATE`. Its shell-process exception was preserved and distinguished from Forge; scoped logs contained no Forge fatal exception, ANR or SQLite error. API 26 catalog preservation was checked through SQL/count/hash assertions; actual offline recipe/food UI searches passed on API 37.

Only the optimized production-signed `Forge-1.4.2.apk`, its `SHA256SUMS.txt` and public documentation belong in this release. Private signing inputs, debug/unsigned/QA APKs and local test data are excluded. The complete-file APK checksum differs from the certificate fingerprint above.
