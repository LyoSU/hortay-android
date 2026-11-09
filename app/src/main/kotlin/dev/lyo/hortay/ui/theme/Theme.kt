package dev.lyo.hortay.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
)

/**
 * [HortayStatusColors] for the theme currently in effect — chosen by the same `darkTheme` flag
 * [HortayTheme] uses for [ColorScheme], independent of whether dynamic color is on. Defaults to
 * the light set so previews / call sites outside [HortayTheme] still resolve to something sane.
 */
val LocalHortayStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * The EFFECTIVE dark flag [HortayTheme] renders with — the user's theme mode resolved against
 * the system. Use this instead of `isSystemInDarkTheme()` anywhere below the theme, otherwise a
 * pinned Light/Dark choice disagrees with the system and the two halves of the UI diverge.
 * Defaults to `false` (light) for previews / call sites outside [HortayTheme].
 */
val LocalHortayDarkTheme = staticCompositionLocalOf { false }

/**
 * Pure-black variant of a dark [ColorScheme] for OLED screens: the canvas (`background`,
 * `surface`, `surfaceContainerLowest`) goes to #000000 while the rest of the container ladder
 * (`surfaceContainerLow` … `Highest`) keeps its tones, so cards, sheets and bars still separate
 * from the canvas by elevation. Pure function of the input scheme — applied on top of both the
 * brand dark scheme and the wallpaper-derived dynamic one.
 */
internal fun ColorScheme.withPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
)

/**
 * Hortay's Material 3 Expressive theme entry point.
 *
 * Layers, in order of how they shape what the user sees:
 *   1. **Color** — Material You dynamic palette on Android 12+ (wallpaper-derived);
 *      brand periwinkle fallback on older devices and as the deterministic baseline
 *      for tests / dynamic-color-disabled paths.
 *   2. **Typography** — Plus Jakarta Sans for display/headline (brand voice on hero
 *      surfaces), Inter for body/label (dense reading). Scale calibrated for the
 *      Expressive type-contrast ratio (display 32 sp+ vs body 14–16 sp).
 *   3. **Shapes** — flat rounded rectangles for dense reading containers
 *      (`HortayShapes`); MaterialShapes presets for hero moments via
 *      `HortayExpressive` (reactions, FAB press, loading, empty states).
 *   4. **Motion** — `MotionScheme.expressive()` swaps the standard duration+easing
 *      animation specs for spring-based physics across every Material component
 *      that reads from `MaterialTheme.motionScheme`. Visible overshoot on selection
 *      transitions, faster damping on scroll-anchored chrome — Google's recommended
 *      default for consumer-facing apps as of M3 Expressive (I/O 2025).
 *
 * [darkTheme] is the EFFECTIVE flag — `MainActivity` resolves it from the user's
 * [dev.lyo.hortay.data.ThemeMode] (only `System` consults `isSystemInDarkTheme()`). Everything
 * below the theme that needs "am I dark?" reads [LocalHortayDarkTheme], never
 * `isSystemInDarkTheme()`, so a pinned Light/Dark choice is honoured app-wide. Status- and
 * navigation-bar icon contrast follow the same effective flag.
 *
 * [pureBlack] swaps the dark scheme's canvas to #000000 (see [withPureBlack]); ignored in light.
 * [readingScale] is provided as [LocalReadingScale] for post bodies and rich articles only.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HortayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    pureBlack: Boolean = false,
    readingScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val scheme = if (darkTheme && pureBlack) baseScheme.withPureBlack() else baseScheme

    // Keyed on the system flag too (not only [darkTheme]): MainActivity.onConfigurationChanged
    // re-runs enableEdgeToEdge on a system night-mode flip, which resets the bar icons to the
    // SYSTEM theme. With a pinned mode [darkTheme] doesn't change, so without this key the
    // effect would not re-run and the icons would keep the system's contrast, not the app's.
    val systemDark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme, systemDark) {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            // API 29+ only: there enableEdgeToEdge leaves the navigation bar transparent, so the
            // icon tint must follow the app. Below 29 it paints a scrim chosen by the SYSTEM
            // theme, and flipping the icons against that scrim would make them unreadable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose { }
        }
    }

    CompositionLocalProvider(
        LocalHortayStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
        LocalHortayDarkTheme provides darkTheme,
        LocalReadingScale provides readingScale,
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            typography = HortayTypography,
            shapes = HortayShapes,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
