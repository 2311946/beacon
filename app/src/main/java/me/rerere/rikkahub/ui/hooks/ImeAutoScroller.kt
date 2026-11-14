package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
    enabled: Boolean = true,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(lazyListState, localDensity) {
        // 即使暂停滚动也更新基线，恢复时不补偿地图搜索框产生的键盘高度。
        var previousHeight = ime.getBottom(localDensity)
        snapshotFlow { ime.getBottom(localDensity) }.collect { keyboardHeight ->
            val delta = keyboardHeight - previousHeight
            previousHeight = keyboardHeight
            if (currentEnabled && keyboardHeight > 0 && delta != 0) {
                lazyListState.scrollBy(delta.toFloat())
            }
        }
    }
}
