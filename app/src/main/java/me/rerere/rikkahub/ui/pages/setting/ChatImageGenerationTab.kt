package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ChatImageGeneration
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.Screen

@Composable
internal fun ChatImageGenerationTab(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    val navController = LocalNavController.current
    var showModelPicker by remember { mutableStateOf(false) }
    val images = settings.mediaGenerationProviders.flatMap { provider ->
        provider.models.filter { it.kind == MediaKind.IMAGE && provider.capabilities(it.kind) != null }
            .map { provider to it }
    }
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("选择聊天生图模型") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    images.forEach { (provider, model) ->
                        val selectModel = {
                            onUpdateSettings {
                                it.copy(chatImageGeneration = it.chatImageGeneration.copy(modelId = model.id))
                            }
                            showModelPicker = false
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = selectModel).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = model.id == (settings.chatImageGeneration.modelId ?: images.firstOrNull()?.second?.id),
                                onClick = selectModel,
                            )
                            Text("${provider.name}: ${model.modelId}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelPicker = false }) { Text("关闭") } },
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("聊天生图使用媒体服务中的独立 API 配置，不会使用聊天 API 的地址或密钥。")
        CardGroup {
            item(
                headlineContent = { Text("自动执行生图指令") },
                supportingContent = { Text("AI 只有输出独立一行 [生成图片: 描述] 时才会调用生图 API。") },
                trailingContent = {
                    Switch(
                        checked = settings.chatImageGeneration.automatic,
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(chatImageGeneration = it.chatImageGeneration.copy(automatic = enabled)) }
                        },
                    )
                },
            )
            item(
                headlineContent = { Text("当前生图模型") },
                supportingContent = {
                    Text(
                        images.firstOrNull { it.second.id == settings.chatImageGeneration.modelId }
                            ?.let { "${it.first.name}: ${it.second.modelId}" }
                            ?: if (settings.chatImageGeneration.modelId != null) {
                                "原模型已不可用，请重新选择"
                            } else {
                                images.firstOrNull()?.let { "${it.first.name}: ${it.second.modelId}（默认）" }
                                    ?: "未配置，请先在媒体服务中添加生图模型"
                            },
                    )
                },
                onClick = if (images.isNotEmpty()) ({ showModelPicker = true }) else null,
            )
            item(
                headlineContent = { Text("查看生图历史") },
                supportingContent = { Text("打开已有媒体创作记录，可预览、重试和保存图片。") },
                onClick = { navController.navigate(Screen.MediaCreationSessions) },
            )
        }
        Text(ChatImageGeneration.INSTRUCTION)
    }
}
