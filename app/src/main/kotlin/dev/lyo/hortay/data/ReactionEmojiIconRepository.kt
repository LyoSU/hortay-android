package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves a standard (unicode) reaction emoji — `ReactionKind.Emoji.text` — into
 * Telegram's own static reaction artwork, so a ❤ chip draws Telegram's heart instead of
 * the device vendor's emoji font.
 *
 * TDLib path: [TdApi.GetEmojiReaction] → [TdApi.EmojiReaction.staticIcon], a WEBP
 * [TdApi.Sticker]. Neither [TdApi.AvailableReaction] (just a `ReactionType`) nor
 * [TdApi.UpdateActiveEmojiReactions] (just emoji strings) carries the artwork, so the
 * per-emoji call is the only documented source. The same response also carries
 * Telegram's one-shot reaction animations; two of them are kept from it (no extra call):
 * `centerAnimation` and `aroundAnimation`, played once when the user picks the reaction. Only TGS stickers are
 * kept — they go through the app's existing Lottie pipeline; any other format counts as
 * "no animation". The animation FILES are not downloaded here: [ReactionEmojiIcon] only
 * holds their TDLib file ids, and the glyph fetches one through `MediaCache` at
 * [DownloadPriority.Avatar] once the reaction is on screen, ahead of the user's tap.
 *
 * Request budget:
 *   - **Lazy.** [request] is called from the reaction glyph composable only, so only
 *     reactions that are actually composed on screen are resolved — nothing on cold
 *     start, no harvest over the feed.
 *   - **At most once per emoji per session.** [requested] dedupes concurrent and
 *     repeated lookups. A definite "no artwork" — TDLib's 404 (its reaction list has no
 *     such emoji) or a response without a usable static raster — is remembered in
 *     [missing] and never retried until the next session. Any OTHER failure (send
 *     timeout, FLOOD_WAIT, a network or server error) is transient: the emoji is
 *     forgotten so a glyph composed after [TRANSIENT_RETRY_MS] asks again. Memoising
 *     those too left a standard reaction (seen with 🤓) on the device-font fallback for
 *     the rest of the session after one unlucky call. The number of distinct standard
 *     reaction emoji Telegram ships is small (tens), which bounds the total.
 *   - **Serial.** One [sendLock] keeps at most one call in flight, so a screen-full of
 *     fresh emoji resolves as a short queue rather than a parallel burst. Every call goes
 *     through [TdSender.send], i.e. through `TdClient`'s central FLOOD_WAIT gate.
 *   - **Off under [EmojiStyle.System].** [request] is a no-op then, so a user who picked
 *     the device emoji font never triggers a single call.
 *
 * Session scope: the resolved icons hold TDLib file ids, which belong to the signed-in
 * account's TDLib database. [clear] (registered on `AccountSession.onEnd` in `AppGraph`,
 * i.e. on `TdClient.loggedOut`) wipes the cache, and an [epoch] bump makes any call that
 * was in flight across the logout drop its result instead of re-inserting it.
 */
