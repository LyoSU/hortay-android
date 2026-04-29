package dev.lyo.telread.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material.icons.outlined.VideoCameraFront
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App-wide icon entry point.
 *
 * Looks up a Material Symbols-style snake_case name and renders the corresponding glyph.
 * Today the lookup resolves into the `Outlined` variant of `material-icons-extended`,
 * which Google ships as the closest 1:1 match to Material Symbols Outlined Default until
 * an official Compose Material Symbols package lands. The single name → ImageVector map
 * is the only place that needs to change when migrating to the real Symbols set later;
 * the ~50 call sites in the app keep their current `Symbol("name")` form.
 *
 * R8 strips icons that aren't referenced from this file, so the bundled set is still
 * minimal — there's no APK cost from the extended library being on the classpath.
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
        imageVector = symbolVector(name),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * snake_case → Material Icons Outlined ImageVector. Add new icons here when introducing
 * them at call sites; an unmapped name falls through to a help-outline glyph so a typo
 * surfaces visually instead of crashing.
 */
private fun symbolVector(name: String): ImageVector = when (name) {
    "add" -> Icons.Outlined.Add
    "arrow_back" -> Icons.AutoMirrored.Outlined.ArrowBack
    "arrow_downward" -> Icons.Outlined.ArrowDownward
    "arrow_upward" -> Icons.Outlined.ArrowUpward
    "audio_file" -> Icons.Outlined.AudioFile
    "bookmark" -> Icons.Outlined.Bookmark
    "call" -> Icons.Outlined.Call
    "call_end" -> Icons.Outlined.CallEnd
    "call_received" -> Icons.AutoMirrored.Outlined.CallReceived
    "card_giftcard" -> Icons.Outlined.CardGiftcard
    "chat_bubble" -> Icons.Outlined.ChatBubbleOutline
    "check_box" -> Icons.Outlined.CheckBox
    "check_box_outline_blank" -> Icons.Outlined.CheckBoxOutlineBlank
    "close" -> Icons.Outlined.Close
    "cloud_off" -> Icons.Outlined.CloudOff
    "content_copy" -> Icons.Outlined.ContentCopy
    "delete_sweep" -> Icons.Outlined.DeleteSweep
    "description" -> Icons.Outlined.Description
    "edit" -> Icons.Outlined.Edit
    "forum" -> Icons.Outlined.Forum
    "hide_image" -> Icons.Outlined.HideImage
    "home" -> Icons.Outlined.Home
    "info" -> Icons.Outlined.Info
    "ios_share" -> Icons.Outlined.IosShare
    "lock" -> Icons.Outlined.Lock
    "logout" -> Icons.AutoMirrored.Outlined.Logout
    "mic" -> Icons.Outlined.Mic
    "mic_off" -> Icons.Outlined.MicOff
    "notifications_active" -> Icons.Outlined.NotificationsActive
    "notifications_off" -> Icons.Outlined.NotificationsOff
    "open_in_new" -> Icons.AutoMirrored.Outlined.OpenInNew
    "person" -> Icons.Outlined.Person
    "photo_camera" -> Icons.Outlined.PhotoCamera
    "pin" -> Icons.Outlined.Pin
    "place" -> Icons.Outlined.Place
    "play_circle" -> Icons.Outlined.PlayCircle
    "poll" -> Icons.Outlined.Poll
    "push_pin" -> Icons.Outlined.PushPin
    "refresh" -> Icons.Outlined.Refresh
    "repeat" -> Icons.Outlined.Repeat
    "rocket_launch" -> Icons.Outlined.RocketLaunch
    "search" -> Icons.Outlined.Search
    "search_off" -> Icons.Outlined.SearchOff
    "smartphone" -> Icons.Outlined.Smartphone
    "storage" -> Icons.Outlined.Storage
    "sync" -> Icons.Outlined.Sync
    "translate" -> Icons.Outlined.Translate
    "verified" -> Icons.Outlined.Verified
    "video_call" -> Icons.Outlined.VideoCall
    "video_camera_front" -> Icons.Outlined.VideoCameraFront
    "videocam_off" -> Icons.Outlined.VideocamOff
    "visibility" -> Icons.Outlined.Visibility
    "wifi_off" -> Icons.Outlined.WifiOff
    else -> Icons.AutoMirrored.Outlined.HelpOutline
}
