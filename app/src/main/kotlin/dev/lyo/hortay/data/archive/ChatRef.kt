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
