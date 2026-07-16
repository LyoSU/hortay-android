@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.proxy.AddResult
import dev.lyo.hortay.data.proxy.ProxyDraft
import dev.lyo.hortay.data.proxy.ProxyHealth
import dev.lyo.hortay.data.proxy.ProxyKind
import dev.lyo.hortay.data.proxy.ProxyRepository
import dev.lyo.hortay.data.proxy.ProxyUi
import dev.lyo.hortay.data.proxy.TestResult
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.LocalHortayStatusColors
import kotlinx.coroutines.launch

/**
 * Proxy management — self-contained so it can be hosted both as a Settings sub-page (TDLib mode)
 * and directly from [dev.lyo.hortay.ui.auth.AuthScreen] before sign-in (proxy methods work
 * pre-authorization; see [ProxyRepository] KDoc). All proxy state and persistence live in TDLib;
 * this screen is a thin view over [ProxyRepository.state].
 */
@Composable
internal fun ProxyScreen(
    repo: ProxyRepository,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var showAdd by remember { mutableStateOf(false) }
    val active = state.entries.firstOrNull { it.isEnabled }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.proxy_title),
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
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Symbol(
                            name = "add",
                            contentDescription = stringResource(R.string.proxy_add),
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
            // Master toggle: enabled only when there is at least one proxy to switch on. When on,
            // the supporting line names the active server so the state is legible at a glance.
            SegmentedListItem(
                onClick = {},
                shapes = ListItemDefaults.segmentedShapes(
                    index = 0,
                    count = 1,
                    defaultShapes = ListItemDefaults.shapes(),
                ),
                leadingContent = {
                    Symbol(
                        name = "vpn_key",
                        size = 24.dp,
                        tint = if (state.useProxy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                supportingContent = {
                    Text(
                        text = if (active != null) {
                            stringResource(R.string.proxy_active_summary, "${active.server}:${active.port}")
                        } else {
                            stringResource(R.string.proxy_use_toggle_subtitle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.useProxy,
                        enabled = state.entries.isNotEmpty(),
                        onCheckedChange = { next ->
                            scope.launch {
                                if (next) {
                                    state.entries.firstOrNull()?.let { repo.enable(it.id) }
                                } else {
                                    repo.disableProxy()
                                }
                            }
                        },
                    )
                },
                content = {
                    Text(
                        text = stringResource(R.string.proxy_use_toggle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )

            if (state.entries.isEmpty()) {
                EmptyProxyState(onAdd = { showAdd = true })
            } else {
                SectionLabel(
                    pluralStringResource(
                        R.plurals.proxy_count,
                        state.entries.size,
                        state.entries.size,
                    ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    state.entries.forEachIndexed { index, entry ->
                        ProxyRow(
                            entry = entry,
                            index = index,
                            count = state.entries.size,
                            onActivate = { scope.launch { repo.enable(entry.id) } },
                            onTest = { scope.launch { repo.ping(entry.id) } },
                            onRemove = { scope.launch { repo.remove(entry.id) } },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddProxySheet(
            onDismiss = { showAdd = false },
            onAddLink = { link -> repo.addFromLink(link) },
            onAddManual = { draft, enable -> repo.addManual(draft, enable) },
            onTest = { draft -> repo.test(draft) },
            poolEmpty = state.entries.isEmpty(),
        )
    }
}

@Composable
private fun EmptyProxyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Symbol(
            name = "vpn_key",
            size = 56.dp,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Text(
            text = stringResource(R.string.proxy_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(onClick = onAdd) {
            Symbol(name = "add", size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.proxy_add))
        }
    }
}

@Composable
private fun ProxyRow(
    entry: ProxyUi,
    index: Int,
    count: Int,
    onActivate: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    SegmentedListItem(
        onClick = onActivate,
        shapes = ListItemDefaults.segmentedShapes(
            index = index,
            count = count,
            defaultShapes = ListItemDefaults.shapes(),
        ),
        leadingContent = { RadioButton(selected = entry.isEnabled, onClick = onActivate) },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(healthColor(entry.health)),
                )
                Text(
                    text = "${entry.kind.protocolLabel()} · ${healthLabel(entry.health)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onTest) {
                    Symbol(
                        name = "network_check",
                        contentDescription = stringResource(R.string.proxy_test),
                        size = 20.dp,
                    )
                }
                IconButton(onClick = onRemove) {
                    Symbol(
                        name = "delete",
                        contentDescription = stringResource(R.string.proxy_remove),
                        size = 20.dp,
                    )
                }
            }
        },
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(
                    name = entry.kind.glyph(),
                    size = 18.dp,
                    tint = if (entry.isEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "${entry.server}:${entry.port}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (entry.isEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        },
    )
}

@Composable
private fun AddProxySheet(
    onDismiss: () -> Unit,
    onAddLink: suspend (String) -> AddResult,
    onAddManual: suspend (ProxyDraft, Boolean) -> AddResult,
    onTest: suspend (ProxyDraft) -> TestResult,
    poolEmpty: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var link by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ProxyTypeOption.Socks5) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var httpOnly by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    val notProxy = stringResource(R.string.proxy_error_not_link)
    // Animated dismiss: slide the sheet out before removing it (framework only animates the
    // swipe / scrim path itself — programmatic closes must hide() first).
    suspend fun closeAnimated() {
        sheetState.hide()
        onDismiss()
    }
    fun showResult(result: AddResult) {
        when (result) {
            AddResult.Success -> scope.launch { closeAnimated() }
            AddResult.NotAProxyLink -> error = notProxy
            is AddResult.Error -> error = result.message
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.proxy_add),
                style = MaterialTheme.typography.titleLarge,
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // Paste a Telegram proxy link — parsed entirely by TDLib (getInternalLinkType).
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; error = null; testResult = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.proxy_link_label)) },
                placeholder = { Text(stringResource(R.string.proxy_link_placeholder)) },
                leadingIcon = { Symbol(name = "vpn_key", size = 20.dp) },
                singleLine = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        showResult(onAddLink(link.trim()))
                        busy = false
                    }
                },
                enabled = !busy && link.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.proxy_add_from_link)) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.proxy_manual_label))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProxyTypeOption.entries.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = type == option,
                        onClick = { type = option; error = null; testResult = null },
                        shape = SegmentedButtonDefaults.itemShape(i, ProxyTypeOption.entries.size),
                        label = { Text(option.label) },
                    )
                }
            }
            OutlinedTextField(
                value = server,
                onValueChange = { server = it; error = null; testResult = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.proxy_field_server)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { input -> port = input.filter { it.isDigit() }; error = null; testResult = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.proxy_field_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            when (type) {
                ProxyTypeOption.Mtproto -> OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; error = null; testResult = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.proxy_field_secret)) },
                    singleLine = true,
                )
                else -> {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; error = null; testResult = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.proxy_field_username)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null; testResult = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.proxy_field_password)) },
                        singleLine = true,
                    )
                    if (type == ProxyTypeOption.Http) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = httpOnly, onCheckedChange = { httpOnly = it })
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.proxy_field_http_only))
                        }
                    }
                }
            }

            val portValue = port.toIntOrNull()
            val manualValid = server.isNotBlank() && portValue != null && portValue in 1..65535 &&
                (type != ProxyTypeOption.Mtproto || secret.isNotBlank())
            fun draft(): ProxyDraft {
                val kind = when (type) {
                    ProxyTypeOption.Socks5 -> ProxyKind.Socks5(username.trim(), password)
                    ProxyTypeOption.Http -> ProxyKind.Http(username.trim(), password, httpOnly)
                    ProxyTypeOption.Mtproto -> ProxyKind.Mtproto(secret.trim())
                }
                return ProxyDraft(server.trim(), portValue ?: 0, kind)
            }

            testResult?.let { r ->
                when (r) {
                    is TestResult.Ok -> Text(
                        text = stringResource(R.string.proxy_test_reachable),
                        color = LocalHortayStatusColors.current.success,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is TestResult.Error -> Text(
                        text = r.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true; testResult = null
                            testResult = onTest(draft())
                            busy = false
                        }
                    },
                    enabled = !busy && manualValid,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.proxy_test)) }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            showResult(onAddManual(draft(), poolEmpty))
                            busy = false
                        }
                    },
                    enabled = !busy && manualValid,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.proxy_add)) }
            }

            TextButton(
                onClick = { scope.launch { closeAnimated() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

private enum class ProxyTypeOption(val label: String) {
    Socks5("SOCKS5"),
    Http("HTTP"),
    Mtproto("MTProto"),
}

private fun ProxyKind.protocolLabel(): String = when (this) {
    is ProxyKind.Socks5 -> "SOCKS5"
    is ProxyKind.Http -> "HTTP"
    is ProxyKind.Mtproto -> "MTProto"
}

/** Per-type leading glyph: MTProto → key, SOCKS5 → LAN, HTTP → DNS/server. */
private fun ProxyKind.glyph(): String = when (this) {
    is ProxyKind.Mtproto -> "vpn_key"
    is ProxyKind.Socks5 -> "lan"
    is ProxyKind.Http -> "dns"
}

@Composable
private fun healthLabel(health: ProxyHealth): String = when (health) {
    ProxyHealth.Unknown -> stringResource(R.string.proxy_health_unknown)
    ProxyHealth.Checking -> stringResource(R.string.proxy_health_checking)
    is ProxyHealth.Reachable -> stringResource(R.string.proxy_health_latency, health.latencyMs)
    ProxyHealth.Unreachable -> stringResource(R.string.proxy_health_unreachable)
}

/** Status-dot colour. Latency tiers use theme-aware but palette-independent signal colours
 *  (green/amber/orange, from [LocalHortayStatusColors]) so reachability reads at a glance the
 *  same way in light or dark mode, regardless of the active M3 (possibly dynamic) palette;
 *  failure falls back to the theme error colour. */
@Composable
private fun healthColor(health: ProxyHealth): Color = when (health) {
    ProxyHealth.Unknown -> MaterialTheme.colorScheme.outline
    ProxyHealth.Checking -> MaterialTheme.colorScheme.tertiary
    ProxyHealth.Unreachable -> MaterialTheme.colorScheme.error
    is ProxyHealth.Reachable -> {
        val status = LocalHortayStatusColors.current
        when {
            health.latencyMs < 150 -> status.success
            health.latencyMs < 500 -> status.caution
            else -> status.degraded
        }
    }
}
