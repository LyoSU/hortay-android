package dev.lyo.hortay.ui.archive

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.archive.ArchivedContent
import dev.lyo.hortay.data.archive.ArchivedEntityKeys
import dev.lyo.hortay.data.archive.ArchivedMediaRef
import dev.lyo.hortay.data.archive.ContentNormalizer
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import dev.lyo.hortay.data.archive.diff.TextDiff
import dev.lyo.hortay.data.web.WebPostAdapter
import dev.lyo.hortay.ui.timeline.withEmojiPresentation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.TimeZone

/** What a version changed relative to the version before it in the picker. */
enum class RevisionChange { Published, FirstSeen, Text, Formatting, Media, TextAndMedia, NoVisibleChange, Deleted, Unreadable }

/**
 * One entry of the edit-history picker: a run of consecutive snapshots that look the same
 * to the reader, collapsed into one version.
 *
 * @property snapshot the run's first snapshot — the moment this version appeared.
 * @property media the run's best media reference: the first one carrying an archive copy,
 *   else the first one at all. Identical versions often differ only in whether the file
 *   was on disk when they were captured.
 * @property previous the readable version this one is compared against (null for the first).
 */
@Immutable
data class RevisionEntry(
    val snapshot: PostSnapshot,
    val text: FormattedText,
    val media: ArchivedMediaRef?,
    val change: RevisionChange,
    val previous: RevisionEntry?,
)

/**
 * Collapses [snapshots] (ascending by time) into picker entries.
 *
 * Rule: a VERSION snapshot whose user-visible content — text, entities (offset / length /
 * type, in [ContentNormalizer.canonicalEntities] form) and media identity — equals the previous kept VERSION is folded into it; so is a
 * DELETED marker that follows another DELETED marker. Unreadable rows are always kept (the
 * reader is told the version can't be shown) and are never used as a diff base. Rows are
 * not touched — this is display only.
 */
fun buildRevisionEntries(snapshots: List<PostSnapshot>): ImmutableList<RevisionEntry> {
    val out = mutableListOf<RevisionEntry>()
    var lastReadable: RevisionEntry? = null
    var lastKey: VisibleKey? = null
    for (snap in snapshots) {
        val last = out.lastOrNull()
        when {
            snap.kind == SnapshotKind.DELETED -> {
                if (last?.snapshot?.kind != SnapshotKind.DELETED) {
                    out += RevisionEntry(snap, FormattedText.Empty, null, RevisionChange.Deleted, lastReadable)
                }
                lastKey = null
            }
            snap.isUnreadable() -> {
                out += RevisionEntry(snap, FormattedText.Empty, null, RevisionChange.Unreadable, lastReadable)
                lastKey = null
            }
            last != null && last.snapshot.kind == SnapshotKind.VERSION && visibleKey(snap) == lastKey -> {
                // Same visible content — keep the earlier entry, upgrade its media if this
                // capture got the archive copy the earlier one missed.
                val media = mediaRefOf(snap)
                if (last.media?.localArchiveSha == null && media?.localArchiveSha != null) {
                    val upgraded = last.copy(media = media)
                    out[out.lastIndex] = upgraded
                    if (lastReadable === last) lastReadable = upgraded
                }
            }
            else -> {
                val key = visibleKey(snap)
                val prev = lastReadable
                val change = when {
                    prev != null -> classify(visibleKey(prev.snapshot), key)
                    out.isEmpty() && snap.editedAtMs == null -> RevisionChange.Published
                    else -> RevisionChange.FirstSeen
                }
                val entry = RevisionEntry(snap, formattedOf(snap), mediaRefOf(snap), change, prev)
                out += entry
                lastReadable = entry
                lastKey = key
            }
        }
    }
    return out.toImmutableList()
}

