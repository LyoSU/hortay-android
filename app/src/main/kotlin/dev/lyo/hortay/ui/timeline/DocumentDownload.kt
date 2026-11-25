package dev.lyo.hortay.ui.timeline

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.components.formatByteSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.LocalUserMessageBus
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaPassive
import dev.lyo.hortay.ui.media.LocalScrollGate
import dev.lyo.hortay.ui.media.MediaShareActions
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * In-app document download + open for signed-in (TDLib) posts.
 *
 * The file is fetched through [MediaCache] like every other TDLib file — one reducer, one
 * stall watchdog, no second download path — but ONLY on an explicit tap: the card never
 * calls `ensure` on mount (documents stay outside the auto-download policy), it only
 * [MediaCache.probe]s TDLib's local copy so an already-downloaded file shows as openable.
 * A started download is not tied to the card's lifetime (Telegram's behaviour: scrolling
 * away keeps it going); the ring's tap cancels it.
 *
 * Opening hands the TDLib file to the system through the app's FileProvider with the
 * document's real name and MIME type ([MediaShareActions.openFile]).
 */

/** What a document card shows, derived from its [MediaState] by [documentFileState]. */
@Immutable
internal sealed interface DocumentFileState {
    data object Idle : DocumentFileState

    /** [progress] null = indeterminate (no byte has landed yet, or the size is unknown). */
    data class Downloading(val progress: Float?, val downloadedBytes: Long, val totalBytes: Long) : DocumentFileState

    data class Ready(val path: String) : DocumentFileState

    data object Failed : DocumentFileState
}

/** The card's single action for a [DocumentFileState]. */
internal enum class DocumentTapAction { Download, Cancel, Open }

/**
 * Card state for the file's [media] slot. [requested] is "the user tapped download on this
 * card and the slot has not yet answered": it shows the ring at once, before TDLib's first
 * update, and covers a queued download TDLib reports as not yet active.
 *
 * A [MediaState.Downloading] nothing is fetching (`active == false` — a partial file left
 * by an earlier session, or a stray update for a file nobody asked for) reads as [Idle]:
 * the card must never show a ring that isn't moving.
 */
internal fun documentFileState(media: MediaState, requested: Boolean): DocumentFileState = when (media) {
    is MediaState.Ready -> DocumentFileState.Ready(media.path)
    is MediaState.Downloading -> if (media.active || requested) {
        val known = media.downloadedBytes > 0L && media.totalBytes > 0L
        DocumentFileState.Downloading(
            progress = if (known) media.progress.coerceIn(0f, 1f) else null,
            downloadedBytes = media.downloadedBytes,
            totalBytes = media.totalBytes,
        )
    } else {
        DocumentFileState.Idle
    }
    is MediaState.Failed -> if (requested) PENDING else DocumentFileState.Failed
    MediaState.Idle, MediaState.Paused -> if (requested) PENDING else DocumentFileState.Idle
}

private val PENDING = DocumentFileState.Downloading(progress = null, downloadedBytes = 0L, totalBytes = 0L)

internal fun DocumentFileState.tapAction(): DocumentTapAction = when (this) {
    DocumentFileState.Idle, DocumentFileState.Failed -> DocumentTapAction.Download
    is DocumentFileState.Downloading -> DocumentTapAction.Cancel
    is DocumentFileState.Ready -> DocumentTapAction.Open
}

/** The card's secondary line, as data; [documentMetaText] renders it. */
@Immutable
internal sealed interface DocumentMeta {
    data object None : DocumentMeta
    data class Size(val bytes: Long) : DocumentMeta
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DocumentMeta
    /** [bytes] 0 = size unknown, show the failure alone. */
    data class Failed(val bytes: Long) : DocumentMeta
}

/**
 * Secondary line for [state]. A download shows "x of y" against TDLib's total, falling
 * back to the message's [sizeBytes] while TDLib has not resolved one yet.
 */
internal fun documentMeta(state: DocumentFileState, sizeBytes: Long): DocumentMeta {
    val size = sizeBytes.coerceAtLeast(0L)
    return when (state) {
        is DocumentFileState.Downloading -> {
            val total = state.totalBytes.takeIf { it > 0L } ?: size
            if (total > 0L) DocumentMeta.Progress(state.downloadedBytes.coerceIn(0L, total), total) else DocumentMeta.None
        }
        DocumentFileState.Failed -> DocumentMeta.Failed(size)
        DocumentFileState.Idle, is DocumentFileState.Ready -> if (size > 0L) DocumentMeta.Size(size) else DocumentMeta.None
    }
}

private const val OCTET_STREAM = "application/octet-stream"

