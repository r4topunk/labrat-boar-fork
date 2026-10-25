# Submission checklist

Tracks the bounty's requirements against what LabRat (the no-network offline build) must show. Steps that
need a device or an account can't be done from an agent session.

- [ ] From a fresh clone: `bash scripts/fetch-bundle-inputs.sh <inputs>`, then
      `cd android-native && bash scripts/stage-bundle.sh <out> --inputs <inputs>` and
      `./gradlew :app:assembleRelease -Pboar.bundleDir=<out>` (see the README, "Build from source"), then
      `adb install` the APK on a connected device. The data bundle (Qwen3 1.7B, the search model, the 13
      packs of the K13 kit and the prebuilt index, about 3.0 GB) is built into the APK and unpacked on
      first launch.
- [ ] Check the APK has no network permission (`aapt2 dump permissions`: no `INTERNET`; see
      `scripts/check-release-apk.sh` and `scripts/audit-apk.py` at the repo root)
- [ ] Confirm first launch unpacks the bundle with no connection and the app answers a query end to end
- [ ] Try example queries and capture the responses (sources and the "May contain errors; check the
      source." line shown; a health question takes the lexical gate)
- [ ] Spot-check RAM (the menu's live memory and storage line) stays under 12 GB and storage under 50 GB
- [ ] Turn off networking entirely (airplane mode) and re-verify the app works, as the proof of the
      "zero connectivity" requirement (`scripts/offline-proof.sh`)
- [ ] The 7 packs listed in `docs/PACKS-UPLOAD-PLAN.md` are on Hugging Face, `LABRAT_K13_REV` is pinned, and
      `bash scripts/fetch-bundle-inputs.sh --check-urls` prints 16 × `ok`
- [ ] The APK is on Hugging Face and the README's `https://huggingface.co/r4topunk/labrat/resolve/main/LabRat-1.0.0.apk` and `d2e00ba4a9adff6f903eab7d34759fd4f4db9cd721303dc7c38ae280b465dae8` are filled in
- [ ] Post a public demo (X or Farcaster): offline proof, example queries, a brief approach explanation,
      a link to this repository (github.com/r4topunk/labrat-boar-fork) and the APK on Hugging Face, and credit
      BOAR (github.com/rferrari/boar-app, MIT) as the base.
- [ ] Submit a screenshot + links to poidh
- [ ] Make sure what is claimed matches the APK that was demoed