private fun classify(prev: VisibleKey, cur: VisibleKey): RevisionChange {
    val textChanged = prev.text != cur.text
    val formattingChanged = !textChanged && prev.entities != cur.entities
    val mediaChanged = prev.media != cur.media
    return when {
        textChanged && mediaChanged -> RevisionChange.TextAndMedia
        textChanged -> RevisionChange.Text
        mediaChanged -> RevisionChange.Media
        formattingChanged -> RevisionChange.Formatting
        else -> RevisionChange.NoVisibleChange
    }
}

private data class VisibleKey(val text: String, val entities: List<ContentNormalizer.CanonicalEntity>, val media: String?)

private fun PostSnapshot.isUnreadable(): Boolean = (content as? ArchivedContent.Tdlib)?.isUnreadable == true

private fun visibleKey(snap: PostSnapshot): VisibleKey = when (val c = snap.content) {
    is ArchivedContent.Tdlib -> VisibleKey(
        text = c.meta.text,
        // The content hash's canonical form: per-read TDLib entities (media timestamps, bot
        // commands), re-split style runs and list order never make two versions here.
        entities = ContentNormalizer.canonicalEntities(c.meta.entitiesJson),
        media = c.meta.mediaRef?.let { ref ->
            if (ref.uniqueId.isNotEmpty()) "${ref.type}:${ref.uniqueId}" else "${ref.type}:${ref.width}x${ref.height}"
        } ?: c.meta.mediaSummaryJson,
    )
    is ArchivedContent.Web -> VisibleKey(
        text = c.post.textHtml,
        entities = emptyList(),
        media = c.post.media.joinToString("|") { it.toString() },
    )
}

internal fun mediaRefOf(snap: PostSnapshot): ArchivedMediaRef? = (snap.content as? ArchivedContent.Tdlib)?.meta?.mediaRef

private fun formattedOf(snap: PostSnapshot): FormattedText = when (val c = snap.content) {
    is ArchivedContent.Tdlib -> archivedFormattedText(c.meta.text, c.meta.entitiesJson)
    is ArchivedContent.Web -> WebPostAdapter.formattedFromHtml(c.post.textHtml)
}

// ---- Stored entities → FormattedText ----------------------------------------------------

private class StoredEntity(
    val offset: Int,
    val length: Int,
    val type: String,
    val url: String?,
    val customEmojiId: Long?,
    val userId: Long?,
    val language: String?,
)

private fun parseEntities(json: String): List<StoredEntity> {
    if (json.isBlank()) return emptyList()
    val arr = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun prim(key: String) = o[key] as? JsonPrimitive
        StoredEntity(
            offset = prim(ArchivedEntityKeys.OFFSET)?.intOrNull ?: return@mapNotNull null,
            length = prim(ArchivedEntityKeys.LENGTH)?.intOrNull ?: return@mapNotNull null,
            type = prim(ArchivedEntityKeys.TYPE)?.content ?: return@mapNotNull null,
            url = prim(ArchivedEntityKeys.URL)?.content,
            customEmojiId = prim(ArchivedEntityKeys.CUSTOM_EMOJI_ID)?.longOrNull,
            userId = prim(ArchivedEntityKeys.USER_ID)?.longOrNull,
            language = prim(ArchivedEntityKeys.LANGUAGE)?.content,
        )
    }
}

/**
 * Rebuilds a stored version's formatted text from `TdlibContentMeta.entitiesJson`, mapping
 * entity types the same way the feed's mapper does.
 *
 * Rows captured before the entity payload was stored know an entity's type but not its
 * payload. Those degrade without losing the text:
 *  - a masked link without its url renders as plain text;
 *  - an inline mention without its user id renders as plain text;
 *  - a custom emoji without its id renders its fallback emoji — with the emoji
 *    presentation selector added, because Telegram stores that fallback as the bare code
 *    point (e.g. U+203C "‼"), which text fonts draw as a monochrome "!!" glyph. That was
 *    the "‼️ became !!" in the old sheet, which drew the text with no entities at all.
 */
