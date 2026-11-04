/* asoc cells — the mark of record is a cell, not a disc.
   A membrane that wobbles a little, seeded by what it stands for, so every mark is slightly different and always the
   same, with a nucleus for what is yours inside it. Canvas and SVG. No dependencies.
   API: hash(seed) → 0..1; pts(seed, n, wob); cell(ctx, x, y, r, seed, {fill, stroke, width, wob, n, nucleus:{r, fill}});
        d(seed, r, wob, n) → SVG path data centred at 0,0; mark(seed, {r, fill, stroke, width, wob, nucleus}) → SVG inner markup */
(function (root, factory) {
  var api = factory();
  root.ASOC = root.ASOC || {}; root.ASOC.cells = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';
  var cache = {};
  function hash(s) { var h = 2166136261; s = String(s); for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0) / 4294967296; }
  function pts(seed, n, wob) {
    var key = seed + '|' + n + '|' + wob;
    if (cache[key]) return cache[key];
    var x = Math.floor(hash(seed) * 2147483646) + 1, rnd = function () { x = x * 16807 % 2147483647; return (x - 1) / 2147483646; };
    var out = [];
    for (var i = 0; i < n; i++) out.push([i / n * Math.PI * 2 + (rnd() - 0.5) * 0.5 / n * Math.PI * 2, 1 + (rnd() - 0.5) * 2 * wob]);
    var na = rnd() * Math.PI * 2, nd = rnd() * 0.22;
    return (cache[key] = { pts: out, nx: Math.cos(na) * nd, ny: Math.sin(na) * nd });
  }
  function trace(move, curve, x, y, r, c) {
    var p = c.pts, n = p.length;
    function at(i) { var q = p[(i + n) % n]; return [x + Math.cos(q[0]) * q[1] * r, y + Math.sin(q[0]) * q[1] * r]; }
    var a = at(0), b = at(1);
    move((a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
    for (var i = 1; i <= n; i++) { var q = at(i), q2 = at(i + 1); curve(q[0], q[1], (q[0] + q2[0]) / 2, (q[1] + q2[1]) / 2); }
  }
  function cell(g, x, y, r, seed, o) {
    o = o || {};
    var c = pts(seed, o.n || 7, o.wob == null ? 0.14 : o.wob);
    g.beginPath();
    trace(function (mx, my) { g.moveTo(mx, my); }, function (cx, cy, px, py) { g.quadraticCurveTo(cx, cy, px, py); }, x, y, r, c);
    g.closePath();
    if (o.fill) { g.fillStyle = o.fill; g.fill(); }
    if (o.stroke) { g.strokeStyle = o.stroke; g.lineWidth = o.width || 1; g.stroke(); }
    if (o.nucleus && o.nucleus.r > 0) {
      var nr = r * o.nucleus.r;
      g.fillStyle = o.nucleus.fill; g.beginPath(); g.arc(x + c.nx * r * (1 - o.nucleus.r), y + c.ny * r * (1 - o.nucleus.r), Math.max(0.8, nr), 0, Math.PI * 2); g.fill();
    }
  }
  function d(seed, r, wob, n) {
    var c = pts(seed, n || 7, wob == null ? 0.14 : wob), out = '';
    trace(function (mx, my) { out += 'M' + mx.toFixed(2) + ' ' + my.toFixed(2); }, function (cx, cy, px, py) { out += 'Q' + cx.toFixed(2) + ' ' + cy.toFixed(2) + ' ' + px.toFixed(2) + ' ' + py.toFixed(2); }, 0, 0, r, c);
    return out + 'Z';
  }
  function mark(seed, o) {
    o = o || {};
    var r = o.r || 5, c = pts(seed, 7, o.wob == null ? 0.14 : o.wob), s = '<path d="' + d(seed, r, o.wob) + '"';
    s += o.fill ? ' fill="' + o.fill + '"' : ' fill="none"';
    if (o.stroke) s += ' stroke="' + o.stroke + '" stroke-width="' + (o.width || 1.2) + '"';
    s += '/>';
    if (o.nucleus) s += '<circle cx="' + (c.nx * r * 0.6).toFixed(2) + '" cy="' + (c.ny * r * 0.6).toFixed(2) + '" r="' + (r * 0.36).toFixed(2) + '" fill="' + o.nucleus + '"/>';
    return s;
  }
  return { hash: hash, pts: pts, cell: cell, d: d, mark: mark, version: '0.1.0' };
});
