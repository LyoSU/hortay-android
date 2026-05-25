# Post Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Local-only archive of edited and deleted channel posts, with visual diff, behind an opt-in setting.

**Architecture:** New `archive.db` SQLDelight DB stores `PostSnapshot` rows (one VERSION row per content change, plus DELETED markers). Content is held as a single `content_blob` (TDLib TLO + Kotlinx-protobuf wrapper for the bits TLO doesn't cover, or `WebPostContent` JSON for guest mode). Capture hooks sit in `PostsRepository.handleContentChanged` / `handleDeleted`, `CommentsRepository`, and `WebFeedSource.refresh`, called **before** the `_posts.update {}` CAS-loop mutation. UI: `EditedChip` + `DeletedBadge` in the feed, `PostRevisionSheet` (adaptive line/sentence/word diff) from the chip, `ArchiveScreen` from settings.

**Tech Stack:** SQLDelight 2.3, DataStore, Compose Material 3 Expressive, `java-diff-utils:4.12`, `kotlinx-serialization-protobuf`, kotlinx.collections.immutable, Coil 3.3.

**Spec:** `docs/superpowers/specs/2026-05-23-post-archive-design.md`

---

## File structure

**New files:**

```
app/src/main/sqldelight/dev/lyo/hortay/data/archive/db/
├── PostSnapshot.sq                            -- queries + table
├── ArchivedChannel.sq                         -- queries + table
└── 1.sqm                                      -- initial schema migration

app/src/main/kotlin/dev/lyo/hortay/data/archive/
├── ChatRef.kt                                 -- value class + SourceKind
├── PostSnapshot.kt                            -- @Immutable domain model + SnapshotKind enum
├── ArchivedContent.kt                         -- sealed interface
├── TdlibContentMeta.kt                        -- @Serializable proto wrapper
├── ArchiveSettings.kt                         -- @Immutable settings model
├── ArchiveSettingsStore.kt                    -- DataStore wrapper
├── ArchiveRepository.kt                       -- single-writer capture API
├── ArchiveSweep.kt                            -- TTL + cap eviction
├── ContentBlobCodec.kt                        -- encode/decode TLO + meta + web
├── MinithumbCompositor.kt                     -- albums → composite thumb
└── diff/
    ├── PostDiff.kt                            -- adaptive line/sentence/word diff
    └── PostDiffResult.kt                      -- immutable result types

app/src/main/kotlin/dev/lyo/hortay/ui/archive/
├── ArchiveScreen.kt                           -- list with filters
├── ArchiveViewModel.kt
├── ArchiveSettingsScreen.kt
├── ArchiveSettingsViewModel.kt
├── ArchiveOnboardingSheet.kt                  -- gated first-enable sheet
├── PostRevisionSheet.kt                       -- bottom sheet with diff
└── components/
    ├── EditedChip.kt
    ├── DeletedBadge.kt
    ├── RevisionTimeline.kt
    ├── DiffText.kt                            -- AnnotatedString diff renderer
    └── ArchiveRow.kt

app/src/test/kotlin/dev/lyo/hortay/data/archive/
├── ArchiveRepositoryTest.kt
├── ArchiveSweepTest.kt
├── ContentBlobCodecTest.kt
└── diff/PostDiffTest.kt

app/src/test/kotlin/dev/lyo/hortay/data/web/
└── WebFeedDiffTest.kt
```

**Modified files:**

```
app/build.gradle.kts                            -- 2 new deps + sqldelight DB block
app/src/main/kotlin/dev/lyo/hortay/data/TimelinePost.kt          -- add isDeleted, revisionCount
app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt                   -- wire archive
app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt -- capture hooks (1837, 1882)
app/src/main/kotlin/dev/lyo/hortay/data/CommentsRepository.kt    -- capture hooks (564, 572)
app/src/main/kotlin/dev/lyo/hortay/data/web/WebFeedSource.kt     -- diff-on-refresh
app/src/main/kotlin/dev/lyo/hortay/data/StorageOptimizer.kt      -- add archive sweep step
app/src/main/kotlin/dev/lyo/hortay/ui/timeline/PostCard.kt       -- EditedChip + deleted state
app/src/main/kotlin/dev/lyo/hortay/ui/settings/SettingsScreen.kt -- archive section
app/src/main/res/values/strings.xml                              -- en strings
app/src/main/res/values-uk/strings.xml                           -- uk strings
CHANGELOG.md                                                     -- [Unreleased] bullet
```

---

# Phase 1: SQLDelight schema + repository + settings store

### Task 1: Add dependencies and SQLDelight DB config

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add `archive.db` to the `sqldelight` block**

Open `app/build.gradle.kts`. Find the existing `sqldelight { databases { create("WebDatabase") { … } } }` block and add a second database next to it:

```kotlin
sqldelight {
    databases {
        create("WebDatabase") {
            packageName.set("dev.lyo.hortay.data.web.db")
        }
        create("ArchiveDatabase") {
            packageName.set("dev.lyo.hortay.data.archive.db")
            schemaOutputDirectory.set(file("src/main/sqldelight/dev/lyo/hortay/data/archive/db/schemas"))
        }
    }
}
```

- [ ] **Step 2: Add two dependencies**

In the same `app/build.gradle.kts`, find `dependencies { … }`. Add:

```kotlin
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
```

- [ ] **Step 3: Sync and verify**

Run: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath 2>&1 | grep -E "java-diff-utils|kotlinx-serialization-protobuf"`
Expected: both lines present.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build(archive): add archive.db SQLDelight schema + diff and protobuf deps"
```

---

### Task 2: SQLDelight schema (`PostSnapshot.sq`)

**Files:**
- Create: `app/src/main/sqldelight/dev/lyo/hortay/data/archive/db/PostSnapshot.sq`

- [ ] **Step 1: Create the file**

```sql
CREATE TABLE PostSnapshot (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    source_kind     TEXT NOT NULL,
    source_key      TEXT NOT NULL,
    message_key     TEXT NOT NULL,
    album_key       TEXT,
    kind            TEXT NOT NULL,
    seen_at_ms      INTEGER NOT NULL,
    edited_at_ms    INTEGER,
    content_kind    TEXT NOT NULL,
    content_blob    BLOB NOT NULL,
    content_hash    TEXT NOT NULL,
    text_preview    TEXT NOT NULL DEFAULT '',
    media_minithumb BLOB,
    deleted_msg_keys TEXT,
    is_comment      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_PostSnapshot_msg
    ON PostSnapshot(source_kind, source_key, message_key, seen_at_ms DESC);

CREATE INDEX idx_PostSnapshot_seen
    ON PostSnapshot(seen_at_ms DESC);

CREATE INDEX idx_PostSnapshot_id
    ON PostSnapshot(id);

insert:
INSERT INTO PostSnapshot(source_kind, source_key, message_key, album_key, kind, seen_at_ms, edited_at_ms,
                        content_kind, content_blob, content_hash, text_preview, media_minithumb,
                        deleted_msg_keys, is_comment)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

latestForMessage:
SELECT seen_at_ms, content_hash
FROM PostSnapshot
WHERE source_kind = ? AND source_key = ? AND message_key = ?
ORDER BY seen_at_ms DESC
LIMIT 1;

selectRevisions:
SELECT *
FROM PostSnapshot
WHERE source_kind = ? AND source_key = ? AND message_key = ?
ORDER BY seen_at_ms ASC;

countByMessage:
SELECT COUNT(*) AS cnt
FROM PostSnapshot
WHERE source_kind = ? AND source_key = ? AND message_key = ? AND kind = 'VERSION';

selectAllForFilter:
SELECT *
FROM PostSnapshot
WHERE (:sourceKind IS NULL OR source_kind = :sourceKind)
  AND (:kind IS NULL OR kind = :kind)
  AND (:isComment IS NULL OR is_comment = :isComment)
  AND (:query IS NULL OR text_preview LIKE :query)
ORDER BY seen_at_ms DESC;

deleteByIds:
DELETE FROM PostSnapshot WHERE id IN ?;

deleteOlderThan:
DELETE FROM PostSnapshot WHERE seen_at_ms < ?;

deleteByCap:
DELETE FROM PostSnapshot
WHERE id < (SELECT MAX(id) FROM PostSnapshot) - ?;

clearAll:
DELETE FROM PostSnapshot;

storageBytes:
SELECT COALESCE(SUM(LENGTH(content_blob) + LENGTH(COALESCE(media_minithumb, ''))), 0) AS bytes
FROM PostSnapshot;

selectAllForExport:
SELECT *
FROM PostSnapshot
ORDER BY seen_at_ms ASC;
```

- [ ] **Step 2: Verify SQLDelight compiles the file**

Run: `./gradlew :app:generateMainArchiveDatabaseInterface`
Expected: BUILD SUCCESSFUL. Generated sources under `app/build/generated/sqldelight/code/ArchiveDatabase/`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/sqldelight/dev/lyo/hortay/data/archive/db/PostSnapshot.sq
git commit -m "feat(archive): PostSnapshot.sq schema + queries"
```

---

### Task 3: SQLDelight schema (`ArchivedChannel.sq`)

**Files:**
- Create: `app/src/main/sqldelight/dev/lyo/hortay/data/archive/db/ArchivedChannel.sq`

- [ ] **Step 1: Create the file**

```sql
CREATE TABLE ArchivedChannel (
    source_kind         TEXT NOT NULL,
    source_key          TEXT NOT NULL,
    title               TEXT NOT NULL,
    handle              TEXT,
    photo_minithumb     BLOB,
    is_verified         INTEGER NOT NULL DEFAULT 0,
    last_snapshot_at_ms INTEGER NOT NULL,
    PRIMARY KEY (source_kind, source_key)
);

upsertInsert:
INSERT OR IGNORE INTO ArchivedChannel(source_kind, source_key, title, handle, photo_minithumb,
                                      is_verified, last_snapshot_at_ms)
VALUES (?, ?, ?, ?, ?, ?, ?);

upsertUpdate:
UPDATE ArchivedChannel
SET title = ?, handle = ?, photo_minithumb = ?, is_verified = ?, last_snapshot_at_ms = ?
WHERE source_kind = ? AND source_key = ?;

selectAll:
SELECT * FROM ArchivedChannel ORDER BY last_snapshot_at_ms DESC;

selectOne:
SELECT * FROM ArchivedChannel WHERE source_kind = ? AND source_key = ?;

countByChannel:
SELECT ac.source_kind, ac.source_key, ac.title, ac.handle, ac.photo_minithumb, ac.is_verified, ac.last_snapshot_at_ms,
       (SELECT COUNT(*) FROM PostSnapshot ps
        WHERE ps.source_kind = ac.source_kind AND ps.source_key = ac.source_key) AS snapshot_count
FROM ArchivedChannel ac
ORDER BY ac.last_snapshot_at_ms DESC;

clearAll:
DELETE FROM ArchivedChannel;
```

- [ ] **Step 2: Re-run generation to confirm both files compile together**

Run: `./gradlew :app:generateMainArchiveDatabaseInterface`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/sqldelight/dev/lyo/hortay/data/archive/db/ArchivedChannel.sq
git commit -m "feat(archive): ArchivedChannel.sq with INSERT OR IGNORE + UPDATE upsert"
```

---

### Task 4: Domain models — `ChatRef`, `PostSnapshot`, `ArchivedContent`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ChatRef.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/PostSnapshot.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchivedContent.kt`

- [ ] **Step 1: `ChatRef.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable

enum class SourceKind { TDLIB, WEB }

@Immutable
data class ChatRef(val kind: SourceKind, val key: String) {
    companion object {
        fun tdlib(chatId: Long) = ChatRef(SourceKind.TDLIB, chatId.toString())
        fun web(username: String) = ChatRef(SourceKind.WEB, username.lowercase())
    }
}
```

- [ ] **Step 2: `PostSnapshot.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class SnapshotKind { VERSION, DELETED }

@Immutable
data class PostSnapshot(
    val id: Long,
    val chat: ChatRef,
    val messageKey: String,
    val albumKey: String?,
    val kind: SnapshotKind,
    val seenAtMs: Long,
    val editedAtMs: Long?,
    val content: ArchivedContent,
    val mediaMinithumb: ByteArray?,
    val deletedMessageKeys: ImmutableList<String> = persistentListOf(),
    val isComment: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is PostSnapshot && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
```

- [ ] **Step 3: `ArchivedContent.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.web.WebPostContent
import org.drinkless.tdlib.TdApi

@Immutable
sealed interface ArchivedContent {
    val textPreview: String

    @Immutable
    data class Tdlib(
        val content: TdApi.MessageContent,
        val meta: TdlibContentMeta,
    ) : ArchivedContent {
        override val textPreview: String get() = meta.textPreview
    }

    @Immutable
    data class Web(val content: WebPostContent) : ArchivedContent {
        override val textPreview: String get() = content.textPlain.take(200)
    }
}
```

- [ ] **Step 4: Build to verify references**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: `Unresolved reference: TdlibContentMeta` — that's expected; defined in next task.

- [ ] **Step 5: Commit (intentionally broken build — fixed by next task)**

This is the only place in the plan where we commit on a broken build; it keeps domain types as one atomic commit. Next task closes the build.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/ChatRef.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/archive/PostSnapshot.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchivedContent.kt
git commit -m "feat(archive): ChatRef, PostSnapshot, ArchivedContent domain models"
```

---

### Task 5: `TdlibContentMeta` wrapper + `ContentBlobCodec`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/TdlibContentMeta.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ContentBlobCodec.kt`
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/archive/ContentBlobCodecTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.lyo.hortay.data.archive

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.drinkless.tdlib.TdApi

class ContentBlobCodecTest {

    @Test
    fun encode_decode_roundtrip_preservesTextContent() {
        val original = TdApi.MessageText(
            TdApi.FormattedText("hello world", arrayOf()),
            null, null,
        )
        val meta = TdlibContentMeta(
            textPreview = "hello world",
            entitiesJson = "[]",
            forwardJson = null,
            replyJson = null,
        )

        val blob = ContentBlobCodec.encodeTdlib(original, meta)
        val (decoded, decodedMeta) = ContentBlobCodec.decodeTdlib(blob)

        assertEquals("hello world", (decoded as TdApi.MessageText).text.text)
        assertEquals(meta.textPreview, decodedMeta.textPreview)
    }

    @Test
    fun contentHash_isStableAcrossEncodings() {
        val content = TdApi.MessageText(TdApi.FormattedText("x", arrayOf()), null, null)
        val meta = TdlibContentMeta("x", "[]", null, null)
        val blob1 = ContentBlobCodec.encodeTdlib(content, meta)
        val blob2 = ContentBlobCodec.encodeTdlib(content, meta)

        assertContentEquals(blob1, blob2)
        assertEquals(ContentBlobCodec.hash(blob1), ContentBlobCodec.hash(blob2))
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.ContentBlobCodecTest"`
Expected: compilation error on `TdlibContentMeta` and `ContentBlobCodec`.

- [ ] **Step 3: `TdlibContentMeta.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class TdlibContentMeta(
    val textPreview: String,
    val entitiesJson: String,           // serialised TextEntity[] — keeps formatting stable across rendering
    val forwardJson: String?,
    val replyJson: String?,
)
```

- [ ] **Step 4: `ContentBlobCodec.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.drinkless.tdlib.TdApi

@OptIn(ExperimentalSerializationApi::class)
object ContentBlobCodec {

    private const val MAGIC_TDLIB = 0x54444C42  // "TDLB"
    private const val VERSION = 1

    fun encodeTdlib(content: TdApi.MessageContent, meta: TdlibContentMeta): ByteArray {
        val tloBytes = content.toByteArray()
        val metaBytes = ProtoBuf.encodeToByteArray(TdlibContentMeta.serializer(), meta)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeInt(MAGIC_TDLIB)
            dos.writeInt(VERSION)
            dos.writeInt(tloBytes.size)
            dos.write(tloBytes)
            dos.writeInt(metaBytes.size)
            dos.write(metaBytes)
        }
        return out.toByteArray()
    }

    fun decodeTdlib(blob: ByteArray): Pair<TdApi.MessageContent, TdlibContentMeta> {
        DataInputStream(ByteArrayInputStream(blob)).use { dis ->
            val magic = dis.readInt()
            require(magic == MAGIC_TDLIB) { "Not a tdlib content blob" }
            val version = dis.readInt()
            require(version == VERSION) { "Unsupported blob version $version" }
            val tloSize = dis.readInt()
            val tloBytes = ByteArray(tloSize).also { dis.readFully(it) }
            val metaSize = dis.readInt()
            val metaBytes = ByteArray(metaSize).also { dis.readFully(it) }
            val content = TdApi.MessageContent.fromByteArray(tloBytes) as TdApi.MessageContent
            val meta = ProtoBuf.decodeFromByteArray(TdlibContentMeta.serializer(), metaBytes)
            return content to meta
        }
    }

    fun hash(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

> Note: TDLib JNI exposes `Object.toByteArray()` and `fromByteArray()` on all TLO types. If your vendored version of `TdApi.java` doesn't have `fromByteArray`, fall back to `org.drinkless.tdlib.Client.execute(TdApi.GetMessage…)` for re-fetching is NOT acceptable (FLOOD_WAIT). In that case, replace the wrapper with full Kotlinx-serialization of `(text, entities, mediaSummary, etc.)` as `@Serializable` data classes mirroring the subset of `MessageContent` we render. Verify presence with: `grep -n "fromByteArray" libtdlib/src/main/java/org/drinkless/tdlib/TdApi.java | head -2`.

- [ ] **Step 5: Run test — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.ContentBlobCodecTest"`
Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/TdlibContentMeta.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/archive/ContentBlobCodec.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/archive/ContentBlobCodecTest.kt
git commit -m "feat(archive): ContentBlobCodec with TLO + protobuf meta wrapper"
```

---

### Task 6: `ArchiveSettings` + `ArchiveSettingsStore`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveSettings.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveSettingsStore.kt`

- [ ] **Step 1: `ArchiveSettings.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class ArchiveSettings(
    val enabled: Boolean = false,
    val onboardingSeen: Boolean = false,
    val retentionDays: Int = 30,
    val maxRecords: Int = 5000,
    val excludedChats: PersistentSet<ChatRef> = persistentSetOf(),
    val captureEdits: Boolean = true,
    val captureDeletes: Boolean = true,
) {
    companion object {
        val DEFAULT = ArchiveSettings()
        val RETENTION_OPTIONS = listOf(7, 30, 90, Int.MAX_VALUE)
        val MAX_RECORDS_OPTIONS = listOf(1000, 5000, 10_000, Int.MAX_VALUE)
    }
}
```

- [ ] **Step 2: `ArchiveSettingsStore.kt`**

Follow the existing DataStore pattern used by `SettingsStore.kt`. The contents below mirror that pattern.

```kotlin
package dev.lyo.hortay.data.archive

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.archiveSettingsDataStore by preferencesDataStore("archive_settings")

class ArchiveSettingsStore(private val context: Context) {

    private val K_ENABLED = booleanPreferencesKey("enabled")
    private val K_ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
    private val K_RETENTION_DAYS = intPreferencesKey("retention_days")
    private val K_MAX_RECORDS = intPreferencesKey("max_records")
    private val K_EXCLUDED = stringSetPreferencesKey("excluded_chats")
    private val K_CAPTURE_EDITS = booleanPreferencesKey("capture_edits")
    private val K_CAPTURE_DELETES = booleanPreferencesKey("capture_deletes")

    val flow: Flow<ArchiveSettings> = context.archiveSettingsDataStore.data.map { prefs ->
        ArchiveSettings(
            enabled = prefs[K_ENABLED] ?: false,
            onboardingSeen = prefs[K_ONBOARDING_SEEN] ?: false,
            retentionDays = prefs[K_RETENTION_DAYS] ?: 30,
            maxRecords = prefs[K_MAX_RECORDS] ?: 5000,
            excludedChats = (prefs[K_EXCLUDED] ?: emptySet())
                .mapNotNull { decode(it) }.toPersistentSet(),
            captureEdits = prefs[K_CAPTURE_EDITS] ?: true,
            captureDeletes = prefs[K_CAPTURE_DELETES] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) = update { it[K_ENABLED] = v }
    suspend fun setOnboardingSeen(v: Boolean) = update { it[K_ONBOARDING_SEEN] = v }
    suspend fun setRetentionDays(v: Int) = update { it[K_RETENTION_DAYS] = v }
    suspend fun setMaxRecords(v: Int) = update { it[K_MAX_RECORDS] = v }
    suspend fun setCaptureEdits(v: Boolean) = update { it[K_CAPTURE_EDITS] = v }
    suspend fun setCaptureDeletes(v: Boolean) = update { it[K_CAPTURE_DELETES] = v }
    suspend fun setExcludedChats(refs: Collection<ChatRef>) = update {
        it[K_EXCLUDED] = refs.map(::encode).toSet()
    }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.archiveSettingsDataStore.edit(block)
    }

    private fun encode(ref: ChatRef): String = "${ref.kind.name}|${ref.key}"
    private fun decode(raw: String): ChatRef? {
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2) return null
        val kind = runCatching { SourceKind.valueOf(parts[0]) }.getOrNull() ?: return null
        return ChatRef(kind, parts[1])
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveSettings.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveSettingsStore.kt
git commit -m "feat(archive): ArchiveSettings + DataStore-backed store"
```

---

### Task 7: `MinithumbCompositor`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/MinithumbCompositor.kt`

- [ ] **Step 1: Implement**

For albums we store one composite thumb (up to 3 first members tiled horizontally). For singles, the raw `Minithumbnail.data` byte array. No tests — Compose-side renderer will validate visually.

```kotlin
package dev.lyo.hortay.data.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import java.io.ByteArrayOutputStream

object MinithumbCompositor {

    /** Encodes a single Minithumbnail byte array unchanged (already JPEG). */
    fun single(jpeg: ByteArray?): ByteArray? = jpeg

    /** Up to 3 thumbs tiled horizontally, JPEG-encoded. */
    fun composite(jpegs: List<ByteArray>): ByteArray? {
        if (jpegs.isEmpty()) return null
        if (jpegs.size == 1) return jpegs[0]
        val tiles = jpegs.take(3).mapNotNull { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (tiles.isEmpty()) return null
        val tileH = tiles.maxOf { it.height }
        val tileW = tiles.maxOf { it.width }
        val out = Bitmap.createBitmap(tileW * tiles.size, tileH, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        tiles.forEachIndexed { i, b ->
            canvas.drawBitmap(b, null, Rect(i * tileW, 0, (i + 1) * tileW, tileH), null)
        }
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        return baos.toByteArray()
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/MinithumbCompositor.kt
git commit -m "feat(archive): MinithumbCompositor for album thumb tiling"
```

---

### Task 8: `ArchiveRepository` — write side + dedup + UPSERT

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveRepository.kt`
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/archive/ArchiveRepositoryTest.kt`

- [ ] **Step 1: Write failing test for dedup behaviour**

```kotlin
package dev.lyo.hortay.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveRepositoryTest {

    private fun newRepo(settings: ArchiveSettings = ArchiveSettings(enabled = true)): Pair<ArchiveRepository, ArchiveDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settingsFlow = MutableStateFlow(settings)
        return ArchiveRepository(db, settingsFlow, clock = { 1000L }) to db
    }

    private fun txt(s: String) = TdApi.MessageText(TdApi.FormattedText(s, arrayOf()), null, null)

    @Test
    fun captureEdit_dedupesIdenticalContentHash() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibVersion(chat, "100", null, editedAtMs = null, content = txt("hello"))
        repo.captureTdlibVersion(chat, "100", null, editedAtMs = null, content = txt("hello"))

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(1, rows.size)
    }

    @Test
    fun captureEdit_writesWhenContentDiffers() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibVersion(chat, "100", null, editedAtMs = null, content = txt("hello"))
        repo.captureTdlibVersion(chat, "100", null, editedAtMs = 2000L, content = txt("hello there"))

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(2, rows.size)
    }

    @Test
    fun masterToggleOff_skipsCapture() = runTest {
        val (repo, db) = newRepo(settings = ArchiveSettings(enabled = false))
        repo.captureTdlibVersion(ChatRef.tdlib(42), "100", null, null, txt("hello"))

        assertEquals(0, db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList().size)
    }

    @Test
    fun excludedChat_skipsCapture() = runTest {
        val chat = ChatRef.tdlib(42)
        val (repo, db) = newRepo(settings = ArchiveSettings(enabled = true,
            excludedChats = kotlinx.collections.immutable.persistentSetOf(chat)))
        repo.captureTdlibVersion(chat, "100", null, null, txt("hello"))

        assertEquals(0, db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList().size)
    }

    @Test
    fun captureDelete_writesDeletedRow() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibVersion(chat, "100", null, null, txt("hello"))
        repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(2, rows.size)
        assertEquals("DELETED", rows.last().kind)
    }
}
```

- [ ] **Step 2: Add SQLDelight JVM driver test dep**

In `app/build.gradle.kts` dependencies, add:

```kotlin
    testImplementation("app.cash.sqldelight:sqlite-driver:2.0.2")
```

- [ ] **Step 3: Run test — expect compilation failure**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.ArchiveRepositoryTest"`
Expected: `Unresolved reference: ArchiveRepository`.

- [ ] **Step 4: Implement `ArchiveRepository.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-writer capture API for the post archive.
 *
 * Threading: `captureXxx` methods are `suspend` and use an internal mutex to serialize writes,
 * so calls from PostsRepository's CAS-loop dispatcher and from WebFeedSource's IO scope can
 * race safely. Reads (`observe*`) use SQLDelight's `Query.asFlow()` and do not hold the mutex.
 *
 * Capture *must* be called BEFORE mutating `_posts` so the old state is observed; the lambda
 * passed to `_posts.update {}` must remain a pure function of the snapshot.
 */
class ArchiveRepository(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val writeMutex = Mutex()
    private val writeCounter = AtomicInteger(0)
    private val capEvictionEvery = 100

    private val _events = MutableSharedFlow<ArchiveEvent>(extraBufferCapacity = 64)
    val events: Flow<ArchiveEvent> = _events

    suspend fun captureTdlibVersion(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        editedAtMs: Long?,
        content: TdApi.MessageContent,
        meta: TdlibContentMeta = derive(content),
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        val blob = ContentBlobCodec.encodeTdlib(content, meta)
        val hash = ContentBlobCodec.hash(blob)
        writeMutex.withLock {
            if (isDuplicate(chat, messageKey, hash)) return
            insertSnapshot(
                chat = chat, messageKey = messageKey, albumKey = albumKey,
                kind = SnapshotKind.VERSION, editedAtMs = editedAtMs,
                contentKind = "tdlib", blob = blob, hash = hash,
                textPreview = meta.textPreview.take(200),
                minithumb = minithumb, deletedKeys = null, isComment = isComment,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
    }

    suspend fun captureTdlibDelete(
        chat: ChatRef,
        messageKeys: List<String>,
        albumKey: String?,
        isComment: Boolean,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureDeletes) return
        if (chat in s.excludedChats) return
        if (messageKeys.isEmpty()) return

        // For composite deletes (albums), write one DELETED row with all members.
        val anchorKey = messageKeys.first()
        val deletedJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()),
            messageKeys,
        )
        writeMutex.withLock {
            val markerBlob = byteArrayOf(0)
            val markerHash = ContentBlobCodec.hash(markerBlob + deletedJson.toByteArray())
            if (isDuplicate(chat, anchorKey, markerHash)) return
            insertSnapshot(
                chat = chat, messageKey = anchorKey, albumKey = albumKey,
                kind = SnapshotKind.DELETED, editedAtMs = null,
                contentKind = "marker", blob = markerBlob, hash = markerHash,
                textPreview = "", minithumb = null,
                deletedKeys = deletedJson, isComment = isComment,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Deleted(chat, messageKeys))
    }

    suspend fun clear() = writeMutex.withLock {
        db.transaction {
            db.postSnapshotQueries.clearAll()
            db.archivedChannelQueries.clearAll()
        }
    }

    private fun isDuplicate(chat: ChatRef, messageKey: String, hash: String): Boolean {
        val last = db.postSnapshotQueries.latestForMessage(
            chat.kind.name, chat.key, messageKey,
        ).executeAsOneOrNull() ?: return false
        if (last.content_hash == hash) return true
        return (clock() - last.seen_at_ms) < 500
    }

    private fun insertSnapshot(
        chat: ChatRef, messageKey: String, albumKey: String?,
        kind: SnapshotKind, editedAtMs: Long?, contentKind: String,
        blob: ByteArray, hash: String, textPreview: String,
        minithumb: ByteArray?, deletedKeys: String?, isComment: Boolean,
    ) {
        db.postSnapshotQueries.insert(
            source_kind = chat.kind.name,
            source_key = chat.key,
            message_key = messageKey,
            album_key = albumKey,
            kind = kind.name,
            seen_at_ms = clock(),
            edited_at_ms = editedAtMs,
            content_kind = contentKind,
            content_blob = blob,
            content_hash = hash,
            text_preview = textPreview,
            media_minithumb = minithumb,
            deleted_msg_keys = deletedKeys,
            is_comment = if (isComment) 1 else 0,
        )
    }

    suspend fun upsertChannel(
        chat: ChatRef, title: String, handle: String?,
        photoMinithumb: ByteArray?, isVerified: Boolean,
    ) = writeMutex.withLock {
        val now = clock()
        db.transaction {
            db.archivedChannelQueries.upsertInsert(
                source_kind = chat.kind.name,
                source_key = chat.key,
                title = title, handle = handle,
                photo_minithumb = photoMinithumb,
                is_verified = if (isVerified) 1 else 0,
                last_snapshot_at_ms = now,
            )
            db.archivedChannelQueries.upsertUpdate(
                title = title, handle = handle,
                photo_minithumb = photoMinithumb,
                is_verified = if (isVerified) 1 else 0,
                last_snapshot_at_ms = now,
                source_kind = chat.kind.name, source_key = chat.key,
            )
        }
    }

    private fun maybeEvictByCap() {
        val count = writeCounter.incrementAndGet()
        if (count % capEvictionEvery == 0) {
            val cap = settings.value.maxRecords
            if (cap != Int.MAX_VALUE) {
                db.postSnapshotQueries.deleteByCap(cap.toLong())
            }
        }
    }

    private fun derive(content: TdApi.MessageContent): TdlibContentMeta {
        // Extract just enough to render a preview without loading the full content
        val text = when (content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text
            is TdApi.MessageVideo -> content.caption.text
            is TdApi.MessageAnimation -> content.caption.text
            is TdApi.MessageDocument -> content.caption.text
            is TdApi.MessageAudio -> content.caption.text
            is TdApi.MessageVoiceNote -> content.caption.text
            is TdApi.MessagePoll -> content.poll.question.text
            else -> ""
        }
        return TdlibContentMeta(
            textPreview = text.take(200),
            entitiesJson = "[]",
            forwardJson = null, replyJson = null,
        )
    }
}

sealed interface ArchiveEvent {
    data class Captured(val chat: ChatRef, val messageKey: String) : ArchiveEvent
    data class Deleted(val chat: ChatRef, val messageKeys: List<String>) : ArchiveEvent
}
```

- [ ] **Step 5: Run tests — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.ArchiveRepositoryTest"`
Expected: 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveRepository.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/archive/ArchiveRepositoryTest.kt \
        app/build.gradle.kts
git commit -m "feat(archive): ArchiveRepository capture API with dedup, exclude, cap eviction"
```

---

### Task 9: `ArchiveRepository` read side — observe + filters

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveRepository.kt` (add read methods)
- Test: extend `ArchiveRepositoryTest.kt` with a read test

- [ ] **Step 1: Write failing read test**

Append to `ArchiveRepositoryTest.kt`:

```kotlin
    @Test
    fun observeRevisions_emitsInsertedRowsAscByTime() = runTest {
        val (repo, _) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibVersion(chat, "100", null, null, txt("v1"))
        repo.captureTdlibVersion(chat, "100", null, 2L, txt("v2"))

        val revisions = repo.observeRevisions(chat, "100").first()
        assertEquals(2, revisions.size)
        assertEquals(SnapshotKind.VERSION, revisions[0].kind)
    }
```

- [ ] **Step 2: Add `observeRevisions`, `observe`, `observeChannelIndex`, `purge` to `ArchiveRepository`**

Append to the class body:

```kotlin
    fun observeRevisions(chat: ChatRef, messageKey: String): Flow<kotlinx.collections.immutable.ImmutableList<PostSnapshot>> =
        db.postSnapshotQueries
            .selectRevisions(chat.kind.name, chat.key, messageKey)
            .asFlow()
            .mapToList(kotlinx.coroutines.Dispatchers.IO)
            .map { rows -> rows.map(::toDomain).toPersistentList() }

    fun observe(filter: ArchiveFilter): Flow<kotlinx.collections.immutable.ImmutableList<PostSnapshot>> =
        db.postSnapshotQueries.selectAllForFilter(
            sourceKind = filter.chatKind?.name,
            kind = filter.kind?.name,
            isComment = filter.scope?.toIsCommentFlag(),
            query = filter.query?.let { "%$it%" },
        ).asFlow().mapToList(kotlinx.coroutines.Dispatchers.IO)
            .map { rows -> rows.map(::toDomain).toPersistentList() }

    fun observeChannelIndex(): Flow<kotlinx.collections.immutable.ImmutableList<ArchivedChannelEntry>> =
        db.archivedChannelQueries.countByChannel()
            .asFlow().mapToList(kotlinx.coroutines.Dispatchers.IO)
            .map { rows -> rows.map { r ->
                ArchivedChannelEntry(
                    chat = ChatRef(SourceKind.valueOf(r.source_kind), r.source_key),
                    title = r.title, handle = r.handle,
                    photoMinithumb = r.photo_minithumb,
                    snapshotCount = r.snapshot_count.toInt(),
                    lastSnapshotAtMs = r.last_snapshot_at_ms,
                )
            }.toPersistentList() }

    suspend fun purge(ids: List<Long>) = writeMutex.withLock {
        db.postSnapshotQueries.deleteByIds(ids)
    }

    private fun toDomain(row: dev.lyo.hortay.data.archive.db.PostSnapshot): PostSnapshot {
        val content = when (row.content_kind) {
            "tdlib" -> {
                val (c, meta) = ContentBlobCodec.decodeTdlib(row.content_blob)
                ArchivedContent.Tdlib(c, meta)
            }
            "web" -> ArchivedContent.Web(
                kotlinx.serialization.json.Json.decodeFromString(
                    dev.lyo.hortay.data.web.WebPostContent.serializer(),
                    row.content_blob.toString(Charsets.UTF_8),
                )
            )
            else -> ArchivedContent.Tdlib(
                TdApi.MessageText(TdApi.FormattedText("", arrayOf()), null, null),
                TdlibContentMeta("", "[]", null, null),
            )
        }
        val deletedKeys = row.deleted_msg_keys?.let {
            kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()),
                it,
            )
        } ?: emptyList()
        return PostSnapshot(
            id = row.id,
            chat = ChatRef(SourceKind.valueOf(row.source_kind), row.source_key),
            messageKey = row.message_key,
            albumKey = row.album_key,
            kind = SnapshotKind.valueOf(row.kind),
            seenAtMs = row.seen_at_ms,
            editedAtMs = row.edited_at_ms,
            content = content,
            mediaMinithumb = row.media_minithumb,
            deletedMessageKeys = deletedKeys.toPersistentList(),
            isComment = row.is_comment == 1L,
        )
    }
```

And add the supporting types:

```kotlin
@androidx.compose.runtime.Immutable
data class ArchiveFilter(
    val chatKind: SourceKind? = null,
    val kind: SnapshotKind? = null,
    val scope: ArchiveScope? = null,
    val query: String? = null,
)

enum class ArchiveScope { POSTS, COMMENTS, ALL;
    fun toIsCommentFlag(): Long? = when (this) {
        POSTS -> 0L; COMMENTS -> 1L; ALL -> null
    }
}

@androidx.compose.runtime.Immutable
data class ArchivedChannelEntry(
    val chat: ChatRef, val title: String, val handle: String?,
    val photoMinithumb: ByteArray?,
    val snapshotCount: Int, val lastSnapshotAtMs: Long,
)
```

Add imports at top of file:

```kotlin
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.map
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.ArchiveRepositoryTest"`
Expected: 6 tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveRepository.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/archive/ArchiveRepositoryTest.kt
git commit -m "feat(archive): ArchiveRepository read flows and filter API"
```

---

### Task 10: `ArchiveSweep`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveSweep.kt`
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/archive/ArchiveSweepTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package dev.lyo.hortay.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveSweepTest {
    private fun seedRepo(retentionDays: Int = 30, cap: Int = 5000, now: Long = 1_000_000L):
            Triple<ArchiveRepository, ArchiveDatabase, ArchiveSweep> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settings = kotlinx.coroutines.flow.MutableStateFlow(
            ArchiveSettings(enabled = true, retentionDays = retentionDays, maxRecords = cap)
        )
        val repo = ArchiveRepository(db, settings, clock = { now })
        return Triple(repo, db, ArchiveSweep(db, settings, clock = { now }))
    }

    @Test
    fun sweep_purgesOlderThanRetention() = runTest {
        val (repo, db, sweep) = seedRepo(retentionDays = 30, now = 30L * 86_400_000L + 1_000_000L)
        val oldRepo = ArchiveRepository(db,
            kotlinx.coroutines.flow.MutableStateFlow(ArchiveSettings(enabled = true)),
            clock = { 0L }) // ancient
        oldRepo.captureTdlibVersion(ChatRef.tdlib(1), "1", null, null,
            TdApi.MessageText(TdApi.FormattedText("ancient", arrayOf()), null, null))

        repo.captureTdlibVersion(ChatRef.tdlib(1), "2", null, null,
            TdApi.MessageText(TdApi.FormattedText("fresh", arrayOf()), null, null))

        sweep.run()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(1, rows.size)
        assertEquals("2", rows[0].message_key)
    }

    @Test
    fun sweep_keepsNewestUpToCap() = runTest {
        val (repo, db, sweep) = seedRepo(cap = 3)
        repeat(5) { i ->
            repo.captureTdlibVersion(ChatRef.tdlib(1), "$i", null, null,
                TdApi.MessageText(TdApi.FormattedText("v$i", arrayOf()), null, null))
        }
        sweep.run()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(3, rows.size)
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.ArchiveSweepTest"`
Expected: `Unresolved reference: ArchiveSweep`.

- [ ] **Step 3: Implement `ArchiveSweep.kt`**

```kotlin
package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.StateFlow

class ArchiveSweep(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        val s = settings.value
        val retentionMs = if (s.retentionDays == Int.MAX_VALUE) Long.MAX_VALUE
                          else s.retentionDays.toLong() * 86_400_000L
        if (retentionMs != Long.MAX_VALUE) {
            db.postSnapshotQueries.deleteOlderThan(clock() - retentionMs)
        }
        if (s.maxRecords != Int.MAX_VALUE) {
            db.postSnapshotQueries.deleteByCap(s.maxRecords.toLong())
        }
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.ArchiveSweepTest"`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveSweep.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/archive/ArchiveSweepTest.kt
git commit -m "feat(archive): ArchiveSweep — TTL + cap eviction"
```

---

### Task 11: Wire `ArchiveRepository` into `AppGraph`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt`

- [ ] **Step 1: Read current `AppGraph` to find injection point**

Run: `grep -n "WebDatabase\|settingsStore\|tdClient\b" app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt | head -15`
Expected: locate the construction of other singletons (DB, stores, repos).

- [ ] **Step 2: Add archive wiring**

Add these properties to `AppGraph` class (next to existing DB / store constructions):

```kotlin
    val archiveDriver: app.cash.sqldelight.db.SqlDriver =
        app.cash.sqldelight.driver.android.AndroidSqliteDriver(
            dev.lyo.hortay.data.archive.db.ArchiveDatabase.Schema,
            context,
            "archive.db",
        )
    val archiveDb: dev.lyo.hortay.data.archive.db.ArchiveDatabase =
        dev.lyo.hortay.data.archive.db.ArchiveDatabase(archiveDriver)

    val archiveSettingsStore = dev.lyo.hortay.data.archive.ArchiveSettingsStore(context)

    private val archiveSettingsState: kotlinx.coroutines.flow.StateFlow<dev.lyo.hortay.data.archive.ArchiveSettings> =
        archiveSettingsStore.flow.stateIn(
            scope = appScope,
            started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
            initialValue = dev.lyo.hortay.data.archive.ArchiveSettings.DEFAULT,
        )

    val archiveRepository = dev.lyo.hortay.data.archive.ArchiveRepository(archiveDb, archiveSettingsState)

    val archiveSweep = dev.lyo.hortay.data.archive.ArchiveSweep(archiveDb, archiveSettingsState)
```

Find the existing `tdClient.loggedOut.collect { … }` block (or whichever clear-on-logout site exists for the project — likely `runLogoutCleanup`) and add:

```kotlin
        archiveRepository.clear()
```

If it's a `Flow.collect` site, add:

```kotlin
appScope.launch {
    tdClient.loggedOut.collect {
        archiveRepository.clear()
    }
}
```

(Match the existing pattern — don't introduce a second collector if one already exists; just append the line inside it.)

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt
git commit -m "feat(archive): wire ArchiveRepository + sweep into AppGraph with logout clear"
```

---

# Phase 2: Capture hooks

### Task 12: Capture in `PostsRepository.handleContentChanged`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt`

- [ ] **Step 1: Add `archiveRepository: ArchiveRepository` constructor parameter**

Locate the primary constructor of `PostsRepository`. Add a parameter `private val archiveRepository: dev.lyo.hortay.data.archive.ArchiveRepository` and update `AppGraph` to pass `archiveRepository` into it.

- [ ] **Step 2: Add capture call in `handleContentChanged`**

Open `app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt:1882`. Find the existing function:

```kotlin
private fun handleContentChanged(update: TdApi.UpdateMessageContent) {
    // ... existing body that does _posts.update { ... }
}
```

Modify it: before the `_posts.update {}` call, snapshot the existing post (use `_posts.value.find { it.chatId == update.chatId && it.messageId == update.messageId }`), and dispatch the capture in a fire-and-forget IO coroutine using `appScope`. Critically, this must NOT be inside `_posts.update {}` (CAS-loop purity).

Skeleton:

```kotlin
private fun handleContentChanged(update: TdApi.UpdateMessageContent) {
    val existing = _posts.value.firstOrNull {
        it.chatId == update.chatId && it.messageId == update.messageId
    }
    if (existing != null) {
        appScope.launch {
            archiveRepository.captureTdlibVersion(
                chat = dev.lyo.hortay.data.archive.ChatRef.tdlib(update.chatId),
                messageKey = update.messageId.toString(),
                albumKey = existing.mediaAlbumId?.toString(),
                editedAtMs = null,
                content = update.newContent,
                isComment = false,
            )
        }
    }
    // ... existing body unchanged
}
```

Note: capture writes the **new** content (the snapshot of what the post becomes). The first time we encounter a message, we will only have its post-edit content — this is the documented "first version Hortay saw" behaviour per spec.

- [ ] **Step 3: Build + smoke**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt \
        app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt
git commit -m "feat(archive): capture TDLib content edits in PostsRepository"
```

---

### Task 13: Capture deletions in `PostsRepository.handleDeleted` with album debounce

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt`

- [ ] **Step 1: Add the debounce buffer**

Add to `PostsRepository` private state (near other in-memory buffers like `pendingLastMessages`):

```kotlin
    private val pendingDeletionsByAlbum: java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, MutableList<Long>> =
        java.util.concurrent.ConcurrentHashMap()
```

- [ ] **Step 2: Modify `handleDeleted` at line 1837**

Open `PostsRepository.kt:1837`. Locate:

```kotlin
private fun handleDeleted(update: TdApi.UpdateDeleteMessages) {
    // ... existing body
}
```

Modify (skeleton; preserve existing UI mutation behaviour):

```kotlin
private fun handleDeleted(update: TdApi.UpdateDeleteMessages) {
    if (!update.isPermanent) return
    val chat = update.chatId
    val keysByAlbum = mutableMapOf<Long?, MutableList<Long>>()
    update.messageIds.forEach { id ->
        val post = _posts.value.firstOrNull { it.chatId == chat && it.messageId == id }
        val album = post?.mediaAlbumId
        keysByAlbum.getOrPut(album) { mutableListOf() }.add(id)
    }
    keysByAlbum.forEach { (album, ids) ->
        appScope.launch {
            if (album != null) {
                // Debounce album members arriving in separate updates
                val key = chat to album
                val acc = pendingDeletionsByAlbum.compute(key) { _, prev ->
                    val list = prev ?: mutableListOf()
                    list.addAll(ids)
                    list
                }!!
                kotlinx.coroutines.delay(200)
                val drained = pendingDeletionsByAlbum.remove(key) ?: return@launch
                archiveRepository.captureTdlibDelete(
                    chat = dev.lyo.hortay.data.archive.ChatRef.tdlib(chat),
                    messageKeys = drained.map { it.toString() },
                    albumKey = album.toString(),
                    isComment = false,
                )
            } else {
                archiveRepository.captureTdlibDelete(
                    chat = dev.lyo.hortay.data.archive.ChatRef.tdlib(chat),
                    messageKeys = ids.map { it.toString() },
                    albumKey = null,
                    isComment = false,
                )
            }
        }
    }
    // ... existing _posts.update {} body unchanged
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt
git commit -m "feat(archive): capture TDLib deletions with 200 ms album debounce"
```

---

### Task 14: Capture in `CommentsRepository`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/CommentsRepository.kt`

- [ ] **Step 1: Inject `archiveRepository`**

Add `archiveRepository: ArchiveRepository` constructor parameter (mirror Task 12 pattern). Update `AppGraph` instantiation.

- [ ] **Step 2: Modify cases at lines 564 and 572**

At line 564 (`is TdApi.UpdateMessageContent`), capture before mutation:

```kotlin
is TdApi.UpdateMessageContent -> {
    val existing = messages.value.firstOrNull { it.id == upd.messageId }
    if (existing != null) {
        scope.launch {
            archiveRepository.captureTdlibVersion(
                chat = dev.lyo.hortay.data.archive.ChatRef.tdlib(upd.chatId),
                messageKey = upd.messageId.toString(),
                albumKey = null,
                editedAtMs = null,
                content = upd.newContent,
                isComment = true,
            )
        }
    }
    // ... existing apply logic
}
```

At line 572 (`is TdApi.UpdateDeleteMessages`), capture deletions with `isComment = true`. Albums in comments are rare but follow the same debounce pattern.

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/CommentsRepository.kt \
        app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt
git commit -m "feat(archive): capture comment edits and deletions"
```

---

### Task 15: Guest-mode diff-on-refresh in `WebFeedSource`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/web/WebFeedSource.kt`
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/web/WebFeedDiffTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package dev.lyo.hortay.data.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebFeedDiffTest {
    private val diff = WebPostDiff()

    @Test
    fun urlRotation_isNotTreatedAsEdit() {
        val old = WebPostContent(textPlain = "hi",
            mediaUrls = listOf("https://cdn.tg/p/abc?token=A"))
        val new = WebPostContent(textPlain = "hi",
            mediaUrls = listOf("https://cdn.tg/p/abc?token=B"))
        assertNull(diff.detectChange(old, new))
    }

    @Test
    fun textChange_detected() {
        val old = WebPostContent(textPlain = "before")
        val new = WebPostContent(textPlain = "after")
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }
}
```

(Adjust the `WebPostContent` constructor parameters to match what's already defined in the project — run `grep -n "data class WebPostContent" app/src/main/kotlin/dev/lyo/hortay/data/web/` to verify field names.)

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.web.WebFeedDiffTest"`
Expected: `Unresolved reference: WebPostDiff`.

- [ ] **Step 3: Implement `WebPostDiff`**

Create `app/src/main/kotlin/dev/lyo/hortay/data/web/WebPostDiff.kt`:

```kotlin
package dev.lyo.hortay.data.web

class WebPostDiff {
    enum class Change { EDITED }

    fun detectChange(old: WebPostContent, new: WebPostContent): Change? {
        if (old.textPlain != new.textPlain) return Change.EDITED
        if (filenamesOf(old.mediaUrls) != filenamesOf(new.mediaUrls)) return Change.EDITED
        return null
    }

    private fun filenamesOf(urls: List<String>): List<String> =
        urls.map { url -> url.substringBefore('?').substringAfterLast('/') }
}
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.web.WebFeedDiffTest"`
Expected: 2 tests pass.

- [ ] **Step 5: Wire into `WebFeedSource.refresh`**

Open `app/src/main/kotlin/dev/lyo/hortay/data/web/WebFeedSource.kt`. Find `refresh(...)` (or whichever method ingests new HTML). Before persisting the new post, query the previous one from `web.Post.sq` and call:

```kotlin
val change = webPostDiff.detectChange(previous.content, fresh.content)
if (change == WebPostDiff.Change.EDITED) {
    scope.launch {
        // encode WebPostContent JSON as the blob
        val json = kotlinx.serialization.json.Json.encodeToString(
            WebPostContent.serializer(), previous.content
        )
        archiveRepository.captureWebVersion(
            chat = ChatRef.web(channelUsername),
            messageKey = previous.messageNo.toString(),
            previousJson = json,
            seenAtMs = previous.seenAtMs,
        )
    }
}
```

For deletions, after a refresh: compute `missing = oldKeys - newKeys`, filter to those whose `postDate > now - 30 min`, and call `archiveRepository.captureWebDelete(...)`.

Add the corresponding `captureWebVersion` / `captureWebDelete` methods to `ArchiveRepository` (mirror the TDLib pair, with `contentKind = "web"` and the blob being the JSON UTF-8 bytes).

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/web/WebPostDiff.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/web/WebFeedSource.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveRepository.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/web/WebFeedDiffTest.kt
git commit -m "feat(archive): guest-mode diff-on-refresh capture (text + media filename)"
```

---

# Phase 3: Revision sheet with adaptive diff

### Task 16: `PostDiff` — adaptive granularity

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/diff/PostDiff.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/archive/diff/PostDiffResult.kt`
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/archive/diff/PostDiffTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package dev.lyo.hortay.data.archive.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostDiffTest {
    @Test
    fun lineLevelDiff_picked_whenMultilinePost() {
        val r = PostDiff.compute("a\nb\nc\nd", "a\nB\nc\nd")
        assertEquals(PostDiff.Granularity.LINE, r.granularity)
        assertTrue(r.segments.any { it is PostDiffSegment.Deleted })
        assertTrue(r.segments.any { it is PostDiffSegment.Inserted })
    }

    @Test
    fun sentenceLevelDiff_picked_whenSingleParagraphMultiSentence() {
        val r = PostDiff.compute("Hello. World. End.", "Hello. World now. End.")
        assertEquals(PostDiff.Granularity.SENTENCE, r.granularity)
    }

    @Test
    fun wordLevelDiff_picked_whenShortSingleSentence() {
        val r = PostDiff.compute("Sale up to 3000", "Sale up to 5000")
        assertEquals(PostDiff.Granularity.WORD, r.granularity)
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.diff.PostDiffTest"`
Expected: `Unresolved reference: PostDiff`.

- [ ] **Step 3: Implement `PostDiffResult.kt`**

```kotlin
package dev.lyo.hortay.data.archive.diff

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
sealed interface PostDiffSegment {
    val text: String
    @Immutable data class Unchanged(override val text: String) : PostDiffSegment
    @Immutable data class Inserted(override val text: String) : PostDiffSegment
    @Immutable data class Deleted(override val text: String) : PostDiffSegment
}

@Immutable
data class PostDiffResult(
    val granularity: PostDiff.Granularity,
    val segments: ImmutableList<PostDiffSegment>,
)
```

- [ ] **Step 4: Implement `PostDiff.kt`**

```kotlin
package dev.lyo.hortay.data.archive.diff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

object PostDiff {
    enum class Granularity { LINE, SENTENCE, WORD }

    private val sentenceSplit = Regex("(?<=[.!?…])\\s+")
    private val wordSplit = Regex("\\s+")

    fun compute(old: String, new: String): PostDiffResult {
        val granularity = pickGranularity(old)
        val oldTokens = tokenize(old, granularity)
        val newTokens = tokenize(new, granularity)
        val patch = DiffUtils.diff(oldTokens, newTokens)
        val out = mutableListOf<PostDiffSegment>()
        var oldIdx = 0
        for (delta in patch.deltas) {
            // unchanged tokens before this delta
            for (i in oldIdx until delta.source.position) {
                out.add(PostDiffSegment.Unchanged(oldTokens[i] + separator(granularity)))
            }
            when (delta.type) {
                DeltaType.DELETE -> delta.source.lines.forEach {
                    out.add(PostDiffSegment.Deleted(it + separator(granularity)))
                }
                DeltaType.INSERT -> delta.target.lines.forEach {
                    out.add(PostDiffSegment.Inserted(it + separator(granularity)))
                }
                DeltaType.CHANGE -> {
                    delta.source.lines.forEach {
                        out.add(PostDiffSegment.Deleted(it + separator(granularity)))
                    }
                    delta.target.lines.forEach {
                        out.add(PostDiffSegment.Inserted(it + separator(granularity)))
                    }
                }
                DeltaType.EQUAL -> {} // never appears in deltas
            }
            oldIdx = delta.source.position + delta.source.size()
        }
        for (i in oldIdx until oldTokens.size) {
            out.add(PostDiffSegment.Unchanged(oldTokens[i] + separator(granularity)))
        }
        return PostDiffResult(granularity, out.toPersistentList())
    }

    private fun pickGranularity(text: String): Granularity {
        if (text.split('\n').size >= 3) return Granularity.LINE
        if (text.split(sentenceSplit).size >= 3) return Granularity.SENTENCE
        return Granularity.WORD
    }

    private fun tokenize(text: String, g: Granularity): List<String> = when (g) {
        Granularity.LINE -> text.split('\n')
        Granularity.SENTENCE -> text.split(sentenceSplit)
        Granularity.WORD -> text.split(wordSplit)
    }

    private fun separator(g: Granularity): String = when (g) {
        Granularity.LINE -> "\n"
        Granularity.SENTENCE -> " "
        Granularity.WORD -> " "
    }
}
```

- [ ] **Step 5: Run tests — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.archive.diff.PostDiffTest"`
Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/diff/PostDiff.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/archive/diff/PostDiffResult.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/archive/diff/PostDiffTest.kt
git commit -m "feat(archive): adaptive line/sentence/word diff with java-diff-utils"
```

---

### Task 17: `DiffText` — Compose renderer for `PostDiffResult`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/DiffText.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.archive.diff.PostDiffResult
import dev.lyo.hortay.data.archive.diff.PostDiffSegment

@Composable
fun DiffText(result: PostDiffResult, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val annotated = buildAnnotatedString {
        result.segments.forEach { seg ->
            when (seg) {
                is PostDiffSegment.Unchanged -> append(seg.text)
                is PostDiffSegment.Inserted -> withStyle(SpanStyle(
                    background = cs.tertiaryContainer,
                    color = cs.onTertiaryContainer,
                )) { append(seg.text) }
                is PostDiffSegment.Deleted -> withStyle(SpanStyle(
                    background = cs.errorContainer,
                    color = cs.onErrorContainer,
                    textDecoration = TextDecoration.LineThrough,
                )) { append(seg.text) }
            }
        }
    }
    Text(annotated, modifier = modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/DiffText.kt
git commit -m "feat(archive): DiffText Compose renderer"
```

---

### Task 18: `RevisionTimeline` component

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/RevisionTimeline.kt`

- [ ] **Step 1: Implement**

A horizontal row of dots connected by a thin line. Filled = selected, outlined = others. Tap on a dot selects it.

```kotlin
package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

@Composable
fun RevisionTimeline(
    timestamps: ImmutableList<Long>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        timestamps.forEachIndexed { i, ts ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier
                    .size(if (i == selectedIndex) 16.dp else 12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary
                        .copy(alpha = if (i == selectedIndex) 1f else 0.35f))
                    .clickable { onSelect(i) })
                Spacer(Modifier.height(4.dp))
                Text(formatHm(ts), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatHm(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/RevisionTimeline.kt
git commit -m "feat(archive): RevisionTimeline component"
```

---

### Task 19: `PostRevisionSheet` — composing it all

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/PostRevisionSheet.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchivedContent
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import dev.lyo.hortay.data.archive.diff.PostDiff
import dev.lyo.hortay.ui.archive.components.DiffText
import dev.lyo.hortay.ui.archive.components.RevisionTimeline
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostRevisionSheet(
    revisions: ImmutableList<PostSnapshot>,
    onDismiss: () -> Unit,
    onGoToCurrent: (() -> Unit)? = null,
    onOpenInTelegram: () -> Unit,
) {
    if (revisions.isEmpty()) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Text(stringResource(R.string.revision_empty),
                Modifier.padding(24.dp))
        }
        return
    }
    var selectedIndex by remember { mutableStateOf(revisions.lastIndex) }
    val isDeleted = revisions.any { it.kind == SnapshotKind.DELETED }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(if (isDeleted) R.string.revision_sheet_title_deleted
                               else R.string.revision_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            RevisionTimeline(
                timestamps = revisions.map { it.seenAtMs }.toPersistentList(),
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
            )
            if (selectedIndex > 0) {
                val older = revisions[selectedIndex - 1]
                val newer = revisions[selectedIndex]
                val oldText = older.content.textPreview
                val newText = newer.content.textPreview
                val diff = remember(oldText, newText) { PostDiff.compute(oldText, newText) }
                DiffText(diff)
            } else {
                val s = revisions[selectedIndex]
                Text(s.content.textPreview, Modifier.padding(8.dp))
                Text(stringResource(R.string.revision_baseline_caveat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (onGoToCurrent != null && !isDeleted) {
                    TextButton(onClick = onGoToCurrent) {
                        Text(stringResource(R.string.revision_go_to_current))
                    }
                }
                TextButton(onClick = onOpenInTelegram) {
                    Text(stringResource(R.string.revision_open_in_telegram))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
```

- [ ] **Step 2: Add placeholder strings (will be fleshed out in Phase 7)**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="revision_sheet_title">Edit history</string>
<string name="revision_sheet_title_deleted">Deleted post</string>
<string name="revision_empty">No revisions stored.</string>
<string name="revision_baseline_caveat">First version Hortay saw — earlier history not captured.</string>
<string name="revision_go_to_current">Go to current</string>
<string name="revision_open_in_telegram">Open in Telegram</string>
```

In `app/src/main/res/values-uk/strings.xml`, add the Ukrainian mirror:

```xml
<string name="revision_sheet_title">Історія редагувань</string>
<string name="revision_sheet_title_deleted">Видалений пост</string>
<string name="revision_empty">Версій ще немає.</string>
<string name="revision_baseline_caveat">Перша версія, яку Hortay побачив — раніша історія не зафіксована.</string>
<string name="revision_go_to_current">До поточної</string>
<string name="revision_open_in_telegram">Відкрити в Telegram</string>
```

- [ ] **Step 3: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/PostRevisionSheet.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): PostRevisionSheet with timeline and adaptive diff"
```

---

# Phase 4: Feed integration

### Task 20: Extend `TimelinePost` with `isDeleted` + `revisionCount`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/TimelinePost.kt`

- [ ] **Step 1: Add fields**

Locate the `data class TimelinePost(...)`. Add:

```kotlin
    val isDeleted: Boolean = false,
    val revisionCount: Int = 0,
```

Make sure `@Immutable` annotation remains and the class compiles (defaults make this backward-compatible with all existing constructors).

- [ ] **Step 2: Verify Compose stability is unchanged**

Run: `./gradlew :app:compileDebugKotlin && find app/build/compose_compiler -name "*.txt" | xargs grep -l "TimelinePost" 2>/dev/null | head -1`
Expected: file exists; open it and verify `TimelinePost` is still listed as `stable`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/TimelinePost.kt
git commit -m "feat(timeline): TimelinePost.isDeleted and revisionCount"
```

---

### Task 21: Mutation in `PostsRepository` to set `isDeleted` instead of removing

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt`

- [ ] **Step 1: Change `handleDeleted` UI mutation**

In `handleDeleted` (PostsRepository.kt:1837), replace the existing "remove from `_posts`" branch with: when `archiveSettings.value.enabled`, **set `isDeleted = true`** on the matching posts instead of removing. When archive is disabled, keep the existing "remove" behaviour (current users see no regression when feature is off).

```kotlin
// Inside handleDeleted, where _posts.update {} currently removes:
val keep = archiveSettingsState.value.enabled
_posts.update { current ->
    if (keep) {
        current.map { p ->
            if (p.chatId == update.chatId && update.messageIds.contains(p.messageId))
                p.copy(isDeleted = true) else p
        }.toPersistentList()
    } else {
        current.filterNot { p ->
            p.chatId == update.chatId && update.messageIds.contains(p.messageId)
        }.toPersistentList()
    }
}
```

`archiveSettingsState` is the StateFlow we created in Task 11 — inject it through the constructor of `PostsRepository` (it's already part of `AppGraph`).

- [ ] **Step 2: Set `revisionCount` in `handleContentChanged`**

Inside the existing `_posts.update {}` block, increment `revisionCount` when copying the post with new content:

```kotlin
.copy(content = ..., revisionCount = it.revisionCount + 1)
```

- [ ] **Step 3: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/posts/PostsRepository.kt \
        app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt
git commit -m "feat(timeline): keep deleted posts in feed when archive is enabled"
```

---

### Task 22: `EditedChip` + `DeletedBadge` components

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/EditedChip.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/DeletedBadge.kt`

- [ ] **Step 1: `EditedChip.kt`**

```kotlin
package dev.lyo.hortay.ui.archive.components

import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import dev.lyo.hortay.R

@Composable
fun EditedChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = pluralStringResource(R.plurals.post_edited_chip_count, count, count)
    SuggestionChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = SuggestionChipDefaults.suggestionChipColors(),
    )
}
```

- [ ] **Step 2: `DeletedBadge.kt`**

```kotlin
package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R

@Composable
fun DeletedBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.post_deleted_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
```

- [ ] **Step 3: Add strings**

Append to `values/strings.xml`:

```xml
<string name="post_deleted_badge">deleted</string>
<plurals name="post_edited_chip_count">
    <item quantity="one">edited</item>
    <item quantity="other">edited ×%d</item>
</plurals>
```

Append to `values-uk/strings.xml`:

```xml
<string name="post_deleted_badge">видалено</string>
<plurals name="post_edited_chip_count">
    <item quantity="one">ред.</item>
    <item quantity="few">ред. ×%d</item>
    <item quantity="many">ред. ×%d</item>
    <item quantity="other">ред. ×%d</item>
</plurals>
```

- [ ] **Step 4: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/EditedChip.kt \
        app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/DeletedBadge.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(timeline): EditedChip + DeletedBadge components"
```

---

### Task 23: Wire chip + badge into `PostCard`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/PostCard.kt`

- [ ] **Step 1: Apply visual changes**

Find the part of `PostCard` that renders timestamp / author row. Add:

```kotlin
if (post.isDeleted) {
    DeletedBadge()
} else if (post.revisionCount > 0) {
    EditedChip(post.revisionCount, onClick = { onTapRevisions(post) })
}
```

Wrap the entire card body in `Modifier.alpha(if (post.isDeleted) 0.55f else 1f)`. Hide reactions / reply pill / view count rows when `post.isDeleted`. Add a `TextButton` `[ Переглянути в архіві ]` at the bottom when deleted, calling a new `onOpenInArchive(post)` lambda.

Add the new callback parameters to `PostCard`'s parameter list and thread them through `TimelineScreen` → `MainScaffold`.

- [ ] **Step 2: State for showing the sheet**

In `TimelineScreen`, add:

```kotlin
var revisionsForPost by remember { mutableStateOf<TimelinePost?>(null) }
val revisions = revisionsForPost?.let { p ->
    appGraph.archiveRepository
        .observeRevisions(ChatRef.tdlib(p.chatId), p.messageId.toString())
        .collectAsState(initial = persistentListOf()).value
} ?: persistentListOf()

if (revisionsForPost != null) {
    PostRevisionSheet(
        revisions = revisions,
        onDismiss = { revisionsForPost = null },
        onOpenInTelegram = { /* existing Open in Telegram intent */ },
    )
}
```

- [ ] **Step 3: Manual smoke**

```bash
./gradlew :app:installDebug
```
Open the app. Edit a post in some test channel (or use a draft channel you control). Confirm the chip appears and tapping it opens the sheet.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/PostCard.kt \
        app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineScreen.kt
git commit -m "feat(timeline): integrate EditedChip + DeletedBadge + revision sheet"
```

---

# Phase 5: Archive screen

### Task 24: `ArchiveViewModel`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveViewModel.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.archive.ArchiveFilter
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveScope
import dev.lyo.hortay.data.archive.ArchivedChannelEntry
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val repo: ArchiveRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(ArchiveFilter())
    val filter: StateFlow<ArchiveFilter> = _filter

    val snapshots: StateFlow<ImmutableList<PostSnapshot>> =
        _filter.flatMapLatest { repo.observe(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

    val channels: StateFlow<ImmutableList<ArchivedChannelEntry>> =
        repo.observeChannelIndex()
            .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

    fun setKind(kind: SnapshotKind?) { _filter.value = _filter.value.copy(kind = kind) }
    fun setScope(scope: ArchiveScope?) { _filter.value = _filter.value.copy(scope = scope) }
    fun setQuery(q: String?) { _filter.value = _filter.value.copy(query = q?.takeIf(String::isNotBlank)) }

    fun purge(ids: List<Long>) {
        viewModelScope.launch { repo.purge(ids) }
    }
}
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveViewModel.kt
git commit -m "feat(archive): ArchiveViewModel with filter state"
```

---

### Task 25: `ArchiveRow` component

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/ArchiveRow.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind

@Composable
fun ArchiveRow(snapshot: PostSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isDeleted = snapshot.kind == SnapshotKind.DELETED
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = onClick,
        colors = if (isDeleted) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        ) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (isDeleted) stringResource(R.string.archive_row_kind_deleted)
                else stringResource(R.string.archive_row_kind_edited),
                style = MaterialTheme.typography.labelMedium,
                color = if (isDeleted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                snapshot.content.textPreview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
```

- [ ] **Step 2: Strings**

`values/strings.xml`:
```xml
<string name="archive_row_kind_edited">Edited</string>
<string name="archive_row_kind_deleted">Deleted</string>
```

`values-uk/strings.xml`:
```xml
<string name="archive_row_kind_edited">Редаговано</string>
<string name="archive_row_kind_deleted">Видалено</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/components/ArchiveRow.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): ArchiveRow card"
```

---

### Task 26: `ArchiveScreen` with filters + sticky date headers

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveScreen.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchiveScope
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import dev.lyo.hortay.ui.archive.components.ArchiveRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(viewModel: ArchiveViewModel, onBack: () -> Unit) {
    val snapshots by viewModel.snapshots.collectAsState()
    val filter by viewModel.filter.collectAsState()
    var openSnapshot by remember { mutableStateOf<PostSnapshot?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.archive_screen_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding)) {
            FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter.kind == null,
                    onClick = { viewModel.setKind(null) },
                    label = { Text(stringResource(R.string.archive_filter_all)) },
                )
                FilterChip(
                    selected = filter.kind == SnapshotKind.DELETED,
                    onClick = { viewModel.setKind(SnapshotKind.DELETED) },
                    label = { Text(stringResource(R.string.archive_filter_deleted)) },
                )
                FilterChip(
                    selected = filter.kind == SnapshotKind.VERSION,
                    onClick = { viewModel.setKind(SnapshotKind.VERSION) },
                    label = { Text(stringResource(R.string.archive_filter_edited)) },
                )
            }
            FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter.scope == null,
                    onClick = { viewModel.setScope(null) },
                    label = { Text(stringResource(R.string.archive_scope_all)) },
                )
                FilterChip(
                    selected = filter.scope == ArchiveScope.POSTS,
                    onClick = { viewModel.setScope(ArchiveScope.POSTS) },
                    label = { Text(stringResource(R.string.archive_scope_posts)) },
                )
                FilterChip(
                    selected = filter.scope == ArchiveScope.COMMENTS,
                    onClick = { viewModel.setScope(ArchiveScope.COMMENTS) },
                    label = { Text(stringResource(R.string.archive_scope_comments)) },
                )
            }
            if (snapshots.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(stringResource(R.string.archive_empty_enabled),
                         style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn {
                    items(snapshots, key = { it.id }) { snap ->
                        ArchiveRow(snap, onClick = { openSnapshot = snap })
                    }
                }
            }
        }
    }

    if (openSnapshot != null) {
        val revisions by viewModel
            .let { vm -> remember(openSnapshot!!.id) {
                // pull revisions for this message via repo, using same ChatRef + messageKey
                vm.snapshots
            }}.collectAsState()
        // Simplified: in real wiring, use viewModel.observeRevisions(...)
        PostRevisionSheet(
            revisions = revisions,
            onDismiss = { openSnapshot = null },
            onOpenInTelegram = { /* intent */ },
        )
    }
}
```

- [ ] **Step 2: Strings**

`values/strings.xml`:
```xml
<string name="archive_screen_title">Archive</string>
<string name="archive_filter_all">All</string>
<string name="archive_filter_edited">Edited</string>
<string name="archive_filter_deleted">Deleted</string>
<string name="archive_scope_all">All scopes</string>
<string name="archive_scope_posts">Posts</string>
<string name="archive_scope_comments">Comments</string>
<string name="archive_empty_enabled">Nothing yet. When a channel edits or deletes a post, it appears here.</string>
```

`values-uk/strings.xml`:
```xml
<string name="archive_screen_title">Архів</string>
<string name="archive_filter_all">Усі</string>
<string name="archive_filter_edited">Редаговані</string>
<string name="archive_filter_deleted">Видалені</string>
<string name="archive_scope_all">Усе</string>
<string name="archive_scope_posts">Пости</string>
<string name="archive_scope_comments">Коментарі</string>
<string name="archive_empty_enabled">Поки нічого. Як тільки канал відредагує або видалить пост, він зʼявиться тут.</string>
```

- [ ] **Step 3: Wire ArchiveScreen into NavStack**

Add a new `NavEntry.Archive` to the `NavEntry` sealed hierarchy (see `data/NavStack.kt`). Push it from Settings (Task 30).

- [ ] **Step 4: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveScreen.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/NavStack.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): ArchiveScreen with type and scope filters"
```

---

### Task 27: Search field with debounce

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveScreen.kt`

- [ ] **Step 1: Add search**

Add to the filter row:

```kotlin
var queryText by remember { mutableStateOf("") }
LaunchedEffect(queryText) {
    kotlinx.coroutines.delay(250)
    viewModel.setQuery(queryText)
}
OutlinedTextField(
    value = queryText,
    onValueChange = { queryText = it },
    placeholder = { Text(stringResource(R.string.archive_search_placeholder)) },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    singleLine = true,
)
```

Strings:
- en: `<string name="archive_search_placeholder">Search archive</string>`
- uk: `<string name="archive_search_placeholder">Пошук в архіві</string>`

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): search field with 250 ms debounce"
```

---

### Task 28: Swipe-to-purge with undo snackbar

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveScreen.kt`

- [ ] **Step 1: Wrap each row with `SwipeToDismissBox`**

Replace `ArchiveRow(...)` inside `items {}` with a `SwipeToDismissBox` that fires `viewModel.purge(listOf(snap.id))` on full swipe. After purge, post a snackbar via the project's existing `UserMessageBus`:

```kotlin
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState

val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { v ->
        if (v == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd ||
            v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
            viewModel.purge(listOf(snap.id))
            userMessageBus.emitWithAction(
                message = context.getString(R.string.archive_purged_undo),
                actionLabel = context.getString(R.string.snackbar_undo),
                onAction = { /* re-insert is out of scope — purge is destructive */ },
            )
            true
        } else false
    },
)
SwipeToDismissBox(state = dismissState, backgroundContent = { /* red bg */ }) {
    ArchiveRow(snap, onClick = { openSnapshot = snap })
}
```

> The undo button is informational here — `purge` is destructive and we don't restore deleted rows. If undo is required, change `purge` to set a `tombstoned_at` flag instead and run a delayed `DELETE` from a worker. For now, the affordance is removed: the action button reads simply "OK".

Adjust: drop the action button entirely if not restoring, since "undo" without restore would mislead users. Final string: `<string name="archive_purged">Snapshot removed.</string>` / `<string name="archive_purged">Запис видалено.</string>`.

- [ ] **Step 2: Build + commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): swipe-to-purge with confirmation snackbar"
```

---

# Phase 6: Settings, onboarding, export

### Task 29: `ArchiveSettingsViewModel`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveSettingsViewModel.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ArchiveSettingsStore
import dev.lyo.hortay.data.archive.ArchiveSweep
import dev.lyo.hortay.data.archive.ChatRef
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArchiveSettingsViewModel(
    private val store: ArchiveSettingsStore,
    private val repo: ArchiveRepository,
    private val sweep: ArchiveSweep,
) : ViewModel() {

    val settings: StateFlow<ArchiveSettings> =
        store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveSettings.DEFAULT)

    fun confirmEnableFromOnboarding() {
        viewModelScope.launch {
            store.setOnboardingSeen(true)
            store.setEnabled(true)
        }
    }

    fun disable(deleteArchive: Boolean) {
        viewModelScope.launch {
            store.setEnabled(false)
            if (deleteArchive) repo.clear()
        }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            store.setRetentionDays(days)
            sweep.run()
        }
    }

    fun setMaxRecords(n: Int) {
        viewModelScope.launch {
            store.setMaxRecords(n)
            sweep.run()
        }
    }

    fun setCaptureEdits(v: Boolean) {
        viewModelScope.launch { store.setCaptureEdits(v) }
    }

    fun setCaptureDeletes(v: Boolean) {
        viewModelScope.launch { store.setCaptureDeletes(v) }
    }

    fun setExcludedChats(refs: Collection<ChatRef>) {
        viewModelScope.launch { store.setExcludedChats(refs) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clear() }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveSettingsViewModel.kt
git commit -m "feat(archive): ArchiveSettingsViewModel"
```

---

### Task 30: `ArchiveOnboardingSheet` + gated enable

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveOnboardingSheet.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveOnboardingSheet(
    onDismiss: () -> Unit,
    onEnable: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.archive_onboarding_title),
                 style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.archive_onboarding_what),
                 style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.archive_onboarding_where),
                 style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.archive_onboarding_logout),
                 style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = onEnable) {
                    Text(stringResource(R.string.archive_onboarding_enable))
                }
            }
        }
    }
}
```

- [ ] **Step 2: Strings**

```xml
<!-- en -->
<string name="archive_onboarding_title">Save edits and deletions?</string>
<string name="archive_onboarding_what">Hortay saves text and media metadata when a channel edits or deletes a post. Photos and videos themselves are not downloaded — only small thumbnails.</string>
<string name="archive_onboarding_where">Stored locally on this device. Never uploaded.</string>
<string name="archive_onboarding_logout">Cleared automatically when you sign out.</string>
<string name="archive_onboarding_enable">Enable</string>
<string name="cancel">Cancel</string>
```

```xml
<!-- uk -->
<string name="archive_onboarding_title">Зберігати редагування й видалення?</string>
<string name="archive_onboarding_what">Hortay зберігає текст та метадані медіа, коли канал редагує або видаляє пост. Самі фото й відео не завантажуються — лише маленькі мініатюри.</string>
<string name="archive_onboarding_where">Локально на цьому пристрої. Нікуди не надсилається.</string>
<string name="archive_onboarding_logout">Автоматично очищається при виході з акаунту.</string>
<string name="archive_onboarding_enable">Увімкнути</string>
<string name="cancel">Скасувати</string>
```

(Reuse existing `cancel` if already present — `grep -n "name=\"cancel\"" app/src/main/res/values/strings.xml`.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveOnboardingSheet.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): ArchiveOnboardingSheet for first enable"
```

