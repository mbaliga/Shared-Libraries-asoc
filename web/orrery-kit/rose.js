/* asoc rose — twenty-four hours as a rose. Series stack outward from a hub on one square-root scale, so area is count.
   API: ASOC.rose.draw(ctx, cx, cy, R, series, opts)
     series: [{ vals: number[24], col: 'r,g,b', a: alpha }], stacked in order
     opts: { rem, hour (lit hour), range: [h0, h1) lit, dots: [{h, col, a, r, row}], caption, sub, now: true,
             hub: fn(ctx, cx, cy, r0) to draw the hub (default a thin ring), ink: 'r,g,b', mono: font, sans: font, segs: [[h0,h1,name]], hotSeg: index }
   ASOC.rose.hit(cx, cy, R, x, y) → hour under a point, or null. */
(function (root, factory) {
  var api = factory();
  root.ASOC = root.ASOC || {}; root.ASOC.rose = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';
  var TAU = Math.PI * 2;
  function rgba(c, a) { return 'rgba(' + c + ',' + Math.max(0, Math.min(1, a)).toFixed(3) + ')'; }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function draw(g, cx, cy, R, series, o) {
    o = o || {};
    var INK = o.ink || '236,232,228', rem = o.rem || 16, mono = o.mono || 'ui-monospace, monospace', sans = o.sans || 'system-ui, sans-serif';
    var r0 = R * 0.3, span = R - r0 - rem * 0.9, max = 1, tot = [], h;
    for (h = 0; h < 24; h++) tot.push(0);
    series.forEach(function (s) { for (var i = 0; i < 24; i++) tot[i] += s.vals[i] || 0; });
    for (h = 0; h < 24; h++) max = Math.max(max, tot[h]);
    var hov = o.hour, range = o.range, now = new Date().getHours();
    function ang(hh) { return hh / 24 * TAU - Math.PI / 2; }
    function wedge(hh, ri, ro, col, a) {
      if (ro - ri < 0.4) return;
      g.beginPath(); g.arc(cx, cy, ro, ang(hh) + 0.02, ang(hh + 1) - 0.02); g.arc(cx, cy, ri, ang(hh + 1) - 0.02, ang(hh) + 0.02, true); g.closePath();
      g.fillStyle = rgba(col, a); g.fill();
    }
    if (o.hub) o.hub(g, cx, cy, r0); else { g.strokeStyle = rgba(INK, 0.24); g.lineWidth = 1; g.beginPath(); g.arc(cx, cy, r0, 0, TAU); g.stroke(); }
    g.font = '400 ' + Math.round(rem * 0.5) + 'px ' + mono; g.fillStyle = rgba(INK, 0.42); g.textAlign = 'center'; g.textBaseline = 'middle';
    [0, 6, 12, 18].forEach(function (hh) { var a = ang(hh), rr = R - rem * 0.38; g.fillText(pad(hh), cx + Math.cos(a) * rr, cy + Math.sin(a) * rr); });
    for (h = 0; h < 24; h++) {
      var lit = hov == null ? (range ? (h >= range[0] && h < range[1]) : true) : h === hov, dim = lit ? 1 : 0.28, acc = 0;
      for (var si = 0; si < series.length; si++) {
        var v = series[si].vals[h] || 0; if (!v) continue;
        wedge(h, r0 + span * Math.sqrt(acc / max), r0 + span * Math.sqrt((acc + v) / max), series[si].col || INK, (series[si].a == null ? 0.7 : series[si].a) * dim);
        acc += v;
      }
      if (!tot[h]) { g.fillStyle = rgba(INK, 0.16 * dim); g.beginPath(); g.arc(cx + Math.cos(ang(h + 0.5)) * (r0 + 2.5), cy + Math.sin(ang(h + 0.5)) * (r0 + 2.5), 0.9, 0, TAU); g.fill(); }
    }
    if (o.now !== false) {
      g.strokeStyle = rgba(INK, 0.75); g.lineWidth = 1;
      g.beginPath(); g.moveTo(cx + Math.cos(ang(now + 0.5)) * (r0 - rem * 0.25), cy + Math.sin(ang(now + 0.5)) * (r0 - rem * 0.25)); g.lineTo(cx + Math.cos(ang(now + 0.5)) * (R - rem * 0.75), cy + Math.sin(ang(now + 0.5)) * (R - rem * 0.75)); g.stroke();
    }
    (o.dots || []).forEach(function (d) {
      var a = ang(d.h + 0.5), rr = r0 - rem * 0.28 - (d.row || 0) * rem * 0.22;
      g.fillStyle = rgba(d.col || INK, d.a == null ? 0.7 : d.a); g.beginPath(); g.arc(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr, d.r || 1.4, 0, TAU); g.fill();
    });
    if (o.segs) {
      var rr2 = R - rem * 0.08;
      o.segs.forEach(function (sg, i) {
        var a0 = sg[0] / 24 * TAU - Math.PI / 2 + 0.03, a1 = sg[1] / 24 * TAU - Math.PI / 2 - 0.03, on = i === o.hotSeg;
        g.beginPath(); g.arc(cx, cy, rr2, a0, a1); g.strokeStyle = rgba(INK, on ? 0.9 : 0.16); g.lineWidth = on ? 2 : 1; g.stroke();
        if (on && sg[2]) { var am = (a0 + a1) / 2; g.font = '400 ' + Math.round(rem * 0.46) + 'px ' + mono; g.fillStyle = rgba(INK, 0.8); g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(sg[2], cx + Math.cos(am) * (R + rem * 0.85), cy + Math.sin(am) * (R + rem * 0.85)); }
      });
    }
    if (o.caption) {
      g.font = '400 ' + Math.round(rem * 0.52) + 'px ' + sans; g.fillStyle = rgba(INK, 0.5); g.textAlign = 'center'; g.textBaseline = 'alphabetic';
      g.fillText(o.caption.length > 26 ? o.caption.slice(0, 25) + '…' : o.caption, cx, cy + R + rem * 0.85);
      if (o.sub) { g.font = '400 ' + Math.round(rem * 0.44) + 'px ' + mono; g.fillStyle = rgba(INK, 0.34); g.fillText(o.sub, cx, cy + R + rem * 1.55); }
    }
    return { cx: cx, cy: cy, R: R, r0: r0 };
  }
  function hit(cx, cy, R, x, y) {
    var dx = x - cx, dy = y - cy, r = Math.sqrt(dx * dx + dy * dy);
    if (r > R || r < R * 0.15) return null;
    return Math.floor((((Math.atan2(dy, dx) + Math.PI / 2) % TAU + TAU) % TAU) / TAU * 24) % 24;
  }
  return { draw: draw, hit: hit, version: '0.1.0' };
});
