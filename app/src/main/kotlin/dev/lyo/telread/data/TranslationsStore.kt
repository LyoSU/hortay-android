package dev.lyo.telread.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.Locale

/**
 * In-memory cache of message translations.
 *
 * Translations live for the process lifetime; they're a transient view (the user taps
 * "Перекласти" once) so we don't bother persisting. The state is exposed as a single
 * [StateFlow] so any composable observing the post can re-render when its translation
 * lands without us having to plumb the result through individual callbacks.
 *
 * One [TdApi.TranslateMessageText] call per (chatId, messageId, language) tuple — the
 * mutex serialises concurrent translate taps on the same message so we don't fire two
 * round-trips for the same id when the user double-taps.
 */
class TranslationsStore(private val td: TdSender) {

    private val _translations = MutableStateFlow<Map<Key, FormattedText>>(emptyMap())
    val translations: StateFlow<Map<Key, FormattedText>> = _translations.asStateFlow()

    /** Coalesce concurrent translate calls per key so we only hit TDLib once. */
    private val inflight = mutableMapOf<Key, Mutex>()
    private val inflightLock = Mutex()

    suspend fun translate(chatId: Long, messageId: Long): Boolean {
        val key = Key(chatId, messageId)
        if (_translations.value.containsKey(key)) return true

        val mutex = inflightLock.withLock { inflight.getOrPut(key) { Mutex() } }
        return mutex.withLock {
            // Double-check under the per-key lock — another concurrent caller may have
            // populated the map while we waited.
            if (_translations.value.containsKey(key)) return@withLock true
            val target = preferredTargetLanguage()
            val result = runCatching {
                td.send(TdApi.TranslateMessageText(chatId, messageId, target, /* tone */ null))
            }.warnUnlessCancelled(TAG, "translate($chatId, $messageId, $target)")
                .getOrNull() ?: return@withLock false

            val mapped = MessageContentMapper.mapFormattedText(result)
            _translations.update { it + (key to mapped) }
            true
        }
    }

    /** Drop the cached translation; the post reverts to its original text on next render. */
    fun clear(chatId: Long, messageId: Long) {
        val key = Key(chatId, messageId)
        _translations.update { it - key }
    }

    fun isTranslated(chatId: Long, messageId: Long): Boolean =
        Key(chatId, messageId) in _translations.value

    fun translation(chatId: Long, messageId: Long): FormattedText? =
        _translations.value[Key(chatId, messageId)]

    /**
     * Two-letter ISO code TDLib accepts. Fallback to "en" so a phone in an unsupported
     * locale (e.g. "iw" / "no" combinations TDLib doesn't recognise) still works instead
     * of erroring. Telegram normalises the rest server-side.
     */
    private fun preferredTargetLanguage(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    data class Key(val chatId: Long, val messageId: Long)

    private companion object { const val TAG = "TranslationsStore" }
}
