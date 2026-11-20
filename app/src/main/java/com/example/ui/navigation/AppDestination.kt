package com.example.ui.navigation

enum class AppDestination(
    val route: String,
    val isTopLevel: Boolean
) {
    OVERVIEW("overview", true),
    PORTS("ports", true),
    ANALYTICS("analytics", true),
    SETTINGS("settings", true),
    DIAGNOSTICS("diagnostics", false);

    companion object {
        fun fromRoute(route: String?): AppDestination =
            entries.firstOrNull { it.route == route } ?: OVERVIEW
    }
}
