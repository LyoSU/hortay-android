package dev.lyo.hortay.data.discover

import dev.lyo.hortay.data.FakeTdSender
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelDiscoveryMembershipTest {

    @Test
    fun `restricted and creator statuses honour their isMember flag`() {
        assertFalse(isChannelMember(TdApi.ChatMemberStatusRestricted().apply { isMember = false }))
        assertTrue(isChannelMember(TdApi.ChatMemberStatusRestricted().apply { isMember = true }))
        assertFalse(isChannelMember(TdApi.ChatMemberStatusCreator().apply { isMember = false }))
        assertTrue(isChannelMember(TdApi.ChatMemberStatusCreator().apply { isMember = true }))
        assertTrue(isChannelMember(TdApi.ChatMemberStatusMember()))
        assertFalse(isChannelMember(TdApi.ChatMemberStatusLeft()))
        assertFalse(isChannelMember(TdApi.ChatMemberStatusBanned()))
        assertFalse(isChannelMember(null))
    }

    @Test
    fun `cached card follows a join without another resolve RPC`() = runTest {
        val td = FakeTdSender().apply {
            onAny("SearchPublicChat") {
                TdApi.Chat().apply {
                    id = -100L
                    title = "News"
                    type = TdApi.ChatTypeSupergroup().apply {
                        supergroupId = 1L
                        isChannel = true
                    }
                }
            }
            onAny("GetSupergroup") {
                TdApi.Supergroup().apply {
                    id = 1L
                    isChannel = true
                    status = TdApi.ChatMemberStatusLeft()
                }
            }
        }
        val repo = ChannelDiscoveryRepository(td)
        assertEquals(false, repo.resolve("news")?.isMember)

        repo.updateMembership(-100L, isMember = true)

        assertEquals(true, repo.resolve("news")?.isMember)
        assertEquals(1, td.rpcCount("SearchPublicChat"))
    }
}
