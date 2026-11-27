package dev.lyo.hortay.data.subscriptions

import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnownChannelMembershipTest {

    private fun supergroup(
        status: TdApi.ChatMemberStatus,
        handle: String = "news",
        isChannel: Boolean = true,
    ) = TdApi.Supergroup().apply {
        this.status = status
        this.isChannel = isChannel
        usernames = TdApi.Usernames(arrayOf(handle), emptyArray(), handle, emptyArray())
    }

    private fun membership(id: Long?, group: TdApi.Supergroup?) = KnownChannelMembership(
        channelIdOf = { id },
        supergroup = { group },
    )

    @Test
    fun `a member of the channel behind the handle is recognised`() = runTest {
        assertTrue(membership(1L, supergroup(TdApi.ChatMemberStatusMember())).isMember("News"))
    }

    @Test
    fun `no stored id, an unknown supergroup or a left channel read as not a member`() = runTest {
        assertFalse(membership(null, supergroup(TdApi.ChatMemberStatusMember())).isMember("news"))
        assertFalse(membership(1L, null).isMember("news"))
        assertFalse(membership(1L, supergroup(TdApi.ChatMemberStatusLeft())).isMember("news"))
    }

    @Test
    fun `a handle that now belongs to another channel is not trusted`() = runTest {
        assertFalse(membership(1L, supergroup(TdApi.ChatMemberStatusMember(), handle = "renamed")).isMember("news"))
        assertFalse(membership(1L, supergroup(TdApi.ChatMemberStatusMember(), isChannel = false)).isMember("news"))
    }
}
