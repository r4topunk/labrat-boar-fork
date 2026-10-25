#!/usr/bin/env bash
# Downloads every input of LabRat's data bundle (Qwen3 1.7B, BGE Small, the prebuilt search index and the 13 packs of
# the K13 kit, about 3.0 GB) from public, commit-pinned URLs into <dir>, each file at its catalog path
# (models/..., corpus/..., kb/...). Every file is checked against the size and sha256 in the app's catalog.json
# (android-native/app/src/main/assets/boar-data/catalog.json); a file that does not match is deleted and the script fails.
# Files already present with the right sha256 are not downloaded again; an interrupted download resumes.
#
# Usage (bash, also from fish: `bash scripts/fetch-bundle-inputs.sh ...`):
#   scripts/fetch-bundle-inputs.sh <dir>                 download what is missing, verify everything
#   scripts/fetch-bundle-inputs.sh <dir> --verify        no network: hash the files already in <dir>, list what is missing
#   scripts/fetch-bundle-inputs.sh --check-urls          no download: ask each URL for its size and sha256 (HEAD request)
# Then: cd android-native && bash scripts/stage-bundle.sh <outDir> --inputs <dir>
#
# Needs bash, curl, python3 and shasum (macOS) or sha256sum (Linux). See docs/PACKS.md for each file's source and licence.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
catalog="$here/../android-native/app/src/main/assets/boar-data/catalog.json"

die() { echo "fetch-bundle-inputs: $*" >&2; exit 1; }

HF="https://huggingface.co"
PACKS="$HF/datasets/r4topunk/boar-packs/resolve"
# Revision of r4topunk/boar-packs that holds the labrat-k13/ folder (the 7 packs that LabRat bundles in a newer build
# than the earlier public release). Set to the upload commit once it exists; until then "main".
LABRAT_K13_REV="${LABRAT_K13_REV:-54d490daddbfa9e1e07470711afaefa660b9fdff}"

# Catalog id -> public URL. Every URL except the labrat-k13/ ones is pinned to a commit.
urls=(
  "qwen3-1.7b-q4km=$HF/unsloth/Qwen3-1.7B-GGUF/resolve/d7f544eead698dbd1f15126ef60b45a1e1933222/Qwen3-1.7B-Q4_K_M.gguf"
  "bge-small-en-v1.5-q8=$HF/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/d32f8c040ea3b516330eeb75b72bcc2d3a780ab7/bge-small-en-v1.5-q8_0.gguf"
  "kb-index-research=$PACKS/bb5931a760ba35863c5b398cbc40a000ac2abb08/kb/kb-index-research/kb-index-research.db"
  "wiki-vital5=$PACKS/9b27b3069bd01c59852ff8d61d9b835933a99903/topics/wiki-vital5/wiki-vital5.sqlite"
  "boar-crypto=$PACKS/d4ff5720f5a7146d5c514763b2f333d472e9b226/topics/boar-crypto.sqlite"
  "boar-wikivoyage-en=$PACKS/d4ff5720f5a7146d5c514763b2f333d472e9b226/wiki/en/boar-wikivoyage-en.sqlite"
  "boar-simplewiki-en=$PACKS/$LABRAT_K13_REV/labrat-k13/boar-simplewiki-en.sqlite"
  "boar-factbook=$PACKS/9b27b3069bd01c59852ff8d61d9b835933a99903/topics/boar-factbook/boar-factbook.sqlite"
  "boar-firstaid-en=$PACKS/$LABRAT_K13_REV/labrat-k13/boar-firstaid-en.sqlite"
  "boar-medlineplus-en=$PACKS/$LABRAT_K13_REV/labrat-k13/boar-medlineplus-en.sqlite"
  "qa-health-se=$PACKS/$LABRAT_K13_REV/labrat-k13/qa-health-se.sqlite"
  "qa-travel-se=$PACKS/$LABRAT_K13_REV/labrat-k13/qa-travel-se.sqlite"
  "qa-outdoors-se=$PACKS/$LABRAT_K13_REV/labrat-k13/qa-outdoors-se.sqlite"
  "qa-cooking-se=$PACKS/9b27b3069bd01c59852ff8d61d9b835933a99903/qa/qa-cooking-se/qa-cooking-se.sqlite"
  "qa-money-se=$PACKS/9b27b3069bd01c59852ff8d61d9b835933a99903/qa/qa-money-se/qa-money-se.sqlite"
  "boar-visa-en=$PACKS/$LABRAT_K13_REV/labrat-k13/boar-visa-en.sqlite"
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
sha256_of() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi
}

