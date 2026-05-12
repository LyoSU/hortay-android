// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.data.report

import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdSender
import kotlinx.coroutines.CoroutineScope
import org.drinkless.tdlib.TdApi

/**
 * States surfaced to [ReportFlowSheet] during the multi-step TDLib ReportChat flow.
 *
 * Flow: [Idle] → [Loading] → one of:
 *   • [OptionSelection]  — server returned options; user picks one
 *   • [TextRequired]     — server wants a text explanation; user types and submits
 *   • [Success]          — server accepted the report; UI should dismiss
 *   • [Error]            — unexpected failure; user can retry
 *   • [FloodWait]        — rate-limited; show retry-after countdown
 *
 * Why sealed interface rather than enum: the data-bearing variants (OptionSelection,
 * TextRequired, Error, FloodWait) would bloat an enum with nullable fields; sealed
 * interface keeps each variant's contract explicit and Kotlin-exhaustive.
 */
sealed interface ReportState {
    data object Idle : ReportState
    data object Loading : ReportState
    data class OptionSelection(
        val title: String,
        val options: List<TdApi.ReportOption>,
    ) : ReportState
    data class TextRequired(
        val optionId: ByteArray,
        val isOptional: Boolean,
    ) : ReportState
    data object Success : ReportState
    /** Generic failure — includes non-flood-wait TDLib errors. */
    data class Error(val message: String) : ReportState
    /** TDLib 420 / 429 FLOOD_WAIT. [retryAfterSeconds] == 0 means "unknown delay". */
    data class FloodWait(val retryAfterSeconds: Int) : ReportState
}

/**
 * Drives the TDLib dynamic ReportChat flow for the authenticated mode.
 *
 * Every call to [start] / [selectOption] / [submitText] maps the raw
 * [TdApi.ReportChatResult] variants to [ReportState] and appends a
 * [ReportLogEntry] when a terminal state is reached (Success, Error, FloodWait).
 * Intermediate states (OptionSelection, TextRequired) are not logged — they carry
 * no outcome information.
 *
 * TDLib contract recap:
 *   ReportChatResultOk            → done
 *   ReportChatResultOptionRequired→ show options list (dynamic, server-localised)
 *   ReportChatResultTextRequired  → show text input; isOptional controls Skip visibility
 *   ReportChatResultMessagesRequired → (rare) ask the user to pick messages;
 *                                  Hortay surfaces this as a generic Error since we
 *                                  have no multi-message selection UI yet.
 */
class ReportRepository(
    private val td: TdSender,
    private val resolver: StringResolver,
    private val log: ReportLogStore,
    @Suppress("UnusedPrivateMember") private val scope: CoroutineScope,
) {
    /**
     * Begin a report against [chatId] / [messageId].
     * Pass an empty [optionId] and empty [text] per TDLib spec for the initial request.
     */
    suspend fun start(chatId: Long, messageId: Long?): ReportState =
        sendReport(chatId, messageId, byteArrayOf(), "")

    /** User selected one of the server-provided [TdApi.ReportOption]s. */
    suspend fun selectOption(
        chatId: Long,
        messageId: Long?,
        option: TdApi.ReportOption,
    ): ReportState = sendReport(chatId, messageId, option.id, "")

    /** User typed text (or tapped Skip when text is optional). */
    suspend fun submitText(
        chatId: Long,
        messageId: Long?,
        optionId: ByteArray,
        text: String,
    ): ReportState = sendReport(chatId, messageId, optionId, text)

    // -------------------------------------------------------------------------

    private suspend fun sendReport(
        chatId: Long,
        messageId: Long?,
        optionId: ByteArray,
        text: String,
    ): ReportState {
        val messageIds = if (messageId != null && messageId != 0L) longArrayOf(messageId)
        else longArrayOf()
        val result = runCatching {
            td.send(TdApi.ReportChat(chatId, optionId, messageIds, text))
        }
        return result.fold(
            onSuccess = { reportResult ->
                mapResult(chatId, messageId, reportResult)
            },
            onFailure = { e ->
                mapError(chatId, messageId, e)
            },
        )
    }

    private suspend fun mapResult(
        chatId: Long,
        messageId: Long?,
        result: TdApi.ReportChatResult,
    ): ReportState = when (result) {
        is TdApi.ReportChatResultOk -> {
            log.log(
                ReportLogEntry(
                    timestamp = System.currentTimeMillis(),
                    mode = "auth",
                    channelUsername = null,
                    chatId = chatId,
                    messageId = messageId,
                    deliveryMethod = "tdlib",
                    deliveryStatus = "ok",
                ),
            )
            ReportState.Success
        }
        is TdApi.ReportChatResultOptionRequired ->
            ReportState.OptionSelection(
                title = result.title,
                options = result.options.toList(),
            )
        is TdApi.ReportChatResultTextRequired ->
            ReportState.TextRequired(
                optionId = result.optionId,
                isOptional = result.isOptional,
            )
        is TdApi.ReportChatResultMessagesRequired -> {
            // We have no multi-message selection UI yet; surface a generic error
            // so the user at least knows the report was attempted.
            val msg = resolver.getString(dev.lyo.hortay.R.string.error_generic)
            log.log(
                ReportLogEntry(
                    timestamp = System.currentTimeMillis(),
                    mode = "auth",
                    channelUsername = null,
                    chatId = chatId,
                    messageId = messageId,
                    deliveryMethod = "tdlib",
                    deliveryStatus = "delegated",
                ),
            )
            ReportState.Error(msg)
        }
        else -> {
            val msg = resolver.getString(dev.lyo.hortay.R.string.error_generic)
            log.log(
                ReportLogEntry(
                    timestamp = System.currentTimeMillis(),
                    mode = "auth",
                    channelUsername = null,
                    chatId = chatId,
                    messageId = messageId,
                    deliveryMethod = "tdlib",
                    deliveryStatus = "failed",
                ),
            )
            ReportState.Error(msg)
        }
    }

    private suspend fun mapError(
        chatId: Long,
        messageId: Long?,
        e: Throwable,
    ): ReportState {
        val code = (e as? TdClient.TdException)?.code ?: 0
        return if (TdClient.isFloodWaitCode(code)) {
            // Extract "FLOOD_WAIT_N" seconds from the message, e.g. "[420] FLOOD_WAIT_31"
            val seconds = Regex("\\d+").find(e.message.orEmpty().substringAfter("FLOOD_WAIT"))
                ?.value?.toIntOrNull() ?: 0
            log.log(
                ReportLogEntry(
                    timestamp = System.currentTimeMillis(),
                    mode = "auth",
                    channelUsername = null,
                    chatId = chatId,
                    messageId = messageId,
                    deliveryMethod = "tdlib",
                    deliveryStatus = "failed",
                ),
            )
            ReportState.FloodWait(seconds)
        } else {
            val msg = resolver.getString(dev.lyo.hortay.R.string.error_generic)
            log.log(
                ReportLogEntry(
                    timestamp = System.currentTimeMillis(),
                    mode = "auth",
                    channelUsername = null,
                    chatId = chatId,
                    messageId = messageId,
                    deliveryMethod = "tdlib",
                    deliveryStatus = "failed",
                ),
            )
            ReportState.Error(msg)
        }
    }
}
