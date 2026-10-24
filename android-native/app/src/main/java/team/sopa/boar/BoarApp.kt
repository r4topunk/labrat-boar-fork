package team.sopa.boar

import android.app.Application
import java.util.Locale

/**
 * BOAR is English only (r4to, Oct 24). The texts live in res/values alone, and this makes the numbers, sizes and dates
 * the app formats with the default locale English too, on a phone set to Portuguese or any other language.
 */
class BoarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Locale.setDefault(Locale.ENGLISH)
    }
}
