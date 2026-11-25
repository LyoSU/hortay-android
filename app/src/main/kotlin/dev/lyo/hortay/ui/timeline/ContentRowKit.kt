package dev.lyo.hortay.ui.timeline

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.lyo.hortay.data.InvoicePrice
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdMediaImage
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/*
 * Shared vocabulary for the post body's "content rows" — file, audio, voice, location,
 * contact and open-in-Telegram cards. Before this file each block picked its own corner
 * (voice 24 dp, audio / docs 18 dp, code 12 dp, quote 10 dp) and its own leading badge, so a
 * post mixing a file and a code block read as two design systems. Everything here matches the
 * code block's container (12 dp, surfaceContainerHigh) so a post reads as one system.
 */

/** Corner of every in-body content container (file / audio / voice / location / contact). */
internal val ContentBlockShape = RoundedCornerShape(12.dp)

/** Corner of a leading badge inside a [ContentBlockShape] row — concentric with the 12 dp outer. */
internal val ContentBadgeShape = RoundedCornerShape(10.dp)

/** Leading badge / play-disc slot width. */
internal val ContentLeadingSize = 40.dp

/** One play / pause disc size across voice, audio and video note — also the touch target. */
internal val PlayDiscSize = 48.dp

/** Fill of every in-body content container; same role as the code block's. */
internal val contentBlockColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * Bidi-isolate [s] as left-to-right (U+2066 … U+2069). Phone numbers and coordinates are
 * LTR data; inside an Arabic / Persian paragraph the bidi algorithm would otherwise move
 * the leading `+` or minus sign to the far end.
 */
internal fun ltrIsolate(s: String): String = "\u2066$s\u2069"

/**
 * Generic content row: leading slot, a one-line primary label and a muted secondary line.
 * The whole row is the action when [onClick] is non-null (ripple, `Role.Button`,
 * [onClickLabel] for TalkBack); there is deliberately no trailing "open" glyph — on the
 * device it read as the primary action and competed with the badge.
 */
