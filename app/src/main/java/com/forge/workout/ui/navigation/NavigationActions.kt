package com.forge.workout.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

fun NavHostController.canActFrom(source: NavBackStackEntry): Boolean =
    currentBackStackEntry?.id == source.id && source.lifecycle.currentState == Lifecycle.State.RESUMED

fun NavHostController.popFrom(source: NavBackStackEntry): Boolean =
    canActFrom(source) && popBackStack()
