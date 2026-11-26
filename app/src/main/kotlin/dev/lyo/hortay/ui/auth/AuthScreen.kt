@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.auth

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.Country
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.openExternalUrl
import dev.lyo.hortay.ui.settings.AUTHOR_SOURCE_URL
import dev.lyo.hortay.ui.settings.ProxyScreen
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Auth flow entry point, in two steps before TDLib's own stages take over:
 *   - **Welcome** — the brand hero, then two equal ways in: sign in with Telegram, or read
 *     public channels without an account (guest mode is a first choice, not fine print).
 *   - **Phone** — the number form, reached only after the user chose to sign in.
 * TDLib sits in WaitPhone for both, so which one shows is UI state (see [authStep]).
 * The code, password and error stages follow with their own content under the same
 * header (back where it is legal, the small fan mark, the proxy entry).
 *
 * Reads two signals from [TdClient]:
 *   - [TdClient.authError] — transient errors from the last submit, kept *separate* from
 *     the stage so a rejected code leaves the user on the same form with their input
 *     intact and an inline message under the field.
 *   - the [stage] passed in — where TDLib's authorization state machine is.
 *
 * The [graph] dependency gives us [AppGraph.countries]; the phone form lazy-loads the
 * country catalogue on first composition (`countries.load()` inside `LaunchedEffect`).
 */
@Composable
fun AuthScreen(graph: AppGraph, stage: AuthStage) {
    val client = graph.tdClient
    val authError by client.authError.collectAsStateWithLifecycle()
    // Proxy can be configured before sign-in — addProxy works pre-authorization (tdlib/td#300),
    // so a user in a blocked network can reach Telegram to authenticate at all.
    var showProxy by remember { mutableStateOf(false) }
    // Welcome vs Phone. Saved across rotation; this is a step inside one screen, not the
    // top-level navigation ARCHITECTURE.md keeps out of saved state.
    var signInChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stage) { signInChosen = signInChosenAfter(stage, signInChosen) }
    val step = authStep(stage, signInChosen)
    // Above the steps, so Phone → Welcome → Phone keeps the typed number.
    val draft = rememberSaveable(saver = PhoneDraft.Saver) { PhoneDraft() }
    val hero = rememberAuthHero()
    val scope = rememberCoroutineScope()
    val cancelAttempt: () -> Unit = remember(graph) { { graph.appScope.launch { graph.tdClient.cancelAuth() } } }
    val enterGuest: () -> Unit = remember(graph) { { graph.appScope.launch { graph.guestMode.setGuest(true) } } }
    val openProxy: () -> Unit = remember { { showProxy = true } }
    val chooseSignIn: () -> Unit = remember { { signInChosen = true } }
    val backToWelcome: () -> Unit = remember { { signInChosen = false } }

    // Back. Phone → Welcome follows the finger (predictive back: the form slides and
    // fades a little toward the edge, then the step switch carries it out); code and
    // password abandon the attempt. The flags are exclusive, and both drop while the
    // proxy overlay owns back, so exactly one handler is enabled at a time.
    val back = authBack(step)
    val peek = remember { Animatable(0f) }
    val peekSettle = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(step) { if (step == AuthStep.Phone) peek.snapTo(0f) }
    PredictiveBackHandler(enabled = back == AuthBack.ToWelcome && !showProxy) { events ->
        try {
            events.collect { peek.snapTo(it.progress) }
            signInChosen = false
        } catch (e: CancellationException) {
            scope.launch { peek.animateTo(0f, peekSettle) }
            throw e
        }
    }
    BackHandler(enabled = back == AuthBack.CancelAttempt && !showProxy) { cancelAttempt() }

    // From the window, not from constraints: the keyboard shrinks the height and must not
    // flip a portrait phone into the two-pane layout mid-typing.
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= WIDE_MIN_WIDTH_DP ||
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val steps = @Composable { modifier: Modifier ->
            AuthSteps(
                frame = AuthFrame(step, stage),
                graph = graph,
                authError = authError,
                draft = draft,
                hero = hero,
                wide = wide,
                peek = { peek.value },
                onSignIn = chooseSignIn,
                onGuest = enterGuest,
                onBackToWelcome = backToWelcome,
                onCancelAttempt = cancelAttempt,
                onProxy = openProxy,
                modifier = modifier,
            )
        }
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                HeroPane(hero = hero, modifier = Modifier.weight(1f).fillMaxHeight())
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    steps(Modifier.widthIn(max = AUTH_CONTENT_MAX_WIDTH).fillMaxSize())
                }
            }
        } else {
            // Centres the capped-width column on a portrait tablet narrower than the
            // two-pane threshold; on phones the column already spans the width.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                steps(Modifier.widthIn(max = AUTH_CONTENT_MAX_WIDTH).fillMaxSize())
            }
        }

        if (showProxy) {
            // The auth screen is the root with no back stack, so the system back gesture would
            // finish the Activity. This handler — composed only while the overlay is up, and
            // the only enabled one then — dismisses the proxy screen instead.
            BackHandler { showProxy = false }
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                ProxyScreen(
                    repo = graph.proxyRepository,
                    contentPadding = PaddingValues(),
                    onBack = { showProxy = false },
                )
            }
        }
    }
}

