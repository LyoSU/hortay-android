package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Captures of one unchanged message must never become a new archive version: not when
 * TDLib hands out the same formatting in another shape, not when a row was hashed by an
 * older normalizer, not when the same edit date comes back with another presentation.
 * Real text / formatting / media edits still must.
 */
class PhantomVersionTest {

    // --- Canonical entity form ---

    @Test
    fun reorderedEntitiesHashTheSame() {
        val a = meta("Lead line and a link", ents(e(0, 4, BOLD), e(0, 2, EMOJI), e(14, 6, URL)))
        val b = meta("Lead line and a link", ents(e(14, 6, URL), e(0, 2, EMOJI), e(0, 4, BOLD)))
        assertEquals(ContentNormalizer.canonicalHash(a), ContentNormalizer.canonicalHash(b))
    }

    @Test
    fun perReadEntitiesDoNotChangeTheHash() {
        val without = meta("watch from 1:23 /start", ents(e(0, 5, BOLD)))
        val with = meta(
            "watch from 1:23 /start",
            ents(e(0, 5, BOLD), e(11, 4, "TextEntityTypeMediaTimestamp"), e(16, 6, "TextEntityTypeBotCommand")),
        )
        assertEquals(ContentNormalizer.canonicalHash(without), ContentNormalizer.canonicalHash(with))
    }

    @Test
    fun resplitStyleRunsHashTheSame() {
        val whole = meta("bold text with link", ents(e(0, 19, BOLD), e(15, 4, URL)))
        val split = meta("bold text with link", ents(e(0, 15, BOLD), e(15, 4, BOLD), e(15, 4, URL)))
        assertEquals(ContentNormalizer.canonicalHash(whole), ContentNormalizer.canonicalHash(split))
    }

    @Test
    fun adjacentLinksAreNotMergedAndRealFormattingChangesStillCount() {
        val twoLinks = meta("onetwo", ents(e(0, 3, URL), e(3, 3, URL)))
        val oneLink = meta("onetwo", ents(e(0, 6, URL)))
        assertNotEquals(ContentNormalizer.canonicalHash(twoLinks), ContentNormalizer.canonicalHash(oneLink))

        val boldLead = meta("Lead rest", ents(e(0, 4, BOLD)))
        val boldAll = meta("Lead rest", ents(e(0, 9, BOLD)))
        val italicLead = meta("Lead rest", ents(e(0, 4, "TextEntityTypeItalic")))
        assertNotEquals(ContentNormalizer.canonicalHash(boldLead), ContentNormalizer.canonicalHash(boldAll))
        assertNotEquals(ContentNormalizer.canonicalHash(boldLead), ContentNormalizer.canonicalHash(italicLead))
    }

    @Test
    fun animatedEmojiAndTextFormsOfOneEmojiHashTheSame() {
        val regularText = TdApi.MessageText(TdApi.FormattedText("👍", emptyArray()), null, null)
        val regularAnimated = TdApi.MessageAnimatedEmoji(TdApi.AnimatedEmoji(sticker(null), 512, 512, 0, null), "👍")
        assertEquals(hashOf(regularText), hashOf(regularAnimated))

        val customText = TdApi.MessageText(
            TdApi.FormattedText("🔥", arrayOf(TdApi.TextEntity(0, 2, TdApi.TextEntityTypeCustomEmoji(77L)))),
            null, null,
        )
        val customAnimated = TdApi.MessageAnimatedEmoji(
            TdApi.AnimatedEmoji(sticker(TdApi.StickerFullTypeCustomEmoji(77L, false)), 512, 512, 0, null), "🔥",
        )
        val customNotLoaded = TdApi.MessageAnimatedEmoji(TdApi.AnimatedEmoji(null, 512, 512, 0, null), "🔥")
        assertEquals(hashOf(customText), hashOf(customAnimated))
        assertEquals(hashOf(customText), hashOf(customNotLoaded))
        assertNotEquals(hashOf(regularText), hashOf(customText))
    }

    // --- Re-hash of rows stored under an older canonical form ---

    @Test
    fun oldFormatRowThenSameContentIsADupByRehash() = runTest {
        val h = Harness()
        // Stored by the previous normalizer: entities hashed in stored order, media
        // timestamp included — the hash a current capture of the same content can't match.
        val stored = meta("Lead line", ents(e(5, 4, URL), e(0, 4, BOLD), e(5, 4, "TextEntityTypeMediaTimestamp")))
        h.insertLegacy(stored, editedAtMs = 1_000L)
        val capture = meta("Lead line", ents(e(0, 4, BOLD), e(5, 4, URL)))
        assertNotEquals(legacyHash(stored), ContentNormalizer.canonicalHash(capture))

        val edit = h.repo.captureTdlibEdit(CHAT, KEY, null, editedAtMs = 2_000L, meta = capture)
        assertEquals(ArchiveDecision.DUP_HASH, edit.decision)
        assertEquals(DETAIL_DUP_REHASH, edit.detail)
        assertEquals(emptyList<String>(), edit.diff!!.components)
        assertEquals(ContentDiff.REASON_COUNT, edit.diff!!.entityReason)

        // The re-hash was written back in the current form: the next comparison is a plain
        // stored-hash match, with nothing decoded.
        val stored2 = h.db.versionsOf(CHAT, KEY).single()
        assertEquals(ContentNormalizer.canonicalHash(capture), stored2.content_hash)
        assertEquals(ContentNormalizer.HASH_VERSION, stored2.hash_version)
        val reconcile = h.repo.captureTdlibReconcile(CHAT, KEY, null, editedAtMs = 3_000L, meta = capture)
        assertEquals(ArchiveDecision.DUP_HASH, reconcile.decision)
        assertNull(reconcile.detail)
        assertNull(reconcile.diff)
        assertEquals(1, h.versions())
    }

