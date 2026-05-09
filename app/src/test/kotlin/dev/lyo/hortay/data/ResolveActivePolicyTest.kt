package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the metered-clamp behaviour of [resolveActivePolicy]. The clamp is the
 * fix for "feed тупить on 4G" — TDLib's mobile per-DC slot pool is small,
 * full-file animation/video prefetch holds slots for seconds while the user
 * looks at a photo trying to grab the same slot. The clamp keeps photo
 * prefetch (30-300 KB, one chunk) and turns videos/animations off until we
 * ship streaming.
 */
class ResolveActivePolicyTest {

    private val customSettings = AutoDownloadSettings(
        onWifi = AutoDownloadPolicy(
            photos = true,
            videos = true,
            videoMaxBytes = 50L * 1024 * 1024,
            animations = true,
        ),
        onMobile = AutoDownloadPolicy(
            photos = true,
            videos = true,
            videoMaxBytes = 10L * 1024 * 1024,
            animations = true,
        ),
        onRoaming = AutoDownloadPolicy(
            photos = true,
            videos = true,
            videoMaxBytes = 2L * 1024 * 1024,
            animations = true,
        ),
    )

    @Test
    fun `wifi keeps user choice intact`() {
        val resolved = resolveActivePolicy(HortayNetworkType.Wifi, customSettings)!!
        assertEquals(customSettings.onWifi, resolved)
    }

    @Test
    fun `mobile clamps videos and animations regardless of user toggle`() {
        val resolved = resolveActivePolicy(HortayNetworkType.Mobile, customSettings)!!
        assertTrue(resolved.photos)
        assertEquals(false, resolved.videos)
        assertEquals(false, resolved.animations)
        // Size cap is preserved on the policy object even though videos=false
        // overrides any size check downstream — exposing a stale cap to tests
        // would be misleading in a future refactor.
        assertEquals(customSettings.onMobile.videoMaxBytes, resolved.videoMaxBytes)
    }

    @Test
    fun `roaming clamps videos and animations same as mobile`() {
        val resolved = resolveActivePolicy(HortayNetworkType.Roaming, customSettings)!!
        assertTrue(resolved.photos)
        assertEquals(false, resolved.videos)
        assertEquals(false, resolved.animations)
    }

    @Test
    fun `none returns null so caller can short-circuit`() {
        assertNull(resolveActivePolicy(HortayNetworkType.None, customSettings))
    }

    @Test
    fun `metered clamp does not enable photos that user disabled`() {
        // The clamp only forces videos / animations off — it must not flip a
        // user-disabled `photos` ON. Otherwise a privacy-conscious "no
        // background data on cellular" user would suddenly see thumbs auto-
        // downloading after the clamp shipped.
        val photosOffOnMobile = customSettings.copy(
            onMobile = AutoDownloadPolicy(
                photos = false,
                videos = false,
                videoMaxBytes = 0L,
                animations = false,
            ),
        )
        val resolved = resolveActivePolicy(HortayNetworkType.Mobile, photosOffOnMobile)!!
        assertEquals(false, resolved.photos)
    }

    @Test
    fun `default settings on mobile are equivalent to clamped`() {
        // Lock in the post-shipping default: out-of-the-box, the resolved
        // mobile policy is photos-only, regardless of the persisted defaults.
        val resolved = resolveActivePolicy(HortayNetworkType.Mobile, AutoDownloadSettings.DEFAULT)!!
        assertTrue(resolved.photos)
        assertEquals(false, resolved.videos)
        assertEquals(false, resolved.animations)
    }
}
