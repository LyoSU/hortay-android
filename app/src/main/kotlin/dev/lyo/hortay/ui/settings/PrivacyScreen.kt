@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.R
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * Privacy & safety sub-screen. "Invisible reading" toggle (TDLib mode only) + the two
 * CSAE policy links (kept discoverable for Play review). Pushed from the Main page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyScreen(
    settings: SettingsStore,
    contentPadding: PaddingValues,
    privacyTogglesAvailable: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.settings_section_privacy),
                size = HortayTopBarSize.Large,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 24.dp,
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (privacyTogglesAvailable) {
                val hideOnline by settings.hideOnlineStatus.collectAsStateWithLifecycle(false)
                SectionLabel(stringResource(R.string.settings_section_privacy))
                HideOnlineStatusRow(
                    enabled = hideOnline,
                    onToggle = { next -> if (next != hideOnline) scope.launch { settings.setHideOnlineStatus(next) } },
                )
                Spacer(Modifier.height(8.dp))
            }
            SectionLabel(stringResource(R.string.settings_section_safety))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsRow(
                    symbol = "child_care",
                    title = stringResource(R.string.settings_safety_child_policy_title),
                    subtitle = stringResource(R.string.settings_safety_child_policy_subtitle),
                    chevron = true,
                    index = 0,
                    count = 2,
                    onClick = { openPolicy(context, uriHandler, BuildConfig.CHILD_SAFETY_POLICY_URL) },
                )
                SettingsRow(
                    symbol = "shield",
                    title = stringResource(R.string.settings_safety_privacy_title),
                    subtitle = stringResource(R.string.settings_safety_privacy_subtitle),
                    chevron = true,
                    index = 1,
                    count = 2,
                    onClick = { openPolicy(context, uriHandler, BuildConfig.PRIVACY_POLICY_URL) },
                )
            }
        }
    }
}

private fun openPolicy(
    context: android.content.Context,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    url: String,
) {
    try {
        androidx.browser.customtabs.CustomTabsIntent.Builder().build()
            .launchUrl(context, android.net.Uri.parse(url))
    } catch (_: android.content.ActivityNotFoundException) {
        uriHandler.openUri(url)
    }
}
