package dev.lyo.hortay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A folder-membership resolve that is still in flight when the account logs out must not
 * write the previous account's chat ids into the freshly-cleared cache.
 */
class ChatFoldersLogoutRaceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val td = FakeTdSender()
    private val repo = ChatFoldersRepository(td, scope)

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `resolve straddling clear does not repopulate the cache`() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        td.onAnySuspending("LoadChats") {
            loadStarted.complete(Unit)
            releaseLoad.await()
            TdApi.Error(404, "Not Found")
        }
        td.onAny("GetChats") { TdApi.Chats(2, longArrayOf(-1L, -2L)) }

        val inFlight = async(Dispatchers.Default) { repo.folderChatIds(FOLDER_ID) }
        loadStarted.await()
        repo.clear()
        releaseLoad.complete(Unit)
        inFlight.await()

        assertNull(repo.folderChatIds.value[FOLDER_ID])
    }

    @Test
    fun `resolve without an intervening clear is cached`() = runBlocking {
        td.onAny("LoadChats") { TdApi.Error(404, "Not Found") }
        td.onAny("GetChats") { TdApi.Chats(2, longArrayOf(-1L, -2L)) }

        repo.folderChatIds(FOLDER_ID)

        assertEquals(setOf(-1L, -2L), repo.folderChatIds.value[FOLDER_ID])
    }

    private companion object {
        const val FOLDER_ID = 7
    }
}
