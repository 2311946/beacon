package me.rerere.rikkahub.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import me.rerere.rikkahub.utils.getActivity

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

/** 界面皮肤独立于 Material You 配色设置，未知 ID 安全回退经典。 */
enum class BuiltInChatTheme(val id: String, val title: String, val description: String) {
    CLASSIC("classic", "经典", "保留当前样式，跟随原有 Material You 和外观设置"),
    KUROMI("kuromi", "库洛米 Kuromi", "暗黑与粉紫的小恶魔风格，固定深色、16dp 圆角"),
    ;

    companion object {
        fun fromId(id: String): BuiltInChatTheme = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** null 表示沿用经典组件原值；后续主题只需提供自己的外观 token。 */
data class ChatThemeAppearance(
    val colorScheme: ColorScheme? = null,
    val userBubbleColors: List<Color> = emptyList(),
    val assistantBubbleColor: Color? = null,
    val assistantBubbleBorder: Color? = null,
    val bubbleCorner: Dp? = null,
    val backgroundColor: Color? = null,
    val backgroundImage: String? = null,
    val topBarColor: Color? = null,
    val bottomBarColor: Color? = null,
    val inputColor: Color? = null,
    val textColor: Color? = null,
    val secondaryTextColor: Color? = null,
    val accentColor: Color? = null,
    val forceAssistantBubble: Boolean = false,
    val novelDialogueColor: Color? = null,
    val novelActionColor: Color? = null,
    val novelNarrationColor: Color? = null,
    val novelThoughtColor: Color? = null,
)

private val KuromiColorScheme = darkColorScheme(
    primary = Color(0xFFB388FF), onPrimary = Color(0xFF1A1A2E),
    primaryContainer = Color(0xFF2D1B4E), onPrimaryContainer = Color.White,
    secondary = Color(0xFFFF69B4), onSecondary = Color(0xFF1A1A2E),
    secondaryContainer = Color(0xFF4A2875), onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFFB6C1), onTertiary = Color(0xFF1A1A2E),
    tertiaryContainer = Color(0xFF2D1B4E), onTertiaryContainer = Color.White,
    background = Color(0xFF0D0D1A), onBackground = Color.White,
    surface = Color(0xFF0D0D1A), onSurface = Color.White,
    surfaceVariant = Color(0xFF2D1B4E), onSurfaceVariant = Color(0xFFFFB6C1),
    surfaceTint = Color(0xFFB388FF),
    surfaceDim = Color(0xFF0D0D1A), surfaceBright = Color(0xFF2D1B4E),
    surfaceContainerLowest = Color(0xFF0D0D1A), surfaceContainerLow = Color(0xFF1E1E2E),
    surfaceContainer = Color(0xFF1A1A2E), surfaceContainerHigh = Color(0xFF1A1A2E),
    surfaceContainerHighest = Color(0xFF2D1B4E),
    outline = Color(0xFFB388FF), outlineVariant = Color(0xFF4A2875),
    inverseSurface = Color(0xFFFFB6C1), inverseOnSurface = Color(0xFF1A1A2E),
    inversePrimary = Color(0xFF4A2875),
)

private val ClassicAppearance = ChatThemeAppearance()
private val KuromiAppearance = ChatThemeAppearance(
    colorScheme = KuromiColorScheme,
    userBubbleColors = listOf(Color(0xFF2D1B4E), Color(0xFF4A2875)),
    assistantBubbleColor = Color(0xFF1A1A2E),
    assistantBubbleBorder = Color(0xFFB388FF),
    bubbleCorner = 16.dp,
    backgroundColor = Color(0xFF0D0D1A),
    topBarColor = Color(0xFF0D0D1A), bottomBarColor = Color(0xFF0D0D1A),
    inputColor = Color(0xFF1E1E2E),
    textColor = Color.White, secondaryTextColor = Color(0xFFFFB6C1),
    accentColor = Color(0xFFB388FF), forceAssistantBubble = true,
    novelDialogueColor = Color(0xFFB388FF), novelActionColor = Color(0xFFC9B5E8),
    novelNarrationColor = Color(0xFFD2CEDB), novelThoughtColor = Color(0xFF9D8CAF),
)

fun BuiltInChatTheme.appearance(): ChatThemeAppearance = when (this) {
    BuiltInChatTheme.CLASSIC -> ClassicAppearance
    BuiltInChatTheme.KUROMI -> KuromiAppearance
}

val LocalChatTheme = compositionLocalOf { ClassicAppearance }

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val chatTheme = BuiltInChatTheme.fromId(settings.displaySetting.chatThemeId).appearance()
    val darkTheme = chatTheme.colorScheme != null || when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = when {
        chatTheme.colorScheme != null -> chatTheme.colorScheme
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme, chatTheme) {
        if (chatTheme.colorScheme == null && darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    val activity = view.context.getActivity()
    // 浮窗可能使用 Application Context，没有可更新系统栏的 Activity。
    if (!view.isInEditMode && activity != null) {
        DisposableEffect(view, activity, darkTheme) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {
                // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    CompositionLocalProvider(
        LocalChatTheme provides chatTheme,
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = Typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