/**
 * MIME type to open a document with. The sender's declared type wins (parameters such as
 * `; charset=utf-8` stripped) unless it is missing, malformed or the generic octet-stream,
 * in which case the extension decides through [byExtension] (the platform's
 * `MimeTypeMap` in the app; injectable for tests). Nothing known → octet-stream.
 */
internal fun documentMimeType(fileName: String, mimeType: String, byExtension: (String) -> String?): String {
    val declared = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    if (declared.isMimeType() && declared != OCTET_STREAM) return declared
    val guessed = fileExtension(fileName)?.let(byExtension)?.trim()?.lowercase(Locale.ROOT)
    return guessed?.takeIf { it.isMimeType() } ?: OCTET_STREAM
}

private fun String.isMimeType(): Boolean {
    val slash = indexOf('/')
    return slash > 0 && slash < lastIndex && indexOf('/', slash + 1) < 0 && none { it.isWhitespace() }
}

/**
 * The downloadable document row. [onOpenInSource] stays the fallback for a deleted post
 * whose file is not on disk ([LocalMediaPassive]: the message is gone server-side, so a
 * download could never finish).
 */
@Composable
internal fun DownloadableDocumentRow(
    content: PostContent.Document,
    fileId: Int,
    onOpenInSource: () -> Unit,
) {
    val cache = LocalMediaCache.current
    val passive = LocalMediaPassive.current
    val media by remember(fileId) { cache.observe(fileId) }.collectAsStateWithLifecycle()
    val gateOpen = LocalScrollGate.current.value
    LaunchedEffect(fileId, gateOpen, passive) {
        if (gateOpen && !passive) cache.probe(fileId)
    }
    if (passive && media !is MediaState.Ready) {
        DocumentInfoRow(content, onOpenInSource)
        return
    }

    var requested by remember(fileId) { mutableStateOf(false) }
    // The slot answered the tap (or the user cancelled): from here the slot alone decides.
    LaunchedEffect(media) {
        if (media is MediaState.Ready || media is MediaState.Failed || media is MediaState.Paused) requested = false
    }
    val state = remember(media, requested) { documentFileState(media, requested) }
    val action = state.tapAction()

    val context = LocalContext.current
    val bus = LocalUserMessageBus.current
    val scope = rememberCoroutineScope()
    val onClick: () -> Unit = remember(action, state, fileId, content, cache, bus, context) {
        {
            when (action) {
                DocumentTapAction.Download -> {
                    requested = true
                    cache.requestDownload(fileId)
                }
                DocumentTapAction.Cancel -> {
                    requested = false
                    cache.cancelExplicit(fileId)
                }
                DocumentTapAction.Open -> {
                    val path = (state as DocumentFileState.Ready).path
                    scope.launch {
                        val missing = openDocument(context, bus, content, path)
                        if (missing) {
                            // TDLib's storage optimiser deleted the file without an UpdateFile
                            // (tdlib/td#3178): fetch it again; the next tap opens it.
                            requested = true
                            cache.invalidate(fileId, DownloadPriority.Foreground)
                        }
                    }
                    Unit
                }
            }
        }
    }

    val units = stringArrayResource(R.array.size_units)
    ContentRow(
        leading = { DocumentBadge(content, state) },
        primary = content.fileName.ifBlank { stringResource(R.string.document_unnamed) },
        secondary = documentMetaText(documentMeta(state, content.sizeBytes), units),
        onClick = onClick,
        onClickLabel = stringResource(
            when (action) {
                DocumentTapAction.Download -> R.string.document_download
                DocumentTapAction.Cancel -> R.string.media_cancel_download
                DocumentTapAction.Open -> R.string.document_open
            },
        ),
        primaryOverflow = TextOverflow.MiddleEllipsis,
        secondaryStyle = MaterialTheme.typography.bodySmall.tabularFigures(),
    )
}

/** Today's informational row: the whole card opens the post in Telegram. */
@Composable
internal fun DocumentInfoRow(content: PostContent.Document, onOpenInSource: () -> Unit) {
    val units = stringArrayResource(R.array.size_units)
    ContentRow(
        leading = { FileTypeBadge(content.fileName, content.mimeType) },
        primary = content.fileName.ifBlank { stringResource(R.string.document_unnamed) },
        secondary = content.sizeBytes.takeIf { it > 0L }?.let { formatByteSize(it, units) },
        onClick = onOpenInSource,
        onClickLabel = stringResource(R.string.content_open_in_telegram),
        primaryOverflow = TextOverflow.MiddleEllipsis,
        secondaryStyle = MaterialTheme.typography.bodySmall.tabularFigures(),
    )
}