/** What one step renders: its kind, and the TDLib stage it was composed with. */
@Immutable
private data class AuthFrame(val step: AuthStep, val stage: AuthStage)

@Composable
private fun AuthSteps(
    frame: AuthFrame,
    graph: AppGraph,
    authError: String?,
    draft: PhoneDraft,
    hero: AuthHero,
    wide: Boolean,
    peek: () -> Float,
    onSignIn: () -> Unit,
    onGuest: () -> Unit,
    onBackToWelcome: () -> Unit,
    onCancelAttempt: () -> Unit,
    onProxy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Steps go forward and back like pages: a short horizontal slide plus fade, mirrored
    // in RTL. Specs are read here because transitionSpec is not composable. Keyed on the
    // step, so a new payload for the same step (a resent code, Loading → WaitPhone under
    // Welcome) updates in place instead of sliding.
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(
        targetState = frame,
        contentKey = { it.step },
        transitionSpec = {
            val sign = if (targetState.step.ordinal >= initialState.step.ordinal) direction else -direction
            (slideInHorizontally(spatial) { sign * it / STEP_SLIDE_DIVISOR } + fadeIn(effects))
                .togetherWith(slideOutHorizontally(spatial) { -sign * it / STEP_SLIDE_DIVISOR } + fadeOut(effects))
        },
        label = "auth-step",
        modifier = modifier,
    ) { current ->
        when (current.step) {
            AuthStep.Welcome -> WelcomeStep(
                stage = current.stage,
                hero = hero,
                showHero = !wide,
                onSignIn = onSignIn,
                onGuest = onGuest,
                onProxy = onProxy,
            )
            AuthStep.Phone -> PhoneStep(
                graph = graph,
                stage = current.stage,
                draft = draft,
                errorMessage = authError,
                showMark = !wide,
                peek = peek,
                onBack = onBackToWelcome,
                onProxy = onProxy,
            )
            AuthStep.Code, AuthStep.Password, AuthStep.Error, AuthStep.Finishing -> StageStep(
                graph = graph,
                stage = current.stage,
                errorMessage = authError,
                showMark = !wide,
                onCancelAttempt = onCancelAttempt,
                onGuest = onGuest,
                onProxy = onProxy,
            )
        }
    }
}

// ---------- Header ----------

/**
 * One header for every step: back where it is legal, the small fan mark (dropped where
 * the big hero is already on screen), and the proxy entry at the end — reachable before
 * sign-in, so a user on a network that blocks Telegram can get through.
 */
@Composable
private fun AuthTopBar(onBack: (() -> Unit)?, showMark: Boolean, onProxy: () -> Unit) {
    val mark = remember { BrandFanState(fanned = true) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TOP_BAR_MIN_HEIGHT)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Symbol(
                    name = "arrow_back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    size = 24.dp,
                    contentDescription = stringResource(R.string.auth_back),
                )
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
        if (showMark) {
            Spacer(Modifier.width(4.dp))
            BrandFanLogo(state = mark, size = 28.dp, modifier = Modifier.clip(BrandTileShape))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onProxy) {
            Symbol(name = "vpn_key", size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.proxy_title))
        }
    }
}

// ---------- Hero ----------

/**
 * Motion state of the hero, hoisted to the screen so the hero can leave and come back
 * (Welcome ↔ Phone on a phone, the left pane on wide screens) without replaying its
 * entrance. [motion] lets one movement run at a time: a tap during the entrance or
 * during a leaf-through is ignored rather than restarting it.
 */
@Stable
private class AuthHero(val reducedMotion: Boolean) {
    val fan = BrandFanState(fanned = reducedMotion)
    val titleAlpha = Animatable(if (reducedMotion) 1f else 0f)
    val titleOffset = Animatable(0f)
    val motion = Mutex()

    fun leafThrough(scope: CoroutineScope, spec: FiniteAnimationSpec<Float>) {
        if (reducedMotion) return
        scope.launch {
            if (!motion.tryLock()) return@launch
            try {
                fan.leafThrough(spec)
            } finally {
                motion.unlock()
            }
        }
    }
}

/**
 * The screen's one expressive moment (ARCHITECTURE.md → intensity budget). On entry the
 * logo's three cards leaf out of a single stack into the fan and the wordmark settles in;
 * once the fan has landed, the cards leaf through one time ("hortay" = "leaf through!")
 * and rest. Nothing runs after that; a tap on the hero leafs through again. Under
 * reduced motion everything starts at rest and the tap does nothing.
 */