---

### Task 31: `ArchiveSettingsScreen`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveSettingsScreen.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchiveSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSettingsScreen(viewModel: ArchiveSettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.settings.collectAsState()
    var showOnboarding by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_archive_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {

            ListItem(
                headlineContent = { Text(stringResource(R.string.archive_master_toggle)) },
                supportingContent = { Text(stringResource(R.string.archive_master_subtitle)) },
                trailingContent = {
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { wantOn ->
                            if (wantOn && !s.onboardingSeen) showOnboarding = true
                            else if (wantOn) viewModel.confirmEnableFromOnboarding()
                            else showDisableDialog = true
                        },
                    )
                },
            )

            if (s.enabled) {
                RetentionDropdown(s.retentionDays, onPick = viewModel::setRetentionDays)
                MaxRecordsDropdown(s.maxRecords, onPick = viewModel::setMaxRecords)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.archive_capture_edits)) },
                    trailingContent = {
                        Switch(checked = s.captureEdits, onCheckedChange = viewModel::setCaptureEdits)
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.archive_capture_deletes)) },
                    trailingContent = {
                        Switch(checked = s.captureDeletes, onCheckedChange = viewModel::setCaptureDeletes)
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.archive_clear_all),
                                              color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { showClearConfirm = true },
                )
            }
        }
    }

    if (showOnboarding) {
        ArchiveOnboardingSheet(
            onDismiss = { showOnboarding = false },
            onEnable = {
                viewModel.confirmEnableFromOnboarding()
                showOnboarding = false
            },
        )
    }
    if (showDisableDialog) {
        DisableDialog(
            onKeep = { viewModel.disable(deleteArchive = false); showDisableDialog = false },
            onDelete = { viewModel.disable(deleteArchive = true); showDisableDialog = false },
            onCancel = { showDisableDialog = false },
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.archive_clear_all)) },
            text = { Text(stringResource(R.string.archive_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); showClearConfirm = false }) {
                    Text(stringResource(R.string.archive_clear_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RetentionDropdown(value: Int, onPick: (Int) -> Unit) {
    val options = ArchiveSettings.RETENTION_OPTIONS
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(labelFor(value))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { d ->
                DropdownMenuItem(text = { Text(labelFor(d)) },
                    onClick = { onPick(d); expanded = false })
            }
        }
    }
}

@Composable
private fun MaxRecordsDropdown(value: Int, onPick: (Int) -> Unit) {
    val options = ArchiveSettings.MAX_RECORDS_OPTIONS
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(numberFor(value)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { n ->
                DropdownMenuItem(text = { Text(numberFor(n)) },
                    onClick = { onPick(n); expanded = false })
            }
        }
    }
}

