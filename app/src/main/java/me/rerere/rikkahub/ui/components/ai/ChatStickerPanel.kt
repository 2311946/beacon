package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.datastore.Sticker
import me.rerere.rikkahub.data.datastore.StickerGroup
import me.rerere.rikkahub.data.datastore.StickerStore

@Composable
fun ChatStickerPanel(
    onSendSticker: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { StickerStore.get(context) }
    val groups by store.groups.collectAsStateWithLifecycle()
    val storeError by store.error.collectAsStateWithLifecycle()
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val group = groups.find { it.id == selectedGroupId } ?: groups.firstOrNull()

    Column(modifier = modifier.fillMaxWidth()) {
        (storeError ?: sendError)?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(216.dp), contentAlignment = Alignment.Center) {
            if (group == null || group.stickers.isEmpty()) {
                Text(
                    text = if (storeError != null) "表情暂时不可用"
                    else if (group == null) "还没有表情分组，点击下方管理添加"
                    else "此分组暂无表情，点击下方管理添加",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(72.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(group.stickers, key = { it.id }) { sticker ->
                        StickerThumbnail(
                            sticker = sticker,
                            onClick = {
                                sendError = null
                                try {
                                    onSendSticker(sticker.url)
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    sendError = exception.message ?: "表情插入失败，请重试"
                                }
                            },
                            clickLabel = "插入表情到输入框",
                        )
                    }
                }
            }
        }
        // 分组标签固定在网格下方，标签自身可横向滚动。
        if (group != null) {
            StickerGroupTabs(groups, group.id) { selectedGroupId = it }
        }
        TextButton(onClick = onManage, modifier = Modifier.align(Alignment.End)) {
            Text("管理表情")
        }
    }
}

@Composable
internal fun StickerGroupTabs(
    groups: List<StickerGroup>,
    selectedGroupId: String,
    onSelect: (String) -> Unit,
) {
    if (groups.isEmpty()) return
    SecondaryScrollableTabRow(
        selectedTabIndex = groups.indexOfFirst { it.id == selectedGroupId }.coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 12.dp,
    ) {
        groups.forEach { group ->
            Tab(
                selected = group.id == selectedGroupId,
                onClick = { onSelect(group.id) },
                text = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun StickerThumbnail(
    sticker: Sticker,
    onClick: () -> Unit,
    clickLabel: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    var loading by remember(sticker.id, sticker.url) { mutableStateOf(true) }
    var failed by remember(sticker.id, sticker.url) { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = clickLabel,
                onClick = onClick,
                onLongClickLabel = if (onLongClick != null) "删除表情" else null,
                onLongClick = onLongClick,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = sticker.url,
                contentDescription = "表情：${sticker.url}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                onLoading = {
                    loading = true
                    failed = false
                },
                onSuccess = {
                    loading = false
                    failed = false
                },
                onError = {
                    loading = false
                    failed = true
                },
            )
            if (loading || failed) {
                Text(
                    text = if (failed) "加载失败" else "加载中",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
