#!/usr/bin/env bash
# Stages BoarLite's data bundle: every file the closed app installs on first launch (the answer model, the search model,
# the 13 knowledge packs of the K13 kit and the prebuilt search index), under <outDir>/bundle/ at its installed path,
# plus bundle/manifest.json. Each sha256 is checked against the app's catalog.json, so the APK never carries a file the
# app would reject. Files are hard links (no copy: the packs and model are about 3 GB), so <outDir> must be on the same
# volume as the sources; across volumes they fall back to symbolic links, and the script says so.
#
# Usage (bash, also from fish: `bash scripts/stage-bundle.sh ...`):
#   scripts/stage-bundle.sh <outDir> --model <Qwen3-1.7B-Q4_K_M.gguf> --embedding <bge-small-en-v1.5-q8_0.gguf> \
#       [--root <BOAR workspace, default: two folders above the repo>] [--kb-index <kb-index-research.db>]
# Then: ./gradlew :app:assembleRelease -Pboar.bundleDir=<outDir>   (or adb push <outDir>/bundle, see docs/BUILDING-RELEASE.md)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
catalog="$here/../app/src/main/assets/boar-data/catalog.json"

die() { echo "stage-bundle: $*" >&2; exit 1; }

[ $# -ge 1 ] || die "usage: stage-bundle.sh <outDir> --model <gguf> --embedding <gguf> [--root <dir>] [--kb-index <db>]"
out="$1"; shift
model="" embedding="" root="" kbindex=""
while [ $# -gt 0 ]; do
  case "$1" in
    --model) model="${2:-}"; shift 2 ;;
    --embedding) embedding="${2:-}"; shift 2 ;;
    --root) root="${2:-}"; shift 2 ;;
    --kb-index) kbindex="${2:-}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$model" ] || die "--model <gguf> is required (Qwen3 1.7B Q4_K_M)"
[ -n "$embedding" ] || die "--embedding <gguf> is required (bge-small-en-v1.5 Q8_0)"
if [ -z "$root" ]; then
  top="$(git -C "$here" rev-parse --show-toplevel 2>/dev/null)" || die "not in a git checkout: pass --root <BOAR workspace>"
  root="$(cd "$top/../.." && pwd)"
fi
[ -d "$root/shared-data" ] || die "$root has no shared-data/: pass --root <BOAR workspace>"
[ -n "$kbindex" ] || kbindex="$root/artifacts/kb-snapshot/kb-index-research.db"

# Catalog id -> source file. The JSON Wikipedia corpora are not here: they already ship as APK assets (corpus/).
sources=(
  "qwen3-1.7b-q4km=$model"
  "bge-small-en-v1.5-q8=$embedding"
  "kb-index-research=$kbindex"
  "wiki-vital5=$root/shared-data/packs/wiki-vital5.sqlite"
  "boar-crypto=$root/shared-data/crypto/boar-crypto.sqlite"
  "boar-wikivoyage-en=$root/shared-data/packs/boar-wikivoyage-en.sqlite"
  "boar-simplewiki-en=$root/shared-data/packs/v3proto/boar-simplewiki-en.sqlite"
  "boar-factbook=$root/kiln-local/shape-packs/shaped/boar-factbook.sqlite"
  "boar-firstaid-en=$root/shared-data/packs/v3proto/boar-firstaid-en.sqlite"
  "boar-medlineplus-en=$root/shared-data/packs/v3proto/boar-medlineplus-en.sqlite"
  "qa-health-se=$root/shared-data/packs/v3proto/qa-health-se.sqlite"
  "qa-travel-se=$root/shared-data/packs/v3proto/qa-travel-se.sqlite"
  "qa-outdoors-se=$root/shared-data/packs/v3proto/qa-outdoors-se.sqlite"
  "qa-cooking-se=$root/shared-data/packs/wave3/qa-cooking-se.sqlite"
  "qa-money-se=$root/shared-data/packs/wave3/qa-money-se.sqlite"
  "boar-visa-en=$root/shared-data/packs/wave4/boar-visa-en.sqlite"
)

# filename, size and sha256 of a catalog id, tab-separated.
catalog_entry() {
  python3 -I -c '
import json, sys
for m in json.load(open(sys.argv[1]))["models"]:
    if m["id"] == sys.argv[2]:
        print(m["filename"], m["sizeBytes"], m["sha256"], sep="\t"); break
else:
    sys.exit("catalog.json has no asset " + sys.argv[2])
' "$catalog" "$1"
}

file_size() { stat -f %z "$1" 2>/dev/null || stat -c %s "$1"; }

bundle="$out/bundle"
rm -rf "$bundle"
mkdir -p "$bundle"
symlinked=0
manifest="["
sep=""
for pair in "${sources[@]}"; do
  id="${pair%%=*}"; src="${pair#*=}"
  [ -f "$src" ] || die "$id: source file not found: $src"
  IFS=$'\t' read -r path size sha < <(catalog_entry "$id")
  actual_size="$(file_size "$src")"
  [ "$actual_size" = "$size" ] || die "$id: $src is $actual_size bytes, catalog.json says $size"
  echo "hashing $id ($((size / 1000000)) MB)..." >&2
  actual_sha="$(shasum -a 256 "$src" | cut -d' ' -f1)"
  [ "$actual_sha" = "$sha" ] || die "$id: sha256 $actual_sha differs from catalog.json's $sha ($src)"
  mkdir -p "$bundle/$(dirname "$path")"
  if ! ln "$src" "$bundle/$path" 2>/dev/null; then
    ln -s "$(cd "$(dirname "$src")" && pwd)/$(basename "$src")" "$bundle/$path"
    symlinked=$((symlinked + 1))
  fi
  manifest+="$sep"$'\n'"  {\"path\": \"$path\", \"sizeBytes\": $size, \"sha256\": \"$sha\"}"
  sep=","
done
printf '%s\n]\n' "$manifest" > "$bundle/manifest.json"

total="$(python3 -I -c 'import json,sys; print(sum(e["sizeBytes"] for e in json.load(open(sys.argv[1]))))' "$bundle/manifest.json")"
echo "staged ${#sources[@]} files ($((total / 1000000)) MB) in $bundle" >&2
if [ "$symlinked" -gt 0 ]; then
  echo "NOTE: $symlinked files are symbolic links (the output folder is on another volume than the sources); keep the sources in place until the APK is built or pushed (adb push copies the targets)." >&2
fi