@Composable
private fun documentMetaText(meta: DocumentMeta, units: Array<String>): String? = when (meta) {
    DocumentMeta.None -> null
    is DocumentMeta.Size -> formatByteSize(meta.bytes, units)
    is DocumentMeta.Progress -> stringResource(
        R.string.document_download_progress,
        formatByteSize(meta.downloadedBytes, units),
        formatByteSize(meta.totalBytes, units),
    )
    is DocumentMeta.Failed -> {
        val failed = stringResource(R.string.document_download_failed)
        if (meta.bytes > 0L) "${formatByteSize(meta.bytes, units)} · $failed" else failed
    }
}

/** Which badge the leading slot draws; the crossfade key, so progress ticks never re-fade. */
private enum class BadgeLook { Download, Progress, Plain }

/**
 * Leading slot: the file-type badge with a small download mark while the file isn't on
 * the device, a determinate ring with a cancel glyph in the same tonal square while it
 * downloads, and the plain badge once it is on disk — the absence of the mark is the
 * "downloaded, tap opens" state, as in Telegram. Looks swap with a short effects-spec
 * crossfade (quiet content: no spatial motion), snapped under reduced motion.
 */
@Composable
private fun DocumentBadge(content: PostContent.Document, state: DocumentFileState) {
    val reducedMotion = rememberReducedMotion()
    val look = when (state) {
        DocumentFileState.Idle, DocumentFileState.Failed -> BadgeLook.Download
        is DocumentFileState.Downloading -> BadgeLook.Progress
        is DocumentFileState.Ready -> BadgeLook.Plain
    }
    Crossfade(
        targetState = look,
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "documentBadge",
    ) { target ->
        when (target) {
            BadgeLook.Plain -> FileTypeBadge(content.fileName, content.mimeType)
            BadgeLook.Download -> Box {
                FileTypeBadge(content.fileName, content.mimeType)
                DownloadMark(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
            }
            BadgeLook.Progress -> ProgressBadge(
                content = content,
                progress = (state as? DocumentFileState.Downloading)?.progress,
                reducedMotion = reducedMotion,
            )
        }
    }
}

/** 18 dp primary disc with a download arrow, ringed in the card colour so it reads as a cut-out. */
@Composable
private fun DownloadMark(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(contentBlockColor)
            .padding(2.dp)
            .clip(CircleShape)
            .background(cs.primary),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(name = "download", tint = cs.onPrimary, size = 11.dp)
    }
}

@Composable
private fun ProgressBadge(content: PostContent.Document, progress: Float?, reducedMotion: Boolean) {
    val (container, onContainer) = fileBadgeColors(fileFamilyOf(content.fileName, content.mimeType))
    Box(
        modifier = Modifier
            .size(ContentLeadingSize)
            .clip(ContentBadgeShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        val ringModifier = Modifier.size(32.dp)
        val track = onContainer.copy(alpha = 0.2f)
        if (progress == null) {
            CircularProgressIndicator(
                modifier = ringModifier,
                color = onContainer,
                trackColor = track,
                strokeWidth = 2.5.dp,
            )
        } else {
            // MediaCache throttles progress to ~10 Hz; easing between samples keeps the arc
            // gliding instead of stepping. Read inside the lambda → draw phase only.
            val animated by animateFloatAsState(
                targetValue = progress,
                animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "documentProgress",
            )
            CircularProgressIndicator(
                progress = { animated },
                modifier = ringModifier,
                color = onContainer,
                trackColor = track,
                strokeWidth = 2.5.dp,
                gapSize = 0.dp,
            )
        }
        Symbol(name = "close", tint = onContainer, size = 16.dp)
    }
}

/**
 * Opens [path] in the system handler for the document's type. Returns true when the file
 * turned out to be missing on disk (the caller re-downloads); a missing handler posts a
 * message offering to share the file instead, any other failure a plain error.
 */
private suspend fun openDocument(
    context: Context,
    bus: UserMessageBus?,
    content: PostContent.Document,
    path: String,
): Boolean {
    val mime = documentMimeType(content.fileName, content.mimeType) { ext ->
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
    val name = content.fileName.takeIf { it.isNotBlank() }
    val outcome = withContext(Dispatchers.IO) { MediaShareActions.openFile(context, path, mime, name) }
    when (outcome) {
        MediaShareActions.OpenOutcome.Opened -> Unit
        MediaShareActions.OpenOutcome.Missing -> return true
        MediaShareActions.OpenOutcome.NoApp -> bus?.post(
            text = context.getString(R.string.document_no_app),
            severity = UserMessageBus.Severity.Info,
            action = UserMessageBus.Action.Run(context.getString(R.string.post_share)) {
                MediaShareActions.shareFile(context, path, mime, name)
            },
        )
        MediaShareActions.OpenOutcome.Failed -> bus?.post(context.getString(R.string.document_open_failed))
    }
    return false
}
