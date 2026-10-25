package team.sopa.boar.ui.setup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import team.sopa.boar.setup.KnowledgeTheme

/**
 * The tour's line icons (24 dp, 2 dp stroke, round caps), drawn as in the onboarding design: the knowledge themes, the
 * calculator and the privacy rows. Not in Material's core set, and the extended set is not a dependency (BoarIcons).
 */
internal object TourIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder("Tour.$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    val Book by lazy { stroke("Book", "M4 19V5a2 2 0 0 1 2-2h14v16H6a2 2 0 0 0-2 2z", "M8 7h8") }
    val Globe by lazy { stroke("Globe", circle(12f, 12f, 9f), "M3 12h18", "M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18") }
    val Compass by lazy { stroke("Compass", circle(12f, 12f, 9f), "M15.5 8.5l-2 5-5 2 2-5z") }
    val Health by lazy { stroke("Health", "M12 20s-8-4.8-8-10.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 8 2.5C20 15.2 12 20 12 20z", "M8 12h2l1-2 2 4 1-2h2") }
    val Coins by lazy { stroke("Coins", circle(9f, 10f, 6f), "M14.5 6.2a6 6 0 1 1-4.7 9.7") }
    val Cooking by lazy { stroke("Cooking", "M4 10h16v4a6 6 0 0 1-6 6h-4a6 6 0 0 1-6-6z", "M2 10h20", "M9 6c0-1 1-1 1-2", "M14 6c0-1 1-1 1-2") }
    val More by lazy { stroke("More", "M3 7l9-4 9 4v10l-9 4-9-4z", "M3 7l9 4 9-4", "M12 11v10") }
    val Calculator by lazy {
        stroke("Calculator", "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 7h8",
            "M8.5 12h.01M12 12h.01M15.5 12h.01M8.5 16h.01M12 16h.01M15.5 16h.01")
    }
    /** Whole wifi arcs under one slash (cut arcs read as a clipped glyph at badge size). */
    val Offline by lazy { stroke("Offline", "M3 3l18 18", "M2 8.8a15 15 0 0 1 20 0", "M5 12.6a10 10 0 0 1 14 0", "M8.5 16.4a5 5 0 0 1 7 0", "M12 20h.01") }
    val Crypto by lazy { stroke("Crypto", "M12 2l6 10-6 4-6-4z", "M6 14l6 8 6-8-6 4z") }
    val Device by lazy { stroke("Device", "M9 2h6a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2") }
    val Shield by lazy { stroke("Shield", "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z") }

    /** The icon of a knowledge theme, by its tour group key (KnowledgeCatalog.tourGroups). */
    fun forGroup(titleKey: String): ImageVector = when (titleKey) {
        "clean.tour.group.encyclopedia" -> Book
        "clean.tour.group.countries" -> Globe
        "clean.tour.group.travel" -> Compass
        "clean.tour.group.health" -> Health
        "clean.tour.group.money" -> Coins
        "clean.tour.group.cooking" -> Cooking
        else -> More
    }

    /** The icon of a Knowledge row: its theme's, except the reference packs, which each get their own subject. */
    fun forPack(id: String, theme: KnowledgeTheme): ImageVector = when (id) {
        "boar-factbook" -> Globe
        "qa-money-se" -> Coins
        "qa-cooking-se" -> Cooking
        else -> when (theme) {
            KnowledgeTheme.ENCYCLOPEDIA -> Book
            KnowledgeTheme.HEALTH -> Health
            KnowledgeTheme.TRAVEL -> Compass
            KnowledgeTheme.REFERENCE -> Globe
            KnowledgeTheme.CRYPTO -> Crypto
            KnowledgeTheme.OTHER -> More
        }
    }
}
