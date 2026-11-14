package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import kotlin.uuid.Uuid

@Stable
class ChatSelectionState {
    var selecting by mutableStateOf(false)
    val selectedItems = mutableStateListOf<Uuid>()

    fun start(ids: List<Uuid>) {
        selectedItems.clear()
        selectedItems.addAll(ids.distinct())
        selecting = true
    }

    fun cancel() {
        selecting = false
        selectedItems.clear()
    }

    fun isAllSelected(ids: List<Uuid>): Boolean = ids.isNotEmpty() && ids.all { it in selectedItems }

    fun toggleAll(ids: List<Uuid>) {
        val allSelected = isAllSelected(ids)
        selectedItems.clear()
        if (!allSelected) selectedItems.addAll(ids.distinct())
    }
}

@Composable
fun rememberChatSelectionState(): ChatSelectionState = remember { ChatSelectionState() }

@Composable
internal fun ChatSelectionToolbar(
    state: ChatSelectionState,
    allIds: List<Uuid>,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(state.selecting) { state.cancel() }
    AnimatedVisibility(
        visible = state.selecting,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it * 2 }),
        exit = slideOutVertically(targetOffsetY = { it * 2 }),
    ) {
        HorizontalFloatingToolbar(expanded = true) {
            IconButton(onClick = state::cancel) { Icon(HugeIcons.Cancel01, "取消选择") }
            TextButton(onClick = { state.toggleAll(allIds) }) {
                Text(if (state.isAllSelected(allIds)) "取消全选" else "全选")
            }
            FilledIconButton(onClick = onConfirm, enabled = allIds.any { it in state.selectedItems }) {
                Icon(HugeIcons.Tick01, "确认导出")
            }
        }
    }
}