fun archivedFormattedText(text: String, entitiesJson: String): FormattedText {
    if (text.isEmpty()) return FormattedText.Empty
    val entities = parseEntities(entitiesJson)
        .filter { it.offset >= 0 && it.length > 0 && it.offset + it.length <= text.length }
    // Fallback-emoji expansions shift every later offset; apply them first, in order.
    val expansions = entities
        .filter { it.type == "TextEntityTypeCustomEmoji" && (it.customEmojiId == null || it.customEmojiId == 0L) }
        .sortedBy { it.offset }
    val sb = StringBuilder(text.length + expansions.size)
    // shift[i] = how many chars were inserted before source position i.
    val shift = IntArray(text.length + 1)
    var cursor = 0
    var added = 0
    for (e in expansions) {
        if (e.offset < cursor) continue
        for (i in cursor..e.offset) shift[i] = added
        sb.append(text, cursor, e.offset)
        val alt = text.substring(e.offset, e.offset + e.length)
        val presented = alt.withEmojiPresentation()
        for (i in e.offset..e.offset + e.length) shift[i] = added
        sb.append(presented)
        added += presented.length - alt.length
        cursor = e.offset + e.length
    }
    for (i in cursor..text.length) shift[i] = added
    sb.append(text, cursor, text.length)

    val spans = entities.mapNotNull { e ->
        val style = styleOf(e) ?: return@mapNotNull null
        val start = e.offset + shift[e.offset]
        // The end of an expanded emoji includes the inserted selector.
        val end = e.offset + e.length + shift[e.offset + e.length]
        FormattedText.Span(start, end, style)
    }
    return FormattedText(sb.toString(), spans)
}

private fun styleOf(e: StoredEntity): FormattedText.Style? = when (e.type) {
    "TextEntityTypeBold" -> FormattedText.Style.Bold
    "TextEntityTypeItalic" -> FormattedText.Style.Italic
    "TextEntityTypeUnderline" -> FormattedText.Style.Underline
    "TextEntityTypeStrikethrough" -> FormattedText.Style.Strikethrough
    "TextEntityTypeCode" -> FormattedText.Style.Code
    "TextEntityTypePre" -> FormattedText.Style.Pre(language = null)
    "TextEntityTypePreCode" -> FormattedText.Style.Pre(language = e.language?.takeIf { it.isNotEmpty() })
    "TextEntityTypeTextUrl" -> e.url?.takeIf { it.isNotEmpty() }?.let { FormattedText.Style.TextUrl(it) }
    "TextEntityTypeUrl" -> FormattedText.Style.Url
    "TextEntityTypeMention" -> FormattedText.Style.Mention
    "TextEntityTypeMentionName" -> e.userId?.takeIf { it != 0L }?.let { FormattedText.Style.MentionName(it) }
    "TextEntityTypeHashtag" -> FormattedText.Style.Hashtag
    "TextEntityTypeBotCommand" -> FormattedText.Style.BotCommand
    "TextEntityTypeSpoiler" -> FormattedText.Style.Spoiler
    "TextEntityTypeCustomEmoji" -> e.customEmojiId?.takeIf { it != 0L }?.let { FormattedText.Style.CustomEmoji(it) }
    "TextEntityTypeBlockQuote" -> FormattedText.Style.BlockQuote(expandable = false)
    "TextEntityTypeExpandableBlockQuote" -> FormattedText.Style.BlockQuote(expandable = true)
    else -> null
}

// ---- Diff over formatted text -----------------------------------------------------------

/**
 * One formatted text showing how [old] became [new]: the new version's text and entities,
 * with removed fragments spliced back in (keeping the old version's inline entities, so a
 * removed custom emoji or bold word still looks like itself) and every change marked with
 * [FormattedText.Style.DiffInserted] / [FormattedText.Style.DiffRemoved].
 *
 * Block entities (quote / code) of removed fragments are not carried over: two block
 * ranges may not overlap, and the new version's block already spans the spot.
 */
