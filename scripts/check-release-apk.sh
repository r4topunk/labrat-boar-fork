#!/usr/bin/env bash
# Checks a BoarLite release APK before it is shared. Exit 1 unless:
# - the application id is BoarLite's (team.sopa.boar.lite),
# - versionCode equals the one android-native/app/build.gradle.kts declares (a stale APK, or one with the same or a lower
#   code, won't install over the previous release),
# - the APK is signed with BoarLite's own release key (D9): a different signer can't update an installed BoarLite,
# - the manifest asks for no INTERNET permission (D1: the app is closed, it has no network).
#
#   scripts/check-release-apk.sh android-native/app/build/outputs/apk/release/app-release.apk
#
# Needs the Android SDK build-tools (aapt2, apksigner) and a Java runtime for apksigner.
set -euo pipefail

apk=${1:?usage: $0 <release.apk>}
[[ -f "$apk" ]] || { echo "not found: $apk" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Signer certificate SHA-256 of BoarLite's upload key (apksigner verify --print-certs on a release build).
RELEASE_SIGNER=f777722b39c5f29f8a0bfc4ef3e8746374015a5fd925c8f75eeece88d9db57e9
want_package=team.sopa.boar.lite
# BoarLite is a native app: its version code lives in the Gradle build, not in the Expo app.json.
want_code=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$ROOT/android-native/app/build.gradle.kts" | head -1)
[[ -n "$want_code" ]] || { echo "android-native/app/build.gradle.kts has no versionCode" >&2; exit 2; }

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
tools=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
[[ -x "$tools/aapt2" && -x "$tools/apksigner" ]] || { echo "aapt2/apksigner not found under $sdk/build-tools" >&2; exit 2; }

fail=0
bad() { echo "FAIL: $*" >&2; fail=1; }

badging=$("$tools/aapt2" dump badging "$apk" | awk 'NR==1')
package=$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<<"$badging")
code=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")
name=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
[[ "$package" == "$want_package" ]] || bad "package is '$package', expected '$want_package'"
[[ "$code" == "$want_code" ]] || bad "versionCode is '$code', build.gradle.kts says '$want_code'"

signers=$("$tools/apksigner" verify --print-certs "$apk" 2>&1) || bad "apksigner verify failed: $signers"
signer=$(grep -m1 "certificate SHA-256 digest" <<<"$signers" | awk '{print $NF}' || true)
[[ "$signer" == "$RELEASE_SIGNER" ]] || bad "signer is '${signer:-none}', expected BoarLite's $RELEASE_SIGNER"

if "$tools/aapt2" dump permissions "$apk" | grep -q "android.permission.INTERNET"; then
  bad "the APK asks for android.permission.INTERNET"
fi

if [[ $fail == 0 ]]; then
  echo "OK: $apk ($package $name, versionCode $code, signed with BoarLite's release key, no INTERNET)"
fi
exit $fail
