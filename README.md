<p align="center">
  <img src="./assets/brand/labrat-mark.svg" width="112" alt="LabRat logo" />
</p>

<h1 align="center">LabRat</h1>

<p align="center">
  <b>AI that works when the internet doesn't.</b><br />
  A no-network, offline Android research assistant: the model and the knowledge ship inside the APK, answers show their sources, and the app has no network permission at all.
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/platform-Android-FFB547" />
  <img alt="No network permission" src="https://img.shields.io/badge/network-none-17110D" />
  <img alt="English only" src="https://img.shields.io/badge/language-English-17110D" />
  <a href="./LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-FF7A3D" /></a>
</p>

<p align="center">
  <a href="#what-it-is">What it is</a> ·
  <a href="#how-it-answers">How it answers</a> ·
  <a href="#what-it-can-cite">What it can cite</a> ·
  <a href="#install-on-a-phone">Install</a> ·
  <a href="#results">Results</a> ·
  <a href="#build-from-source">Build from source</a> ·
  <a href="#based-on-boar">Based on BOAR</a> ·
  <a href="#docs">Docs</a>
</p>

## What it is

Search and chat assistants stop working the moment you lose signal: on a plane, a
trail, a border crossing, a blackout. LabRat answers in airplane mode, with no
account, no server and no Google Play Services.

LabRat is a **no-network offline build** (package `team.sopa.boar.lite`): the APK declares no
`INTERNET` permission, so the app itself cannot send anything you ask, read or import over a network. Everything
the app needs is built into the APK (about 3.0 GB) and unpacked on first launch:

- the answer model, **Qwen3 1.7B** (Q4_K_M), the only one: there is no model choice;
- the search (embedding) model, BGE Small;
- **13 knowledge packs** (the "K13 kit") and the prebuilt search index.

It is **English only** and gives **Succinct** answers only: no Deep Research, no Detailed mode,
no length setting.

## How it answers

- **Retrieval is local and hybrid:** SQLite FTS5 keyword search plus on-device embeddings, over
  the packs and over any documents you imported. Inference is `llama.cpp` through JNI.
- **Every answer the model writes shows its sources** and the time it took, and ends with a fixed line:
  *May contain errors; check the source.*
- **Health questions go through a lexical gate** (a word and phrase check, not a model
  judgment). If the search kept no passage from a health pack (first aid, MedlinePlus, Medical
  Sciences Q&A), the app gives a fixed safe text and the emergency number instead of an answer
  from the model's memory; with a health passage kept, the model answers with that passage in its context. Health search
  runs without the extra search port (mode `ON_NO_HEALTH`). Health content is AI-reviewed, not
  clinically reviewed: general information, not medical advice.
- **Follow-ups are free** and keep up to the last three exchanges. There is no long-chat summary.
- **Quick math** (a unit conversion, a percentage or plain arithmetic) is computed directly,
  with no model and no source list. A health question goes through the health gate first; a cold-or-heat
  exposure question that also asks for a conversion gets the computed line with a health passage or the fixed safe text.
- **A short tour** runs while the bundle unpacks on first launch.

## What it can cite

The 13 packs of the K13 kit, plus the Wikipedia essentials collections (1,000 and 4,000 topics) and
their prebuilt index. Each pack keeps its own license, listed in the app under Settings → About →
Credits; sources, snapshot dates and download URLs are in [docs/PACKS.md](docs/PACKS.md).

