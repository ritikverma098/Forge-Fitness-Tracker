# Forge release history

## 1.3.0 — Nutrition tracking

- Added offline food search/details, custom foods, recipes, dated food logging, goals, body-weight history, estimated workout calories and nutrition history.
- Bundled 13,324 USDA foods with source-defined nutrients/portions and a reproducible importer; optional compatible packs can be installed locally or downloaded explicitly.
- Added Nutrition to bottom navigation while retaining Exercise Library and Routines as accessible child screens.
- Added data-preserving Room migration 3→4 and backup format 3, with older workout-only backup support and snapshot-safe nutrition restore.
- Retained the workout UI, logging, routines, set types, history/progress, rest alarms and Back transitions.

## 1.2.1 — Stability changes

1. Restore keyed only the navigation controller, leaving the surviving host bound to the old lifecycle. The controller and host now rebuild together after a committed restore while repositories, Room, DataStore and application ViewModels remain observed. Navigation and normal actions can resume immediately.
2. Rest expiry depended on a resumed workout Composable. A narrow persisted deadline projection now drives an application coordinator and Android alarm. Foreground feedback/background notifications share one conditional SQLite claim. Skip/adjust/finish/delete/restore reconcile the same timer, and resume never replays a stale completion.
3. Workout clocks invalidated the screen, while exercise composition repeatedly sorted history/prior sets. Clocks now update independently; previous performance is indexed off the UI thread, sorted data is cached, and equal Room entries retain references for skipping unchanged rows.
4. Routine pickers had no creation action. They reuse the library editor, validation and save path, retain filters and pin new exercises. Requested save outcomes remain in the ViewModel across rotation; interrupted process-style recreation leaves a usable editor.

Important components: RestoreNavigation/ForgeApp; RestTimerCoordinator/AndroidRestAlerts/RestAlarmReceiver, application/activity and RestAlertSettings; WorkoutScreen/WorkoutClocks/WorkoutPreviousPerformance; RoutineDialogs/RoutinesScreen and LibraryViewModel.

Room schema stays **3**, migrations **1→2→3** stay intact, and backup format stays **2** with legacy format 1 support. No destructive migration or production dependency upgrade. Test-only Compose host dependencies support real navigation/recomposition regressions.

Version **1.2.1**, code **4**, original application ID and signing certificate. Android permissions added: POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, VIBRATE, RECEIVE_BOOT_COMPLETED and a bounded receiver WAKE_LOCK. Notifications and exact-alarm access are explicit user choices; inexact alarms can be delayed by Android. No network permission or permanent service.

See [README.md](README.md) for the current architecture, build instructions, testing and development status. Machine-specific verification logs are excluded from source publication.