fun composeDiff(old: FormattedText, new: FormattedText): FormattedText {
    val runs = TextDiff.compute(old.text, new.text)
    val sb = StringBuilder(new.text.length + old.text.length / 4)
    val newMap = IntArray(new.text.length)
    val oldMap = IntArray(old.text.length) { -1 }
    val marks = mutableListOf<FormattedText.Span>()
    val removedRuns = mutableListOf<TextDiff.Run>()
    for (r in runs) {
        when (r.op) {
            TextDiff.Op.Equal, TextDiff.Op.Insert -> {
                val at = sb.length
                for (k in r.newStart until r.newEnd) newMap[k] = at + (k - r.newStart)
                sb.append(new.text, r.newStart, r.newEnd)
                if (r.op == TextDiff.Op.Insert) {
                    marks += FormattedText.Span(at, sb.length, FormattedText.Style.DiffInserted)
                }
            }
            TextDiff.Op.Delete -> {
                val at = sb.length
                for (k in r.oldStart until r.oldEnd) oldMap[k] = at + (k - r.oldStart)
                sb.append(old.text, r.oldStart, r.oldEnd)
                marks += FormattedText.Span(at, sb.length, FormattedText.Style.DiffRemoved)
                removedRuns += r
            }
        }
    }
    val spans = ArrayList<FormattedText.Span>(new.spans.size + marks.size)
    for (s in new.spans) {
        if (s.start < 0 || s.end > new.text.length || s.start >= s.end) continue
        spans += FormattedText.Span(newMap[s.start], newMap[s.end - 1] + 1, s.style)
    }
    val inlineOldSpans = old.spans.filterNot {
        it.style is FormattedText.Style.BlockQuote || it.style is FormattedText.Style.Pre
    }
    for (s in inlineOldSpans) {
        for (r in removedRuns) {
            val from = maxOf(s.start, r.oldStart)
            val to = minOf(s.end, r.oldEnd)
            // A custom emoji is one glyph: carry it only when it was removed whole.
            val whole = s.style !is FormattedText.Style.CustomEmoji || (from == s.start && to == s.end)
            if (from < to && whole) spans += FormattedText.Span(oldMap[from], oldMap[to - 1] + 1, s.style)
        }
    }
    spans += marks
    return FormattedText(sb.toString(), spans)
}

// ---- Picker labels ----------------------------------------------------------------------

/** How much of a timestamp a picker label shows. */
enum class RevisionLabelPrecision { Time, TimeSeconds, DateTime, DateTimeSeconds, YearDateTime, YearDateTimeSeconds }

/**
 * Picks a label precision per timestamp so no two picker labels print the same text:
 * time only when every entry falls on one local day, the date as well when they span
 * several days (and the year when they span several years), and seconds for the entries
 * whose minute collides with another entry's.
 */
fun revisionLabelPrecisions(timestamps: List<Long>, zone: TimeZone): ImmutableList<RevisionLabelPrecision> {
    val cal = Calendar.getInstance(zone)
    fun dayOf(ts: Long): Long {
        cal.timeInMillis = ts
        return cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR)
    }
    fun yearOf(ts: Long): Int {
        cal.timeInMillis = ts
        return cal.get(Calendar.YEAR)
    }
    val multiYear = timestamps.map(::yearOf).distinct().size > 1
    val multiDay = timestamps.map(::dayOf).distinct().size > 1
    val minuteCounts = timestamps.groupingBy { Math.floorDiv(it + zone.getOffset(it), MINUTE_MS) }.eachCount()
    return timestamps.map { ts ->
        val seconds = (minuteCounts[Math.floorDiv(ts + zone.getOffset(ts), MINUTE_MS)] ?: 0) > 1
        when {
            multiYear -> if (seconds) RevisionLabelPrecision.YearDateTimeSeconds else RevisionLabelPrecision.YearDateTime
            multiDay -> if (seconds) RevisionLabelPrecision.DateTimeSeconds else RevisionLabelPrecision.DateTime
            else -> if (seconds) RevisionLabelPrecision.TimeSeconds else RevisionLabelPrecision.Time
        }
    }.toImmutableList()
}

private const val MINUTE_MS = 60_000L
