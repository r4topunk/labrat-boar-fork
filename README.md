<p align="center">
  <img src="./assets/brand/boarlite-mark.svg" width="112" alt="BoarLite logo" />
</p>

<h1 align="center">BOAR</h1>

<p align="center">
  <b>AI that works when the internet doesn't.</b><br />
  An open-source research assistant that runs entirely on your phone, and shows where every answer came from.
</p>

<p align="center">
  <a href="https://github.com/rferrari/boar-app/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/rferrari/boar-app?color=FF7A3D" /></a>
  <img alt="Android" src="https://img.shields.io/badge/platform-Android-FFB547" />
  <img alt="Works offline" src="https://img.shields.io/badge/works-offline-17110D" />
  <a href="./LICENSE"><img alt="License" src="https://img.shields.io/github/license/rferrari/boar-app" /></a>
</p>

<p align="center">
  <img src="./docs/readme/hero.gif" width="640" alt="BOAR in 10 seconds: set up once, ask anything in airplane mode, get answers with sources" />
</p>

<p align="center">
  <a href="#get-it">Get it</a> ·
  <a href="#how-it-answers">How it answers</a> ·
  <a href="#measured-on-a-real-phone">Benchmarks</a> ·
  <a href="#bounty-requirements">Requirements</a> ·
  <a href="#docs">Docs</a> ·
  <a href="./MANIFESTO.md">Manifesto</a>
</p>

## Why

Search and chat assistants stop working the moment you lose signal: on a plane, a
trail, a border crossing, a blackout. BOAR downloads a small language model and
the knowledge you pick **once**, then answers in airplane mode, with no account,
no server and no Google Play Services.

It started from Vitalik's call for an offline research tool that is "more than
half as good" as search plus a frontier model, running an extreme mixture of
experts that mostly sits on disk. That model doesn't exist yet, so BOAR is two
things: a useful offline companion today, and a workbench that measures how close
a phone can get. What we believe: [MANIFESTO.md](MANIFESTO.md).

<table>
<tr>
<td width="33%"><img src="docs/readme/answers-sources.jpg" alt="An answer with its sources on the phone"></td>
<td width="33%"><img src="docs/readme/offline.jpg" alt="An answer in airplane mode, with the OFFLINE pill in the header"></td>
<td width="33%"><img src="docs/readme/drawer.jpg" alt="The menu: research sessions, documents and settings, with live RAM and disk use"></td>
</tr>
<tr>
<td><b>Answers with sources.</b> Tap a citation to read the exact passage.</td>
<td><b>Works in airplane mode.</b> The header says OFFLINE, and the answer still comes.</td>
<td><b>Everything stays on the phone.</b> Your sessions and documents, with RAM and disk shown live.</td>
</tr>
</table>

