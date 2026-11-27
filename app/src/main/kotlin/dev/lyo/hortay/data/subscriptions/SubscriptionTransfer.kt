package dev.lyo.hortay.data.subscriptions

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.Immutable
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelDirectory
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebFeedSource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStreamReader

/** Which list an import / export works on: the local guest list or the Telegram account's. */
enum class TransferMode { Guest, Account }

/** What the import sheet shows; null in [SubscriptionTransfer.sheet] means it is closed. */
@Immutable
sealed interface ImportSheetState {
    /** Paste field / open-file step. [nothingFound] marks a submit that found no channel. */
    @Immutable
    data class Input(val initialText: String = "", val nothingFound: Boolean = false) : ImportSheetState

    /** Channels found in the text, in order; [truncated] means the list was cut at the cap. */
    @Immutable
    data class Preview(val usernames: ImmutableList<String>, val truncated: Boolean) : ImportSheetState

    /** Signed-in import joining channels one by one. */
    @Immutable
    data class Joining(val total: Int, val processed: Int, val current: String?) : ImportSheetState
}

/** The list an export would write: public usernames plus how many private channels were left out. */
@Immutable
data class ExportList(val usernames: ImmutableList<String>, val skippedPrivate: Int)

/**
 * Import / export of the channel list, in both modes, plus the "share links to Hortay"
 * target. One graph-scoped holder so the Channels tab menu (both modes) and the share
 * intent in `MainActivity` drive the same import sheet.
 *
 * - **Export** — guest mode writes [SubscriptionsStore]; signed-in mode writes the public
 *   usernames of the account's subscribed channels ([PostsRepository.subscribedChatIds],
 *   the Channels tab's source). A private channel has no stable link and is skipped.
 * - **Import, guest** — [WebFeedSource.subscribeAll]: one bulk write plus one sweep.
 * - **Import, signed in** — [ThrottledChannelJoiner], one channel per second, in the
 *   account [dev.lyo.hortay.data.AccountSession.scope] so a logout cancels it. The run
 *   stops on a FLOOD_WAIT instead of freezing behind TdClient's gate.
 *
 * Outcomes reach the user through [UserMessageBus], which both scaffolds render.
 */
