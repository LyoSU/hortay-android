package dev.lyo.hortay.data

import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ChatRegistryTest {

    private fun user(id: Long, firstName: String) = TdApi.User().apply {
        this.id = id
        this.firstName = firstName
    }

    @Test
    fun `update-fed user is served without an RPC`() = runTest {
        val td = FakeTdSender()
        val registry = ChatRegistry(td)
        registry.onUpdate(TdApi.UpdateUser(user(1L, "Olha")))

        assertEquals("Olha", registry.user(1L)?.firstName)
        assertEquals(0, td.rpcCount("GetUser"))
    }

    @Test
    fun `a later update replaces the entry so renames are never stale`() = runTest {
        val td = FakeTdSender()
        val registry = ChatRegistry(td)
        registry.onUpdate(TdApi.UpdateUser(user(1L, "Old")))
        registry.onUpdate(TdApi.UpdateUser(user(1L, "New")))

        assertEquals("New", registry.user(1L)?.firstName)
    }

    @Test
    fun `a miss falls back to GetUser once and caches the answer`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetUser") { req -> user((req as TdApi.GetUser).userId, "Fetched") }
        val registry = ChatRegistry(td)

        assertEquals("Fetched", registry.user(7L)?.firstName)
        assertEquals("Fetched", registry.user(7L)?.firstName)
        assertEquals(1, td.rpcCount("GetUser"))
    }

    @Test
    fun `supergroup updates are mirrored and clear drops everything`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetSupergroup") { TdApi.Error(404, "gone") }
        val registry = ChatRegistry(td)
        registry.onUpdate(TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 5L; memberCount = 42 }))

        assertEquals(42, registry.supergroup(5L)?.memberCount)
        assertEquals(0, td.rpcCount("GetSupergroup"))

        registry.clear()
        assertNull(registry.supergroup(5L), "after clear the slot misses and the failing RPC yields null")
        assertEquals(1, td.rpcCount("GetSupergroup"))
    }

    @Test
    fun `chats are written explicitly, read without an RPC, and evicted on forget`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetChat") { req -> TdApi.Chat().apply { id = (req as TdApi.GetChat).chatId; title = "Fetched" } }
        val registry = ChatRegistry(td)
        val chat = TdApi.Chat().apply { id = -100L; title = "Known" }

        registry.rememberChat(chat)
        assertSame(chat, registry.cachedChat(-100L))
        assertSame(chat, registry.chat(-100L))
        assertEquals(0, td.rpcCount("GetChat"))

        registry.forgetChat(-100L)
        assertNull(registry.cachedChat(-100L))
        assertEquals("Fetched", registry.chat(-100L)?.title, "a miss re-reads TDLib's local store")
        assertEquals("Fetched", registry.cachedChat(-100L)?.title, "and stores the fresh object")
        assertEquals(1, td.rpcCount("GetChat"))
    }

    @Test
    fun `UpdateNewChat alone does not register a chat`() = runTest {
        // PostsRepository owns chat writes (its UpdateChatLastMessage race gate needs the
        // write in its own coroutine); a collector here would reopen that race.
        val registry = ChatRegistry(FakeTdSender())
        registry.onUpdate(TdApi.UpdateNewChat(TdApi.Chat().apply { id = -100L }))

        assertNull(registry.cachedChat(-100L))
    }
}