mode="fetch" dir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --verify) mode="verify"; shift ;;
    --check-urls) mode="check-urls"; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [ -z "$dir" ] || die "only one <dir>"; dir="$1"; shift ;;
  esac
done
[ -f "$catalog" ] || die "catalog.json not found at $catalog"
[ "$mode" = "check-urls" ] || [ -n "$dir" ] || die "usage: fetch-bundle-inputs.sh <dir> [--verify] | --check-urls"

bad=0 missing=0 total=0
for pair in "${urls[@]}"; do
  id="${pair%%=*}"; url="${pair#*=}"
  IFS=$'\t' read -r path size sha < <(catalog_entry "$id")
  total=$((total + size))

  if [ "$mode" = "check-urls" ]; then
    # Hugging Face answers a HEAD on /resolve/ with the file's size and sha256 (x-linked-size, x-linked-etag).
    headers="$(curl -sI "$url" | tr -d '\r' || true)"
    rsize="$(printf '%s\n' "$headers" | awk -F': ' 'tolower($1)=="x-linked-size"{print $2}' | tail -1)"
    rsha="$(printf '%s\n' "$headers" | awk -F': ' 'tolower($1)=="x-linked-etag"{gsub(/"/,"",$2); print $2}' | tail -1)"
    if [ "$rsize" = "$size" ] && [ "$rsha" = "$sha" ]; then
      echo "ok       $id"
    else
      echo "MISMATCH $id: url gives size '${rsize:-none}' sha256 '${rsha:-none}', catalog says $size $sha ($url)"
      bad=$((bad + 1))
    fi
    continue
  fi

  dest="$dir/$path"
  if [ -f "$dest" ] && [ "$(file_size "$dest")" = "$size" ]; then
    echo "hashing  $id ($((size / 1000000)) MB)..." >&2
    if [ "$(sha256_of "$dest")" = "$sha" ]; then echo "ok       $id"; continue; fi
    echo "BAD      $id: $dest has the right size but the wrong sha256" >&2
    if [ "$mode" = "verify" ]; then bad=$((bad + 1)); continue; fi
    rm -f "$dest"
  fi

  if [ "$mode" = "verify" ]; then
    echo "MISSING  $id: $dest ($((size / 1000000)) MB, from $url)"
    missing=$((missing + 1)); continue
  fi

  mkdir -p "$(dirname "$dest")"
  echo "download $id ($((size / 1000000)) MB) from $url" >&2
  curl -fL --retry 3 --retry-delay 5 -C - -o "$dest.part" "$url" \
    || die "$id: download failed ($url); run again to resume"
  [ "$(file_size "$dest.part")" = "$size" ] || { rm -f "$dest.part"; die "$id: downloaded size differs from catalog.json ($size)"; }
  [ "$(sha256_of "$dest.part")" = "$sha" ] || { rm -f "$dest.part"; die "$id: downloaded sha256 differs from catalog.json ($sha)"; }
  mv "$dest.part" "$dest"
  echo "ok       $id"
done

echo "${#urls[@]} files, $((total / 1000000)) MB in all; $missing missing, $bad bad" >&2
[ "$bad" -eq 0 ] && [ "$missing" -eq 0 ] || exit 1
if [ "$mode" != "check-urls" ]; then
  echo "next: cd android-native && bash scripts/stage-bundle.sh <outDir> --inputs $dir" >&2
fi
