package team.sopa.boar.engine.models

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * File and storage sizes in decimal units, as Android shows them:
 * "18.6 MB", "986 MB", "2.5 GB"; "18,6 MB" in Portuguese. RAM is not formatted here.
 */
object Units {
    private const val KB = 1000.0
    private const val MB = 1000 * KB
    private const val GB = 1000 * MB

    private fun number(value: Double, locale: Locale, digits: Int): String =
        // HALF_UP matches Intl.NumberFormat; Java's default HALF_EVEN would round 18.65 down.
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = digits; minimumFractionDigits = 0; roundingMode = java.math.RoundingMode.HALF_UP }.format(value)

    private fun scaled(bytes: Double, unit: Double, locale: Locale): String {
        val v = bytes / unit
        return number(v, locale, if (v >= 99.95) 0 else 1)
    }

    fun formatBytes(bytes: Long, locale: Locale = Locale.ENGLISH): String {
        val b = max(bytes, 0L).toDouble()
        return when {
            b >= 999.5 * MB -> "${scaled(b, GB, locale)} GB"
            b >= 999.5 * KB -> "${scaled(b, MB, locale)} MB"
            else -> "${number(if (b > 0) max(1.0, (b / KB).roundToLong().toDouble()) else 0.0, locale, 0)} kB"
        }
    }
}
