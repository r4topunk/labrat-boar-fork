# Upload plan: the 7 bundled packs that are not on Hugging Face yet

TL;DR: upload 7 files (1,061 MB) to `hf.co/datasets/r4topunk/boar-packs` under `labrat-k13/`, each with a short card
and a licence file, in **one commit**. Then set `LABRAT_K13_REV` in `scripts/fetch-bundle-inputs.sh` to that commit and
check with `bash scripts/fetch-bundle-inputs.sh --check-urls` (expect 16 × `ok`). Until then the fetch script uses
`main`, and those 7 URLs fail the check.

Status: uploaded 2026-10-25, boar-packs commit 54d490daddbfa9e1e07470711afaefa660b9fdff; --check-urls 16 x ok.

## Files

All seven were hashed on 2026-10-25 and match `catalog.json` (size and sha256). The same files are hard-linked in the
staged bundle `builds/boarlite-bundle/bundle/corpus/` of the maintainers' workspace (same inode).

| Catalog id | Local source (maintainers' workspace) | Size (B) | sha256 | Target path on boar-packs |
|---|---|---|---|---|
| `boar-firstaid-en` | `shared-data/packs/v3proto/boar-firstaid-en.sqlite` | 26,329,088 | `0a7dffa614490199477cc469a06aa92561f89713d16d1ab43d1a0d53f813e9c6` | `labrat-k13/boar-firstaid-en.sqlite` |
| `boar-medlineplus-en` | `shared-data/packs/v3proto/boar-medlineplus-en.sqlite` | 4,530,176 | `e805ccea3aa7d75224a81fa44e60d881161c6a6029255b461f8ff6ee5399ebd0` | `labrat-k13/boar-medlineplus-en.sqlite` |
| `qa-health-se` | `shared-data/packs/v3proto/qa-health-se.sqlite` | 28,958,720 | `f1ba1a4b2253f0f7ff2c643ff3eb2f4f3768036efaf646a776c61978ec390eaf` | `labrat-k13/qa-health-se.sqlite` |
| `qa-outdoors-se` | `shared-data/packs/v3proto/qa-outdoors-se.sqlite` | 42,508,288 | `11bcb96f6bf42ff043665c9b9bd7fe49a5ab206205ba326edde5b9d06918291d` | `labrat-k13/qa-outdoors-se.sqlite` |
| `qa-travel-se` | `shared-data/packs/v3proto/qa-travel-se.sqlite` | 212,033,536 | `ffdebe54dca0a7df3c2d0c6b2db3bcec29322f9b2d25ff94d851b90a7dbc69d3` | `labrat-k13/qa-travel-se.sqlite` |
| `boar-simplewiki-en` | `shared-data/packs/v3proto/boar-simplewiki-en.sqlite` | 735,510,528 | `99bcec306de73d2d8ca5d081531c99f8197d099a85105a5608fce49fb0777472` | `labrat-k13/boar-simplewiki-en.sqlite` |
| `boar-visa-en` | `shared-data/packs/wave4/boar-visa-en.sqlite` | 11,231,232 | `d1dd0ffb58a8e4f9b50a6a21cb164bdb314a7181eb2803c952db21e21359ad20` | `labrat-k13/boar-visa-en.sqlite` |

Paths are relative to the maintainers' BOAR workspace root (two folders above this repo's checkout). Total 1,061,101,568 B (the largest file is 736 MB).

What they are:

