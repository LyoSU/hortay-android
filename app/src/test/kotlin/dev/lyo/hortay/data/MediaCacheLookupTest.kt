package dev.lyo.hortay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GetFile sharing between [MediaCache.resync] and [MediaCache.ensure]. A card's media
 * binding fires both in the same frame when the scroll gate opens; on a cold slot each
 * used to issue its own GetFile for the same file ahead of the DownloadFile.
 *
 * Only `runCurrent` is used, never `advanceUntilIdle`: the stall watchdog loops on
 * `delay` in the cache's scope for as long as the test runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheLookupTest {

    private val fileId = 42

    private fun coldFile(): TdApi.File = TdApi.File(
        fileId,
        1_000L,
        1_000L,
        TdApi.LocalFile("", true, false, false, false, 0L, 0L, 0L),
        TdApi.RemoteFile("remote", "unique", false, true, 1_000L),
    )

    private fun TestScope.newCache(td: FakeTdSender) = MediaCache(
        td = td,
        scope = backgroundScope,
        connection = MutableStateFlow(ConnectionStatus.Ready),
        foreground = MutableStateFlow(true),
        networkType = MutableStateFlow(HortayNetworkType.Wifi),
        res = FakeStrings,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { 0L },
    )

    @Test
    fun `resync and ensure on a cold slot share one GetFile`() = runTest {
        val td = FakeTdSender()
        val gate = CompletableDeferred<Unit>()
        td.onAnySuspending("GetFile") {
            gate.await()
            coldFile()
        }
        td.onAny("DownloadFile") { coldFile() }
        val cache = newCache(td)
        val state = cache.observe(fileId)
        runCurrent()

        cache.resync(fileId)
        launch { cache.ensure(fileId, DownloadPriority.VisibleMedia) }
        runCurrent()
        assertEquals(1, td.rpcCount("GetFile"), "ensure must join the resync's in-flight GetFile")
        assertEquals(0, td.rpcCount("DownloadFile"), "DownloadFile waits for the lookup")

        gate.complete(Unit)
        runCurrent()

        assertEquals(1, td.rpcCount("GetFile"))
        assertEquals(1, td.rpcCount("DownloadFile"))
        assertTrue(state.value is MediaState.Downloading, "the shared reply still reaches the reducer: ${state.value}")
    }

    @Test
    fun `a finished lookup is not reused`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetFile") { coldFile() }
        val cache = newCache(td)
        cache.observe(fileId)
        runCurrent()

        cache.resync(fileId)
        runCurrent()
        cache.resync(fileId)
        runCurrent()

        assertEquals(2, td.rpcCount("GetFile"))
    }

    @Test
    fun `retry starts a fresh lookup instead of joining one in flight`() = runTest {
        val td = FakeTdSender()
        val gate = CompletableDeferred<Unit>()
        td.onAnySuspending("GetFile") {
            gate.await()
            coldFile()
        }
        td.onAny("DownloadFile") { coldFile() }
        val cache = newCache(td)
        cache.observe(fileId)
        runCurrent()

        cache.resync(fileId)
        runCurrent()
        launch { cache.retry(fileId, DownloadPriority.VisibleMedia) }
        runCurrent()

        assertEquals(2, td.rpcCount("GetFile"), "retry's lookup must start after its Reset, not join an older one")

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, td.rpcCount("DownloadFile"))
    }
}
