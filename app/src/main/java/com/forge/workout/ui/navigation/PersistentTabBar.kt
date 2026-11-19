package com.forge.workout.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
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
    val colors = MaterialTheme.colorScheme
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val compactLabels = with(LocalDensity.current) { windowWidth.toDp().value / fontScale < 280f }
    Surface(
        modifier = Modifier.testTag("forge_bottom_navigation").onSizeChanged(onMeasured),
        color = colors.surfaceContainerLowest,
    ) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                destinations.forEach { destination ->
                    val active = route == destination.route
                    val label = stringResource(destination.label)
                    val tint = if (active) colors.primary else colors.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).heightIn(min = if (compactLabels) 48.dp else 62.dp)
                            .selectable(selected = active, onClick = { onOpenTab(destination.route) }, role = Role.Tab),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.fillMaxWidth(.62f).height(2.dp)
                            .background(if (active) colors.primary else androidx.compose.ui.graphics.Color.Transparent))
                        Spacer(Modifier.height(8.dp))
                        Icon(destination.icon, label.takeIf { compactLabels }, Modifier.size(23.dp), tint = tint)
                        if (!compactLabels) Text(
                            label, Modifier.padding(horizontal = 2.dp, vertical = 4.dp), color = tint,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (compactLabels) destinations.firstOrNull { it.route == route }?.let { selected ->
                Text(
                    stringResource(selected.label),
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clearAndSetSemantics {},
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
            }
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
