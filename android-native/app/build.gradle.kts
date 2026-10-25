import java.time.LocalDate
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

// LabRat's data bundle (scripts/stage-bundle.sh <outDir>): with -Pboar.bundleDir=<outDir> the APK carries the model,
// the packs and the index under assets/bundle/, installed on first launch. Without it the APK has no bundle, so
// development builds stay small and get their data from an adb-pushed folder instead (docs/BUILDING-RELEASE.md).
val bundleDir: File? = providers.gradleProperty("boar.bundleDir").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { dir ->
    file(dir).also { root ->
        require(root.resolve("bundle/manifest.json").isFile) { "-Pboar.bundleDir=$dir has no bundle/manifest.json: run scripts/stage-bundle.sh first" }
        checkBundleAgainstCatalog(root.resolve("bundle"), file("src/main/assets/boar-data/catalog.json"))
    }
}

// The staged bundle must still be the one the app's catalog.json describes: stage-bundle.sh checks sizes and sha256 once and
// hard-links the sources, so a catalog edited afterwards (or a source rewritten in place) would build an APK whose first-run
// install ends in a Mismatch on every phone. Every manifest entry must name a catalog file with the same size and sha256,
// and the staged file must have that size (no hashing here: that is stage-bundle.sh's job).
fun checkBundleAgainstCatalog(bundle: File, catalog: File) {
    val slurper = groovy.json.JsonSlurper()
    val models = (slurper.parse(catalog) as Map<*, *>)["models"] as List<*>
    val byFilename = models.map { it as Map<*, *> }.associateBy { it["filename"] as String }
    val entries = slurper.parse(bundle.resolve("manifest.json")) as List<*>
    require(entries.isNotEmpty()) { "${bundle.resolve("manifest.json")} lists no files" }
    for (entry in entries.map { it as Map<*, *> }) {
        val path = entry["path"] as String
        val want = byFilename[path] ?: error("bundle/manifest.json names $path, which catalog.json does not know: run scripts/stage-bundle.sh again")
        require((want["sizeBytes"] as Number).toLong() == (entry["sizeBytes"] as Number).toLong() && want["sha256"] == entry["sha256"]) {
            "bundle/manifest.json disagrees with catalog.json about $path (size or sha256): run scripts/stage-bundle.sh again"
        }
        val staged = bundle.resolve(path)
        require(staged.isFile && staged.length() == (entry["sizeBytes"] as Number).toLong()) {
            "staged $path is missing or is ${staged.length()} bytes, the manifest says ${entry["sizeBytes"]}: run scripts/stage-bundle.sh again"
        }
    }
}

