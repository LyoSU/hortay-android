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
 * vector drawable. Source axis pinned to **Rounded · weight 500 · grade 0 · 24 dp**
 * — Google's canonical pairing for M3 Expressive consumer apps with bold display
 * typography (Plus Jakarta Sans Bold here). Weight 400 reads thin against
 * `displaySmall` 32 sp ExtraBold; weight 500 balances visually without crossing
 * into "loud" weight 600+.
 *
 * Some icons ship a `_filled` companion drawable (e.g. `sym_home_filled.xml`,
 * `sym_bookmark_filled.xml`) for the `filled = true` axis — used to express
 * selected / active state in nav bars and toggles, the M3 Expressive convention.
 * When a drawable lacks a filled twin the parameter is silently a no-op so call
 * sites can pass `filled = isSelected` without branching.
 *
 * To add a new symbol:
 *   1. Visit https://fonts.google.com/icons, pick the icon, choose "Rounded".
 *   2. Set Weight=500, Optical size=24px, Fill=0, Grade=0.
 *   3. Click Android → save as `res/drawable/sym_<name>.xml`.
 *   4. Add a `"<name>" -> R.drawable.sym_<name>` line below.
 */
@Composable
fun Symbol(
    name: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    filled: Boolean = false,
) {
    Icon(
        painter = painterResource(symbolDrawable(name, filled)),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@DrawableRes
private fun symbolDrawable(name: String, filled: Boolean = false): Int {
    if (filled) {
        when (name) {
            "home" -> return R.drawable.sym_home_filled
            "bookmark" -> return R.drawable.sym_bookmark_filled
            "person" -> return R.drawable.sym_person_filled
            "forum" -> return R.drawable.sym_forum_filled
            "dynamic_feed" -> return R.drawable.sym_dynamic_feed_filled
            "chat_bubble" -> return R.drawable.sym_chat_bubble_filled
            "push_pin" -> return R.drawable.sym_push_pin_filled
            "notifications_active" -> return R.drawable.sym_notifications_active_filled
            // No filled variant bundled — fall through to the outline below.
        }
    }
    return when (name) {
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
    // The "report a post" action sheet asks for `flag`. Until a dedicated
    // `sym_flag.xml` is bundled, route to `shield` — the closest moderation
    // glyph in the existing pack. The previous silent `sym_help` fallback
    // landed a question-mark icon on the Report row.
    "flag" -> R.drawable.sym_shield
    "chat_bubble" -> R.drawable.sym_chat_bubble
    "check_box" -> R.drawable.sym_check_box
    "check_box_outline_blank" -> R.drawable.sym_check_box_outline_blank
    "close" -> R.drawable.sym_close
    "cloud_off" -> R.drawable.sym_cloud_off
    "content_copy" -> R.drawable.sym_content_copy
    "delete" -> R.drawable.sym_delete
    "delete_sweep" -> R.drawable.sym_delete_sweep
    "description" -> R.drawable.sym_description
    "dynamic_feed" -> R.drawable.sym_dynamic_feed
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
    "pause" -> R.drawable.sym_pause
    "person" -> R.drawable.sym_person
    "photo_camera" -> R.drawable.sym_photo_camera
    "pin" -> R.drawable.sym_pin
    "place" -> R.drawable.sym_location_on
    "play_arrow" -> R.drawable.sym_play_arrow
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
    "volume_off" -> R.drawable.sym_volume_off
    "volume_up" -> R.drawable.sym_volume_up
    "wifi_off" -> R.drawable.sym_wifi_off
    // Auto-download settings — closest existing glyphs while we don't bundle the
    // dedicated Material Symbols vectors. Visual parity is good enough that the
    // user identifies each row at a glance; swap in dedicated drawables later by
    // following the file's KDoc procedure (download from fonts.google.com/icons).
    "wifi" -> R.drawable.sym_rss_feed
    "signal_cellular_alt" -> R.drawable.sym_smartphone
    "public" -> R.drawable.sym_translate
    "chevron_right" -> R.drawable.sym_arrow_forward
    "image" -> R.drawable.sym_photo_camera
    "gif_box" -> R.drawable.sym_play_circle
    "data_saver_off" -> R.drawable.sym_cloud_off
    "download_for_offline" -> R.drawable.sym_arrow_downward
    "download" -> R.drawable.sym_arrow_downward
    else -> R.drawable.sym_help
    }
}
