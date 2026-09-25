package com.sideload.splitinstaller.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sideload.splitinstaller.core.ThemeMode
import com.sideload.splitinstaller.core.ThemeSettings

// ---- brand schemes --------------------------------------------------------------
// A deep jade seed: calm enough for a diagnostic tool, distinct from the red/amber the
// status colours need to own.

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006C4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8CF8CB),
    onPrimaryContainer = Color(0xFF002116),
    inversePrimary = Color(0xFF70DBB0),
    secondary = Color(0xFF4C6358),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF092016),
    tertiary = Color(0xFF3F6375),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8FD),
    onTertiaryContainer = Color(0xFF001F2A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF6),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF404944),
    surfaceTint = Color(0xFF006C4F),
    inverseSurface = Color(0xFF2B322E),
    inverseOnSurface = Color(0xFFECF2ED),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF5FBF6),
    surfaceDim = Color(0xFFD6DBD7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F0),
    surfaceContainer = Color(0xFFE9EFEA),
    surfaceContainerHigh = Color(0xFFE4EAE4),
    surfaceContainerHighest = Color(0xFFDEE4DF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF70DBB0),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFF8CF8CB),
    inversePrimary = Color(0xFF006C4F),
    secondary = Color(0xFFB3CCBF),
    onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF354B41),
    onSecondaryContainer = Color(0xFFCEE9DA),
    tertiary = Color(0xFFA7CCE1),
    onTertiary = Color(0xFF0B3445),
    tertiaryContainer = Color(0xFF264B5C),
    onTertiaryContainer = Color(0xFFC3E8FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C2),
    surfaceTint = Color(0xFF70DBB0),
    inverseSurface = Color(0xFFDEE4DF),
    inverseOnSurface = Color(0xFF2B322E),
    outline = Color(0xFF89938C),
    outlineVariant = Color(0xFF404944),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B38),
    surfaceDim = Color(0xFF0F1512),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
)

/** True black for OLED panels; containers keep just enough lift to stay separable. */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C100E),
    surfaceContainer = Color(0xFF121714),
    surfaceContainerHigh = Color(0xFF1A1F1C),
    surfaceContainerHighest = Color(0xFF232825),
    surfaceBright = Color(0xFF2B302D),
)

// ---- status colours ---------------------------------------------------------------

/**
 * Success and warning have no slot in Material's scheme, and this app's whole job is
 * telling "works" from "will crash" at a glance, so they get their own tonal pairs.
 */
@Immutable
data class StatusPalette(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightStatus = StatusPalette(
    success = Color(0xFF1B6C3A),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA4F4B6),
    onSuccessContainer = Color(0xFF00210C),
    warning = Color(0xFF7C5800),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF271900),
)

private val DarkStatus = StatusPalette(
    success = Color(0xFF89D89C),
    onSuccess = Color(0xFF003919),
    successContainer = Color(0xFF005228),
    onSuccessContainer = Color(0xFFA4F4B6),
    warning = Color(0xFFF7BD48),
    onWarning = Color(0xFF412D00),
    warningContainer = Color(0xFF5E4200),
    onWarningContainer = Color(0xFFFFDEA6),
)

val LocalStatus = staticCompositionLocalOf { LightStatus }
val LocalDarkTheme = staticCompositionLocalOf { false }

object AppTheme {
    val status: StatusPalette
        @Composable @ReadOnlyComposable get() = LocalStatus.current
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalDarkTheme.current
}

// ---- type and shape -----------------------------------------------------------------
// The system font on purpose: it is what the ROM ships for Vietnamese, and a downloadable
// font would need Play services, which the phones this is for often do not have.

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun SplitSideloaderTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(settings.mode)
    val context = LocalContext.current
    val base = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    val scheme = if (dark && settings.amoled) base.amoled() else base

    // Bars and window follow the theme the user picked, not the one the system is in.
    if (!LocalInspectionMode.current) {
        val activity = context.findActivity()
        LaunchedEffect(dark, scheme.background) {
            (activity as? ComponentActivity)?.let {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent)
                else SystemBarStyle.light(transparent, transparent)
                it.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                it.window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
            }
        }
    }

    CompositionLocalProvider(
        LocalStatus provides if (dark) DarkStatus else LightStatus,
        LocalDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
