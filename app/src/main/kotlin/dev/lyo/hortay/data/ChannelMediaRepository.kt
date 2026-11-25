package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.drinkless.tdlib.TdApi

/**
 * A channel's shared media — its photos and videos, newest first — for the channel media
 * grid. One `searchChatMessages(filter = searchMessagesFilterPhotoAndVideo)` per page,
 * issued only while the user has the grid open and scrolls towards its end: there is no
 * prefetch, no background work and no cache beyond TDLib's own message database, so the
 * repository is stateless and needs no session wipe.
 *
 * On-demand and user-initiated, so it sits outside the cold-start fan-out rule (see
 * ARCHITECTURE.md → TDLib usage); every request still goes through [TdSender] and with it
 * the global FLOOD_WAIT gate in [TdClient.send]. Failures come back as a [Result] — the
 * grid shows its own inline Retry rather than a snackbar.
 */
class ChannelMediaRepository(private val td: TdSender) {

    /**
     * One page older than [fromMessageId] (`0` = start from the newest message). TDLib
     * answers with `nextFromMessageId` for the following page, `0` once there is nothing
     * left. Messages the grid can't show (anything but a photo or a video) are dropped from
     * [ChannelMediaPage.items] but still counted in [ChannelMediaPage.rawCount], so an
     * all-unsupported page doesn't read as the end of the history.
     */
    suspend fun loadPage(
        chatId: Long,
        fromMessageId: Long,
        limit: Int = CHANNEL_MEDIA_PAGE_SIZE,
    ): Result<ChannelMediaPage> = suspendRunCatching {
        td.send(
            TdApi.SearchChatMessages(
                chatId,
                /* topicId */ null,
                /* query */ "",
                /* senderId */ null,
                fromMessageId,
                /* offset */ 0,
                limit,
                TdApi.SearchMessagesFilterPhotoAndVideo(),
            ),
        )
    }
        .warnUnlessCancelled(TAG, "loadPage")
        .map { found -> found.toChannelMediaPage() }

    private companion object {
        const val TAG = "ChannelMedia"
    }
}

/** Page size of [ChannelMediaRepository.loadPage]: five screens of a three-column phone grid. */
const val CHANNEL_MEDIA_PAGE_SIZE = 60

/**
 * One grid cell. [viewerItem] is what the fullscreen viewer pages through; its
 * [AlbumItem.media] is the small grid thumbnail itself (`m`, ~320 px, or the video's own
 * thumbnail), so the cell never downloads the 1280 px inline tier, and the viewer stacks
 * its full-size file over the thumbnail the cell already has on disk.
 */
@Immutable
data class ChannelMediaItem(
    val messageId: Long,
    val viewerItem: AlbumItem,
    /** `!Message.can_be_saved`: the viewer hides Save / Share / Copy (see [TimelinePost.protectedContent]). */
    val protectedContent: Boolean = false,
) {
    val thumb: TdMedia get() = viewerItem.media

    /** Seconds for a video, `null` for a photo. */
    val durationSec: Int? get() = (viewerItem as? AlbumItem.Video)?.durationSec

    /** Spoiler / self-destructing media: the cell shows only the blurred inline preview. */
    val hidden: Boolean get() = viewerItem.hasSpoiler || viewerItem.isSecret
}

@Immutable
data class ChannelMediaPage(
    val items: PersistentList<ChannelMediaItem>,
    /** Messages TDLib returned, shown or not. Zero means the history is exhausted. */
    val rawCount: Int,
    /** Cursor for the next page; `0` = no more results. */
    val nextFromMessageId: Long,
)

internal fun TdApi.FoundChatMessages.toChannelMediaPage(): ChannelMediaPage {
    val raw = messages.orEmpty()
    return ChannelMediaPage(
        items = raw.mapNotNull { it.toChannelMediaItem() }.toPersistentList(),
        rawCount = raw.size,
        nextFromMessageId = nextFromMessageId,
    )
}

