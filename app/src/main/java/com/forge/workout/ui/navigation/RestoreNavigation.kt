package com.forge.workout.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

/** A replacement controller must also receive a fresh NavHost lifecycle binding. */
@Composable
internal fun RestoreNavigation(
    generation: Int,
    content: @Composable (NavHostController) -> Unit,
) {
    key(generation) {
        content(rememberNavController())
    }
}
