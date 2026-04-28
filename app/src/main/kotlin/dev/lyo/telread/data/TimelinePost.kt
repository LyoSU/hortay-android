package dev.lyo.telread.data

/** UI-facing post model. Decouples Compose from TDLib's TdApi types. */
data class TimelinePost(
    val id: Long,
    val chatId: Long,
    val channelTitle: String,
    val channelHandle: String?,
    val avatarFileId: Int?,
    val text: String,
    val mediaThumbFileId: Int?,
    val mediaCount: Int,
    val views: Int,
    val date: Long,
    val isForwarded: Boolean,
)
