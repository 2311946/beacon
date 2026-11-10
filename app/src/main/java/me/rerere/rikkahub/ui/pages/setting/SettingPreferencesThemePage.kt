package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.theme.BuiltInChatTheme
import me.rerere.rikkahub.ui.theme.appearance
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var amoledDarkMode by rememberAmoledDarkMode()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_theme))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("builtInThemes") {
                CardGroup(title = { Text("内置主题") }) {
                    BuiltInChatTheme.entries.forEach { theme ->
                        item(
                            onClick = {
                                vm.updateSettings { latest ->
                                    latest.copy(displaySetting = latest.displaySetting.copy(chatThemeId = theme.id))
                                }
                            },
                            headlineContent = { Text(theme.title) },
                            supportingContent = { Text(theme.description) },
                            leadingContent = {
                                val palette = theme.appearance()
                                val colors = palette.userBubbleColors.ifEmpty {
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    (colors + (palette.accentColor ?: MaterialTheme.colorScheme.tertiary)).forEach { color ->
                                        Box(Modifier.size(12.dp).background(color, CircleShape))
                                    }
                                }
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = BuiltInChatTheme.fromId(settings.displaySetting.chatThemeId) == theme,
                                    onClick = null,
                                )
                            },
                        )
                    }
                }
            }
            item {
                CardGroup(title = { Text("经典主题配色") }) {
                    item(
                        headlineContent = { Text("主题优先级") },
                        supportingContent = {
                            Text("库洛米主题优先于 Material You、明暗模式与 AMOLED，并使用深色纯色聊天背景。以下设置会保留，切回经典后恢复生效。")
                        },
                    )
                    switchItem(
                        checked = settings.dynamicColor,
                        onCheckedChange = { vm.updateSettings { latest -> latest.copy(dynamicColor = it) } },
                        supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTheme) },
                        headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_theme_setting_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    switchItem(
                        checked = amoledDarkMode,
                        onCheckedChange = { amoledDarkMode = it },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                    )
                }
            }
        }
    }
}
