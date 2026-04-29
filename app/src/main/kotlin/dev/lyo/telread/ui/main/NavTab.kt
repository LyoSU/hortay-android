package dev.lyo.telread.ui.main

/**
 * Bottom-nav tabs. [symbol] is the Material Symbols ligature name (rendered via the
 * [dev.lyo.telread.ui.icons.Symbol] composable in [FloatingNavBar]).
 */
enum class NavTab(val label: String, val symbol: String) {
    Feed("Стрічка", "home"),
    Channels("Канали", "forum"),
    Saved("Збережене", "bookmark"),
    Profile("Профіль", "person"),
}
