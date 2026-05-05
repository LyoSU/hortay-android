package dev.lyo.hortay.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import dev.lyo.hortay.AppGraph
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Top-level container for the anonymous (guest) reading mode. Displays the
 * web-mode timeline with an add-channel sheet, hooks the in-app "Sign in to
 * Telegram" CTA back to [GuestModeStore.setGuest] (false) so the next
 * recomposition of [MainActivity] hands control to the TDLib auth flow.
 *
 * Why a separate scaffold from `MainScaffold`: the guest flow has a strictly
 * narrower surface — no comments, no deep links from TDLib chats, no folder
 * bar — and the navigation set ("Feed / Channels / Settings") differs. Sharing
 * MainScaffold's bottom nav would either visually misrepresent guest-mode
 * capabilities or require runtime branching on every nav decision. We get a
 * cleaner UX by giving guest mode its own root.
 */
@Composable
fun WebModeScaffold(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // Use the system locale's primary language to drive curated suggestions.
    // Falls back to "en" for any non-Ukrainian device.
    val locale = remember { Locale.getDefault().language.lowercase() }

    WebTimelineScreen(
        feedSource = graph.webFeedSource,
        emojiResolver = graph.webCustomEmoji,
        contentPadding = PaddingValues(),
        onAddChannel = { addSheetOpen = true },
        onSettings = { settingsOpen = true },
    )

    if (addSheetOpen) {
        AddChannelSheet(
            feedSource = graph.webFeedSource,
            client = graph.webClient,
            locale = locale,
            onDismiss = { addSheetOpen = false },
        )
    }

    if (settingsOpen) {
        WebSettingsSheet(
            graph = graph,
            onDismiss = { settingsOpen = false },
            onSignIn = {
                scope.launch {
                    // Flip guest off; MainActivity recomposes and routes to AuthScreen.
                    graph.guestMode.setGuest(false)
                }
            },
        )
    }
}
