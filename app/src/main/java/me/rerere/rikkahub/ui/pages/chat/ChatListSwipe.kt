package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

private val PreviewSwipeSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

@Composable
internal fun rememberPreviewSwipeProgress(previewMode: Boolean, conversationKey: Any): Animatable<Float, *> {
    val progress = remember(conversationKey) { Animatable(if (previewMode) 1f else 0f) }
    LaunchedEffect(progress, previewMode) {
        val target = if (previewMode) 1f else 0f
        if (progress.targetValue != target) progress.animateTo(target, PreviewSwipeSpec)
    }
    return progress
}

/** 仅接管正确方向且未被子组件消费的横滑，反向滑动留给抽屉。 */
internal fun Modifier.previewSwipeGesture(
    progress: Animatable<Float, *>,
    scope: CoroutineScope,
    layoutDirection: LayoutDirection,
    canEnterPreview: () -> Boolean,
    isPreviewMode: () -> Boolean,
    onPreviewModeChange: (Boolean) -> Unit,
): Modifier = pointerInput(progress, layoutDirection) {
    val sign = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val flingThreshold = 400.dp.toPx()
    var dragProgress = 0f
    fun dragBy(dx: Float, width: Float) {
        dragProgress = (dragProgress - dx * sign / width).coerceIn(0f, 1f)
        val value = dragProgress
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(value) }
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startInPreview = isPreviewMode()
        if (!startInPreview && !canEnterPreview()) return@awaitEachGesture
        var slop = 0f
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            val towardsPreview = -over * sign > 0
            if (towardsPreview != startInPreview) {
                change.consume()
                slop = over
            }
        } ?: return@awaitEachGesture
        val width = size.width.toFloat().coerceAtLeast(1f)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(drag)
        dragProgress = progress.value
        dragBy(slop, width)
        var completed = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == drag.id } ?: break
            tracker.addPointerInputChange(change)
            if (change.changedToUpIgnoreConsumed()) {
                completed = true
                break
            }
            dragBy(change.positionChangeIgnoreConsumed().x, width)
            change.consume()
        }
        val velocity = -tracker.calculateVelocity().x * sign
        val target = when {
            !completed -> startInPreview
            abs(velocity) > flingThreshold -> velocity > 0
            else -> dragProgress > 0.5f
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.animateTo(if (target) 1f else 0f, PreviewSwipeSpec, initialVelocity = velocity / width)
        }
        if (target != isPreviewMode()) onPreviewModeChange(target)
    }
}
