# Forge 1.4.1 reliability verification

Baseline: Forge 1.4.0, commit `77f01de4f9be62b30ea40ae5716f176502cd0608`. Scope: independently verify and remediate audit findings V14-F01 and V14-R01. No unrelated findings or feature changes.

## V14-F01 — recipe backup consistency

Three baseline rejection tests confirmed that independently changing a valid backup's servings, cooked weight or stored nutrients was accepted. Validation checked individual fields without comparing derived recipe nutrition against frozen ingredient totals.

`NutritionValidation.recipe` now uses existing `RecipeMath` calculations to validate the serving basis, cooked grams per serving, normalized nutrient units and per-serving measurements. Numeric tolerance is `max(1, abs(expected)) * 1e-9`. Missing/unreported measurements remain unknown; they are not converted to zero. Invalid backups are rejected before restore mutation. Historical diary snapshots remain independent and are not recalculated from a current recipe or food catalog.

Eight focused regressions cover independent JSON mutations, direct restore rejection and data preservation, valid round trips, legacy unknown-nutrient representations, compatible units and numeric tolerance. Existing recipe persistence, catalog and backup tests also pass. Three existing synthetic fixtures were corrected to describe consistent recipes without weakening their transaction/history assertions.

## V14-R01 — obsolete workout assignments after restore

Classification: **confirmed and fixed under the tested real-Android saved-task conditions**.

Android can capture a pending workout assignment when an Activity stops. A later background restore clears the live journal but cannot update that already captured saved-instance-state Bundle. After process recreation, the old 60-to-80 kg assignment matched the restored set's 60 kg base and replayed incorrectly.

Each successful restore now rotates an internal durable workout generation within the same Room transaction. Startup recovery loads it before workout recovery. Numeric/text journals are stamped with that generation; mismatched saved assignments are discarded before replay. A retained ViewModel also synchronizes the generation before draft access, edits, retries or flush. Failed restores roll back the generation together with data. Internal metadata stays outside portable backups.

### Actual emulator reproduction

Pixel_10_Pro AVD, Android 17/API 37, x86_64, 16 KB pages. Synthetic fixtures used a separate QA package/database; personal app data was not cleared.

1. Store a set at 60 kg and export an older valid backup.
2. Request 80 kg through the production workout writer; a controlled repository barrier delays persistence.
3. Start the real Settings restore and verify it waits for pending changes.
4. Background the Activity. Capture actual STOPPED/saved-state callbacks and the real pending 80/base-60 journal.
5. Release the barrier; restore completes while stopped, leaving Room at 60 kg and the live journal cleared.
6. Kill the background process with `am kill`, preserving its Android task. Recreate through that same task with a different process ID and the actual old saved Bundle.
7. Assert both Room and displayed values after recovery, and inspect write requests/commits.

| Trial | Final Room / UI | Result |
|---|---|---|
| Baseline, two independent valid trials | 80 / 80 kg | Defect reproduced |
| Corrected, two valid trials | 60 / 60 kg | No obsolete write requested or committed |
| Ordinary process death, no restore | 80 / 80 kg | Legitimate pending edit recovered |

All recreated processes initially read Room at 60 kg. Both corrected trials received the old saved journal and crossed different durable generations. The ordinary control retained its generation and committed the legitimate 80 kg edit. The first baseline used Android's file picker; later trials selected the fixture through a QA command calling the real `BackupViewModel.readBackup`, then used the actual confirmation/restore UI. No saved journal was fabricated or injected. Interrupted attempts without the required lifecycle boundary were excluded.

## Verification and compatibility

- Complete unit/Robolectric suite: **654 passed, zero failed/skipped**.
- Original Android instrumentation suite: **51 passed, zero failed/skipped**.
- Focused Android recipe/restore/draft group: **61 passed, zero failed/skipped**. These overlap host coverage and are not added to its count.
- Nutrition catalog importer: **21 passed**.
- Android lint: **zero errors/warnings**. Debug/test and optimized release builds passed.
- Coverage includes workouts, exact weights, failed-save Retry, routines, set types, timers, History/Progress, nutrition snapshots, catalogs, immediate post-restore navigation, Back and GitHub update checking.
- Application ID remains `com.forge.workout`; version 1.4.1/code 10. Room schema 4 and portable backup format 3 are unchanged. No destructive migration, data clearing, signing-key change or new permission/dependency.
- The downloadable optimized APK uses the existing development/test certificate. Source `assembleRelease` remains unsigned, with credentials kept outside the repository.

Final version 1.4.1/code-10 packaging was reverified with all 654 host tests, all 51 instrumentation tests and all 21 importer tests passing, plus debug/test builds, optimized release build and zero-issue lint. The signed APK passed certificate comparison against the downloaded public 1.4.0 APK and 16 KB alignment verification. Its in-place emulator installation succeeded without clearing data: all 24 visible Home text values, including the existing active workout and completed-workout count, matched the pre-update baseline. Package metadata showed 1.4.1/code 10; focused runtime error capture was empty.

The specific saved-task boundary was exercised meaningfully on API 37; physical OEM lifecycle variants were not tested. Existing frame-timing checks are measurements without absolute smoothness guarantees. No broader performance claim is made.