class ReactionEmojiIconRepository(
    private val td: TdSender,
    private val scope: CoroutineScope,
    private val style: StateFlow<EmojiStyle>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val _icons = MutableStateFlow<PersistentMap<String, ReactionEmojiIcon>>(persistentMapOf())

    /** Resolved icons keyed by the raw reaction emoji string, exactly as TDLib sent it. */
    val icons: StateFlow<PersistentMap<String, ReactionEmojiIcon>> = _icons.asStateFlow()

    /** Every emoji ever handed to TDLib this session — resolved, failed or in flight. */
    private val requested = ConcurrentHashMap.newKeySet<String>()

    /** Emoji TDLib had no usable artwork for (or failed on) — never re-requested in-session. */
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Emoji whose last lookup failed transiently → earliest [nowMs] for the next try. */
    private val retryAfter = ConcurrentHashMap<String, Long>()

    private val sendLock = Mutex()
    private val epoch = AtomicInteger(0)

    /** Synchronous accessor for an already-resolved icon. */
    fun get(emoji: String): ReactionEmojiIcon? = _icons.value[emoji]

    /** Whether resolution for [emoji] already failed this session. */
    fun isMissing(emoji: String): Boolean = emoji in missing

    /**
     * Hint: resolve [emoji]'s Telegram artwork. Idempotent and cheap on repeat — an
     * emoji already resolved, failed or in flight is skipped without a TDLib call.
     * Safe to call from a Composable's `LaunchedEffect`.
     */
    fun request(emoji: String) {
        if (emoji.isEmpty()) return
        if (style.value != EmojiStyle.Telegram) return
        val retryAt = retryAfter[emoji]
        if (retryAt != null && nowMs() < retryAt) return
        if (!requested.add(emoji)) return
        val startEpoch = epoch.get()
        scope.launch(ioDispatcher) {
            val result = sendLock.withLock {
                if (epoch.get() != startEpoch) return@launch
                suspendRunCatching { td.send(TdApi.GetEmojiReaction(emoji)) }
                    .warnUnlessCancelled(TAG, "GetEmojiReaction($emoji)")
            }
            if (epoch.get() != startEpoch) return@launch
            val failure = result.exceptionOrNull()
            if (failure != null && !(failure is TdClient.TdException && failure.code == NOT_FOUND)) {
                // Transient: forget it, so a glyph composed after the cooldown asks again.
                retryAfter[emoji] = nowMs() + TRANSIENT_RETRY_MS
                requested.remove(emoji)
                return@launch
            }
            retryAfter.remove(emoji)
            val icon = result.getOrNull()?.toReactionEmojiIcon(emoji)
            if (icon == null) {
                missing.add(emoji)
            } else {
                _icons.update { it.put(emoji, icon) }
            }
        }
    }

    /**
     * Wipe the resolved icons, the failure memo and the dedupe set. Called on logout:
     * the cached [TdMedia.fileId]s are scoped to the previous account's TDLib database.
     * The epoch bump goes first so an in-flight call can't land after the wipe.
     */
    fun clear() {
        epoch.incrementAndGet()
        requested.clear()
        missing.clear()
        retryAfter.clear()
        _icons.value = persistentMapOf()
    }

    private companion object {
        const val TAG = "ReactionEmojiIcons"

        /** TDLib's answer to `getEmojiReaction` for an emoji its reaction list doesn't have. */
        const val NOT_FOUND = 404

        /** Cooldown before an emoji whose lookup failed transiently is asked for again. */
        const val TRANSIENT_RETRY_MS = 30_000L
    }
}

/**
 * Telegram's artwork for one standard reaction emoji: the static icon, plus the one-shot
 * animations the reaction glyph plays (each null when TDLib sent none, or not as TGS).
 * The animation entries are TDLib file ids only — nothing is downloaded until the glyph
 * asks for one.
 */
@Immutable
data class ReactionEmojiIcon(
    val emoji: String,
    val media: TdMedia,
    /** Played once inside the chip's glyph box when the user picks the reaction. */
    val centerAnimation: TdMedia? = null,
    /** The burst played once around the glyph, together with [centerAnimation]. */
    val aroundAnimation: TdMedia? = null,
)

/** Null when the static icon has no usable raster — the emoji then counts as missing. */
internal fun TdApi.EmojiReaction.toReactionEmojiIcon(emoji: String): ReactionEmojiIcon? {
    val media = staticIcon?.toReactionIconMedia() ?: return null
    return ReactionEmojiIcon(
        emoji = emoji,
        media = media,
        centerAnimation = centerAnimation.toReactionAnimationMedia(),
        aroundAnimation = aroundAnimation.toReactionAnimationMedia(),
    )
}

/**
 * A reaction animation sticker as a playable TGS file, or null. Telegram ships these as
 * TGS (gzipped Lottie), which the app's Lottie pipeline plays; a WEBM or WEBP variant, or
 * a sticker without a file, is treated as "no animation" and the glyph stays still.
 */
internal fun TdApi.Sticker?.toReactionAnimationMedia(): TdMedia? {
    val sticker = this ?: return null
    if (sticker.format !is TdApi.StickerFormatTgs) return null
    val file = sticker.sticker ?: return null
    return TdMedia(fileId = file.id, width = sticker.width, height = sticker.height)
}

/**
 * The static image to draw for a reaction's `staticIcon` sticker. Telegram ships it as a
 * WEBP sticker; should it ever arrive as an animated format, its still raster thumbnail
 * is used instead, and with neither the emoji counts as having no artwork (text
 * fallback). The animations live in separate fields of [ReactionEmojiIcon].
 */
internal fun TdApi.Sticker.toReactionIconMedia(): TdMedia? {
    val file = sticker
    if (format is TdApi.StickerFormatWebp && file != null) {
        return TdMedia(fileId = file.id, width = width, height = height)
    }
    val thumb = thumbnail ?: return null
    return when (thumb.format) {
        is TdApi.ThumbnailFormatWebp,
        is TdApi.ThumbnailFormatPng,
        is TdApi.ThumbnailFormatJpeg,
        -> thumb.toMedia()
        else -> null
    }
}