android {
    namespace = "team.sopa.boar"
    compileSdk = 36
    // The NDK's strip tool for the engine's libraries: without it they ship with their debug symbols (77 MB libllama-common).
    ndkVersion = "27.1.12297006"

    defaultConfig {
        // LabRat has its own id: it installs beside BOAR (team.sopa.aoair) and never over it or its models.
        applicationId = "team.sopa.boar.lite"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        // The release name comes from the build (-Pboar.versionName=2.0.0 for a release), the commit and date from git, so
        // About always says exactly which code this APK is.
        versionName = (findProperty("boar.versionName") as String?)?.takeIf { it.isNotBlank() } ?: "2.0.0-dev"
        val gitSha = runCatching { providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim() }.getOrNull().orEmpty()
        val gitDirty = runCatching { providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=no", "--ignore-submodules=all"); isIgnoreExitValue = true }.standardOutput.asText.get().isNotBlank() }.getOrDefault(false)
        // A tree synced without .git (a build machine) names its commit with -Pboar.gitSha=<sha>[+].
        val gitShaOverride = providers.gradleProperty("boar.gitSha").orNull?.trim()?.takeIf { it.isNotEmpty() }
        buildConfigField("String", "GIT_SHA", "\"${gitShaOverride ?: (gitSha + if (gitDirty && gitSha.isNotEmpty()) "+" else "")}\"")
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
        // Whether this APK carries the data bundle (assets/bundle); the app reads the bundle itself, this is for About and logs.
        buildConfigField("boolean", "BUNDLED", "${bundleDir != null}")
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // LabRat's own upload key (D9), named in ~/.gradle/gradle.properties and never in the repo. It is not any earlier app's key,
    // so LabRat never updates a BOAR install. Without it the release is debug-signed.
    val storeFile = providers.gradleProperty("BOARLITE_UPLOAD_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("BOARLITE_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("BOARLITE_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("BOARLITE_UPLOAD_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        debug {
            // Development builds install beside the current BOAR, never over it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // A throwaway install for the on-device tests that wipe data (first-run setup, Purge All Data), so they never
        // touch the debug app someone is using.
        create("devtest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devtest"
            // JaCoCo line coverage of the on-device test runs (adb am instrument -e coverage true; qaCoverageReport).
            enableAndroidTestCoverage = true
            versionNameSuffix = "-devtest"
            // The device tests never carry the multi-GB bundle.
            buildConfigField("boolean", "BUNDLED", "false")
            matchingFallbacks += listOf("debug")
        }
        // Local QA only: the release code, debug-signed, profileable from the shell (perfetto, gfxinfo), installed beside
        // every other BOAR. Compose in a debug build is several times slower, so fluidity is measured here.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-perf"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // BOAR is English only (r4to, Oct 24): no library's translations ship in the APK, so nothing shows in another language.
    androidResources {
        localeFilters += "en"
        // The bundle's files are stored, not deflated: the installer streams them out of the APK, and a 1 GB model
        // gains nothing from compression.
        noCompress += listOf("gguf", "sqlite", "db")
    }
    // Instrumented tests run against the throwaway devtest install (./gradlew :app:assembleDevtestAndroidTest).
    testBuildType = "devtest"
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { jvmToolchain(17) }

// The bundled corpus (assets/corpus at the repo root), copied into the APK at build time.
val sharedAssets = layout.buildDirectory.dir("generated/sharedAssets")
val copySharedAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../assets")) {
        include("corpus/*.json")
    }
    into(sharedAssets)
}
android.sourceSets["main"].assets.srcDir(sharedAssets)
// Release, debug and perf get the bundle; devtest (the device tests' install) never does.
bundleDir?.let { dir -> listOf("release", "debug", "perf").forEach { android.sourceSets[it].assets.srcDir(dir) } }
tasks.named("preBuild") { dependsOn(copySharedAssets) }

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // PDF text for document import (Apache-2.0).
    implementation(libs.pdfbox.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Icons.* (core set only; the few extended icons we need are copied into ui/icons/BoarIcons.kt: no minify in release).
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // On-device checks (chat, setup, settings, knowledge, evaluation), run with adb am instrument.
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Compose ui-test brings espresso-core 3.5.0, which calls InputManager.getInstance (gone on Android 16+): every
    // device test failed on a Pixel 8a on Android 17 (Oct 21). 3.7.0 with androidx.test 1.7.0 does not.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    "devtestImplementation"(libs.compose.ui.test.manifest)
}

// Line coverage of the app module from the device test runs' .ec files (pulled from the phone into build/qa-ec, or
// -Pqa.ecDir=<dir>): ./gradlew :app:qaCoverageReport after assembleDevtest.
tasks.register<JacocoReport>("qaCoverageReport") {
    val ec = providers.gradleProperty("qa.ecDir").orElse(layout.buildDirectory.dir("qa-ec").get().asFile.path)
    executionData.setFrom(fileTree(ec) { include("*.ec") })
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/devtest")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/*ComposableSingletons*")
    })
    sourceDirectories.setFrom("src/main/java")
    reports { csv.required.set(true); html.required.set(true); xml.required.set(false) }
}
