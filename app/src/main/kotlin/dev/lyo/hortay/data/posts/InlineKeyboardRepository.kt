package dev.lyo.hortay.data.posts

import dev.lyo.hortay.R
import dev.lyo.hortay.data.AccountSession
import dev.lyo.hortay.data.CallbackAnswer
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.surfaceTo
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

/**
 * Presses callback buttons of a post's inline keyboard (`getCallbackQueryAnswer`).
 *
 * A callback query is a network request to the bot, routed through [TdSender.send] like
 * every other RPC — so it waits behind the global FLOOD_WAIT gate and its failures map
 * through [surfaceTo]. Bots answer within a few seconds or not at all (Telegram drops an
 * unanswered query after ~15 s and clients then show nothing), so the wait is capped at
 * [CALLBACK_ANSWER_TIMEOUT_MS]; past it the press resolves to null quietly, the button's
 * progress state ends and nothing is shown — the same as Telegram's own clients.
 *
 * The request runs in [AccountSession.scope], not the caller's: a card scrolled off
 * screen mid-press cancels only the caller's wait, and the bot's toast still reaches
 * [UserMessageBus]. A logout cancels it with the rest of the account's work. No state is
 * held between presses, so there is nothing to wipe on logout.
 */
class InlineKeyboardRepository(
    private val td: TdSender,
    private val userMessages: UserMessageBus,
    private val connection: StateFlow<ConnectionStatus>,
    private val res: StringResolver,
    private val session: AccountSession,
) {

    /**
     * Send the callback query for [data] on [messageId] in [chatId]. A non-alert answer text
     * is posted to [UserMessageBus] here; the returned answer is for the caller to show an
     * alert dialog ([CallbackAnswer.showAlert]) or open [CallbackAnswer.url]. Null on
     * failure (already surfaced) or timeout.
     */
    suspend fun pressCallback(chatId: Long, messageId: Long, data: ByteArray): CallbackAnswer? =
        session.scope.async {
            val answer = withTimeoutOrNull(CALLBACK_ANSWER_TIMEOUT_MS) {
                runCatching {
                    td.send(
                        TdApi.GetCallbackQueryAnswer(chatId, messageId, TdApi.CallbackQueryPayloadData(data)),
                    )
                }
                    .warnUnlessCancelled(TAG, "pressCallback($chatId,$messageId)")
                    .onFailure { it.surfaceTo(userMessages, res, R.string.op_press_button, connection.value) }
                    .getOrNull()
            } ?: return@async null
            val result = CallbackAnswer(
                text = answer.text.orEmpty(),
                showAlert = answer.showAlert,
                url = answer.url?.takeIf { it.isNotBlank() },
            )
            if (!result.showAlert && result.text.isNotBlank()) {
                userMessages.post(result.text, UserMessageBus.Severity.Info)
            }
            result
        }.await()

    private companion object {
        const val TAG = "InlineKeyboard"

        /** Telegram's own server-side wait for a bot's callback answer is about this long. */
        const val CALLBACK_ANSWER_TIMEOUT_MS = 15_000L
    }
}