- The six `v3proto` files are the packs of the earlier boar-packs release with one added table, `chunk_vecs` (BGE
  Small int8 vectors for every chunk), and one added `meta` key, `densechunk = bge-small-int8-v1`. Every other table is
  identical (checked table by table for medlineplus). Their bases on boar-packs `main` (9b27b306):
  `topics/boar-firstaid-en/` (8cfab0b4…), `topics/boar-medlineplus-en/` (c9ac4dcf…), `qa/qa-health-se/` (687e4994…),
  `qa/qa-outdoors-se/` (ff733e4f…), `qa/qa-travel-se/` (a85521ce…) and `topics/boar-simplewiki-en/` r1 (c751768b…, not
  r2: the bundled file's `builtAt` is 2026-10-13, before r2).
- `boar-visa-en` is new: built 2026-10-20 from Wikipedia revisions current on 2026-10-01. Its card and licence are
  ready in `shared-data/packs/wave4/boar-visa-en.card.md` and `boar-visa-en.LICENSE.txt`.

## What to upload, per file

Layout (one folder, flat, like `topics/boar-crypto.sqlite`):

```
labrat-k13/
  README.md                       one card for the folder (text below)
  <id>.sqlite                     the 7 files above
  <id>.LICENSE.md                 one licence file per pack (sources below)
```

Licence files: copy the existing ones; the licences don't change with the added vector table.

| Pack | Licence file source | Licence summary | Share-alike |
|---|---|---|---|
| `boar-firstaid-en` | boar-packs `topics/boar-firstaid-en/LICENSE.md` | CC BY-SA 4.0 (1,548 docs: Wikipedia, Wikivoyage, Wikibooks, Appropedia); public domain (294 docs: FM 21-76, US federal works, ATP 4-02.11) | yes, for the CC BY-SA rows |
| `boar-medlineplus-en` | boar-packs `topics/boar-medlineplus-en/LICENSE.md` | Public domain (NLM); credit line "Courtesy of MedlinePlus from the National Library of Medicine" | no |
| `qa-health-se` | boar-packs `qa/qa-health-se/LICENSE.md` | CC BY-SA 3.0 / 4.0 per post, author attribution per row | yes |
| `qa-outdoors-se` | boar-packs `qa/qa-outdoors-se/LICENSE.md` | CC BY-SA 3.0 / 4.0 per post | yes |
| `qa-travel-se` | boar-packs `qa/qa-travel-se/LICENSE.md` | CC BY-SA 3.0 / 4.0 per post | yes |
| `boar-simplewiki-en` | boar-packs `topics/boar-simplewiki-en/LICENSE.md` | CC BY-SA 4.0 (Simple English Wikipedia) | yes |
| `boar-visa-en` | local `shared-data/packs/wave4/boar-visa-en.LICENSE.txt` (47 KB; lists all 387 revision URLs) | CC BY-SA 4.0 (English Wikipedia) | yes |

Checks before upload (D-K3 / audit B1): none of the seven holds NC, NHS or A.D.A.M. content. MedlinePlus is the NLM
public-domain summaries only; first aid is the CC BY-SA and US-government set above. Re-read each licence file once at
upload.

Optional: also upload the per-pack `selftest.jsonl` from the base folders (same questions, same text). Not needed for
the build.

## Card text for `labrat-k13/README.md`

```markdown
# labrat-k13: the exact pack files bundled in LabRat 1.0.0

LabRat (github.com/r4topunk/labrat-boar-fork) is an offline Android research assistant that ships these packs inside
its APK. This folder holds the seven K13-kit packs whose bundled bytes are not elsewhere in this dataset, so anyone can
rebuild the APK from source (`scripts/fetch-bundle-inputs.sh` in the app repo downloads them and checks each sha256).

| File | Size (B) | SHA-256 | Base pack in this dataset |
|---|---|---|---|
| boar-firstaid-en.sqlite | 26,329,088 | 0a7dffa614490199477cc469a06aa92561f89713d16d1ab43d1a0d53f813e9c6 | topics/boar-firstaid-en/ |
| boar-medlineplus-en.sqlite | 4,530,176 | e805ccea3aa7d75224a81fa44e60d881161c6a6029255b461f8ff6ee5399ebd0 | topics/boar-medlineplus-en/ |
| qa-health-se.sqlite | 28,958,720 | f1ba1a4b2253f0f7ff2c643ff3eb2f4f3768036efaf646a776c61978ec390eaf | qa/qa-health-se/ |
| qa-outdoors-se.sqlite | 42,508,288 | 11bcb96f6bf42ff043665c9b9bd7fe49a5ab206205ba326edde5b9d06918291d | qa/qa-outdoors-se/ |
| qa-travel-se.sqlite | 212,033,536 | ffdebe54dca0a7df3c2d0c6b2db3bcec29322f9b2d25ff94d851b90a7dbc69d3 | qa/qa-travel-se/ |
| boar-simplewiki-en.sqlite | 735,510,528 | 99bcec306de73d2d8ca5d081531c99f8197d099a85105a5608fce49fb0777472 | topics/boar-simplewiki-en/ (r1) |
| boar-visa-en.sqlite | 11,231,232 | d1dd0ffb58a8e4f9b50a6a21cb164bdb314a7181eb2803c952db21e21359ad20 | new (see below) |

The first six are the base packs with one added table, `chunk_vecs` (int8 bge-small-en-v1.5 vectors for every chunk),
and one added `meta` key (`densechunk`). Text, sources and licences are those of the base pack; its card describes them.

boar-visa-en: 387 English Wikipedia articles, "Visa requirements for <nationality> citizens" (203) and "Visa policy of
<country>" (184), each at the revision current on 2026-10-01, tables flattened to one text line per row. CC BY-SA 4.0;
every row carries its revision URL and a credit line.

Licences: each pack keeps its sources' licence (see `<id>.LICENSE.md`). Share-alike packs stay share-alike. Health
packs are general information, AI-indexed and not clinically reviewed; not medical advice.
```

## After the upload

1. Note the commit sha of the upload.
2. In this repo: set `LABRAT_K13_REV` in `scripts/fetch-bundle-inputs.sh` to it, and replace "pending upload" in
   `docs/PACKS.md` with the pinned URLs.
3. `bash scripts/fetch-bundle-inputs.sh --check-urls` prints 16 × `ok`.
4. Log the upload (one row per file) in the R&D queue, as the pack rules require.