    /** Old-format phantoms of one version are folded by the sweep's dedupe step, then not listed. */
    @Test
    fun oldFormatRowsOfOneVersionAreNotListedAsEdited() = runTest {
        val h = Harness()
        val a = meta("Same post", ents(e(0, 4, BOLD), e(5, 4, URL)))
        val b = meta("Same post", ents(e(5, 4, URL), e(0, 4, BOLD)))
        h.insertLegacy(a, editedAtMs = null, seenAtMs = 500L)
        h.insertLegacy(b, editedAtMs = 1_000L, seenAtMs = 900L)
        assertEquals(1, h.db.dedupeVersions())
        assertTrue(h.repo.observeEntries().first().isEmpty())

        h.repo.captureTdlibEdit(CHAT, KEY, null, editedAtMs = 2_000L, meta = meta("Edited post"))
        assertEquals(2, h.repo.observeEntries().first().single().versionCount)
    }

    @Test
    fun undecodableStoredRowFallsBackToTheStoredHash() = runTest {
        val h = Harness()
        h.db.insertRawVersion(
            CHAT, KEY, byteArrayOf(1, 2, 3), "0123456789abcdef", sortAtMs = 500L,
            hashVersion = ContentNormalizer.LEGACY_HASH_VERSION,
        )
        val out = h.repo.captureTdlibEdit(CHAT, KEY, null, editedAtMs = 2_000L, meta = meta("x"))
        assertEquals(ArchiveDecision.WRITTEN, out.decision)
        assertNull(out.diff)
    }

    // --- Real changes still make versions, with content-free diagnostics ---

    @Test
    fun realTextFormattingAndMediaChangesWriteVersions() = runTest {
        val h = Harness()
        h.repo.captureTdlibBaseline(CHAT, KEY, null, meta("Lead rest", ents(e(0, 4, BOLD))), originalDateMs = 500L)

        val text = h.repo.captureTdlibEdit(CHAT, KEY, null, 1_000L, meta("Lead rest!", ents(e(0, 4, BOLD))))
        assertEquals(ArchiveDecision.WRITTEN, text.decision)
        assertEquals(listOf("text"), text.diff!!.components)
        assertEquals(9 to 10, text.diff!!.textLenOld to text.diff!!.textLenNew)

        val formatting = h.repo.captureTdlibEdit(CHAT, KEY, null, 2_000L, meta("Lead rest!", ents(e(0, 10, BOLD))))
        assertEquals(ArchiveDecision.WRITTEN, formatting.decision)
        assertEquals(listOf("entities"), formatting.diff!!.components)
        assertEquals(ContentDiff.REASON_OFFSETS, formatting.diff!!.entityReason)

        val photo = """{"type":"photo","w":1280,"h":720}"""
        val media = h.repo.captureTdlibEdit(CHAT, KEY, null, 3_000L, meta("Lead rest!", ents(e(0, 10, BOLD)), photo))
        assertEquals(ArchiveDecision.WRITTEN, media.decision)
        assertEquals(listOf("media"), media.diff!!.components)
        assertEquals(4, h.versions())
    }

    @Test
    fun differentCaptureAtTheSameEditDateIsNotAVersion() = runTest {
        val h = Harness()
        h.repo.captureTdlibBaseline(
            CHAT, KEY, null, meta("Lead rest", ents(e(0, 4, BOLD))), originalDateMs = 500L, priorEditedAtMs = 1_000L,
        )
        val out = h.repo.captureTdlibEdit(
            CHAT, KEY, null, 1_000L, meta("Lead rest", ents(e(0, 4, BOLD), e(5, 4, "TextEntityTypeSpoiler"))),
        )
        assertEquals(ArchiveDecision.STALE, out.decision)
        assertEquals(DETAIL_SAME_EDIT_DATE, out.detail)
        assertEquals(ContentDiff.REASON_COUNT, out.diff!!.entityReason)
        assertEquals(listOf("Spoiler"), out.diff!!.typesAdded)
        assertEquals(1, h.versions())
    }

