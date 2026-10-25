# Building the LabRat release

LabRat is an offline app with no network permission, so everything it uses (Qwen3 1.7B, the BGE search
model, the 13 knowledge packs of the K13 kit and the prebuilt search index, about 3.0 GB) comes in its data bundle.
On first launch the "Unpacking LabRat" screen copies the bundle into the app's files folder. Each file is checked
against its sha256 in `catalog.json` while it is copied. If the copy stops, it resumes at the file it stopped in.

## 0. Download the inputs

From the repository root. The script downloads the two models, the index and the 13 packs (about 3.0 GB) from
commit-pinned Hugging Face URLs and checks each file's size and sha256 against `catalog.json`. It skips files already
downloaded and resumes an interrupted download. Sources and licences: `docs/PACKS.md`.

```bash
bash scripts/fetch-bundle-inputs.sh ../labrat-inputs            # --verify: hash local files only, no network
```

## 1. Stage the bundle

The script hard-links the files, so it copies nothing. The output folder must be on the same volume as the sources. If
it is on another volume, the script falls back to symbolic links and says so. The script fails if a file's size or
sha256 differs from `app/src/main/assets/boar-data/catalog.json`.

```bash
cd android-native
bash scripts/stage-bundle.sh ../../bundle-out --inputs ../../labrat-inputs
# optional: --model <gguf> / --embedding <gguf> override the two models
```

The maintainers' workspace layout still works without `--inputs`: `--model <gguf> --embedding <gguf>
[--root <BOAR workspace>] [--kb-index <db>]`.

This writes `<outDir>/bundle/` (files at their installed paths) and `<outDir>/bundle/manifest.json`. The JSON Wikipedia
corpora are not part of the bundle because they already ship as APK assets (`corpus/`).

## 2. Build the APK with the bundle

```bash
export JAVA_HOME=<your JDK 17> ANDROID_HOME=<your Android SDK>
# e.g. macOS + Homebrew: /opt/homebrew/opt/openjdk@17 and $HOME/Library/Android/sdk; Linux: /usr/lib/jvm/java-17-openjdk-amd64 and $HOME/Android/Sdk
./gradlew :app:assembleRelease -Pboar.bundleDir=$PWD/../../bundle-out
```

`-Pboar.bundleDir` adds `<outDir>` as an assets folder for the release, debug and perf builds. The `gguf`, `sqlite` and
`db` files are stored uncompressed. `BuildConfig.BUNDLED` is true. Without the property the APK carries no bundle, so
development builds stay small.

Gradle merges the assets into `app/build/` before it packs the APK, so the build needs about twice the bundle size free
(about 6 GB), plus the APK itself.

## 3. Without a bundled APK: adb push (testers, development builds)

When the APK carries no bundle, setup reads the same bundle from the app's external files folder. It deletes that copy
once the install is verified, so the data is not kept twice.

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n team.sopa.boar.lite.debug/team.sopa.boar.MainActivity   # creates the app's folders
adb push ../../bundle-out/bundle /sdcard/Android/data/team.sopa.boar.lite.debug/files/
```

Then tap **Check again** on the "LabRat needs its data" screen. For a release build, use the package
`team.sopa.boar.lite`. The screen shows the exact command for the installed package.

## What the app logs

`adb logcat -s BoarSetup` shows the source (`apk` or the pushed folder), the files copied and skipped, the index merge,
and `bundle installed in X s`.

Settings → Run setup again (or Purge, then a relaunch) runs the installer again. It skips the files already verified,
restores a pack removed from Knowledge, and installs a newer APK's bundle.

## Signing

`:app:assembleRelease` signs with LabRat's own upload key when `~/.gradle/gradle.properties` names it (never put the values in the repo): `BOARLITE_UPLOAD_STORE_FILE`, `BOARLITE_UPLOAD_STORE_PASSWORD`, `BOARLITE_UPLOAD_KEY_ALIAS`, `BOARLITE_UPLOAD_KEY_PASSWORD`. Without them the release is debug-signed. Check the result with `scripts/check-release-apk.sh <apk>` (package, versionCode, signer, no INTERNET permission).