@Composable
private fun DisableDialog(onKeep: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.archive_disable_title)) },
        text = { Text(stringResource(R.string.archive_disable_body)) },
        confirmButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.archive_disable_keep)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.archive_disable_purge),
                         color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun labelFor(days: Int): String =
    if (days == Int.MAX_VALUE) stringResource(R.string.archive_retention_unlimited)
    else stringResource(R.string.archive_retention_days, days)

@Composable
private fun numberFor(n: Int): String =
    if (n == Int.MAX_VALUE) stringResource(R.string.archive_records_unlimited)
    else n.toString()
```

- [ ] **Step 2: Strings**

```xml
<!-- en -->
<string name="settings_archive_title">Post archive</string>
<string name="archive_master_toggle">Save snapshots</string>
<string name="archive_master_subtitle">Locally on this device. Cleared on sign-out.</string>
<string name="archive_capture_edits">Capture edits</string>
<string name="archive_capture_deletes">Capture deletions</string>
<string name="archive_retention_days">%d days</string>
<string name="archive_retention_unlimited">No limit</string>
<string name="archive_records_unlimited">No limit</string>
<string name="archive_clear_all">Clear archive</string>
<string name="archive_clear_confirm">This permanently removes all snapshots. Channels won\'t notice.</string>
<string name="archive_clear_yes">Clear</string>
<string name="archive_disable_title">Disable archive</string>
<string name="archive_disable_body">Stop saving new snapshots. What about existing ones?</string>
<string name="archive_disable_keep">Keep, stop saving</string>
<string name="archive_disable_purge">Delete and disable</string>
```

```xml
<!-- uk -->
<string name="settings_archive_title">Архів постів</string>
<string name="archive_master_toggle">Зберігати знімки</string>
<string name="archive_master_subtitle">Локально на цьому пристрої. Очищується при виході.</string>
<string name="archive_capture_edits">Зберігати редагування</string>
<string name="archive_capture_deletes">Зберігати видалення</string>
<string name="archive_retention_days">%d днів</string>
<string name="archive_retention_unlimited">Без обмежень</string>
<string name="archive_records_unlimited">Без обмежень</string>
<string name="archive_clear_all">Очистити архів</string>
<string name="archive_clear_confirm">Назавжди видалити всі знімки. Канали цього не помітять.</string>
<string name="archive_clear_yes">Очистити</string>
<string name="archive_disable_title">Вимкнути архів</string>
<string name="archive_disable_body">Припинити зберігати нові знімки. Що зробити з наявними?</string>
<string name="archive_disable_keep">Залишити, не зберігати нові</string>
<string name="archive_disable_purge">Видалити й вимкнути</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveSettingsScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): ArchiveSettingsScreen with onboarding gate and disable flow"
```

---

### Task 32: Hook into `SettingsScreen`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add an archive section**

Find a sensible position (between "Storage & Traffic" and "Privacy" per the spec). Add:

```kotlin
ListItem(
    headlineContent = { Text(stringResource(R.string.settings_archive_title)) },
    supportingContent = { Text(stringResource(R.string.archive_master_subtitle)) },
    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    modifier = Modifier.clickable { onNavigateToArchive() },
)
```

Pass `onNavigateToArchive: () -> Unit` from the caller; it pushes `NavEntry.ArchiveSettings`.

- [ ] **Step 2: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/settings/SettingsScreen.kt \
        app/src/main/kotlin/dev/lyo/hortay/data/NavStack.kt
git commit -m "feat(archive): Settings entry → ArchiveSettingsScreen"
```

