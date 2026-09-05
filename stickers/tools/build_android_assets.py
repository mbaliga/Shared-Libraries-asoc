#!/usr/bin/env python3
"""Rebuild stickers/android-assets/ and both manifest.json files from the pack folders.

Run from the stickers/ directory:  python3 tools/build_android_assets.py
Needs python3 + Pillow + pngquant on PATH. The pack folders (bao/, fauna/, flora/) are the
source of truth; android-assets/default_stickers/ is a pngquant-optimised copy laid out the
way a consumer's assets/ directory wants it, plus a minified manifest. Never hand-edit the
generated tree — change the packs and re-run.
"""
import os, glob, json, subprocess, re, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'android-assets', 'default_stickers')
# pack id -> [(category folder or '' for a flat pack, chip label)]
CATS = {
    'bao': [('00-originals', 'Originals'), ('01-core-emotions', 'Emotions'), ('02-conversation', 'Chat'),
            ('03-affection-support', 'Love'), ('04-everyday-life', 'Life')],
    'fauna': [('', 'Fauna')],
    'flora': [('', 'Flora')],
}
PACK_LABEL = {'bao': 'Bao', 'fauna': 'Fauna', 'flora': 'Flora'}

def quant(src, dst):
    """pngquant at the highest quality floor it can meet; exit 99 = floor not reachable."""
    for q in ('80-98', '55-98', '30-98'):
        r = subprocess.run(['pngquant', f'--quality={q}', '--speed', '1', '--strip', '--force', '--output', dst, src])
        if r.returncode == 0: return q
        if r.returncode != 99: raise RuntimeError(f'pngquant failed ({r.returncode}) on {src}')
    shutil.copy(src, dst); return 'copy'

def main():
    shutil.rmtree(OUT, ignore_errors=True); os.makedirs(OUT)
    manifest = {'version': 1, 'packs': []}
    total_src = total_out = 0; fallbacks = []
    for pack, cats in CATS.items():
        pm = {'id': pack, 'label': PACK_LABEL[pack], 'categories': []}
        for cat, label in cats:
            srcdir = os.path.join(ROOT, pack, cat) if cat else os.path.join(ROOT, pack)
            files = sorted(f for f in glob.glob(os.path.join(srcdir, '*.png')) if not os.path.basename(f).startswith('preview'))
            cm = {'id': cat or pack, 'label': label, 'stickers': []}
            for f in files:
                base = os.path.basename(f); stem = base[:-4]
                words = re.sub(r'^\d+-', '', stem).split('-')
                rel = '/'.join(p for p in (pack, cat, base) if p)
                dst = os.path.join(OUT, *rel.split('/')); os.makedirs(os.path.dirname(dst), exist_ok=True)
                q = quant(f, dst)
                if q != '80-98': fallbacks.append((rel, q))
                total_src += os.path.getsize(f); total_out += os.path.getsize(dst)
                cm['stickers'].append({'file': rel, 'name': ' '.join(words).capitalize(), 'keywords': words})
            if cm['stickers'] or pack == 'flora': pm['categories'].append(cm)
        manifest['packs'].append(pm)
    json.dump(manifest, open(os.path.join(ROOT, 'manifest.json'), 'w'), indent=1)
    json.dump(manifest, open(os.path.join(OUT, 'manifest.json'), 'w'), separators=(',', ':'))
    n = sum(len(c['stickers']) for p in manifest['packs'] for c in p['categories'])
    print(f'{n} stickers: sources {total_src/1e6:.1f} MB -> bundled {total_out/1e6:.1f} MB')
    if fallbacks: print('quality floor lowered for:', fallbacks)

if __name__ == '__main__':
    main()
