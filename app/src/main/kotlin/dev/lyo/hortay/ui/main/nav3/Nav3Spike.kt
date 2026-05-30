@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.lyo.hortay.ui.main.nav3

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay

/**
 * Stage-0 throwaway spike for the Navigation 3 migration (see the approved plan).
 *
 * NOT wired into [dev.lyo.hortay.MainActivity] — its only job is to force the compiler to
 * resolve the EXACT API surface the migration depends on, so a name/shape mismatch surfaces
 * at `:app:compileDebugKotlin` instead of mid-migration. It exercises every primitive the
 * real wiring needs: a [NavKey] back stack as a [mutableStateListOf], [NavDisplay] +
 * [entryProvider]/[entry], [SharedTransitionLayout] + `sharedBounds` for the container-transform
 * morph, and [LocalNavAnimatedContentScope] for the per-entry AnimatedVisibilityScope.
 *
 * Deleted in the cleanup stage once the production NavDisplay is in place.
 */
private sealed interface SpikeKey : NavKey

private data object SpikeList : SpikeKey

private data class SpikeDetail(val id: Int) : SpikeKey

@Composable
internal fun Nav3Spike() {
    val backStack = remember { mutableStateListOf<NavKey>(SpikeList) }
    SharedTransitionLayout {
        val sharedScope = this
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<SpikeList> {
                    val animatedScope = LocalNavAnimatedContentScope.current
                    with(sharedScope) {
                        Box(
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "spike-card"),
                                    animatedVisibilityScope = animatedScope,
                                )
                                .size(120.dp)
                                .clickable { backStack.add(SpikeDetail(1)) },
                        ) {
                            Text("open")
                        }
                    }
                }
                entry<SpikeDetail> { key ->
                    val animatedScope = LocalNavAnimatedContentScope.current
                    with(sharedScope) {
                        Box(
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "spike-card"),
                                    animatedVisibilityScope = animatedScope,
                                )
                                .fillMaxSize()
                                .clickable { backStack.removeLastOrNull() },
                        ) {
                            Text("detail ${key.id}")
                        }
                    }
                }
            },
        )
    }
}