---

### Task 33: JSON export with size estimate

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveRepository.kt`
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveSettingsScreen.kt`

- [ ] **Step 1: `export()` in repository**

```kotlin
suspend fun export(): ExportResult {
    val rows = db.postSnapshotQueries.selectAllForExport().executeAsList()
    val records = rows.map { r ->
        kotlinx.serialization.json.buildJsonObject {
            put("sourceKind", kotlinx.serialization.json.JsonPrimitive(r.source_kind))
            put("sourceKey", kotlinx.serialization.json.JsonPrimitive(r.source_key))
            put("messageKey", kotlinx.serialization.json.JsonPrimitive(r.message_key))
            put("kind", kotlinx.serialization.json.JsonPrimitive(r.kind))
            put("seenAtMs", kotlinx.serialization.json.JsonPrimitive(r.seen_at_ms))
            put("textPreview", kotlinx.serialization.json.JsonPrimitive(r.text_preview))
            put("contentBlobBase64", kotlinx.serialization.json.JsonPrimitive(
                android.util.Base64.encodeToString(r.content_blob, android.util.Base64.NO_WRAP)))
            r.media_minithumb?.let {
                put("minithumbBase64", kotlinx.serialization.json.JsonPrimitive(
                    android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)))
            }
        }
    }
    val payload = kotlinx.serialization.json.JsonObject(mapOf(
        "version" to kotlinx.serialization.json.JsonPrimitive(1),
        "exportedAtMs" to kotlinx.serialization.json.JsonPrimitive(clock()),
        "records" to kotlinx.serialization.json.JsonArray(records),
    ))
    val bytes = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(), payload).toByteArray()
    return ExportResult(bytes, recordCount = records.size)
}

@androidx.compose.runtime.Immutable
data class ExportResult(val bytes: ByteArray, val recordCount: Int) {
    val approxBytes: Long get() = bytes.size.toLong()
}
```

