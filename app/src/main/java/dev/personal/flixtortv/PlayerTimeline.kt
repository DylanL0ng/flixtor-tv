package dev.personal.flixtortv

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable internal fun PlayerTimeline(
    position: Long, duration: Long, regions: List<SkipSegment>, label: String, modifier: Modifier = Modifier,
    buffered: Long = 0, baseStep: Long = 10000, windowStart: Long = 0, windowEnd: Long = duration,
    enabled: Boolean = true, onFocus: (Boolean) -> Unit = {}, onMove: (Long) -> Unit, onCommit: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    val currentPosition by rememberUpdatedState(position)
    val move by rememberUpdatedState(onMove)
    val span = (windowEnd - windowStart).coerceAtLeast(1)
    fun at(x: Float) = (windowStart + (x / width).coerceIn(0f, 1f) * span).toLong().coerceIn(0, duration.coerceAtLeast(0))
    fun fraction(value: Long) = ((value - windowStart).toFloat() / span).coerceIn(0f, 1f)
    Canvas(modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (!enabled || duration <= 0) false
            else if (key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (key.action == KeyEvent.ACTION_DOWN) {
                    val delta = timelineStep(baseStep, key.eventTime - key.downTime, key.repeatCount, span) *
                        if (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    move((currentPosition + delta).coerceIn(0, duration))
                }
                true
            } else if (key.action == KeyEvent.ACTION_DOWN && key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                onCommit(); true
            } else false
        }
        .pointerInput(enabled, windowStart, windowEnd) {
            if (enabled) detectTapGestures { move(at(it.x)); onCommit() }
        }.pointerInput(enabled, windowStart, windowEnd) {
            if (enabled) detectDragGestures(onDragEnd = onCommit) { change, _ ->
                change.consume(); move(at(change.position.x))
            }
        }.pointerInput(enabled, windowStart, windowEnd) {
            if (enabled) awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type == PointerEventType.Scroll) {
                        val amount = event.changes.sumOf { it.scrollDelta.y.toDouble() + it.scrollDelta.x.toDouble() }
                        if (amount != 0.0) {
                            move((currentPosition + (amount * baseStep).toLong()).coerceIn(0, duration))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        }.focusable(enabled).semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(fraction(position), 0f..1f)
            if (enabled) setProgress { value -> move((windowStart + value * span).toLong().coerceIn(0, duration)); true }
        }) {
        val y = size.height / 2
        drawLine(Color.White.copy(alpha = .28f), Offset(0f, y), Offset(size.width, y), 4.dp.toPx())
        if (buffered > windowStart) drawLine(Color.White.copy(alpha = .5f), Offset(0f, y), Offset(size.width * fraction(buffered), y), 4.dp.toPx())
        drawLine(Accent, Offset(0f, y), Offset(size.width * fraction(position), y), 4.dp.toPx())
        regions.filter { it.endMs > windowStart && it.startMs < windowEnd }.forEach {
            val start = fraction(it.startMs); val end = fraction(it.endMs)
            drawRect(Color.White.copy(alpha = .6f), Offset(size.width * start, y - 4.dp.toPx()), Size(size.width * (end - start), 8.dp.toPx()))
        }
        drawCircle(if (focused) Accent else Color.White, if (focused) 7.dp.toPx() else 4.dp.toPx(), Offset(size.width * fraction(position), y))
    }
}
