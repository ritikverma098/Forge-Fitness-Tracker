# Forge 1.3.0

[Third-party notices and bundled data licenses](THIRD_PARTY_NOTICES.md). No overall license for the original project code has been selected.

A native workout and nutrition tracker for Android 8.0 and newer, built with Kotlin, Jetpack Compose and Material 3. Log training, plan routines, review progress and keep a food diary on your device. Forge uses an original interface with neutral surfaces, a lime accent and light/dark/system themes.

Training and nutrition work offline, including the bundled food catalog. There are no ads, accounts, analytics or cloud sync. The app uses the internet only for an optional food catalog download requested by the user.

[Features](#features) · [Build and install](#build-and-install) · [Backup and restore](#backup-and-restore) · [Architecture](#architecture) · [Tests](#dependencies-and-tests)

## Screenshots

Physical-device screenshots are not yet included in this repository.

## Development status

The **1.3.0 feature implementation is complete**. Workout and nutrition changes were implemented through sequential build/test gates. Automated checks cover database migrations, calculations, editor state, navigation, backups and Android platform adapters; physical-phone validation remains pending.

Tentative follow-up work includes physical-device checks for scrolling, Back transitions and rest alerts, and additional regional food packs where suitable redistribution rights and source data are available. Scope and timing are undecided. No additional regional packs are hosted with this release.

## Build and install

1. Open this folder in **Android Studio Quail 4 (2026.1.4) or newer**.
2. Use Android Studio's bundled JDK (Java 25 works), install Android SDK platform 37 and build tools 36.0.0, then let Gradle sync.
3. Select `app` and Run on an emulator or Android 8.0+ phone with USB debugging enabled.

From a terminal at the project root:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat connectedDebugAndroidTest
```

On macOS/Linux use `./gradlew` (make it executable if your archive tool drops the executable bit). Set `JAVA_HOME` to a JDK supported by Gradle 9.6; the installed Android Studio runtime is suitable. Configure your SDK path in Android Studio or in an untracked `local.properties` using `sdk.dir`.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. It is installable and signed with the standard development key. `assembleRelease` produces an optimized **unsigned** release APK; configure your own signing key in Android Studio before distributing a release. Never commit signing credentials.

Debug builds use the development key on the machine that builds them. Android requires a matching signing certificate to update an installed copy. Keep release signing keys and passwords outside this repository.

Dependencies require internet for the first build. The installed application includes its initial food catalog and works offline. First opening Nutrition installs that bundled catalog into Room in the background; progress and failures are shown with a retry action.

## Features

- Dashboard with current session, last session, weekly/total counts, recent workouts, and shortcuts.
- Searchable, muscle-filtered library of 81 default exercises across 13 groups; custom exercise creation, editing, and deletion, secondary muscle groups and notes.
- Weight/reps, bodyweight, reps-only, duration, and distance logging; automatic next-set duplication, previous performance, exercise ordering, notes, and rapid numeric input.
- A persisted active session and elapsed timer; optional 30/60/90/120/180-second rest timers and an Off setting.
- Completed workout summaries, completed-only volume/repetition totals, automatic weight/repetition/volume records, and grouped history with detail/edit/repeat/delete.
- Create, rename, reorder, edit, delete, and launch routines.
- Exercise progress with best sets, frequency, maximum reps, and interactive lightweight Compose charts for weight, reps, duration, distance, and weighted volume.
- DataStore preferences for theme, kg/lb, rest duration, and workout deletion confirmation.

### Nutrition in 1.3.0

- Bottom tabs: Home, Workout, Nutrition, History, Progress. Exercise Library and Routines remain accessible from Workout and Home; nutrition has its own history child screen.
- An indexed, paginated food picker with name/brand/alias search, recent/frequent/favorite/Indian/branded filters, date and meal selection. Search and logging context survive child navigation and recreation.
- Food details show source, brand, nutrient basis, source-defined portions, calories/macros and additional available nutrients. Unknown values remain unavailable; zero remains zero. Gram/mL conversion is offered only with a known equivalent.
- Custom foods support optional nutrients, notes, favorites, editing and safe deletion. Recipes reuse the picker and custom-food editor, retain ingredient snapshots, support ordering/quantities/servings and optional final cooked weight. Weight-based cooked portions require that final weight.
- A daily dashboard with dated meals, calorie/macro goals, progress, totals and remaining allowance; edit/move/delete/repeat entries or meals. Goals take effect from their selected date. Incomplete nutrient totals are labeled rather than silently treated as zero.
- Dated body-weight records, estimated completed-workout active calories, editable activity/active fraction, manual overrides, and an optional calorie-budget setting. Estimates are displayed in workout detail/summary and the relevant nutrition day.
- Nutrition history uses paginated cached daily aggregates and effective-date goals. Opening a day reuses the diary editor; past entries retain their captured nutrients after food/recipe edits or deletion.
- Portable local backup covers personal nutrition and training data, accepts earlier workout-only files, and refreshes navigation/drafts after restore.

### Food sources and catalog packs

The bundled USDA FoodData Central catalog contains **13,324 foods, 993,304 nutrient rows and 36,142 portions**: 7,793 SR Legacy, 5,332 FNDDS, and 199 branded foods after documented exclusions. The Indian filter contains 655 curated USDA entries and kitchen ingredients; it is not an ICMR-NIN database. No proprietary, Samsung Health or Open Food Facts data is bundled.

Attribution: U.S. Department of Agriculture, Agricultural Research Service, FoodData Central. The public-domain/CC0 source records, reproducible standard-library importer and exclusions are documented in [tools/catalog/README.md](tools/catalog/README.md). Running the importer also generates a report with source hashes, counts and exclusions. The compressed initial pack is approximately 3.8 MB; its structured payload is approximately 77 MB before Room indexing, so installation needs additional free storage.

The food catalog screen supports compatible local `.forgefoods` files or optional HTTPS downloads with an expected whole-file SHA-256. Packs validate structure, provenance, counts, checksums, storage and stable IDs before atomic installation/update. Personal foods and historical snapshots are preserved. Publishers can generate licensed compatible packs using the documented pipeline. There is no barcode scanner or camera permission.

### Workout calorie estimates

Forge uses documented [2024 Adult Compendium conditioning activities](https://pacompendium.com/conditioning-exercise/) and its [MET conversion formula](https://pacompendium.com/unite-conversions/). Available activities are light calisthenics (2.8 MET), resistance training (3.5), vigorous resistance training (6.0), general bodyweight exercise (3.0), moderate calisthenics (3.8), and vigorous calisthenics (7.5). These are estimates, not measured expenditure.

`active kcal = (MET - 1) × 3.5 × recorded kg × elapsed minutes × active fraction / 200`

The default active fraction of **50% is an explicit editable assumption** to account for rests, not a measured timer statistic. A 60-minute resistance workout at 80 kg and 50% gives 105 estimated active kcal. The closest recorded body weight on/before the workout day is used; without eligible weight, no estimate is fabricated. Records capture the weight used and remain readable after deleting that measurement. Manual overrides survive recalculation.

Workout calories are excluded from the daily food allowance by default. Enabling them adds effective workout estimates; the UI warns that a goal which already includes activity can double-count expenditure. Defaults affect newly created estimates; users edit historical estimates explicitly.

### Stability fixes in 1.2.1

- A confirmed restore rebuilds the complete navigation host with its controller, preserving Room/DataStore observers and immediately usable navigation without restarting.
- Rest completion uses a lifecycle-aware coordinator and Android alarms. Foreground feedback and background/locked notifications share one atomic deadline claim; returning to Forge does not replay an expired alert. Settings provides explicit notification and Alarms & reminders access actions.
- Workout clocks update independently of exercise cards. Previous performance is indexed off the UI thread, and unchanged rows retain their references after Room updates.
- Routine exercise pickers open the existing custom-exercise editor. Search/group selections stay intact, and a created exercise is pinned for immediate selection.
- That release used version code 4, Room schema 3 and backup format 2. Version 1.3.0 advances these to code 5, schema 4 and format 3, retaining earlier migration/backup support.

### Changes in 1.2

- Compact set tables with previous-performance columns, selectable numeric values, Weight → Reps keyboard focus and set-number menus.
- Sticky rest controls for ±15 seconds and Skip, using the existing saved deadline; foreground completion haptics respect the screen lifecycle.
- Working, Warm-up, Drop Set and Failure annotations, preserved in history. Add Set and Repeat copy values into normal Working Sets.
- The exercise picker prioritizes use in the last 30 days, then older exercises by completed-workout frequency, while preserving search, selected muscle groups and All.
- History combines workout-name, exercise, muscle-group and inclusive local-date filters. Filter and progress selections survive recreation.
- Progress adds Best Weight, Estimated 1RM, Best Reps, Volume and weekly Frequency with 1M/3M/6M/1Y/All ranges; duration/distance exercises retain their relevant metrics. Epley estimates use `weight × (1 + reps / 30)` on completed weighted sets and are never recorded lifts or new PR categories.
- Highest-weight records in the workout summary also show the repetitions performed. The explicit predictive Back fades remain unchanged.

### Retained from 1.1

1. A compact rest timer stays visible above the scrolling exercise list; Skip clears its persisted deadline.
2. Every set has a dedicated completion checkbox under a clear **Done** heading, separate from the numeric inputs.
3. Workouts retain multiple selected muscle groups. The exercise picker initially includes matches to any selected primary or secondary group; search narrows those results, and All muscles overrides the workout filter.
4. Repeating a workout preserves its name, notes, groups, exercises and ordering, and creates one unchecked set per exercise using that exercise's first saved set. Exercises without sets receive a blank set; the original workout is untouched.
5. Back navigation preserves History scroll and consistent screen bounds. Entry guards prevent repeated outgoing taps from navigating twice; editor changes continue to save automatically.
6. Local JSON backup and confirmed restore include training data and preferences.

Numeric values are validated before saving; invalid fields block finishing. Weights are stored in kilograms and converted for display and input. Sets not checked as completed remain in history but do not count toward statistics or records. Bodyweight and reps-only sets contribute repetitions, but not weighted volume.

All valid workout edits save automatically, including edits to past workouts. Rest timers use a persisted wall-clock deadline and remain accurate after navigation or process restart. The sticky timer remains accessible while scrolling. Foreground expiry uses subtle vibration; background or locked expiry posts a local notification. Android's notification channel controls sound/vibration and lock-screen presentation. Enable notifications and, on Android 12+, Alarms & reminders for precise alerts. Without exact-alarm access Forge falls back to an inexact idle alarm, which Android may delay. Denied notifications remain graceful: expired timers clear without a misleading late vibration on return. No service runs permanently, and alerts never force open the app.

Deleting a referenced custom exercise archives it to preserve past sessions. Archived routine entries are marked unavailable and can be removed; routine starts omit them. Repeating a saved workout retains its archived exercises. Exercise types cannot be changed after the exercise has logged sets, so historical measurements retain their meaning.

## Backup and restore

Settings exports/opens local UTF-8 JSON documents through Android's standard file picker. New backups use **format 3 / database layout 4**, limited to 32 MiB. They include exercises/archive state, workouts/active rest deadline, sets/types, routines, seed metadata and DataStore preferences, plus personal foods/portions/nutrients, recipes/ingredient snapshots, diary snapshots, favorites, effective-date goals, body weights, nutrition settings and workout calorie records/manual overrides. Canonical kg, stable food IDs and user row IDs are preserved; internal catalog search row IDs are regenerated. Exact repeated snapshots share a registry to keep long histories portable.

Reference catalog packs are not copied into personal backups, and installed packs remain intact on restore. Captured diary/recipe details remain readable if their source catalog is absent. A full restore replaces personal nutrition and workout data after a scope/count preview. Older **format 1 / database 2** and **format 2 / database 3** files retain their workout-only scope; existing independent nutrition records are kept, while estimates linked to replaced workouts are regenerated. Format 1 sets default to Working Set.

Import validates structure, versions, relationships, snapshot references, types and values before confirmation. Room replaces rows in one transaction; if it fails after preferences were imported, previous preferences are restored. Pending workout/nutrition writes are drained first. A committed restore rebuilds navigation and editor drafts while shared repositories keep observing the same Room instance. Rest delivery and workout-energy reconciliation pause across restore, then reconcile committed or rolled-back data. A failed restore leaves existing data usable; no database-file replacement, destructive recreation or manual app restart is required.

## Architecture

A single-activity MVVM app using Navigation Compose and small feature ViewModels. Screens receive immutable snapshots from `StateFlow` and call ViewModel actions. Room access is isolated behind `WorkoutRepository`, `NutritionRepository` and `BackupRepository`; multi-row mutations are transactional. A small application-scoped dependency container constructs Room and DataStore once. Hilt is intentionally unnecessary for this project's scope.

```text
com.forge.workout/
  data/database/    Room database, DAO, converters, seed metadata
  data/seed/        First-install exercise catalog
  model/            Entities and relational snapshots
  repository/       Repository contract and Room implementation
  backup/           Validated JSON codec, document I/O and transactional restore
  preferences/      DataStore settings and canonical unit conversion
  rest/             Deadline coordinator, Android alarm/notification adapter and bounded receiver
  nutrition/model/  Food/diary/recipe/goal/weight entities, scaling and estimate calculations
  nutrition/data/   Indexed DAO, schema migration, validation and snapshot converters
  nutrition/catalog/ Streaming versioned local/HTTPS catalog installer
  nutrition/repository/ Targeted personal-data and paginated catalog access
  nutrition/energy/ Completed-workout estimate reconciliation
  viewmodel/        App, workout, library, history, progress, routine and backup state/actions
  ui/navigation/    Bottom navigation and feature routes
  ui/screens/       Existing training screens and focused nutrition screens
  ui/components/    Shared cards, headers, formatting and empty states
  ui/theme/         Original Material 3 themes and typography
  utils/            Validation, statistics, PR detection, localized errors
```

Workout mutations are serialized. Finishing waits for pending writes; sessions are restored from Room after configuration changes and process recreation. History and progress reuse the application's observed workout graph; filtering and progress indexing/calculations run off the UI thread, without database queries per keystroke. Picker usage uses a focused aggregate Room query. The database contains no fake workout history.

## Database

Schema **4** retains `exercises`, `workouts`, `workout_exercises`, `workout_sets`, `routines`, `routine_exercises`, and `app_metadata`. The existing 1→2 migration adds workout muscle groups, and 2→3 adds set types. **3→4 only adds nutrition tables, indexes and FTS synchronization triggers**; workout tables/data are untouched. The registered chain upgrades versions 1/2/3 without clearing data. Foreign keys cascade workout/routine children; exercise references restrict hard deletion. `endTime == null` denotes the persisted active session.

Nutrition tables cover catalog packs, foods (auto-increment internal rowid plus unique stable String ID), nutrients, portions, FTS4 search, diary, recipes/ingredients, effective-date goals, favorites, body weights, settings and workout energy. Diary/ingredient logical food IDs intentionally have no catalog foreign key: immutable snapshots preserve history when catalog/custom foods disappear. Recipe children and food nutrient/portion rows use indexed foreign keys. Workout energy has one row per completed workout with cascading workout deletion; deleting body-weight records sets its measurement reference to null while retaining captured kg. Local ISO days drive diary/goal history independently of timestamp display.

An atomic metadata marker seeds defaults once. Schema JSON is committed under `app/schemas/`. Future versions should increase the database version, add explicit Room migrations to the builder, and add migration tests. Destructive migration is disabled.

## Dependencies and tests

Pinned project toolchain: AGP 9.4.0, Gradle 9.6.0, Kotlin/Compose compiler 2.4.20, KSP 2.3.11, Compose BOM 2026.09.00, Navigation 2.10.2, Lifecycle 2.11.0, Room 2.8.5, DataStore 1.2.1, and Coroutines 1.11.0. Charts use Compose Canvas, with no chart SDK dependency.

See [Android Gradle Plugin compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [AndroidX stable releases](https://developer.android.com/jetpack/androidx/versions), and [Kotlin Coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases).

Unit tests cover calculations, personal records, exercise/set validation, queued workout mutations, backup validation and restore state. Robolectric 4.17 runs Room transactions, migrations, real Compose navigation/recomposition checks and Android alarm/notification behavior on the host, with caches isolated under the project's ignored `.gradle/` directory. Instrumented tests cover Room operations and application navigation. UI tests use an isolated in-memory database and separate test preferences so they never clear user training data.

For the 1.3.0 release, **459 unit/Robolectric tests passed with no failures, errors or skips**, debug and instrumentation APKs built, and lint reported zero findings. The catalog importer's six Python tests also passed. Instrumentation APK compilation does not establish a device-test pass: physical-device alert timing, scrolling, Back-transition smoothness and device screenshots remain unverified.

Run the host checks with the commands in [Build and install](#build-and-install). `connectedDebugAndroidTest` requires a connected emulator or device. Run the catalog pipeline tests separately with:

```text
python -m unittest discover -s tools/catalog -p "test_*.py"
```

On Windows, set `FORGE_TEST_TMP` to a short writable directory if SQLite tests encounter long-path errors. For example, before running the host tests:

```powershell
$env:FORGE_TEST_TMP = "$env:USERPROFILE\forge-test-tmp"
.\gradlew.bat testDebugUnitTest
```
