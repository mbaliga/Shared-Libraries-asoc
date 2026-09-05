"""Turn a flat illustration on a plain paper background into a 512x512 die-cut sticker.

Pipeline: estimate the paper colour from the image frame; flood the paper region (pixels
within TOL of it, connected to the border, plus any enclosed paper pocket bigger than
MIN_POCKET) to alpha 0; ramp alpha across the 2px anti-aliased fringe and de-fringe its
colour; crop to content; scale to fit; add the white die-cut border with a faint grey rim.
"""
import sys, os, numpy as np
from PIL import Image, ImageDraw, ImageFilter

TOL = 14          # max per-channel distance from paper colour that still counts as paper
FRINGE_LO, FRINGE_HI = 8, 30
MIN_POCKET = 2000 # px^2 at source resolution: enclosed paper pockets at least this big go transparent
OUT = 512
BORDER = 13       # white die-cut border, px at OUT
RIM = 1.6         # grey rim outside the white border
CONTENT_MAX = OUT - 2 * (BORDER + 6)

def paper_colour(rgb):
    f = 12
    frame = np.concatenate([rgb[:f].reshape(-1,3), rgb[-f:].reshape(-1,3), rgb[:,:f].reshape(-1,3), rgb[:,-f:].reshape(-1,3)])
    return np.median(frame, axis=0)

def label_paper(near, min_pocket):
    """near: bool HxW. Returns bool mask of paper = border-connected near-region + big pockets."""
    h, w = near.shape
    lab = Image.fromarray(np.where(near, 1, 0).astype(np.int32), mode='I')
    px = lab.load()
    # 1 = unvisited paper candidate, 0 = object, 2 = border-connected paper, 3+ = pocket ids
    for x in range(w):
        for y in (0, h-1):
            if px[x, y] == 1: ImageDraw.floodfill(lab, (x, y), 2, thresh=0)
    for y in range(h):
        for x in (0, w-1):
            if px[x, y] == 1: ImageDraw.floodfill(lab, (x, y), 2, thresh=0)
    arr = np.array(lab)
    paper = arr == 2
    # enclosed pockets: seed from a coarse grid, measure, keep the big ones
    nxt = 3
    for y in range(0, h, 6):
        for x in range(0, w, 6):
            if px[x, y] == 1:
                ImageDraw.floodfill(lab, (x, y), nxt, thresh=0)
                a2 = np.array(lab)
                area = int((a2 == nxt).sum())
                if area >= min_pocket: paper |= (a2 == nxt)
                nxt += 1
    return paper

def cutout(im):
    rgb = np.asarray(im.convert('RGB')).astype(np.float32)
    bg = paper_colour(rgb)
    d = np.abs(rgb - bg).max(axis=2)
    near = d <= TOL
    paper = label_paper(near, MIN_POCKET)
    alpha = np.ones(d.shape, np.float32)
    alpha[paper] = 0.0
    # 2px fringe band just outside the paper mask gets a distance ramp + de-fringe
    pm = Image.fromarray((paper * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))
    band = (np.array(pm) > 0) & ~paper
    ramp = np.clip((d - FRINGE_LO) / (FRINGE_HI - FRINGE_LO), 0, 1)
    alpha[band] = ramp[band]
    out = rgb.copy()
    a3 = alpha[..., None]
    sel = band & (alpha > 0.02)
    out[sel] = np.clip((rgb[sel] - (1 - a3[sel]) * bg) / np.maximum(a3[sel], 0.02), 0, 255)
    rgba = np.dstack([out, alpha * 255]).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')

def split_columns(im, min_gap=14):
    """Split a horizontal strip of several subjects on shared paper into separate images."""
    rgb = np.asarray(im.convert('RGB')).astype(np.float32)
    bg = paper_colour(rgb); d = np.abs(rgb - bg).max(axis=2)
    occ = (d > TOL).sum(axis=0) > 2
    segs, x, w = [], 0, len(occ)
    while x < w:
        while x < w and not occ[x]: x += 1
        if x >= w: break
        s = x
        gap = 0
        while x < w and gap < min_gap:
            gap = gap + 1 if not occ[x] else 0
            x += 1
        e = x - gap
        segs.append((max(0, s - 8), min(w, e + 8)))
    return [im.crop((s, 0, e, im.height)) for s, e in segs]

def finish(cut):
    a = np.array(cut)[..., 3]
    ys, xs = np.where(a > 8)
    cut = cut.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    scale = min(CONTENT_MAX / cut.width, CONTENT_MAX / cut.height)
    cut = cut.resize((max(1, round(cut.width * scale)), max(1, round(cut.height * scale))), Image.LANCZOS)
    canvas = Image.new('RGBA', (OUT, OUT), (0, 0, 0, 0))
    ox, oy = (OUT - cut.width) // 2, (OUT - cut.height) // 2
    layer = Image.new('RGBA', (OUT, OUT), (0, 0, 0, 0)); layer.paste(cut, (ox, oy), cut)
    mask = layer.getchannel('A')
    # white border = dilated alpha; MaxFilter needs an odd size
    k = 2 * BORDER + 1
    hard = mask.point(lambda v: 255 if v > 24 else 0)
    white = hard.filter(ImageFilter.MaxFilter(k)).filter(ImageFilter.GaussianBlur(0.8))
    rim = hard.filter(ImageFilter.MaxFilter(k + 4)).filter(ImageFilter.GaussianBlur(1.2))
    out = Image.new('RGBA', (OUT, OUT), (0, 0, 0, 0))
    rim_layer = Image.new('RGBA', (OUT, OUT), (150, 150, 150, 0)); rim_layer.putalpha(rim.point(lambda v: int(v * 0.55)))
    white_layer = Image.new('RGBA', (OUT, OUT), (255, 255, 255, 0)); white_layer.putalpha(white)
    out = Image.alpha_composite(out, rim_layer)
    out = Image.alpha_composite(out, white_layer)
    out = Image.alpha_composite(out, layer)
    return out

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    im = Image.open(src)
    parts = [im] if not (len(sys.argv) > 3 and sys.argv[3] == '--split') else split_columns(im)
    names = dst.split(',')
    assert len(names) == len(parts), f'{len(parts)} parts found, {len(names)} names given: {names}'
    for p, n in zip(parts, names):
        finish(cutout(p)).save(n, 'PNG', optimize=True); print('wrote', n)
