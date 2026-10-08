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

# rel path ("<pack>/[<category>/]<file>.png") -> extra search words, layered on top of the
# words mechanically derived from the file stem. 75 of 115 stems today derive to exactly one
# word (the file's own name minus its numeric prefix), so a search for a genuine synonym like
# "happy" or "sad" finds nothing even though a matching sticker exists — this is that synonym
# map. Extend it whenever a new sticker's derived keyword doesn't cover how someone would
# actually search for it.
SYNONYMS = {
    'bao/00-originals/01-garland.png': ['flowers', 'decoration', 'festive'],
    'bao/00-originals/02-victory.png': ['win', 'winner', 'celebrate', 'yay'],
    'bao/00-originals/03-laughing.png': ['laugh', 'haha', 'lol', 'funny'],
    'bao/00-originals/04-prayer-love.png': ['pray', 'thanks', 'namaste', 'love', 'gratitude'],
    'bao/00-originals/05-wave.png': ['hello', 'hi', 'bye', 'hey'],
    'bao/00-originals/06-namaste.png': ['hello', 'greeting', 'respect', 'hi'],
    'bao/00-originals/07-thumbs-up.png': ['good', 'yes', 'ok', 'approve', 'nice'],
    'bao/00-originals/08-bird-friend.png': ['bird', 'pet', 'friend'],
    'bao/00-originals/09-thinking.png': ['hmm', 'ponder', 'idea', 'thought'],
    'bao/00-originals/10-shrug.png': ['dunno', 'idk', 'whatever', 'unsure'],
    'bao/00-originals/11-surprised.png': ['shock', 'wow', 'omg', 'surprise'],
    'bao/00-originals/12-pondering.png': ['think', 'hmm', 'consider'],
    'bao/00-originals/13-angry.png': ['mad', 'furious', 'upset', 'annoyed'],
    'bao/00-originals/14-sad.png': ['unhappy', 'down', 'cry', 'blue'],
    'bao/00-originals/15-crying.png': ['cry', 'tears', 'sad', 'sob'],
    'bao/00-originals/16-bashful.png': ['shy', 'blush', 'embarrassed'],
    'bao/00-originals/17-facepalm.png': ['oops', 'fail', 'ugh', 'smh'],
    'bao/00-originals/18-spa-day.png': ['relax', 'spa', 'selfcare', 'pamper'],
    'bao/00-originals/19-sleeping.png': ['sleep', 'tired', 'zzz', 'nap'],
    'bao/00-originals/20-reading.png': ['book', 'study'],
    'bao/00-originals/21-daydreaming.png': ['dream', 'imagine', 'daydream'],
    'bao/00-originals/22-self-love.png': ['selfcare', 'love'],
    'bao/00-originals/23-peaceful.png': ['calm', 'zen', 'relax', 'peace'],
    'bao/00-originals/24-fist-pump.png': ['yes', 'win', 'excited', 'yeah'],
    'bao/01-core-emotions/01-delighted-grin.png': ['happy', 'smile', 'joy', 'grinning'],
    'bao/01-core-emotions/02-laughing-tears.png': ['lol', 'haha', 'funny', 'laughing'],
    'bao/01-core-emotions/03-finger-guns.png': ['cool', 'nice', 'awesome'],
    'bao/01-core-emotions/04-side-eye.png': ['suspicious', 'skeptical'],
    'bao/01-core-emotions/05-eye-roll.png': ['annoyed', 'whatever', 'ugh'],
    'bao/01-core-emotions/06-awkward-smile.png': ['awkward', 'nervous', 'cringe'],
    'bao/01-core-emotions/07-startled.png': ['shocked', 'surprised', 'scared'],
    'bao/01-core-emotions/08-scared.png': ['afraid', 'fear', 'scared'],
    'bao/01-core-emotions/09-panicking.png': ['panic', 'scared', 'stressed'],
    'bao/01-core-emotions/10-worried.png': ['anxious', 'nervous', 'concerned'],
    'bao/01-core-emotions/11-frustrated.png': ['annoyed', 'upset', 'mad'],
    'bao/01-core-emotions/12-furious.png': ['angry', 'mad', 'rage'],
    'bao/01-core-emotions/13-sulking.png': ['pouty', 'upset', 'moody', 'sad'],
    'bao/01-core-emotions/14-heartbroken.png': ['sad', 'heartbreak', 'crying'],
    'bao/01-core-emotions/15-relieved.png': ['relief', 'phew'],
    'bao/01-core-emotions/16-exhausted.png': ['tired', 'sleepy', 'drained'],
    'bao/01-core-emotions/17-feverish.png': ['sick', 'ill', 'fever'],
    'bao/01-core-emotions/18-dizzy.png': ['woozy', 'confused'],
    'bao/01-core-emotions/19-pleading.png': ['please', 'beg'],
    'bao/01-core-emotions/20-smug.png': ['proud', 'confident'],
    'bao/02-conversation/04-sorry.png': ['apology', 'oops'],
    'bao/02-conversation/05-grateful.png': ['thanks', 'thank you', 'gratitude'],
    'bao/02-conversation/07-one-moment.png': ['wait', 'brb', 'moment'],
    'bao/02-conversation/11-shh.png': ['quiet', 'silence', 'hush'],
    'bao/02-conversation/12-call-me.png': ['phone', 'call'],
    'bao/02-conversation/13-messaging.png': ['text', 'chat', 'message'],
    'bao/02-conversation/15-on-it.png': ['working', 'busy'],
    'bao/02-conversation/16-done.png': ['finished', 'complete'],
    'bao/02-conversation/17-idea.png': ['lightbulb', 'idea'],
    'bao/02-conversation/19-choosing.png': ['decide', 'choice'],
    'bao/02-conversation/20-peeking.png': ['peek', 'spy', 'shy'],
    'bao/03-affection-support/03-hand-heart.png': ['love', 'heart'],
    'bao/03-affection-support/04-heart-to-chest.png': ['love', 'grateful'],
    'bao/03-affection-support/05-smitten.png': ['love', 'crush', 'infatuated'],
    'bao/03-affection-support/06-sending-hearts.png': ['love', 'kisses'],
    'bao/03-affection-support/07-comforting.png': ['support', 'hug', 'there for you'],
    'bao/03-affection-support/08-reassuring.png': ['support', 'comfort', 'there for you'],
    'bao/03-affection-support/09-proud-applause.png': ['proud', 'clap', 'applause'],
    'bao/03-affection-support/10-celebration.png': ['party', 'celebrate', 'yay'],
    'bao/03-affection-support/13-peace.png': ['peace sign', 'victory'],
    'bao/03-affection-support/16-congratulations.png': ['congrats', 'well done'],
    'bao/03-affection-support/17-gift.png': ['present', 'gift'],
    'bao/03-affection-support/18-birthday.png': ['bday', 'cake', 'party'],
    'bao/03-affection-support/20-goodbye.png': ['bye', 'farewell'],
    'bao/03-affection-support/21-finger-heart.png': ['love', 'korean heart'],
    'bao/04-everyday-life/01-sipping-chai.png': ['tea', 'drink'],
    'bao/04-everyday-life/02-offering-chai.png': ['tea', 'share'],
    'bao/04-everyday-life/03-hungry.png': ['hunger', 'food'],
    'bao/04-everyday-life/04-eating.png': ['food', 'meal'],
    'bao/04-everyday-life/05-hydrate.png': ['water', 'drink', 'thirsty'],
    'bao/04-everyday-life/06-working.png': ['work', 'job', 'busy'],
    'bao/04-everyday-life/07-studying.png': ['study', 'school', 'homework'],
    'bao/04-everyday-life/08-music.png': ['song', 'listening', 'headphones'],
    'bao/04-everyday-life/09-dancing.png': ['dance', 'party'],
    'bao/04-everyday-life/10-stretch.png': ['exercise', 'stretching'],
    'bao/04-everyday-life/11-workout.png': ['gym', 'exercise', 'fitness'],
    'bao/04-everyday-life/12-shopping.png': ['shop', 'buy'],
    'bao/04-everyday-life/13-cooking.png': ['cook', 'kitchen'],
    'bao/04-everyday-life/14-plant-care.png': ['garden', 'plant'],
    'bao/04-everyday-life/15-rainy-day.png': ['rain', 'weather'],
    'bao/04-everyday-life/16-summer-heat.png': ['hot', 'summer'],
    'bao/04-everyday-life/17-winter-cold.png': ['cold', 'winter', 'snow'],
    'bao/04-everyday-life/18-commuting.png': ['travel', 'commute'],
    'bao/04-everyday-life/19-diya.png': ['festival', 'light', 'diwali'],
    'bao/04-everyday-life/20-holi.png': ['festival', 'colors'],
    'fauna/01-leaf-footed-bug.png': ['bug', 'insect'],
    'fauna/02-bocydium-treehopper.png': ['bug', 'insect', 'treehopper'],
    'fauna/03-lanternfly.png': ['bug', 'insect'],
    'fauna/04-curl-horn-treehopper.png': ['bug', 'insect', 'treehopper'],
    'fauna/05-horned-hopper.png': ['bug', 'insect', 'hopper'],
    'fauna/06-shield-bug.png': ['bug', 'insect'],
    'fauna/07-spiny-caterpillar.png': ['bug', 'caterpillar', 'insect'],
    'fauna/08-pink-frog.png': ['frog', 'animal'],
    'fauna/09-bird-of-paradise.png': ['bird', 'animal'],
    'fauna/10-deep-sea-drifter.png': ['jellyfish', 'sea', 'animal'],
}

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
                keywords = words + [w for w in SYNONYMS.get(rel, []) if w not in words]
                dst = os.path.join(OUT, *rel.split('/')); os.makedirs(os.path.dirname(dst), exist_ok=True)
                q = quant(f, dst)
                if q != '80-98': fallbacks.append((rel, q))
                total_src += os.path.getsize(f); total_out += os.path.getsize(dst)
                cm['stickers'].append({'file': rel, 'name': ' '.join(words).capitalize(), 'keywords': keywords})
            if cm['stickers']: pm['categories'].append(cm)
        manifest['packs'].append(pm)
    json.dump(manifest, open(os.path.join(ROOT, 'manifest.json'), 'w'), indent=1)
    json.dump(manifest, open(os.path.join(OUT, 'manifest.json'), 'w'), separators=(',', ':'))
    n = sum(len(c['stickers']) for p in manifest['packs'] for c in p['categories'])
    print(f'{n} stickers: sources {total_src/1e6:.1f} MB -> bundled {total_out/1e6:.1f} MB')
    if fallbacks: print('quality floor lowered for:', fallbacks)

if __name__ == '__main__':
    main()