class SubscriptionTransfer(
    private val webSubscriptions: SubscriptionsStore,
    private val webFeed: WebFeedSource,
    private val posts: PostsRepository,
    private val channels: ChannelDirectory,
    private val joiner: ThrottledChannelJoiner,
    /** Account-session scope provider; read per run because a logout replaces the scope. */
    private val accountScope: () -> CoroutineScope,
    private val appScope: CoroutineScope,
    private val userMessages: UserMessageBus,
    private val res: StringResolver,
) {
    private val _sheet = MutableStateFlow<ImportSheetState?>(null)
    val sheet: StateFlow<ImportSheetState?> = _sheet.asStateFlow()

    @Volatile private var joinJob: Job? = null
    @Volatile private var stoppedByUser = false

    private val isJoining: Boolean get() = _sheet.value is ImportSheetState.Joining

    /** Open the sheet on its empty paste step. */
    fun openImport() {
        if (isJoining) return
        _sheet.value = ImportSheetState.Input()
    }

    /**
     * Text shared to Hortay from another app. Channel links open the preview; text without
     * any says so in a snackbar. Ignored while a signed-in import is still joining.
     */
    fun offerSharedText(text: String) {
        if (isJoining) return
        val parsed = SubscriptionListFormat.parse(text)
        if (parsed.usernames.isEmpty()) {
            userMessages.post(res.getString(R.string.channel_import_nothing_shared), UserMessageBus.Severity.Info)
            return
        }
        _sheet.value = ImportSheetState.Preview(parsed.usernames.toImmutableList(), parsed.truncated)
    }

    /** Parse pasted text: the preview when it holds channels, else back to input with a note. */
    fun submitText(text: String) {
        if (isJoining) return
        val parsed = SubscriptionListFormat.parse(text)
        _sheet.value = if (parsed.usernames.isEmpty()) {
            ImportSheetState.Input(initialText = text, nothingFound = true)
        } else {
            ImportSheetState.Preview(parsed.usernames.toImmutableList(), parsed.truncated)
        }
    }

    /** Read at most [SubscriptionListFormat.MAX_IMPORT_CHARS] of [uri] off the main thread, then [submitText]. */
    suspend fun submitFile(resolver: ContentResolver, uri: Uri) {
        val text = try {
            withContext(Dispatchers.IO) { readCapped(resolver, uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // SecurityException from a revoked grant, IOException from the provider.
            Log.w(TAG, "import: reading $uri failed", e)
            userMessages.post(res.getString(R.string.channel_import_read_failed))
            return
        }
        submitText(text)
    }

    /** From the preview back to the paste step. */
    fun backToInput() {
        if (isJoining) return
        _sheet.value = ImportSheetState.Input()
    }

    /** Close the sheet. A running join is stopped only through [stopJoining]. */
    fun dismiss() {
        if (isJoining) return
        _sheet.value = null
    }

    /** Usernames already followed in [mode], for the preview's "already following" marks. */
    suspend fun followedUsernames(mode: TransferMode): Set<String> = when (mode) {
        TransferMode.Guest -> webSubscriptions.subscriptions.first()
        TransferMode.Account -> exportList(mode).usernames.toSet()
    }

    /** Follow [usernames] (the new ones from the preview) in [mode]. */
    fun apply(mode: TransferMode, usernames: List<String>) {
        if (isJoining || usernames.isEmpty()) {
            _sheet.value = null
            return
        }
        when (mode) {
            TransferMode.Guest -> {
                _sheet.value = null
                appScope.launch {
                    webFeed.subscribeAll(usernames)
                    userMessages.post(
                        res.getQuantityString(R.plurals.channel_import_followed_guest, usernames.size, usernames.size),
                        UserMessageBus.Severity.Info,
                    )
                }
            }
            TransferMode.Account -> startJoining(usernames)
        }
    }

    private fun startJoining(usernames: List<String>) {
        val total = usernames.size
        stoppedByUser = false
        _sheet.value = ImportSheetState.Joining(total = total, processed = 0, current = null)
        var joinedSoFar = 0
        joinJob = accountScope().launch {
            try {
                val result = joiner.joinAll(
                    usernames = usernames,
                    stopOnFloodWait = true,
                    onProgress = { processed, current ->
                        _sheet.value = ImportSheetState.Joining(total, processed, current)
                    },
                    onJoined = { joinedSoFar++ },
                )
                _sheet.value = null
                userMessages.post(summary(result.joined.size, total, result.floodLimited), severityFor(result))
            } catch (e: CancellationException) {
                _sheet.value = null
                // A logout cancels the session scope too; only the user's Stop gets a summary.
                if (stoppedByUser) {
                    userMessages.post(summary(joinedSoFar, total, floodLimited = false), UserMessageBus.Severity.Info)
                }
                throw e
            } finally {
                joinJob = null
            }
        }
    }

    /** Stop a signed-in import between channels; already joined channels stay joined. */
    fun stopJoining() {
        stoppedByUser = true
        joinJob?.cancel()
    }

    private fun summary(joined: Int, total: Int, floodLimited: Boolean): String = when {
        floodLimited -> res.getQuantityString(R.plurals.channel_import_flood_limited, total, joined, total)
        joined == total -> res.getQuantityString(R.plurals.channel_import_joined, joined, joined)
        else -> res.getQuantityString(R.plurals.channel_import_joined_partial, total, joined, total)
    }

    private fun severityFor(result: JoinRunResult): UserMessageBus.Severity =
        if (result.floodLimited || result.failed.isNotEmpty()) UserMessageBus.Severity.Warning
        else UserMessageBus.Severity.Info

    /** The list an export in [mode] would write, sorted. */
    suspend fun exportList(mode: TransferMode): ExportList = when (mode) {
        TransferMode.Guest -> ExportList(webSubscriptions.subscriptions.first().sorted().toImmutableList(), 0)
        TransferMode.Account -> {
            val ids = posts.subscribedChatIds()
            val names = ids.mapNotNull { channels.publicUsername(it)?.lowercase() }.distinct().sorted()
            ExportList(names.toImmutableList(), skippedPrivate = ids.size - names.size)
        }
    }

    /**
     * Post the "N private channels were left out" note after an export of [list], when
     * there were any — they have no public link to write down.
     */
    fun reportSkipped(list: ExportList) {
        if (list.skippedPrivate <= 0) return
        userMessages.post(
            res.getQuantityString(R.plurals.channel_export_skipped_private, list.skippedPrivate, list.skippedPrivate),
            UserMessageBus.Severity.Info,
        )
    }

    /**
     * Write the [mode] list to the document at [uri] off the main thread, then report the
     * outcome. Runs in the app scope with a [NonCancellable] write, mirroring the post-archive
     * export: leaving the screen must not leave the file cut off. A failed write deletes the
     * partial document when the provider allows it.
     */
    fun writeExport(resolver: ContentResolver, uri: Uri, mode: TransferMode) {
        appScope.launch {
            val list = exportList(mode)
            val ok = withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val out = resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("No output stream for the export document")
                    out.use { it.write(SubscriptionListFormat.format(list.usernames).toByteArray(Charsets.UTF_8)) }
                    true
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.w(TAG, "export: writing $uri failed", e)
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                    false
                }
            }
            if (ok) {
                userMessages.post(
                    res.getQuantityString(R.plurals.channel_export_saved, list.usernames.size, list.usernames.size),
                    UserMessageBus.Severity.Info,
                )
                reportSkipped(list)
            } else {
                userMessages.post(res.getString(R.string.channel_export_failed))
            }
        }
    }

    /** Logout: drop the sheet and stop a signed-in import (its scope is cancelled anyway). */
    fun clear() {
        stoppedByUser = false
        joinJob?.cancel()
        joinJob = null
        _sheet.value = null
    }

    private fun readCapped(resolver: ContentResolver, uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException("No input stream for $uri")
        return input.use { stream ->
            val buffer = CharArray(READ_CHUNK)
            val out = StringBuilder()
            InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                while (out.length < SubscriptionListFormat.MAX_IMPORT_CHARS) {
                    val n = reader.read(buffer, 0, minOf(buffer.size, SubscriptionListFormat.MAX_IMPORT_CHARS - out.length))
                    if (n < 0) break
                    out.appendRange(buffer, 0, n)
                }
            }
            out.toString()
        }
    }

    private companion object {
        const val TAG = "SubscriptionTransfer"
        const val READ_CHUNK = 8 * 1024
    }
}
