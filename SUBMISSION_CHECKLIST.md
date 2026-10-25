# Submission checklist

Tracks the bounty's actual requirements against repo state. Code-reachable work
is done (see `ARCHITECTURE.md` for the detailed status list); everything below
needs your device/accounts and can't be done from within an agent session.

- [ ] `cd android-native && ./gradlew :app:assembleRelease -Pboar.bundleDir=<out>` (after
      `bash scripts/stage-bundle.sh <out> --model … --embedding …`), then `adb install` the APK
      on a connected device — the real unverified step, needs an actual Gradle/NDK compile.
      The data bundle (model, embedding model, packs) is built into the APK and copied on first launch.
- [ ] Confirm the first-run wizard downloads and loads all three default models, and
      the app answers a query end-to-end
- [ ] Try example queries and capture the responses
- [ ] Spot-check RAM via the in-app monitor (Settings > Stats & System) stays under 12GB,
      and storage stays under 50GB
- [ ] Turn off networking entirely (airplane mode) and re-verify the app still works —
      the real test of the "zero connectivity" requirement
- [ ] Post a public demo (X or Farcaster): offline proof, example queries incl. ones
      a 1B model would fail on, repo link, brief approach explanation
- [ ] Submit a screenshot + links to poidh
- [ ] Make sure the GitHub repo's current state matches what's in the demo/claim