@Composable
internal fun ContentRow(
    leading: @Composable () -> Unit,
    primary: String,
    secondary: String?,
    onClick: (() -> Unit)?,
    onClickLabel: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ContentBlockShape,
    primaryOverflow: TextOverflow = TextOverflow.Ellipsis,
    secondaryStyle: TextStyle = MaterialTheme.typography.bodySmall,
    top: (@Composable () -> Unit)? = null,
    tertiary: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(contentBlockColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        top?.invoke()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = primaryOverflow,
                )
                if (!secondary.isNullOrBlank()) {
                    Text(
                        text = secondary,
                        style = secondaryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!tertiary.isNullOrBlank()) {
                    Text(
                        text = tertiary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** Tonal leading badge with a glyph — location, contact, invoice and the no-extension file case. */
@Composable
internal fun GlyphBadge(
    symbol: String,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = Modifier
            .size(ContentLeadingSize)
            .clip(ContentBadgeShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(name = symbol, tint = content, size = 22.dp)
    }
}

/**
 * File-type badge: the short uppercase extension ("PDF", "XLSX") in a tonal square tinted by
 * the file's [FileFamily]; falls back to the family glyph when there is no usable extension.
 * The label auto-sizes down so a four-letter extension fits at font scale 2.0 without
 * growing the badge.
 */
@Composable
internal fun FileTypeBadge(fileName: String, mimeType: String) {
    val label = fileExtensionLabel(fileName)
    val family = fileFamilyOf(fileName, mimeType)
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (family) {
        FileFamily.Document -> cs.primaryContainer to cs.onPrimaryContainer
        FileFamily.Sheet, FileFamily.Audio, FileFamily.Image -> cs.tertiaryContainer to cs.onTertiaryContainer
        FileFamily.Archive, FileFamily.Code -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    if (label == null) {
        GlyphBadge(symbol = family.symbol, container = container, content = content)
        return
    }
    Box(
        modifier = Modifier
            .size(ContentLeadingSize)
            .clip(ContentBadgeShape)
            .background(container)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = content,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
        )
    }
}

/** Coarse file family driving the badge tint and the no-extension fallback glyph. */
internal enum class FileFamily(val symbol: String) {
    Document("description"),
    Sheet("description"),
    Archive("description"),
    Audio("audio_file"),
    Image("image"),
    Code("code"),
}

private val SHEET_EXT = setOf("xls", "xlsx", "xlsm", "csv", "tsv", "ods", "numbers")
private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "apk", "iso", "dmg")
private val AUDIO_EXT = setOf("mp3", "m4a", "flac", "wav", "ogg", "oga", "opus", "aac", "wma", "aiff")
private val IMAGE_EXT = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "svg", "bmp", "tif", "tiff",
    "mp4", "mov", "mkv", "webm", "avi",
)
private val CODE_EXT = setOf(
    "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "htm", "css", "c", "h",
    "cpp", "hpp", "cs", "sh", "yml", "yaml", "toml", "sql", "go", "rs", "swift", "rb", "php",
)

/** Longest extension shown as a badge label; longer ones fall back to the family glyph. */
private const val MAX_BADGE_EXT_LENGTH = 4

/**
 * Lower-case extension of [fileName], or null when there is none: no dot, a leading-dot
 * name (".gitignore"), a trailing dot, or a non-alphanumeric tail ("report.final draft").
 */
internal fun fileExtension(fileName: String): String? {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.lastIndex) return null
    val ext = fileName.substring(dot + 1)
    if (!ext.all { it.isLetterOrDigit() && it.code < 128 }) return null
    return ext.lowercase(Locale.ROOT)
}

/** Uppercase badge label ("PDF", "XLSX"), or null when there is no extension or it is > 4 chars. */
internal fun fileExtensionLabel(fileName: String): String? =
    fileExtension(fileName)
        ?.takeIf { it.length <= MAX_BADGE_EXT_LENGTH }
        ?.uppercase(Locale.ROOT)

/** Family by extension first (authoritative for the badge text), then by MIME type. */
internal fun fileFamilyOf(fileName: String, mimeType: String): FileFamily {
    when (fileExtension(fileName)) {
        in SHEET_EXT -> return FileFamily.Sheet
        in ARCHIVE_EXT -> return FileFamily.Archive
        in AUDIO_EXT -> return FileFamily.Audio
        in IMAGE_EXT -> return FileFamily.Image
        in CODE_EXT -> return FileFamily.Code
    }
    val mime = mimeType.lowercase(Locale.ROOT)
    return when {
        mime.startsWith("audio/") -> FileFamily.Audio
        mime.startsWith("image/") || mime.startsWith("video/") -> FileFamily.Image
        "spreadsheet" in mime || mime == "text/csv" -> FileFamily.Sheet
        "zip" in mime || "compressed" in mime || "x-tar" in mime || "x-7z" in mime || "x-rar" in mime -> FileFamily.Archive
        else -> FileFamily.Document
    }
}

/**
 * Coordinates as signed decimal degrees with a dot separator in every locale:
 * "49.23311°, 28.46819°". A locale decimal comma ("49,23311, 28,46819") reads as four
 * numbers, so the format is pinned to [Locale.ROOT]; the degree sign marks them as
 * coordinates without needing a localised N/S/E/W suffix.
 */
internal fun formatCoordinates(latitude: Double, longitude: Double): String =
    String.format(Locale.ROOT, "%.5f°, %.5f°", latitude, longitude)

/**
 * Open a coordinate in the system maps app through a generic `geo:` URI and a chooser —
 * no Google-specific host, nothing fetched by the app. Shared by the feed's location card
 * and the rich-message map block.
 */
internal fun openCoordinatesInMaps(context: Context, latitude: Double, longitude: Double, label: String?) {
    val lat = latitude.toString()
    val lon = longitude.toString()
    val query = if (label.isNullOrBlank()) "$lat,$lon" else "$lat,$lon(${android.net.Uri.encode(label)})"
    val intent = Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?q=$query".toUri())
    runCatching {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Currency code Telegram uses for Telegram Stars. */
private const val STARS_CURRENCY = "XTR"

/**
 * Display price of an invoice. Telegram Stars (`XTR`) read "250 ⭐", the way paid content and
 * paid reactions show stars elsewhere. Fiat goes through the [locale]'s currency format with
 * the currency's own fraction digits — [InvoicePrice.totalAmount] is in the smallest units,
 * so 1999 USD is $19.99 and 1999 JPY is ¥1,999. An unknown code falls back to the amount
 * with two decimals and the raw code.
 */
internal fun formatInvoicePrice(price: InvoicePrice, locale: Locale): String {
    val code = price.currency.uppercase(Locale.ROOT)
    if (code == STARS_CURRENCY) {
        return "${NumberFormat.getIntegerInstance(locale).format(price.totalAmount)} ⭐"
    }
    val currency = runCatching { Currency.getInstance(code) }.getOrNull()
        ?: return "${BigDecimal.valueOf(price.totalAmount, 2).toPlainString()} $code"
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)
    val format = NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = currency
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
    return format.format(BigDecimal.valueOf(price.totalAmount, digits))
}

/** What a [PlayDisc] shows. */
internal enum class PlayGlyph { Play, Pause, Loading }

/**
 * The one play / pause disc shared by voice, audio and the video note: a 48 dp tonal
 * (primaryContainer) circle with a 24 dp glyph. Visual only — the caller owns the click so
 * the disc can be a button (audio) or a decoration on a tappable bubble (video note).
 *
 * [cover] (album art) paints under the glyph with a dark scrim when present. [overMedia]
 * keeps the tonal look but lifts it off a video frame with a little transparency.
 */
@Composable
internal fun PlayDisc(
    glyph: PlayGlyph,
    modifier: Modifier = Modifier,
    cover: TdMedia? = null,
    overMedia: Boolean = false,
    size: Dp = PlayDiscSize,
) {
    val cs = MaterialTheme.colorScheme
    val onCover = cover != null
    val container = if (overMedia) cs.primaryContainer.copy(alpha = 0.92f) else cs.primaryContainer
    val tint = if (onCover) Color.White else cs.onPrimaryContainer
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            TdMediaImage(media = cover, contentDescription = null, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        when (glyph) {
            PlayGlyph.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
            PlayGlyph.Pause -> Symbol(name = "pause", tint = tint, size = 24.dp, filled = true)
            // A play triangle is heavier on its left edge; a 1 dp nudge centres it optically.
            // The triangle never mirrors in RTL (media transport glyphs are direction-neutral).
            PlayGlyph.Play -> Symbol(
                name = "play_arrow",
                tint = tint,
                size = 24.dp,
                filled = true,
                modifier = Modifier.absoluteOffset(x = 1.dp),
            )
        }
    }
}
