# Models, packs and index: provenance

Every file in LabRat's data bundle, where it comes from, its licence and its sha256. The app checks each file against
the size and sha256 in [`catalog.json`](../android-native/app/src/main/assets/boar-data/catalog.json) while it unpacks
the bundle on first launch, and `scripts/fetch-bundle-inputs.sh` checks the same values when it downloads them.

Total: 16 files, 3,015 MB. The two JSON Wikipedia corpora (essentials, 1,000 and 4,000 topics) are not in the bundle:
they ship as normal APK assets under `android-native/app/src/main/assets/corpus/` (from
[BOAR](https://github.com/rferrari/boar-app), CC BY-SA 4.0).

## Models and index

| Catalog id | File in the app | Size (B) | sha256 | Source | Licence |
|---|---|---|---|---|---|
| `qwen3-1.7b-q4km` | `models/qwen3-1.7b-q4km.gguf` | 1,107,409,472 | `b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897` | [unsloth/Qwen3-1.7B-GGUF @ d7f544ee](https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/d7f544eead698dbd1f15126ef60b45a1e1933222/Qwen3-1.7B-Q4_K_M.gguf), Q4_K_M quant of Qwen/Qwen3-1.7B | Apache-2.0 |
| `bge-small-en-v1.5-q8` | `models/embedding.gguf` | 36,806,944 | `ec38e8da142596baa913124ae50550de284b6916bf59577ef2f0cb9660c2f514` | [CompendiumLabs/bge-small-en-v1.5-gguf @ d32f8c04](https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/d32f8c040ea3b516330eeb75b72bcc2d3a780ab7/bge-small-en-v1.5-q8_0.gguf), Q8_0 of BAAI/bge-small-en-v1.5 | MIT |
| `kb-index-research` | `kb/kb-index-research.db` | 21,250,048 | `9fa0ad3345916fea656cfca95eeed8bb8b2d558b6ea274e73b88bf052cf333df` | Prebuilt search index (FTS5 + BGE vectors) over the two Wikipedia essentials corpora; [boar-packs @ bb5931a7](https://huggingface.co/datasets/r4topunk/boar-packs/resolve/bb5931a760ba35863c5b398cbc40a000ac2abb08/kb/kb-index-research/kb-index-research.db) | CC BY-SA 4.0 (Wikipedia) |

## The 13 packs of the K13 kit

Each pack is one SQLite file (pack format 2: articles, chunks, FTS5 index, BGE Small lead vectors). Every row keeps its
source URL and licence in the `article_meta` table, and the app shows them next to each answer. "Snapshot" is the date
of the source data; "built" is the pack's `meta.builtAt`.

| Catalog id | Size (B) | sha256 | Source and snapshot | Built | Licence | Download |
|---|---|---|---|---|---|---|
| `wiki-vital5` | 163,647,488 | `d3b87d562baba3489f6878bf99783f50d504db94c347029771e53f6d1aecc666` | Introductions of the ~50,000 Wikipedia Vital Articles (level 5); BOAR's knowledge-pack-v1 release | 2026-09-24 | CC BY-SA 4.0 | [boar-packs @ 9b27b306](https://huggingface.co/datasets/r4topunk/boar-packs/resolve/9b27b3069bd01c59852ff8d61d9b835933a99903/topics/wiki-vital5/wiki-vital5.sqlite) (also BOAR's GitHub release `knowledge-pack-v1`) |
| `boar-simplewiki-en` | 735,510,528 | `99bcec306de73d2d8ca5d081531c99f8197d099a85105a5608fce49fb0777472` | Whole Simple English Wikipedia, dump `simplewiki-20261001`: 208,622 articles in full text with infobox key facts and redirects | 2026-10-13 | CC BY-SA 4.0 | `labrat-k13/` at boar-packs 54d490da |
| `boar-wikivoyage-en` | 351,092,736 | `ff8595e32f56b8b84e20464afd8bf88532d47d2d82d799e3df8489440004b66e` | English Wikivoyage dump (latest as of the build): 34,002 travel guides with redirects | 2026-09-27 | CC BY-SA 4.0 | [boar-packs @ d4ff5720](https://huggingface.co/datasets/r4topunk/boar-packs/resolve/d4ff5720f5a7146d5c514763b2f333d472e9b226/wiki/en/boar-wikivoyage-en.sqlite) |
| `boar-factbook` | 11,624,448 | `c585435354a413d04790882c4e2263f725b1024c067febad3f2a99783e663447` | CIA World Factbook, CC0 JSON archive [factbook/factbook.json](https://github.com/factbook/factbook.json) at commit 144d6977 (2026-09-11): 261 entries | 2026-10-13 | CC0 | [boar-packs @ 9b27b306](https://huggingface.co/datasets/r4topunk/boar-packs/resolve/9b27b3069bd01c59852ff8d61d9b835933a99903/topics/boar-factbook/boar-factbook.sqlite) |
| `boar-firstaid-en` | 26,329,088 | `0a7dffa614490199477cc469a06aa92561f89713d16d1ab43d1a0d53f813e9c6` | First aid and emergency preparedness, 1,842 documents: Wikipedia, Wikivoyage, Wikibooks, Appropedia, US federal works (Ready.gov, NPS), US Army FM 21-76 (1992) and ATP 4-02.11 (March 2026) | 2026-10-13 | CC BY-SA 4.0; US public domain (17 U.S.C. §105) | `labrat-k13/` at boar-packs 54d490da |
| `boar-medlineplus-en` | 4,530,176 | `e805ccea3aa7d75224a81fa44e60d881161c6a6029255b461f8ff6ee5399ebd0` | MedlinePlus Health Topics XML of 2026-10-10: the 1,016 English topic summaries | 2026-10-13 | Public domain (NLM); courtesy of MedlinePlus from the National Library of Medicine | `labrat-k13/` at boar-packs 54d490da |
| `boar-visa-en` | 11,231,232 | `d1dd0ffb58a8e4f9b50a6a21cb164bdb314a7181eb2803c952db21e21359ad20` | 387 English Wikipedia articles "Visa requirements for … citizens" and "Visa policy of …", each at its revision current on 2026-10-01, tables flattened to text | 2026-10-20 | CC BY-SA 4.0 | `labrat-k13/` at boar-packs 54d490da |
| `boar-crypto` | 36,093,952 | `ae9fbd2c7a46a46a815d46d0283e7b192adb4c342b38a2adc4881e8f9d8831fb` | Crypto and Ethereum reference, 3,141 documents: EIPs/ERCs, BIPs, Ethereum specs, ethereum.org, 1,369 Wikipedia articles | 2026-09-27 | Mixed per document: CC0, public domain, BSD, MIT, FSFAP, CC BY 4.0, CC BY-SA 4.0 | [boar-packs @ d4ff5720](https://huggingface.co/datasets/r4topunk/boar-packs/resolve/d4ff5720f5a7146d5c514763b2f333d472e9b226/topics/boar-crypto.sqlite) |
| `qa-health-se` | 28,958,720 | `f1ba1a4b2253f0f7ff2c643ff3eb2f4f3768036efaf646a776c61978ec390eaf` | Medical Sciences Stack Exchange, archive.org dump 2024-04-02: 5,048 questions with up to 5 answers (score ≥ 1 or accepted) | 2026-10-13 | CC BY-SA 3.0 / 4.0 per post, author attribution per row | `labrat-k13/` at boar-packs 54d490da |
| `qa-outdoors-se` | 42,508,288 | `11bcb96f6bf42ff043665c9b9bd7fe49a5ab206205ba326edde5b9d06918291d` | The Great Outdoors Stack Exchange, dump 2024-04-02: 5,872 questions | 2026-10-13 | CC BY-SA 3.0 / 4.0 per post | `labrat-k13/` at boar-packs 54d490da |
| `qa-travel-se` | 212,033,536 | `ffdebe54dca0a7df3c2d0c6b2db3bcec29322f9b2d25ff94d851b90a7dbc69d3` | Travel Stack Exchange, dump 2024-04-02: 42,307 questions | 2026-10-13 | CC BY-SA 3.0 / 4.0 per post | `labrat-k13/` at boar-packs 54d490da |
| `qa-cooking-se` | 88,973,312 | `81990fa938ecf7d467a31efacdd58cd6f98392261e5e06ba92fcd0174b3dba35` | Seasoned Advice (cooking) Stack Exchange, dump 2024-04-02: 25,698 questions | 2026-10-13 | CC BY-SA 2.5 / 3.0 / 4.0 per post | [boar-packs @ 9b27b306](https://huggingface.co/datasets/r4topunk/boar-packs/resolve/9b27b3069bd01c59852ff8d61d9b835933a99903/qa/qa-cooking-se/qa-cooking-se.sqlite) |
| `qa-money-se` | 137,240,576 | `374112341f2aa92f136b37fd3855d90710936cd158fb46f0f639de7b33ed5a88` | Personal Finance & Money Stack Exchange, dump 2024-04-02: 35,102 questions | 2026-10-13 | CC BY-SA 2.5 / 3.0 / 4.0 per post | [boar-packs @ 9b27b306](https://huggingface.co/datasets/r4topunk/boar-packs/resolve/9b27b3069bd01c59852ff8d61d9b835933a99903/qa/qa-money-se/qa-money-se.sqlite) |

The Stack Exchange packs come from the `stackexchange` item on archive.org (dump 2024-04-02); unanswered questions are
skipped.

### Why seven packs live in `labrat-k13/`

Six of them (`boar-simplewiki-en`, `boar-firstaid-en`, `boar-medlineplus-en`, `qa-health-se`, `qa-outdoors-se`,
`qa-travel-se`) are the packs published earlier on [r4topunk/boar-packs](https://huggingface.co/datasets/r4topunk/boar-packs)
with one extra table, `chunk_vecs` (BGE Small int8 vectors for every chunk, used by LabRat's search), and one extra
`meta` key (`densechunk = bge-small-int8-v1`). That changes the file, so the sha256 differs from the earlier release;
every other table is the same (checked table by table for `boar-medlineplus-en`). `boar-visa-en` was not
published before. These seven exact files go to `labrat-k13/` on the same dataset; the per-pack cards of the earlier
release (`topics/<id>/README.md`, `qa/<id>/README.md`) describe the sources and the build in detail.

`scripts/fetch-bundle-inputs.sh --check-urls` reports whether each URL serves the exact file.

## Not included

The scripts that built the packs are not part of this repository. The packs are documented here and on their Hugging
Face cards (source, dump, licence, counts), and the app uses them as they are, verified by sha256.
