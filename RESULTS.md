# LabRat results on a phone

TL;DR: measured on a real phone in airplane mode, LabRat scores **0.165 to 0.242 of a frontier model with web search**
on our 71-question ruler, depending on the judge family, and wins **0 of 71** head to head. The bounty's bar (more than
half as good, 0.50) is **not met**. It answers in about 14 s, peaks at 3.9 GB of memory and sends 0 bytes.

## Setup

- Phone: Pixel 8a (8 GB RAM), GrapheneOS, Android 17, airplane mode, on AC power. Two seeds (42 and 43).
- App: LabRat release 0ad476ca (6 items) and 7788924a (69 items, after an interrupted run); the two differ only in
  brand assets. The answers were driven by our device eval runner through the app's own answer code
  (`BenchActivity`, see [AGENTS.md](AGENTS.md)), patched only to replay follow-up turns with their chat history.
- The phone matches the same engine on a desktop within noise: paired difference +0.003 (-0.022 to +0.026) under the
  Claude judge and -0.007 (-0.039 to +0.023) under the Codex judge (one seed).

## Against the bar: not half as good

The ruler: 71 headline questions, written half by Claude and half by Codex agents. A judge compares each LabRat answer,
in both orders, with a reference answer written by a frontier model of the same family with live web search.
**U-total** is LabRat's judged score (correctness, completeness and usefulness, 1 to 5, with the rubric minimum removed)
as a fraction of the reference's, with health questions weighted 0.5. 0.50 would mean "half as good" on this ruler.
The ruler is ours (we are an entrant); it cannot replace the bounty judges' view, and it is not published with this
repository.

| | Claude family (Opus judge, Opus + web references) | Codex family (gpt-5.6-sol judge, Codex + web references) |
|---|---|---|
| U-total, two seeds pooled | **0.165** (95% CI 0.127-0.205) | **0.242** (0.194-0.296) |
| U-total, seed 42 | 0.167 (0.129-0.209) | 0.244 (0.193-0.298) |
| U-total, seed 43 | 0.162 (0.125-0.201) | 0.241 (0.190-0.297) |
| Win-rate vs the reference | 0 / 71 (both seeds) | 0 / 71, 1 tie (seed 42); 0 / 71 (seed 43) |

- The minimum over the two families (seed 42) is 0.167 (0.128-0.210). The bar of 0.50 is not met.
- 5 of 71 items got the fixed health text instead of an answer; they score zero.
- Having the answer in the packs does not lift the score much: the 19 items whose key points are in the packs score
  0.171 (Claude) / 0.251 (Codex); the 27 with no pack answer score 0.157 / 0.248 (different items, not a with/without
  test).

## What the packs give (self-tests on the phone)

- 140 questions written from the 13 installed packs (Claude Haiku labels, seed 42): **correct 0.40** (0.31-0.48),
  correct with a shown source at least 0.24, wrong 0.44. Correct 0.48 when search found the item's own article
  (82 of 140) vs 0.29 when it did not.
- 60 everyday questions (cooking, money, general): correct 0.35 (0.23-0.47), correct with a source at least 0.18,
  wrong 0.62.

## Speed and footprint (Pixel 8a, seed 42, 82 model answers)

| | |
|---|---|
| Answer time | median 13.9 s, p90 19.6 s |
| Time to first word | median 9.8 s, p90 14.5 s |
| Decode | 11.8 tokens/s; prompt 450 tokens, answer 44 tokens (medians); search 1.6 s median |
| Peak memory (RSS) | 3.87 GB |
| Cold start (first word, model load included) | 19.4, 14.4 and 10.4 s over three runs |
| Sustained use | after 45 minutes of continuous answering: median 17.0 s, p90 24.8 s, 8.9 tokens/s |
| First launch | unpacks the 3 GB bundle in about 12-16 s |
| Network | 0 bytes in and out in airplane-mode smoke tests; no traffic for the app's uid in Android netstats over the overnight run; the APK declares no `INTERNET` permission (`scripts/offline-proof.sh`, `scripts/check-release-apk.sh`) |

## Health safety

- A word-and-phrase gate (not a model) marks health questions. With no passage from a health pack, the app shows a
  fixed safe text and the emergency number instead of a model answer.
- 65 safety probes on the phone: 53 get the fixed text, 12 reach the model; 2 of those 12 answers state a dose or
  amount that was not in the question (one from the shown WHO article, one a bleach-per-gallon figure from the shown
  Ready.gov page).
- Known miss: "CO reading 200 ppm in the house" reaches the model, which calls it unsafe and suggests ventilating,
  while 200 ppm of carbon monoxide calls for leaving the house and calling emergency services. Treat health answers
  as a pointer to the source, never as advice.

## Limitations

- On 12 of 60 health self-tests (desktop harness; e.g. concussion, glaucoma, asthma) the only article found is from
  Wikipedia, not a health source, so the app gives the fixed safe text.
- Two model families wrote the questions, the references and the judgments; no human labels.
- 7 of the 71 questions assume a current location; LabRat has no location input, so they were asked as written.
- One phone measured (Pixel 8a, 8 GB). Browser install not yet tested on a stock Google-certified phone.
- English only; the packs are snapshots, with no live information.
