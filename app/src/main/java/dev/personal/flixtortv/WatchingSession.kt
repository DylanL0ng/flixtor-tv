package dev.personal.flixtortv

/** In-memory attendance for one playback session. Time is wall-clock playing time, never seek position. */
internal class WatchingSession(
    private val now: () -> Long,
    private val minimumWatchMs: Long = 90L * 60 * 1000,
    private val minimumEpisodes: Int = 3,
    private val responseTimeoutMs: Long = 60_000,
) {
    var token = 0L
        private set
    var finished = false
        private set
    var pending = false
        private set
    private var watchedMs = 0L
    private var episodes = 0
    private var playing = false
    private var foreground = false
    private var remainingMs = responseTimeoutMs
    private var lastUpdate = now()

    private fun settle() {
        val time = now()
        val elapsed = (time - lastUpdate).coerceAtLeast(0)
        if (foreground) {
            if (pending) remainingMs = (remainingMs - elapsed).coerceAtLeast(0)
            else if (playing) watchedMs += elapsed
        }
        lastUpdate = time
    }

    fun reset() {
        token++ // Disposed players cannot contribute to a new session.
        watchedMs = 0; episodes = 0; playing = false; foreground = false
        finished = false; pending = false; remainingMs = responseTimeoutMs; lastUpdate = now()
    }

    fun start(automatic: Boolean = false): Long {
        if (!automatic) reset() else {
            settle(); token++; playing = false; finished = false
        }
        return token
    }

    fun playback(token: Long, isPlaying: Boolean, isForeground: Boolean) {
        if (token != this.token) return
        settle(); playing = isPlaying; foreground = isForeground
    }

    fun interact() {
        settle(); watchedMs = 0; episodes = 0; remainingMs = responseTimeoutMs
        // Confirmation remains latched; navigating is not consent to start another episode.
    }

    fun complete(token: Long, episode: Boolean) {
        if (token != this.token || finished) return
        settle(); playing = false; finished = true
        if (episode) episodes++
    }

    fun requireConfirmation(token: Long): Boolean {
        if (token != this.token || !finished) return false
        settle()
        if (!pending && !playing && episodes >= minimumEpisodes && watchedMs >= minimumWatchMs) {
            pending = true; remainingMs = responseTimeoutMs
        }
        return pending
    }

    /** Credits may advance early, unless this completion would require attendance. */
    fun confirmationDueAtCredits(token: Long): Boolean {
        if (token != this.token || pending) return true
        settle()
        return episodes + (if (finished) 0 else 1) >= minimumEpisodes && watchedMs >= minimumWatchMs
    }

    val responseSeconds: Int?
        get() {
            settle()
            return if (pending) ((remainingMs + 999) / 1000).toInt() else null
        }
}
