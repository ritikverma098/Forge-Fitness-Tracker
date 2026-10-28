package com.forge.workout.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Stable
internal class PersistentTabBarState(private val currentRoute: State<String?>) {
    private data class Entry(val route: String, val alpha: State<Float>, val order: Long)
    private val entries = mutableStateMapOf<Any, Entry>()
    private var nextOrder = 0L
    private var lastMainRoute by mutableStateOf("home")
    var height by mutableStateOf(80.dp)
    val interactive get() = destinations.any { it.route == currentRoute.value }
    val selectedRoute get() = currentRoute.value?.takeIf { route -> destinations.any { it.route == route } }
        ?: entries.values.maxByOrNull { it.order }?.route ?: lastMainRoute
    // Only placement and drawing observe animation frames; the retained Material content does not.
    val alpha get() = entries.values.sumOf { it.alpha.value.toDouble() }.toFloat().coerceIn(0f, 1f)
    fun register(owner: Any, route: String, alpha: State<Float>) {
        entries[owner] = Entry(route, alpha, nextOrder++)
        lastMainRoute = route
    }
    fun unregister(owner: Any) { entries.remove(owner) }
}

internal val LocalPersistentTabBar = staticCompositionLocalOf<PersistentTabBarState> { error("Missing tab bar owner") }

@Composable
internal fun rememberPersistentTabBarState(route: String?): PersistentTabBarState {
    val currentRoute = rememberUpdatedState(route)
    return remember { PersistentTabBarState(currentRoute) }
}

@Composable
internal fun PersistentTabBar(
    state: PersistentTabBarState,
    onOpenTab: (String) -> Unit,
    canOpenTab: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val latestOpen = rememberUpdatedState(onOpenTab)
    val latestCanOpen = rememberUpdatedState(canOpenTab)
    val open = remember(state) { { route: String ->
        if (state.interactive && state.alpha > 0f && latestCanOpen.value()) latestOpen.value(route)
    } }
    val density = LocalDensity.current
    val measured: (androidx.compose.ui.unit.IntSize) -> Unit = remember(state, density) { { size ->
        state.height = with(density) { size.height.toDp() }
    } }
    Layout(
        modifier = modifier.then(if (state.interactive) Modifier else Modifier.clearAndSetSemantics { })
            .graphicsLayer { alpha = state.alpha },
        content = { RetainedNavigationBar(state.selectedRoute, open, measured) },
    ) { measurables, constraints ->
        val bar = measurables.single().measure(constraints)
        layout(bar.width, bar.height) {
            // An unplaced child cannot intercept taps beneath a fully hidden bar.
            if (state.alpha > 0f) bar.placeRelative(0, 0)
        }
    }
}

@Composable
private fun RetainedNavigationBar(route: String, onOpenTab: (String) -> Unit, onMeasured: (androidx.compose.ui.unit.IntSize) -> Unit) {
    NavigationBar(
        modifier = Modifier.testTag("forge_bottom_navigation").onSizeChanged(onMeasured),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        destinations.forEach { destination ->
            NavigationBarItem(selected = route == destination.route, onClick = { onOpenTab(destination.route) },
                icon = { Icon(destination.icon, stringResource(destination.label)) },
                label = { Text(stringResource(destination.label), maxLines = 1) })
        }
    }
}

@Composable
internal fun AnimatedContentScope.MainTab(route: String, snackbar: SnackbarHostState, content: @Composable () -> Unit) {
    val bar = LocalPersistentTabBar.current
    val alpha = transition.animateFloat(transitionSpec = { tween(300) }, label = "tab_bar_alpha") {
        if (it == EnterExitState.Visible) 1f else 0f
    }
    val owner = remember { Any() }
    DisposableEffect(bar, owner, route, alpha) {
        bar.register(owner, route, alpha)
        onDispose { bar.unregister(owner) }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.fillMaxWidth().height(bar.height))
    }
}
