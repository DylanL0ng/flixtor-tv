package dev.personal.flixtortv

/** Remote repeats accelerate by elapsed hold time; a new press/direction starts fine. */
internal fun timelineStep(baseMs: Long, heldMs: Long, repeat: Int, durationMs: Long): Long {
    if (repeat == 0) return baseMs
    return when {
        heldMs >= 5000 -> maxOf(baseMs * 30, (durationMs / 20).coerceAtMost(600000))
        heldMs >= 3000 -> baseMs * 18
        heldMs >= 1500 -> baseMs * 6
        heldMs >= 500 -> baseMs * 3
        else -> baseMs
    }
}

/** One countdown survives credits → media end. Elapsed time excludes suspended UI. */
internal class NextEpisodeCountdown {
    var remainingMs = 5000L
        private set
    var cancelled = false
        private set
    private var last = 0L
    private var running = false
    private var fired = false
    fun cancel() { cancelled = true; running = false }
    fun tick(now: Long, eligible: Boolean, canRun: Boolean): Boolean {
        if (!eligible) { remainingMs = 5000; running = false; fired = false; last = now; return false }
        if (running && canRun && !cancelled) remainingMs = (remainingMs - (now - last).coerceAtLeast(0)).coerceAtLeast(0)
        last = now; running = canRun && !cancelled
        if (!running || fired || remainingMs > 0) return false
        fired = true
        return true
    }
}

internal fun episodeDrawerTarget(episodes: List<Episode>, current: Episode?): Pair<Int, Int>? {
    val target = episodes.firstOrNull { it.key == current?.key } ?: episodes.firstOrNull() ?: return null
    return target.season to episodes.filter { it.season == target.season }.indexOfFirst { it.key == target.key }
}
