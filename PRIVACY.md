# BoarLite Privacy Notice

> **Preview: first draft, not legally reviewed.** This notice describes what BoarLite does today, as
> accurately as we can, and may change.

Version 1.0 (preview) · 24 October 2026

BoarLite is a closed, offline Android research assistant (package `team.sopa.boar.lite`). It is a fork
of [BOAR](https://github.com/rferrari/boar-app) (MIT) and is not the BOAR app, the BOAR website
(boarapp.com) or any BOAR service: the BOAR privacy policy does not apply to it, and this notice does
not cover those.

## In short

- **BoarLite has no network access.** Its manifest removes the `INTERNET` permission (and the
  network-state permission), so the app cannot send anything over a network, and there is no server behind it.
- **Your questions, answers, chats and documents stay on your phone unless you export them yourself (section 2).** The model that answers runs
  on the phone.
- **No accounts, no advertising, no analytics, no crash reporting, no result sharing.** There is nothing
  to sign in to and nothing that reports back to us.
- **Everything ships inside the APK:** the answer model (Qwen3 1.7B), the search model, the 13
  knowledge packs and the search index (about 3 GB in total). They are unpacked on first launch from
  the APK; nothing is downloaded.

## 1. What stays on your phone

BoarLite processes these only on your device:

- your questions and its answers, and your chats (up to the last three exchanges of a chat are used as context
  for follow-ups; there is no long-chat summary);
- documents you import (`.txt`, `.md`, `.csv`, `.json`, `.pdf`) and the collections made from them;
- the knowledge packs and the searches over them;
- BoarLite's per-answer performance record (model load time, speed, memory, which sources were used) and
  any evaluation run you start, saved as files in the app's storage; they never include anything beyond
  the test or the chat that produced them;
- your settings.

BoarLite does not read your location, use the microphone or the camera, or read your contacts.
Its only permissions are vibration (haptic feedback) and a foreground-service notification that shows
"BoarLite is answering…" while an answer is being written (on Android 13 and later the app asks once for
permission to show it; saying no doesn't stop answers).

## 2. Leaving the phone only when you choose

BoarLite never sends data itself. Two actions hand a file to another app through Android's share sheet,
and only when you tap them: exporting a collection of your documents, and exporting an evaluation run.
From that point the app you choose receives the file and its own privacy policy applies; BoarLite
cannot see or control it.

## 3. Backups

BoarLite's data is excluded from Android cloud backup and device transfer (`allowBackup` is off and
the data extraction rules exclude every domain). It lives only on this phone.

## 4. Deleting your data

- **Settings → Start over → Erase everything** deletes every model and pack, your documents and all
  chats from the phone. It cannot be undone, and the next launch unpacks the bundle again.
- **Clear chat history** removes your chats only.
- **Uninstalling BoarLite** deletes everything it stored on the phone.

## 5. Security, and what is up to you

BoarLite does not encrypt your data beyond what Android does for every app. Anyone who can unlock or take over your phone can read what BoarLite stored. Keeping
the phone, its lock screen and anything you export safe is up to you. Install BoarLite only from the
team that distributed it: a modified copy is not covered by this notice.

## 6. Children

BoarLite is not directed to children under 13 (16 where local law sets that age). It collects no
personal data from anyone.

## 7. Changes and questions

When this notice changes we update the version and date above. Because BoarLite has no network, it
cannot tell you itself: check the notice that comes with the build you installed. For questions, ask
the BoarLite team that gave you the APK.