@Composable
private fun rememberAuthHero(): AuthHero {
    val reducedMotion = rememberReducedMotion()
    val hero = remember { AuthHero(reducedMotion) }
    val fanSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val leafSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val titleSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val titleEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val titleRise = with(LocalDensity.current) { HERO_TITLE_RISE.toPx() }
    LaunchedEffect(hero) {
        if (hero.reducedMotion) return@LaunchedEffect
        hero.motion.withLock {
            hero.titleOffset.snapTo(titleRise)
            coroutineScope {
                launch {
                    delay(HERO_TITLE_DELAY_MS)
                    launch { hero.titleAlpha.animateTo(1f, titleEffects) }
                    hero.titleOffset.animateTo(0f, titleSpatial)
                }
                hero.fan.leafOut(fanSpec, HERO_FAN_STAGGER_MS)
            }
            delay(HERO_LEAF_PAUSE_MS)
            hero.fan.leafThrough(leafSpec)
        }
    }
    return hero
}

@Composable
private fun HeroMark(hero: AuthHero, size: Dp) {
    val scope = rememberCoroutineScope()
    val leafSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    BrandFanLogo(
        state = hero.fan,
        size = size,
        style = BrandFanStyle.Floating,
        showPost = true,
        modifier = Modifier
            // Decorative replay: a bare tap detector adds no semantics, so a screen reader
            // neither announces nor focuses the logo. No ripple.
            .pointerInput(hero) {
                detectTapGestures { hero.leafThrough(scope, leafSpec) }
            },
    )
}

/** Wordmark and the one-line promise under it; they settle in together. */
@Composable
private fun HeroTitle(hero: AuthHero, modifier: Modifier = Modifier) {
    Column(
        // Layer-phase reads: the settle animates without recomposing the text.
        modifier = modifier.graphicsLayer {
            alpha = hero.titleAlpha.value
            translationY = hero.titleOffset.value
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_subtitle_default),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The hero as the left pane of the two-pane layout, beside every step. */
@Composable
private fun HeroPane(hero: AuthHero, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val size = min(maxHeight * WIDE_HERO_HEIGHT_FRACTION, maxWidth * HERO_WIDTH_FRACTION)
            .coerceIn(HERO_MIN_SIZE, HERO_MAX_SIZE)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroMark(hero = hero, size = size)
            Spacer(Modifier.height(28.dp))
            HeroTitle(hero = hero)
        }
    }
}

// ---------- Welcome ----------

/**
 * Step 1. The hero takes the middle of the screen; the two ways in sit at the bottom,
 * within thumb reach, with the privacy policy as one quiet line under them. When the
 * content can't fit (large font, landscape-ish heights) the column scrolls instead of
 * squeezing: it is at least the viewport tall, so the weights pin the actions to the
 * bottom whenever there is room.
 */
