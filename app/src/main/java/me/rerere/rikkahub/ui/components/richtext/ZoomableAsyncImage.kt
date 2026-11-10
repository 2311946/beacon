package me.rerere.rikkahub.ui.components.richtext

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.StickerStore
import me.rerere.rikkahub.data.datastore.StickerDisplaySize
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.ui.components.LocalExportContext

/** 用户与 AI 共用同一套上限；Fit 保持原比例，点击仍预览原图。 */
@Composable
fun ChatStickerImage(model: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { StickerStore.get(context) }
    val size by store.displaySize.collectAsStateWithLifecycle()
    ZoomableAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.layout { measurable, constraints ->
            val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 320.dp.roundToPx()
            val width = (availableWidth * size.widthFraction).toInt().coerceAtLeast(1)
            val height = minOf(constraints.maxHeight, size.maxHeightDp.dp.roundToPx()).coerceAtLeast(1)
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width, maxHeight = height))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        },
    )
}

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val coilModel = ImageRequest.Builder(context)
        .data(model)
        .placeholder(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember(model) { mutableStateOf(false) }
    var failed by remember(model) { mutableStateOf(false) }
    if (failed) {
        Text(
            text = "${contentDescription?.takeIf { it.isNotBlank() } ?: "图片"}加载失败\n${model.orEmpty()}",
            modifier = modifier.clickable { failed = false },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .shimmer(isLoading = loading)
            .clickable {
                showImageViewer = true
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = {
            loading = false
        },
        onError = {
            loading = false
            failed = true
            if (me.rerere.rikkahub.BuildConfig.DEBUG) {
                Log.w("StickerReply", "[image-load-failed] type=${it.result.throwable.javaClass.simpleName}")
            }
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}