/**
 * Photo → `m`-tier thumbnail plus the `w` tier for the viewer; video → its cover (when the
 * uploader picked one) or TDLib's own thumbnail, with the same playback-quality pick the
 * feed uses. Anything else returns null.
 */
internal fun TdApi.Message.toChannelMediaItem(): ChannelMediaItem? {
    val item: AlbumItem = when (val c = content) {
        is TdApi.MessagePhoto -> AlbumItem.Photo(
            media = c.photo.toMedia(PHOTO_TARGET_PREVIEW_PX),
            fullscreen = c.photo.toMedia(PHOTO_TARGET_FULLSCREEN_PX),
            hasSpoiler = c.hasSpoiler,
            isSecret = c.isSecret,
            isLive = c.video != null,
        )
        is TdApi.MessageVideo -> {
            val video = c.video ?: return null
            val qualities = videoQualities(video, c.alternativeVideos)
            AlbumItem.Video(
                media = c.cover?.toMedia(PHOTO_TARGET_PREVIEW_PX) ?: video.toThumbMedia(),
                durationSec = video.duration,
                playbackFileId = qualities.defaultPick.fileId,
                qualities = qualities,
                hasSpoiler = c.hasSpoiler,
                isSecret = c.isSecret,
            )
        }
        else -> return null
    }
    return ChannelMediaItem(messageId = id, viewerItem = item, protectedContent = !canBeSaved)
}

/**
 * Grid state: everything loaded so far plus where paging stands. Pure — the reducers
 * below are what [dev.lyo.hortay.ui.channels.ChannelMediaViewModel] applies, and what the
 * unit tests drive.
 */
@Immutable
data class ChannelMediaState(
    val items: PersistentList<ChannelMediaItem> = persistentListOf(),
    val nextFromMessageId: Long = 0L,
    val status: Status = Status.Loading,
) {
    enum class Status {
        /** A page request is in flight (the first one, or one appending at the end). */
        Loading,

        /** Idle with more history to fetch. */
        Idle,

        /** The last request failed; [items] are kept and Retry resumes from the same cursor. */
        Error,

        /** TDLib has no older photos or videos. */
        End,
    }

    val canLoadMore: Boolean get() = status == Status.Idle

    /**
     * What the grid screen shows. With no cells yet, only an exhausted history ([Status.End])
     * reads as "no photos or videos": an [Status.Idle] state with no cells means the bounded
     * walk of one request found nothing showable but TDLib has more, and the grid (whose
     * near-end trigger would resume paging) isn't mounted — so the screen offers to keep
     * looking instead of claiming the channel is empty.
     */
    val view: View
        get() = when {
            items.isNotEmpty() -> View.Grid
            status == Status.Loading -> View.Loading
            status == Status.Error -> View.Error
            status == Status.Idle -> View.KeepLooking
            else -> View.Empty
        }

    enum class View { Grid, Loading, Error, KeepLooking, Empty }
}

internal fun ChannelMediaState.loading(): ChannelMediaState = copy(status = ChannelMediaState.Status.Loading)

internal fun ChannelMediaState.failed(): ChannelMediaState = copy(status = ChannelMediaState.Status.Error)

/**
 * Appends [page] (requested from [requestedFrom]), skipping ids already shown — TDLib may
 * repeat the boundary message across pages. The history is over when the page is empty,
 * when TDLib returns no next cursor, or when the cursor didn't move (a guard against a
 * request loop on a misbehaving answer).
 */
internal fun ChannelMediaState.appended(page: ChannelMediaPage, requestedFrom: Long): ChannelMediaState {
    val seen = HashSet<Long>(items.size * 2)
    items.forEach { seen += it.messageId }
    val fresh = page.items.filter { seen.add(it.messageId) }
    val end = page.rawCount == 0 ||
        page.nextFromMessageId == 0L ||
        page.nextFromMessageId == requestedFrom
    return copy(
        items = if (fresh.isEmpty()) items else items.addAll(fresh),
        nextFromMessageId = page.nextFromMessageId,
        status = if (end) ChannelMediaState.Status.End else ChannelMediaState.Status.Idle,
    )
}
