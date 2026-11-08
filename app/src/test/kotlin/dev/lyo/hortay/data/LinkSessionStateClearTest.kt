package dev.lyo.hortay.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Logout hooks for the link plumbing: nothing from the old session survives [clear]. */
class LinkSessionStateClearTest {

    @Test
    fun `link dialog state clear drops both pending dialogs`() {
        val state = LinkDialogState()
        state.showMaskedLink("https://example.com")
        state.clear()
        assertNull(state.maskedLink.value)
        assertNull(state.invitePreview.value)
    }

    @Test
    fun `router clear discards buffered links but keeps accepting new ones`() = runTest {
        val router = DeepLinkRouter()
        router.submit(DeepLink.PrivateChannel(chatId = -1L, serverPostId = null, originalUrl = "old"))
        router.submit(DeepLink.External("old2"))
        router.clear()
        val fresh = DeepLink.External("new")
        router.submit(fresh)
        assertEquals(fresh, withTimeoutOrNull(1_000) { router.events.first() })
    }
}
