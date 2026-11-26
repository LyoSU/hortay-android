@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.RatePromptPolicy
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.theme.HortaySpacing

/**
 * One-time, quiet request to rate the app on Google Play. Deliberately plain: a title, one
 * line of body, a primary button and a text button, laid out like [FeedOrderOnboardingSheet].
 * No in-app review library: the app stays free of proprietary dependencies, so the primary
 * button simply opens the store listing.
 *
 * [onRate] and [onDismiss] both end the prompt for good; the host owns that bookkeeping.
 */
@Composable
fun RatePromptSheet(
    onRate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberExpandedSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HortaySpacing.xl)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.rate_prompt_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(HortaySpacing.md))
            Text(
                text = stringResource(R.string.rate_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(ACTIONS_GAP))
            Button(
                onClick = onRate,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BUTTON_MIN_HEIGHT),
            ) {
                Text(stringResource(R.string.rate_prompt_rate))
            }
            Spacer(Modifier.height(HortaySpacing.sm))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BUTTON_MIN_HEIGHT),
            ) {
                Text(stringResource(R.string.rate_prompt_later))
            }
            Spacer(Modifier.height(HortaySpacing.xl))
        }
    }
}

/**
 * Opens this package's Play Store listing: the Play app when present, otherwise the web
 * listing. Deliberately bypasses the in-app link resolver, which would try to treat the
 * URL as a Telegram link first.
 */
fun openPlayStoreListing(context: Context) {
    val id = context.packageName
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
        .setPackage(RatePromptPolicy.PLAY_STORE_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(web) }
    }
}

private val BUTTON_MIN_HEIGHT = 48.dp

/** Gap between the content and the actions, as in the feed-order sheet. */
private val ACTIONS_GAP = 20.dp
