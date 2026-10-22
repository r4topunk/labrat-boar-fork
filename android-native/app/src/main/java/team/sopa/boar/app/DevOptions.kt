package team.sopa.boar.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Developer options: the measurement tools (Evaluation, technical details, answer length, memory numbers) are hidden
 * by default and come back after seven taps on the version in About, as on Android itself. Kept in the app's own
 * preferences, not settings.json, so "Reset settings" leaves them as they are and the engine never sees them.
 */
object DevOptions {
    private const val PREFS = "dev_options"
    private const val KEY = "enabled"
    const val TAPS_TO_UNLOCK = 7

    /** Read by Compose: a screen that checks it recomposes when it changes. */
    private val state = mutableStateOf<Boolean?>(null)

    fun enabled(ctx: Context): Boolean =
        state.value ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false).also { state.value = it }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        state.value = on
    }
}
