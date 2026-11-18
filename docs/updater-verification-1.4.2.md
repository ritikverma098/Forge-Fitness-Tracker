# Forge 1.4.2 candidate verification

**Historical development-certificate evidence.** This record predates the dedicated production signing identity. Its APK hash and compatible development-signer update results are not the production release proof. See [production signing, migration and publication verification](release-1.4.2.md) for the final release gates and [required one-time migration](signing.md).

Local verification on 16–17 November 2026. Candidate version 1.4.2 / code 11; application ID `com.forge.workout`. No commit, push, tag or release publication was performed. Room schema 4 and portable backup format 3 are unchanged.

## Actual Android upgrade and cleanup

The isolated `Forge_Updater_QA_37` emulator used Android 17/API 37, `sdk_gphone16k_x86_64`, 16 KiB pages, 720×1280, two cores and 1536 MiB RAM. The same complete update/data/cleanup scenario was repeated successfully on `Forge_Updater_QA_26`, Android 8.0/API 26, x86, 4 KiB pages, 720×1280/320 dpi, two cores and 1024 MiB RAM. Existing personal emulators were not touched or cleared.

1. Installed the actual published optimized 1.4.1 APK and restored a synthetic workout/nutrition fixture through the real backup UI. Imported the India pack through Android's document picker. Captured the business tables and catalog state.
2. Installed a separately identified same-ID/same-certificate 1.4.1/code 10 QA bootstrap containing the new updater. Controlled unpublished-release metadata and transport supplied the candidate bytes; production download, hash, package, version and signing validation remained active.
3. Used Forge's Download & Install UI, then its explicit Install Update action and Android's system confirmation. The genuine optimized 1.4.2/code 11 candidate was **not installed by ADB**. Android reported `installingPackageName=com.forge.workout`.
4. Started the updated app normally. A separate Android-framework-only observer read Room and the private update directory; it did not invoke cleanup. The installed Application was the production `ForgeApplication`, with no QA hooks in the APK.

| Assertion | Observed result |
| --- | --- |
| Installed version/code | 1.4.2 / 11 |
| Actual APK SHA-256 | `e59974943bc9d10d57fadf4fd3eadb5374b71b77e1882a8f403284523f981d74` |
| APK bytes | 6,629,147 |
| Temporary bytes before installation | 6,629,440 (verified APK + private journal) |
| Temporary files/bytes after normal updated startup | **0 files / 0 bytes** |
| Abandoned partial files / extra QA input APKs | 0 / 0 |
| Exact personal-table hash | `83b734d8420960465ce6c1bde4d0166314665e1f296f1dad530a4c6ab73f84f6`, unchanged across 17 tables |
| Workout | 73.125 kg × 9, notes and routine unchanged |
| Nutrition | 153.75 kcal diary snapshot, recipe, custom food and 2200 kcal goal unchanged |
| Installed catalogs | India 631 + USDA core 13,324 reference foods, versions/hashes/counts unchanged |
| Unrelated private pack control | Original SHA-256 unchanged |

Android's Play Protect scan initially requested a possible external upload. Automatic approval review rejected that upload before execution. The platform subsequently offered **Install without scanning**, which completed locally after its Update confirmation; no Play Protect setting or signature enforcement was disabled and no APK was uploaded to Google. The user later authorized scanning, but it was unnecessary.

This is an actual Android installer/data/cleanup test with fixture release transport, **not a claimed public 1.4.2 download**. The release remains unpublished. Published 1.4.1 lacks the new downloader and uses its existing browser/manual path for the initial upgrade. Separately, a real public GitHub API/CDN download streamed all 6,579,994 bytes of 1.4.1, passed its expected SHA-256, and was correctly rejected by the real package inspector as already installed, leaving zero temporary bytes.

Local evidence is retained outside the repository under `work/updater-1.4.2/native-qa/`: `final-download-native.log`, `final-downloaded-framework.log`, `final-complete-native-valid.log`, `final-candidate-framework.log`, `final-native-result.json`, and screenshots/XML in `evidence/`.

Android 8 evidence: `older-baseline-framework.log`, `older-downloaded-framework.log`, `older-complete-native.log`, `older-candidate-framework.log` and `older-final-native-result.json`. Both devices produced the exact values in the table above, including zero retained bytes and Forge as the installer. On the genuine updated Android 8 app, a live manual GitHub check reported **Forge is up to date**. After Android reported **Active default network: none**, the catalog showed 13,955 foods and a rice search opened Food Details. Connectivity was restored afterward. See `older-final-live-check.log`, `older-offline-connectivity-final.txt` and `older-offline-search-valid.log`.

## Failure, permission and recreation checks

