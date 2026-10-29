package com.forge.workout.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.forge.workout.ForgeApplication
import com.forge.workout.R
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.backup.LocalBackupDocuments
import com.forge.workout.utils.userMessage
import com.forge.workout.ui.screens.home.HomeScreen
import com.forge.workout.ui.screens.history.*
import com.forge.workout.ui.screens.progress.ProgressScreen
import com.forge.workout.ui.screens.library.ExerciseLibraryScreen
import com.forge.workout.ui.screens.routines.RoutinesScreen
import com.forge.workout.ui.screens.settings.SettingsScreen
import com.forge.workout.ui.screens.workout.WorkoutScreen
import com.forge.workout.ui.screens.nutrition.NutritionScreen
import com.forge.workout.ui.screens.nutrition.WorkoutCaloriesCard
import com.forge.workout.ui.theme.ForgeTheme
import com.forge.workout.viewmodel.*
import com.forge.workout.preferences.ThemeMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

internal data class Destination(val route: String,val label: Int,val icon: ImageVector)
internal val destinations = listOf(
    Destination("home",R.string.home,Icons.Rounded.Home),Destination("workout",R.string.workout,Icons.Rounded.FitnessCenter),
    Destination("nutrition",R.string.nutrition,Icons.Rounded.Restaurant),Destination("history",R.string.history,Icons.Rounded.CalendarMonth),
    Destination("progress",R.string.progress,Icons.AutoMirrored.Rounded.TrendingUp)
)
@Composable fun ForgeApp(application: ForgeApplication) {
    val formatError: (Throwable) -> String = remember(application) { { failure -> userMessage(application, failure) } }
    val appViewModel: AppViewModel = viewModel(factory = viewModelFactory { initializer {
        AppViewModel(application.repository, application.preferences, formatError, { application.getString(R.string.new_workout_name) })
    } })
    val workoutViewModel: WorkoutViewModel = viewModel(factory = viewModelFactory { initializer { WorkoutViewModel(application.repository, formatError) } })
    val libraryViewModel: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(application.repository, formatError) } })
    val routineViewModel: RoutineViewModel = viewModel(factory = viewModelFactory { initializer { RoutineViewModel(application.repository, formatError) } })
    val catalogViewModel: CatalogViewModel = viewModel(factory = viewModelFactory { initializer { CatalogViewModel(application.catalogs, application.nutritionRepository) } })
    val foodLogViewModel: FoodLogViewModel = viewModel(factory = viewModelFactory { initializer {
        FoodLogViewModel(application.nutritionRepository, createSavedStateHandle(), formatError)
    } })
    val foodMutationViewModel: FoodMutationViewModel = viewModel(factory = viewModelFactory { initializer {
        FoodMutationViewModel(application.nutritionRepository, formatError)
    } })
    val diaryMutationViewModel: DiaryMutationViewModel = viewModel(factory = viewModelFactory { initializer {
        DiaryMutationViewModel(application.nutritionRepository, formatError)
    } })
    val nutritionEnergyWriter: NutritionEnergyMutationViewModel = viewModel(factory = viewModelFactory { initializer {
        NutritionEnergyMutationViewModel(application.nutritionRepository, formatError)
    } })
    LaunchedEffect(application) { application.workoutEnergy.start() }
    val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(createSavedStateHandle()) } })
    val progressViewModel: ProgressViewModel = viewModel(factory = viewModelFactory { initializer { ProgressViewModel(createSavedStateHandle()) } })
    val backupViewModel: BackupViewModel = viewModel(factory = viewModelFactory { initializer {
        BackupViewModel(application.backups, application.preferences, LocalBackupDocuments(application),
            flushBeforeOperation = {
                if(!workoutViewModel.flush()) false else {
                    libraryViewModel.busy.first { !it }
                    routineViewModel.busy.first { !it }
                    appViewModel.flush()
                    foodLogViewModel.flush()
                    foodMutationViewModel.flush()
                    diaryMutationViewModel.flush()
                    nutritionEnergyWriter.flush()
                    true
                }
            }, onRestored = {
                workoutViewModel.resetAfterRestore()
                libraryViewModel.clearError()
                routineViewModel.clearError()
                historyViewModel.clearFilters()
                progressViewModel.resetSelection()
                foodLogViewModel.resetAfterRestore()
                foodMutationViewModel.resetAfterRestore()
                diaryMutationViewModel.resetAfterRestore()
                nutritionEnergyWriter.resetAfterRestore()
            }, beforeRestore = {
                application.workoutEnergy.pauseForRestore()
                application.restTimers.pauseForRestore()
            }, afterRestoreAttempt = {
                try { application.restTimers.resumeAfterRestore() }
                finally { application.workoutEnergy.resumeAfterRestore() }
            })
    } })
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val requestedWorkoutDeletions = remember(backupState.restoreGeneration) { mutableMapOf<Long, Pair<Set<Long>, Set<Long>>>() }
    val activity = LocalActivity.current as? ComponentActivity
    val dark = when(state.preferences.themeMode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    DisposableEffect(activity, dark) {
        val style = if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style,navigationBarStyle = style)
        onDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val appErrors = remember(appViewModel) { appViewModel.events }
    LaunchedEffect(workoutViewModel) {
        // Re-publishing a blocked save must not cancel the action on an already visible snackbar.
        workoutViewModel.errorNotice.collect { notice -> if (notice != null) {
            val result = coroutineScope {
                val showing = async { snackbar.showSnackbar(notice.message, actionLabel = application.getString(R.string.retry_failed_changes), withDismissAction = true) }
                val resolved = async { workoutViewModel.awaitResolution(notice) }
                try {
                    select {
                        showing.onAwait { it }
                        resolved.onAwait { SnackbarResult.Dismissed }
                    }
                } finally {
                    // Cancel only this snackbar request, including when it is queued behind another message.
                    showing.cancel()
                    resolved.cancel()
                }
            }
            workoutViewModel.clearError(notice)
            if (result == SnackbarResult.ActionPerformed) workoutViewModel.retryFailedChanges()
        } }
    }
    LaunchedEffect(backupViewModel) {
        backupViewModel.events.collect { message ->
            // The workout flush already presents this failure with its Retry action.
            if (message != R.string.failed_save_retry) scope.launch { snackbar.showSnackbar(application.getString(message)) }
        }
    }
    // Recreate the controller and its host together so the host binds the new lifecycle.
    RestoreNavigation(backupState.restoreGeneration) { nav ->
    val navigationEntry by nav.currentBackStackEntryAsState()
    val mainDestination = destinations.any { it.route == navigationEntry?.destination?.route }
    val persistentTabBar = rememberPersistentTabBarState(if (state.ready) navigationEntry?.destination?.route else null)
    LaunchedEffect(appErrors, nav, requestedWorkoutDeletions) { appErrors.collect { event -> when(event) {
        is AppEvent.Message -> scope.launch { snackbar.showSnackbar(event.value) }
        else -> {
            val generation = backupViewModel.state.value.restoreGeneration
            backupViewModel.state.first { !it.busy }
            if(generation == backupViewModel.state.value.restoreGeneration) when(event) {
                is AppEvent.OpenWorkout -> nav.navigate("workout") { launchSingleTop = true; popUpTo("home") }
                is AppEvent.OpenSummary -> nav.navigate("summary/${event.id}") { popUpTo("home"); launchSingleTop = true }
                AppEvent.GoHome -> {
                    requestedWorkoutDeletions.toMap().forEach { (id, entities) ->
                        workoutViewModel.forgetDeletedWorkout(id, entities.first, entities.second)
                    }
                    requestedWorkoutDeletions.clear()
                    nav.navigate("home") { popUpTo("home") { inclusive = true }; launchSingleTop = true }
                }
                is AppEvent.Message -> Unit
            }
        }
    } } }
    fun openTab(destination: String) {
        nav.openMainTab(destination)
    }
    fun openChild(source: NavBackStackEntry, destination: String) {
        if(nav.canActFrom(source)) nav.navigate(destination) { launchSingleTop = true }
    }
    fun deleteWorkout(detail: WorkoutDetail) {
        requestedWorkoutDeletions[detail.workout.id] = detail.exercises.map { it.entry.id }.toSet() to
            detail.exercises.flatMap { it.sets }.map { it.id }.toSet()
        appViewModel.deleteWorkout(detail.workout.id)
    }
    ForgeTheme(state.preferences.themeMode) {
        Scaffold(snackbarHost = { if (!mainDestination) SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(),contentAlignment = Alignment.TopCenter) {
                if(!state.ready) {
                    Column(Modifier.align(Alignment.Center),horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(); Text(stringResource(R.string.loading)) }
                } else CompositionLocalProvider(LocalPersistentTabBar provides persistentTabBar) {
                NavHost(navController = nav,startDestination = "home",modifier = Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth().testTag("forge_navigation_host"),
                    enterTransition = { fadeIn(tween(300)) }, exitTransition = { fadeOut(tween(300)) },
                    popEnterTransition = { fadeIn(tween(300)) }, popExitTransition = { fadeOut(tween(300)) },
                    // Predictive Back has separate defaults; use Forge's fades instead of screen scaling.
                    predictivePopEnterTransition = { _ -> fadeIn(tween(300)) },
                    predictivePopExitTransition = { _ -> fadeOut(tween(300)) }) {
                    composable("home") { backStack ->
                        MainTab("home", snackbar) {
                            HomeScreen(state.workouts,state.activeWorkout,state.preferences.weightUnit,{ appViewModel.start() },{ openTab("workout") },{ openTab("history") },{ openChild(backStack,"exercises") },{ openChild(backStack,"routines") },{ openChild(backStack,"settings") })
                        }
                    }
                    composable("workout") { backStack ->
                        MainTab("workout", snackbar) {
                        WorkoutScreen(state.activeWorkout,state.workouts,state.preferences,workoutViewModel,false,{ appViewModel.start() },{ openChild(backStack,"routines") },{
                            state.activeWorkout?.let { openChild(backStack,"picker/${it.workout.id}") }
                        },{
                            state.activeWorkout?.let { detail -> scope.launch { if(workoutViewModel.flush()) appViewModel.finish(detail.workout.id) } }
                        },{ state.activeWorkout?.let { detail -> scope.launch {
                            workoutViewModel.flush()
                            deleteWorkout(detail)
                        } } },{ nav.popFrom(backStack) },appViewModel::setRest, { openChild(backStack,"exercises") })
                        }
                    }
                    composable("nutrition") { backStack ->
                        LaunchedEffect(catalogViewModel) { catalogViewModel.ensureBundled() }
                        val dashboard: NutritionDashboardViewModel = viewModel(factory = viewModelFactory { initializer {
                            NutritionDashboardViewModel(application.nutritionRepository, foodLogViewModel.day)
                        } })
                        val diaryBusy by diaryMutationViewModel.busy.collectAsStateWithLifecycle()
                        val diaryResult by diaryMutationViewModel.result.collectAsStateWithLifecycle()
                        val diaryFeedbackResult = diaryResult
                        MainTab("nutrition", snackbar) {
                            NutritionScreen(dashboard, onDay = foodLogViewModel::selectDay,
                                onCatalog = { openChild(backStack,"nutrition/catalog") },
                                onAddFood = { meal ->
                                    foodLogViewModel.selectMeal(meal)
                                    openChild(backStack,"nutrition/add")
                                }, onEntry = { id -> openChild(backStack,"nutrition/entry/$id") },
                                onGoals = { openChild(backStack,"nutrition/goals/${foodLogViewModel.day.value}") },
                                onRepeat = { ids, day, meal ->
                                    if (nav.canActFrom(backStack)) diaryMutationViewModel.repeat(backStack.id, ids, day, meal)
                                }, onDelete = { id ->
                                    if (nav.canActFrom(backStack)) diaryMutationViewModel.delete(backStack.id, id)
                                }, busy = diaryBusy,
                                feedback = diaryResult?.takeIf { it.requestId == backStack.id }?.let {
                                    it.error ?: application.getString(R.string.diary_updated)
                                },
                                onFeedbackHandled = { diaryFeedbackResult?.let { diaryMutationViewModel.acknowledge(it) } },
                                feedbackToken = diaryFeedbackResult?.takeIf { it.requestId == backStack.id },
                                onBodyWeights = { openChild(backStack,"nutrition/body-weight") },
                                onEnergySettings = { openChild(backStack,"nutrition/energy-settings") },
                                onHistory = { openChild(backStack,"nutrition/history") })
                        }
                    }
                    nutritionChildren(nav, application, catalogViewModel, foodLogViewModel, foodMutationViewModel)
                    nutritionDiaryChildren(nav, application, diaryMutationViewModel, foodLogViewModel)
                    nutritionEnergyChildren(nav, application, nutritionEnergyWriter, state.preferences.weightUnit)
                    composable("exercises") { backStack -> ExerciseLibraryScreen(state.exercises,libraryViewModel,onBack = { nav.popFrom(backStack) }) }
                    composable("history") { backStack -> MainTab("history", snackbar) { HistoryScreen(state.workouts,state.preferences.weightUnit,historyViewModel) { openChild(backStack,"detail/$it") } } }
                    composable("progress") { MainTab("progress", snackbar) { ProgressScreen(state.exercises,state.workouts,state.preferences.weightUnit,progressViewModel) } }
                    composable("routines") { backStack -> RoutinesScreen(state.routines,state.exercises,routineViewModel,libraryViewModel,{ appViewModel.start(routineId = it) },{ nav.popFrom(backStack) }) }
                    composable("settings") { backStack -> SettingsScreen(state.preferences,{ nav.popFrom(backStack) },appViewModel::setTheme,appViewModel::setUnit,appViewModel::setRest,appViewModel::setConfirmation,
                        backupState,backupViewModel::writeBackup,backupViewModel::readBackup,backupViewModel::confirmRestore,backupViewModel::cancelRestore) }
                    composable("picker/{id}",arguments = listOf(navArgument("id") { type = NavType.LongType })) { backStack ->
                        val id = backStack.arguments?.getLong("id") ?: return@composable
                        ExerciseLibraryScreen(state.exercises,libraryViewModel,{ exercise -> if(nav.canActFrom(backStack)) { workoutViewModel.addExercise(id,exercise.id); nav.popFrom(backStack) } },{ nav.popFrom(backStack) },
                            workoutMuscleGroups = state.workouts.firstOrNull { it.workout.id == id }?.workout?.muscleGroups.orEmpty().toSet())
                    }
                    composable("editor/{id}",arguments = listOf(navArgument("id") { type = NavType.LongType })) { backStack ->
                        val id = backStack.arguments?.getLong("id") ?: return@composable
                        val detail = state.workouts.firstOrNull { it.workout.id == id }
                        WorkoutScreen(detail,state.workouts,state.preferences,workoutViewModel,true,{},{},{ openChild(backStack,"picker/$id") },{ scope.launch { if(workoutViewModel.flush()) nav.popFrom(backStack) } },{},{ nav.popFrom(backStack) },appViewModel::setRest)
                    }
                    listOf("detail/{id}","summary/{id}").forEach { pattern ->
                        composable(pattern,arguments = listOf(navArgument("id") { type = NavType.LongType })) { backStack ->
                            val id = backStack.arguments?.getLong("id") ?: return@composable
                            WorkoutDetailScreen(state.workouts.firstOrNull { it.workout.id == id },state.workouts,state.preferences.weightUnit,pattern.startsWith("summary"),{ nav.popFrom(backStack) },{ openChild(backStack,"editor/$it") },{ appViewModel.start(repeatId = it) },{ workoutId -> scope.launch {
                                workoutViewModel.flush()
                                state.workouts.firstOrNull { it.workout.id == workoutId }?.let(::deleteWorkout)
                            } },state.preferences.confirmDeletion, extraContent = {
                                val energy: WorkoutEnergyViewModel = viewModel(key = "workout_energy_$id",
                                    factory = viewModelFactory { initializer { WorkoutEnergyViewModel(application.nutritionRepository, id) } })
                                WorkoutCaloriesCard(energy,
                                    onEdit = { openChild(backStack,"nutrition/energy/$id") },
                                    onWeights = { openChild(backStack,"nutrition/body-weight") },
                                    weightUnit = state.preferences.weightUnit)
                            })
                        }
                    }
                }
                }
                PersistentTabBar(persistentTabBar, ::openTab, canOpenTab = {
                    val source = nav.currentBackStackEntry
                    source != null && destinations.any { it.route == source.destination.route } && nav.canActFrom(source)
                }, modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 840.dp).fillMaxWidth())
            }
        }
    }
}
}
