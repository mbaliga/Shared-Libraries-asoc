/* asoc viz — charts whose categories are textures, not hues. One ink on one ground: solid, hatch, dots, fade and
   their kin carry identity, so a chart reads the same in print, in forced colours and to any colour vision, and the
   ground is free to be as loud as the host likes. SVG, dependency-free, nothing fetched.

   Every chart: ASOC.viz.<name>(el, data, opts) → { el, update(data, opts), table(), destroy() }
     steps(el, [{label, value, fill?}])            columns from one baseline, number and name above each
     bars(el, [{label, value, fill?}])             ranked rows; the chart sets its own height
     fan(el, [{label, value, fill?}])              parts of a whole; opts.sweep 90 | 180 | 360, opts.inner 0–0.9, opts.callout
     stack(el, [{label, value, fill?}])            one pill split into parts, labelled where the label fits
     line(el, [{label, values, fill?}] | number[]) one y axis; opts.x labels, opts.zero, opts.area, opts.grid, opts.smooth
     spark(el, number[])                           a line with nothing but its end
     halftone(el, {rows, cols, values})            a matrix where magnitude is dot area
     meter(el, {value, max, label})                a figure against its ceiling
     stat(el, {value, label, delta, vs})           the number itself (HTML)
     legend(el, [{label, fill?, dash?, value?}])   the keys, in the same textures (HTML)
   Shared opts: tone ('sun' | 'ember' | 'night' | 'bone' | 'radium' | 'violet' | {ink, ground, accent}), or ink, ground,
     accent directly; rem (16); font; weight (300); format(n); height; scale (texture scale, 1); fill (one texture for
     every mark, or 'each'); label (accessible name); on: { hover(d), select(d) }; responsive (true).
   ASOC.viz.order is the fixed texture order — a ninth part folds into "Other", never a ninth texture.
   ASOC.viz.fills(svg, {ink, ground, scale}) → paint(kind, dir) for hosts that draw their own marks. */