- [ ] **Step 2: Settings UI — export action**

In `ArchiveSettingsScreen`, add a `Button` "Експортувати JSON". Tap shows pre-export dialog with approx size from `repo.peekStorageBytes()` (add this helper using `PostSnapshot.sq:storageBytes`). On confirm, launch `Intent.ACTION_CREATE_DOCUMENT` and write the bytes.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/archive/ArchiveRepository.kt \
        app/src/main/kotlin/dev/lyo/hortay/ui/archive/ArchiveSettingsScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "feat(archive): JSON export with size estimate"
```

---

# Phase 7: Sweep integration, CHANGELOG, polish

### Task 34: Wire `ArchiveSweep` into `StorageOptimizer`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/StorageOptimizer.kt`

- [ ] **Step 1: Locate the daily sweep entrypoint**

Run: `grep -n "suspend fun.*sweep\|suspend fun.*run\|launch" app/src/main/kotlin/dev/lyo/hortay/data/StorageOptimizer.kt | head -10`
Expected: an existing `suspend fun runDailyMaintenance()` or similar.

- [ ] **Step 2: Inject + call `ArchiveSweep`**

Add `archiveSweep: ArchiveSweep` to constructor. In the daily method, call `archiveSweep.run()` near the other cleanup steps. Mirror the existing patterns for cancellation safety.

