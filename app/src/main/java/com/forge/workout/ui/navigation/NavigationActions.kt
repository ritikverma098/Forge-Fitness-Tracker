package com.forge.workout.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

fun NavHostController.canActFrom(source: NavBackStackEntry): Boolean =
    currentBackStackEntry?.id == source.id && source.lifecycle.currentState == Lifecycle.State.RESUMED

fun NavHostController.popFrom(source: NavBackStackEntry): Boolean =
    canActFrom(source) && popBackStack()

internal fun NavHostController.openMainTab(destination: String) {
    val source = currentBackStackEntry ?: return
    if (!canActFrom(source) || source.destination.route == destination) return
    navigate(destination) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        // Home is already retained by popUpTo. A saved pop can alias its ID to
        // another tab's stack, so restoring Home can incorrectly reopen Workout.
        restoreState = destination != "home"
    }
}
