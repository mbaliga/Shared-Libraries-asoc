# `stickers` — sticker artwork, as a shared **asset** package

Die-cut sticker artwork for the constellation's keyboards/chat surfaces, organised into
packs. Like `word-graph/` and `multilang-dict/`, this ships **no Gradle plugin and no
code** — it is images and a manifest, consumed as an asset source-set directory.

## The packs

- **Bao** — the girl character. 105 stickers across five categories: `00-originals` (the
  original 24), `01-core-emotions`, `02-conversation`, `04-everyday-life` (20 each) and
  `03-affection-support` (21 — the pack's 20 plus the later finger-heart addition).
- **Fauna** — ten die-cut insect and animal illustrations (leaf-footed bug, treehoppers,
  lanternfly, shield bug, spiny caterpillar, pink frog, bird-of-paradise, deep-sea drifter,
  …).
- **Flora** — reserved. Empty until plant artwork arrives; see `flora/README.md`.

## Layout

```
stickers/
  manifest.json                 packs → categories → stickers, source of truth for the UI
  bao/
    00-originals/NN-name.png    105 x 512px RGBA die-cut PNGs, five category folders
    01-core-emotions/NN-name.png
    02-conversation/NN-name.png
    03-affection-support/NN-name.png
    04-everyday-life/NN-name.png
    sheets/*.png                 master sheets the individual stickers were cut from
    preview-*.png                 contact sheets, not part of the sticker set
  fauna/
    NN-name.png                  10 die-cut illustrations (no contact sheet yet)
  flora/                          reserved, empty
  android-assets/                GENERATED — see below, never hand-edited
    default_stickers/
      <pack>/[<category>/]<file>.png
      manifest.json
  tools/
    diecut.py                     flat illustration on paper -> 512px die-cut sticker
    checker_cut.py                sticker exported on a baked-in checkerboard -> clean 512px PNG
    build_android_assets.py       regenerates android-assets/ + both manifest.json files
```

## `manifest.json` schema

```json
{
  "version": 1,
  "packs": [
    {
      "id": "bao",
      "label": "Bao",
      "categories": [
        {
          "id": "00-originals",
          "label": "Originals",
          "stickers": [
            { "file": "bao/00-originals/01-garland.png", "name": "Garland", "keywords": ["garland"] }
          ]
        }
      ]
    }
  ]
}
```

`file` is the path relative to `stickers/` for the root manifest, and relative to
`android-assets/default_stickers/` for the copy that ships alongside the bundled PNGs.
`name` is a display label; `keywords` back search/filter in a consumer's picker UI.

## `android-assets/` is generated

`stickers/android-assets/default_stickers/` is a pngquant-optimised copy of the pack
folders, laid out exactly as a consumer's `assets/default_stickers/` tree wants it, plus
its own minified `manifest.json`. **Never hand-edit it** — it is rebuilt wholesale by
`tools/build_android_assets.py` every time, and any manual change is silently discarded on
the next run.

## `tools/diecut.py` — cutting a new sticker

Turns a flat illustration on a plain paper background into a 512x512 die-cut sticker: it
estimates the paper colour from the image frame, floods the paper region (and any large
enclosed paper pocket) to transparent, ramps alpha across the anti-aliased fringe and
de-fringes its colour, crops to content, scales to fit, and adds the white die-cut border
with a faint grey rim. Read its module docstring and the `if __name__ == '__main__':`
block for the exact algorithm and knobs (`TOL`, `FRINGE_LO`/`FRINGE_HI`, `MIN_POCKET`,
`BORDER`, `RIM`).

```bash
python3 tools/diecut.py source.png output.png
# a strip of several subjects sharing one paper background, cut apart into separate files:
python3 tools/diecut.py strip.png out1.png,out2.png,out3.png --split
```

## Adding a new flora or fauna sticker

1. Drop the source illustration (on plain paper, one subject, or a strip of several for
   `--split`) somewhere convenient.
2. Run `tools/diecut.py` to produce the 512px die-cut PNG(s), and move the result(s) into
   `flora/` or `fauna/` with a `NN-name.png` name (zero-padded index, kebab-case name).
3. Re-run `tools/build_android_assets.py` from the `stickers/` directory to regenerate
   `manifest.json` and `android-assets/`.
4. Commit the new source PNG, the updated `manifest.json`, and the regenerated
   `android-assets/` tree together.

## Artwork provenance

The artwork in this package was supplied directly by the repository owner. It is not
covered by any third-party licence — there is no NOTICE.md here, unlike `word-graph/`.

## Consuming it

No Gradle module, no `includeBuild`, no AGP constraint — same reasoning as `word-graph/`
and `multilang-dict/`, see [`word-graph/README.md`](../word-graph/README.md) for the full
argument. Point an asset source-set at `android-assets/`:

```kotlin
// app/build.gradle.kts
android {
    sourceSets["main"].assets.srcDir("../shared-libraries/stickers/android-assets")
}
```

That merges `default_stickers/<pack>/[<category>/]<file>.png` and
`default_stickers/manifest.json` into the consumer's assets, reachable through its own
`AssetManager` exactly as if they lived in `src/main/assets`.

## Seeded-file naming convention (Clackpad)

Clackpad seeds these into its own sticker store on first run, one PNG per manifest entry,
named and ordered exactly like this:

> seeded file name = "default_" + asset path relative to default_stickers/ with every "/"
> replaced by "_"
>   bao/01-core-emotions/02-laughing-tears.png  →  default_bao_01-core-emotions_02-laughing-tears.png
>   fauna/03-lanternfly.png                     →  default_fauna_03-lanternfly.png
> manifest.json is NOT seeded (skip any non-.png). Ordering: seeded files get lastModified =
> (seedTime − index·1000 ms) with index following manifest/asset order (bao 00-originals first …
> 04-everyday-life, then fauna, then flora), so stickerList()'s newest-first sort shows the user's
> own stickers first, then Bao in category order, then Fauna.

Any consumer that seeds defaults from this package should follow the same convention, so a
sticker's file name always tells you which pack/category it came from.