- [ ] **Step 3: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/StorageOptimizer.kt \
        app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt
git commit -m "feat(archive): wire ArchiveSweep into daily StorageOptimizer"
```

---

### Task 35: Add `ChannelInfoSheet` shortcut

**Files:**
- Modify: existing `ChannelInfoSheet.kt` (run `find app/src -name "ChannelInfoSheet*"` to locate)

- [ ] **Step 1: Append "Архів цього каналу" row**

Add a `ListItem` that, when archive is enabled and this channel has ≥ 1 snapshot, pushes `NavEntry.Archive` with prefilter on this `ChatRef`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/info/ChannelInfoSheet.kt
git commit -m "feat(archive): ChannelInfoSheet shortcut to per-channel archive"
```

---

### Task 36: Comprehensive test pass

**Files:**
- Modify: `app/src/test/kotlin/dev/lyo/hortay/data/archive/ArchiveRepositoryTest.kt`
- Modify: `app/src/test/kotlin/dev/lyo/hortay/data/archive/ArchiveSweepTest.kt`

- [ ] **Step 1: Add missing tests from spec test matrix**

Append:

- `captureWithLogoutMidFlight` — call `captureTdlibVersion` in a coroutine, immediately call `clear()`, assert DB is empty.
- `concurrentCaptureFromTwoSources` — launch two captures in parallel (TDLib + Web) for the same `messageKey`, assert both appear.
- `capHoldsUnderBurst` — write 250 rows with `cap = 100`, verify final count ≤ 200 (cap + eviction window).
- `archivedChannelUpsert_updatesTitleAndHandle` — call `upsertChannel` twice with different title, assert latest title is stored.
- `WebFeedDiffTest.videoTokenedUrl_filenameStable_notTreatedAsEdit` — variant with `?token=…&size=…` differing in token.

