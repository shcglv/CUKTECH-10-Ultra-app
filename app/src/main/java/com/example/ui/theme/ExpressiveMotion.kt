package com.example.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * Centralized Material 3 Expressive (MD3E) & Pixel System motion choreography tokens.
 * Uses emphasized spring physics and Pixel shared-axis transitions for natural responsiveness.
 */
object ExpressiveMotion {

    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val StandardEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    fun <T> expressiveSpring() = spring<T>(
        dampingRatio = 0.90f,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> spatialSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> snappySpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun intOffsetSpring() = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    private const val TOP_LEVEL_DURATION_MS = 220
    private const val SUBSCREEN_ENTER_MS = 260
    private const val SUBSCREEN_EXIT_MS = 180

    fun topLevelEnter(): EnterTransition =
        fadeIn(tween(TOP_LEVEL_DURATION_MS, easing = EmphasizedDecelerate)) +
            scaleIn(
                initialScale = 0.985f,
                animationSpec = tween(TOP_LEVEL_DURATION_MS, easing = EmphasizedDecelerate)
            )

    fun topLevelExit(): ExitTransition =
        fadeOut(tween(150, easing = EmphasizedAccelerate)) +
            scaleOut(
                targetScale = 0.99f,
                animationSpec = tween(150, easing = EmphasizedAccelerate)
            )

    fun subscreenEnter(): EnterTransition =
        slideInHorizontally(
            initialOffsetX = { fullWidth -> (fullWidth * 0.10f).toInt() },
            animationSpec = tween(SUBSCREEN_ENTER_MS, easing = EmphasizedDecelerate)
        ) + fadeIn(tween(SUBSCREEN_ENTER_MS, easing = EmphasizedDecelerate)) +
            scaleIn(
                initialScale = 0.985f,
                animationSpec = tween(SUBSCREEN_ENTER_MS, easing = EmphasizedDecelerate)
            )

    fun subscreenExit(): ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -(fullWidth * 0.06f).toInt() },
            animationSpec = tween(SUBSCREEN_EXIT_MS, easing = EmphasizedAccelerate)
        ) + fadeOut(tween(SUBSCREEN_EXIT_MS, easing = EmphasizedAccelerate)) +
            scaleOut(
                targetScale = 0.99f,
                animationSpec = tween(SUBSCREEN_EXIT_MS, easing = EmphasizedAccelerate)
            )

    fun subscreenPopEnter(): EnterTransition =
        slideInHorizontally(
            initialOffsetX = { fullWidth -> -(fullWidth * 0.06f).toInt() },
            animationSpec = tween(SUBSCREEN_ENTER_MS, easing = EmphasizedDecelerate)
        ) + fadeIn(tween(SUBSCREEN_ENTER_MS, easing = EmphasizedDecelerate)) +
            scaleIn(
                initialScale = 0.99f,
                animationSpec = tween(SUBSCREEN_ENTER_MS, easing = EmphasizedDecelerate)
            )

    fun subscreenPopExit(): ExitTransition =
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() },
            animationSpec = tween(SUBSCREEN_EXIT_MS, easing = EmphasizedAccelerate)
        ) + fadeOut(tween(SUBSCREEN_EXIT_MS, easing = EmphasizedAccelerate)) +
            scaleOut(
                targetScale = 0.985f,
                animationSpec = tween(SUBSCREEN_EXIT_MS, easing = EmphasizedAccelerate)
            )

    fun AnimatedContentTransitionScope<NavBackStackEntry>.resolveEnter(
        topLevelRoutes: Set<String>
    ): EnterTransition {
        val targetRoute = targetState.destination.route
        return if (targetRoute in topLevelRoutes) topLevelEnter() else subscreenEnter()
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.resolveExit(
        topLevelRoutes: Set<String>
    ): ExitTransition {
        val targetRoute = targetState.destination.route
        return if (targetRoute in topLevelRoutes) topLevelExit() else subscreenExit()
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.resolvePopEnter(
        topLevelRoutes: Set<String>
    ): EnterTransition {
        val initialRoute = initialState.destination.route
        return if (initialRoute in topLevelRoutes) topLevelEnter() else subscreenPopEnter()
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.resolvePopExit(
        topLevelRoutes: Set<String>
    ): ExitTransition {
        val initialRoute = initialState.destination.route
        return if (initialRoute in topLevelRoutes) topLevelExit() else subscreenPopExit()
    }
}
