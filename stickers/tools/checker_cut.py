"""Recover a die-cut sticker exported on a BAKED-IN checkerboard (fake transparency).
The sticker keeps its own white border (it came from the same generator as the pack); only
the checker is removed, speckle remnants are dropped by area, the edge is de-fringed against
the checker tone, and the result is re-fitted to the pack's scale (max content extent FIT px)."""
import sys, numpy as np
from PIL import Image, ImageDraw, ImageFilter
OUT = 512; FIT = 480; MIN_BLOB = 300   # px^2 at source: smaller foreground islands are compression specks

def label_fill(mask):
    """4-connected flood from the image border over `mask`; returns bool of border-connected pixels."""
    h, w = mask.shape
    lab = Image.fromarray(np.where(mask, 1, 0).astype(np.int32), mode='I'); px = lab.load()
    for x in range(w):
        for y in (0, h-1):
            if px[x, y] == 1: ImageDraw.floodfill(lab, (x, y), 2, thresh=0)
    for y in range(h):
        for x in (0, w-1):
            if px[x, y] == 1: ImageDraw.floodfill(lab, (x, y), 2, thresh=0)
    return np.array(lab) == 2

def drop_small_islands(fg, min_area):
    h, w = fg.shape
    lab = Image.fromarray(np.where(fg, 1, 0).astype(np.int32), mode='I'); px = lab.load()
    keep = np.zeros_like(fg); nxt = 2
    ys, xs = np.where(fg)
    for y, x in zip(ys[::2], xs[::2]):
        if px[int(x), int(y)] != 1: continue
        ImageDraw.floodfill(lab, (int(x), int(y)), nxt, thresh=0)
        a = np.array(lab) == nxt
        if a.sum() >= min_area: keep |= a
        nxt += 1
    return keep

def checker_cutout(im):
    rgb = np.asarray(im.convert('RGB')).astype(np.float32); h, w, _ = rgb.shape
    f = 16
    frame = np.concatenate([rgb[:f].reshape(-1,3), rgb[-f:].reshape(-1,3), rgb[:,:f].reshape(-1,3), rgb[:,-f:].reshape(-1,3)])
    v = frame.mean(axis=1); light, dark = np.percentile(v, 85), np.percentile(v, 15); tone = np.array([v.mean()]*3)
    mean = rgb.mean(axis=2); sat = rgb.max(axis=2) - rgb.min(axis=2)
    checker = (sat <= 40) & (mean >= dark - 30) & (mean <= light + 22)
    bg = label_fill(checker)
    fg = drop_small_islands(~bg, MIN_BLOB)
    bg = ~fg
    # close hairline seams, then a 2px fringe ramp de-fringed against the mean checker tone
    m = Image.fromarray((bg*255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))
    bg = np.array(m) > 127
    alpha = np.ones((h, w), np.float32); alpha[bg] = 0
    band = (np.array(Image.fromarray((bg*255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))) > 0) & ~bg
    d = np.abs(rgb - tone).max(axis=2)
    ramp = np.clip((d - 12) / 50.0, 0, 1); alpha[band] = ramp[band]
    out = rgb.copy(); a3 = alpha[..., None]; sel = band & (alpha > 0.02)
    out[sel] = np.clip((rgb[sel] - (1 - a3[sel]) * tone) / np.maximum(a3[sel], 0.02), 0, 255)
    return Image.fromarray(np.dstack([out, alpha*255]).astype(np.uint8), 'RGBA')

def fit(cut):
    a = np.array(cut)[..., 3]; ys, xs = np.where(a > 8)
    cut = cut.crop((xs.min(), ys.min(), xs.max()+1, ys.max()+1))
    s = FIT / max(cut.width, cut.height)
    cut = cut.resize((max(1, round(cut.width*s)), max(1, round(cut.height*s))), Image.LANCZOS)
    canvas = Image.new('RGBA', (OUT, OUT), (0,0,0,0))
    # canvas.paste(cut, box, cut) would use cut's own alpha band as both the blend weight AND
    # the mask, compositing alpha as alpha^2/255 and crushing semi-transparent edge pixels well
    # below their true value; alpha_composite does real RGBA-over-RGBA compositing instead.
    canvas.alpha_composite(cut, ((OUT-cut.width)//2, (OUT-cut.height)//2))
    return canvas

if __name__ == '__main__':
    fit(checker_cutout(Image.open(sys.argv[1]))).save(sys.argv[2], 'PNG', optimize=True); print('wrote', sys.argv[2])