(function (root, factory) {
  var api = factory();
  root.ASOC = root.ASOC || {}; root.ASOC.viz = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg', uid = 0;
  var ORDER = ['solid', 'hatch', 'dots', 'fade', 'cross', 'lines', 'checks', 'stipple'];
  var DASH = [null, '7 5', '1.5 5', '11 4 2 4'];
  var TONES = {
    sun: { ground: '#f6ee2d', ink: '#0c0c0c' },
    ember: { ground: '#ff8f4c', ink: '#0c0c0c', accent: '#f6ee2d' },
    night: { ground: '#0c0c0d', ink: '#f2efe9', accent: '#f6ee2d' },
    bone: { ground: '#edebe6', ink: '#0c0c0c' },
    radium: { ground: '#ccec75', ink: '#07070a' },
    violet: { ground: '#8e7bff', ink: '#07070a', accent: '#ccec75' }
  };

  function f(n) { return Math.round(n * 10) / 10; }
  function S(name, a, parent) {
    var e = document.createElementNS(NS, name), k;
    for (k in a) if (a[k] != null) e.setAttribute(k, a[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function H(name, css, parent, text) {
    var e = document.createElement(name);
    if (css) e.style.cssText = css;
    if (text != null) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }
  function fmt(n) {
    if (typeof n !== 'number' || !isFinite(n)) return String(n);
    var a = Math.abs(n);
    if (a >= 1e4) { try { return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n); } catch (e) {} }
    return n.toLocaleString(undefined, { maximumFractionDigits: a < 10 ? 2 : 1 });
  }
  function nice(v) {
    if (v <= 0) return 1;
    var p = Math.pow(10, Math.floor(Math.log(v) / Math.LN10)), m = v / p;
    return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 2.5 ? 2.5 : m <= 5 ? 5 : 10) * p;
  }
  function est(str, size) { return String(str).length * size * 0.56; }
  function clip(str, size, room) {
    str = String(str);
    if (est(str, size) <= room) return str;
    var n = Math.max(1, Math.floor(room / (size * 0.56)) - 1);
    return str.slice(0, n) + '…';
  }
  function tone(o) { var t = typeof o.tone === 'string' ? TONES[o.tone] : o.tone; return t || {}; }
  /* more than eight parts: the first seven keep their textures, the rest become one "Other" */
  function fold(d) {
    if (d.length <= ORDER.length) return d;
    var head = d.slice(0, ORDER.length - 1), rest = d.slice(ORDER.length - 1), sum = 0;
    rest.forEach(function (x) { sum += +x.value || 0; });
    return head.concat([{ label: 'Other', value: sum, parts: rest }]);
  }

  /* ── textures ─────────────────────────────────────────────────────────────────────────────────────────────── */
  function fills(svg, o) {
    o = o || {};
    var id = 'asocv' + (++uid) + '-', defs = S('defs', {}, svg), made = {}, k = o.scale || 1, ink = o.ink || '#0c0c0c';
    function pat(name, size, rot, build) {
      var p = S('pattern', { id: id + name, width: f(size), height: f(size), patternUnits: 'userSpaceOnUse', patternTransform: rot ? 'rotate(' + rot + ')' : null }, defs);
      build(p, size);
      return 'url(#' + id + name + ')';
    }
    function fade(dir) {
      var name = 'fade' + (dir && dir.r ? 'r' + (++uid) : dir || 'up'), g;
      if (dir && dir.r) g = S('radialGradient', { id: id + name, gradientUnits: 'userSpaceOnUse', cx: f(dir.cx), cy: f(dir.cy), r: f(dir.r) }, defs);
      else g = S('linearGradient', { id: id + name, x1: dir === 'right' ? 0 : 0, y1: dir === 'right' ? 0 : 1, x2: dir === 'right' ? 1 : 0, y2: 0 }, defs);
      S('stop', { offset: dir && dir.r ? (dir.r0 || 0.05) : 0, 'stop-color': ink, 'stop-opacity': 0.07 }, g);
      S('stop', { offset: 0.55, 'stop-color': ink, 'stop-opacity': 0.5 }, g);
      S('stop', { offset: 1, 'stop-color': ink, 'stop-opacity': 1 }, g);
      return 'url(#' + id + name + ')';
    }
    var make = {
      solid: function () { return ink; },
      hatch: function () { return pat('hatch', 7 * k, 45, function (p, s) { S('rect', { width: f(s * 0.46), height: f(s), fill: ink }, p); }); },
      dots: function () { return pat('dots', 9 * k, 0, function (p, s) { S('circle', { cx: f(s * 0.25), cy: f(s * 0.25), r: f(1.5 * k), fill: ink }, p); S('circle', { cx: f(s * 0.75), cy: f(s * 0.75), r: f(1.5 * k), fill: ink }, p); }); },
      cross: function () { return pat('cross', 7 * k, 45, function (p, s) { S('rect', { width: f(s * 0.18), height: f(s), fill: ink }, p); S('rect', { width: f(s), height: f(s * 0.18), fill: ink }, p); }); },
      lines: function () { return pat('lines', 6 * k, 0, function (p, s) { S('rect', { width: f(s), height: f(s * 0.3), fill: ink }, p); }); },
      checks: function () { return pat('checks', 8 * k, 0, function (p, s) { S('rect', { width: f(s / 2), height: f(s / 2), fill: ink }, p); S('rect', { x: f(s / 2), y: f(s / 2), width: f(s / 2), height: f(s / 2), fill: ink }, p); }); },
      stipple: function () { return pat('stipple', 4.5 * k, 0, function (p, s) { S('circle', { cx: f(s / 2), cy: f(s / 2), r: f(0.85 * k), fill: ink }, p); }); }
    };
    return function (kind, dir) {
      if (kind === 'fade') { if (dir && dir.r) return fade(dir); var key = 'fade' + (dir || 'up'); return made[key] || (made[key] = fade(dir)); }
      if (!make[kind]) kind = 'solid';
      return made[kind] || (made[kind] = make[kind]());
    };
  }

  /* ── the frame every chart shares: sizing, tooltip, hover, focus, table ───────────────────────────────────── */
  function mount(el, data, o, defH, draw) {
    o = o || {};
    var c = { el: el, marks: [], rows: [] }, tipEl = null, ro = null, lastW = -1;
    c.tip = {
      show: function (x, y, rows, head) {
        if (!tipEl) tipEl = H('div', 'position:absolute;z-index:5;pointer-events:none;padding:.4em .8em;border-radius:1em;white-space:nowrap;font-size:' + f(c.rem * 0.72) + 'px;line-height:1.35;box-shadow:0 4px 14px rgba(0,0,0,.18)', el);
        tipEl.style.background = c.ink; tipEl.style.color = c.ground; tipEl.textContent = '';
        if (head) H('div', 'opacity:.6', tipEl, head);
        rows.forEach(function (r) {
          var row = H('div', 'display:flex;gap:.5em;align-items:baseline', tipEl);
          if (r.dash !== undefined) { var k = S('svg', { width: 14, height: 6, viewBox: '0 0 14 6' }, row); S('line', { x1: 0, y1: 3, x2: 14, y2: 3, stroke: c.ground, 'stroke-width': 2, 'stroke-dasharray': r.dash }, k); }
          H('b', 'font-weight:500', row, r.v);
          if (r.l) H('span', 'opacity:.7', row, r.l);
        });
        tipEl.style.display = 'block';
        var tw = tipEl.offsetWidth, th = tipEl.offsetHeight, W = el.clientWidth, top = y - th - 10;
        if (top < 0 && y + 18 + th <= el.clientHeight) top = y + 18;
        tipEl.style.left = f(Math.max(0, Math.min(W - tw, x - tw / 2))) + 'px'; tipEl.style.top = f(top) + 'px';
      },
      hide: function () { if (tipEl) tipEl.style.display = 'none'; }
    };
    c.text = function (x, y, str, size, a) {
      a = a || {};
      var t = S('text', { x: f(x), y: f(y), 'font-size': f(size), fill: c.ink, 'text-anchor': a.anchor, 'font-weight': a.weight, opacity: a.op, 'pointer-events': 'none' }, a.parent || c.svg);
      t.textContent = str;
      return t;
    };
    c.fillOf = function (d, i, dir) { var k = d && d.fill ? d.fill : o.fill && o.fill !== 'each' ? o.fill : ORDER[i % ORDER.length]; return c.paint(k, dir); };
    /* a mark answers the pointer and the keyboard alike; `hit` may be a larger transparent target round it */
    c.mark = function (node, label, value, d, hit) {
      var txt = typeof value === 'string' ? value : c.fmt(value), on = o.on || {}, t = hit || node;
      c.marks.push(node);
      t.setAttribute('tabindex', '0'); t.setAttribute('role', 'img'); t.setAttribute('aria-label', label + ': ' + txt);
      t.style.outlineOffset = '2px'; node.style.transition = 'opacity .15s';
      if (on.select) t.style.cursor = 'pointer';
      function show(x, y) { c.marks.forEach(function (m) { m.style.opacity = m === node ? '' : 0.3; }); c.tip.show(x, y, [{ v: txt, l: label }]); if (on.hover) on.hover(d); }
      function hide() { c.marks.forEach(function (m) { m.style.opacity = ''; }); c.tip.hide(); if (on.hover) on.hover(null); }
      function at(ev) { var r = el.getBoundingClientRect(); show(ev.clientX - r.left, ev.clientY - r.top); }
      t.addEventListener('pointerenter', at); t.addEventListener('pointermove', at); t.addEventListener('pointerleave', hide);
      t.addEventListener('focus', function () { var r = el.getBoundingClientRect(), b = node.getBoundingClientRect(); show(b.left + b.width / 2 - r.left, b.top - r.top); });
      t.addEventListener('blur', hide);
      t.addEventListener('click', function () { if (on.select) on.select(d); });
      t.addEventListener('keydown', function (ev) { if ((ev.key === 'Enter' || ev.key === ' ') && on.select) { ev.preventDefault(); on.select(d); } });
    };
    function render() {
      var t = tone(o), cs = getComputedStyle(el);
      c.o = o; c.data = data; c.ink = o.ink || t.ink || '#0c0c0c'; c.ground = o.ground || t.ground || '#f6ee2d'; c.accent = o.accent || t.accent || null;
      c.rem = o.rem || 16; c.fmt = o.format || fmt; c.weight = o.weight || 300; c.marks = []; c.rows = []; c.head = null;
      if (cs.position === 'static') el.style.position = 'relative';
      c.w = o.width || Math.max(40, el.clientWidth - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0)) || 320;
      c.h = o.height || defH(c, el.clientHeight - (parseFloat(cs.paddingTop) || 0) - (parseFloat(cs.paddingBottom) || 0));
      if (c.svg && c.svg.parentNode) c.svg.parentNode.removeChild(c.svg);
      c.tip.hide();
      c.svg = S('svg', { viewBox: '0 0 ' + f(c.w) + ' ' + f(c.h), width: f(c.w), height: f(c.h), role: 'group' });
      c.svg.style.cssText = 'display:block;overflow:visible;font-family:' + (o.font || 'inherit') + ';font-variant-numeric:tabular-nums';
      el.insertBefore(c.svg, el.firstChild);
      c.paint = fills(c.svg, { ink: c.ink, scale: o.scale });
      draw(c);
      c.svg.setAttribute('aria-label', o.label || c.rows.slice(0, 12).map(function (r) { return r.join(' '); }).join(', '));
      lastW = el.clientWidth;
    }
    render();
    if (o.responsive !== false && typeof ResizeObserver !== 'undefined') {
      ro = new ResizeObserver(function () { if (Math.abs(el.clientWidth - lastW) > 0.5) render(); });
      ro.observe(el);
    }
    return {
      get el() { return c.svg; },
      update: function (d, o2) { if (d != null) data = d; if (o2) { for (var k in o2) o[k] = o2[k]; } render(); return this; },
      table: function () {
        var tb = document.createElement('table'), tr;
        if (c.head) { tr = H('tr', '', H('thead', '', tb)); c.head.forEach(function (h) { H('th', '', tr, h).scope = 'col'; }); }
        var body = H('tbody', '', tb);
        c.rows.forEach(function (r) { tr = H('tr', '', body); r.forEach(function (v, i) { H(i ? 'td' : 'th', '', tr, v).scope = i ? '' : 'row'; }); });
        return tb;
      },
      destroy: function () { if (ro) ro.disconnect(); if (c.svg && c.svg.parentNode) c.svg.parentNode.removeChild(c.svg); if (tipEl && tipEl.parentNode) tipEl.parentNode.removeChild(tipEl); }
    };
  }

  /* ── steps: columns from one baseline, the figure standing on each ────────────────────────────────────────── */
  function steps(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 60 ? ch : c.rem * 12; }, function (c) {
      var d = c.data, n = d.length || 1, rem = c.rem, gap = c.o.gap == null ? Math.max(6, c.w * 0.03) : c.o.gap, bw = (c.w - gap * (n - 1)) / n;
      var big = Math.min(rem * 1.5, bw * 0.5), small = rem * 0.64, head = big * 0.8 + small + rem * 0.85, max = 0, plot = c.h - head;
      d.forEach(function (it) { max = Math.max(max, +it.value || 0); });
      c.head = ['', c.o.unit || 'Value'];
      d.forEach(function (it, i) {
        var v = +it.value || 0, bh = v > 0 ? Math.max(3, v / (max || 1) * plot) : 0, x = i * (bw + gap), y = c.h - bh, r = Math.min(c.o.radius == null ? rem * 0.9 : c.o.radius, bw / 2, bh);
        var g = S('g', {}, c.svg), hit = S('rect', { x: f(x - gap / 2), y: 0, width: f(bw + gap), height: f(c.h), fill: 'transparent' }, g);
        if (bh) S('path', { d: 'M' + f(x) + ',' + f(c.h) + 'V' + f(y + r) + 'a' + f(r) + ',' + f(r) + ' 0 0 1 ' + f(r) + ',' + f(-r) + 'h' + f(bw - 2 * r) + 'a' + f(r) + ',' + f(r) + ' 0 0 1 ' + f(r) + ',' + f(r) + 'V' + f(c.h) + 'Z', fill: c.fillOf(it, i, 'up') }, g);
        c.text(x + 1, y - small - rem * 0.62, c.fmt(v), big, { weight: c.weight, parent: g });
        c.text(x + 1, y - rem * 0.4, clip(it.label, small, bw + gap * 0.6), small, { op: 0.72, parent: g });
        c.rows.push([it.label, c.fmt(v)]);
        c.mark(g, it.label, v, it, hit);
      });
    });
  }

  /* ── bars: ranked rows, the name and the figure on the line above each ────────────────────────────────────── */
  function bars(el, data, o) {
    function rowH(c) { return c.rem * 2.5; }
    return mount(el, data, o, function (c) { return (c.data.length || 1) * rowH(c) - c.rem * 0.5; }, function (c) {
      var d = c.data, rem = c.rem, row = rowH(c), th = c.o.thickness || rem * 0.75, small = rem * 0.72, max = 0;
      d.forEach(function (it) { max = Math.max(max, +it.value || 0); });
      c.head = ['', c.o.unit || 'Value'];
      d.forEach(function (it, i) {
        var v = +it.value || 0, y = i * row, bw = v > 0 ? Math.max(3, v / (max || 1) * c.w) : 0, r = Math.min(th / 2, bw), val = c.fmt(v);
        var g = S('g', {}, c.svg), hit = S('rect', { x: 0, y: f(y), width: f(c.w), height: f(row), fill: 'transparent' }, g);
        c.text(0, y + small, clip(it.label, small, c.w - est(val, small) - rem), small, { op: 0.8, parent: g });
        c.text(c.w, y + small, val, small, { anchor: 'end', weight: 500, parent: g });
        if (bw) S('path', { d: 'M0,' + f(y + small + rem * 0.45) + 'h' + f(bw - r) + 'a' + f(r) + ',' + f(th / 2) + ' 0 0 1 0,' + f(th) + 'H0Z', fill: c.o.fill === 'each' || it.fill ? c.fillOf(it, i, 'right') : c.paint(c.o.fill || 'solid', 'right') }, g);
        c.rows.push([it.label, val]);
        c.mark(g, it.label, v, it, hit);
      });
    });
  }

  /* ── fan: parts of a whole as a quarter, a half or a ring ─────────────────────────────────────────────────── */
  function fan(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 60 ? ch : (c.o.sweep >= 360 ? Math.min(c.w, c.rem * 14) : c.rem * 12); }, function (c) {
      var d = fold(c.data), rem = c.rem, sweep = c.o.sweep || 180, full = sweep >= 360, quarter = sweep <= 90, total = 0, ph = rem * 1.9;
      var call = c.o.callout === false ? null : c.o.callout, cx, cy, R, a0, inner;
      d.forEach(function (it) { total += Math.max(0, +it.value || 0); });
      if (call === undefined || call === true) call = c.fmt(total);
      if (full) { cx = c.w / 2; cy = c.h / 2; R = Math.min(c.w, c.h) / 2 - 1; a0 = -Math.PI / 2; }
      else if (quarter) { cx = c.w; cy = c.h; R = Math.min(c.w - (call ? est(call, rem) / 2 + rem : 0), c.h - (call ? ph / 2 : 0)) / 1; R = Math.min(R, Math.min(c.w, c.h) * 1.25); a0 = Math.PI; }
      else { cx = c.w / 2; cy = c.h; R = Math.min(c.w / 2, c.h - (call ? ph / 2 : 0)); a0 = Math.PI; }
      inner = (c.o.inner == null ? (full ? 0.6 : 0) : c.o.inner) * R;
      var span = (full ? 360 : quarter ? 90 : 180) * Math.PI / 180, a = a0;
      c.head = ['', c.o.unit || 'Value', 'Share'];
      function pt(r, ang) { return f(cx + Math.cos(ang) * r) + ',' + f(cy + Math.sin(ang) * r); }
      d.forEach(function (it, i) {
        var v = Math.max(0, +it.value || 0), share = total ? v / total : 0, a1 = a + share * span, big = a1 - a > Math.PI ? 1 : 0, pct = Math.round(share * 100) + '%';
        c.rows.push([it.label, c.fmt(v), pct]);
        if (!v) return;
        var one = share > 0.9995, e = one ? a1 - 0.0001 : a1, path = 'M' + pt(R, a) + 'A' + f(R) + ',' + f(R) + ' 0 ' + big + ' 1 ' + pt(R, e);
        path += inner > 0 ? 'L' + pt(inner, e) + 'A' + f(inner) + ',' + f(inner) + ' 0 ' + big + ' 0 ' + pt(inner, a) + 'Z' : 'L' + f(cx) + ',' + f(cy) + 'Z';
        var fillKey = it.fill || (c.o.fill && c.o.fill !== 'each' ? c.o.fill : ORDER[i % ORDER.length]);
        var w = S('path', { d: path, fill: fillKey === 'fade' ? c.paint('fade', { cx: cx, cy: cy, r: R, r0: Math.max(0.05, inner / R) }) : c.paint(fillKey), stroke: c.ground, 'stroke-width': one ? 0 : 2, 'stroke-linejoin': 'round' }, c.svg);
        c.mark(w, it.label, c.fmt(v) + ' · ' + pct, it);
        a = a1;
      });
      if (!call) return;
      if (full) {
        c.text(cx, cy + (c.o.caption ? 0 : rem * 0.5), call, Math.min(rem * 1.7, inner * 0.62), { anchor: 'middle', weight: c.weight });
        if (c.o.caption) c.text(cx, cy + rem * 1.05, clip(c.o.caption, rem * 0.64, inner * 1.6), rem * 0.64, { anchor: 'middle', op: 0.7 });
        return;
      }
      var am = quarter ? a0 + span * 0.42 : a0 + span / 2, px = cx + Math.cos(am) * R, py = cy + Math.sin(am) * R, pw = est(call, rem * 0.95) + rem * 1.5;
      px = Math.max(pw / 2, Math.min(c.w - pw / 2, px)); py = Math.max(ph / 2, py);
      S('rect', { x: f(px - pw / 2), y: f(py - ph / 2), width: f(pw), height: f(ph), rx: f(ph / 2), fill: c.accent || c.ink, 'pointer-events': 'none' }, c.svg);
      var t = c.text(px, py + rem * 0.33, call, rem * 0.95, { anchor: 'middle', weight: 400 });
      if (!c.accent) t.setAttribute('fill', c.ground);
    });
  }

  /* ── stack: one pill, split ───────────────────────────────────────────────────────────────────────────────── */
  function stack(el, data, o) {
    return mount(el, data, o, function (c) { return (c.o.thickness || c.rem * 1.75) + c.rem * 2.6; }, function (c) {
      var d = fold(c.data).filter(function (it) { return +it.value > 0; }), rem = c.rem, th = c.o.thickness || rem * 1.75, gap = 2, total = 0, x = 0;
      var big = rem * 0.95, small = rem * 0.64, room = c.w - gap * (d.length - 1), cid = 'asocv' + (++uid) + '-clip';
      d.forEach(function (it) { total += +it.value; });
      S('rect', { width: f(c.w), height: f(th), rx: f(th / 2) }, S('clipPath', { id: cid }, S('defs', {}, c.svg)));
      var band = S('g', { 'clip-path': 'url(#' + cid + ')' }, c.svg);
      c.head = ['', c.o.unit || 'Value', 'Share'];
      d.forEach(function (it, i) {
        var v = +it.value, sw = v / total * room, pct = Math.round(v / total * 100) + '%', val = c.fmt(v), g = S('g', {}, c.svg);
        var seg = S('rect', { x: f(x), y: 0, width: f(sw), height: f(th), rx: 3, fill: c.fillOf(it, i, 'up') }, band);
        var hit = S('rect', { x: f(x), y: 0, width: f(sw), height: f(c.h), fill: 'transparent' }, g);
        if (est(val, big) <= sw - 4 && est(it.label, small) <= sw - 4) {
          c.text(x + 1, th + rem * 1.25, val, big, { weight: c.weight, parent: g });
          c.text(x + 1, th + rem * 1.25 + small + rem * 0.3, it.label, small, { op: 0.72, parent: g });
        }
        c.rows.push([it.label, val, pct]);
        c.mark(seg, it.label, val + ' · ' + pct, it, hit);
        x += sw + gap;
      });
    });
  }

  /* ── line: one axis, the first series standing on a fade ──────────────────────────────────────────────────── */
  function curve(p) {
    var n = p.length, i, d = [], m = [], out;
    if (n < 3) return 'M' + p.map(function (q) { return f(q[0]) + ',' + f(q[1]); }).join('L');
    for (i = 0; i < n - 1; i++) d.push((p[i + 1][1] - p[i][1]) / ((p[i + 1][0] - p[i][0]) || 1));
    m[0] = d[0]; m[n - 1] = d[n - 2];
    for (i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
    for (i = 0; i < n - 1; i++) {
      if (d[i] === 0) { m[i] = m[i + 1] = 0; continue; }
      var a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b;
      if (s > 9) { var t = 3 / Math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
    }
    out = 'M' + f(p[0][0]) + ',' + f(p[0][1]);
    for (i = 0; i < n - 1; i++) {
      var h = (p[i + 1][0] - p[i][0]) / 3;
      out += 'C' + f(p[i][0] + h) + ',' + f(p[i][1] + m[i] * h) + ' ' + f(p[i + 1][0] - h) + ',' + f(p[i + 1][1] - m[i + 1] * h) + ' ' + f(p[i + 1][0]) + ',' + f(p[i + 1][1]);
    }
    return out;
  }
  function line(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 30 ? ch : c.rem * (c.o.bare ? 2.2 : 12); }, function (c) {
      var series = (typeof c.data[0] === 'number' ? [{ values: c.data }] : c.data).slice(0, DASH.length), rem = c.rem, bare = !!c.o.bare, xs = c.o.x || [], small = rem * 0.64;
      var n = 0, lo = Infinity, hi = -Infinity;
      series.forEach(function (s) { n = Math.max(n, s.values.length); s.values.forEach(function (v) { if (v == null) return; lo = Math.min(lo, v); hi = Math.max(hi, v); }); });
      if (n < 1 || !isFinite(hi)) return;
      if (c.o.zero !== false && !bare) { lo = Math.min(0, lo); hi = nice(hi); }
      if (hi === lo) hi = lo + 1;
      var multi = series.length > 1, labW = 0;
      series.forEach(function (s) { if (s.label) labW = Math.max(labW, est(s.label, small)); });
      var ends = multi && !bare && labW > 0, mr = bare ? 6 : ends ? labW + rem * 0.9 : 7, mt = bare ? 6 : rem * 1.3, mb = bare ? 6 : xs.length ? rem * 1.5 : 4, ml = bare ? 2 : 0;
      var pw = c.w - ml - mr, ph = c.h - mt - mb;
      function X(i) { return ml + (n > 1 ? i / (n - 1) : 0.5) * pw; }
      function Y(v) { return mt + (1 - (v - lo) / (hi - lo)) * ph; }
      /* end labels only when they do not collide; otherwise the legend carries the names */
      if (ends) {
        var ys = series.map(function (s) { return Y(s.values[s.values.length - 1]); }).sort(function (a, b) { return a - b; });
        for (var q = 1; q < ys.length; q++) if (ys[q] - ys[q - 1] < small * 1.25) ends = false;
      }
      if (!bare) {
        if (c.o.grid) [0.5, 1].forEach(function (t) { S('line', { x1: ml, x2: f(ml + pw), y1: f(Y(lo + (hi - lo) * t)), y2: f(Y(lo + (hi - lo) * t)), stroke: c.ink, 'stroke-opacity': 0.12 }, c.svg); });
        S('line', { x1: ml, x2: f(ml + pw), y1: f(Y(lo)), y2: f(Y(lo)), stroke: c.ink, 'stroke-opacity': 0.28 }, c.svg);
        c.text(ml, mt - rem * 0.45, c.fmt(hi), small, { op: 0.6 });
        if (lo !== 0) c.text(ml, Y(lo) - rem * 0.35, c.fmt(lo), small, { op: 0.6 });
        if (xs.length) { c.text(ml, c.h - 3, xs[0], small, { op: 0.6 }); if (n > 1) c.text(ml + pw, c.h - 3, xs[n - 1], small, { op: 0.6, anchor: 'end' }); }
      }
      c.head = [c.o.xTitle || ''].concat(series.map(function (s, i) { return s.label || (c.o.unit || 'Value') + (multi ? ' ' + (i + 1) : ''); }));
      for (var r = 0; r < n; r++) c.rows.push([xs[r] == null ? String(r + 1) : xs[r]].concat(series.map(function (s) { return s.values[r] == null ? '' : c.fmt(s.values[r]); })));
      series.forEach(function (s, si) {
        var pts = [];
        s.values.forEach(function (v, i) { if (v != null) pts.push([X(i), Y(v)]); });
        if (!pts.length) return;
        var path = c.o.smooth === false ? 'M' + pts.map(function (p) { return f(p[0]) + ',' + f(p[1]); }).join('L') : curve(pts), last = pts[pts.length - 1];
        if (si === 0 && c.o.area !== false && !bare) S('path', { d: path + 'L' + f(last[0]) + ',' + f(Y(lo)) + 'L' + f(pts[0][0]) + ',' + f(Y(lo)) + 'Z', fill: c.paint(s.fill || 'fade', 'up'), opacity: s.fill && s.fill !== 'fade' ? 0.5 : 0.42 }, c.svg);
        S('path', { d: path, fill: 'none', stroke: c.ink, 'stroke-width': bare ? 1.6 : 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round', 'stroke-dasharray': DASH[si] }, c.svg);
        S('circle', { cx: f(last[0]), cy: f(last[1]), r: bare ? 3 : 4, fill: c.ink, stroke: c.ground, 'stroke-width': 2 }, c.svg);
        if (ends) c.text(last[0] + rem * 0.6, last[1] + small * 0.36, s.label, small, { op: 0.85 });
      });
      if (bare) return;
      /* the crosshair finds the x; one readout lists every series there */
      var cur = S('g', { 'pointer-events': 'none', visibility: 'hidden' }, c.svg), rule = S('line', { y1: f(mt), y2: f(Y(lo)), stroke: c.ink, 'stroke-opacity': 0.4 }, cur);
      var dots = series.map(function () { return S('circle', { r: 4.5, fill: c.ground, stroke: c.ink, 'stroke-width': 2 }, cur); });
      var over = S('rect', { x: 0, y: 0, width: f(c.w), height: f(c.h), fill: 'transparent', tabindex: 0, role: 'img', 'aria-label': 'Readout; arrow keys step through the points' }, c.svg), at = n - 1;
      over.style.outlineOffset = '2px';
      function show(i) {
        at = Math.max(0, Math.min(n - 1, i));
        var x = X(at), top = c.h;
        rule.setAttribute('x1', f(x)); rule.setAttribute('x2', f(x)); cur.setAttribute('visibility', 'visible');
        var rows = [];
        series.forEach(function (s, si) {
          var v = s.values[at];
          dots[si].setAttribute('visibility', v == null ? 'hidden' : 'inherit');
          if (v == null) return;
          dots[si].setAttribute('cx', f(x)); dots[si].setAttribute('cy', f(Y(v))); top = Math.min(top, Y(v));
          rows.push({ v: c.fmt(v), l: s.label, dash: multi ? (DASH[si] || '') : undefined });
        });
        c.tip.show(x, top - 4, rows, xs[at] == null ? null : String(xs[at]));
        if (c.o.on && c.o.on.hover) c.o.on.hover({ index: at, x: xs[at] });
      }
      function hide() { cur.setAttribute('visibility', 'hidden'); c.tip.hide(); if (c.o.on && c.o.on.hover) c.o.on.hover(null); }
      function move(ev) { var b = c.svg.getBoundingClientRect(); show(Math.round((ev.clientX - b.left - ml) / (pw || 1) * (n - 1))); }
      over.addEventListener('pointermove', move); over.addEventListener('pointerdown', move); over.addEventListener('pointerleave', hide);
      over.addEventListener('focus', function () { show(at); }); over.addEventListener('blur', hide);
      over.addEventListener('keydown', function (ev) {
        if (ev.key === 'ArrowLeft') show(at - 1); else if (ev.key === 'ArrowRight') show(at + 1); else if (ev.key === 'Home') show(0); else if (ev.key === 'End') show(n - 1); else return;
        ev.preventDefault();
      });
      over.addEventListener('click', function () { if (c.o.on && c.o.on.select) c.o.on.select({ index: at, x: xs[at] }); });
    });
  }
  function spark(el, values, o) { o = o || {}; o.bare = true; return line(el, values, o); }

  /* ── halftone: a matrix where magnitude is the area of a dot ──────────────────────────────────────────────── */
  function halftone(el, data, o) {
    function geo(c) {
      var d = c.data, small = c.rem * 0.64, labW = 0;
      (d.rows || []).forEach(function (r) { labW = Math.max(labW, est(r, small)); });
      labW = labW ? labW + c.rem * 0.6 : 0;
      var nc = (d.values[0] || []).length || 1, cell = Math.min((c.w - labW) / nc, c.o.cell || c.rem * 2.2), top = d.cols ? c.rem * 1.2 : 0;
      return { labW: labW, nc: nc, cell: cell, top: top, small: small };
    }
    return mount(el, data, o, function (c) { var g = geo(c); return g.top + c.data.values.length * g.cell; }, function (c) {
      var d = c.data, g = geo(c), max = 0, every = d.cols ? Math.max(1, Math.ceil((est(d.cols.reduce(function (a, b) { return String(b).length > String(a).length ? b : a; }, ''), g.small) + 4) / g.cell)) : 1;
      d.values.forEach(function (row) { row.forEach(function (v) { max = Math.max(max, +v || 0); }); });
      c.head = [''].concat(d.cols || d.values[0].map(function (_, i) { return String(i + 1); }));
      if (d.cols) d.cols.forEach(function (name, j) { if (j % every === 0) c.text(g.labW + (j + 0.5) * g.cell, g.small, name, g.small, { anchor: 'middle', op: 0.6 }); });
      d.values.forEach(function (row, i) {
        var y = g.top + (i + 0.5) * g.cell, rn = d.rows ? d.rows[i] : String(i + 1);
        if (d.rows) c.text(0, y + g.small * 0.36, rn, g.small, { op: 0.7 });
        c.rows.push([rn].concat(row.map(function (v) { return c.fmt(+v || 0); })));
        row.forEach(function (v, j) {
          v = +v || 0;
          var x = g.labW + (j + 0.5) * g.cell, grp = S('g', {}, c.svg), cn = d.cols ? d.cols[j] : String(j + 1);
          var hit = S('rect', { x: f(x - g.cell / 2), y: f(y - g.cell / 2), width: f(g.cell), height: f(g.cell), fill: 'transparent' }, grp);
          S('circle', { cx: f(x), cy: f(y), r: f(v > 0 ? Math.max(0.9, Math.sqrt(v / (max || 1)) * g.cell * 0.46) : 0.7), fill: c.ink, opacity: v > 0 ? 1 : 0.25 }, grp);
          c.mark(grp, rn + ' · ' + cn, v, { row: i, col: j, value: v }, hit);
        });
      });
    });
  }

  /* ── meter: a figure against its ceiling; the track is the same ink, hatched and faint ────────────────────── */
  function meter(el, data, o) {
    return mount(el, data, o, function (c) { return c.rem * (c.o.plain ? 0.75 : 3.3); }, function (c) {
      var d = c.data, rem = c.rem, max = d.max || 100, share = Math.max(0, Math.min(1, (+d.value || 0) / max)), th = c.o.thickness || rem * 0.75, y = c.h - th, big = rem * 1.5;
      var fw = share * c.w, gap = share > 0 && share < 1 ? 2 : 0, cid = 'asocv' + (++uid) + '-clip';
      if (!c.o.plain) {
        var t = c.text(0, big * 0.82, c.fmt(+d.value || 0), big, { weight: c.weight });
        var tail = S('tspan', { 'font-size': f(rem * 0.64), 'fill-opacity': 0.7, dx: f(rem * 0.4) }, t);
        tail.textContent = 'of ' + c.fmt(max) + (d.label ? ' ' + d.label : '');
      }
      S('rect', { y: f(y), width: f(c.w), height: f(th), rx: f(th / 2) }, S('clipPath', { id: cid }, S('defs', {}, c.svg)));
      var band = S('g', { 'clip-path': 'url(#' + cid + ')' }, c.svg);
      S('rect', { x: f(fw + gap), y: f(y), width: f(Math.max(0, c.w - fw - gap)), height: f(th), fill: c.paint('hatch'), opacity: 0.4 }, band);
      var bar = S('rect', { x: 0, y: f(y), width: f(fw), height: f(th), rx: 2, fill: c.paint(c.o.fill || 'solid') }, band);
      c.head = ['', 'Value', 'Of'];
      c.rows.push([d.label || 'Value', c.fmt(+d.value || 0), c.fmt(max)]);
      c.mark(bar, d.label || 'Value', c.fmt(+d.value || 0) + ' of ' + c.fmt(max) + ' · ' + Math.round(share * 100) + '%', d, S('rect', { x: 0, y: f(y - 6), width: f(c.w), height: f(th + 12), fill: 'transparent' }, c.svg));
    });
  }

  /* ── the two HTML pieces ──────────────────────────────────────────────────────────────────────────────────── */
  function colours(o) { var t = tone(o); return { ink: o.ink || t.ink || '#0c0c0c', ground: o.ground || t.ground || '#f6ee2d' }; }
  function stat(el, d, o) {
    o = o || {};
    function render() {
      var k = colours(o), rem = o.rem || 16;
      el.textContent = '';
      var box = H('div', 'color:' + k.ink + ';font-variant-numeric:tabular-nums' + (o.font ? ';font-family:' + o.font : ''), el);
      H('div', 'font-size:' + f(rem * 0.72) + 'px;opacity:.72', box, d.label);
      H('div', 'font-size:' + f(rem * (o.size || 2.6)) + 'px;font-weight:' + (o.weight || 300) + ';line-height:1.1;letter-spacing:-.01em', box, typeof d.value === 'number' ? (o.format || fmt)(d.value) : d.value);
      if (d.delta != null) {
        var up = d.delta > 0, flat = d.delta === 0, row = H('div', 'font-size:' + f(rem * 0.72) + 'px;display:flex;gap:.4em;align-items:baseline', box);
        H('span', 'font-weight:500', row, (flat ? '→ ' : up ? '▲ ' : '▼ ') + (typeof d.delta === 'number' ? (up ? '+' : '') + (o.format || fmt)(d.delta) + (d.deltaUnit || '') : d.delta));
        if (d.vs) H('span', 'opacity:.7', row, d.vs);
      }
    }
    render();
    return { el: el, update: function (d2, o2) { if (d2) d = d2; if (o2) for (var k in o2) o[k] = o2[k]; render(); return this; }, table: function () { var tb = document.createElement('table'), tr = H('tr', '', H('tbody', '', tb)); H('th', '', tr, d.label); H('td', '', tr, String(d.value)); return tb; }, destroy: function () { el.textContent = ''; } };
  }
  function swatch(kind, o) {
    o = o || {};
    var k = colours(o), s = o.size || 14, svg = S('svg', { width: s, height: s, viewBox: '0 0 ' + s + ' ' + s, 'aria-hidden': 'true' });
    svg.style.flex = 'none';
    if (o.dash !== undefined) S('line', { x1: 0, y1: s / 2, x2: s, y2: s / 2, stroke: k.ink, 'stroke-width': 2, 'stroke-dasharray': o.dash, 'stroke-linecap': 'round' }, svg);
    else S('rect', { x: 0.5, y: 0.5, width: s - 1, height: s - 1, rx: o.round === false ? 3 : s / 2, fill: fills(svg, { ink: k.ink, scale: o.scale || (s < 20 ? 0.62 : 1) })(kind, 'up'), stroke: k.ink, 'stroke-opacity': kind === 'solid' ? 0 : 0.35 }, svg);
    return svg;
  }
  function legend(el, items, o) {
    o = o || {};
    function render() {
      var k = colours(o), rem = o.rem || 16;
      el.textContent = '';
      var list = H('ul', 'list-style:none;margin:0;padding:0;display:flex;flex-direction:' + (o.row ? 'row;flex-wrap:wrap;gap:.4em 1.1em' : 'column;gap:.55em') + ';font-size:' + f(rem * 0.72) + 'px;color:' + k.ink, el);
      fold(items).forEach(function (it, i) {
        var li = H('li', 'display:flex;align-items:center;gap:.55em;min-width:0', list);
        li.appendChild(swatch(it.fill || ORDER[i % ORDER.length], { ink: k.ink, ground: k.ground, dash: o.lines ? (it.dash === undefined ? (DASH[i] || '') : it.dash) : undefined, size: o.lines ? 18 : 14 }));
        H('span', 'opacity:.85', li, it.label);
        if (it.value != null && o.values) H('span', 'margin-left:auto;font-weight:500;font-variant-numeric:tabular-nums', li, (o.format || fmt)(it.value));
      });
    }
    render();
    return { el: el, update: function (i2, o2) { if (i2) items = i2; if (o2) for (var k in o2) o[k] = o2[k]; render(); return this; }, destroy: function () { el.textContent = ''; } };
  }

  return { version: '0.1.0', order: ORDER, dashes: DASH, tones: TONES, fills: fills, swatch: swatch, steps: steps, bars: bars, fan: fan, stack: stack, line: line, spark: spark, halftone: halftone, meter: meter, stat: stat, legend: legend };
});
