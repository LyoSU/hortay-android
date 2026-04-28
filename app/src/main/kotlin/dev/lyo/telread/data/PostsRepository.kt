package dev.lyo.telread.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

/**
 * Pulls a Twitter-style chronological feed merged from all *channel* chats the user follows.
 *
 * TDLib pattern:
 *   1. [TdApi.LoadChats] tells the daemon to fetch chat list pages from network.
 *   2. [TdApi.GetChats] returns the now-cached chat IDs.
 *   3. For each chat we call [TdApi.GetChatHistory] with `fromMessageId = 0` to get latest N msgs.
 *   4. Merge → sort by date desc → run through [PostFilterStrategy] → emit.
 */
class PostsRepository(private val td: TdClient) {

    private val _posts = MutableStateFlow<List<TimelinePost>>(emptyList())
    val posts: StateFlow<List<TimelinePost>> = _posts.asStateFlow()

    suspend fun refresh(limitPerChannel: Int = 20) {
        runCatching { td.send(TdApi.LoadChats(TdApi.ChatListMain(), 200)) }
        val chats = td.send(TdApi.GetChats(TdApi.ChatListMain(), 500)).chatIds

        val raw = mutableListOf<TimelinePost>()
        for (chatId in chats) {
            val chat = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull() ?: continue
            if (!chat.isChannel()) continue

            val history = runCatching {
                td.send(TdApi.GetChatHistory(chatId, 0, 0, limitPerChannel, false))
            }.getOrNull() ?: continue

            history.messages?.forEach { msg ->
                raw += msg.toPost(chat)
            }
        }

        _posts.value = PostFilterStrategy.apply(raw)
    }
}

private fun TdApi.Chat.isChannel(): Boolean {
    val type = this.type
    return type is TdApi.ChatTypeSupergroup && type.isChannel
}

private fun TdApi.Message.toPost(chat: TdApi.Chat): TimelinePost {
    val text: String
    val thumb: Int?
    val mediaCount: Int
    when (val c = content) {
        is TdApi.MessageText -> {
            text = c.text.text
            thumb = null
            mediaCount = 0
        }
        is TdApi.MessagePhoto -> {
            text = c.caption?.text.orEmpty()
            thumb = c.photo.sizes.minByOrNull { it.width * it.height }?.photo?.id
            mediaCount = 1
        }
        is TdApi.MessageVideo -> {
            text = c.caption?.text.orEmpty()
            thumb = c.video.thumbnail?.file?.id
            mediaCount = 1
        }
        is TdApi.MessageAnimation -> {
            text = c.caption?.text.orEmpty()
            thumb = c.animation.thumbnail?.file?.id
            mediaCount = 1
        }
        else -> {
            text = "[unsupported content]"
            thumb = null
            mediaCount = 0
        }
    }

    return TimelinePost(
        id = id,
        chatId = chatId,
        channelTitle = chat.title.orEmpty(),
        channelHandle = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId?.let { "id$it" },
        avatarFileId = chat.photo?.small?.id,
        text = text,
        mediaThumbFileId = thumb,
        mediaCount = mediaCount,
        views = interactionInfo?.viewCount ?: 0,
        date = date.toLong() * 1000L,
        isForwarded = forwardInfo != null,
    )
}