- Actual slow download showed bytes/percentage and Cancel without scrolling. Cancel left no partial, APK, journal or session. Retry completed; rotation did not duplicate the transfer.
- A partial download held 3,059,712 bytes before terminating the stopped background process through Android's preserved task. Next startup returned Idle with **zero bytes**. This was process death, not only force-stop.
- A verified download survived another process recreation with the exact same hash. Denying/returning from unknown-source settings kept installation blocked. Granting access returned to explicit Install; it did not install automatically.
- Android installer Cancel retained a verified readable APK for Retry, with no outstanding session. Process death while the installer confirmation was visible recovered safely. Explicit Discard then left zero bytes.
- Wrong/missing checksum, corrupt bytes, interrupted reads, offline/network/timeout/HTTP failure, unexpected redirect, oversize and simulated storage exhaustion produced the expected failure with zero retained bytes and no install session. Storage exhaustion was an injected failure, not a physically filled disk.
- Same-version immediate Retry and manual Download after hiding a failed attempt reopened the modal. An explicit attempt identity fixes conflated intermediate states without an artificial delay.
- A native symlink safety regression first established an unsafe update-directory root, then passed both corrected guards. Cleanup remains restricted to its dedicated directory.

These checks found and corrected two real UI issues during implementation: Settings progress below the viewport and hidden fast retries. Host and Android regression tests cover the corrected behavior. Harness-only selector ambiguity, old Android file-row accessibility differences and framework observer title waits are not counted as application defects.

## Automated and build results

| Check | Result |
| --- | --- |
| Complete host unit/Robolectric suite | **736 passed**, 0 failures/errors/skips |
| Complete Android 17 instrumentation suite | **54 passed**, 0 failures/ignored, 328.919 seconds |
| Python food catalog importer suite | **21 passed** |
| Android lint | **0 issues** |
| Debug app and instrumentation APK build | Passed |
| Optimized unsigned release build | Passed, R8 and resource shrinking enabled |
| Final signed APK | Alignment and v2/v3 signature verification passed |
| Signature compatibility | Same development/test certificate as public 1.4.1 |
| GitHub workflow | Actionlint and four Bash shell blocks passed; all four public pack/report fixture SHA-256 checks passed locally |

Certificate SHA-256: `ebf2faa1b45b6a6eea4742e7c31f8144415f5748067af1b9e4159a988ce32adc`. Signing credentials stay outside the repository. `assembleRelease` remains unsigned, and CI neither signs nor publishes. The locally prepared APK is `outputs/Forge-1.4.2/Forge-1.4.2.apk` relative to the enclosing workspace.

The final Android 17 result is `25-final-android-regressions-api37.log`. GitHub-hosted CI has not run because no push is authorized. The Android 17 emulator subsequently hit a system-process AppOps crash (`Invalid @IntRange(from = -1): -38399`), independently of the earlier completed upgrade assertions. It recovered after one controlled emulator restart without a data wipe. The regression run uses a standard tall-phone viewport (1080×2400/420 dpi); the actual updater acceptance above used the smaller viewport. No application layout was changed to accommodate the tests.

An initial Android 17 run had three failures: existing navigation/restore tests waited for Nutrition's empty-state item without scrolling. That item is below summary cards in a LazyColumn and need not be composed initially. Two test files now wait for the loaded summary and explicitly scroll to the empty item, retaining the original assertions. All three focused cases then passed, followed by the complete 54-test run. No production code or APK bytes changed for this correction; `21-lazy-nutrition-test-build.log` confirms the test build/lint gate, and `22-lazy-nutrition-focused-api37.log` the focused results.

### Android 8 baseline comparison

The complete 54-test run on Android 8 at 720×1280/320 dpi had **42 passes and 12 failures**, with zero ignored tests. The updater-specific tests passed. Failures were investigated instead of being omitted from the result.

An untouched archive of baseline commit `376a02a6cfdca0b1cc879cfce40399c9ab1490f2` (1.4.1) was built under a separate QA application ID, `com.forge.workout.baselineqa`, with its own test data. The same 12 failing cases were replayed on the same Android 8 emulator and geometry. **All 12 failed with the same first assertion/exception**, establishing that these failures predate this update. This comparison used a source build with only the scratch QA package override, not the published obfuscated APK. No unrelated application correction was introduced.

| Existing test class | Failing cases | Observed assertion category |
| --- | ---: | --- |
| CompactWorkoutLoggingTest | 2 | Add Set node outside composition; immediate focus assertion |
| ForgeNavigationTest | 3 | Add Set/empty-dashboard visibility; missing routine result |
| ForgePredictiveBackTest | 1 | Nutrition empty-state wait |
| RestoreNavigationTest | 2 | Navigation/empty-state waits |
| RoutineCustomExerciseTest | 1 | Routine-start wait |
| SetTypesTest | 2 | Save Changes visibility; saved-value wait |
| WorkoutFrameTimingTest | 1 | Required visible accessibility node absent |

These are **baseline test failures**, not a claim that every underlying Android 8 interaction has been diagnosed or fixed. Small-viewport/IME assumptions and older-Android behavior need a separate follow-up. The actual Android 8 update, permission, data-preservation, offline search and automatic cleanup tests above passed. Evidence: `17-final-android-regressions-api26.log`, `17-api26-results.json`, `19-baseline-api26-failed-cases.log` and `19-baseline-comparison.json` under the enclosing `work/updater-1.4.2/` directory.

