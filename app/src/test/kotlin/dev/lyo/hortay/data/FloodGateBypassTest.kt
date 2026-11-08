package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FloodGateBypassTest {

    @Test
    fun `file transfer control skips the flood gate`() {
        assertTrue(TdClient.isOfflineRequest(TdApi.DownloadFile(1, 1, 0, 0, false)))
        assertTrue(TdClient.isOfflineRequest(TdApi.CancelDownloadFile(1, true)))
    }

    @Test
    fun `server history calls still wait on the flood gate`() {
        assertFalse(TdClient.isOfflineRequest(TdApi.GetChatHistory(1, 0, 0, 10, false)))
    }
}
