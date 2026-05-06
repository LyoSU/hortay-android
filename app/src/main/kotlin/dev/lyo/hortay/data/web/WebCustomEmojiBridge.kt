package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.CustomEmojiSticker
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.StickerFormat
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.TimelinePost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridge from [WebCustomEmojiResolver] (which talks to t.me/i/emoji/<id>.json)
 * into [CustomEmojiRepository] (which the shared
 * [dev.lyo.hortay.ui.media.CustomEmojiInlineView] reads from). Lets guest mode
 * render TGS / WebM / WebP custom emoji through the same Composable that TDLib
 * mode uses — the resolver writes to the repo, the inline view reads from the
 * repo, neither knows or cares about the other side.
 *
 * Design:
 *   - Subscribes to [feed] (the merged post stream) and scans every emitted
 *     post for custom-emoji ids in two places: the formatted-text spans
 *     ([FormattedText.Style.CustomEmoji]) and reaction buckets
 *     ([ReactionKind.CustomEmoji]).
 *   - Deduplicates against an in-memory "already requested" set so a feed
 *     re-emit (interaction-info coalescing, post upsert) doesn't issue a
 *     second `t.me/i/emoji/<id>.json` for an id we already resolved.
 *   - Resolves through [WebCustomEmojiResolver] (which has its own 200ms-spaced
 *     rate gate + in-memory cache).
 *   - Builds a [CustomEmojiSticker] with `media = TdMedia(remoteUrl = …)` so
 *     [TdMediaImage]'s remote-URL fast path renders the asset without going
 *     through TDLib's MediaCache. The resolver's discovered type is published
 *     faithfully as [StickerFormat]:
 *       • [StickerFormat.Tgs] — animated. [LottieUrlStore] pulls the .tgs
 *         payload from the t.me CDN, sniffs the gzip header (handles both
 *         pre-decompressed JSON and raw .tgs blobs), parses through
 *         LottieCompositionFactory, and feeds the same Lottie-Compose
 *         pipeline TDLib mode uses. Real animation, not a static thumb.
 *       • [StickerFormat.Webm] — falls back to the static WEBP thumb at
 *         inline size (same as TDLib mode). The t.me/i/emoji endpoint
 *         serves WebMs pre-rendered as yuv420p baked against srgb(0,0,0);
 *         the original alpha lives in a Matroska BlockAdditional sidecar
 *         VP9 stream that standard Android MediaCodec doesn't decode. A
 *         luma-key shader can't distinguish "background black" from
 *         "intentionally black glyph parts" — the only artefact-free
 *         animated path is media3-decoder-vp9 (~10 MB libvpx × 2 archs)
 *         which is disproportionate for inline-emoji size. Full rationale
 *         in CHANGELOG.md under "Animated TGS custom emojis in guest mode".
 *       • [StickerFormat.Webp] — TdMediaImage's Coil remote-URL fast path.
 *
 * Lifecycle: bind() once at AppGraph startup; the Flow collector lives for
 * the entire process. Idempotent.
 */
