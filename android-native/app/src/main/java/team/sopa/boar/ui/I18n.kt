package team.sopa.boar.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The Expo app's i18next texts (res/values/strings.xml and values-pt, from scripts/export-native-strings.mjs), by their TS keys:
 * t("chatScreen.roleYou"), t("chatScreen.reasoningElapsed", "seconds" to 4). "{{var}}" placeholders are filled here,
 * and count-based keys pick key_one / key_other by the language's rule, as i18next does.
 */
object I18n {
    private val ids = ConcurrentHashMap<String, Int>()

    private fun resName(key: String) = key.replace('.', '_').replace("-", "__")

    private fun id(context: Context, key: String): Int = ids.getOrPut(key) {
        context.resources.getIdentifier(resName(key), "string", context.packageName)
    }

    /** i18next's plural category (Intl.PluralRules): English "one" is 1, Portuguese "one" is 0 and 1. */
    fun pluralCategory(count: Number, language: String): String {
        val d = count.toDouble()
        val integer = d == kotlin.math.floor(d)
        return when (language) {
            "pt" -> if (integer && (d == 0.0 || d == 1.0)) "one" else "other"
            else -> if (integer && d == 1.0) "one" else "other"
        }
    }

    fun t(context: Context, key: String, vararg args: Pair<String, Any?>): String {
        val count = args.firstOrNull { it.first == "count" }?.second as? Number
        val language = context.resources.configuration.locales[0]?.language ?: Locale.getDefault().language
        var resolved = key
        if (count != null) {
            val plural = "${key}_${pluralCategory(count, language)}"
            if (id(context, plural) != 0) resolved = plural else if (id(context, "${key}_other") != 0) resolved = "${key}_other"
        }
        // Native wording wins where it differs from the Expo app's (strings.xml is generated from the shared locales):
        // "native.interfaceSettings.hapticFeedbackValue" over "interfaceSettings.hapticFeedbackValue".
        if (!resolved.startsWith("native.") && id(context, "native.$resolved") != 0) resolved = "native.$resolved"
        val rid = id(context, resolved)
        var s = if (rid != 0) context.getString(rid) else key
        for ((name, value) in args) s = s.replace("{{$name}}", value?.toString() ?: "")
        return s
    }
}

/** t("chatScreen.roleYou") in a composable. */
@Composable
fun t(key: String, vararg args: Pair<String, Any?>): String = I18n.t(LocalContext.current, key, *args)
