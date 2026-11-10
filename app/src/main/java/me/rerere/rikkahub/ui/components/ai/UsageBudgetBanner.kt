package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.UsageBudgetLevel
import me.rerere.rikkahub.data.datastore.UsageBudgetStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.formatBudgetYuan
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

@Composable
fun UsageBudgetBanner() {
    val store: UsageBudgetStore = koinInject()
    val state by store.stateFlow.collectAsStateWithLifecycle()
    val error by store.error.collectAsStateWithLifecycle()
    Column {
        if (error != null) {
            BudgetNotice(error!!, atLimit = true)
        } else if (state.level != UsageBudgetLevel.NORMAL) {
            val atLimit = state.level == UsageBudgetLevel.AT_LIMIT
            BudgetNotice(
                if (atLimit) "本月已达预算上限（已用 ${formatBudgetYuan(state.spentYuan)} 元），仅提醒，不阻止请求"
                else "本月已用 ${formatBudgetYuan(state.spentYuan)} 元，接近预算上限（${formatBudgetYuan(state.budgetYuan)} 元）",
                atLimit,
            )
        }
    }
}

@Composable
private fun BudgetNotice(text: String, atLimit: Boolean) {
    Surface(
        color = if (atLimit) Color(0xFFFFDAD6) else Color(0xFFFFE082),
        contentColor = if (atLimit) Color(0xFF93000A) else Color(0xFF4E3900),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
