package dev.lyo.hortay.data.archive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArchiveSweepTest {

    private val day = 86_400_000L
    private val chat = ChatRef.tdlib(1)

    private class Fixture(settings: ArchiveSettings) {
        var now = 1_000L
        val archive = TestArchive()
        val db = archive.db
        val settings = MutableStateFlow(settings)
        val repo = ArchiveRepository(db, this.settings, clock = { now })
        fun sweep() = ArchiveSweep(db, settings, clock = { now })
    }

    private fun keys(f: Fixture): List<String> =
        f.db.archivedMessageQueries.selectPageForExport(0L, Long.MAX_VALUE).executeAsList().map { it.message_key }.sorted()

    /** Retention keys on the message's newest event, not on Telegram's dates. */
    @Test
    fun retention_dropsMessagesWhoseNewestEventIsOld() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, retentionDays = 30))
        f.now = 0L
        f.repo.captureTdlibBaseline(chat, "1", null, testMeta("ancient"), originalDateMs = 0L)
        f.now = 30L * day + 1_000_000L
        f.repo.captureTdlibBaseline(chat, "2", null, testMeta("fresh"), originalDateMs = f.now)

        f.sweep().run()

        assertNull(f.db.messageOf(chat, "1"))
        assertNotNull(f.db.messageOf(chat, "2"))
        assertEquals(1L, f.archive.count("MessageVersion"))
    }

    /**
     * A baseline is sorted at the publication time; retention must key on capture time,
     * or an old post's baseline is swept the night it is captured, losing the original of
     * a later edit and the body of a later tombstone.
     */
    @Test
    fun retention_keepsFreshlyCapturedBaselineOfOldPost() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, retentionDays = 30))
        f.now = 100L * day
        f.repo.captureTdlibBaseline(chat, "1", null, testMeta("original"), originalDateMs = f.now - 90L * day)
        f.repo.captureTdlibEdit(chat, "1", null, editedAtMs = f.now, meta = testMeta("edited"))

        f.sweep().run()

        val rows = f.db.versionsOf(chat, "1")
        assertEquals(listOf("original", "edited"), rows.map { it.text() })
        assertEquals(f.now - 90L * day, rows[0].sort_at_ms)
    }

    /** An old message keeps its whole history while a recent event (an edit, the deletion) is in range. */
    @Test
    fun retention_takesWholeMessages_andADeletionRenewsIt() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, retentionDays = 30))
        f.now = 0L
        f.repo.captureTdlibBaseline(chat, "1", null, testMeta("v1"), originalDateMs = 0L)
        f.repo.captureTdlibBaseline(chat, "2", null, testMeta("v1"), originalDateMs = 0L)
        f.now = 40L * day
        f.repo.captureTdlibEdit(chat, "1", null, editedAtMs = f.now, meta = testMeta("v2"))
        f.repo.captureTdlibDelete(chat, listOf("2"), albumKey = null, isComment = false)

        f.sweep().run()

        assertEquals(listOf("v1", "v2"), f.db.textsOf(chat, "1"))
        assertEquals(listOf("v1"), f.db.textsOf(chat, "2"))
    }

    @Test
    fun unlimitedRetention_keepsEverything() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, retentionDays = Int.MAX_VALUE, maxRecords = Int.MAX_VALUE))
        f.now = 0L
        f.repo.captureTdlibBaseline(chat, "1", null, testMeta("ancient"), originalDateMs = 0L)
        f.now = Long.MAX_VALUE / 2

        f.sweep().run()

        assertEquals(1L, f.db.countMessages())
    }

    @Test
    fun retentionChange_appliesOnTheNextRun() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, retentionDays = 90))
        f.now = 0L
        f.repo.captureTdlibBaseline(chat, "1", null, testMeta("ancient"), originalDateMs = 0L)
        f.now = 30L * day + 1_000_000L

        f.sweep().run()
        assertEquals(1L, f.db.countMessages())

        f.settings.value = f.settings.value.copy(retentionDays = 7)
        f.sweep().run()
        assertEquals(0L, f.db.countMessages())
    }

    /**
     * Past the cap, messages without history (one version, not deleted) go first, oldest
     * first; histories and deletions are kept while there is anything else to drop.
     */
    @Test
    fun cap_evictsUnchangedMessagesBeforeHistories() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, maxRecords = 3))
        f.now = 10L
        f.repo.captureTdlibBaseline(chat, "edited", null, testMeta("v1"), originalDateMs = 1L)
        f.repo.captureTdlibEdit(chat, "edited", null, editedAtMs = 2L, meta = testMeta("v2"))
        f.repo.captureTdlibBaseline(chat, "deleted", null, testMeta("gone"), originalDateMs = 1L)
        f.repo.captureTdlibDelete(chat, listOf("deleted"), albumKey = null, isComment = false)
        for (i in 1..3) {
            f.now = 100L + i
            f.repo.captureTdlibBaseline(chat, "plain$i", null, testMeta("p$i"), originalDateMs = 1L)
        }

        f.sweep().run()

        assertEquals(listOf("deleted", "edited", "plain3"), keys(f))
    }

    @Test
    fun cap_thenTakesTheOldestHistories() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, maxRecords = 1))
        for (key in listOf("old", "new")) {
            f.now += 1_000L
            f.repo.captureTdlibBaseline(chat, key, null, testMeta("v1"), originalDateMs = 1L)
            f.repo.captureTdlibEdit(chat, key, null, editedAtMs = f.now, meta = testMeta("v2"))
        }

        f.sweep().run()

        assertEquals(listOf("new"), keys(f))
        assertEquals(2L, f.archive.count("MessageVersion"))
    }

    @Test
    fun capHoldsUnderBurst() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true, maxRecords = 100))
        repeat(250) { i ->
            f.repo.captureTdlibBaseline(chat, "$i", null, testMeta("v$i"), originalDateMs = 1_000L)
        }

        f.sweep().run()

        assertEquals(100L, f.db.countMessages())
    }

    /**
     * Phantom versions stored under an older canonical form fold into the earlier version,
     * which takes over the archived media file; the hashes are upgraded, so a second run
     * finds nothing.
     */
    @Test
    fun dedupe_foldsPhantoms_movesMediaSha_andIsIdempotent() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true))
        val blob = ContentBlobCodec.encode(testMeta("same"))
        val other = ContentBlobCodec.encode(testMeta("changed"))
        f.db.insertRawVersion(chat, "1", blob, "legacy-a", sortAtMs = 10L, hashVersion = ContentNormalizer.LEGACY_HASH_VERSION)
        f.db.insertRawVersion(
            chat, "1", blob, "legacy-b", sortAtMs = 20L,
            hashVersion = ContentNormalizer.LEGACY_HASH_VERSION, mediaSha = "sha-1",
        )
        f.db.insertRawVersion(chat, "1", other, "legacy-c", sortAtMs = 30L, hashVersion = ContentNormalizer.LEGACY_HASH_VERSION)

        assertEquals(1, f.db.dedupeVersions())

        val rows = f.db.versionsOf(chat, "1")
        assertEquals(listOf("same", "changed"), rows.map { it.text() })
        assertEquals(listOf(10L, 30L), rows.map { it.sort_at_ms })
        assertEquals("sha-1", rows[0].media_sha)
        assertEquals(ContentNormalizer.canonicalHash(testMeta("same")), rows[0].content_hash)
        assertEquals(listOf(ContentNormalizer.HASH_VERSION, ContentNormalizer.HASH_VERSION), rows.map { it.hash_version })

        assertEquals(0, f.db.dedupeVersions())
        assertEquals(2, f.db.versionsOf(chat, "1").size)
    }

    @Test
    fun dedupe_keepsVersionsAcrossAnUnreadableOne() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true))
        val blob = ContentBlobCodec.encode(testMeta("same"))
        val legacy = ContentNormalizer.LEGACY_HASH_VERSION
        f.db.insertRawVersion(chat, "1", blob, "a", sortAtMs = 10L, hashVersion = legacy)
        f.db.insertRawVersion(chat, "1", byteArrayOf(9, 9, 9), "bad", sortAtMs = 20L, hashVersion = legacy)
        f.db.insertRawVersion(chat, "1", blob, "b", sortAtMs = 30L, hashVersion = legacy)

        assertEquals(0, f.db.dedupeVersions())
        assertEquals(3, f.db.versionsOf(chat, "1").size)
        assertEquals(0, f.db.dedupeVersions())
    }

    /** A file is an orphan only when no version references it AND its last claim is older than the grace window. */
    @Test
    fun orphanQuery_honoursReferencesAndGrace() = runTest {
        val f = Fixture(ArchiveSettings(enabled = true))
        val q = f.db.archivedMediaFileQueries
        q.insert("referenced", "/a", 1L, null, 0L, 0L)
        q.insert("orphan-old", "/b", 1L, null, 0L, 0L)
        q.insert("orphan-fresh", "/c", 1L, null, 0L, 5_000L)
        f.db.insertRawVersion(chat, "1", ContentBlobCodec.encode(testMeta("x")), "h", sortAtMs = 1L, mediaSha = "referenced")

        val orphans = q.selectOrphans(claimedBefore = 1_000L).executeAsList().map { it.sha }

        assertEquals(listOf("orphan-old"), orphans)
    }
}
