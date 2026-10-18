# `flora` — reserved

No artwork yet. This pack exists so the `manifest.json` schema, `tools/build_android_assets.py`
and consumers' category-chip logic (a hidden "Flora" chip when this pack is empty) already
have a place to plug plant stickers into once they arrive — no code or layout changes needed
on that day.

To fill it:

1. Cut plant illustrations to 512px die-cut PNGs with `../tools/diecut.py` (see
   `../README.md` for the exact steps), and drop them here as `NN-name.png`.
2. Re-run `../tools/build_android_assets.py` from the `stickers/` directory to regenerate
   `../manifest.json` and `../android-assets/`.
3. Commit the new PNGs alongside the regenerated manifest and `android-assets/` tree.
