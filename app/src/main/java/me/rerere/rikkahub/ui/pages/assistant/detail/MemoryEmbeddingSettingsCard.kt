package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.validEmbeddingEndpoint
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem

@Composable
fun MemoryEmbeddingSettingsCard(
    settings: MemoryEmbeddingSettings,
    onSave: (MemoryEmbeddingSettings) -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var weightInput by remember(settings) { mutableStateOf(settings.semanticWeight.toString()) }
    val endpointValid = validEmbeddingEndpoint(draft.endpoint)
    val modelValid = draft.model.isNotBlank()
    val weight = weightInput.toDoubleOrNull()
    val weightValid = weight != null && weight.isFinite() && weight in 0.0..1.0
    val canSave = endpointValid && modelValid && weightValid

    CardGroup(title = { Text("语义记忆检索（全局）") }) {
        switchItem(
            checked = draft.enabled,
            onCheckedChange = { draft = draft.copy(enabled = it) },
            supportingContent = {
                Text("全局配置，作用于所有助手的个人库和全局库，不随查看的记忆库切换。默认关闭。启用并保存后会将所有 ACTIVE 记忆正文片段及当前查询发送到指定服务商，可能产生费用。")
            },
            headlineContent = { Text("启用语义记忆检索") },
        )
        formItem(
            headlineContent = { Text("Embedding Endpoint") },
            supportingContent = {
                Text("必须是完整的 /embeddings 地址，仅允许 HTTPS 或 HTTP loopback。")
            },
        ) {
            OutlinedTextField(
                value = draft.endpoint,
                onValueChange = { draft = draft.copy(endpoint = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = draft.endpoint.isNotBlank() && !endpointValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    if (draft.endpoint.isNotBlank() && !endpointValid) {
                        Text("请输入有效的 HTTPS /embeddings 地址，或 localhost、127.0.0.1、::1 的 HTTP 地址。")
                    }
                },
            )
        }
        formItem(
            headlineContent = { Text("API Key") },
            supportingContent = { Text("仅用于请求认证，不会显示在日志中。") },
        ) {
            OutlinedTextField(
                value = draft.apiKey,
                onValueChange = { draft = draft.copy(apiKey = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        formItem(
            headlineContent = { Text("Embedding Model") },
        ) {
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = !modelValid,
                supportingText = { if (!modelValid) Text("模型不能为空。") },
            )
        }
        formItem(
            headlineContent = { Text("语义权重") },
            supportingContent = { Text("范围 0 到 1，用于混合词法召回和语义召回。") },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = { value ->
                        weightInput = value
                        value.toDoubleOrNull()?.let { draft = draft.copy(semanticWeight = it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !weightValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { if (!weightValid) Text("请输入 0 到 1 之间的有限数字。") },
                )
                Text(
                    text = "当前值：${draft.semanticWeight}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(
            trailingContent = {
                Button(enabled = canSave, onClick = { onSave(draft) }) {
                    Text("保存语义检索设置")
                }
            },
            headlineContent = { Text("保存当前草稿") },
            supportingContent = { Text("开关和输入项均需点击保存后才会生效，不会随每次输入触发索引。") },
        )
    }
}

