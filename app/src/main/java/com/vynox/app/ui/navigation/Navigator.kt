package com.vynox.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Minimal, dependency-free navigation for Vynox.
 *
 * The app is a single activity with a small, fixed set of screens and no deep
 * links or argument passing, so a tiny back stack over a sealed route is enough
 * - and it keeps the Compose tree (and the editor's GL surface) under our
 * control instead of delegating it to a navigation library.
 */
class Navigator(start: Screen) {

    private var stack by mutableStateOf(listOf(start))

    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    val size: Int get() = stack.size

    fun navigate(screen: Screen) {
        stack = stack + screen
    }

    /** Navigates and drops everything above [screen] (no-op if it is not in the stack). */
    fun popUpTo(screen: Screen, inclusive: Boolean = false) {
        val index = stack.lastIndexOf(screen)
        if (index < 0) return
        stack = if (inclusive) stack.take(index) else stack.take(index + 1)
    }

    fun replace(screen: Screen) {
        stack = stack.dropLast(1) + screen
    }

    fun navigateReplacing(screen: Screen, target: Screen) {
        val index = stack.lastIndexOf(target)
        stack = if (index >= 0) stack.take(index) + screen else stack + screen
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }
}

/**
 * Renders [content] for the current screen with a short crossfade, and wires the
 * system back button to the navigator.
 */
@Composable
fun NavHost(
    navigator: Navigator,
    onExit: () -> Unit = {},
    content: @Composable (Screen) -> Unit
) {
    val screen = navigator.current

    BackHandler(enabled = navigator.canPop) {
        if (!navigator.pop()) onExit()
    }

    Crossfade(
        targetState = screen,
        animationSpec = tween(180),
        label = "vynox-nav"
    ) { current ->
        content(current)
    }
}
