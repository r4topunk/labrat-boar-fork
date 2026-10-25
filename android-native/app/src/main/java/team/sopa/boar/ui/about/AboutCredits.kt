package team.sopa.boar.ui.about

/**
 * The open work LabRat (a fork of BOAR) is built on, for About's credits: the libraries in the app, by name and license (android-native
 * NOTICE has the same list with links). Models and packs come from the catalog, each with its own license.
 */
object AboutCredits {
    val libraries: List<Pair<String, String>> = listOf(
        "llama.cpp / ggml" to "MIT",
        "SQLite" to "public domain",
        "Zstandard" to "BSD",
        "PdfBox-Android" to "Apache-2.0",
        "AndroidLM (knowledge-pack search design)" to "Apache-2.0",
        "Baloo 2 and Lexend fonts" to "SIL OFL 1.1",
    )

    /** The license texts in assets/licenses: LabRat's own (MIT) and llama.cpp / ggml's (MIT). */
    val licenseFiles: List<String> = listOf("boarlite-LICENSE.txt", "llama-cpp-ggml-LICENSE.txt")
}
