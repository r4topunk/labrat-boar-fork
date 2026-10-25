package team.sopa.boar.engine.settings

/**
 * Settings → "Free memory when I leave BOAR": a while after BOAR leaves the screen, the model (3-6 GB) is unloaded.
 * The next answer loads it again. The rules are here, apart from Android, so they're tested.
 */
object FreeMemory {
    /**
     * Auto's delay, in seconds: none. Was 5 minutes, then 10 s. Xiaomi's memory manager freezes BOAR about 8 s after it
     * leaves the screen and force-stops it 20-30 s later with the model still loaded (F3 and X6, Oct 19 chaos runs 6 and 7);
     * a frozen app can't run a timer, so the model goes at once. An answer still being written finishes first.
     */
    const val AUTO_SECONDS = 0
    /** How often a timer that fired during an answer looks again: well under MIUI's freeze about 8 s after Home. */
    const val BUSY_POLL_MS = 500L

    /** The delay for the user's choice in minutes: Auto (null) is [AUTO_SECONDS]; a negative value counts as Immediately. */
    fun delayMs(minutes: Int?): Long = if (minutes == null) AUTO_SECONDS * 1_000L else minutes.coerceAtLeast(0) * 60_000L

    enum class Decision { UNLOAD, WAIT, SKIP }

    /**
     * When the timer fires: nothing to do if the setting is off, BOAR is back on screen or no model is loaded; an answer,
     * a chat title, or a load still running is waited for; otherwise the model goes.
     */
    fun decide(enabled: Boolean, inForeground: Boolean, loaded: Boolean, busy: Boolean): Decision = when {
        !enabled || inForeground -> Decision.SKIP
        // Busy first: a model still loading isn't "loaded" yet, and skipping would leave it in memory once it is.
        busy -> Decision.WAIT
        !loaded -> Decision.SKIP
        else -> Decision.UNLOAD
    }
}
