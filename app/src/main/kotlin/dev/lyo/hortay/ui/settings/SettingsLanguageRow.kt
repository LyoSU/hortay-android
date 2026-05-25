@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import dev.lyo.hortay.R
import dev.lyo.hortay.data.LocaleStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Per-app language row. Reads / writes through [LocaleStore], which bridges Hortay's
 * Compose-only, ComponentActivity-based setup to Android's per-app language picker.
 *
 * On API 33+ the platform [android.app.LocaleManager] is the source of truth — the
 * system Settings → Apps → Hortay → Language picker writes here too, and the system
 * recreates the activity stack on change.
 *
 * On API 26-32 the choice is persisted in a small SharedPrefs file and the activity
 * is recreated explicitly so [dev.lyo.hortay.MainActivity.attachBaseContext] can wrap
 * the base context with the new [java.util.Locale] before resources resolve.
 *
 * The locales offered must stay aligned with `res/xml/locales_config.xml`. Append a new
 * [LanguageEntry] to [LANGUAGES] only after the matching `values-XX/strings.xml` exists,
 * otherwise the picker shows a language that resolves back to the default at runtime.
 *
 * Why not [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales]: it dispatches
 * through an internal `sActivityDelegates` set populated only by `AppCompatActivity`, so
 * with a plain `ComponentActivity` (ARCHITECTURE.md pins us here) the call is a no-op on every
 * API level — symptom was "pick a language, dialog dismisses, nothing else happens".
 */
private data class LanguageEntry(val tag: String, @StringRes val labelRes: Int)

private val LANGUAGES: ImmutableList<LanguageEntry> = persistentListOf(
    LanguageEntry("ar", R.string.settings_language_summary_ar),
    LanguageEntry("de", R.string.settings_language_summary_de),
    LanguageEntry("en", R.string.settings_language_summary_en),
    LanguageEntry("es", R.string.settings_language_summary_es),
    LanguageEntry("fa", R.string.settings_language_summary_fa),
    LanguageEntry("fr", R.string.settings_language_summary_fr),
    // Tag is BCP47 `id`; Android resource resolver maps it to `values-in/`
    // automatically (legacy ISO 639-1 quirk — see locales_config.xml).
    LanguageEntry("id", R.string.settings_language_summary_id),
    LanguageEntry("it", R.string.settings_language_summary_it),
    LanguageEntry("pl", R.string.settings_language_summary_pl),
    LanguageEntry("pt-BR", R.string.settings_language_summary_pt_br),
    LanguageEntry("ru", R.string.settings_language_summary_ru),
    LanguageEntry("tr", R.string.settings_language_summary_tr),
    LanguageEntry("uk", R.string.settings_language_summary_uk),
)

@Composable
internal fun LanguageRow(index: Int, count: Int) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    // Re-read on every dialog open so the row reflects an out-of-band change (e.g. the
    // user flipped the language via the system per-app picker on API 33+ and came back).
    val activeTag = remember(showDialog) { LocaleStore.read(context) }
    val matchedRes = LANGUAGES.firstOrNull { it.tag == activeTag }?.labelRes
    val summary = if (matchedRes != null) {
        stringResource(matchedRes)
    } else {
        stringResource(R.string.settings_language_summary_system)
    }
    SettingsRow(
        symbol = "translate",
        title = stringResource(R.string.settings_language),
        subtitle = summary,
        chevron = true,
        index = index,
        count = count,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        LanguageDialog(
            activeTag = activeTag,
            onDismiss = { showDialog = false },
            onSelect = { tag ->
                showDialog = false
                if (tag == activeTag) return@LanguageDialog
                LocaleStore.write(context, tag)
                // On API 33+ the platform LocaleManager recreates the activity stack
                // itself; on older API levels we have to do it so attachBaseContext
                // re-wraps with the new locale.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    (context as? Activity)?.recreate()
                }
            },
        )
    }
}

@Composable
private fun LanguageDialog(
    activeTag: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_logout_cancel))
            }
        },
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LanguageOption(
                    label = stringResource(R.string.settings_language_summary_system),
                    selected = activeTag == null,
                    onClick = { onSelect(null) },
                )
                LANGUAGES.forEach { entry ->
                    LanguageOption(
                        label = stringResource(entry.labelRes),
                        selected = activeTag == entry.tag,
                        onClick = { onSelect(entry.tag) },
                    )
                }
            }
        },
    )
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