@Composable
private fun WelcomeStep(
    stage: AuthStage,
    hero: AuthHero,
    showHero: Boolean,
    onSignIn: () -> Unit,
    onGuest: () -> Unit,
    onProxy: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val heroSize = (viewport * HERO_HEIGHT_FRACTION)
            .coerceAtMost(maxWidth * HERO_WIDTH_FRACTION)
            .coerceIn(HERO_MIN_SIZE, HERO_MAX_SIZE)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuthTopBar(onBack = null, showMark = false, onProxy = onProxy)
            Spacer(Modifier.weight(1f))
            if (showHero) {
                HeroMark(hero = hero, size = heroSize)
                Spacer(Modifier.height(28.dp))
                HeroTitle(hero = hero, modifier = Modifier.padding(horizontal = 24.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                // While TDLib is still starting, the button shows it is getting ready
                // instead of looking disabled for no visible reason.
                PrimaryActionButton(
                    text = stringResource(R.string.auth_sign_in_telegram),
                    enabled = canStartSignIn(stage),
                    loading = stage is AuthStage.Loading,
                    onClick = onSignIn,
                )
                Spacer(Modifier.height(12.dp))
                GuestButton(onClick = onGuest)
                Spacer(Modifier.height(4.dp))
                PrivacyLink()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ---------- Phone ----------

/**
 * Step 2: one title, one helper line (where the sign-in goes and where the code will
 * arrive — the worry a third-party app asking for a Telegram number raises), the form,
 * and a quiet link to the source for anyone who wants to check.
 */
@Composable
private fun PhoneStep(
    graph: AppGraph,
    stage: AuthStage,
    draft: PhoneDraft,
    errorMessage: String?,
    showMark: Boolean,
    peek: () -> Float,
    onBack: () -> Unit,
    onProxy: () -> Unit,
) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val peekDistance = with(LocalDensity.current) { PHONE_PEEK_DISTANCE.toPx() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = peek()
                translationX = progress * peekDistance * direction
                alpha = 1f - progress * PHONE_PEEK_FADE
            }
            .verticalScroll(rememberScrollState()),
    ) {
        AuthTopBar(onBack = onBack, showMark = showMark, onProxy = onProxy)
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.auth_title_phone),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_phone_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            PhoneForm(
                graph = graph,
                draft = draft,
                errorMessage = errorMessage,
                ready = stage is AuthStage.WaitPhone,
            )
            Spacer(Modifier.height(4.dp))
            OpenSourceLink(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PhoneForm(graph: AppGraph, draft: PhoneDraft, errorMessage: String?, ready: Boolean) {
    val client = graph.tdClient
    val scope = rememberCoroutineScope()
    val catalogue by graph.countries.countries.collectAsStateWithLifecycle()
    val detectedIso by graph.countries.detectedIso.collectAsStateWithLifecycle()
    // TDLib's names arrive in English; re-label them in the app's language (observable
    // through LocalConfiguration, so an in-app language switch re-labels too).
    val locale = LocalConfiguration.current.locales[0]
    val countries = remember(catalogue, locale) { localizeCountries(catalogue, locale) }

    LaunchedEffect(Unit) { graph.countries.load() }

    val selected = remember(countries, draft.selectedIso, detectedIso) {
        pickCountry(countries, savedIso = draft.selectedIso, detectedIso = detectedIso)
    }
    var sheetOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    // The number field takes focus, keyboard up, as the step opens: typing the number is
    // the only thing to do here. One frame first, so the field is attached.
    val numberFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        numberFocus.requestFocus()
        keyboard?.show()
    }

    // Errors from a previous submit clear once the user edits anything — feels right and
    // avoids a stale red message hanging around after they've already corrected it.
    LaunchedEffect(draft.national, selected, draft.customDial) {
        if (errorMessage != null) client.clearAuthError()
    }

    val canSubmit = ready &&
        !submitting &&
        selected != null &&
        draft.national.length >= 5 &&
        (!selected.isCustom || draft.customDial.length >= 2)

    Column(modifier = Modifier.fillMaxWidth()) {
        CountrySelectorRow(
            country = selected,
            onClick = { sheetOpen = true },
        )
        Spacer(Modifier.height(12.dp))
        PhoneNumberRow(
            country = selected,
            customDial = draft.customDial,
            onCustomDialChange = { raw ->
                // Allow only `+` plus digits; keep `+` always at the head so the user
                // can't accidentally erase it. Cap at 5 (E.164 country code max is 3,
                // but Fragment-style services can quote longer prefixes; 5 is safe).
                val digits = raw.toAsciiDigits().take(4)
                draft.customDial = "+$digits"
            },
            value = draft.national,
            onValueChange = { draft.national = it.toAsciiDigits().take(15) },
            isError = errorMessage != null,
            focusRequester = numberFocus,
        )
        AnimatedFieldError(text = errorMessage)
        Spacer(Modifier.height(24.dp))
        PrimaryActionButton(
            text = stringResource(R.string.auth_send_code),
            enabled = canSubmit,
            // Also while TDLib is still coming back to WaitPhone (after "Change number").
            loading = submitting || !ready,
            onClick = {
                val country = selected ?: return@PrimaryActionButton
                val prefix = if (country.isCustom) draft.customDial else country.dialCode
                val full = prefix + draft.national
                submitting = true
                scope.launch {
                    try { client.submitPhone(full) } finally { submitting = false }
                }
            },
        )
    }

    if (sheetOpen) {
        CountryPickerSheet(
            countries = countries,
            onPick = { picked ->
                draft.selectedIso = picked.iso
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

/** The picked country by name only: no flag emoji (see [CountryPickerSheet]). */
@Composable
private fun CountrySelectorRow(country: Country?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.auth_country_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = country?.name ?: stringResource(R.string.auth_country_loading),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Symbol(
            name = "arrow_forward",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 20.dp,
        )
    }
}

@Composable
private fun PhoneNumberRow(
    country: Country?,
    customDial: String,
    onCustomDialChange: (String) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    focusRequester: FocusRequester,
) {
    val isCustom = country?.isCustom == true
    // Dial code then national number is a fixed left-to-right sequence in every locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dial-code field. Read-only when paired with a real country (changing it means
        // opening the picker, exactly like in official Telegram), but editable when the
        // user picks the synthetic "Інша країна" row so they can type any prefix.
        OutlinedTextField(
            value = if (isCustom) customDial else (country?.dialCode ?: "+"),
            onValueChange = onCustomDialChange,
            readOnly = !isCustom,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.width(110.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.auth_phone_placeholder)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            isError = isError,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .semantics { contentType = ContentType.PhoneNumber },
        )
    }
    }
}

// ---------- Code ----------

@Composable
private fun CodeForm(graph: AppGraph, stage: AuthStage.WaitCode, errorMessage: String?) {
    val client = graph.tdClient
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Reset the typed code whenever TDLib swaps the active code channel — resend can
    // change codeLength (e.g. a 5-digit TelegramMessage code falling back to a 6-digit
    // Fragment one) and a submit with a stale longer/shorter value would 400 with
    // PHONE_CODE_INVALID even if the user re-typed correctly afterwards. Keying on
    // (codeLength, channelLabel) means the field clears on a real channel switch but stays
    // put through harmless recompositions of the same WaitCode payload.
    var code by rememberSaveable(stage.codeLength, stage.channelLabel) { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }

    // Server-mandated cooldown ticks down from stage.resendAvailableInSec to 0. While it
    // is non-zero the resend button is disabled with a "0:42" label, which is what the
    // official Telegram client does — and saves users from spamming themselves into a
    // FLOOD_WAIT. Resets whenever a different WaitCode stage arrives (e.g. TDLib hands back
    // a new delivery channel) AND after every successful resend: WaitCode is a data class,
    // so a resend that yields an equal stage (same channel, same timeout) would not re-key
    // on `stage` alone — [resendGeneration] covers that case.
    var resendGeneration by remember { mutableIntStateOf(0) }
    var secondsLeft by remember(stage, resendGeneration) {
        mutableIntStateOf(stage.resendAvailableInSec)
    }
    LaunchedEffect(stage, resendGeneration) {
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000)
            secondsLeft -= 1
        }
    }

    LaunchedEffect(code) {
        if (errorMessage != null) client.clearAuthError()
    }

    val onSubmit: (String) -> Unit = { input ->
        if (!submitting) {
            submitting = true
            scope.launch {
                try { client.submitCode(input) } finally { submitting = false }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Numeric channels get the segmented OTP grid sized to TDLib's reported length.
        // For a third-party app that's TelegramMessage and (rarely) Fragment — the only
        // types we ever receive (tdlib/td#2310). The exotic SmsWord / SmsPhrase channels
        // would need a free-form text field instead, so the defensive `else` branch keeps
        // one; in practice it doesn't fire for us.
        if (stage.isNumeric) {
            OtpInput(
                value = code,
                onValueChange = { code = it },
                length = stage.codeLength,
                isError = errorMessage != null,
                enabled = !submitting,
                onComplete = onSubmit,
            )
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                isError = errorMessage != null,
                enabled = !submitting,
                shape = MaterialTheme.shapes.medium,
                placeholder = { Text(stringResource(R.string.auth_code_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedFieldError(text = errorMessage)
        // For third-party apps the code only ever arrives inside Telegram itself
        // (tdlib/td#2310) — SMS is reserved for official mobile clients. Spell that out
        // so a user without a second signed-in device doesn't sit waiting for an SMS that
        // will never come and conclude the app is broken.
        //
        // The code message itself warns "don't give this code to anyone". Read cold, that
        // looks like a reason to stop here, so the next line says what the warning is
        // about. And since the code sits in another app, one tap opens it.
        if (stage.deliveredInApp) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_code_hint_telegram),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.auth_code_share_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            val context = LocalContext.current
            OutlinedButton(
                onClick = { dev.lyo.hortay.ui.main.openTelegramApp(context) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Symbol(name = "open_in_new", contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_open_telegram))
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryActionButton(
            text = stringResource(R.string.auth_continue),
            enabled = !submitting && code.length >= minSubmitLength(stage),
            loading = submitting,
            onClick = {
                focusManager.clearFocus()
                onSubmit(code)
            },
        )
        Spacer(Modifier.height(8.dp))
        // No next_type means TDLib has nowhere else to send the code: the call fails with
        // "Authentication code can't be resend" (sic, issue #50). Third-party apps only ever get
        // the in-app channel (tdlib/td#2310), so this is the usual case — hide the button
        // instead of offering an action that can only error.
        val nextChannel = stage.nextChannelLabel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (nextChannel != null) Arrangement.SpaceBetween else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nextChannel != null) {
                TextButton(
                    enabled = !resending && !submitting && secondsLeft == 0,
                    onClick = {
                        resending = true
                        scope.launch {
                            try {
                                client.resendCode()
                                // resendCode reports failures through authError, not by throwing.
                                if (client.authError.value == null) resendGeneration++
                            } finally {
                                resending = false
                            }
                        }
                    },
                ) {
                    val ctxRes = LocalContext.current.resources
                    Text(
                        text = resendLabel(
                            res = ctxRes,
                            secondsLeft = secondsLeft,
                            resending = resending,
                            nextChannelLabel = nextChannel,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            TextButton(
                enabled = !submitting,
                onClick = { scope.launch { client.cancelAuth() } },
            ) {
                Text(
                    text = stringResource(R.string.auth_change_number),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Minimum length to enable the primary submit button. For numeric channels we wait for
 * exactly the digit count TDLib expects; for SmsWord / SmsPhrase any non-empty input is
 * acceptable because we don't know the actual length up front.
 */
private fun minSubmitLength(stage: AuthStage.WaitCode): Int =
    if (stage.isNumeric) stage.codeLength else 1

/**
 * Resend button label by cooldown state: "Sending…" while in flight, a "0:42" countdown
 * while the server cooldown is non-zero, then "Send code again (<next channel>)". Only
 * called when TDLib advertises a next_type (for us only ever Fragment — tdlib/td#2310);
 * with none the button is hidden. We never name SMS here because a third-party app's
 * next_type is never SMS. Pulling this out keeps CodeForm's layout block readable.
 */
private fun resendLabel(
    res: android.content.res.Resources,
    secondsLeft: Int,
    resending: Boolean,
    nextChannelLabel: String,
): String = when {
    resending -> res.getString(R.string.auth_resend_sending)
    secondsLeft > 0 -> res.getString(R.string.auth_resend_countdown, secondsLeft / 60, secondsLeft % 60)
    else -> res.getString(R.string.auth_resend_via, nextChannelLabel)
}

// ---------- Password ----------

@Composable
private fun PasswordForm(graph: AppGraph, stage: AuthStage.WaitPassword, errorMessage: String?) {
    val client = graph.tdClient
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var password by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    // Recovery flow has three states:
    //   • Idle (default) — show password field + "Forgot password" link.
    //   • Sent — link tapped, requestPasswordRecovery fired, code field shown.
    //     Toggling back into password mode (auth_password_recovery_back) resets
    //     to Idle without retracting Telegram's already-sent email; that's
    //     acceptable, the code stays valid for a few minutes either way.
    //   • Unavailable — link tapped but stage.hasRecoveryEmail is false. Static
    //     info card explaining the user must reset on another device. No RPC.
    var recoveryMode by remember { mutableStateOf(RecoveryMode.Idle) }

    LaunchedEffect(password, recoveryCode) {
        if (errorMessage != null) client.clearAuthError()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (recoveryMode != RecoveryMode.Sent) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.auth_password_label)) },
                singleLine = true,
                isError = errorMessage != null,
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Symbol(
                            name = if (visible) "visibility_off" else "visibility",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 22.dp,
                            contentDescription = stringResource(
                                if (visible) R.string.auth_password_hide else R.string.auth_password_show,
                            ),
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
            )
            AnimatedFieldError(text = errorMessage)
            if (stage.hint.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Symbol(
                        name = "info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 18.dp,
                    )
                    Text(
                        text = stringResource(R.string.auth_password_hint, stage.hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_password_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recoveryMode == RecoveryMode.Unavailable) {
            Spacer(Modifier.height(12.dp))
            RecoveryInfoCard(
                text = stringResource(R.string.auth_password_recovery_unavailable),
            )
        }
        if (recoveryMode == RecoveryMode.Sent) {
            RecoveryInfoCard(
                text = stringResource(
                    R.string.auth_password_recovery_sent,
                    stage.recoveryEmailPattern.ifEmpty { "***" },
                ),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = recoveryCode,
                onValueChange = { recoveryCode = it.toAsciiDigits().take(8) },
                label = { Text(stringResource(R.string.auth_password_recovery_code_label)) },
                singleLine = true,
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedFieldError(text = errorMessage)
        }
        // Forgot-password / back-to-password toggle. Hidden when recovery is
        // Unavailable — the info card already explains the user has no path
        // forward in-app, a "back to password" link there would be misleading.
        // The link sits in a 48 dp touch target (minimumInteractiveComponentSize). That
        // target's own slop replaces the former 8 dp spacer + 4 dp padding above the link, and
        // the spacer below shrinks to match, so the text keeps its place.
        val recoveryLinkShown = recoveryMode != RecoveryMode.Unavailable
        if (recoveryLinkShown) {
            val toggleText = stringResource(
                if (recoveryMode == RecoveryMode.Sent) R.string.auth_password_recovery_back
                else R.string.auth_password_forgot,
            )
            Text(
                text = toggleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(role = Role.Button) {
                        if (recoveryMode == RecoveryMode.Sent) {
                            recoveryMode = RecoveryMode.Idle
                            recoveryCode = ""
                        } else if (!stage.hasRecoveryEmail) {
                            recoveryMode = RecoveryMode.Unavailable
                        } else {
                            recoveryMode = RecoveryMode.Sent
                            scope.launch { client.requestPasswordRecovery() }
                        }
                    },
            )
        }
        Spacer(Modifier.height(if (recoveryLinkShown) 10.dp else 20.dp))
        when (recoveryMode) {
            RecoveryMode.Sent -> PrimaryActionButton(
                text = stringResource(R.string.auth_continue),
                enabled = !submitting && recoveryCode.isNotEmpty(),
                loading = submitting,
                onClick = {
                    focusManager.clearFocus()
                    submitting = true
                    scope.launch {
                        try { client.recoverPassword(recoveryCode) } finally { submitting = false }
                    }
                },
            )
            RecoveryMode.Unavailable -> Unit
            RecoveryMode.Idle -> PrimaryActionButton(
                text = stringResource(R.string.auth_continue),
                enabled = !submitting && password.isNotEmpty(),
                loading = submitting,
                onClick = {
                    focusManager.clearFocus()
                    submitting = true
                    scope.launch {
                        try { client.submitPassword(password) } finally { submitting = false }
                    }
                },
            )
        }
    }
}

private enum class RecoveryMode { Idle, Sent, Unavailable }

@Composable
private fun RecoveryInfoCard(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Symbol(
            name = "info",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Code, password, error ----------

/**
 * TDLib's later stages keep their own content under the shared header. Back is offered
 * on the code and password stages only, where it abandons the attempt; an error has its
 * own Retry, and on an error the guest way in stays offered, since a user without a
 * Telegram account would otherwise have no way forward.
 */
@Composable
private fun StageStep(
    graph: AppGraph,
    stage: AuthStage,
    errorMessage: String?,
    showMark: Boolean,
    onCancelAttempt: () -> Unit,
    onGuest: () -> Unit,
    onProxy: () -> Unit,
) {
    val canGoBack = stage is AuthStage.WaitCode || stage is AuthStage.WaitPassword
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AuthTopBar(onBack = if (canGoBack) onCancelAttempt else null, showMark = showMark, onProxy = onProxy)
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            StageHeading(stage = stage)
            Spacer(Modifier.height(28.dp))
            when (stage) {
                is AuthStage.WaitCode -> CodeForm(graph = graph, stage = stage, errorMessage = errorMessage)
                is AuthStage.WaitPassword -> PasswordForm(graph = graph, stage = stage, errorMessage = errorMessage)
                is AuthStage.Error -> {
                    RecoverableErrorBlock(
                        message = stage.message,
                        showOpenTelegram = stage.resolvedInTelegram,
                        onRetry = onCancelAttempt,
                    )
                    Spacer(Modifier.height(12.dp))
                    GuestButton(onClick = onGuest)
                }
                AuthStage.Loading, AuthStage.WaitPhone, AuthStage.Ready -> LoadingForm()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StageHeading(stage: AuthStage) {
    val title = when (stage) {
        is AuthStage.WaitCode -> stringResource(R.string.auth_title_wait_code)
        is AuthStage.WaitPassword -> stringResource(R.string.auth_title_wait_password)
        is AuthStage.Error -> stringResource(
            if (stage.resolvedInTelegram) R.string.auth_title_error else R.string.auth_title_init_failure,
        )
        AuthStage.Loading, AuthStage.WaitPhone, AuthStage.Ready -> return
    }
    val subtitle = when (stage) {
        is AuthStage.WaitCode -> stringResource(R.string.auth_subtitle_wait_code, stage.channelLabel)
        is AuthStage.WaitPassword -> stringResource(
            if (stage.hint.isNotEmpty()) R.string.auth_subtitle_password_no_hint
            else R.string.auth_subtitle_password_with_hint,
        )
        is AuthStage.Error -> stringResource(
            if (stage.resolvedInTelegram) R.string.auth_open_telegram_hint else R.string.auth_subtitle_init_failure,
        )
        AuthStage.Loading, AuthStage.WaitPhone, AuthStage.Ready -> return
    }
    Text(
        text = title,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------- Entry: guest and links ----------

/**
 * Guest-mode way in: no account, no phone number, no TDLib call — a local DataStore flag
 * that MainActivity routes on. Tonal, full width and in the primary button's size class:
 * reading public channels without an account is a real first choice, not an opt-out.
 */
@Composable
private fun GuestButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(
            shape = CircleShape,
            pressedShape = MaterialTheme.shapes.medium,
        ),
        contentPadding = PaddingValuesPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.auth_continue_without_login),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The privacy policy (the one Settings → Privacy opens) as one quiet line under the ways
 * in. The whole line is the link, in a 48 dp target. Opened through the guarded helper:
 * no browser installed must not throw out of the tap.
 */
@Composable
private fun PrivacyLink() {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.auth_trust_privacy_link),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { openExternalUrl(context, BuildConfig.PRIVACY_POLICY_URL) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** The repository Settings → About opens, under the phone form: the claim made checkable. */
@Composable
private fun OpenSourceLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = { openExternalUrl(context, AUTHOR_SOURCE_URL) },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.auth_trust_source_link),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// ---------- Shared ----------

/**
 * The step's one filled action. Disabled is the standard M3 disabled button, so it never
 * passes for the tonal guest button. [loading] (work under way, or TDLib not ready yet)
 * keeps the filled colours with a spinner in place of the label, and keeps the label as
 * the button's spoken name.
 */
@Composable
private fun PrimaryActionButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    // M3 Expressive press-shape morph: pill at rest, squarer (`shapes.medium`,
    // 18 dp) under thumb. Squish reads as tactile feedback and is the canonical
    // ButtonGroup vocabulary documented in M3E's interaction-states spec; without
    // it the primary "Sign in" / "Continue" buttons looked identical pressed vs
    // resting, hiding the only press-state cue.
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(
            shape = CircleShape,
            pressedShape = MaterialTheme.shapes.medium,
        ),
        enabled = enabled && !loading,
        contentPadding = PaddingValuesPrimary,
        colors = if (loading) {
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (loading) Modifier.semantics { contentDescription = text } else Modifier),
    ) {
        if (loading) {
            // Inline expressive spinner that morphs through the canonical M3 polygon
            // cycle (Circle → SoftBurst → Cookie9 → Pill → Sunny). Reads as "alive"
            // even at 20 dp where a circular ring would be a static halo.
            LoadingIndicator(
                modifier = Modifier.size(24.dp),
                color = LocalContentColor.current,
            )
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

/** Delay before the back card follows the accent card out of the stack. */
private const val HERO_FAN_STAGGER_MS = 70L

/** The title starts settling once the fan is under way, not after it lands. */
private const val HERO_TITLE_DELAY_MS = 120L

/** A breath between the fan landing and the leaf-through, so the two read as two beats. */
private const val HERO_LEAF_PAUSE_MS = 220L

/** How far below its place the title starts. */
private val HERO_TITLE_RISE = 8.dp

/**
 * Share of the viewport height the hero box takes on a phone ("roughly 40 %"); the
 * floating cards fill about 58 % of the box (see BrandFanGeometry's floating frame).
 */
private const val HERO_HEIGHT_FRACTION = 0.42f

/** The hero beside the steps on wide screens has the whole pane height to itself. */
private const val WIDE_HERO_HEIGHT_FRACTION = 0.5f

/** Never wider than this share of the available width, so it keeps side margins. */
private const val HERO_WIDTH_FRACTION = 0.8f
private val HERO_MIN_SIZE = 96.dp
private val HERO_MAX_SIZE = 360.dp

/** The launcher tile's rounding, proportional, for the small header mark. */
private val BrandTileShape = RoundedCornerShape(percent = 24)

/** Two panes from this window width (and in any landscape window). */
private const val WIDE_MIN_WIDTH_DP = 600

/** The column cap the screen has always used on tablets and desktop windows. */
private val AUTH_CONTENT_MAX_WIDTH = 520.dp

private val TOP_BAR_MIN_HEIGHT = 56.dp

/** Steps slide by a fifth of their width; the fade carries the rest. */
private const val STEP_SLIDE_DIVISOR = 5

/** How far the phone step follows a back swipe before the step switch takes over. */
private val PHONE_PEEK_DISTANCE = 48.dp
private const val PHONE_PEEK_FADE = 0.25f

private val PaddingValuesPrimary = PaddingValues(
    vertical = 18.dp,
    horizontal = 24.dp,
)

@Composable
private fun LoadingForm() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // ContainedLoadingIndicator wraps the morphing polygon cycle in a tonal
        // surface — heavier visual weight that reads as "system is working" on a
        // full-screen blocking loader, vs the inline LoadingIndicator on buttons.
        ContainedLoadingIndicator()
    }
}

@Composable
private fun AnimatedFieldError(text: String?) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(effects) + slideInVertically(spatial) { -it / 2 },
        exit = fadeOut(effects),
    ) {
        if (text != null) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(
                    name = "info",
                    tint = MaterialTheme.colorScheme.error,
                    size = 16.dp,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RecoverableErrorBlock(message: String, showOpenTelegram: Boolean, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))
        // An AuthStage.Error resolved in Telegram carries an "open Telegram, do X there,
        // come back" recovery path (the subtitle on the hero block makes this explicit).
        // Surface the open-Telegram action right next to retry so the user has a one-tap
        // jump to the recovery surface — not just guidance copy. A local failure (client
        // init) has nothing to do in Telegram, so it gets retry only.
        if (showOpenTelegram) {
            val context = LocalContext.current
            OutlinedButton(
                onClick = { dev.lyo.hortay.ui.main.openTelegramApp(context) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Symbol(name = "open_in_new", contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_open_telegram))
            }
            Spacer(Modifier.height(12.dp))
        }
        PrimaryActionButton(
            text = stringResource(R.string.auth_retry),
            enabled = true,
            loading = false,
            onClick = onRetry,
        )
    }
}
