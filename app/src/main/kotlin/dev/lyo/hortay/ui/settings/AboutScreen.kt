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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol

/** About sub-screen: author attribution + source link, app language, version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.settings_section_about),
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
            SectionLabel(stringResource(R.string.settings_section_author))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsRow(
                    symbol = "campaign",
                    title = stringResource(R.string.settings_author_channel_title),
                    subtitle = "@$AUTHOR_CHANNEL_HANDLE",
                    chevron = true, index = 0, count = 3,
                    onClick = { uriHandler.openUri("https://t.me/$AUTHOR_CHANNEL_HANDLE") },
                )
                SettingsRow(
                    symbol = "person",
                    title = stringResource(R.string.settings_author_developer_title),
                    subtitle = "@$AUTHOR_DEVELOPER_HANDLE",
                    chevron = true, index = 1, count = 3,
                    onClick = { uriHandler.openUri("https://t.me/$AUTHOR_DEVELOPER_HANDLE") },
                )
                SettingsRow(
                    symbol = "code",
                    title = stringResource(R.string.settings_author_source_title),
                    subtitle = AUTHOR_SOURCE_LABEL,
                    chevron = true, index = 2, count = 3,
                    onClick = { uriHandler.openUri(AUTHOR_SOURCE_URL) },
                )
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_about))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                LanguageRow(index = 0, count = 2)
                SettingsRow(
                    symbol = "info",
                    title = stringResource(R.string.settings_version),
                    subtitle = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                    index = 1, count = 2,
                )
            }
        }
    }
}

// ---- Author attribution ------------------------------------------------------

private const val AUTHOR_CHANNEL_HANDLE = "lyblog"
private const val AUTHOR_DEVELOPER_HANDLE = "lydev"
private const val AUTHOR_SOURCE_URL = "https://github.com/LyoSU/hortay-android"
private const val AUTHOR_SOURCE_LABEL = "github.com/LyoSU/hortay-android"
