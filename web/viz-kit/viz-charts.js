/* asoc viz, the wider set — the rest of what a charting grammar owes its users, in the same one ink and eight
   textures. Load after viz.js; every chart lands on ASOC.viz with the same contract:
     ASOC.viz.<name>(el, data, opts) → { el, update(data, opts), table(), destroy() }

   Comparison   columns(el, {categories, series: [{label, values}]})   opts.mode 'group' | 'stack' | 'percent'
                waterfall(el, [{label, value, total?}])               rises solid, falls hatched, totals dotted
                bullet(el, [{label, value, target, ranges: [a, b, c]}])
                range(el, [{label, lo, hi}])                           a span per row
                radial(el, [{label, value}])                           bars bent round a centre
                funnel(el, [{label, value}])
   Time         area(el, [{label, values}])                            opts.x, opts.mode 'stack' | 'percent' | 'stream'
                candle(el, [{t, o, h, l, c}])                          a rise is hollow, a fall is solid
                calendar(el, [{date: 'yyyy-mm-dd', value}])            weeks as columns, magnitude as dot area
   Distribution histogram(el, number[])                                opts.bins
                box(el, [{label, values}])                             quartiles, whiskers at 1.5 IQR, outliers
                violin(el, [{label, values}])                          a density, mirrored, with its median
   Relationship scatter(el, [{x, y, size?, group?, label?}])           groups are shapes; opts.fit draws a trend line
                radar(el, {axes, series: [{label, values}], max?})
                parallel(el, {dims, rows: [{label, values, group?}]})
   Hierarchy    treemap(el, [{label, value, children?}])
                sunburst(el, [{label, value, children?}])
                pack(el, [{label, value}])                             circles, packed
                tree(el, {label, children})                            a tidy tree, left to right
   Flow         sankey(el, {nodes: [{id, label?}], links: [{a, b, value}]})
                chord(el, {nodes: [{id, label?}], links: [{a, b, value}]})
   Figure       gauge(el, {value, max, label})
   Text         words(el, [{label, value}])                            a word cloud

   Shared opts are viz.js's (tone | ink, ground, accent; rem; font; format; height; on; label). Points become cells
   when ../orrery-kit/cells.js is loaded (opts.marks: 'disc' keeps them round). */