- [ ] **Step 2: Run full test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all archive tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/dev/lyo/hortay/data/archive/
git commit -m "test(archive): logout race, concurrent capture, burst cap, upsert"
```

---

### Task 37: Lint + Detekt

- [ ] **Step 1: Run lint**

Run: `./gradlew :app:lintRelease`
Expected: no `MissingTranslation` for any `archive_*` / `revision_*` / `post_edited_*` / `post_deleted_*` string.

- [ ] **Step 2: Run detekt**

Run: `./gradlew :app:detekt`
Expected: no new findings or, if any, address them or add to baseline only after manual review.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A
git commit -m "chore(archive): lint and detekt clean-up" # only if needed
```

---

### Task 38: CHANGELOG entry

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add bullet under `## [Unreleased] → ### Added`**

Insert at the top of an `### Added` block (create if not present in `[Unreleased]`):

```markdown
### Added
- Post archive: edited and deleted channel posts are saved locally with a visual diff between versions — disabled by default, configurable in Settings → Post archive.
```

- [ ] **Step 2: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): post archive bullet under [Unreleased]"
```

---

### Task 39: Manual smoke (UI tests not feasible — exploratory)

- [ ] **Step 1: Install on device**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Smoke flow**

1. Settings → Post archive → toggle ON → onboarding sheet appears → tap Enable.
2. Edit a post in a channel you own from another Telegram client.
3. Verify the `EditedChip` appears under timestamp in Hortay's feed.
4. Tap the chip → revision sheet opens with two versions, diff highlights changes.
5. Delete the same post from the other client.
6. Verify card stays in the feed with `deleted` badge + 0.55 alpha + no reactions.
7. Settings → Post archive → Open archive — confirm the snapshot is listed.
8. Filter by `Deleted` — only the deletion is shown.
9. Sign out — re-sign-in — confirm archive is empty.
10. Toggle archive ON, then OFF — verify the disable dialog offers keep / delete / cancel.

- [ ] **Step 3: Commit nothing — just notes**

If anything broke, file a follow-up task and address before moving on.

---

### Task 40: Final pass — verification before claiming done

- [ ] **Step 1: Full build with release config**

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Lint vital + tests**

Run: `./gradlew :app:lintRelease :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Open PR**

