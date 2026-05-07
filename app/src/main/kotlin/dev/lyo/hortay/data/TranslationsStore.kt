package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.Locale

// Note: takes UserMessageBus + connection to surface translate failures (most common
// real-world cause: Telegram Premium gate or per-method FLOOD_WAIT) directly to the
// user; otherwise the translate button silently does nothing on rejection.

/**
 * In-memory cache of message translations.
 *
 * Translations live for the process lifetime; they're a transient view (the user taps
 * "Перекласти" once) so we don't bother persisting. The state is exposed as a single
 * [StateFlow] so any composable observing the post can re-render when its translation
 * lands without us having to plumb the result through individual callbacks.
 *
 * Caching is keyed on **(chatId, messageId, language)** rather than just (chatId,
 * messageId): the system locale can change at runtime (Configuration change recreates
 * Activities but not the process), and serving an old-language translation after the
 * user switches device language would be stale and confusing. The per-language key also
 * lets a future "translate to specific language" UI reuse the same cache.
 *
 * Invalidation hooks into [TdApi.UpdateMessageContent], [TdApi.UpdateMessageEdited] and
 * [TdApi.UpdateDeleteMessages]: when the source body changes, the previous translation
 * no longer matches, and edits to a translated post are common (typo fixes, late-added
 * disclaimers). All language entries for that (chatId, messageId) drop together since
 * the underlying text changed regardless of which target language we cached.
 *
 * One [TdApi.TranslateMessageText] call per (chatId, messageId, language) tuple — the
 * mutex serialises concurrent translate taps on the same message so we don't fire two
 * round-trips for the same id when the user double-taps.
 */
class TranslationsStore(
    private val td: TdSender,
    scope: CoroutineScope,
    private val userMessages: UserMessageBus,
    private val connection: StateFlow<ConnectionStatus>,
    private val res: StringResolver,
) {

    private val _translations = MutableStateFlow<Map<Key, FormattedText>>(emptyMap())
    val translations: StateFlow<Map<Key, FormattedText>> = _translations.asStateFlow()

    /** Coalesce concurrent translate calls per key so we only hit TDLib once. */
    private val inflight = mutableMapOf<Key, Mutex>()
    private val inflightLock = Mutex()

    init {
        // Drop cached translations whose source body has changed or been deleted upstream.
        // We listen here rather than relying on PostsRepository to call us so the cache
        // stays consistent regardless of which surface (channel feed, comments thread)
        // produced the original translation.
        merge(
            td.updates.filterIsInstance<TdApi.UpdateMessageContent>()
                .onEach { invalidate(it.chatId, it.messageId) },
            td.updates.filterIsInstance<TdApi.UpdateMessageEdited>()
                .onEach { invalidate(it.chatId, it.messageId) },
            td.updates.filterIsInstance<TdApi.UpdateDeleteMessages>()
                .onEach { upd ->
                    if (!upd.isPermanent) return@onEach
                    upd.messageIds.forEach { id -> invalidate(upd.chatId, id) }
                },
        ).launchIn(scope)
    }

    suspend fun translate(chatId: Long, messageId: Long): Boolean {
        val target = preferredTargetLanguage()
        val key = Key(chatId, messageId, target)
        if (_translations.value.containsKey(key)) return true

        val mutex = inflightLock.withLock { inflight.getOrPut(key) { Mutex() } }
        return mutex.withLock {
            // Double-check under the per-key lock — another concurrent caller may have
            // populated the map while we waited.
            if (_translations.value.containsKey(key)) return@withLock true
            val attempt = runCatching {
                td.send(TdApi.TranslateMessageText(chatId, messageId, target, /* tone */ null))
            }.warnUnlessCancelled(TAG, "translate($chatId, $messageId, $target)")
            val result = attempt.getOrElse { err ->
                err.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_translate, connection.value)
                return@withLock false
            }

            val mapped = MessageContentMapper.mapFormattedText(result)
            _translations.update { it + (key to mapped) }
            true
        }
    }

    /** Drop cached translations for this message in the **current** target language. */
    fun clear(chatId: Long, messageId: Long) {
        val target = preferredTargetLanguage()
        val key = Key(chatId, messageId, target)
        _translations.update { it - key }
    }

    /**
     * Drop cached translations for this message across **all** cached languages. Used by
     * the update listener — any change to the source body invalidates every translation
     * regardless of target.
     */
    private fun invalidate(chatId: Long, messageId: Long) {
        _translations.update { current ->
            val toRemove = current.keys.filter { it.chatId == chatId && it.messageId == messageId }
            if (toRemove.isEmpty()) current else current - toRemove.toSet()
        }
    }

    fun isTranslated(chatId: Long, messageId: Long): Boolean =
        translation(chatId, messageId) != null

    fun translation(chatId: Long, messageId: Long): FormattedText? {
        val target = preferredTargetLanguage()
        return _translations.value[Key(chatId, messageId, target)]
    }

    /** Read the current target language; exposed so composables can build keys for direct lookup. */
    fun currentTargetLanguage(): String = preferredTargetLanguage()

    /**
     * Two-letter ISO code TDLib accepts. Fallback to "en" so a phone in an unsupported
     * locale (e.g. "iw" / "no" combinations TDLib doesn't recognise) still works instead
     * of erroring. Telegram normalises the rest server-side.
     */
    private fun preferredTargetLanguage(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    data class Key(val chatId: Long, val messageId: Long, val language: String)

    /**
     * Wipe cached translations + in-flight request mutexes. Called from
     * [AppGraph] on logout: translation entries are keyed by
     * (chatId, messageId, language) — chatId/messageId carry implicit
     * account context (they reference the previous account's TDLib
     * database). After re-sign-in those references no longer mean the
     * same thing, so re-translating fresh is correct.
     */
    suspend fun clear() {
        _translations.value = emptyMap()
        inflightLock.withLock { inflight.clear() }
    }

    private companion object { const val TAG = "TranslationsStore" }
}