class WebCustomEmojiBridge(
    private val resolver: WebCustomEmojiResolver,
    private val customEmojiRepo: CustomEmojiRepository,
    private val feed: StateFlow<*>, // covariant: actually StateFlow<List<TimelinePost>>
    private val scope: CoroutineScope,
) {

    private val requested = HashSet<Long>()
    private val mutex = Mutex()
    private var bound = false

    /**
     * Start listening to the feed. Idempotent — second call is a no-op.
     * The feed parameter is typed loosely (`StateFlow<*>`) because tying the
     * bridge to a specific generic shape (`StateFlow<PersistentList<TimelinePost>>`)
     * adds compile coupling without runtime benefit. We unsafe-cast on collect.
     */
    fun bind(): Job? {
        if (bound) return null
        bound = true
        @Suppress("UNCHECKED_CAST")
        val typed = feed as StateFlow<List<TimelinePost>>
        return typed
            .onEach { posts -> harvest(posts) }
            .launchIn(scope)
    }

    private suspend fun harvest(posts: List<TimelinePost>) {
        val ids = collectIds(posts)
        if (ids.isEmpty()) return

        val toResolve: List<Long> = mutex.withLock {
            ids.filter { requested.add(it) }
        }
        if (toResolve.isEmpty()) return

        scope.launch {
            val resolved = HashMap<Long, CustomEmojiSticker>(toResolve.size)
            for (id in toResolve) {
                val asset = runCatching { resolver.resolve(id.toString()) }
                    .getOrNull() ?: continue
                resolved[id] = asset.toCustomEmojiSticker(id)
            }
            if (resolved.isNotEmpty()) {
                customEmojiRepo.populate(resolved)
                Log.i(TAG, "populated ${resolved.size} custom emoji stickers from web resolver")
            }
        }
    }

    private fun collectIds(posts: List<TimelinePost>): Set<Long> {
        val ids = HashSet<Long>()
        for (post in posts) {
            // Reactions on the post.
            for (item in post.reactions.items) {
                val kind = item.kind
                if (kind is ReactionKind.CustomEmoji) ids += kind.customEmojiId
            }
            // Text spans (Style.CustomEmoji) inside any caption-bearing PostContent.
            val captionSpans = when (val c = post.content) {
                is PostContent.Text -> c.formatted.spans
                is PostContent.PhotoAlbum -> c.caption.spans
                is PostContent.Video -> c.caption.spans
                is PostContent.Animation -> c.caption.spans
                is PostContent.Document -> c.caption.spans
                else -> emptyList()
            }
            for (span in captionSpans) {
                val style = span.style
                if (style is FormattedText.Style.CustomEmoji) ids += style.emojiId
            }
        }
        return ids
    }

    private fun ResolvedEmoji.toCustomEmojiSticker(id: Long): CustomEmojiSticker {
        // Map the resolver's discovered asset type onto the same [StickerFormat]
        // surface that TDLib mode uses. CustomEmojiInlineView now branches on the
        // (fileId, remoteUrl) pair inside each format and does the right thing:
        //   • Tgs: LottieUrlStore fetches .tgs bytes, parses to LottieComposition
        //   • Webm: ExoPlayer streams the URL directly
        //   • Webp: TdMediaImage's Coil remote-URL fallback
        // i.e. animated guest-mode emojis are now real animations, not just thumbs.
        val format = when (type) {
            ResolvedEmoji.Type.Tgs -> StickerFormat.Tgs
            ResolvedEmoji.Type.Webm -> StickerFormat.Webm
            ResolvedEmoji.Type.Webp -> StickerFormat.Webp
        }
        // For Tgs the `url` field is the Lottie JSON / .tgs payload; the thumb is
        // a small WEBP/PNG. For Webm `url` is the .webm; thumb is the WEBP poster.
        // For Webp the same URL serves as both — we keep `thumb=null` so we don't
        // double-fetch the same image.
        val mainMedia = TdMedia(
            fileId = null,
            width = sizePx,
            height = sizePx,
            minithumbBytes = null,
            remoteUrl = url,
        )
        val thumbMedia = thumbUrl
            ?.takeIf { type != ResolvedEmoji.Type.Webp }
            ?.let {
                TdMedia(
                    fileId = null,
                    width = sizePx,
                    height = sizePx,
                    minithumbBytes = null,
                    remoteUrl = it,
                )
            }
        return CustomEmojiSticker(
            customEmojiId = id,
            media = mainMedia,
            thumb = thumbMedia,
            format = format,
            // The t.me JSON endpoint doesn't surface needsRepainting. Defaulting to
            // false matches the visible behaviour in the official web client — the
            // server-recoloured monochrome emoji are a Premium-only ship-on-demand
            // feature that the static page doesn't expose anyway.
            needsRepainting = false,
        )
    }

    private companion object {
        const val TAG = "WebCustomEmojiBridge"
    }
}