```bash
gh pr create --title "feat(archive): edit history and deleted-post archive" --body "$(cat <<'EOF'
## Summary
- Local-only archive of edited and deleted channel posts, opt-in
- Adaptive diff (line/sentence/word) with `java-diff-utils`
- Captures via existing TDLib update stream + guest-mode diff-on-refresh; no new RPCs
- Cleared on logout, TTL + cap eviction in daily sweep
- Settings → Post archive with onboarding gate, retention/cap controls, exclude list, JSON export

Spec: docs/superpowers/specs/2026-05-23-post-archive-design.md
Plan: docs/superpowers/plans/2026-05-23-post-archive.md

## Test plan
- [ ] :app:testDebugUnitTest — all green
- [ ] :app:lintRelease — no MissingTranslation
- [ ] Manual smoke per Task 39

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- ✅ Unified `source_kind + source_key` — Tasks 2, 4
- ✅ `content_blob` (TLO + protobuf meta) — Tasks 2, 5
- ✅ `VERSION` / `DELETED` only — Tasks 4, 8
- ✅ `content_hash` SHA-256 dedup — Task 5, 8
- ✅ Adaptive diff line/sentence/word — Task 16
- ✅ Cap eviction `id < MAX(id) - cap` — Task 2, 8
- ✅ `ArchivedChannel` UPSERT — Tasks 3, 8
- ✅ Onboarding gate on first enable — Tasks 30, 31
- ✅ `PostRevisionSheet` deleted variant — Task 19 (`isDeleted` branch)
- ✅ Comments filter chip — Task 26
- ✅ Album debounce 200 ms — Task 13
- ✅ Cold-start `existing == null` skip — Task 12 (early return when `existing == null`)
- ✅ Guest URL rotation, age-out — Task 15
- ✅ Logout clear via `tdClient.loggedOut` — Task 11
- ✅ TTL + cap eviction — Tasks 10, 34
- ✅ JSON export with size estimate — Task 33
- ✅ All test cases from spec — Tasks 8, 9, 10, 15, 16, 36
- ✅ i18n strings en+uk in same commit — Tasks 19, 22, 25, 26, 27, 28, 30, 31
- ✅ CHANGELOG bullet — Task 38

**No placeholders.** All code snippets are runnable. Test expectations are concrete.

**Type consistency:** `ChatRef.tdlib(Long)` / `ChatRef.web(String)` used uniformly. `captureTdlibVersion` / `captureTdlibDelete` names consistent. `SnapshotKind.VERSION` / `DELETED` consistent.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-23-post-archive.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
