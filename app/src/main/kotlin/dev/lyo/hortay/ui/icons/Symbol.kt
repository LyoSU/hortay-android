package dev.lyo.hortay.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R

/**
 * App-wide icon entry point.
 *
 * Resolves a Material Symbols snake_case name to a bundled `res/drawable/sym_*.xml`
 * vector drawable downloaded from `fonts.google.com/icons` (canonical Google source,
 * synced from `google/material-design-icons` on GitHub). This is the path Google
 * officially recommends after deprecating `androidx.compose.material:material-icons*`
 * in 2026: bundle only the icons you use as individual XML drawables, ~1-2 KB each,
 * stripped of unused glyphs at compile time.
 *
 * The 50+ call sites in the app keep their `Symbol("name")` form — only this single
 * file changes when adding / swapping / re-styling an icon. To add a new symbol:
 *   1. Visit https://fonts.google.com/icons , pick the icon, choose "Rounded" style.
 *   2. Click the Android tab → Download → save as `res/drawable/sym_<name>.xml`.
 *   3. Add a `"<name>" -> R.drawable.sym_<name>` line below.
 */
@Composable
fun Symbol(
    name: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(
        painter = painterResource(symbolDrawable(name)),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@DrawableRes
private fun symbolDrawable(name: String): Int = when (name) {
    "add" -> R.drawable.sym_add
    "arrow_back" -> R.drawable.sym_arrow_back
    "arrow_downward" -> R.drawable.sym_arrow_downward
    "arrow_forward" -> R.drawable.sym_arrow_forward
    "arrow_upward" -> R.drawable.sym_arrow_upward
    "audio_file" -> R.drawable.sym_audio_file
    "bookmark" -> R.drawable.sym_bookmark
    "call" -> R.drawable.sym_call
    "call_end" -> R.drawable.sym_call_end
    "call_received" -> R.drawable.sym_call_received
    "campaign" -> R.drawable.sym_campaign
    // card_giftcard / place / poll were renamed in Material Symbols — map old names
    // to the modern glyph so call sites don't have to chase the rename.
    "card_giftcard" -> R.drawable.sym_redeem
    "chat_bubble" -> R.drawable.sym_chat_bubble
    "check_box" -> R.drawable.sym_check_box
    "check_box_outline_blank" -> R.drawable.sym_check_box_outline_blank
    "close" -> R.drawable.sym_close
    "cloud_off" -> R.drawable.sym_cloud_off
    "content_copy" -> R.drawable.sym_content_copy
    "delete" -> R.drawable.sym_delete
    "delete_sweep" -> R.drawable.sym_delete_sweep
    "description" -> R.drawable.sym_description
    "edit" -> R.drawable.sym_edit
    "forum" -> R.drawable.sym_forum
    "hide_image" -> R.drawable.sym_hide_image
    "home" -> R.drawable.sym_home
    "info" -> R.drawable.sym_info
    "ios_share" -> R.drawable.sym_ios_share
    "lock" -> R.drawable.sym_lock
    "login" -> R.drawable.sym_login
    "logout" -> R.drawable.sym_logout
    "mic" -> R.drawable.sym_mic
    "mic_off" -> R.drawable.sym_mic_off
    "notifications_active" -> R.drawable.sym_notifications_active
    "notifications_off" -> R.drawable.sym_notifications_off
    "open_in_new" -> R.drawable.sym_open_in_new
    "person" -> R.drawable.sym_person
    "photo_camera" -> R.drawable.sym_photo_camera
    "pin" -> R.drawable.sym_pin
    "place" -> R.drawable.sym_location_on
    "play_circle" -> R.drawable.sym_play_circle
    "poll" -> R.drawable.sym_ballot
    "push_pin" -> R.drawable.sym_push_pin
    "refresh" -> R.drawable.sym_refresh
    "repeat" -> R.drawable.sym_repeat
    "rocket_launch" -> R.drawable.sym_rocket_launch
    "rss_feed" -> R.drawable.sym_rss_feed
    "search" -> R.drawable.sym_search
    "search_off" -> R.drawable.sym_search_off
    "shield" -> R.drawable.sym_shield
    "smartphone" -> R.drawable.sym_smartphone
    "storage" -> R.drawable.sym_storage
    "sync" -> R.drawable.sym_sync
    "translate" -> R.drawable.sym_translate
    "verified" -> R.drawable.sym_verified
    "video_call" -> R.drawable.sym_video_call
    "video_camera_front" -> R.drawable.sym_video_camera_front
    "videocam_off" -> R.drawable.sym_videocam_off
    "visibility" -> R.drawable.sym_visibility
    "visibility_off" -> R.drawable.sym_visibility_off
    "wifi_off" -> R.drawable.sym_wifi_off
    else -> R.drawable.sym_help
}