| Pack id | What | License | Size | sha256 |
|---|---|---|---|---|
| `wiki-vital5` | Wikipedia Vital Articles (50,000 introductions) | CC BY-SA 4.0 | 164 MB | `d3b87d56…c666` |
| `boar-simplewiki-en` | Simple English Wikipedia (dump 2026-10-01) | CC BY-SA 4.0 | 736 MB | `99bcec30…7472` |
| `boar-wikivoyage-en` | Wikivoyage travel guides | CC BY-SA 4.0 | 351 MB | `ff8595e3…b66e` |
| `boar-factbook` | CIA World Factbook (archive, 2026-09-11) | CC0 | 12 MB | `c5854353…3447` |
| `boar-firstaid-en` | First aid and emergency | CC BY-SA 4.0; US public domain | 26 MB | `0a7dffa6…e9c6` |
| `boar-medlineplus-en` | MedlinePlus health topics (2026-10-10) | Public domain (NLM) | 5 MB | `e805ccea…ebd0` |
| `boar-visa-en` | Visa requirements and policies (Wikipedia, 2026-10-01) | CC BY-SA 4.0 | 11 MB | `d1dd0ffb…ad20` |
| `boar-crypto` | Crypto and Ethereum reference | Mixed, per document (CC0, PD, BSD, MIT, CC BY, CC BY-SA) | 36 MB | `ae9fbd2c…31fb` |
| `qa-health-se` | Medical Sciences Stack Exchange Q&A | CC BY-SA 3.0/4.0 per post | 29 MB | `f1ba1a4b…0eaf` |
| `qa-outdoors-se` | The Great Outdoors Stack Exchange Q&A | CC BY-SA 3.0/4.0 per post | 43 MB | `11bcb96f…291d` |
| `qa-travel-se` | Travel Stack Exchange Q&A | CC BY-SA 3.0/4.0 per post | 212 MB | `ffdebe54…69d3` |
| `qa-cooking-se` | Seasoned Advice (cooking) Stack Exchange Q&A | CC BY-SA 2.5/3.0/4.0 per post | 89 MB | `81990fa9…ba35` |
| `qa-money-se` | Personal Finance & Money Stack Exchange Q&A | CC BY-SA 2.5/3.0/4.0 per post | 137 MB | `37411234…5a88` |

Full sha256 values are in [catalog.json](android-native/app/src/main/assets/boar-data/catalog.json) and
[docs/PACKS.md](docs/PACKS.md); the app checks every file against them when it unpacks the bundle.

**Your files:** `.txt`, `.md`, `.csv`, `.json` and `.pdf` (selectable text, no OCR) are chunked
and embedded on the phone and stay there. You can export a collection or an evaluation run as a
file through Android's share sheet, but only when you choose to, and the app itself never
sends anything.

## Install on a phone

No computer needed. LabRat is not on any app store: you download one APK (about 3.04 GB) that
carries everything, the model, the search index and the 13 packs.

- APK: {{HF_APK_URL}}
- sha256: `{{APK_SHA256}}`

**Requirements:** Android 8.0 or newer (minSdk 26), or GrapheneOS; no Google Play Services. About
6-9 GB of free storage: the 3 GB download, the installed app and the 3 GB unpacked on first launch
(delete the downloaded file after installing and about 6 GB is enough). Wi-Fi for the download
only. Tested on a Pixel 8a (8 GB RAM, GrapheneOS), where the app peaked at 3.9 GB of memory.

1. Open the APK link in the phone's browser and download the file.
2. Tap the downloaded file. If Android says the browser is not allowed to install apps, open the
   setting it offers and turn on **Allow from this source** ("Install unknown apps"), then go back.
3. Tap **Install**. Google Play Protect may warn that the app is unknown; LabRat is not on any
   store, so this is expected (you can scan it, then **Install anyway**). Installing a 3 GB APK
   takes a few minutes.
4. Open LabRat. The first launch unpacks the bundle in about 16 s behind a short tour, with no
   connection.
5. Turn on airplane mode and ask a question.

**With a computer (optional):** check the file with `shasum -a 256 <file>.apk` (macOS) or
`sha256sum <file>.apk` (Linux): it must print the sha256 above. Then, with USB debugging on,
`adb install -r <file>.apk`. Installing with adb also avoids the extra warning flow that Android's
developer verification may show for browser installs on Google-certified phones in some countries.

