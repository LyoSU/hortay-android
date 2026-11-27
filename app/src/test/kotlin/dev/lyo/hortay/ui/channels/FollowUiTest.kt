package dev.lyo.hortay.ui.channels

import dev.lyo.hortay.data.ChannelMembership
import dev.lyo.hortay.data.subscriptions.FollowedChannels.FollowResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FollowUiTest {

    private val publicChannel = ChannelMembership(isChannel = true, isMember = false, isPublic = true)

    @Test
    fun `public channel the account is not in shows the join bar`() {
        assertEquals(ChannelJoinState.NotJoined, channelJoinState(publicChannel, followed = false))
    }

    @Test
    fun `followed channel shows no bar`() {
        assertEquals(ChannelJoinState.Followed, channelJoinState(publicChannel, followed = true))
        // Membership not resolved yet: the store alone says followed.
        assertEquals(ChannelJoinState.Followed, channelJoinState(null, followed = true))
    }

    @Test
    fun `member reads as member even while the follow hand-off is pending`() {
        val member = publicChannel.copy(isMember = true)
        assertEquals(ChannelJoinState.Member, channelJoinState(member, followed = false))
        assertEquals(ChannelJoinState.Member, channelJoinState(member, followed = true))
    }

    @Test
    fun `private channels and groups cannot be read without joining`() {
        assertEquals(ChannelJoinState.Ineligible, channelJoinState(publicChannel.copy(isPublic = false), followed = false))
        assertEquals(ChannelJoinState.Ineligible, channelJoinState(publicChannel.copy(isChannel = false), followed = false))
    }

    @Test
    fun `unknown membership shows nothing`() {
        assertEquals(ChannelJoinState.Unknown, channelJoinState(null, followed = false))
    }

    @Test
    fun `a follow far from the limit is a plain added notice`() {
        assertEquals(FollowNotice.Added, followNotice(FollowResult.Followed, followedCount = 1, max = 100))
        assertEquals(FollowNotice.Added, followNotice(FollowResult.Followed, followedCount = 89, max = 100))
    }

    @Test
    fun `a follow leaving ten or fewer places says how many are left`() {
        assertEquals(FollowNotice.AddedNearLimit(10), followNotice(FollowResult.Followed, followedCount = 90, max = 100))
        assertEquals(FollowNotice.AddedNearLimit(1), followNotice(FollowResult.Followed, followedCount = 99, max = 100))
        assertEquals(FollowNotice.AddedAtLimit, followNotice(FollowResult.Followed, followedCount = 100, max = 100))
    }

    @Test
    fun `every other result maps to its own notice`() {
        assertEquals(FollowNotice.LimitReached, followNotice(FollowResult.LimitReached, 100))
        assertEquals(FollowNotice.Failed, followNotice(FollowResult.NotPublicChannel, 3))
        assertEquals(FollowNotice.Failed, followNotice(FollowResult.Unavailable, 3))
        assertEquals(FollowNotice.None, followNotice(FollowResult.AlreadyMember, 3))
        assertEquals(FollowNotice.None, followNotice(FollowResult.AlreadyFollowed, 3))
    }
}