**Android first.** `main` is the Android app: an APK you install yourself, with no Play Store or Google account needed. Search, packs, models, measurements and sharing are one shared engine; on top of it sits an app built for mid-range phones, in the Campfire and Moonlight themes. The iPhone app is built from the same engine on the [`ios`](https://github.com/rferrari/boar-app/tree/ios) branch.

## Online once, offline from then on

<img src="docs/readme/offline.png" alt="First launch downloads the models once; after that chat, search and sources work in airplane mode" />

First launch downloads an answer model plus a small embedding model: Qwen3-4B
(about 2.5 GB) by default, or the compact Qwen2.5-1.5B (about 1 GB) for phones
with less RAM. That is the only network use the app needs. The network
permission stays for two things you start yourself: downloading more models or
packs, and searching Hugging Face for other GGUF models. Why the permission is
present but unused during chat: [ARCHITECTURE.md](ARCHITECTURE.md).

## How it answers

<img src="docs/readme/how.png" alt="Question is classified, then answered at instant, fast or deep depth from a local index, with numbered sources" />

Routing picks **how deep** to answer, never which model writes a normal answer:
an **instant** source sentence (no LLM, under 1 s), a **fast** answer from your
model over compressed sources, or a **deep** answer from a large MoE or several
passes ("Go deeper"), checked by a second model. Retrieval is local and hybrid: SQLite FTS5
keyword search plus on-device embeddings. Inference is `llama.cpp` through JNI.
Every answer records the model, load time, time to first token, tokens/sec,
memory and what it retrieved.

## What it can cite

<img src="docs/readme/knowledge.png" alt="Built-in Wikipedia, topic packs, OpenStreetMap places packs and your own files feed one local hybrid index" />

- **Built in:** Wikipedia articles, plus the Standard (+1,000 topics) and Full (+4,000) libraries. The Encyclopedia setup adds 50,000 Wikipedia Vital Articles.
- **Knowledge packs:** pre-indexed files such as Wikipedia Vital Articles, Emergency & preparedness, Ethereum & cryptography.
- **Places packs (in the iPhone app on [`ios`](https://github.com/rferrari/boar-app/tree/ios) for now):** OpenStreetMap restaurants and cafés with diet tags and hours, plus Wikivoyage listings. Download a city in setup or in Knowledge (Berlin is 3.3 MB for 15,278 places).
- **Your files:** `.txt`, `.md`, `.csv`, `.json`, `.pdf` (selectable text, no OCR), chunked and embedded on the phone. Toggle, delete, or export a collection as a JSON pack to share over Bluetooth or Nearby Share.

## Measured on a real phone

<img src="docs/readme/bench.png" alt="LFM2.5-8B-A1B 14.8 tok/s, Qwen2.5-1.5B 11.4, Phi-3.5-mini 4.0, Qwen2.5-7B 2.7" />

| Model | Architecture | Median tok/s | Peak memory |
|---|---|---|---|
| **LFM2.5-8B-A1B** | MoE, 8B total, ~1.5B active | **14.8** | 5.2 GB |
| Qwen2.5-1.5B (compact) | dense, 1.5B | 11.4 | 3.1 GB |
| Phi-3.5-mini | dense, 3.8B | 4.0 | 4.8 GB |
| Qwen2.5-7B | dense, 7B | 2.7 | 5.1 GB |

Xiaomi 2311DRK48G, Dimensity 8300, 11.6 GB RAM, 17-question evaluation set, on a hot phone (24 Sep 2026).

Later runs, with the phone kept cool and the model kept loaded: Qwen2.5-1.5B at **16.8 tok/s** on the same X6 Pro (1 Oct) and **19.6 tok/s** on a POCO F3 (Snapdragon 870, 8 GB; 29 Sep). On the X6 Pro the first word comes after 1.6 s when no sources are needed and 6.8 s with four full passages, which is why fast answers now send only the sentences that answer.

- The MoE model is as fast as the 1.5B dense model while carrying 8B parameters, and it was the only one to get the multi-step RAM-budget question right.
- Its weak spot: it reasons before answering, and with a 512-token budget 4 of 17 answers ran out before the final answer.
- Not every MoE loads yet: Instella-MoE-16B-A3B fails on this llama.cpp build, and the evaluation records that instead of skipping it.

## Bounty requirements

<img src="docs/readme/limits.png" alt="Peak RAM at most 12 GB, disk at most 50 GB, offline after setup, no Google Play Services, real device" />

Built for the [poidh bounty #31](https://poidh.xyz/mainnet/bounty/31), "Best Offline
AI Research App". Submission wallet:
`0x32d1C8A4d133241a710d780f1198992A015Ea5Ed`.

## Get it

<table>
<tr>
<td width="25%"><img src="docs/readme/setup.jpg" alt="Setup: language and this phone's memory"></td>
<td width="25%"><img src="docs/readme/model.jpg" alt="Choose the answer model: Qwen3-4B recommended, or Qwen2.5-1.5B"></td>
<td width="25%"><img src="docs/readme/setup-knowledge.jpg" alt="Choose your knowledge: Essential or Encyclopedia, plus places for your city"></td>
<td width="25%"><img src="docs/readme/ready.jpg" alt="Ready: everything runs offline from now on"></td>
</tr>
<tr>
<td><b>Start.</b> English or Portuguese, and what this phone has.</td>
<td><b>Pick a model.</b> Qwen3-4B, or the lighter Qwen2.5-1.5B.</td>
<td><b>Pick knowledge.</b> Essential or Encyclopedia, plus your city's places.</td>
<td><b>Ready.</b> Download and index once, then offline.</td>
</tr>
</table>

**Android APK:** download `boar-v1.0.0-arm64.apk` from the
[latest release](https://github.com/rferrari/boar-app/releases/latest) (122 MB, any
64-bit ARM phone), check it and install it:

```bash
sha256sum -c boar-v1.0.0-arm64.apk.sha256   # prints "boar-v1.0.0-arm64.apk: OK"
adb install boar-v1.0.0-arm64.apk           # or open the file on the phone
```

The v1.0.0 APK (Sep 26) downloads Qwen2.5-1.5B (about 1 GB) on first launch.
Builds from `main` let you pick the model in setup: Qwen3-4B (about 2.5 GB) is recommended on 12 GB phones and Qwen2.5-1.5B (about 1 GB) on smaller ones. They also have the Campfire and Moonlight look.

**Build from source:** see [android-native/docs/BUILDING-RELEASE.md](android-native/docs/BUILDING-RELEASE.md) (and `AGENTS.md` for the commands).

**iPhone:** the iOS app is built from the same engine on the [`ios`](https://github.com/rferrari/boar-app/tree/ios) branch, together with its build guide and the steps to install it on your own iPhone with a free Apple ID.

## Docs

| | |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Design, first-run setup, network permission |
| [android-native/docs/BUILDING-RELEASE.md](android-native/docs/BUILDING-RELEASE.md) | Building a release APK with its data bundle, signing, adb-push install |
| [PRIVACY.md](PRIVACY.md) · [TERMS.md](TERMS.md) | Privacy policy and terms (preview) |

License: [LICENSE](LICENSE).
