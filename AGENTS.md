# AGENTS.md — compiling & installing BoarLite from source

This file is for an AI coding agent (or anyone scripting a build) that needs
to go from a clean checkout to a running app on a real Android device — the
exact commands, and the non-obvious constraints that make the naive path
fail. For what the app *is* and how it's designed, read `README.md` and
`ARCHITECTURE.md` first; this file only covers build/install mechanics.

## The one fact that changes everything: the APK is built only from `android-native/`

BoarLite is a native Kotlin + Compose app. The APK is built by Gradle from
`android-native/` only: module `:app` (UI, setup, eval hooks) and module
`:engine` (answer pipeline, retrieval, llama.cpp JNI), with the `llama.cpp`
and `zstd` submodules under `android-native/engine/src/main/cpp/`. There is no
JavaScript layer.

## Prerequisites

- JDK 17 and the Android SDK (Android Studio installs the SDK). Set
  `JAVA_HOME` and `ANDROID_HOME`.
- The NDK and CMake versions pinned in `android-native/engine/build.gradle.kts`
  (`ndkVersion`, `cmake { version }`); install them with `sdkmanager`.
- Submodules: `git submodule update --init --recursive`.
- A physical Android device with USB debugging enabled and `adb` able to see
  it (`adb devices` lists it). Final verification needs a real device, not just
  an emulator, but an emulator works fine for iterating during development.

## Compile from source and install

```bash
git clone <repo-url>
cd <repo>
git submodule update --init --recursive
cd android-native
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release APK

```bash
cd android-native
bash scripts/stage-bundle.sh <out> --model <Qwen3-1.7B-Q4_K_M.gguf> --embedding <bge-small-en-v1.5-q8_0.gguf>
./gradlew :app:assembleRelease -Pboar.bundleDir=<out>
../scripts/check-release-apk.sh app/build/outputs/apk/release/app-release.apk
```

Signing uses `BOARLITE_UPLOAD_STORE_FILE`, `BOARLITE_UPLOAD_STORE_PASSWORD`,
`BOARLITE_UPLOAD_KEY_ALIAS` and `BOARLITE_UPLOAD_KEY_PASSWORD` in
`~/.gradle/gradle.properties` (never in the repo). Without them the release
build is debug-signed, which is fine for your own phone. An APK signed with a
different key can't install over an existing BoarLite: Android requires
uninstalling first, which deletes the app's data. Full steps:
`android-native/docs/BUILDING-RELEASE.md`.

## After install: the app needs its data bundle

The app has no network permission, so the Qwen3 1.7B model, the search model, the
knowledge packs and the search index come in a data bundle. A release APK built
with `-Pboar.bundleDir` carries it and copies it on first launch ("Unpacking
BoarLite"). An APK without a bundle shows the "BoarLite needs its data" screen
until the bundle is pushed with `adb` (see `BUILDING-RELEASE.md`, section 3).
If you're scripting an unattended install-and-verify flow, one of the two has to
happen before the app is usable.

## Verifying the build without a device (what an agent without device access can actually check)

```bash
cd android-native
./gradlew :engine:testDebugUnitTest :app:compileDebugKotlin
# device tests (compile only without a phone): ./gradlew :app:assembleDevtestAndroidTest
```

Neither of these proves the native build/install actually works on a phone —
they only prove the Kotlin layer is internally consistent. There is no way to
verify a real device install without a real device; don't claim success from
the unit tests alone if the task was specifically about building/installing.

## Before pushing: CodeRabbit review

Run a local CodeRabbit review before every `git push`, in addition to
the Gradle checks:

```bash
cr doctor                          # once: CLI installed and signed in
cr review --agent --base main      # this branch against main, structured findings
cr review --agent --uncommitted    # or only what isn't committed yet
```

A review can take
several minutes, so run it in the background. Check each finding against the
current code before acting on it: fix the ones that hold up, and note in one
line why you skipped the others. Re-run once after fixing, not in a loop.

If `cr doctor` says you are signed out, ask the person you're working with to
run `cr auth login` (a browser sign-in) before you push. If they choose to push
without the review, say so in the PR.

## Benchmarking on a connected phone

With a phone on USB, an agent can run an evaluation without anyone tapping the
screen. `BenchActivity` polls for a request written by the host
(`DeviceEvalRunner`), so the host side is one `am start`:

```bash
adb devices                                  # exactly one device, state "device"
adb shell am start -n <pkg>/team.sopa.boar.bench.BenchActivity \
  --es run eval --es request64 <base64 of the request JSON>
```

The results are copied to `Android/data/<pkg>/files/eval-out`, where `adb pull`
can read them (a release build can't be reached with `run-as`).

Before and during a run:

- **Check the phone isn't busy.** The phone's owner may be using it: ask first.
- **Keep the screen on.** Locking the phone backgrounds the app. If the phone
  won't accept `settings put global stay_on_while_plugged_in`, send
  `adb shell input keyevent KEYCODE_WAKEUP` every 30 seconds for the length of
  the run, then stop.
- **Report only what the phone measured.** The JSONL is the source of truth;
  a model that fails to load is recorded as failures, not skipped.

## Common pitfalls

- **Don't run destructive git/native-reset commands to "fix" a build
  problem** (`rm -rf android-native/*/build`, force-pushes, etc.) without
  checking `git status` first and understanding why the build actually failed.
- **The submodules must be initialised**: a build without `llama.cpp` and `zstd`
  fails in the CMake step of `:engine`.
