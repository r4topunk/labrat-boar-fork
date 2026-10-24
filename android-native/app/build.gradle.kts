import java.time.LocalDate
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "team.sopa.boar"
    compileSdk = 36
    // The NDK's strip tool for the engine's libraries: without it they ship with their debug symbols (77 MB libllama-common).
    ndkVersion = "27.1.12297006"

    // The Supabase URL and publishable key the Expo app reads (EXPO_PUBLIC_* in the repo's .env, never committed).
    // Without them the build has no Share button, as the Expo app.
    val env = rootProject.file("../.env").takeIf { it.isFile }?.readLines().orEmpty()
        .mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim().trim('"') } }.toMap()
    // Uncensored and adult models (HfQuery.uncensored) are hidden from Discover GGUF models unless ALLOW_UNCENSORED_MODELS=true
    // (.env, the environment, or -Pboar.allowUncensoredModels=true).
    val allowUncensored = (providers.gradleProperty("boar.allowUncensoredModels").orNull ?: env["ALLOW_UNCENSORED_MODELS"] ?: System.getenv("ALLOW_UNCENSORED_MODELS"))?.trim()?.toBoolean() ?: false
    // Sharing evaluation results with BOAR's server is off unless EVAL_SHARING=true (.env, the environment, or
    // -Pboar.evalSharing=true). Off, the server's address and key aren't built in at all: no Share button, and runs and
    // their files stay on the phone. In two weeks after launch only the team shared a run (Oct 19).
    val evalSharing = (providers.gradleProperty("boar.evalSharing").orNull ?: env["EVAL_SHARING"] ?: System.getenv("EVAL_SHARING"))?.trim()?.toBoolean() ?: false
    defaultConfig {
        buildConfigField("boolean", "ALLOW_UNCENSORED_MODELS", "$allowUncensored")
        buildConfigField("String", "SUPABASE_URL", "\"${if (evalSharing) env["EXPO_PUBLIC_SUPABASE_URL"] ?: "" else ""}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${if (evalSharing) env["EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY"] ?: "" else ""}\"")
        // BoarLite has its own id: it installs beside BOAR (team.sopa.aoair) and never over it or its models.
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
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The Expo app's upload key, named in ~/.gradle/gradle.properties (never in the repo), as plugins/withReleaseSigning.js
    // reads it. Without it the release is debug-signed, which can't install over a store or release build of BOAR.
    val storeFile = providers.gradleProperty("BOAR_UPLOAD_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("BOAR_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("BOAR_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("BOAR_UPLOAD_KEY_PASSWORD").get()
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
    androidResources { localeFilters += "en" }
    // Instrumented tests run against the throwaway devtest install (./gradlew :app:assembleDevtestAndroidTest).
    testBuildType = "devtest"
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { jvmToolchain(17) }

// Data the Expo app ships in the repo's assets/, copied at build time so it stays in one place: the Portuguese name
// lexicon (assets/lexicon/pt-en.json) and the bundled corpus (assets/corpus).
val sharedAssets = layout.buildDirectory.dir("generated/sharedAssets")
val copySharedAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../assets")) {
        include("lexicon/pt-en.json", "corpus/*.json")
    }
    into(sharedAssets)
}
android.sourceSets["main"].assets.srcDir(sharedAssets)
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
    // On-device checks (the PIN, its Keystore key, the sealed database, the PIN screens), run with adb am instrument.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
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
