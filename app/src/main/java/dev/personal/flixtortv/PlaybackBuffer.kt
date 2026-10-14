package dev.personal.flixtortv

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

internal fun bufferByteTarget(heap: Long): Int = (heap / 4).coerceAtMost(96L * 1024 * 1024).toInt()
internal fun bufferReady(aheadMs: Long, remainingMs: Long, bytes: Int, limit: Int): Boolean =
    aheadMs >= minOf(90000L, remainingMs) || bytes >= limit

/** Keep Media3's preparation/start behavior and allocator; extend only loading policy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackBuffer : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE), 50000, 50000, 2500, 5000,
    bufferByteTarget(Runtime.getRuntime().maxMemory()), false, 0, false,
) {
    @Volatile var larger = false
    val byteTarget = bufferByteTarget(Runtime.getRuntime().maxMemory())
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val normal = super.shouldContinueLoading(parameters)
        return if (!larger) normal else parameters.bufferedDurationUs < 90000000L &&
            allocator.totalBytesAllocated < byteTarget
    }
}
