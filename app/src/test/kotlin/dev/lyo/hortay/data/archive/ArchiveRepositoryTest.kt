package dev.lyo.hortay.data.archive

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArchiveRepositoryTest {

    private class Fixture(
        settings: ArchiveSettings = ArchiveSettings(enabled = true),
        available: Boolean = true,
    ) {
        var now = 1_000L
        val archive = TestArchive()
        val db = archive.db
        val repo = ArchiveRepository(db, MutableStateFlow(settings), clock = { now }, available = { available })
    }

    private val chat = ChatRef.tdlib(42)

    @Test
    fun captureEdit_dedupesIdenticalContentHash() = runTest {
        val f = Fixture()
        f.repo.captureTdlibEdit(chat, "100", null, 2_000L, testMeta("hello"))
        f.repo.captureTdlibEdit(chat, "100", null, 2_000L, testMeta("hello"))

        assertEquals(1, f.db.versionsOf(chat, "100").size)
    }

    @Test
    fun captureEdit_writesWhenContentDiffers() = runTest {
        val f = Fixture()
        f.repo.captureTdlibEdit(chat, "100", null, 1_500L, testMeta("hello"))
        f.now = 5_000L
        f.repo.captureTdlibEdit(chat, "100", null, 2_000L, testMeta("hello there"))

        assertEquals(listOf("hello", "hello there"), f.db.textsOf(chat, "100"))
        assertEquals(1L, f.db.countMessages())
    }

    /**
     * Two edit captures reaching the repository in the wrong order: the stale one (older
     * edit date) must not land on top of the newer version — otherwise the next
     * content-less re-emit writes the newer text a second time and the history alternates.
     */
    @Test
    fun captureEdit_dropsCaptureOlderThanLatestVersion() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 500L)
        f.now = 5_000L
        f.repo.captureTdlibEdit(chat, "100", null, editedAtMs = 3_000L, meta = testMeta("v3"))
        f.now = 5_100L
        f.repo.captureTdlibEdit(chat, "100", null, editedAtMs = 2_000L, meta = testMeta("v2"))
        f.now = 6_000L
        f.repo.captureTdlibEdit(chat, "100", null, editedAtMs = 3_000L, meta = testMeta("v3"))

        assertEquals(listOf("v1", "v3"), f.db.textsOf(chat, "100"))
    }

    @Test
    fun captureEdit_newerEditStillLandsAfterOlderOne() = runTest {
        val f = Fixture()
        f.repo.captureTdlibEdit(chat, "100", null, editedAtMs = 2_000L, meta = testMeta("v2"))
        f.now = 2_000L
        f.repo.captureTdlibEdit(chat, "100", null, editedAtMs = 3_000L, meta = testMeta("v3"))

        assertEquals(listOf("v2", "v3"), f.db.textsOf(chat, "100"))
    }

    @Test
    fun masterToggleOff_skipsCapture() = runTest {
        val f = Fixture(ArchiveSettings(enabled = false))
        f.repo.captureTdlibEdit(chat, "100", null, 1_500L, testMeta("hello"))
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("hello"), originalDateMs = 500L)

        assertEquals(0L, f.db.countMessages())
    }

    @Test
    fun excludedChat_skipsCapture() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, excludedChats = persistentSetOf(chat)))
        f.repo.captureTdlibEdit(chat, "100", null, 1_500L, testMeta("hello"))
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("hello"), originalDateMs = 500L)

        assertEquals(0L, f.db.countMessages())
    }

    /** archive.db failed to open: captures report DISABLED instead of writing to the stand-in. */
    @Test
    fun unavailableArchive_skipsEveryCapture() = runTest {
        val f = Fixture(available = false)
        val edit = f.repo.captureTdlibEdit(chat, "100", null, 1_500L, testMeta("hello"))
        val baseline = f.repo.captureTdlibBaseline(chat, "101", null, testMeta("hello"), originalDateMs = 500L)
        val delete = f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)

        assertEquals(ArchiveDecision.DISABLED, edit.decision)
        assertEquals(ArchiveDecision.DISABLED, baseline.decision)
        assertEquals(ArchiveDecision.DISABLED, delete.decision)
        assertEquals(0L, f.db.countMessages())
    }

    @Test
    fun captureDelete_marksTheMessageDeleted() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("hello"), originalDateMs = 500L)
        f.now = 7_000L
        f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)

        // A state on the message, not a marker version.
        assertEquals(1, f.db.versionsOf(chat, "100").size)
        val message = f.db.messageOf(chat, "100")!!
        assertEquals(7_000L, message.deleted_at_ms)
        assertEquals(7_000L, message.last_event_at_ms)
    }

    @Test
    fun captureDelete_twiceKeepsTheFirstDeletionTime() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("hello"), originalDateMs = 500L)
        f.now = 7_000L
        f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)
        f.now = 8_000L
        val again = f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)

        assertEquals(ArchiveDecision.DUP_HASH, again.decision)
        assertEquals(7_000L, f.db.messageOf(chat, "100")!!.deleted_at_ms)
    }

    @Test
    fun captureDelete_marksEveryAlbumMember() = runTest {
        val f = Fixture()
        for (key in listOf("100", "101", "102")) {
            f.repo.captureTdlibBaseline(chat, key, "5", testMeta("photo $key"), originalDateMs = 500L)
        }
        f.repo.captureTdlibDelete(chat, listOf("100", "101", "102"), albumKey = "5", isComment = false)

        for (key in listOf("100", "101", "102")) {
            val m = f.db.messageOf(chat, key)!!
            assertNotNull(m.deleted_at_ms, key)
            assertEquals("5", m.album_key)
        }
    }

    @Test
    fun captureDelete_ofANeverArchivedMessageWritesNothing() = runTest {
        val f = Fixture()

        val outcome = f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)
        val comment = f.repo.captureTdlibDelete(chat, listOf("200", "201"), albumKey = null, isComment = true)

        assertEquals(ArchiveDecision.NO_CONTENT, outcome.decision)
        assertEquals(DETAIL_NEVER_ARCHIVED, outcome.detail)
        assertEquals(DETAIL_NEVER_ARCHIVED, comment.detail)
        assertEquals(0L, f.db.countMessages())
    }

    @Test
    fun captureDeleteSmart_marksOnlyTheArchivedMembersOfAnAlbum() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "101", "5", testMeta("caption"), originalDateMs = 500L)

        val outcomes = f.repo.captureTdlibDeleteSmart(chat, listOf("100", "101", "102"), isComment = false)

        assertEquals(1L, f.db.countMessages())
        assertNotNull(f.db.messageOf(chat, "101")!!.deleted_at_ms)
        assertNull(f.db.messageOf(chat, "100"))
        assertEquals(1, outcomes.count { it.outcome.decision == ArchiveDecision.WRITTEN })
        assertEquals(2, outcomes.count { it.outcome.detail == DETAIL_NEVER_ARCHIVED })
    }

    @Test
    fun upsertChannel_updatesTitleAndHandle() = runTest {
        val f = Fixture()
        f.repo.upsertChannel(chat, "Old Title", "old_handle", null, false)
        f.repo.upsertChannel(chat, "New Title", "new_handle", null, true)

        val row = f.db.archivedChannelQueries.selectOne("TDLIB", "42").executeAsOne()
        assertEquals("New Title", row.title)
        assertEquals("new_handle", row.handle)
        assertEquals(1L, row.is_verified)
    }

    @Test
    fun baseline_sortedAtPublicationTime_observedAtClock() = runTest {
        val f = Fixture()
        f.now = 9_999L
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 500L)

        val v = f.db.versionsOf(chat, "100").single()
        assertEquals(500L, v.sort_at_ms)
        assertNull(v.edit_date_ms)
        assertEquals(9_999L, v.observed_at_ms)
        assertEquals(VersionOrigin.PUBLISHED.name, v.origin)
        assertEquals(ContentNormalizer.HASH_VERSION, v.hash_version)
        val m = f.db.messageOf(chat, "100")!!
        assertEquals(500L, m.published_at_ms)
        assertEquals(9_999L, m.first_captured_at_ms)
    }

    @Test
    fun baseline_idempotentEvenWhenContentDiffers() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 500L)
        // A baseline is a fixed point on the timeline, not a hash-keyed dedup.
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1-different"), originalDateMs = 500L)

        assertEquals(listOf("v1"), f.db.textsOf(chat, "100"))
    }

    /**
     * Posts first seen already edited record their first-observed state sorted at that
     * edit date, origin FIRST_SEEN_EDITED, so a later deletion has a version to show.
     * Re-ingest stays idempotent.
     */
    @Test
    fun baseline_firstSeenEdited_isIdempotentOnReingest() = runTest {
        val f = Fixture()
        repeat(2) {
            f.repo.captureTdlibBaseline(
                chat, "100", null, testMeta("v1-edited"),
                originalDateMs = 500L, priorEditedAtMs = 700L,
            )
        }

        val v = f.db.versionsOf(chat, "100").single()
        assertEquals(700L, v.sort_at_ms)
        assertEquals(700L, v.edit_date_ms)
        assertEquals(VersionOrigin.FIRST_SEEN_EDITED.name, v.origin)
        assertEquals(500L, f.db.messageOf(chat, "100")!!.published_at_ms)
    }

    /**
     * Race: the edit lands FIRST (the edit update beats the ingest baseline through the
     * recorder), then the baseline arrives. The baseline sorts at the publication time, so
     * the timeline is still [baseline, edit].
     */
    @Test
    fun race_editFirstThenBaseline_timelineOrderedCorrectly() = runTest {
        val f = Fixture()
        f.repo.captureTdlibEdit(chat, "100", null, 800L, testMeta("v2-edited"))
        f.now = 1_200L
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1-original"), originalDateMs = 500L)

        val rows = f.db.versionsOf(chat, "100")
        assertEquals(listOf("v1-original", "v2-edited"), rows.map { it.text() })
        assertEquals(listOf(500L, 800L), rows.map { it.sort_at_ms })
        assertEquals(listOf(1_200L, 1_000L), rows.map { it.observed_at_ms })
        assertEquals(listOf(VersionOrigin.PUBLISHED.name, VersionOrigin.LIVE_EDIT.name), rows.map { it.origin })
    }

    /** A comment is captured only on edit: its first version is a LIVE_EDIT at its edit date. */
    @Test
    fun edit_withoutBaseline_isLiveEditAtEditDate() = runTest {
        val f = Fixture()
        f.repo.captureTdlibEdit(chat, "100", null, 800L, testMeta("edited"), isComment = true)

        val v = f.db.versionsOf(chat, "100").single()
        assertEquals(800L, v.sort_at_ms)
        assertEquals(800L, v.edit_date_ms)
        assertEquals(1_000L, v.observed_at_ms)
        assertEquals(VersionOrigin.LIVE_EDIT.name, v.origin)
        assertEquals(1L, v.is_comment)
        assertNull(f.db.messageOf(chat, "100")!!.published_at_ms)
    }

    /** An edit date behind the latest version's position never sorts it before that version. */
    @Test
    fun edit_sortNeverGoesBackwards() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 5_000L)
        f.repo.captureTdlibEdit(chat, "100", null, editedAtMs = 4_000L, meta = testMeta("v2"))

        val rows = f.db.versionsOf(chat, "100")
        assertEquals(listOf("v1", "v2"), rows.map { it.text() })
        assertEquals(listOf(5_000L, 5_000L), rows.map { it.sort_at_ms })
    }

    @Test
    fun reconcile_appendsReconciledVersion_andSkipsDeletedMessages() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 500L)
        val written = f.repo.captureTdlibReconcile(chat, "100", null, editedAtMs = 2_000L, meta = testMeta("v3"))
        assertEquals(ArchiveDecision.WRITTEN, written.decision)
        assertEquals(VersionOrigin.RECONCILED.name, f.db.versionsOf(chat, "100").last().origin)

        f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)
        val afterDelete = f.repo.captureTdlibReconcile(chat, "100", null, editedAtMs = 3_000L, meta = testMeta("v4"))
        assertEquals(ArchiveDecision.STALE, afterDelete.decision)
        assertEquals(DETAIL_DELETED, afterDelete.detail)

        val unknown = f.repo.captureTdlibReconcile(chat, "999", null, editedAtMs = 3_000L, meta = testMeta("x"))
        assertEquals(DETAIL_NOT_ARCHIVED, unknown.detail)
    }

    @Test
    fun observeRevisions_ascByTime_withDeletionLast() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 500L)
        f.now = 5_000L
        f.repo.captureTdlibEdit(chat, "100", null, 2_000L, testMeta("v2"))
        f.now = 6_000L
        f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)

        val revisions = f.repo.observeRevisions(chat, "100").first()
        assertEquals(
            listOf(SnapshotKind.VERSION, SnapshotKind.VERSION, SnapshotKind.DELETED),
            revisions.map { it.kind },
        )
        assertEquals("v1", (revisions[0].content as ArchivedContent.Tdlib).meta.text)
        assertEquals(VersionOrigin.PUBLISHED, revisions[0].origin)
        assertEquals(VersionOrigin.LIVE_EDIT, revisions[1].origin)
        assertEquals(6_000L, revisions[2].seenAtMs)
    }

    /**
     * Comment deletions must NOT leak into the channel-feed tombstone stream: a deleted
     * comment lives only inside its discussion thread.
     */
    @Test
    fun tombstones_excludeComments() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("post body"), originalDateMs = 500L)
        f.repo.upsertChannel(chat, "Test Channel", "test_handle", null, false)
        f.repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)
        val discussion = ChatRef.tdlib(99)
        f.repo.captureTdlibEdit(discussion, "200", null, 1_500L, testMeta("comment body"), isComment = true)
        f.repo.captureTdlibDelete(discussion, listOf("200"), albumKey = null, isComment = true)

        val tombstones = f.repo.observeTdlibTombstones().first()
        assertEquals(1, tombstones.size)
        assertEquals(42L, tombstones[0].chatId)
        assertEquals("post body", tombstones[0].text)
        assertEquals(500L, tombstones[0].originalSeenAtMs)
    }

    @Test
    fun tombstones_foldAnAlbumIntoOneRecord() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "102", "5", testMeta(""), originalDateMs = 500L)
        f.repo.captureTdlibBaseline(chat, "101", "5", testMeta("caption"), originalDateMs = 500L)
        f.repo.upsertChannel(chat, "Test Channel", null, null, false)
        f.repo.captureTdlibDeleteSmart(chat, listOf("101", "102"), isComment = false)

        val tombstone = f.repo.observeTdlibTombstones().first().single()
        assertEquals(101L, tombstone.primaryMessageId)
        assertEquals(listOf(101L, 102L), tombstone.allMessageIds.sorted())
        assertEquals("caption", tombstone.text)
    }

    @Test
    fun revisionCounts_countVersionsBeyondTheFirst() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 500L)
        f.repo.captureTdlibEdit(chat, "100", null, 2_000L, testMeta("v2"))
        f.repo.captureTdlibEdit(chat, "100", null, 3_000L, testMeta("v3"))
        f.repo.captureTdlibBaseline(chat, "101", null, testMeta("same"), originalDateMs = 500L)

        val counts = f.repo.observeTdlibRevisionCounts().first()
        assertEquals(2, counts[42L to 100L])
        assertNull(counts[42L to 101L])
    }

    @Test
    fun purge_removesWholeMessages() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("v1"), originalDateMs = 500L)
        f.repo.captureTdlibEdit(chat, "100", null, 2_000L, testMeta("v2"))
        f.repo.captureTdlibBaseline(chat, "101", null, testMeta("keep"), originalDateMs = 500L)

        f.repo.purge(listOf(f.db.messageOf(chat, "100")!!.id))

        assertNull(f.db.messageOf(chat, "100"))
        assertEquals(1L, f.archive.count("MessageVersion"))
        assertEquals(listOf("keep"), f.db.textsOf(chat, "101"))
    }

    @Test
    fun clear_wipesEverything() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("a"), originalDateMs = 100L)
        f.repo.captureTdlibBaseline(chat, "101", null, testMeta("b"), originalDateMs = 200L)
        f.repo.upsertChannel(chat, "T", null, null, false)

        f.repo.clear()

        assertEquals(0L, f.archive.count("ArchivedMessage"))
        assertEquals(0L, f.archive.count("MessageVersion"))
        assertEquals(0L, f.archive.count("ArchivedChannel"))
    }

    @Test
    fun concurrentCaptureFromTwoSources_writesBoth() = runTest {
        val f = Fixture()
        val webChat = ChatRef.web("demo")
        f.repo.captureTdlibBaseline(chat, "100", null, testMeta("tdlib version"), originalDateMs = 500L)
        val webPost = dev.lyo.hortay.data.web.WebPost(
            id = "demo/100", seq = 100L, publishedAt = "2026-05-23T10:00:00Z",
            textHtml = "web version",
            media = kotlinx.collections.immutable.persistentListOf(),
            webPreview = null, forwardedFrom = null, views = null,
            reactions = kotlinx.collections.immutable.persistentListOf(),
        )
        f.repo.captureWebVersion(webChat, "100", webPost)

        assertEquals(1, f.db.versionsOf(chat, "100").size)
        val web = f.db.versionsOf(webChat, "100").single()
        assertEquals(VersionOrigin.WEB.name, web.origin)
        assertEquals(CONTENT_KIND_WEB, web.content_kind)
    }

    /**
     * A corrupt or version-drifted blob (an older build over a newer archive.db) must NOT
     * crash the read flow: it renders as an unreadable placeholder.
     */
    @Test
    fun observeRevisions_corruptBlob_yieldsPlaceholderInsteadOfCrashing() = runTest {
        val f = Fixture()
        f.db.insertRawVersion(chat, "100", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "deadbeef", sortAtMs = 500L)
        val future = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).use { dos ->
                dos.writeInt(0x54444C42) // "TDLB" magic
                dos.writeInt(99) // unsupported version
                dos.write(byteArrayOf(0, 1, 2))
            }
        }.toByteArray()
        f.db.insertRawVersion(chat, "100", future, "cafe", sortAtMs = 600L)

        val revisions = f.repo.observeRevisions(chat, "100").first()
        assertEquals(2, revisions.size)
        revisions.forEach { r ->
            val c = r.content as ArchivedContent.Tdlib
            assertEquals("", c.meta.text)
            assertEquals(true, c.isUnreadable)
        }
    }

    @Test
    fun capturesEditsFor_honoursEnabledCaptureEditsAndExclusions() {
        assertEquals(false, Fixture(ArchiveSettings(enabled = false)).repo.capturesEditsFor(chat))
        assertEquals(false, Fixture(ArchiveSettings(enabled = true, captureEdits = false)).repo.capturesEditsFor(chat))
        assertEquals(
            false,
            Fixture(ArchiveSettings(enabled = true, excludedChats = persistentSetOf(chat))).repo.capturesEditsFor(chat),
        )
        assertEquals(true, Fixture(ArchiveSettings(enabled = true)).repo.capturesEditsFor(chat))
    }
}
