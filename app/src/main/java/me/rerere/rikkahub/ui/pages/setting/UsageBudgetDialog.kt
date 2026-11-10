package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.UsageBudgetState
import me.rerere.rikkahub.data.datastore.UsageBudgetStore
import me.rerere.rikkahub.data.datastore.formatBudgetYuan

@Composable
fun UsageBudgetDialog(state: UsageBudgetState, store: UsageBudgetStore, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var budget by remember { mutableStateOf(state.budget) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val validBudget = budget.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("月用量预算") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.month} 本月已用 ${formatBudgetYuan(state.spentYuan)} 元")
                OutlinedTextField(
                    value = budget, onValueChange = { budget = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("月预算（元）") }, singleLine = true, isError = validBudget == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text("下次重置：${state.resetDate} 00:00（本地时间）")
                Text("仅累计 API 返回的费用；未返回或格式无法识别则跳过，不按 token 估价。未标币种按站子账户元处理，明确外币不计入。API 单价未注明单位时按每 token 计算。", style = MaterialTheme.typography.bodySmall)
                Text("每月 1 日归零，80% 黄色提醒、100% 红色提醒，均不限制发送。已有记录保留、不重算。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && validBudget != null, onClick = {
                saving = true
                scope.launch {
                    try {
                        store.setBudgetYuan(validBudget!!)
                        onDismiss()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        error = exception.message ?: "预算保存失败"
                    } finally {
                        saving = false
                    }
                }
            }) { Text("保存预算") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("关闭") } },
    )
}