**Your data:** it stays on the phone, outside cloud backup and device transfer. It is deleted when
you uninstall the app, or from Settings → Start over → Erase everything (every model and pack,
your documents and all chats).

## Results

Measured on a Pixel 8a in airplane mode, over 71 questions with two judge families, LabRat scores
**0.165 (Claude judge) to 0.242 (Codex judge)** of a frontier model with live web search, and wins
**0 of 71** head to head. **The bounty's bar (more than half as good, 0.50) is not met.** It answers
in about 14 s (median), peaks at 3.9 GB of memory and sends 0 bytes. On questions written from its
own packs it is correct 40% of the time. Details, confidence intervals, health-safety probes and
limitations: [RESULTS.md](RESULTS.md).

## Build from source

You need git, JDK 17, the Android SDK with the NDK and CMake versions pinned in
`android-native/engine/build.gradle.kts`, bash, curl and python3, and about 10 GB of free disk.

```bash
git clone https://github.com/r4topunk/labrat-boar-fork.git
cd labrat-boar-fork
git submodule update --init --recursive          # llama.cpp and zstd

# 1. Download the bundle inputs (2 models, the index, 13 packs; about 3.0 GB), each checked by sha256
bash scripts/fetch-bundle-inputs.sh ../labrat-inputs

# 2. Stage the bundle (hard links, no copy) and build the release APK
cd android-native
bash scripts/stage-bundle.sh ../../labrat-bundle --inputs ../../labrat-inputs
export JAVA_HOME=<your JDK 17> ANDROID_HOME=<your Android SDK>
./gradlew :app:assembleRelease -Pboar.bundleDir=$PWD/../../labrat-bundle
../scripts/check-release-apk.sh --any-signer app/build/outputs/apk/release/app-release.apk   # no INTERNET permission
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without signing keys in `~/.gradle/gradle.properties` the release is debug-signed, which is fine
for your own phone, but it cannot install over a LabRat signed with another key (uninstall first).
A debug build without the bundle (`./gradlew :app:assembleDebug`) stays small and takes the bundle
by `adb push`. Full steps: [android-native/docs/BUILDING-RELEASE.md](android-native/docs/BUILDING-RELEASE.md)
and [AGENTS.md](AGENTS.md).

## Based on BOAR

LabRat is a fork of [BOAR](https://github.com/rferrari/boar-app) (MIT), the open-source
offline research app by its community of contributors, forked at `rferrari/boar-app@0be84acd`.
The engine, the pack search design and much of the UI come from BOAR; LabRat fixes one model, one
language and one answer style, and removes everything that needs a network. BOAR's own README,
release badges, benchmarks and store or download instructions describe that project, not this
build. The MIT license and copyright notice are in [LICENSE](LICENSE), and third-party credits are
in [android-native/NOTICE](android-native/NOTICE).

## Docs

| | |
|---|---|
| [RESULTS.md](RESULTS.md) | Benchmark, speed and safety results measured on a phone |
| [docs/PACKS.md](docs/PACKS.md) | Every model, pack and index: source, snapshot, license, sha256, download URL |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Design notes (inherited from BOAR; where it differs, this README and the code win) |
| [android-native/docs/BUILDING-RELEASE.md](android-native/docs/BUILDING-RELEASE.md) | Building a release APK with its data bundle, signing, adb-push install |
| [AGENTS.md](AGENTS.md) | Compile, install and benchmark from source |
| [PRIVACY.md](PRIVACY.md) · [TERMS.md](TERMS.md) | Privacy notice and terms for LabRat |
| [SUBMISSION_CHECKLIST.md](SUBMISSION_CHECKLIST.md) | Release and submission checks |

## License

LabRat's code is under the [MIT License](LICENSE), like BOAR's. The models and packs keep their own
licenses (Apache-2.0, MIT, CC BY-SA, CC0, public domain): see [docs/PACKS.md](docs/PACKS.md) and
[android-native/NOTICE](android-native/NOTICE).
