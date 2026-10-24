#!/usr/bin/env bash
# Checks a release APK before it is uploaded to a GitHub Release. Exit 1 unless:
# - the application id is BoarLite's (team.sopa.boar.lite, or team.sopa.aoair.offline for the offline variant),
# - versionCode equals app.json's expo.android.versionCode (an APK with the same or a lower code
#   won't install over the previous release),
# - the APK is signed with v1.0.0's key: a different signer can't update an installed BOAR.
#
#   scripts/check-release-apk.sh dist/boar-downloader-1.1.0-arm64.apk
#   RELEASE_VARIANT=offline scripts/check-release-apk.sh dist/boar-offline-1.1.0-arm64.apk
#   make check-release-apk APK=dist/boar-downloader-1.1.0-arm64.apk   (RELEASE_VARIANT=offline for the other)
#
# Needs the Android SDK build-tools (aapt2, apksigner) and a Java runtime for apksigner.
set -euo pipefail

apk=${1:?usage: $0 <release.apk>}
variant=${RELEASE_VARIANT:-downloader}
[[ -f "$apk" ]] || { echo "not found: $apk" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Signer certificate SHA-256 of v1.0.0 (apksigner verify --print-certs on boar-v1.0.0-arm64.apk,
# CN=BOAR, O=sopa.team); the same key the results server pins as RELEASE_CERT.
RELEASE_SIGNER=7cf514f9ab8253cbd78eb7d112d66fd05c2ad27e6fc987bf8f3276a7eedf1f01

case "$variant" in
  downloader) want_package=team.sopa.boar.lite ;;
  offline) want_package=team.sopa.aoair.offline ;;
  *) echo "RELEASE_VARIANT must be downloader or offline" >&2; exit 2 ;;
esac
want_code=$(node -p "require('$ROOT/app.json').expo.android.versionCode ?? ''")
[[ -n "$want_code" ]] || { echo "app.json has no expo.android.versionCode" >&2; exit 2; }

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
[[ "$code" == "$want_code" ]] || bad "versionCode is '$code', app.json says '$want_code'"

signers=$("$tools/apksigner" verify --print-certs "$apk" 2>&1) || bad "apksigner verify failed: $signers"
signer=$(grep -m1 "certificate SHA-256 digest" <<<"$signers" | awk '{print $NF}' || true)
[[ "$signer" == "$RELEASE_SIGNER" ]] || bad "signer is '${signer:-none}', expected v1.0.0's $RELEASE_SIGNER"

if [[ $fail == 0 ]]; then
  echo "OK: $apk ($package $name, versionCode $code, signed with the release key)"
fi
exit $fail
