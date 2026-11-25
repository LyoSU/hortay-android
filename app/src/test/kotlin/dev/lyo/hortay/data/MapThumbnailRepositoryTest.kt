package dev.lyo.hortay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapThumbnailRepositoryTest {

    private val request = MapThumbnailRequest(50.45, 30.52, zoom = 15, width = 352, height = 176, scale = 2)
    private val fileId = 46_706

    private fun generatedFile(completed: Boolean): TdApi.File = TdApi.File(
        fileId,
        if (completed) 50_000L else 0L,
        if (completed) 50_000L else 0L,
        TdApi.LocalFile(
            if (completed) "/td/thumbnails/map_15_1.png" else "",
            true,
            false,
            !completed,
            completed,
            0L,
            if (completed) 50_000L else 0L,
            if (completed) 50_000L else 0L,
        ),
        TdApi.RemoteFile("", "", false, false, 0L),
    )

    private fun TestScope.newRepository(td: FakeTdSender): Pair<MapThumbnailRepository, CoroutineScope> {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val session = AccountSession(parent)
        return MapThumbnailRepository(td, session, clock = { testScheduler.currentTime }) to parent
    }

    @Test
    fun `downloads the generated picture synchronously and hands back a finished file`() = runTest {
        val td = FakeTdSender()
        var download: TdApi.DownloadFile? = null
        td.onAny("GetMapThumbnailFile") { generatedFile(completed = false) }
        td.onAny("DownloadFile") {
            download = it as TdApi.DownloadFile
            generatedFile(completed = true)
        }
        val (repo, parent) = newRepository(td)

        assertEquals(fileId, repo.resolve(request))
        assertEquals(fileId, repo.resolve(request))

        assertTrue(download!!.synchronous)
        assertEquals(1, td.rpcCount("GetMapThumbnailFile"))
        assertEquals(1, td.rpcCount("DownloadFile"))
        parent.cancel()
    }

    @Test
    fun `a picture already on disk is not downloaded again`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetMapThumbnailFile") { generatedFile(completed = true) }
        val (repo, parent) = newRepository(td)

        assertEquals(fileId, repo.resolve(request))
        assertEquals(0, td.rpcCount("DownloadFile"))
        parent.cancel()
    }

    @Test
    fun `a slow picture is never cancelled and lands through updateFile after a send timeout`() = runTest {
        val td = FakeTdSender()
        val reply = CompletableDeferred<TdApi.Object>()
        var downloads = 0
        td.onAny("GetMapThumbnailFile") { generatedFile(completed = false) }
        td.onAnySuspending("DownloadFile") {
            downloads++
            // First wait ends in our own send timeout; the repeat stays pending like TDLib's.
            if (downloads == 1) TdApi.Error(TdClient.SEND_TIMEOUT_CODE, "SEND_TIMEOUT DownloadFile") else reply.await()
        }
        val (repo, parent) = newRepository(td)

        val first = async { repo.resolve(request) }
        runCurrent()
        // A card that scrolled away and came back joins the same fetch.
        val second = async { repo.resolve(request) }
        testScheduler.advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertFalse(first.isCompleted)

        td.emitUpdate(TdApi.UpdateFile(generatedFile(completed = true)))
        assertEquals(fileId, first.await())
        assertEquals(fileId, second.await())

        assertEquals(1, td.rpcCount("GetMapThumbnailFile"))
        assertEquals(2, downloads)
        assertEquals(0, td.rpcCount("CancelDownloadFile"))
        parent.cancel()
    }

    @Test
    fun `a caller that leaves does not stop the fetch for the next one`() = runTest {
        val td = FakeTdSender()
        val reply = CompletableDeferred<TdApi.Object>()
        td.onAny("GetMapThumbnailFile") { generatedFile(completed = false) }
        td.onAnySuspending("DownloadFile") { reply.await() }
        val (repo, parent) = newRepository(td)

        val leaving = async { repo.resolve(request) }
        runCurrent()
        leaving.cancel()
        runCurrent()
        reply.complete(generatedFile(completed = true))
        runCurrent()

        assertEquals(fileId, repo.resolve(request))
        assertEquals(1, td.rpcCount("DownloadFile"))
        assertEquals(0, td.rpcCount("CancelDownloadFile"))
        parent.cancel()
    }

    @Test
    fun `a TDLib error reports unavailable and a later ask tries again`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetMapThumbnailFile") { generatedFile(completed = false) }
        td.onAny("DownloadFile") { TdApi.Error(400, "File is too big") }
        val (repo, parent) = newRepository(td)

        assertNull(repo.resolve(request))
        assertEquals(1, td.rpcCount("DownloadFile"))

        td.onAny("DownloadFile") { generatedFile(completed = true) }
        assertEquals(fileId, repo.resolve(request))
        assertEquals(2, td.rpcCount("DownloadFile"))
        parent.cancel()
    }

    @Test
    fun `a refused request reports unavailable without downloading`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetMapThumbnailFile") { TdApi.Error(400, "Wrong zoom") }
        val (repo, parent) = newRepository(td)

        assertNull(repo.resolve(request))
        assertFalse(td.rpcCounts.containsKey("DownloadFile"))
        parent.cancel()
    }
}