    @Test
    fun journalDiffFieldsCarryNoContent() = runTest {
        val h = Harness()
        val secret = "SECRET https://secret.example/path"
        h.repo.captureTdlibBaseline(CHAT, KEY, null, meta(secret, ents(e(0, 6, BOLD))), originalDateMs = 500L)
        val linked = TdlibContentMeta(
            text = "$secret more", mediaSummaryJson = null, pollJson = null, forwardJson = null, replyJson = null,
            entitiesJson = """[{"offset":7,"length":27,"type":"$URL","url":"https://secret.example/path"},""" +
                """{"offset":0,"length":6,"type":"$EMOJI","customEmojiId":5368324170671202286}]""",
        )
        val out = h.repo.captureTdlibEdit(CHAT, KEY, null, 1_000L, linked)
        val line = ArchiveJournalRecord(
            wallTimeMs = 0L, sourceKind = CHAT.kind, chatKey = CHAT.key, messageKey = KEY,
            event = ArchiveEventKind.EDIT, decision = out.decision, newHash = out.hash, latestHash = out.latestHash,
            contentSource = ArchiveContentSource.LOCAL_READ, detail = out.detail, diff = out.diff,
        ).toJsonLine()
        val json = Json.parseToJsonElement(line) as JsonObject
        assertEquals("text+entities", (json["diff"] as JsonPrimitive).content)
        assertEquals(secret.length.toString(), (json["textLenOld"] as JsonPrimitive).content)
        assertEquals("1", (json["entOld"] as JsonPrimitive).content)
        assertEquals("2", (json["entNew"] as JsonPrimitive).content)
        assertEquals(ContentDiff.REASON_COUNT, (json["entReason"] as JsonPrimitive).content)
        assertEquals("CustomEmoji+TextUrl", (json["entAdded"] as JsonPrimitive).content)
        assertEquals("Bold", (json["entRemoved"] as JsonPrimitive).content)
        for (leak in listOf("SECRET", "secret.example", "5368324170671202286", "more")) {
            assertFalse(line.contains(leak), line)
        }
    }

    // --- Helpers ---

    private class Harness {
        val db: ArchiveDatabase = newArchiveDb()
        private var t = 1_700_000_000_000L
        val repo = ArchiveRepository(db, MutableStateFlow(ArchiveSettings(enabled = true)), clock = { t++ })

        /** A version as the previous normalizer would have hashed it (`hash_version` 1). */
        fun insertLegacy(meta: TdlibContentMeta, editedAtMs: Long?, seenAtMs: Long = 500L) {
            db.insertRawVersion(
                CHAT, KEY, ContentBlobCodec.encode(meta), legacyHash(meta),
                sortAtMs = editedAtMs ?: seenAtMs, hashVersion = ContentNormalizer.LEGACY_HASH_VERSION,
                origin = if (editedAtMs == null) VersionOrigin.PUBLISHED else VersionOrigin.LIVE_EDIT,
                editDateMs = editedAtMs, observedAtMs = seenAtMs, textPreview = meta.textPreview,
            )
        }

        fun versions(): Int = db.versionsOf(CHAT, KEY).size
    }

    private fun hashOf(content: TdApi.MessageContent): String =
        ContentNormalizer.canonicalHash(TdlibContentMetaExtractor.extract(content))

    private fun sticker(fullType: TdApi.StickerFullType?): TdApi.Sticker = TdApi.Sticker().apply {
        this.fullType = fullType ?: TdApi.StickerFullTypeRegular()
    }

    private fun meta(text: String, entitiesJson: String = "[]", media: String? = null) = TdlibContentMeta(
        text = text, entitiesJson = entitiesJson,
        mediaSummaryJson = media, pollJson = null, forwardJson = null, replyJson = null,
    )

    private fun e(offset: Int, length: Int, type: String) = Triple(offset, length, type)

    private fun ents(vararg es: Triple<Int, Int, String>): String =
        es.joinToString(",", "[", "]") { (o, l, t) -> """{"offset":$o,"length":$l,"type":"$t"}""" }

    companion object {
        private val CHAT = ChatRef.tdlib(42)
        private const val KEY = "100"
        private const val BOLD = "TextEntityTypeBold"
        private const val URL = "TextEntityTypeTextUrl"
        private const val EMOJI = "TextEntityTypeCustomEmoji"

        /** The previous canonical form: entities projected but neither filtered, merged nor sorted. */
        fun legacyHash(meta: TdlibContentMeta): String {
            val entities = (Json.parseToJsonElement(meta.entitiesJson) as JsonArray).map { el ->
                JsonObject((el as JsonObject).filterKeys { it == "offset" || it == "length" || it == "type" })
            }
            val obj = buildJsonObject {
                put("text", JsonPrimitive(meta.text))
                put("entities", JsonArray(entities))
                put("media", JsonObject(emptyMap()))
                put("poll", JsonObject(emptyMap()))
                put("forward", JsonPrimitive(""))
                put("reply", JsonPrimitive(""))
            }
            return ContentBlobCodec.hash(Json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8))
        }
    }
}
