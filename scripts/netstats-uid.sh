#!/usr/bin/env bash
# Prints the network traffic Android's kernel has counted for one app.
# Reads `dumpsys netstats detail`: the per-UID BPF counters since boot (mAppUidStatsMap) and the recorded
# per-UID history (UID stats). Exit 0 when both are empty for the app, 1 when it has traffic, 2 when it
# can't tell (a section missing from this Android's dump, or more than one uid for the package).
#
#   scripts/netstats-uid.sh team.sopa.boar.lite     # expect: 0 bytes, exit 0
#
# ADB=/path/to/adb and ANDROID_SERIAL=<serial> are honoured. Needs USB debugging, not root.
set -euo pipefail

pkg=${1:?usage: $0 <package>}
adb=${ADB:-adb}
uid=$("$adb" shell pm list packages -U "$pkg" | tr -d '\r' | awk -v p="package:$pkg" '$1 == p { sub("uid:", "", $2); print $2 }')
[[ -n "$uid" ]] || { echo "$pkg is not installed" >&2; exit 2; }
# A second user or a dual app prints "uid:A,B": no single uid to check, so no verdict.
[[ "$uid" =~ ^[0-9]+$ ]] || { echo "$pkg has more than one uid ($uid): check each user separately" >&2; exit 2; }

"$adb" shell dumpsys netstats --poll >/dev/null 2>&1 || true   # flush pending counters into the history
dump=$("$adb" shell dumpsys netstats detail | tr -d '\r')
# No row for the uid means no traffic; no section means nothing was checked, never a pass.
for section in "^  mAppUidStatsMap:" "^UID stats:"; do
  grep -q "$section" <<<"$dump" || { echo "this Android's netstats dump has no '${section#^}' section: can't tell" >&2; exit 2; }
done

# mAppUidStatsMap rows: uid rxBytes rxPackets txBytes txPackets (every interface, loopback included).
live=$(awk -v u="$uid" '/^  mAppUidStatsMap:/ { f = 1; next } f && /^  m/ { f = 0 } f && $1 == u { print $2, $4 }' <<<"$dump")
rx=${live% *}; tx=${live#* }
[[ -n "$live" ]] || { rx=0; tx=0; }
# Recorded history entries for the uid (identity lines look like "uid=10148 set=DEFAULT tag=0x0").
hist=$(awk -v u="uid=$uid" '/^UID stats:/ { f = 1; next } /^UID tag stats:/ { f = 0 } f && index($0, u " ") { n++ } END { print n + 0 }' <<<"$dump")

echo "$pkg uid=$uid  since boot: rx=${rx} B tx=${tx} B  recorded history entries: $hist"
[[ "$rx" == 0 && "$tx" == 0 && "$hist" == 0 ]]