(function (root, factory) {
  var V = root.ASOC && root.ASOC.viz;
  if (!V || !V._) { if (typeof console !== 'undefined') console.error('viz-charts.js: load viz.js first'); return; }
  var api = factory(V, root);
  for (var k in api) V[k] = api[k];
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function (V, root) {
  'use strict';
  var I = V._, S = I.S, f = I.f, est = I.est, clip = I.clip, nice = I.nice, mount = I.mount, ORDER = V.order, DASH = V.dashes, SHADES = V.shades, TAU = Math.PI * 2;
  var SHAPES = ['disc', 'ring', 'square', 'diamond', 'triangle', 'cross'];

  function sum(a, fn) { var t = 0; a.forEach(function (x, i) { t += +(fn ? fn(x, i) : x) || 0; }); return t; }
  function tex(i) { return ORDER[i % ORDER.length]; }
  function fixedH(n) { return function (c, ch) { return ch > 60 ? ch : c.rem * n; }; }
  function pct(v, t) { return (t ? Math.round(v / t * 100) : 0) + '%'; }
  function polar(cx, cy, r, a) { return f(cx + Math.cos(a) * r) + ',' + f(cy + Math.sin(a) * r); }
  function wedge(cx, cy, r0, r1, a0, a1) {
    var big = a1 - a0 > Math.PI ? 1 : 0;
    return 'M' + polar(cx, cy, r1, a0) + 'A' + f(r1) + ',' + f(r1) + ' 0 ' + big + ' 1 ' + polar(cx, cy, r1, a1) + 'L' + polar(cx, cy, r0, a1) + 'A' + f(r0) + ',' + f(r0) + ' 0 ' + big + ' 0 ' + polar(cx, cy, r0, a0) + 'Z';
  }
  /* a name set on a texture needs a plate of ground behind it to stay legible */
  function plate(c, x, y, str, size, parent) {
    var w = est(str, size) + 8;
    S('rect', { x: f(x), y: f(y), width: f(w), height: f(size + 6), rx: 4, fill: c.ground, 'pointer-events': 'none' }, parent || c.svg);
    c.text(x + 4, y + size + 0.5, str, size, { parent: parent });
    return w;
  }
  function baseline(c, x0, x1, y) { S('line', { x1: f(x0), x2: f(x1), y1: f(y), y2: f(y), stroke: c.ink, 'stroke-opacity': 0.28 }, c.svg); }
  function yLabels(c, lo, hi, x, yTop, yBot) {
    c.text(x, yTop - c.rem * 0.4, c.fmt(hi), c.rem * 0.64, { op: 0.6 });
    if (lo !== 0) c.text(x, yBot - c.rem * 0.35, c.fmt(lo), c.rem * 0.64, { op: 0.6 });
  }
  function quant(s, q) { var p = (s.length - 1) * q, b = Math.floor(p); return s[b] + ((s[Math.min(s.length - 1, b + 1)] - s[b]) * (p - b)); }
  /* a point in one of six shapes, or a cell when cells.js is there and the group is the first */
  function shape(c, kind, x, y, r, parent, seed) {
    var a = { fill: c.ink, stroke: c.ground, 'stroke-width': 1.5 }, cells = root.ASOC && root.ASOC.cells, e;
    if (kind === 'disc' && cells && c.o.marks !== 'disc') { e = S('path', { d: cells.d(seed == null ? x + '|' + y : seed, r * 1.12, 0.16), transform: 'translate(' + f(x) + ' ' + f(y) + ')' }, parent); }
    else if (kind === 'ring') { e = S('circle', { cx: f(x), cy: f(y), r: f(r * 0.86) }, parent); a = { fill: c.ground, stroke: c.ink, 'stroke-width': 1.8 }; }
    else if (kind === 'square') e = S('rect', { x: f(x - r * 0.88), y: f(y - r * 0.88), width: f(r * 1.76), height: f(r * 1.76), rx: 1.5 }, parent);
    else if (kind === 'diamond') e = S('path', { d: 'M' + f(x) + ',' + f(y - r * 1.25) + 'L' + f(x + r * 1.25) + ',' + f(y) + 'L' + f(x) + ',' + f(y + r * 1.25) + 'L' + f(x - r * 1.25) + ',' + f(y) + 'Z' }, parent);
    else if (kind === 'triangle') e = S('path', { d: 'M' + f(x) + ',' + f(y - r * 1.2) + 'L' + f(x + r * 1.15) + ',' + f(y + r * 0.85) + 'L' + f(x - r * 1.15) + ',' + f(y + r * 0.85) + 'Z' }, parent);
    else if (kind === 'cross') { e = S('path', { d: 'M' + f(x - r) + ',' + f(y - r) + 'L' + f(x + r) + ',' + f(y + r) + 'M' + f(x + r) + ',' + f(y - r) + 'L' + f(x - r) + ',' + f(y + r) }, parent); a = { fill: 'none', stroke: c.ink, 'stroke-width': 2.2, 'stroke-linecap': 'round' }; }
    else e = S('circle', { cx: f(x), cy: f(y), r: f(r) }, parent);
    for (var k in a) e.setAttribute(k, a[k]);
    return e;
  }

  /* ── columns: several series across categories — side by side, stacked, or as shares ──────────────────────── */
  function columns(el, data, o) {
    return mount(el, data, o, fixedH(13), function (c) {
      var d = c.data, cats = d.categories, ser = d.series.slice(0, ORDER.length), rem = c.rem, mode = c.o.mode || 'group', small = rem * 0.64;
      var mt = rem * 1.3, mb = rem * 1.5, ph = c.h - mt - mb, band = c.w / cats.length, pad = Math.min(band * 0.16, rem * 0.9), hi = 0;
      cats.forEach(function (_, j) { var t = sum(ser, function (s) { return s.values[j]; }); hi = Math.max(hi, mode === 'group' ? Math.max.apply(null, ser.map(function (s) { return +s.values[j] || 0; })) : t); });
      hi = mode === 'percent' ? 100 : nice(hi);
      c.head = [''].concat(ser.map(function (s) { return s.label; }));
      baseline(c, 0, c.w, mt + ph); c.text(0, mt - rem * 0.4, mode === 'percent' ? '100%' : c.fmt(hi), small, { op: 0.6 });
      cats.forEach(function (cat, j) {
        var x0 = j * band + pad, bw = band - pad * 2, tot = sum(ser, function (s) { return s.values[j]; }), acc = 0;
        c.text(j * band + band / 2, c.h - 3, clip(cat, small, band - 4), small, { anchor: 'middle', op: 0.7 });
        c.rows.push([cat].concat(ser.map(function (s) { return c.fmt(+s.values[j] || 0); })));
        ser.forEach(function (s, i) {
          var v = +s.values[j] || 0, x, w, y, hgt, r;
          if (mode === 'group') { w = (bw - 2 * (ser.length - 1)) / ser.length; x = x0 + i * (w + 2); hgt = v / hi * ph; y = mt + ph - hgt; }
          else { var share = mode === 'percent' ? (tot ? v / tot * 100 : 0) : v; w = bw; x = x0; hgt = Math.max(0, share / hi * ph - (i ? 2 : 0)); y = mt + ph - acc / hi * ph - share / hi * ph; acc += share; }
          if (hgt <= 0.5) return;
          r = Math.min(mode === 'group' || i === ser.length - 1 ? rem * 0.4 : 0, w / 2, hgt);
          var bar = S('path', { d: 'M' + f(x) + ',' + f(y + hgt) + 'V' + f(y + r) + 'a' + f(r) + ',' + f(r) + ' 0 0 1 ' + f(r) + ',' + f(-r) + 'h' + f(w - 2 * r) + 'a' + f(r) + ',' + f(r) + ' 0 0 1 ' + f(r) + ',' + f(r) + 'V' + f(y + hgt) + 'Z', fill: c.paint(s.fill || tex(i), 'up') }, c.svg);
          c.mark(bar, s.label + ' · ' + cat, mode === 'percent' ? c.fmt(v) + ' · ' + pct(v, tot) : v, { series: i, category: j, value: v });
        });
      });
    });
  }

  /* ── waterfall: how a figure is built — rises solid, falls hatched, totals dotted ──────────────────────────── */
  function waterfall(el, data, o) {
    return mount(el, data, o, fixedH(13), function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.64, mt = rem * 1.6, mb = rem * 1.5, ph = c.h - mt - mb, band = c.w / d.length, pad = Math.min(band * 0.18, rem), run = 0, lo = 0, hi = 0, spans = [];
      d.forEach(function (it) { var a = it.total ? 0 : run, b = it.total ? (it.value == null ? run : it.value) : run + it.value; if (it.total) run = b; else run += it.value; spans.push([a, b]); lo = Math.min(lo, a, b); hi = Math.max(hi, a, b); });
      hi = nice(hi); if (lo < 0) lo = -nice(-lo);
      function Y(v) { return mt + (1 - (v - lo) / (hi - lo)) * ph; }
      baseline(c, 0, c.w, Y(0)); c.head = ['', 'Change', 'Running'];
      d.forEach(function (it, j) {
        var a = spans[j][0], b = spans[j][1], x = j * band + pad, w = band - pad * 2, y0 = Y(Math.max(a, b)), hgt = Math.max(2, Math.abs(Y(a) - Y(b))), up = b >= a, delta = it.total ? b : it.value;
        var bar = S('rect', { x: f(x), y: f(y0), width: f(w), height: f(hgt), rx: 3, fill: c.paint(it.total ? 'dots' : up ? 'solid' : 'hatch') }, c.svg);
        if (it.total) bar.setAttribute('stroke', c.ink);
        if (j < d.length - 1) S('line', { x1: f(x + w), x2: f(x + band), y1: f(Y(b)), y2: f(Y(b)), stroke: c.ink, 'stroke-opacity': 0.35 }, c.svg);
        c.text(x + w / 2, y0 - rem * 0.35, (it.total || delta < 0 ? '' : '+') + c.fmt(delta), small, { anchor: 'middle', weight: 500 });
        c.text(j * band + band / 2, c.h - 3, clip(it.label, small, band - 4), small, { anchor: 'middle', op: 0.7 });
        c.rows.push([it.label, it.total ? '' : (delta < 0 ? '' : '+') + c.fmt(delta), c.fmt(b)]);
        c.mark(bar, it.label, (it.total ? '' : (delta < 0 ? '' : '+')) + c.fmt(delta) + (it.total ? '' : ' → ' + c.fmt(b)), it);
      });
    });
  }

  /* ── bullet: a measure against its target, over bands of how good that is ─────────────────────────────────── */
  function bullet(el, data, o) {
    return mount(el, data, o, function (c) { return c.data.length * c.rem * 2.9 - c.rem * 0.6; }, function (c) {
      var rem = c.rem, small = rem * 0.72, row = rem * 2.9, th = rem * 1.1;
      c.head = ['', 'Value', 'Target'];
      c.data.forEach(function (it, i) {
        var y = i * row, by = y + small + rem * 0.45, rs = it.ranges || [], hi = Math.max(it.value, it.target || 0, rs.length ? rs[rs.length - 1] : 0) || 1, g = S('g', {}, c.svg), prev = 0;
        function X(v) { return v / hi * c.w; }
        c.text(0, y + small, it.label, small, { op: 0.8, parent: g }); c.text(c.w, y + small, c.fmt(it.value) + (it.target != null ? ' / ' + c.fmt(it.target) : ''), small, { anchor: 'end', weight: 500, parent: g });
        (rs.length ? rs : [hi]).forEach(function (r, k) { S('rect', { x: f(X(prev) + (k ? 2 : 0)), y: f(by), width: f(Math.max(0, X(r) - X(prev) - (k ? 2 : 0))), height: f(th), fill: c.ink, opacity: [0.34, 0.2, 0.1][Math.min(k, 2)] }, g); prev = r; });
        S('rect', { x: 0, y: f(by + th * 0.32), width: f(X(it.value)), height: f(th * 0.36), fill: c.ink }, g);
        if (it.target != null) S('rect', { x: f(X(it.target) - 1.5), y: f(by - 2), width: 3, height: f(th + 4), fill: c.ink }, g);
        c.rows.push([it.label, c.fmt(it.value), it.target == null ? '' : c.fmt(it.target)]);
        c.mark(g, it.label, c.fmt(it.value) + (it.target != null ? ' of ' + c.fmt(it.target) + ' target' : ''), it, S('rect', { x: 0, y: f(y), width: f(c.w), height: f(row), fill: 'transparent' }, g));
      });
    });
  }

  /* ── range: where each thing starts and where it stops ────────────────────────────────────────────────────── */
  function range(el, data, o) {
    return mount(el, data, o, function (c) { return c.data.length * c.rem * 2.5 - c.rem * 0.4; }, function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.72, row = rem * 2.5, lo = Infinity, hi = -Infinity, th = rem * 0.75;
      d.forEach(function (it) { lo = Math.min(lo, it.lo); hi = Math.max(hi, it.hi); });
      if (c.o.zero !== false && lo > 0) lo = 0; if (hi === lo) hi = lo + 1;
      function X(v) { return th / 2 + (v - lo) / (hi - lo) * (c.w - th); }
      c.head = ['', 'From', 'To'];
      d.forEach(function (it, i) {
        var y = i * row, by = y + small + rem * 0.45, g = S('g', {}, c.svg);
        c.text(0, y + small, it.label, small, { op: 0.8, parent: g }); c.text(c.w, y + small, c.fmt(it.lo) + ' – ' + c.fmt(it.hi), small, { anchor: 'end', weight: 500, parent: g });
        S('rect', { x: f(X(it.lo) - th / 2), y: f(by), width: f(X(it.hi) - X(it.lo) + th), height: f(th), rx: f(th / 2), fill: c.paint(it.fill || c.o.fill || 'hatch') }, g);
        S('circle', { cx: f(X(it.lo)), cy: f(by + th / 2), r: f(th / 2), fill: c.ink }, g); S('circle', { cx: f(X(it.hi)), cy: f(by + th / 2), r: f(th / 2), fill: c.ink }, g);
        c.rows.push([it.label, c.fmt(it.lo), c.fmt(it.hi)]);
        c.mark(g, it.label, c.fmt(it.lo) + ' – ' + c.fmt(it.hi), it, S('rect', { x: 0, y: f(y), width: f(c.w), height: f(row), fill: 'transparent' }, g));
      });
    });
  }

  /* ── radial: bars bent round a centre, longest outside ────────────────────────────────────────────────────── */
  function radial(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 60 ? ch : Math.min(c.w, c.rem * 15); }, function (c) {
      var d = c.data.slice(0, ORDER.length), rem = c.rem, small = rem * 0.64, cx = c.w / 2, cy = c.h / 2, R = Math.min(c.w, c.h) / 2 - 1, r0 = R * 0.28, th = (R - r0) / d.length, hi = 0;
      d.forEach(function (it) { hi = Math.max(hi, +it.value || 0); });
      c.head = ['', c.o.unit || 'Value'];
      d.forEach(function (it, i) {
        var ro = R - i * th, ri = ro - th + 3, a1 = -Math.PI / 2 + Math.max(0.02, (+it.value || 0) / (hi || 1) * TAU * 0.75);
        var bar = S('path', { d: wedge(cx, cy, ri, ro, -Math.PI / 2, a1), fill: c.fillOf(it, i) }, c.svg);
        c.text(cx - rem * 0.4, cy - (ri + ro) / 2 + small * 0.36, clip(it.label, small, cx - rem), small, { anchor: 'end', op: 0.8 });
        c.rows.push([it.label, c.fmt(+it.value || 0)]); c.mark(bar, it.label, +it.value || 0, it);
      });
    });
  }

  /* ── funnel: what is left at each stage ───────────────────────────────────────────────────────────────────── */
  function funnel(el, data, o) {
    return mount(el, data, o, function (c) { return c.data.length * c.rem * 2.6; }, function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.72, row = rem * 2.6, first = +d[0].value || 1, side = Math.min(c.w * 0.28, rem * 8), fw = c.w - side * 2, cx = c.w / 2;
      c.head = ['', c.o.unit || 'Value', 'Of first', 'Of previous'];
      d.forEach(function (it, i) {
        var v = +it.value || 0, nx = d[i + 1] ? +d[i + 1].value || 0 : v, w0 = v / first * fw, w1 = nx / first * fw, y = i * row, hgt = row - 2, prev = i ? +d[i - 1].value : v;
        var bar = S('path', { d: 'M' + f(cx - w0 / 2) + ',' + f(y) + 'H' + f(cx + w0 / 2) + 'L' + f(cx + w1 / 2) + ',' + f(y + hgt) + 'H' + f(cx - w1 / 2) + 'Z', fill: c.paint(it.fill || c.o.fill || 'solid') }, c.svg);
        c.text(0, y + hgt / 2 + small * 0.36, clip(it.label, small, side - 6), small, { op: 0.8 });
        c.text(c.w, y + hgt / 2 + small * 0.36, c.fmt(v) + (i ? ' · ' + pct(v, prev) : ''), small, { anchor: 'end', weight: 500 });
        c.rows.push([it.label, c.fmt(v), pct(v, first), i ? pct(v, prev) : '']);
        c.mark(bar, it.label, c.fmt(v) + ' · ' + pct(v, first) + ' of first', it);
      });
    });
  }

  /* ── area: series stacked on one axis, each band a texture ────────────────────────────────────────────────── */
  function area(el, data, o) {
    return mount(el, data, o, fixedH(13), function (c) {
      var ser = c.data.slice(0, ORDER.length), rem = c.rem, small = rem * 0.64, xs = c.o.x || [], mode = c.o.mode || 'stack', n = 0, hi = 0, mt = rem * 1.3, mb = xs.length ? rem * 1.5 : 4, ph = c.h - mt - mb;
      ser.forEach(function (s) { n = Math.max(n, s.values.length); });
      var tot = []; for (var j = 0; j < n; j++) { tot.push(sum(ser, function (s) { return s.values[j]; })); hi = Math.max(hi, tot[j]); }
      hi = mode === 'percent' ? 1 : mode === 'stream' ? hi : nice(hi);
      function X(i) { return n > 1 ? i / (n - 1) * c.w : c.w / 2; }
      function Y(v) { return mt + (1 - v / hi) * ph; }
      var base = tot.map(function (t) { return mode === 'stream' ? (hi - t) / 2 : 0; });
      if (mode !== 'stream') { baseline(c, 0, c.w, Y(0)); c.text(0, mt - rem * 0.4, mode === 'percent' ? '100%' : c.fmt(hi), small, { op: 0.6 }); }
      if (xs.length) { c.text(0, c.h - 3, xs[0], small, { op: 0.6 }); c.text(c.w, c.h - 3, xs[n - 1], small, { op: 0.6, anchor: 'end' }); }
      c.head = [c.o.xTitle || ''].concat(ser.map(function (s) { return s.label; }));
      for (j = 0; j < n; j++) c.rows.push([xs[j] == null ? String(j + 1) : xs[j]].concat(ser.map(function (s) { return c.fmt(+s.values[j] || 0); })));
      var tops = [];
      ser.forEach(function (s, i) {
        var lower = base.slice(), upper = base.map(function (b, k) { var v = +s.values[k] || 0; return b + (mode === 'percent' ? (tot[k] ? v / tot[k] : 0) : v); });
        var up = upper.map(function (v, k) { return [X(k), Y(v)]; }), dn = lower.map(function (v, k) { return [X(k), Y(v)]; }).reverse();
        var top = I.curve(up), bot = I.curve(dn);
        S('path', { d: top + 'L' + bot.slice(1) + 'Z', fill: c.paint(s.fill || tex(i), 'up'), stroke: c.ground, 'stroke-width': 2, 'stroke-linejoin': 'round', 'pointer-events': 'none' }, c.svg);
        tops.push(upper); base = upper;
      });
      /* one readout for every series at the x under the pointer */
      var cur = S('line', { y1: f(mt), y2: f(mt + ph), stroke: c.ink, 'stroke-width': 1.5, visibility: 'hidden', 'pointer-events': 'none' }, c.svg), at = n - 1;
      var over = S('rect', { x: 0, y: 0, width: f(c.w), height: f(c.h), fill: 'transparent', tabindex: 0, role: 'img', 'aria-label': 'Readout; arrow keys step through the points' }, c.svg);
      over.style.outlineOffset = '2px';
      function show(i) {
        at = Math.max(0, Math.min(n - 1, i)); cur.setAttribute('x1', f(X(at))); cur.setAttribute('x2', f(X(at))); cur.setAttribute('visibility', 'visible');
        c.tip.show(X(at), Y(tops[tops.length - 1][at]) - 4, ser.map(function (s) { var v = +s.values[at] || 0; return { v: c.fmt(v) + (mode === 'percent' ? ' · ' + pct(v, tot[at]) : ''), l: s.label }; }).reverse(), xs[at] == null ? null : String(xs[at]));
      }
      function hide() { cur.setAttribute('visibility', 'hidden'); c.tip.hide(); }
      function move(ev) { var b = c.svg.getBoundingClientRect(); show(Math.round((ev.clientX - b.left) / (c.w || 1) * (n - 1))); }
      over.addEventListener('pointermove', move); over.addEventListener('pointerdown', move); over.addEventListener('pointerleave', hide); over.addEventListener('focus', function () { show(at); }); over.addEventListener('blur', hide);
      over.addEventListener('keydown', function (ev) { if (ev.key === 'ArrowLeft') show(at - 1); else if (ev.key === 'ArrowRight') show(at + 1); else return; ev.preventDefault(); });
    });
  }

  /* ── candle: open, high, low, close — a rise is hollow, a fall is solid ───────────────────────────────────── */
  function candle(el, data, o) {
    return mount(el, data, o, fixedH(13), function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.64, lo = Infinity, hi = -Infinity, mt = rem * 1.3, mb = rem * 1.5, ph = c.h - mt - mb, band = c.w / d.length, bw = Math.max(2, Math.min(band * 0.62, rem));
      d.forEach(function (k) { lo = Math.min(lo, k.l); hi = Math.max(hi, k.h); });
      var padv = (hi - lo) * 0.06 || 1; lo -= padv; hi += padv;
      function Y(v) { return mt + (1 - (v - lo) / (hi - lo)) * ph; }
      baseline(c, 0, c.w, mt + ph); c.text(0, mt - rem * 0.4, c.fmt(hi), small, { op: 0.6 }); c.text(0, mt + ph - rem * 0.35, c.fmt(lo), small, { op: 0.6 });
      c.text(0, c.h - 3, d[0].t, small, { op: 0.6 }); c.text(c.w, c.h - 3, d[d.length - 1].t, small, { op: 0.6, anchor: 'end' });
      c.head = ['', 'Open', 'High', 'Low', 'Close'];
      d.forEach(function (k, i) {
        var x = (i + 0.5) * band, up = k.c >= k.o, g = S('g', {}, c.svg), y0 = Y(Math.max(k.o, k.c)), hgt = Math.max(1.5, Math.abs(Y(k.o) - Y(k.c)));
        var hit = S('rect', { x: f(i * band), y: 0, width: f(band), height: f(c.h), fill: 'transparent' }, g);
        S('line', { x1: f(x), x2: f(x), y1: f(Y(k.h)), y2: f(Y(k.l)), stroke: c.ink, 'stroke-width': 1.4 }, g);
        S('rect', { x: f(x - bw / 2), y: f(y0), width: f(bw), height: f(hgt), rx: 1.5, fill: up ? c.ground : c.ink, stroke: c.ink, 'stroke-width': 1.4 }, g);
        c.rows.push([k.t, c.fmt(k.o), c.fmt(k.h), c.fmt(k.l), c.fmt(k.c)]);
        c.mark(g, k.t + (up ? ' ▲' : ' ▼'), 'O ' + c.fmt(k.o) + '  H ' + c.fmt(k.h) + '  L ' + c.fmt(k.l) + '  C ' + c.fmt(k.c), k, hit);
      });
    });
  }

  /* ── calendar: weeks as columns, a day's magnitude as the area of its dot ─────────────────────────────────── */
  function calendar(el, data, o) {
    function geo(c) {
      var days = c.data.map(function (d) { var p = d.date.split('-'); return { t: new Date(+p[0], +p[1] - 1, +p[2]), v: +d.value || 0, date: d.date }; }).sort(function (a, b) { return a.t - b.t; });
      var start = new Date(days[0].t); start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
      var weeks = Math.ceil(((days[days.length - 1].t - start) / 864e5 + 1) / 7), lab = c.rem * 1.6;
      return { days: days, start: start, weeks: weeks, lab: lab, cell: Math.min((c.w - lab) / weeks, c.o.cell || c.rem * 1.15), top: c.rem * 1.2 };
    }
    return mount(el, data, o, function (c) { var g = geo(c); return g.top + g.cell * 7; }, function (c) {
      var g = geo(c), small = c.rem * 0.6, max = 0, MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'], seen = {};
      g.days.forEach(function (d) { max = Math.max(max, d.v); });
      ['M', '', 'W', '', 'F', '', 'S'].forEach(function (n, i) { if (n) c.text(0, g.top + (i + 0.5) * g.cell + small * 0.36, n, small, { op: 0.6 }); });
      c.head = ['', c.o.unit || 'Value'];
      g.days.forEach(function (d) {
        var n = Math.round((d.t - g.start) / 864e5), wk = Math.floor(n / 7), dow = n % 7, x = g.lab + (wk + 0.5) * g.cell, y = g.top + (dow + 0.5) * g.cell, key = d.t.getFullYear() + '-' + d.t.getMonth();
        if (!seen[key] && d.t.getDate() <= 7) { seen[key] = 1; c.text(g.lab + wk * g.cell, small, MON[d.t.getMonth()], small, { op: 0.6 }); }
        var grp = S('g', {}, c.svg), hit = S('rect', { x: f(x - g.cell / 2), y: f(y - g.cell / 2), width: f(g.cell), height: f(g.cell), fill: 'transparent' }, grp);
        S('circle', { cx: f(x), cy: f(y), r: f(d.v > 0 ? Math.max(0.9, Math.sqrt(d.v / (max || 1)) * g.cell * 0.46) : 0.7), fill: c.ink, opacity: d.v > 0 ? 1 : 0.25 }, grp);
        c.rows.push([d.date, c.fmt(d.v)]); c.mark(grp, d.t.getDate() + ' ' + MON[d.t.getMonth()], d.v, d, hit);
      });
    });
  }

  /* ── histogram: how many fall in each bin ─────────────────────────────────────────────────────────────────── */
  function histogram(el, data, o) {
    return mount(el, data, o, fixedH(12), function (c) {
      var v = c.data.slice().sort(function (a, b) { return a - b; }), rem = c.rem, small = rem * 0.64, want = c.o.bins || Math.max(5, Math.min(24, Math.ceil(Math.sqrt(v.length))));
      var step = nice((v[v.length - 1] - v[0]) / want) || 1, lo = Math.floor(v[0] / step) * step, bins = [], hi = 0, mt = rem * 1.3, mb = rem * 1.5, ph = c.h - mt - mb;
      for (var b = lo; b <= v[v.length - 1]; b += step) bins.push({ a: b, b: b + step, n: 0 });
      v.forEach(function (x) { bins[Math.min(bins.length - 1, Math.floor((x - lo) / step))].n++; });
      bins.forEach(function (bn) { hi = Math.max(hi, bn.n); }); hi = nice(hi);
      var bw = c.w / bins.length;
      baseline(c, 0, c.w, mt + ph); c.text(0, mt - rem * 0.4, c.fmt(hi), small, { op: 0.6 });
      c.text(0, c.h - 3, c.fmt(lo), small, { op: 0.6 }); c.text(c.w, c.h - 3, c.fmt(bins[bins.length - 1].b), small, { op: 0.6, anchor: 'end' });
      c.head = ['Bin', 'Count'];
      bins.forEach(function (bn, i) {
        var hgt = bn.n / hi * ph, r = Math.min(4, hgt), x = i * bw + 1, w = bw - 2, y = mt + ph - hgt, name = c.fmt(bn.a) + ' – ' + c.fmt(bn.b), g = S('g', {}, c.svg);
        var hit = S('rect', { x: f(i * bw), y: 0, width: f(bw), height: f(c.h), fill: 'transparent' }, g);
        if (hgt > 0) S('path', { d: 'M' + f(x) + ',' + f(mt + ph) + 'V' + f(y + r) + 'a' + r + ',' + r + ' 0 0 1 ' + r + ',' + (-r) + 'h' + f(w - 2 * r) + 'a' + r + ',' + r + ' 0 0 1 ' + r + ',' + r + 'V' + f(mt + ph) + 'Z', fill: c.paint(c.o.fill || 'solid', 'up') }, g);
        c.rows.push([name, String(bn.n)]); c.mark(g, name, bn.n, bn, hit);
      });
    });
  }

  /* ── box and violin: the shape of a spread, a row per group ───────────────────────────────────────────────── */
  function spread(el, data, o, asViolin) {
    return mount(el, data, o, function (c) { return c.data.length * c.rem * (asViolin ? 3.4 : 2.7) + c.rem * 1.2; }, function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.72, tiny = rem * 0.64, row = rem * (asViolin ? 3.4 : 2.7), lo = Infinity, hi = -Infinity, lab = 0;
      d.forEach(function (g) { lab = Math.max(lab, est(g.label, small)); g.values.forEach(function (x) { lo = Math.min(lo, x); hi = Math.max(hi, x); }); });
      lab += rem * 0.8; if (hi === lo) hi = lo + 1;
      function X(x) { return lab + 4 + (x - lo) / (hi - lo) * (c.w - lab - 8); }
      c.text(lab + 4, c.h - 3, c.fmt(lo), tiny, { op: 0.6 }); c.text(c.w - 4, c.h - 3, c.fmt(hi), tiny, { op: 0.6, anchor: 'end' });
      c.head = ['', 'Min', 'Q1', 'Median', 'Q3', 'Max'];
      d.forEach(function (g, i) {
        var s = g.values.slice().sort(function (a, b) { return a - b; }), q1 = quant(s, 0.25), md = quant(s, 0.5), q3 = quant(s, 0.75), iqr = q3 - q1;
        var wl = s.filter(function (x) { return x >= q1 - 1.5 * iqr; })[0], wh = s.filter(function (x) { return x <= q3 + 1.5 * iqr; }).pop(), y = i * row + row / 2, grp = S('g', {}, c.svg), th = rem * 1.2;
        var hit = S('rect', { x: 0, y: f(i * row), width: f(c.w), height: f(row), fill: 'transparent' }, grp);
        c.text(0, y + small * 0.36, g.label, small, { op: 0.8, parent: grp });
        if (asViolin) {
          var bwd = (iqr || (hi - lo) / 10) * 0.7, N = 40, pts = [], peak = 0, k;
          for (k = 0; k <= N; k++) { var x = s[0] + (s[s.length - 1] - s[0]) * k / N, dn = 0; s.forEach(function (q) { var u = (x - q) / bwd; dn += Math.exp(-0.5 * u * u); }); pts.push([x, dn]); peak = Math.max(peak, dn); }
          var amp = row * 0.42, up = pts.map(function (p) { return [X(p[0]), y - p[1] / peak * amp]; }), dnp = pts.map(function (p) { return [X(p[0]), y + p[1] / peak * amp]; }).reverse();
          S('path', { d: I.curve(up) + 'L' + I.curve(dnp).slice(1) + 'Z', fill: c.paint(g.fill || c.o.fill || 'stipple'), stroke: c.ink, 'stroke-width': 1.4 }, grp);
          S('line', { x1: f(X(q1)), x2: f(X(q3)), y1: f(y), y2: f(y), stroke: c.ink, 'stroke-width': 4, 'stroke-linecap': 'round' }, grp);
          S('circle', { cx: f(X(md)), cy: f(y), r: 3.4, fill: c.ground, stroke: c.ink, 'stroke-width': 1.6 }, grp);
        } else {
          S('line', { x1: f(X(wl)), x2: f(X(wh)), y1: f(y), y2: f(y), stroke: c.ink, 'stroke-width': 1.4 }, grp);
          [wl, wh].forEach(function (x) { S('line', { x1: f(X(x)), x2: f(X(x)), y1: f(y - th * 0.3), y2: f(y + th * 0.3), stroke: c.ink, 'stroke-width': 1.4 }, grp); });
          S('rect', { x: f(X(q1)), y: f(y - th / 2), width: f(Math.max(2, X(q3) - X(q1))), height: f(th), rx: 3, fill: c.ground }, grp);
          S('rect', { x: f(X(q1)), y: f(y - th / 2), width: f(Math.max(2, X(q3) - X(q1))), height: f(th), rx: 3, fill: c.paint(g.fill || c.o.fill || 'hatch'), stroke: c.ink, 'stroke-width': 1.4 }, grp);
          S('rect', { x: f(X(md) - 2), y: f(y - th / 2 - 2), width: 4, height: f(th + 4), rx: 1, fill: c.ink, stroke: c.ground, 'stroke-width': 1.5 }, grp);
          s.forEach(function (x) { if (x < wl || x > wh) S('circle', { cx: f(X(x)), cy: f(y), r: 2.4, fill: c.ground, stroke: c.ink, 'stroke-width': 1.3 }, grp); });
        }
        c.rows.push([g.label, c.fmt(s[0]), c.fmt(q1), c.fmt(md), c.fmt(q3), c.fmt(s[s.length - 1])]);
        c.mark(grp, g.label, 'median ' + c.fmt(md) + ' · ' + c.fmt(q1) + ' – ' + c.fmt(q3), g, hit);
      });
    });
  }
  function box(el, data, o) { return spread(el, data, o, false); }
  function violin(el, data, o) { return spread(el, data, o, true); }

  /* ── scatter: two measures; a third as size; groups as shapes ─────────────────────────────────────────────── */
  function scatter(el, data, o) {
    return mount(el, data, o, fixedH(14), function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.64, xl = Infinity, xh = -Infinity, yl = Infinity, yh = -Infinity, sm = 0, groups = [], mt = rem * 1.3, mb = rem * 1.5, ml = 0, mr = rem * 0.6;
      d.forEach(function (p) { xl = Math.min(xl, p.x); xh = Math.max(xh, p.x); yl = Math.min(yl, p.y); yh = Math.max(yh, p.y); sm = Math.max(sm, +p.size || 0); if (p.group != null && groups.indexOf(p.group) < 0) groups.push(p.group); });
      if (c.o.zero !== false) { xl = Math.min(0, xl); yl = Math.min(0, yl); xh = nice(xh); yh = nice(yh); }
      if (xh === xl) xh = xl + 1; if (yh === yl) yh = yl + 1;
      var pw = c.w - ml - mr, ph = c.h - mt - mb;
      function X(v) { return ml + (v - xl) / (xh - xl) * pw; } function Y(v) { return mt + (1 - (v - yl) / (yh - yl)) * ph; }
      baseline(c, ml, ml + pw, mt + ph); S('line', { x1: f(ml), x2: f(ml), y1: f(mt), y2: f(mt + ph), stroke: c.ink, 'stroke-opacity': 0.28 }, c.svg);
      c.text(ml + 4, mt - rem * 0.4, c.fmt(yh) + (c.o.yTitle ? ' ' + c.o.yTitle : ''), small, { op: 0.6 });
      c.text(ml, c.h - 3, c.fmt(xl), small, { op: 0.6 }); c.text(ml + pw, c.h - 3, c.fmt(xh) + (c.o.xTitle ? ' ' + c.o.xTitle : ''), small, { op: 0.6, anchor: 'end' });
      if (c.o.fit && d.length > 2) {
        var n = d.length, sx = sum(d, function (p) { return p.x; }), sy = sum(d, function (p) { return p.y; }), sxy = sum(d, function (p) { return p.x * p.y; }), sxx = sum(d, function (p) { return p.x * p.x; }), m = (n * sxy - sx * sy) / ((n * sxx - sx * sx) || 1), b0 = (sy - m * sx) / n;
        S('line', { x1: f(X(xl)), y1: f(Y(m * xl + b0)), x2: f(X(xh)), y2: f(Y(m * xh + b0)), stroke: c.ink, 'stroke-width': 1.5, 'stroke-dasharray': '6 5', 'clip-path': 'none' }, c.svg);
      }
      c.head = [c.o.labelTitle || '', c.o.xTitle || 'x', c.o.yTitle || 'y'].concat(sm ? [c.o.sizeTitle || 'Size'] : []);
      d.slice().sort(function (a, b) { return (+b.size || 0) - (+a.size || 0); }).forEach(function (p, i) {
        var r = sm ? Math.max(3, Math.sqrt((+p.size || 0) / sm) * rem * 1.5) : rem * 0.3, kind = SHAPES[Math.max(0, groups.indexOf(p.group)) % SHAPES.length], g = S('g', {}, c.svg), e;
        S('circle', { cx: f(X(p.x)), cy: f(Y(p.y)), r: f(Math.max(12, r + 3)), fill: 'transparent' }, g);
        if (sm) e = S('circle', { cx: f(X(p.x)), cy: f(Y(p.y)), r: f(r), fill: c.paint(p.fill || (groups.length ? tex(groups.indexOf(p.group)) : 'stipple')), stroke: c.ink, 'stroke-width': 1.4 }, g);
        else e = shape(c, kind, X(p.x), Y(p.y), r, g, p.label || i);
        c.rows.push([p.label || String(i + 1), c.fmt(p.x), c.fmt(p.y)].concat(sm ? [c.fmt(+p.size || 0)] : []));
        c.mark(g, (p.label || '') + (p.group != null ? (p.label ? ' · ' : '') + p.group : ''), c.fmt(p.x) + ', ' + c.fmt(p.y) + (sm ? ' · ' + c.fmt(+p.size || 0) : ''), p);
      });
    });
  }

  /* ── radar: several measures on spokes from one centre ────────────────────────────────────────────────────── */
  function radar(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 60 ? ch : Math.min(c.w, c.rem * 16); }, function (c) {
      var d = c.data, n = d.axes.length, rem = c.rem, small = rem * 0.64, cx = c.w / 2, cy = c.h / 2, R = Math.min(c.w / 2 - rem * 3.2, c.h / 2 - rem * 1.3), hi = d.max || 0;
      if (!hi) { d.series.forEach(function (s) { s.values.forEach(function (v) { hi = Math.max(hi, v); }); }); hi = nice(hi); }
      function A(i) { return i / n * TAU - Math.PI / 2; }
      [1 / 3, 2 / 3, 1].forEach(function (t) { S('path', { d: 'M' + d.axes.map(function (_, i) { return polar(cx, cy, R * t, A(i)); }).join('L') + 'Z', fill: 'none', stroke: c.ink, 'stroke-opacity': t === 1 ? 0.3 : 0.14 }, c.svg); });
      d.axes.forEach(function (name, i) {
        var a = A(i), x = cx + Math.cos(a) * (R + rem * 0.5), y = cy + Math.sin(a) * (R + rem * 0.5), co = Math.cos(a);
        c.text(x, y + small * (Math.sin(a) > 0.3 ? 0.9 : Math.sin(a) < -0.3 ? 0 : 0.36), name, small, { anchor: co > 0.3 ? 'start' : co < -0.3 ? 'end' : 'middle', op: 0.75 });
      });
      c.head = [''].concat(d.series.map(function (s) { return s.label; }));
      d.axes.forEach(function (name, i) { c.rows.push([name].concat(d.series.map(function (s) { return c.fmt(s.values[i]); }))); });
      d.series.slice(0, DASH.length).forEach(function (s, si) {
        var pts = s.values.map(function (v, i) { return [cx + Math.cos(A(i)) * R * v / hi, cy + Math.sin(A(i)) * R * v / hi]; });
        S('path', { d: 'M' + pts.map(function (p) { return f(p[0]) + ',' + f(p[1]); }).join('L') + 'Z', fill: si === 0 ? c.paint(s.fill || 'hatch') : 'none', 'fill-opacity': 0.55, stroke: c.ink, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-dasharray': DASH[si], 'pointer-events': 'none' }, c.svg);
        pts.forEach(function (p, i) { var g = S('g', {}, c.svg); S('circle', { cx: f(p[0]), cy: f(p[1]), r: 12, fill: 'transparent' }, g); S('circle', { cx: f(p[0]), cy: f(p[1]), r: 3.6, fill: c.ink, stroke: c.ground, 'stroke-width': 1.5 }, g); c.mark(g, s.label + ' · ' + d.axes[i], s.values[i], { series: si, axis: i }); });
      });
    });
  }

  /* ── parallel: many measures at once, a line per row ──────────────────────────────────────────────────────── */
  function parallel(el, data, o) {
    return mount(el, data, o, fixedH(14), function (c) {
      var d = c.data, n = d.dims.length, rem = c.rem, small = rem * 0.64, mt = rem * 2.2, mb = rem * 1.3, ph = c.h - mt - mb, pad = rem * 1.2, groups = [], ext = d.dims.map(function (_, i) { var lo = Infinity, hi = -Infinity; d.rows.forEach(function (r) { lo = Math.min(lo, r.values[i]); hi = Math.max(hi, r.values[i]); }); return [lo, hi === lo ? lo + 1 : hi]; });
      d.rows.forEach(function (r) { if (r.group != null && groups.indexOf(r.group) < 0) groups.push(r.group); });
      function X(i) { return pad + i / (n - 1) * (c.w - pad * 2); } function Y(i, v) { return mt + (1 - (v - ext[i][0]) / (ext[i][1] - ext[i][0])) * ph; }
      d.dims.forEach(function (name, i) {
        S('line', { x1: f(X(i)), x2: f(X(i)), y1: f(mt), y2: f(mt + ph), stroke: c.ink, 'stroke-opacity': 0.3 }, c.svg);
        var anchor = i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle', x = i === 0 ? 0 : i === n - 1 ? c.w : X(i);
        c.text(x, small, name, small, { anchor: anchor, weight: 500 }); c.text(x, mt - rem * 0.35, c.fmt(ext[i][1]), small, { anchor: anchor, op: 0.6 }); c.text(x, c.h - 2, c.fmt(ext[i][0]), small, { anchor: anchor, op: 0.6 });
      });
      c.head = [''].concat(d.dims);
      d.rows.forEach(function (r, k) {
        var path = 'M' + r.values.map(function (v, i) { return f(X(i)) + ',' + f(Y(i, v)); }).join('L'), g = S('g', {}, c.svg);
        S('path', { d: path, fill: 'none', stroke: 'transparent', 'stroke-width': 12 }, g);
        S('path', { d: path, fill: 'none', stroke: c.ink, 'stroke-width': 1.6, 'stroke-opacity': 0.55, 'stroke-linejoin': 'round', 'stroke-dasharray': DASH[Math.max(0, groups.indexOf(r.group)) % DASH.length] }, g);
        c.rows.push([r.label || String(k + 1)].concat(r.values.map(function (v) { return c.fmt(v); })));
        c.mark(g, r.label || String(k + 1), r.values.map(function (v, i) { return d.dims[i] + ' ' + c.fmt(v); }).join(' · '), r);
      });
    });
  }

  /* ── treemap: parts of a whole as area, parts of parts inside them ────────────────────────────────────────── */
  function squarify(items, x, y, w, h) {
    var total = sum(items, function (it) { return it.v; }), out = [], rest = items.slice(), row = [], scale = total ? w * h / total : 0;
    function worst(r, side) { var s = sum(r, function (q) { return q.a; }), mx = 0, mn = Infinity; r.forEach(function (q) { mx = Math.max(mx, q.a); mn = Math.min(mn, q.a); }); return Math.max(side * side * mx / (s * s), s * s / (side * side * mn)); }
    function flush() {
      var s = sum(row, function (q) { return q.a; });
      if (w >= h) { var cw = s / h, yy = y; row.forEach(function (q) { out.push({ it: q.it, x: x, y: yy, w: cw, h: q.a / cw }); yy += q.a / cw; }); x += cw; w -= cw; }
      else { var ch = s / w, xx = x; row.forEach(function (q) { out.push({ it: q.it, x: xx, y: y, w: q.a / ch, h: ch }); xx += q.a / ch; }); y += ch; h -= ch; }
      row = [];
    }
    while (rest.length) { var q = { it: rest[0], a: rest[0].v * scale }, side = Math.min(w, h); if (!row.length || worst(row.concat([q]), side) <= worst(row, side)) { row.push(q); rest.shift(); } else flush(); }
    if (row.length) flush();
    return out;
  }
  function weigh(nodes) { return nodes.map(function (n) { var kids = n.children ? weigh(n.children) : null; return { label: n.label, v: kids ? sum(kids, function (k) { return k.v; }) : +n.value || 0, kids: kids, src: n, fill: n.fill }; }).filter(function (n) { return n.v > 0; }).sort(function (a, b) { return b.v - a.v; }); }
  function treemap(el, data, o) {
    return mount(el, data, o, fixedH(15), function (c) {
      var tops = weigh(c.data), rem = c.rem, small = rem * 0.64, total = sum(tops, function (t) { return t.v; });
      c.head = ['', c.o.unit || 'Value', 'Share'];
      squarify(tops, 0, 0, c.w, c.h).forEach(function (t, i) {
        var fill = c.paint(t.it.fill || tex(Math.min(i, ORDER.length - 1)), 'up'), leaves = t.it.kids ? squarify(t.it.kids, t.x, t.y, t.w, t.h) : [t];
        leaves.forEach(function (lf) {
          var r = S('rect', { x: f(lf.x + 1), y: f(lf.y + 1), width: f(Math.max(0, lf.w - 2)), height: f(Math.max(0, lf.h - 2)), rx: 5, fill: fill }, c.svg), name = (t.it.kids ? t.it.label + ' · ' : '') + lf.it.label;
          c.rows.push([name, c.fmt(lf.it.v), pct(lf.it.v, total)]); c.mark(r, name, c.fmt(lf.it.v) + ' · ' + pct(lf.it.v, total), lf.it.src);
          if (t.it.kids && lf.w > est(lf.it.label, small) + 18 && lf.h > small + 34) plate(c, lf.x + 6, lf.y + lf.h - small - 12, lf.it.label, small);
        });
        if (t.w > est(t.it.label, small) + 18 && t.h > small + 14) plate(c, t.x + 6, t.y + 6, t.it.label, small).toString();
      });
    });
  }

  /* ── sunburst: the same hierarchy as rings ────────────────────────────────────────────────────────────────── */
  function sunburst(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 60 ? ch : Math.min(c.w, c.rem * 16); }, function (c) {
      var tops = weigh(c.data), rem = c.rem, cx = c.w / 2, cy = c.h / 2, R = Math.min(c.w, c.h) / 2 - 1, total = sum(tops, function (t) { return t.v; }), deep = tops.some(function (t) { return t.kids; }), r0 = R * 0.34, r1 = deep ? R * 0.68 : R, a = -Math.PI / 2;
      c.head = ['', c.o.unit || 'Value', 'Share'];
      tops.forEach(function (t, i) {
        var a1 = a + t.v / total * TAU, fill = c.paint(t.fill || tex(Math.min(i, ORDER.length - 1))), b = a;
        var w = S('path', { d: wedge(cx, cy, r0, r1, a, a1 - 0.0001), fill: fill, stroke: c.ground, 'stroke-width': 2, 'stroke-linejoin': 'round' }, c.svg);
        c.rows.push([t.label, c.fmt(t.v), pct(t.v, total)]); c.mark(w, t.label, c.fmt(t.v) + ' · ' + pct(t.v, total), t.src);
        (t.kids || []).forEach(function (k) {
          var b1 = b + k.v / total * TAU, kw = S('path', { d: wedge(cx, cy, r1, R, b, b1 - 0.0001), fill: fill, 'fill-opacity': 0.6, stroke: c.ground, 'stroke-width': 2, 'stroke-linejoin': 'round' }, c.svg);
          c.rows.push([t.label + ' · ' + k.label, c.fmt(k.v), pct(k.v, total)]); c.mark(kw, t.label + ' · ' + k.label, c.fmt(k.v) + ' · ' + pct(k.v, total), k.src); b = b1;
        });
        a = a1;
      });
      if (c.o.callout !== false) c.text(cx, cy + rem * 0.4, c.o.callout || c.fmt(total), Math.min(rem * 1.4, r0 * 0.6), { anchor: 'middle', weight: c.weight });
    });
  }

  /* ── pack: values as circles, drawn in as close as they will go ───────────────────────────────────────────── */
  function pack(el, data, o) {
    return mount(el, data, o, fixedH(15), function (c) {
      var items = c.data.filter(function (d) { return +d.value > 0; }).sort(function (a, b) { return b.value - a.value; }).slice(0, 60), placed = [], rem = c.rem, small = rem * 0.64, total = sum(items, function (d) { return d.value; });
      function free(x, y, r) { for (var i = 0; i < placed.length; i++) { var p = placed[i], dx = p.x - x, dy = p.y - y, rr = p.r + r - 0.001; if (dx * dx + dy * dy < rr * rr) return false; } return true; }
      items.forEach(function (it, n) {
        var r = Math.sqrt(it.value), best = null, bd = Infinity, i, j;
        if (!n) best = [0, 0]; else if (n === 1) best = [placed[0].r + r, 0];
        else for (i = 0; i < placed.length; i++) for (j = i + 1; j < placed.length; j++) {
          var A = placed[i], B = placed[j], d1 = A.r + r, d2 = B.r + r, dx = B.x - A.x, dy = B.y - A.y, d = Math.sqrt(dx * dx + dy * dy);
          if (d > d1 + d2 || d === 0) continue;
          var a = (d1 * d1 - d2 * d2 + d * d) / (2 * d), hh = Math.sqrt(Math.max(0, d1 * d1 - a * a)), bx = A.x + a * dx / d, by = A.y + a * dy / d;
          [[bx - hh * dy / d, by + hh * dx / d], [bx + hh * dy / d, by - hh * dx / d]].forEach(function (p) { var dist = p[0] * p[0] * (c.h / c.w) + p[1] * p[1] * (c.w / c.h); if (dist < bd && free(p[0], p[1], r)) { bd = dist; best = p; } });
        }
        if (best) placed.push({ x: best[0], y: best[1], r: r, it: it });
      });
      var x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
      placed.forEach(function (p) { x0 = Math.min(x0, p.x - p.r); x1 = Math.max(x1, p.x + p.r); y0 = Math.min(y0, p.y - p.r); y1 = Math.max(y1, p.y + p.r); });
      var k = Math.min(c.w / (x1 - x0), c.h / (y1 - y0)), ox = (c.w - (x1 - x0) * k) / 2 - x0 * k, oy = (c.h - (y1 - y0) * k) / 2 - y0 * k;
      c.head = ['', c.o.unit || 'Value', 'Share'];
      placed.forEach(function (p, i) {
        var x = ox + p.x * k, y = oy + p.y * k, r = Math.max(1, p.r * k - 1.5), g = S('g', {}, c.svg);
        S('circle', { cx: f(x), cy: f(y), r: f(r), fill: c.paint(p.it.fill || c.o.fill || tex(Math.min(i, ORDER.length - 1)), 'up'), stroke: c.ink, 'stroke-width': 1.2 }, g);
        if (r * 1.7 > est(p.it.label, small) + 8) plate(c, x - est(p.it.label, small) / 2 - 4, y - small / 2 - 3, p.it.label, small, g);
        c.rows.push([p.it.label, c.fmt(p.it.value), pct(p.it.value, total)]); c.mark(g, p.it.label, c.fmt(p.it.value) + ' · ' + pct(p.it.value, total), p.it);
      });
    });
  }

  /* ── tree: a hierarchy as a tidy tree, left to right ──────────────────────────────────────────────────────── */
  function tree(el, data, o) {
    function flat(c) { var leaves = 0, depth = 0, all = []; (function walk(n, d, parent) { var me = { n: n, d: d, parent: parent, kids: [] }; all.push(me); depth = Math.max(depth, d); if (parent) parent.kids.push(me); (n.children || []).forEach(function (k) { walk(k, d + 1, me); }); if (!me.kids.length) me.y = leaves++; else me.y = sum(me.kids, function (k) { return k.y; }) / me.kids.length; })(c.data, 0, null); return { all: all, leaves: leaves, depth: depth }; }
    return mount(el, data, o, function (c) { return Math.max(c.rem * 8, flat(c).leaves * c.rem * 1.5); }, function (c) {
      var t = flat(c), rem = c.rem, small = rem * 0.68, lastW = 0;
      t.all.forEach(function (m) { if (m.d === t.depth) lastW = Math.max(lastW, est(m.n.label, small)); });
      var col = (c.w - lastW - rem * 1.2) / (t.depth || 1), rowH = c.h / t.leaves;
      function X(m) { return 6 + m.d * col; } function Y(m) { return (m.y + 0.5) * rowH; }
      c.head = ['Node', 'Parent'];
      t.all.forEach(function (m) { if (m.parent) { var x0 = X(m.parent), x1 = X(m), mx = (x0 + x1) / 2; S('path', { d: 'M' + f(x0) + ',' + f(Y(m.parent)) + 'C' + f(mx) + ',' + f(Y(m.parent)) + ' ' + f(mx) + ',' + f(Y(m)) + ' ' + f(x1) + ',' + f(Y(m)), fill: 'none', stroke: c.ink, 'stroke-opacity': 0.4, 'stroke-width': 1.3 }, c.svg); } });
      t.all.forEach(function (m) {
        var g = S('g', {}, c.svg), leaf = !m.kids.length;
        S('circle', { cx: f(X(m)), cy: f(Y(m)), r: 12, fill: 'transparent' }, g);
        shape(c, leaf ? 'ring' : 'disc', X(m), Y(m), leaf ? 4 : 5, g, m.n.label);
        if (leaf) c.text(X(m) + 9, Y(m) + small * 0.36, m.n.label, small, { op: 0.85, parent: g }); else plate(c, X(m) + 8, Y(m) - small - 9, m.n.label, small, g);
        c.rows.push([m.n.label, m.parent ? m.parent.n.label : '']); c.mark(g, m.n.label, m.kids.length ? m.kids.length + ' below' : (m.n.value != null ? c.fmt(m.n.value) : 'leaf'), m.n);
      });
    });
  }

  /* ── sankey: what flows from where to where ───────────────────────────────────────────────────────────────── */
  function sankey(el, data, o) {
    return mount(el, data, o, fixedH(15), function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.64, by = {}, nw = rem * 0.6, gap = rem * 0.7, maxD = 0, changed = true, guard = 0;
      d.nodes.forEach(function (n) { by[n.id] = { n: n, label: n.label || String(n.id), inn: 0, out: 0, depth: 0, src: [], dst: [] }; });
      var links = d.links.filter(function (l) { return by[l.a] && by[l.b] && l.value > 0; }).map(function (l) { var k = { a: by[l.a], b: by[l.b], v: +l.value }; k.a.out += k.v; k.b.inn += k.v; k.a.src.push(k); k.b.dst.push(k); return k; });
      while (changed && guard++ < 50) { changed = false; links.forEach(function (l) { if (l.b.depth < l.a.depth + 1) { l.b.depth = l.a.depth + 1; changed = true; } }); }
      var cols = []; Object.keys(by).forEach(function (id) { var n = by[id]; n.v = Math.max(n.inn, n.out); maxD = Math.max(maxD, n.depth); (cols[n.depth] = cols[n.depth] || []).push(n); });
      var ky = Infinity; cols.forEach(function (col) { ky = Math.min(ky, (c.h - gap * (col.length - 1)) / sum(col, function (n) { return n.v; })); });
      var lab = 0; (cols[maxD] || []).forEach(function (n) { lab = Math.max(lab, est(n.label, small)); });
      var span = c.w - nw - lab - rem * 0.5;
      cols.forEach(function (col, di) { var y = (c.h - sum(col, function (n) { return n.v; }) * ky - gap * (col.length - 1)) / 2; col.forEach(function (n) { n.x = maxD ? di / maxD * span : 0; n.y = y; n.h = n.v * ky; y += n.h + gap; }); });
      Object.keys(by).forEach(function (id) { var n = by[id], y = n.y; n.src.sort(function (p, q) { return p.b.y - q.b.y; }).forEach(function (l) { l.y0 = y; y += l.v * ky; }); y = n.y; n.dst.sort(function (p, q) { return p.a.y - q.a.y; }).forEach(function (l) { l.y1 = y; y += l.v * ky; }); });
      c.head = ['From', 'To', c.o.unit || 'Value'];
      links.forEach(function (l) {
        var x0 = l.a.x + nw, x1 = l.b.x, mx = (x0 + x1) / 2, t = Math.max(1, l.v * ky);
        var rib = S('path', { d: 'M' + f(x0) + ',' + f(l.y0) + 'C' + f(mx) + ',' + f(l.y0) + ' ' + f(mx) + ',' + f(l.y1) + ' ' + f(x1) + ',' + f(l.y1) + 'V' + f(l.y1 + t) + 'C' + f(mx) + ',' + f(l.y1 + t) + ' ' + f(mx) + ',' + f(l.y0 + t) + ' ' + f(x0) + ',' + f(l.y0 + t) + 'Z', fill: c.ink, 'fill-opacity': 0.26 }, c.svg);
        c.rows.push([l.a.label, l.b.label, c.fmt(l.v)]); c.mark(rib, l.a.label + ' → ' + l.b.label, l.v, l);
      });
      Object.keys(by).forEach(function (id) {
        var n = by[id], last = n.depth === maxD;
        S('rect', { x: f(n.x), y: f(n.y), width: f(nw), height: f(Math.max(2, n.h)), rx: 2, fill: c.ink, 'pointer-events': 'none' }, c.svg);
        c.text(last ? n.x + nw + 6 : n.x + nw + 6, n.y + n.h / 2 + small * 0.36, n.label, small, { op: 0.9 }).setAttribute('paint-order', 'stroke');
      });
      Array.prototype.forEach.call(c.svg.querySelectorAll('text'), function (t) { t.setAttribute('stroke', c.ground); t.setAttribute('stroke-width', 3); t.setAttribute('stroke-linejoin', 'round'); });
    });
  }

  /* ── chord: who exchanges with whom — arc length is how much, a ribbon is a pair ──────────────────────────── */
  function chord(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 60 ? ch : Math.min(c.w, c.rem * 18); }, function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.64, by = {}, cx = c.w / 2, cy = c.h / 2, lab = 0, total = 0, gapA = 0.05;
      d.nodes.forEach(function (n, i) { by[n.id] = { n: n, label: n.label || String(n.id), v: 0, i: i }; lab = Math.max(lab, est(n.label || String(n.id), small)); });
      var links = d.links.filter(function (l) { return by[l.a] && by[l.b] && l.value > 0; });
      links.forEach(function (l) { by[l.a].v += +l.value; by[l.b].v += +l.value; total += l.value * 2; });
      var R = Math.max(rem * 3, Math.min(c.w / 2 - lab - rem * 0.8, c.h / 2 - small - rem * 0.4)), ri = R - rem * 0.7, a = -Math.PI / 2, span = TAU - gapA * d.nodes.length;
      d.nodes.forEach(function (n) { var m = by[n.id]; m.a0 = m.at = a; m.a1 = a + m.v / (total || 1) * span; a = m.a1 + gapA; });
      c.head = ['From', 'To', c.o.unit || 'Value'];
      var ribs = links.map(function (l) {
        var A = by[l.a], B = by[l.b], da = l.value / (total || 1) * span, a0 = A.at, a1 = (A.at += da), b0 = B.at, b1 = (B.at += da);
        var rib = S('path', { d: 'M' + polar(cx, cy, ri, a0) + 'A' + f(ri) + ',' + f(ri) + ' 0 0 1 ' + polar(cx, cy, ri, a1) + 'Q' + f(cx) + ',' + f(cy) + ' ' + polar(cx, cy, ri, b0) + 'A' + f(ri) + ',' + f(ri) + ' 0 0 1 ' + polar(cx, cy, ri, b1) + 'Q' + f(cx) + ',' + f(cy) + ' ' + polar(cx, cy, ri, a0) + 'Z', fill: c.ink, 'fill-opacity': 0.24 }, c.svg);
        c.rows.push([A.label, B.label, c.fmt(l.value)]); c.mark(rib, A.label + ' ↔ ' + B.label, l.value, l);
        return rib;
      });
      d.nodes.forEach(function (n) {
        var m = by[n.id], mid = (m.a0 + m.a1) / 2, co = Math.cos(mid);
        if (m.a1 - m.a0 < 0.002) return;
        var arc = S('path', { d: wedge(cx, cy, ri + 2, R, m.a0, m.a1), fill: c.paint(n.fill || tex(Math.min(m.i, ORDER.length - 1))), stroke: c.ink, 'stroke-width': 1 }, c.svg);
        c.text(cx + Math.cos(mid) * (R + rem * 0.45), cy + Math.sin(mid) * (R + rem * 0.45) + small * (Math.sin(mid) > 0.3 ? 0.8 : Math.sin(mid) < -0.3 ? 0 : 0.36), m.label, small, { anchor: co > 0.25 ? 'start' : co < -0.25 ? 'end' : 'middle', op: 0.85 });
        c.mark(arc, m.label, m.v, n);
      });
    });
  }

  /* ── gauge: one figure on a dial ──────────────────────────────────────────────────────────────────────────── */
  function gauge(el, data, o) {
    return mount(el, data, o, function (c, ch) { return ch > 60 ? ch : Math.min(c.w * 0.72, c.rem * 11); }, function (c) {
      var d = c.data, rem = c.rem, small = rem * 0.64, max = d.max || 100, share = Math.max(0, Math.min(1, (+d.value || 0) / max)), A0 = Math.PI * 0.8, SW = Math.PI * 1.4, th = c.o.thickness || rem * 0.9;
      var R = Math.min(c.w / 2, (c.h - small - 2) / (1 + Math.sin(A0 - Math.PI / 2 + 0.0) * 0 + 0.59)) - 1, cx = c.w / 2, cy = R + 1, am = A0 + SW * share;
      if (share < 1) S('path', { d: wedge(cx, cy, R - th, R, am + (share > 0 ? 0.02 : 0), A0 + SW), fill: c.paint('hatch'), opacity: 0.4 }, c.svg);
      var arc = S('path', { d: wedge(cx, cy, R - th, R, A0, Math.max(A0 + 0.01, am)), fill: c.paint(c.o.fill || 'solid') }, c.svg);
      c.text(cx, cy + rem * 0.35, c.fmt(+d.value || 0), Math.min(rem * 2.2, R * 0.5), { anchor: 'middle', weight: c.weight });
      if (d.label) c.text(cx, cy + rem * 1.45, d.label, small, { anchor: 'middle', op: 0.7 });
      c.text(cx + Math.cos(A0) * (R - th / 2), cy + Math.sin(A0) * R + small + 3, '0', small, { anchor: 'middle', op: 0.6 }); c.text(cx + Math.cos(A0 + SW) * (R - th / 2), cy + Math.sin(A0 + SW) * R + small + 3, c.fmt(max), small, { anchor: 'middle', op: 0.6 });
      c.head = ['', 'Value', 'Of']; c.rows.push([d.label || 'Value', c.fmt(+d.value || 0), c.fmt(max)]);
      c.mark(arc, d.label || 'Value', c.fmt(+d.value || 0) + ' of ' + c.fmt(max) + ' · ' + Math.round(share * 100) + '%', d);
    });
  }

  /* ── words: frequency as size ─────────────────────────────────────────────────────────────────────────────── */
  function words(el, data, o) {
    return mount(el, data, o, fixedH(13), function (c) {
      var d = c.data.slice().sort(function (a, b) { return b.value - a.value; }).slice(0, 80), rem = c.rem, hi = d.length ? d[0].value : 1, placed = [], cx = c.w / 2, cy = c.h / 2, lo = rem * 0.66, top = Math.min(rem * 2.6, c.w / 8);
      c.head = ['', c.o.unit || 'Value'];
      d.forEach(function (it, i) {
        var size = lo + (top - lo) * Math.sqrt(it.value / hi), w = est(it.label, size) * 1.02 + 6, hgt = size * 1.05, t, x, y, ok = false;
        for (t = 0; t < 900 && !ok; t++) { var ang = t * 0.35, rad = t * 0.5; x = cx + Math.cos(ang) * rad * (c.w / c.h) * 0.7 - w / 2; y = cy + Math.sin(ang) * rad * 0.7 - hgt / 2; ok = x >= 0 && y >= 0 && x + w <= c.w && y + hgt <= c.h && !placed.some(function (p) { return x < p[2] && x + w > p[0] && y < p[3] && y + hgt > p[1]; }); }
        c.rows.push([it.label, c.fmt(it.value)]);
        if (!ok) return;
        placed.push([x, y, x + w, y + hgt]);
        var g = S('g', {}, c.svg), hit = S('rect', { x: f(x), y: f(y), width: f(w), height: f(hgt), fill: 'transparent' }, g);
        c.text(x + 3, y + size * 0.86, it.label, size, { weight: i < 3 ? 500 : c.weight, op: 0.45 + 0.55 * Math.sqrt(it.value / hi), parent: g });
        c.mark(g, it.label, it.value, it, hit);
      });
    });
  }

  return { shapes: SHAPES, columns: columns, waterfall: waterfall, bullet: bullet, range: range, radial: radial, funnel: funnel, area: area, candle: candle, calendar: calendar, histogram: histogram, box: box, violin: violin, scatter: scatter, radar: radar, parallel: parallel, treemap: treemap, sunburst: sunburst, pack: pack, tree: tree, sankey: sankey, chord: chord, gauge: gauge, words: words };
});
