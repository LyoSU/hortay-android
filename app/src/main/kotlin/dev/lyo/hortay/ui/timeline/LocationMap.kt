package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.MapThumbnailRequest
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdMediaImage
import kotlin.math.ceil

/**
 * Resolves a static map picture to the id of a TDLib file that is already on disk, suspending
 * until it is; null when TDLib refused or failed it.
 */
typealias MapThumbnailResolver = suspend (MapThumbnailRequest) -> Int?

/**
 * Map-picture resolver for location cards. Provided by the signed-in scaffold; null in guest
 * mode (no TDLib) and previews, where a location card stays a compact row.
 */
val LocalMapThumbnailResolver = staticCompositionLocalOf<MapThumbnailResolver?> { null }

/** Zoom for a fixed point: street level. */
private const val POINT_ZOOM = 15

/** Zoom for a live location: wider, since the point moves and is less precise. */
private const val LIVE_ZOOM = 13

/** TDLib's lower bound for `getMapThumbnailFile` width / height (before scale). */
private const val MAP_MIN_SIDE = 16

/**
 * Widest picture asked for, in logical pixels before scale. TDLib allows 1024, but it fetches
 * the whole PNG in one `upload.getWebFile` capped at 1 MiB and fails the picture when the
 * reply is shorter than the file, so a tablet-wide request at scale 2 risks never arriving;
 * a wider slot upscales this one instead.
 */
private const val MAP_MAX_SIDE = 512

/**
 * Highest scale asked for. TDLib accepts 3, but the official Android client caps the scale
 * at 2 (`min(2, ceil(density))`, ChatMessageCell) and so do the other TDLib clients that
 * draw maps; 3 is the outlier that multiplies the picture's bytes by 2.25 for no visible gain
 * at card size.
 */
private const val MAP_MAX_SCALE = 2

/**
 * Width step the request is snapped to, so cards a few pixels apart (feed vs channel vs
 * comments padding) share one cached picture instead of each costing a download.
 */
private const val MAP_WIDTH_STEP = 32

/**
 * The [MapThumbnailRequest] for a slot [widthPx] wide at [density], 2:1. TDLib's width and
 * height are logical pixels before [MapThumbnailRequest.scale], so the device density,
 * rounded up and capped at [MAP_MAX_SCALE], becomes the scale and the measured width is
 * divided by it. The coordinates pass through untouched: TDLib snaps them to its own map
 * tile and finds the geo point's access hash from that.
 */
internal fun mapThumbnailRequest(
    latitude: Double,
    longitude: Double,
    isLive: Boolean,
    widthPx: Int,
    density: Float,
): MapThumbnailRequest {
    val scale = ceil(density).toInt().coerceIn(1, MAP_MAX_SCALE)
    val logical = (widthPx / scale).coerceIn(MAP_MIN_SIDE, MAP_MAX_SIDE)
    val width = (logical / MAP_WIDTH_STEP * MAP_WIDTH_STEP).coerceAtLeast(MAP_MIN_SIDE)
    return MapThumbnailRequest(
        latitude = latitude,
        longitude = longitude,
        zoom = if (isLive) LIVE_ZOOM else POINT_ZOOM,
        width = width,
        height = (width / 2).coerceAtLeast(MAP_MIN_SIDE),
        scale = scale,
    )
}

/**
 * Static map of a location card's point, filling the 2:1 slot above the row. Resolves only
 * while composed (the card is on screen) and only once the slot has a measured width. The
 * resolver suspends until the picture is on disk (see
 * [dev.lyo.hortay.data.MapThumbnailRepository]) — seconds or minutes, since TDLib fetches it
 * in one slow webfile request — and only then hands its id to the media pipeline, which fades
 * it in over the slot's placeholder. An incomplete map file never reaches [TdMediaImage]: its
 * dispose-time cancel would kill TDLib's in-flight generation. If the picture never comes (a
 * TDLib error), the placeholder simply stays; the slot is never removed, so the card's height
 * doesn't change under the reader. TDLib's map has no marker, so a pin is drawn with its tip
 * on the centre — over the placeholder it marks the slot as a map that is still loading.
 */
@Composable
internal fun LocationMapThumbnail(
    resolver: MapThumbnailResolver,
    latitude: Double,
    longitude: Double,
    isLive: Boolean,
) {
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val request = remember(latitude, longitude, isLive, widthPx, density) {
            mapThumbnailRequest(latitude, longitude, isLive, widthPx, density)
        }
        var fileId by remember(request) { mutableStateOf<Int?>(null) }
        LaunchedEffect(request, resolver) {
            fileId = resolver(request)
        }
        val id = fileId
        if (id != null) {
            val media = remember(id, request) {
                TdMedia(fileId = id, width = request.width * request.scale, height = request.height * request.scale)
            }
            TdMediaImage(
                media = media,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                placeholderColor = null,
                showProgress = false,
            )
        }
        Box(Modifier.align(Alignment.Center)) {
            // The pin glyph's tip sits at ~92 % of its height: lift it so the tip, not the
            // glyph's centre, marks the point.
            Symbol(
                name = "location_on",
                tint = MaterialTheme.colorScheme.primary,
                size = PIN_SIZE,
                filled = true,
                modifier = Modifier.offset(y = -PIN_SIZE * PIN_TIP_LIFT),
            )
        }
    }
}

private val PIN_SIZE = 32.dp
private const val PIN_TIP_LIFT = 0.42f
