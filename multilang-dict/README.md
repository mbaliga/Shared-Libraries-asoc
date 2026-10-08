# multilang-dict

Bundled, offline dictionary data for Clackpad's multilingual lookup and
stroke-order display: German, French, Italian, Spanish, Japanese, Korean,
Chinese (Simplified) and Arabic, plus KanjiVG stroke-order geometry.

Like `word-graph/`, this package ships **no Gradle plugin and no code** — it is
data only. Clackpad merges `assets/` straight into the APK via an asset
source-set (`clackpad/app/build.gradle.kts`), deliberately not as an Android
library module, because an AGP version lockstep between the two repos is
unsatisfiable. See `word-graph/README.md` for the full reasoning.

## Do not hand-edit

Everything under `assets/mldict/` is generated. Regenerate it from the Clackpad
repo with:

    ./scripts/build_multilang_dict.sh

That script fetches each upstream source, converts it, and rewrites
`assets/mldict/manifest.json` with the source URL, retrieval date, byte count
and SHA256 of every upstream artifact. Review the diff before committing.

## Layout

    assets/mldict/
      <lang>/index.json   shard boundary list (first headword of each shard)
      <lang>/sNN.txt      ~420 KB shard, tab-separated `headword <TAB> payload`
      stroke/…            same shape, payload is ordered SVG path data
      manifest.json       upstream provenance

`<lang>` is one of `de fr it es ja ko zh ar`. English is **not** here: it lives
in the app's own `assets/defs/<a-z>.txt` (WordNet).

## Sharding

The English corpus shards on the headword's first letter. That does not
generalise — there is no "first letter" in 日本語 or العربية, and one shard per
Unicode block is hopelessly uneven (CJK Unified Ideographs alone would be a
single 4 MB shard). Instead each language's headwords are sorted in UTF-16
code-unit order and cut into contiguous runs of roughly equal byte size, with
`index.json` recording the first headword of each. A lookup binary-searches that
list and fetches exactly one shard.

UTF-16 code-unit order because JavaScript's `<`, JavaScript's default
`Array#sort` and Kotlin's `String.compareTo` all already agree on it, so the web
and native lookups cannot disagree without anyone writing a collator.

## Row format

Identical to the English corpus, with one appended field:

    headword \t sense \x1e sense \x1e sense
    sense = pos \x1f gloss \x1f synonyms \x1f antonyms \x1f reading

`reading` is kana for Japanese and tone-marked pinyin for Chinese, empty
otherwise. It is appended rather than inserted so the existing parsers, which
read fields 0–3, work on these files unchanged. A payload of `=lemma` is an
alias hop (kana → kanji, traditional → simplified).

Synonym and antonym fields are present in the format but **empty** in every
language here: thesaurus coverage is too uneven across these languages to source
responsibly alongside the definitions.

## Licences

Every corpus is CC BY-SA. Share-alike attaches to these data files, which remain
available under their source licences; it does not reach the application.

| Data | Source | Licence |
|---|---|---|
| `ja/` | JMdict (EDRDG) | CC BY-SA 4.0 |
| `zh/` | CC-CEDICT (MDBG) | CC BY-SA 4.0 |
| `stroke/` | KanjiVG (Ulrich Apel) | CC BY-SA 3.0 |
| `de/ fr/ it/ es/ ko/ ar/` | Wiktionary via Wiktextract (kaikki.org) | CC BY-SA 4.0 |

EDRDG's licence additionally requires the acknowledgement on **every** on-screen
dictionary display (not only an About page) and a documented procedure for
refreshing the data at least monthly. Clackpad satisfies both; see `NOTICE.md`
in the Clackpad repo.

Deliberately **not** included: makemeahanzi `graphics.txt` (Arphic Public
Licence) and `dictionary.txt` (LGPL-3.0), and FreeDict (GPL-2.0-or-later).
Chinese stroke-order coverage is therefore partial — a hanzi has stroke data only
where it is also a Japanese kanji present in KanjiVG.
