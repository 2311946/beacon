package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation

/** 连续通话只显示统一入口；展开由列表控制，原消息保持惰性排版。 */
@Composable
internal fun VoiceCallGroupHeader(firstMessage: UIMessage, count: Int, expanded: Boolean, onClick: () -> Unit) {
    val time = firstMessage.createdAt.toJavaLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = "📞 通话 · $time · ${count}条 ${if (expanded) "▲" else "▼"}",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .clickable { onClickSuggestion(suggestion) }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(suggestion, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState,
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(initialOffsetX = { if (onLeft) -it * 2 else it * 2 }),
        exit = slideOutHorizontally(targetOffsetX = { if (onLeft) -it * 2 else it * 2 }),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val icons = listOf(HugeIcons.ArrowUpDouble, HugeIcons.ArrowUp01, HugeIcons.ArrowDown01, HugeIcons.ArrowDownDouble)
            icons.forEachIndexed { index, icon ->
                Surface(
                    onClick = {
                        scope.launch {
                            val lastIndex = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            when (index) {
                                0 -> state.scrollToItem(0)
                                1 -> state.animateScrollToItem((state.firstVisibleItemIndex - 1).coerceAtLeast(0))
                                2 -> state.animateScrollToItem((state.firstVisibleItemIndex + 1).coerceAtMost(lastIndex))
                                else -> state.scrollToItem(lastIndex)
                            }
                        }
                    },
                    shape = CircleShape,
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f),
                ) {
                    Icon(icon, if (index == 3) stringResource(R.string.chat_page_scroll_to_bottom) else null,
                        modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}
