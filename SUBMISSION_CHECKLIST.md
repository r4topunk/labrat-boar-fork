# Submission checklist

Tracks the bounty's requirements against what BoarLite (the closed offline build) must show. Steps that
need a device or an account can't be done from an agent session.

- [ ] `cd android-native && ./gradlew :app:assembleRelease -Pboar.bundleDir=<out>` (after
      `bash scripts/stage-bundle.sh <out> ...`, see `android-native/docs/BUILDING-RELEASE.md`), then
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
- [ ] Post a public demo (X or Farcaster): offline proof, example queries, a brief approach explanation.
      The BoarLite repository is private: do not link it as source, and credit BOAR
      (github.com/rferrari/boar-app, MIT) as the base.
- [ ] Submit a screenshot + links to poidh
- [ ] Make sure what is claimed matches the APK that was demoed
