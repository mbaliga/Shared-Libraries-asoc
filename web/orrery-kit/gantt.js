/* asoc gantt — days as columns, one row per dated thing, the work that leads to it as bars, hatched where it runs
   past the date; swimlanes by any grouping; a load row under everything. SVG, no dependencies.

   ASOC.gantt.render(host, {
     days: [{ k: 'yyyy-mm-dd', cap, used }],            // the columns; index 0 is today
     rows: [{ id, label, date: 'dd Mon', dueIdx, kind: 'hard'|'plan'|'send', vio, fits, late, unplaced, slots, group,
              items: [{ id, kind, title, runs: [{ a, b, slots }] }] }],
     groups: [{ key, label, collapsed }],               // optional swimlanes in order; rows carry group keys
     height,                                            // fill this height: row height adapts, then scrolls
     rem, ink, violet, cyan, field, mono, sans,
     fill: { task, gate, brief, review },               // bar colours by item kind
     maxDays, compact, loadLabel: 'load',
     onHover(rowId|null), onClick(id, ev), onBar(itemId, ev), onGroup(key),
     labels: { fits(row), late(row) }                   // verdict text per row (optional)
   }) → { svg, N, rowH } */
(function (root, factory) {
  var api = factory();
  root.ASOC = root.ASOC || {}; root.ASOC.gantt = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';
  var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function f(n) { return Math.round(n * 100) / 100; }
  function sv(tag, attrs, kids) {
    var e = document.createElementNS(NS, tag);
    Object.keys(attrs || {}).forEach(function (k) { if (attrs[k] != null) e.setAttribute(k, attrs[k]); });
    (kids || []).forEach(function (k) { if (k != null) e.appendChild(typeof k === 'string' ? document.createTextNode(k) : k); });
    return e;
  }
  function fromKey(k) { var p = k.split('-'); return new Date(+p[0], +p[1] - 1, +p[2]); }
  function mark(kind, col) {
    if (kind === 'hard') return sv('path', { d: 'M0-5.2L5.2 0 0 5.2-5.2 0z', fill: col });
    if (kind === 'send') return sv('rect', { x: -3.6, y: -3.6, width: 7.2, height: 7.2, fill: 'none', stroke: col });
    return sv('circle', { r: 4.2, fill: 'none', stroke: col, 'stroke-width': 1.3 });
  }
  function plural(n, w) { return n + ' ' + (n === 1 ? w : w + 's'); }

  function render(host, o) {
    while (host.firstChild) host.removeChild(host.firstChild);
    var rem = o.rem || 16, INK = o.ink || 'rgba(236,232,228', VIO = o.violet || 'rgb(165,147,255)', days = o.days || [], rows = o.rows || [];
    var ink = function (a) { return INK + ',' + a + ')'; };
    var FILL = Object.assign({ task: ink(0.78), gate: VIO, brief: 'rgba(53,224,255,.55)', review: 'rgba(53,224,255,.85)', send: 'none' }, o.fill || {});
    var W = host.clientWidth || 600;
    if (!rows.length) { var p = document.createElement('p'); p.className = 'rw-empty'; p.textContent = o.emptyText || 'Nothing dated ahead.'; host.appendChild(p); return null; }
    var groups = o.groups || [], byGroup = {};
    groups.forEach(function (g) { byGroup[g.key] = g; });
    // lines: group headers interleaved with their rows (collapsed groups keep one summary line)
    var lines = [];
    if (groups.length) {
      groups.forEach(function (g) {
        var rs = rows.filter(function (r) { return r.group === g.key; }); if (!rs.length) return;
        lines.push({ head: g, n: rs.length, rows: rs });
        if (!g.collapsed) rs.forEach(function (r) { lines.push({ row: r }); });
      });
    } else rows.forEach(function (r) { lines.push({ row: r }); });
    var LW = Math.min(rem * 15, W * 0.34), RW = rem * 5.2, avail = W - LW - RW;
    var far = 0; rows.forEach(function (r) { far = Math.max(far, r.dueIdx, r.end == null ? 0 : r.end); });
    var N = Math.max(14, Math.min(days.length, far + 4, o.maxDays || 999)), dw = avail / N;
    if (dw < 7) { N = Math.max(14, Math.floor(avail / 7)); dw = avail / N; }
    var top = rem * 2.1, bottom = rem * 2.4;
    var rhMin = o.compact ? rem * 1.2 : rem * 1.5, rhMax = rem * 2.6;
    var rh = o.height ? Math.max(rhMin, Math.min(rhMax, (o.height - top - bottom - rem * 1.2) / Math.max(1, lines.length))) : (o.compact ? rem * 1.3 : rem * 1.55);
    var Hh = top + lines.length * rh + bottom + rem * 1.2;
    var root = sv('svg', { class: 'rw', width: W, height: f(Hh), viewBox: '0 0 ' + W + ' ' + f(Hh), role: 'img', 'aria-label': rows.length + ' rows over the next ' + N + ' days' });
    root.appendChild(sv('defs', null, [sv('pattern', { id: 'rwHatch', patternUnits: 'userSpaceOnUse', width: 5, height: 5, patternTransform: 'rotate(45)' }, [sv('rect', { width: 5, height: 5, fill: 'rgba(165,147,255,.12)' }), sv('line', { x1: 0, y1: 0, x2: 0, y2: 5, stroke: VIO, 'stroke-width': 1.2 })])]));
    function X(i) { return LW + i * dw; }
    root.appendChild(sv('rect', { x: f(X(0)), y: f(top - rem * 0.5), width: f(dw * 3), height: f(lines.length * rh + rem * 0.6), fill: ink(0.045) }));
    var ax = sv('g', { class: 'rw-ax' });
    for (var i = 0; i <= N; i++) {
      var d = fromKey(days[Math.min(i, days.length - 1)].k), x = X(i);
      if (i < N && d.getDate() === 1) ax.appendChild(sv('text', { x: f(x + 3), y: f(rem * 0.7), class: 'rw-mo' }, [MON[d.getMonth()]]));
      if (d.getDay() === 1 || i === 0) {
        ax.appendChild(sv('line', { x1: f(x), y1: f(rem * 0.95), x2: f(x), y2: f(top + lines.length * rh), class: i === 0 ? 'rw-now' : 'rw-wk' }));
        if (i < N && dw * 7 > rem * 2.2 && (i === 0 || i * dw > rem * 2.6)) ax.appendChild(sv('text', { x: f(x + 3), y: f(rem * 1.75), class: 'rw-dy' }, [i === 0 ? 'today' : String(d.getDate())]));
      } else if (dw >= 14 && i < N) ax.appendChild(sv('text', { x: f(x + dw / 2), y: f(rem * 1.75), class: 'rw-dy rw-dd', 'text-anchor': 'middle' }, [String(d.getDate())]));
    }
    root.appendChild(ax);
    var gr = sv('g');
    lines.forEach(function (ln, li) {
      var y = top + li * rh + rh / 2;
      if (ln.head) {
        var g = ln.head, hg = sv('g', { class: 'rw-g' + (g.collapsed ? ' closed' : ''), role: 'button', tabindex: 0, 'aria-label': g.label + ', ' + plural(ln.n, 'row') + (g.collapsed ? ', collapsed' : '') });
        hg.appendChild(sv('line', { x1: f(rem * 0.2), y1: f(y - rh / 2 + 1), x2: f(X(N) + RW), y2: f(y - rh / 2 + 1), class: 'rw-gl' }));
        hg.appendChild(sv('text', { x: f(rem * 0.2), y: f(y + rem * 0.22), class: 'rw-gt' }, [(g.collapsed ? '▸ ' : '▾ ') + g.label]));
        hg.appendChild(sv('text', { x: f(LW - rem * 0.7), y: f(y + rem * 0.22), class: 'rw-dt', 'text-anchor': 'end' }, [plural(ln.n, 'row')]));
        if (g.collapsed) {
          // the collapsed group's rows as one line of markers on their days
          ln.rows.forEach(function (r) { var m = sv('g', { transform: 'translate(' + f(X(Math.min(r.dueIdx, N) + 0.5)) + ' ' + f(y) + ')' }); m.appendChild(mark(r.kind, r.vio ? VIO : ink(0.7))); hg.appendChild(m); });
          var late = ln.rows.filter(function (r) { return !r.fits; }).length;
          hg.appendChild(sv('text', { x: f(X(N) + RW - rem * 0.2), y: f(y + rem * 0.22), class: 'rw-v' + (late ? ' late' : ''), 'text-anchor': 'end' }, [late ? late + ' over' : 'all fit']));
        }
        hg.addEventListener('click', function () { if (o.onGroup) o.onGroup(g.key); });
        hg.addEventListener('keydown', function (ev) { if (ev.key === 'Enter' && o.onGroup) o.onGroup(g.key); });
        gr.appendChild(hg);
        return;
      }
      var r = ln.row, vio = !!r.vio;
      var row = sv('g', { class: 'rw-r' + (r.fits ? '' : ' late'), 'data-k': r.dayKey || '', 'data-id': r.id, tabindex: 0, role: 'button', 'aria-label': r.label + ', ' + r.date + (r.fits ? ', runway fits' : ', runs ' + plural(r.late || 0, 'day') + ' past its date') });
      var xd = X(Math.min(r.dueIdx, N) + 0.5);
      row.appendChild(sv('line', { x1: f(X(0)), y1: f(y), x2: f(xd), y2: f(y), class: 'rw-ln' }));
      var title = r.label, maxCh = Math.max(8, Math.floor((LW - rem * 4.2) / (rem * 0.46)));
      if (title.length > maxCh) title = title.slice(0, maxCh - 1) + '…';
      row.appendChild(sv('text', { x: f(rem * 0.2), y: f(y + rem * 0.22), class: 'rw-dt' }, [r.date]));
      row.appendChild(sv('text', { x: f(LW - rem * 0.7), y: f(y + rem * 0.22), class: 'rw-t' + (vio ? ' vio' : ''), 'text-anchor': 'end' }, [title]));
      (r.items || []).forEach(function (it) {
        (it.runs || []).forEach(function (run) {
          var x0 = X(Math.min(run.a, N)), x1 = X(Math.min(run.b + 1, N)), h = Math.min(rh * 0.42, rem * 0.7), over = run.b > r.dueIdx;
          var bar = sv('rect', { x: f(x0 + 1), y: f(y - h / 2), width: f(Math.max(2, x1 - x0 - 2)), height: f(h), rx: 1.5, fill: it.kind === 'send' ? 'none' : (FILL[it.kind] || FILL.task), stroke: it.kind === 'send' ? ink(0.8) : 'none', class: 'rw-b k-' + it.kind });
          bar.appendChild(sv('title', null, [(it.prefix || '') + it.title + ' — ' + run.slots + (run.slots === 1 ? ' slot' : ' slots') + (run.a === run.b ? '' : ', ' + (run.b - run.a + 1) + ' days') + (over ? ', past the date' : '')]));
          bar.addEventListener('click', function (ev) { ev.stopPropagation(); if (o.onBar) o.onBar(it.id, ev); });
          row.appendChild(bar);
          if (over) { var hx0 = Math.max(x0, X(Math.min(r.dueIdx + 1, N))); row.appendChild(sv('rect', { x: f(hx0), y: f(y - h / 2 - 2), width: f(Math.max(2, x1 - hx0)), height: f(h + 4), fill: 'url(#rwHatch)', class: 'rw-ov' })); }
        });
      });
      if (r.unplaced) row.appendChild(sv('text', { x: f(X(N) + 4), y: f(y + rem * 0.22), class: 'rw-dy' }, ['→ ' + r.unplaced + ' more']));
      var m = sv('g', { transform: 'translate(' + f(xd) + ' ' + f(y) + ')' });
      m.appendChild(sv('rect', { x: -6, y: -6, width: 12, height: 12, fill: o.field || '#000' })); m.appendChild(mark(r.kind, vio ? VIO : ink(0.92))); row.appendChild(m);
      var v = r.verdict != null ? r.verdict : (r.fits ? (r.slots ? r.slots + (r.slots === 1 ? ' slot' : ' slots') : '') : 'over ' + (r.unplaced ? 'the horizon' : plural(r.late || 0, 'day')));
      if (v) row.appendChild(sv('text', { x: f(X(N) + RW - rem * 0.2), y: f(y + rem * 0.22), class: 'rw-v' + (r.fits ? '' : ' late'), 'text-anchor': 'end' }, [v]));
      row.addEventListener('pointerenter', function () { if (o.onHover) o.onHover(r.id, r); });
      row.addEventListener('pointerleave', function () { if (o.onHover) o.onHover(null); });
      row.addEventListener('click', function (ev) { if (ev.target.classList && ev.target.classList.contains('rw-b')) return; if (o.onClick) o.onClick(r.id, ev); });
      row.addEventListener('keydown', function (ev) { if (ev.key === 'Enter' && o.onClick) o.onClick(r.id, ev); });
      gr.appendChild(row);
    });
    root.appendChild(gr);
    // the load: how full each day is
    var ly = top + lines.length * rh + rem * 0.9, lh = rem * 1.1, gl = sv('g', { class: 'rw-load' });
    gl.appendChild(sv('text', { x: f(LW - rem * 0.7), y: f(ly + lh), class: 'rw-dt', 'text-anchor': 'end' }, [o.loadLabel || 'load']));
    for (var j = 0; j < N; j++) {
      var dd = days[j]; if (!dd) break;
      var frac = dd.cap ? Math.min(1.4, dd.used / dd.cap) : (dd.used ? 1.4 : 0);
      var bar2 = sv('rect', { x: f(X(j) + 1), y: f(ly + lh - lh * Math.min(1, frac)), width: f(Math.max(1, dw - 2)), height: f(Math.max(0, lh * Math.min(1, frac))), fill: frac > 1 ? VIO : ink((0.28 + 0.5 * frac).toFixed(2)) });
      bar2.appendChild(sv('title', null, [dd.k + ': ' + dd.used + ' planned on ' + dd.cap + ' slots']));
      gl.appendChild(bar2);
      if (!dd.cap) gl.appendChild(sv('line', { x1: f(X(j) + 1), y1: f(ly + lh), x2: f(X(j) + dw - 1), y2: f(ly + lh), stroke: ink(0.25) }));
    }
    root.appendChild(gl);
    host.appendChild(root);
    return { svg: root, N: N, rowH: rh, lines: lines.length };
  }
  return { render: render, version: '0.1.0' };
});
