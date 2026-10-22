package team.sopa.boar.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why BOAR's last process ended, as Android recorded it (API 30+), for LoadGuard (issue #89): a model marker left by a
 * process the user swiped away, force-stopped or updated isn't a load that ran out of memory.
 */
object ExitReason {
    /** True only when Android recorded a non-memory exit at or after [since] (ms); false when unsure. */
    fun notALoadKill(context: Context, since: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return false
        if (last.timestamp < since) return false
        return isUserOrUpdate(last.reason, last.description)
    }

    /** Swiped away, force-stopped, stopped from Settings, updated or reinstalled. Low memory, crashes and the rest count. */
    fun isUserOrUpdate(reason: Int, description: String?): Boolean = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> true
        // A swipe from Recents is recorded as "other" with this description on several Android builds.
        ApplicationExitInfo.REASON_OTHER -> description?.contains("remove task", ignoreCase = true) == true
        else -> false
    }
}
