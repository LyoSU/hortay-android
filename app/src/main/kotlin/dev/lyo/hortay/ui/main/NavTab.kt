package dev.lyo.hortay.ui.main

import androidx.annotation.StringRes
import dev.lyo.hortay.R

/**
 * Bottom-nav tabs. [symbol] is the Material Symbols ligature name (rendered via the
 * [dev.lyo.hortay.ui.icons.Symbol] composable in [FloatingNavBar]). [labelRes] is
 * resolved at draw time with `stringResource` so the tab name follows the active
 * locale.
 */
enum class NavTab(@StringRes val labelRes: Int, val symbol: String) {
    Feed(R.string.nav_feed, "home"),
    Channels(R.string.nav_channels, "dynamic_feed"),
    Saved(R.string.nav_saved, "bookmark"),
    Profile(R.string.nav_profile, "person"),
}