With the same Nutrition-scroll test correction, an Android 8 focused run passed two cases and progressed beyond the original Nutrition failure in the third, which then timed out waiting for the workout-name marker after Start Workout. Replaying that corrected test against the 1.4.1 QA baseline reproduced the **same later line-124 timeout**. Evidence: `23-lazy-nutrition-focused-api26.log` and `26-baseline-post-scroll-api26.log`. The full small-viewport Android 8 suite is therefore **not reported as green**, and the later checkpoint is also a baseline issue. No speculative workout/Room changes were made to address it.

## Source changes

Production changes are confined to version metadata, update delivery, Settings integration, localized strings and the install-source permission/receiver. No migration, nutrition catalog, repository/database, workout logging, timer or navigation-transition implementation was changed.

New updater classes: `GitHubAssetPolicy`, `SecureApkDownloader`, `AndroidApkInspector`, `UpdateFiles`, `UpdateInstaller`, `AndroidUpdateInstaller`, `ApkUpdateCoordinator`, `UpdateInstallReceiver`. New UI components: `UpdateTransferPanel` and `UpdateInstallerHost`. Existing release metadata, Application, update dialog, Settings and root safe-screen wiring were extended.

The existing `ForgePredictiveBackTest` and `RestoreNavigationTest` were corrected only to scroll to the lazily composed Nutrition assertion, as described above.

See [lifecycle and trust design](updater-design.md) and [CI and separate emulator procedure](ci.md). The complete exact file inventory is listed below.

## Remaining verification

After publication, verify the official next-release API digest/size, live Download & Install, Android source permission, Cancel/Retry, in-place data preservation and next-start cleanup on a physical phone. OEM installer/Play Protect prompts may differ. No claim is made about an unpublished public download or physical-device installation. Transfers deliberately do not continue in a permanent foreground service after process death; partials are removed and the user can retry. Key rotation and production signing remain separate work.

## Exact changed files

- `.github/workflows/android.yml`
- `CHANGELOG.md`
- `README.md`
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/forge/workout/ForgeTestApplication.kt`
- `app/src/androidTest/java/com/forge/workout/ui/ForgePredictiveBackTest.kt`
- `app/src/androidTest/java/com/forge/workout/ui/ForgeUpdatesTest.kt`
- `app/src/androidTest/java/com/forge/workout/ui/RestoreNavigationTest.kt`
- `app/src/androidTest/java/com/forge/workout/updates/UpdateFileSafetyTest.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/forge/workout/ForgeApplication.kt`
- `app/src/main/java/com/forge/workout/ui/components/UpdateAvailableDialog.kt`
- `app/src/main/java/com/forge/workout/ui/components/UpdateInstallerHost.kt`
- `app/src/main/java/com/forge/workout/ui/components/UpdateTransferPanel.kt`
- `app/src/main/java/com/forge/workout/ui/navigation/ForgeApp.kt`
- `app/src/main/java/com/forge/workout/ui/screens/settings/SettingsScreen.kt`
- `app/src/main/java/com/forge/workout/ui/screens/settings/UpdateSettings.kt`
- `app/src/main/java/com/forge/workout/updates/AndroidApkInspector.kt`
- `app/src/main/java/com/forge/workout/updates/AndroidUpdateInstaller.kt`
- `app/src/main/java/com/forge/workout/updates/ApkUpdateCoordinator.kt`
- `app/src/main/java/com/forge/workout/updates/GitHubAssetPolicy.kt`
- `app/src/main/java/com/forge/workout/updates/GitHubReleaseSource.kt`
- `app/src/main/java/com/forge/workout/updates/SecureApkDownloader.kt`
- `app/src/main/java/com/forge/workout/updates/UpdateFiles.kt`
- `app/src/main/java/com/forge/workout/updates/UpdateInstallReceiver.kt`
- `app/src/main/java/com/forge/workout/updates/UpdateInstaller.kt`
- `app/src/main/res/values/update_strings.xml`
- `app/src/main/res/values/updater_download_strings.xml`
- `app/src/test/java/com/forge/workout/ui/UpdateDialogHostTest.kt`
- `app/src/test/java/com/forge/workout/ui/UpdateSettingsHostTest.kt`
- `app/src/test/java/com/forge/workout/ui/UpdateTransferHostTest.kt`
- `app/src/test/java/com/forge/workout/updates/AndroidApkInspectorHostTest.kt`
- `app/src/test/java/com/forge/workout/updates/AndroidUpdateInstallerHostTest.kt`
- `app/src/test/java/com/forge/workout/updates/ApkIdentityValidationTest.kt`
- `app/src/test/java/com/forge/workout/updates/ApkUpdateCoordinatorTest.kt`
- `app/src/test/java/com/forge/workout/updates/GitHubReleaseSourceTest.kt`
- `app/src/test/java/com/forge/workout/updates/SecureApkDownloaderTest.kt`
- `app/src/test/java/com/forge/workout/updates/UpdateFilesTest.kt`
- `docs/ci.md`
- `docs/updater-design.md`
- `docs/updater-verification-1.4.2.md`
