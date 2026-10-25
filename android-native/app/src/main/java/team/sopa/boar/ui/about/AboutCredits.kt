package team.sopa.boar.ui.about

/**
 * The open work LabRat (a fork of BOAR) is built on, for About's credits: the libraries in the app, by name and license (android-native
 * NOTICE has the same list with links). Models and packs come from the catalog, each with its own license.
 */
object AboutCredits {
    val libraries: List<Pair<String, String>> = listOf(
        "BOAR, the app LabRat is based on (github.com/rferrari/boar-app)" to "MIT",
        "llama.cpp / ggml" to "MIT",
        "SQLite" to "public domain",
        "Zstandard" to "BSD",
        "PdfBox-Android" to "Apache-2.0",
        "AndroidLM (knowledge-pack search design)" to "Apache-2.0",
        "Baloo 2 and Lexend fonts" to "SIL OFL 1.1",
        "Jetpack Compose, AndroidX and Material icons" to "Apache-2.0",
        "Kotlin coroutines and serialization" to "Apache-2.0",
    )

    /** The license texts in assets/licenses, each under a heading that says what it covers. */
    val licenseFiles: List<Pair<String, String>> = listOf(
        "LabRat and BOAR (MIT)" to "labrat-LICENSE.txt",
        "llama.cpp / ggml (MIT)" to "llama-cpp-ggml-LICENSE.txt",
        "Apache License 2.0: Qwen3, PdfBox-Android, AndroidLM's search design, Jetpack Compose, AndroidX, Material icons, Kotlin libraries" to "apache-2.0-LICENSE.txt",
        "SIL Open Font License 1.1: Baloo 2" to "baloo2-OFL.txt",
        "SIL Open Font License 1.1: Lexend" to "lexend-OFL.txt",
        "Zstandard (BSD)" to "zstd-LICENSE.txt",
    )
}
