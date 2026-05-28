package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import org.drinkless.tdlib.TdApi

/**
 * TDLib delivers profile accent colours as 0xRRGGBB ints (no alpha). The UI needs
 * opaque ARGB, so OR in a full alpha byte. Top-level so the conversion lives in one
 * place for both the background gradient and the ring.
 *
 * The mask guards against any non-zero high byte; TDLib sends 0xRRGGBB but we don't rely on it.
 */
internal fun Int.rgbToOpaqueArgb(): Int = 0xFF000000.toInt() or (this and 0x00FFFFFF)

/**
 * Background-gradient colours for the profile photo backdrop, as opaque ARGB.
 * Picks the dark- or light-theme variant; empty when TDLib supplied none (the
 * caller then renders the brand fallback brush).
 */
internal fun TdApi.ProfileAccentColor.backgroundArgb(dark: Boolean): IntArray {
    val variant = if (dark) darkThemeColors else lightThemeColors
    return (variant?.backgroundColors ?: IntArray(0)).map { it.rgbToOpaqueArgb() }.toIntArray()
}

/**
 * Avatar-ring colours, as opaque ARGB. Prefers `storyColors` (the gradient Telegram
 * draws around the photo); falls back to `paletteColors` when the user's accent has
 * no story gradient. Empty when neither is set.
 */
internal fun TdApi.ProfileAccentColor.ringArgb(dark: Boolean): IntArray {
    val variant = if (dark) darkThemeColors else lightThemeColors
    val story = variant?.storyColors ?: IntArray(0)
    val source = if (story.isNotEmpty()) story else (variant?.paletteColors ?: IntArray(0))
    return source.map { it.rgbToOpaqueArgb() }.toIntArray()
}

/**
 * Read-side contract the Compose layer consumes through `LocalProfileAccent`. Returns
 * opaque-ARGB colour arrays for a given accent id, or null when the user hasn't set a
 * colour (`id == -1`), the id is unknown, or TDLib supplied no colours — the caller
 * then paints the brand fallback brush.
 */
interface ProfileAccentResolver {
    fun backgroundArgb(accentId: Int, dark: Boolean): IntArray?
    fun ringArgb(accentId: Int, dark: Boolean): IntArray?

    /** Always-fallback resolver for guest mode, auth, previews and tests. */
    companion object Empty : ProfileAccentResolver {
        override fun backgroundArgb(accentId: Int, dark: Boolean): IntArray? = null
        override fun ringArgb(accentId: Int, dark: Boolean): IntArray? = null
    }
}

/**
 * Process-singleton cache of TDLib's profile accent palette. TDLib pushes the whole
 * palette once, early, via [TdApi.UpdateProfileAccentColors] (not per-request), so we
 * just keep the last snapshot in an [AtomicReference] and look colours up by id.
 *
 * Cleared on `loggedOut` (hard rule: every session-scoped state holder clears on
 * logout) so a fresh sign-in on another account can't read the previous palette.
 */
class ProfileAccentRegistry : ProfileAccentResolver {
    private val byId = AtomicReference<Map<Int, TdApi.ProfileAccentColor>>(emptyMap())

    /** Collect the one-shot palette update and the logout signal. Call once at graph build. */
    fun bind(
        updates: SharedFlow<TdApi.Update>,
        loggedOut: SharedFlow<Unit>,
        scope: CoroutineScope,
    ) {
        scope.launch {
            updates.collect { u -> if (u is TdApi.UpdateProfileAccentColors) ingest(u.colors) }
        }
        scope.launch { loggedOut.collect { clear() } }
    }

    fun ingest(colors: Array<TdApi.ProfileAccentColor>) {
        byId.set(colors.associateBy { it.id })
    }

    fun clear() {
        byId.set(emptyMap())
    }

    private fun color(accentId: Int): TdApi.ProfileAccentColor? =
        if (accentId < 0) null else byId.get()[accentId]

    override fun backgroundArgb(accentId: Int, dark: Boolean): IntArray? =
        color(accentId)?.backgroundArgb(dark)?.takeIf { it.isNotEmpty() }

    override fun ringArgb(accentId: Int, dark: Boolean): IntArray? =
        color(accentId)?.ringArgb(dark)?.takeIf { it.isNotEmpty() }
}
