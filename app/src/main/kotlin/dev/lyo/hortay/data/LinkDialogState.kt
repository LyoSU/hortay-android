package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for link-confirmation dialogs. Two surfaces:
 *
 *  - **Masked-link anti-phishing dialog** ([maskedLink]) — fired by `LinkAwareScaffold`
 *    when a `[visible text](actual URL)` span resolves to a non-Telegram URL. The
 *    visible text vs destination mismatch is the anti-phishing signal; the dialog
 *    bolds the destination host so the user reviews where they're really going.
 *
 *  - **Chat-invite preview dialog** ([invitePreview]) — fired by `MainScaffold`'s
 *    deep-link collector for `t.me/+abc` links. TDLib's `CheckChatInviteLink`
 *    populates a [ChatInvitePreview]; on the user's Join confirmation we run
 *    `JoinChatByInviteLink` and route to the new chat.
 *
 * Why on the graph (instead of `rememberSaveable` in the scaffold): the writeback
 * happens from a suspending call (`HortayUriHandler.openUri` → resolver) launched
 * into the app scope, which outlives any single composition. A saveable inside the
 * composition is packed up at rotation BEFORE the suspending call returns — the
 * eventual write lands in thin air and the rotated composition starts with `null`,
 * silently dropping the dialog. A MutableStateFlow on the long-lived graph survives
 * configuration changes naturally, and the scaffold's `collectAsStateWithLifecycle`
 * delivers the value on the next composition. Process-death is NOT preserved: an
 * in-flight link confirmation that survives a Doze kill is almost certainly user
 * abandonment.
 */
class LinkDialogState {

    private val _maskedLink = MutableStateFlow<String?>(null)
    val maskedLink: StateFlow<String?> = _maskedLink.asStateFlow()

    private val _invitePreview = MutableStateFlow<ChatInvitePreview?>(null)
    val invitePreview: StateFlow<ChatInvitePreview?> = _invitePreview.asStateFlow()

    fun showMaskedLink(url: String) { _maskedLink.value = url }
    fun dismissMaskedLink() { _maskedLink.value = null }

    fun showInvitePreview(preview: ChatInvitePreview) { _invitePreview.value = preview }
    fun dismissInvitePreview() { _invitePreview.value = null }
}
