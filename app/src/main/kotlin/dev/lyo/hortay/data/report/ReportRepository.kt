// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.data.report

import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdSender
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
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
        val options: ImmutableList<TdApi.ReportOption>,
    ) : ReportState
    data class TextRequired(
        val isOptional: Boolean,
    ) : ReportState
    data object Success : ReportState
    /** Generic failure — includes non-flood-wait TDLib errors. */
    data class Error(val message: String) : ReportState
    /** TDLib 420 / 429 FLOOD_WAIT. [retryAfterSeconds] == 0 means "unknown delay". */
    data class FloodWait(val retryAfterSeconds: Int) : ReportState
}

/**
 * Repository-internal step type. Carries the server [optionId] alongside the
 * public-facing [ReportState] so the ViewModel can stash it for the eventual
 * [ReportRepository.submitText] call without leaking [ByteArray] into the
 * public [ReportState] graph (where [ByteArray] equality breaks [@Stable]
 * analysis and StateFlow dedup).
 */
data class ReportStep(
    val state: ReportState,
    /** Non-null only when [state] is [ReportState.TextRequired]. */
    val pendingOptionId: ByteArray? = null,
)

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
) {
    /**
     * Begin a report against [chatId] / [messageId].
     * Pass an empty [optionId] and empty [text] per TDLib spec for the initial request.
     */
    suspend fun start(chatId: Long, messageId: Long?): ReportStep =
        sendReport(chatId, messageId, byteArrayOf(), "")

    /** User selected one of the server-provided [TdApi.ReportOption]s. */
    suspend fun selectOption(
        chatId: Long,
        messageId: Long?,
        option: TdApi.ReportOption,
    ): ReportStep = sendReport(chatId, messageId, option.id, "")

    /** User typed text (or tapped Skip when text is optional). */
    suspend fun submitText(
        chatId: Long,
        messageId: Long?,
        optionId: ByteArray,
        text: String,
    ): ReportStep = sendReport(chatId, messageId, optionId, text)

    // -------------------------------------------------------------------------

    private suspend fun sendReport(
        chatId: Long,
        messageId: Long?,
        optionId: ByteArray,
        text: String,
    ): ReportStep {
        val messageIds = if (messageId != null && messageId != 0L) longArrayOf(messageId)
        else longArrayOf()
        val result = runCatching {
            td.send(TdApi.ReportChat(chatId, optionId, messageIds, text))
        }
        return result.fold(
            onSuccess = { reportResult -> mapResult(chatId, messageId, reportResult) },
            onFailure = { e -> ReportStep(mapError(chatId, messageId, e)) },
        )
    }

    private suspend fun mapResult(
        chatId: Long,
        messageId: Long?,
        result: TdApi.ReportChatResult,
    ): ReportStep = when (result) {
        is TdApi.ReportChatResultOk -> {
            logTerminal("tdlib", "ok", chatId, messageId)
            ReportStep(ReportState.Success)
        }
        is TdApi.ReportChatResultOptionRequired ->
            ReportStep(
                ReportState.OptionSelection(
                    title = result.title,
                    options = result.options.toList().toImmutableList(),
                ),
            )
        is TdApi.ReportChatResultTextRequired ->
            ReportStep(
                state = ReportState.TextRequired(isOptional = result.isOptional),
                pendingOptionId = result.optionId,
            )
        is TdApi.ReportChatResultMessagesRequired -> {
            // We have no multi-message selection UI yet; surface a generic error
            // so the user at least knows the report was attempted.
            val msg = resolver.getString(dev.lyo.hortay.R.string.error_generic)
            logTerminal("tdlib", "delegated", chatId, messageId)
            ReportStep(ReportState.Error(msg))
        }
        else -> {
            val msg = resolver.getString(dev.lyo.hortay.R.string.error_generic)
            logTerminal("tdlib", "failed", chatId, messageId)
            ReportStep(ReportState.Error(msg))
        }
    }

    private suspend fun mapError(
        chatId: Long,
        messageId: Long?,
        e: Throwable,
    ): ReportState {
        val tdEx = e as? TdClient.TdException
        if (tdEx != null && TdClient.isFloodWaitCode(tdEx.code)) {
            val seconds = TdClient.parseFloodWaitSeconds(tdEx) ?: 0
            logTerminal("tdlib", "failed", chatId, messageId)
            return ReportState.FloodWait(seconds)
        }
        val msg = resolver.getString(dev.lyo.hortay.R.string.error_generic)
        logTerminal("tdlib", "failed", chatId, messageId)
        return ReportState.Error(msg)
    }

    private suspend fun logTerminal(
        method: String,
        status: String,
        chatId: Long,
        messageId: Long?,
    ) {
        log.log(
            ReportLogEntry(
                timestamp = System.currentTimeMillis(),
                mode = "auth",
                channelUsername = null,
                chatId = chatId,
                messageId = messageId,
                deliveryMethod = method,
                deliveryStatus = status,
            ),
        )
    }
}
