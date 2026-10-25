> Inherited from BOAR. LabRat removed downloads, model choice, Deep Research beyond the recap, adaptive routing and Portuguese.
> Where this document differs from the code, the code wins.

# BOAR — Adaptive Local Intelligence (Android)

An offline-first mobile AI system for Android exploring local
retrieval and resource-aware inference. Originally built for
the "Best Offline AI Research App" bounty (inspired by @VitalikButerin's post). Hard
constraints this design targets:

- ≤ 12GB peak RAM during inference
- ≤ 50GB total on-disk (app + weights + indexes)
- Works completely offline: the app has no network permission, and a one-time
  first-launch step installs the bundled models and packs (installed = app +
  that setup done).
- No Google Play Services dependency (runs on GrapheneOS)
- Real Android device, not just emulator

## Stack

- **App shell**: native Android, Kotlin + Jetpack Compose (module `android-native/app`).
- **Inference engine**: [llama.cpp](https://github.com/ggml-org/llama.cpp) (a git submodule) called
  through JNI from `android-native/engine` (`BoarNative.kt`). GGUF, mmap weight streaming, Android NDK
  build, no GMS.
- **Default model**: Qwen3 1.7B (Q4_K_M), the only answer model. It ships in the data bundle, together
  with the embedding model, so there is no model picker.
- **Retrieval**: SQLite (FTS5) for lexical search over the local knowledge packs, plus bge-small-en-v1.5
  (MIT, 33M) for a local vector index (brute-force cosine): hybrid BM25 + vector RAG, fully local at
  query time.
- **Storage**: model weights, packs and the search index live in the app's files folder, verified by
  sha256 against `catalog.json`.

## First-run data setup

LabRat is a closed app (the manifest removes the `INTERNET` permission), so nothing is downloaded. Its
models, knowledge packs and search index come in a data bundle (about 3.0 GB) that the build adds to the
APK (`-Pboar.bundleDir`, see `android-native/docs/BUILDING-RELEASE.md`):

1. On launch the app checks whether its data is installed. If not, it shows the "Unpacking LabRat"
   screen (or "LabRat needs its data" when the APK carries no bundle) instead of the chat UI.
2. The installer copies each bundle file into the app's files folder and checks it against its sha256 in
   `catalog.json`. If the copy stops, it resumes at the file it stopped in.
3. Once the data is installed the app moves to the normal chat UI. Chat, retrieval and generation are pure
   on-device code paths, so airplane mode changes nothing.

An APK without a bundle (development builds) reads the same bundle from the app's external files folder
after an `adb push`.

## Directory layout

```
boar-lite/
  android-native/
    app/                      # Compose UI, setup/installer, eval hooks (BenchActivity, DeviceEvalRunner)
    engine/                   # answer pipeline, retrieval, llama.cpp JNI, unit tests with golden data
    scripts/stage-bundle.sh   # stages the data bundle for a release build
    docs/BUILDING-RELEASE.md  # release build, signing, adb-push install
  assets/
    brand/                    # logo sources for the launcher icon and mascot
    corpus/                   # bundled Wikipedia-derived corpora (copied into the APK at build time)
  scripts/
    audit-apk.py              # network-surface audit of an APK
    check-release-apk.sh      # package, versionCode, signer, no INTERNET permission
    offline-proof.sh          # runs the audit and writes a report
    netstats-uid.sh           # on-device traffic counters for one app
```

## Non-negotiables enforced in code

1. No Firebase / GMS / Play Services libraries in `android-native/app/build.gradle.kts`.
2. No network call site in android-native: the manifest removes `INTERNET`, and `scripts/check-release-apk.sh`
   fails a release APK that has it.
3. A visible system-monitor bar (RAM + storage) so the RAM/storage caps are auditable live
   on-device, not just claimed.

## Status

- [x] Native Kotlin + Compose app with a llama.cpp JNI engine (`:app`, `:engine`)
- [x] First-run installer for the bundled data (sha256-verified, resumable)
- [x] FTS5 schema + hybrid (lexical+semantic) retrieval + prompt assembly
- [x] Chat UI: streaming tokens, sources, free follow-ups, onboarding card tour
- [x] Device QA on real Android hardware through the `BenchActivity` eval hook
- [x] Engine unit tests with golden data (`./gradlew :engine:testDebugUnitTest`) + CI
- [ ] Benchmark chosen models on real hardware (tokens/sec, RSS) — not yet measured
- [ ] whisper.cpp binding for voice input on devices with no system speech
      service (GrapheneOS) — documented as future work, not attempted
- [ ] Public demo post (X/Farcaster) + poidh submission
