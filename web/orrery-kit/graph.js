/* asoc graph — a force-directed graph you can turn, in three dimensions or two, with the interactions a graph
   library owes its users (the AntV G6 set, mapped in README.md). Canvas 2D, no dependencies.

   var g = ASOC.Graph.create({
     nodes: [{ id, group, size, pinned? }], links: [{ a, b, band?, w? }],
     mode: '3d' | '2d',
     draw: { node(ctx, n, s, st), link(ctx, l, sa, sb, st), label(n) → text|null },   // optional overrides
     on:   { select(ids), hover(id), focus(id), context(id, x, y, ev), link(aId, bId), change(kind), tooltip(id, x, y) },
     options: { wheel: 'zoom'|'scroll', fixSize: true, fisheye: false, edgeLens: false, grid: false, hull: true,
                snap: true, history: true, forceDrag: false, labels: true, minZoom: .4, maxZoom: 4 }
   });
   g.layout(iterations); g.attach(canvas); g.frame(ctx, W, H); g.fit(); g.focus(id); g.select(ids); g.setMode(mode);
   g.collapse(group); g.expand(group); g.pin(id, on); g.undo(); g.redo(); g.hitAt(x, y); g.screen(id);
   g.hullOf(ids); g.drawMinimap(ctx, x, y, w, h); g.labelsFor(order, ctx) ; g.state; g.destroy()

   Screen positions come back as { x, y, k (scale), z (depth, 3D) }. The host draws; the engine lays out, projects,
   and turns pointer and key events into selection, focus, drags, brushes, lassos, collapses and history. */
(function (root, factory) {
  var api = factory();
  root.ASOC = root.ASOC || {}; root.ASOC.Graph = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';
  var TAU = Math.PI * 2;
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function rng(seed) { var x = Math.floor(seed * 2147483646) + 1; return function () { x = x * 16807 % 2147483647; return (x - 1) / 2147483646; }; }

  function create(cfg) {
    cfg = cfg || {};
    var O = Object.assign({ wheel: 'zoom', fixSize: true, fisheye: false, fisheyeR: 120, fisheyeK: 1.8, edgeLens: false, edgeLensR: 110, grid: false, hull: true,
      snap: true, history: true, forceDrag: false, labels: true, minZoom: 0.4, maxZoom: 4, D: 3.1, within: null }, cfg.options || {});
    // within(x, y): when the canvas is shared with other things, the part of it that is the graph's
    var on = cfg.on || {}, drawCb = cfg.draw || {};
    var G = {
      mode: cfg.mode || '3d', nodes: [], links: [], byId: {}, groups: {}, collapsed: {},
      yaw: 0.4, pitch: -0.32, zoom: 1, px: 0, py: 0, W: 300, H: 300, cx: 150, cy: 150, R: 100,
      hover: null, selected: [], moving: false, drag: null, brush: null, lasso: null, pointer: null,
      hist: [], histAt: -1, anim: null, raf: 0, el: null, ctx: null
    };
    var self = { state: G, options: O };

    // ---------------------------------------------------------------- data
    function setData(nodes, links) {
      G.nodes = []; G.byId = {}; G.links = []; G.groups = {};
      (nodes || []).forEach(function (n) {
        var m = Object.assign({ deg: 0, x: 0, y: 0, z: 0, vx: 0, vy: 0, vz: 0, size: 1, pinned: false }, n);
        G.nodes.push(m); G.byId[m.id] = m;
        if (m.group) (G.groups[m.group] = G.groups[m.group] || []).push(m.id);
      });
      var seen = {};
      (links || []).forEach(function (l) {
        var a = G.byId[l.a], b = G.byId[l.b], k = l.a + '|' + l.b;
        if (!a || !b || seen[k]) return;
        seen[k] = 1; a.deg++; b.deg++;
        G.links.push({ a: a, b: b, band: l.band || '', w: l.w == null ? 1 : l.w, hub: l.hub == null ? 1 : l.hub });
      });
    }
    self.setData = setData;
    setData(cfg.nodes, cfg.links);

    // ---------------------------------------------------------------- layout: springs, repulsion, a pull to the centre
    function seedPositions() {
      var r = rng(0.4141), keys = Object.keys(G.groups), gc = {};
      keys.forEach(function (k, i) { var t = i / Math.max(1, keys.length) * TAU; gc[k] = [Math.cos(t) * 0.7, (i % 2 ? 0.3 : -0.3), Math.sin(t) * 0.7]; });
      G.nodes.forEach(function (n) {
        if (n.placed) return;
        var c = n.group && gc[n.group] ? gc[n.group] : [0, 0, 0];
        n.x = c[0] + (r() - 0.5) * 0.6; n.y = c[1] + (r() - 0.5) * 0.6; n.z = G.mode === '2d' ? 0 : c[2] + (r() - 0.5) * 0.6;
        n.placed = true;
      });
    }
    function tick(cool, K, REST, SPRING, CENTRE) {
      var N = G.nodes, n2 = N.length, flat = G.mode === '2d', i, j, a, b, dx, dy, dz, d2, f, d;
      for (i = 0; i < n2; i++) {
        a = N[i];
        for (j = i + 1; j < n2; j++) {
          b = N[j]; dx = a.x - b.x; dy = a.y - b.y; dz = flat ? 0 : a.z - b.z; d2 = dx * dx + dy * dy + dz * dz + 0.004; f = K / d2;
          d = Math.sqrt(d2); dx /= d; dy /= d; dz /= d;
          a.vx += dx * f; a.vy += dy * f; a.vz += dz * f; b.vx -= dx * f; b.vy -= dy * f; b.vz -= dz * f;
        }
      }
      G.links.forEach(function (l) {
        var p = l.a, q = l.b, ddx = q.x - p.x, ddy = q.y - p.y, ddz = flat ? 0 : q.z - p.z, dd = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) + 1e-6;
        var k = SPRING * l.hub * (dd - REST) / dd, wa = 1 / Math.max(1, p.deg * 0.25), wb = 1 / Math.max(1, q.deg * 0.25);
        p.vx += ddx * k * wa; p.vy += ddy * k * wa; p.vz += ddz * k * wa; q.vx -= ddx * k * wb; q.vy -= ddy * k * wb; q.vz -= ddz * k * wb;
      });
      N.forEach(function (n) {
        n.vx -= n.x * CENTRE; n.vy -= n.y * CENTRE; n.vz -= n.z * CENTRE;
        if (!n.pinned && !(G.drag && G.drag.node === n)) { n.x += n.vx * cool; n.y += n.vy * cool; n.z += flat ? 0 : n.vz * cool; }
        n.vx *= 0.55; n.vy *= 0.55; n.vz *= 0.55;
        if (flat) n.z = 0;
      });
    }
    function normalise() {
      var N = G.nodes, n2 = N.length || 1, cx = 0, cy = 0, cz = 0;
      N.forEach(function (n) { cx += n.x; cy += n.y; cz += n.z; });
      cx /= n2; cy /= n2; cz /= n2;
      var ds = N.map(function (n) { n.x -= cx; n.y -= cy; n.z -= cz; return Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z); }).sort(function (a, b) { return a - b; });
      var far = ds[Math.floor(ds.length * 0.9)] || 1;
      N.forEach(function (n) {
        n.x /= far; n.y /= far; n.z /= far;
        var d = Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z);
        if (d > 1.06) { var k = (1.06 + (d - 1.06) * 0.15) / d; n.x *= k; n.y *= k; n.z *= k; }
      });
    }
    self.layout = function (iterations) {
      seedPositions();
      var it = iterations || 420;
      for (var i = 0; i < it; i++) tick(1 - i / it, 0.0062, 0.4, 0.028, 0.012);
      normalise();
      return self;
    };
    // a few steps while something is dragged, so the neighbourhood follows (DragElementForce)
    function settle(steps) { for (var i = 0; i < steps; i++) tick(0.5, 0.0062, 0.4, 0.028, 0.012); }

    // ---------------------------------------------------------------- projection and camera
    function project(n) {
      var x, y, z, k;
      if (G.mode === '2d') { x = n.x; y = n.y; z = 0; k = 1; }
      else {
        var cy = Math.cos(G.yaw), sy = Math.sin(G.yaw), cp = Math.cos(G.pitch), sp = Math.sin(G.pitch);
        x = n.x * cy + n.z * sy; z = -n.x * sy + n.z * cy; y = n.y;
        var y2 = y * cp - z * sp, z2 = y * sp + z * cp;
        k = O.D / (O.D + z2); x *= k; y = y2 * k; z = z2;
      }
      var s = { x: G.cx + G.px + x * G.R * G.zoom, y: G.cy + G.py + y * G.R * G.zoom, k: k * G.zoom, z: z };
      if (O.fisheye && G.pointer && !G.moving) fisheye(s);
      n.sx = s.x; n.sy = s.y; n.sk = s.k; n.sz = s.z;
      return s;
    }
    function fisheye(s) {
      var dx = s.x - G.pointer.x, dy = s.y - G.pointer.y, d = Math.sqrt(dx * dx + dy * dy), R = O.fisheyeR;
      if (d >= R || d === 0) return;
      var m = (O.fisheyeK + 1) * d / (O.fisheyeK * d / R + 1);   // Sarkar–Brown
      s.x = G.pointer.x + dx / d * m; s.y = G.pointer.y + dy / d * m; s.k *= 1 + (1 - d / R) * 0.6;
    }
    // the inverse for the plane: where a screen point lands in graph space (2D)
    function unproject2(x, y) { return [(x - G.cx - G.px) / (G.R * G.zoom), (y - G.cy - G.py) / (G.R * G.zoom)]; }
    self.project = function () { G.nodes.forEach(project); if (G.morph) { morphBlend(); kick(); } return G.nodes; };
    self.morphing = function () { return !!G.morph; };
    self.screen = function (id) { var n = G.byId[id]; return n ? project(n) : null; };
    self.size = function (W, H, cx, cy, R) { G.W = W; G.H = H; G.cx = cx == null ? W / 2 : cx; G.cy = cy == null ? H / 2 : cy; G.R = R || Math.min(W, H) * 0.42; };
    // the plane is laid out on its own terms: more room between nodes, and groups kept together, so every label fits
    function layout2d() {
      G.nodes.forEach(function (n) { n.z = 0; n.vx = n.vy = n.vz = 0; });
      for (var i = 0; i < 220; i++) tick(1 - i / 220, 0.011, 0.62, 0.03, 0.01);
      normalise();
    }
    function layout3d() {
      var r = rng(0.77);
      G.nodes.forEach(function (n) { n.z = (r() - 0.5) * 0.6; n.vx = n.vy = n.vz = 0; });
      for (var i = 0; i < 220; i++) tick(1 - i / 220, 0.0062, 0.4, 0.028, 0.012);
      normalise();
    }
    // mode changes morph: every node travels from where it was drawn to where it lands (the viewport animation)
    self.setMode = function (mode, quiet) {
      if (mode === G.mode) return;
      if (!quiet) push({ kind: 'mode', from: G.mode, to: mode });
      G.nodes.forEach(project);
      var from = G.nodes.map(function (n) { return { n: n, sx: n.sx, sy: n.sy, k: n.sk }; });
      G.mode = mode;
      if (mode === '2d') layout2d(); else layout3d();
      if (mode === '2d') { G.pitch = 0; } else { G.pitch = -0.32; }
      G.morph = { from: from, t0: performance.now(), ms: 520 };
      emit('change', 'mode');
      kick();
    };
    // morph: blend the drawn position between the old screen place and the new one
    function morphBlend() {
      var m = G.morph; if (!m) return false;
      var t = clamp((performance.now() - m.t0) / m.ms, 0, 1), e = 1 - Math.pow(1 - t, 3);
      m.from.forEach(function (f) { var n = f.n; n.sx = f.sx + (n.sx - f.sx) * e; n.sy = f.sy + (n.sy - f.sy) * e; n.sk = f.k + (n.sk - f.k) * e; });
      if (t >= 1) G.morph = null;
      return true;
    }

    // ---------------------------------------------------------------- selection, hover, focus
    function emit(ev, a, b, c, d) { if (on[ev]) on[ev](a, b, c, d); }
    self.select = function (ids, additive) {
      var next = additive ? G.selected.slice() : [];
      (ids || []).forEach(function (id) { var i = next.indexOf(id); if (i >= 0 && additive) next.splice(i, 1); else if (i < 0) next.push(id); });
      G.selected = next; emit('select', next.slice());
    };
    self.selected = function () { return G.selected.slice(); };
    self.isSelected = function (id) { return G.selected.indexOf(id) >= 0; };
    self.neighbours = function (id) { var out = []; G.links.forEach(function (l) { if (l.a.id === id) out.push(l.b.id); else if (l.b.id === id) out.push(l.a.id); }); return out; };
    self.setHover = function (id) { if (G.hover === id) return; G.hover = id; emit('hover', id); };
    // focus: bring a node to the centre, a little closer (FocusElement)
    self.focus = function (id, zoomTo) {
      var n = G.byId[id]; if (!n) return;
      var s = project(n), tz = zoomTo || Math.min(O.maxZoom, Math.max(G.zoom, 1.4));
      animate({ px: G.px - (s.x - G.cx) * (tz / G.zoom), py: G.py - (s.y - G.cy) * (tz / G.zoom), zoom: tz }, 360);
      emit('focus', id);
    };
    self.fit = function () {
      G.nodes.forEach(project);
      var x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
      G.nodes.forEach(function (n) { x0 = Math.min(x0, n.sx); y0 = Math.min(y0, n.sy); x1 = Math.max(x1, n.sx); y1 = Math.max(y1, n.sy); });
      if (x1 <= x0 || y1 <= y0) return;
      var pad = G.R * 0.18, fx = (G.W - pad * 2) / (x1 - x0), fy = (G.H - pad * 2) / (y1 - y0), f = clamp(Math.min(fx, fy), 0.2, 6);
      var mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
      animate({ zoom: clamp(G.zoom * f, O.minZoom, O.maxZoom), px: G.px + (G.cx - mx) * f, py: G.py + (G.cy - my) * f }, 360);
    };
    self.reset = function () { animate({ zoom: 1, px: 0, py: 0, yaw: 0.4, pitch: -0.32 }, 360); };
    function animate(to, ms) {
      var from = {}; Object.keys(to).forEach(function (k) { from[k] = G[k]; });
      var t0 = performance.now();
      G.anim = function (now) {
        var t = clamp((now - t0) / ms, 0, 1), e = 1 - Math.pow(1 - t, 3);
        Object.keys(to).forEach(function (k) { G[k] = from[k] + (to[k] - from[k]) * e; });
        if (t >= 1) G.anim = null;
      };
      kick();
    }

    // ---------------------------------------------------------------- collapse and expand groups (CollapseExpand)
    self.collapse = function (group) {
      var ids = G.groups[group]; if (!ids || G.collapsed[group]) return;
      var members = ids.map(function (id) { return G.byId[id]; }).filter(Boolean), cx = 0, cy = 0, cz = 0;
      members.forEach(function (m) { cx += m.x; cy += m.y; cz += m.z; m.hidden = true; });
      var sup = { id: 'group:' + group, group: group, superOf: ids, size: 1 + Math.sqrt(members.length) * 0.5, deg: 0, x: cx / members.length, y: cy / members.length, z: cz / members.length, vx: 0, vy: 0, vz: 0, placed: true, isGroup: true };
      G.nodes.push(sup); G.byId[sup.id] = sup;
      G.collapsed[group] = sup;
      // links that touched a member now touch the group
      G.links.forEach(function (l) {
        if (l.a.hidden && l.b.hidden) { l.hidden = true; return; }
        if (l.a.hidden) { l.a0 = l.a; l.a = sup; sup.deg++; }
        if (l.b.hidden) { l.b0 = l.b; l.b = sup; sup.deg++; }
      });
      push({ kind: 'collapse', group: group });
      emit('change', 'collapse');
    };
    self.expand = function (group) {
      var sup = G.collapsed[group]; if (!sup) return;
      sup.superOf.forEach(function (id) { var m = G.byId[id]; if (m) { m.hidden = false; m.x = sup.x + (Math.random() - 0.5) * 0.1; m.y = sup.y + (Math.random() - 0.5) * 0.1; } });
      G.links.forEach(function (l) { if (l.a === sup) { l.a = l.a0; delete l.a0; } if (l.b === sup) { l.b = l.b0; delete l.b0; } if (l.hidden && !l.a.hidden && !l.b.hidden) l.hidden = false; });
      G.nodes.splice(G.nodes.indexOf(sup), 1); delete G.byId[sup.id]; delete G.collapsed[group];
      settle(40);
      push({ kind: 'expand', group: group });
      emit('change', 'expand');
    };
    self.isCollapsed = function (group) { return !!G.collapsed[group]; };
    self.groupMembers = function (group) { return (G.groups[group] || []).filter(function (id) { var n = G.byId[id]; return n && !n.hidden; }); };
    // a smooth outline round a set of nodes (BubbleSets stand-in): the padded hull with rounded corners
    self.bubbleOf = function (ids, pad) {
      var h = self.hullOf(ids, pad == null ? 18 : pad); if (h.length < 3) return h;
      var out = [];
      for (var i = 0; i < h.length; i++) { var a = h[i], b = h[(i + 1) % h.length]; out.push([(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, b[0], b[1]]); }
      return { mid: out, pts: h };
    };
    self.drawBubble = function (ctx, ids, fill, stroke, pad) {
      var b = self.bubbleOf(ids, pad); if (!b || !b.mid || b.mid.length < 3) return false;
      ctx.beginPath(); ctx.moveTo(b.mid[0][0], b.mid[0][1]);
      for (var i = 1; i <= b.mid.length; i++) { var q = b.mid[i % b.mid.length], c = b.mid[i - 1]; ctx.quadraticCurveTo(c[2], c[3], q[0], q[1]); }
      ctx.closePath(); if (fill) { ctx.fillStyle = fill; ctx.fill(); } if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = 1; ctx.stroke(); }
      return true;
    };
    // cheap edge bundling: a link between two groups bends through the midpoint of the group centroids
    self.linkPath = function (l) {
      var a = l.a, b = l.b;
      if (!O.bundle || !a.group || !b.group || a.group === b.group) return null;
      var ca = centroid(a.group), cb = centroid(b.group);
      if (!ca || !cb) return null;
      return [(ca[0] + cb[0]) / 2 * 0.7 + (a.sx + b.sx) / 2 * 0.3, (ca[1] + cb[1]) / 2 * 0.7 + (a.sy + b.sy) / 2 * 0.3];
    };
    function centroid(group) { var ids = G.groups[group] || [], x = 0, y = 0, n = 0; ids.forEach(function (id) { var m = G.byId[id]; if (m && !m.hidden) { x += m.sx; y += m.sy; n++; } }); return n ? [x / n, y / n] : null; }

    // ---------------------------------------------------------------- pins and history (DragElement, History)
    self.pin = function (id, on2) { var n = G.byId[id]; if (!n) return; var was = n.pinned; n.pinned = on2 == null ? !n.pinned : !!on2; if (was !== n.pinned) push({ kind: 'pin', id: id, from: was, to: n.pinned, x: n.x, y: n.y, z: n.z }); };
    function push(h) { if (!O.history) return; G.hist = G.hist.slice(0, G.histAt + 1); G.hist.push(h); if (G.hist.length > 60) G.hist.shift(); G.histAt = G.hist.length - 1; }
    function apply(h, back) {
      if (h.kind === 'pin') { var n = G.byId[h.id]; if (n) { n.pinned = back ? h.from : h.to; if (back && h.x0 != null) { n.x = h.x0; n.y = h.y0; n.z = h.z0; } } }
      else if (h.kind === 'move') { var m = G.byId[h.id]; if (m) { var p = back ? h.from : h.to; m.x = p[0]; m.y = p[1]; m.z = p[2]; } }
      else if (h.kind === 'collapse') { O.history = false; if (back) self.expand(h.group); else self.collapse(h.group); O.history = true; }
      else if (h.kind === 'expand') { O.history = false; if (back) self.collapse(h.group); else self.expand(h.group); O.history = true; }
      else if (h.kind === 'mode') { var want = back ? h.from : h.to; if (G.mode !== want) { G.mode = want; settle(60); normalise(); } }
    }
    self.undo = function () { if (G.histAt < 0) return false; apply(G.hist[G.histAt], true); G.histAt--; emit('change', 'undo'); return true; };
    self.redo = function () { if (G.histAt >= G.hist.length - 1) return false; G.histAt++; apply(G.hist[G.histAt], false); emit('change', 'redo'); return true; };
    self.canUndo = function () { return G.histAt >= 0; };
    self.canRedo = function () { return G.histAt < G.hist.length - 1; };

    // ---------------------------------------------------------------- hits, hulls, labels
    self.labelBoxes = {};   // id -> [x0,y0,x1,y1], set by the host after it draws labels (InnerElementEvent)
    self.partAt = function (x, y) {
      var n = self.hitAt(x, y); if (n) return { node: n, part: 'body' };
      var ids = Object.keys(self.labelBoxes);
      for (var i = 0; i < ids.length; i++) { var b = self.labelBoxes[ids[i]]; if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) return { node: G.byId[ids[i]], part: 'label' }; }
      return null;
    };
    self.hitAt = function (x, y) {
      var best = null, bd = 1e9;
      G.nodes.forEach(function (n) {
        if (n.hidden) return;
        var hr = (n.hitR || 8), d = Math.hypot(n.sx - x, n.sy - y);
        if (d < hr + 4 && d + (n.sz || 0) * 3 < bd) { bd = d + (n.sz || 0) * 3; best = n; }
      });
      return best;
    };
    // the convex hull of some nodes on screen, padded (Hull, BubbleSets stand-in)
    self.hullOf = function (ids, pad) {
      var pts = ids.map(function (id) { return G.byId[id]; }).filter(function (n) { return n && !n.hidden; }).map(function (n) { return [n.sx, n.sy]; });
      if (pts.length < 2) return pts;
      pts.sort(function (a, b) { return a[0] - b[0] || a[1] - b[1]; });
      function cross(o, a, b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }
      var lo = [], up = [], i;
      for (i = 0; i < pts.length; i++) { while (lo.length >= 2 && cross(lo[lo.length - 2], lo[lo.length - 1], pts[i]) <= 0) lo.pop(); lo.push(pts[i]); }
      for (i = pts.length - 1; i >= 0; i--) { while (up.length >= 2 && cross(up[up.length - 2], up[up.length - 1], pts[i]) <= 0) up.pop(); up.push(pts[i]); }
      var hull = lo.slice(0, -1).concat(up.slice(0, -1)), cx = 0, cy = 0;
      hull.forEach(function (p) { cx += p[0]; cy += p[1]; }); cx /= hull.length; cy /= hull.length;
      pad = pad == null ? 14 : pad;
      return hull.map(function (p) { var dx = p[0] - cx, dy = p[1] - cy, d = Math.hypot(dx, dy) || 1; return [p[0] + dx / d * pad, p[1] + dy / d * pad]; });
    };
    // which labels fit without overlapping, in the order given (AutoAdaptLabel): returns the nodes to label
    self.labelsFor = function (order, measure, pad) {
      var placed = [], out = [];
      order.forEach(function (n) {
        var box = measure(n); if (!box) return;
        if (placed.some(function (q) { return box[0] < q[2] && box[2] > q[0] && box[1] < q[3] && box[3] > q[1]; })) return;
        placed.push(box); out.push(n);
      });
      return out;
    };
    // edges to draw: all, or only those near the pointer when the edge lens is on (EdgeFilterLens)
    self.visibleLinks = function () {
      if (!O.edgeLens || !G.pointer) return G.links.filter(function (l) { return !l.hidden; });
      var p = G.pointer, R = O.edgeLensR;
      return G.links.filter(function (l) { return !l.hidden && (Math.hypot(l.a.sx - p.x, l.a.sy - p.y) < R || Math.hypot(l.b.sx - p.x, l.b.sy - p.y) < R); });
    };

    // ---------------------------------------------------------------- the minimap (Minimap)
    self.drawMinimap = function (ctx, x, y, w, h, o) {
      o = o || {};
      var ink = o.ink || '236,232,228';
      var x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
      G.nodes.forEach(function (n) { if (n.hidden) return; x0 = Math.min(x0, n.sx); y0 = Math.min(y0, n.sy); x1 = Math.max(x1, n.sx); y1 = Math.max(y1, n.sy); });
      if (x1 <= x0) return null;
      var sx = (w - 8) / Math.max(x1 - x0, 1), sy = (h - 8) / Math.max(y1 - y0, 1), s = Math.min(sx, sy);
      var ox = x + 4 + ((w - 8) - (x1 - x0) * s) / 2, oy = y + 4 + ((h - 8) - (y1 - y0) * s) / 2;
      ctx.strokeStyle = 'rgba(' + ink + ',.2)'; ctx.lineWidth = 1; ctx.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
      ctx.fillStyle = 'rgba(' + ink + ',.55)';
      G.nodes.forEach(function (n) { if (n.hidden) return; ctx.fillRect(ox + (n.sx - x0) * s - 0.75, oy + (n.sy - y0) * s - 0.75, 1.5, 1.5); });
      // the viewport
      var vx = ox + (0 - x0) * s, vy = oy + (0 - y0) * s, vw = G.W * s, vh = G.H * s;
      ctx.strokeStyle = 'rgba(' + ink + ',.6)'; ctx.strokeRect(clamp(vx, x, x + w) + 0.5, clamp(vy, y, y + h) + 0.5, Math.min(vw, w), Math.min(vh, h));
      G.mini = { x: x, y: y, w: w, h: h, ox: ox, oy: oy, s: s, x0: x0, y0: y0 };
      return G.mini;
    };
    function miniHit(x, y) { var m = G.mini; return m && x >= m.x && x <= m.x + m.w && y >= m.y && y <= m.y + m.h ? m : null; }

    // ---------------------------------------------------------------- pointer and keys
    var el = null, moveT = 0;
    function local(ev) { var r = el.getBoundingClientRect(); return [ev.clientX - r.left, ev.clientY - r.top]; }
    function setMoving(v) { G.moving = v; }   // OptimizeViewportTransform: hosts draw cheaper while this is true
    function onMove(ev) {
      var p = local(ev), x = p[0], y = p[1];
      G.pointer = { x: x, y: y };
      var d = G.drag;
      if (d) {
        var dx = x - d.x, dy = y - d.y;
        if (!d.on && Math.abs(dx) + Math.abs(dy) > 3) d.on = true;
        if (!d.on) { kick(); return; }
        if (d.kind === 'node') {
          var n = d.node;
          if (G.mode === '2d') { var w = unproject2(x - d.gx, y - d.gy); n.x = w[0]; n.y = w[1]; if (O.snap) snapTo(n); }
          else { var k = n.sk || 1; n.x = d.nx + dx / (G.R * k) * (Math.cos(G.yaw)) - 0 * dy; n.y = d.ny + dy / (G.R * k) * Math.cos(G.pitch); n.z = d.nz - dx / (G.R * k) * Math.sin(G.yaw); }
          n.pinned = true;
          if (O.forceDrag) settle(2);
          var over = self.hitAt(x, y); if (over === n) over = null; d.over = over ? over.id : null;
          emit('hover', d.over);
        } else if (d.kind === 'brush') { G.brush = [d.x, d.y, x, y]; }
        else if (d.kind === 'lasso') { G.lasso.push([x, y]); }
        else if (d.kind === 'pan') { G.px = d.px + dx; G.py = d.py + dy; setMoving(true); }
        else if (d.kind === 'rotate') { G.yaw = d.yaw + dx / Math.max(120, G.R) * 1.6; G.pitch = clamp(d.pitch + dy / Math.max(120, G.R) * 1.6, -1.25, 1.25); setMoving(true); }
        else if (d.kind === 'mini') { navMini(x, y); }
        kick(); return;
      }
      if (O.within && !O.within(x, y)) { if (G.hover) { self.setHover(null); if (on.tooltip) on.tooltip(null); } G.pointer = null; kick(); return; }
      var h = self.hitAt(x, y);
      self.setHover(h ? h.id : null);
      if (h && on.tooltip) on.tooltip(h.id, ev.clientX, ev.clientY); else if (on.tooltip) on.tooltip(null);
      if (el) el.style.cursor = h ? 'pointer' : (G.mode === '2d' ? 'default' : 'grab');
      kick();
    }
    self.inside = function (x, y) { return !O.within || O.within(x, y); };
    function snapTo(n) {
      var best = null;
      G.nodes.forEach(function (m) { if (m === n || m.hidden) return; if (Math.abs(m.sx - n.sx) < 5) { var w = unproject2(m.sx, n.sy); n.x = w[0]; best = best || {}; best.x = m.sx; } if (Math.abs(m.sy - n.sy) < 5) { var w2 = unproject2(n.sx, m.sy); n.y = w2[1]; best = best || {}; best.y = m.sy; } });
      G.snap = best;
    }
    function navMini(x, y) { var m = G.mini; if (!m) return; var gx = (x - m.ox) / m.s + m.x0, gy = (y - m.oy) / m.s + m.y0; G.px += G.cx - gx; G.py += G.cy - gy; }
    function onDown(ev) {
      if (ev.button !== undefined && ev.button !== 0 && ev.button !== 1) return;
      var p = local(ev), x = p[0], y = p[1];
      if (O.within && !O.within(x, y) && !miniHit(x, y)) return;
      try { el.setPointerCapture(ev.pointerId); } catch (e) { /* fine */ }
      if (miniHit(x, y)) { G.drag = { kind: 'mini', x: x, y: y, on: true }; navMini(x, y); kick(); return; }
      var h = self.hitAt(x, y);
      if (h) {
        var s = project(h);
        G.drag = { kind: 'node', node: h, x: x, y: y, gx: x - s.x, gy: y - s.y, nx: h.x, ny: h.y, nz: h.z, from: [h.x, h.y, h.z], on: false, create: ev.shiftKey };
        if (el) el.style.cursor = 'grabbing';
      } else if (ev.shiftKey) { G.drag = { kind: 'brush', x: x, y: y, on: false }; G.brush = null; }
      else if (ev.altKey) { G.drag = { kind: 'lasso', x: x, y: y, on: false }; G.lasso = [[x, y]]; }
      else if (G.mode === '2d' || ev.button === 1 || ev.ctrlKey || ev.metaKey) { G.drag = { kind: 'pan', x: x, y: y, px: G.px, py: G.py, on: false }; }
      else { G.drag = { kind: 'rotate', x: x, y: y, yaw: G.yaw, pitch: G.pitch, on: false }; if (el) el.style.cursor = 'grabbing'; }
    }
    function onUp(ev) {
      var d = G.drag; G.drag = null; setMoving(false); G.snap = null;
      if (el) el.style.cursor = G.mode === '2d' ? 'default' : 'grab';
      if (!d) return;
      var p = local(ev), x = p[0], y = p[1];
      if (d.kind === 'node') {
        if (d.on) {
          if (d.create && d.over) { d.node.x = d.from[0]; d.node.y = d.from[1]; d.node.z = d.from[2]; d.node.pinned = false; emit('link', d.node.id, d.over); }
          else { push({ kind: 'move', id: d.node.id, from: d.from, to: [d.node.x, d.node.y, d.node.z] }); emit('change', 'move'); }
        } else self.select([d.node.id], ev.shiftKey || ev.ctrlKey || ev.metaKey);
      } else if (d.kind === 'brush' && d.on && G.brush) {
        var b = G.brush, x0 = Math.min(b[0], b[2]), x1 = Math.max(b[0], b[2]), y0 = Math.min(b[1], b[3]), y1 = Math.max(b[1], b[3]);
        self.select(G.nodes.filter(function (n) { return !n.hidden && n.sx >= x0 && n.sx <= x1 && n.sy >= y0 && n.sy <= y1; }).map(function (n) { return n.id; }), ev.ctrlKey || ev.metaKey);
        G.brush = null;
      } else if (d.kind === 'lasso' && d.on && G.lasso && G.lasso.length > 2) {
        var poly = G.lasso;
        self.select(G.nodes.filter(function (n) { return !n.hidden && inside(poly, n.sx, n.sy); }).map(function (n) { return n.id; }), ev.ctrlKey || ev.metaKey);
        G.lasso = null;
      } else if (!d.on && (d.kind === 'pan' || d.kind === 'rotate')) { G.brush = null; G.lasso = null; self.select([]); }
      G.brush = null; G.lasso = null;
      void x; void y;
      kick();
    }
    function inside(poly, x, y) {
      var c = false;
      for (var i = 0, j = poly.length - 1; i < poly.length; j = i++) { var xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1]; if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c; }
      return c;
    }
    function onDbl(ev) {
      var p = local(ev), h = self.hitAt(p[0], p[1]);
      if (O.within && !O.within(p[0], p[1])) return;
      if (!h) { var pt = self.partAt(p[0], p[1]); if (pt && pt.part === 'label' && on.rename) { on.rename(pt.node.id, ev.clientX, ev.clientY); return; } }
      if (!h) { self.fit(); return; }
      if (h.isGroup) { self.expand(h.group); return; }
      if ((ev.shiftKey) && h.group) { self.collapse(h.group); return; }
      self.focus(h.id);
    }
    function onWheel(ev) {
      var p = local(ev);
      if (O.within && !O.within(p[0], p[1])) return;
      ev.preventDefault();
      var dy = ev.deltaMode === 1 ? ev.deltaY * 33 : ev.deltaY, dx = ev.deltaMode === 1 ? ev.deltaX * 33 : ev.deltaX;
      var scroll = O.wheel === 'scroll' ? !ev.ctrlKey : ev.ctrlKey && false;
      if (O.wheel === 'scroll' && !ev.ctrlKey && !ev.metaKey) { G.px -= dx; G.py -= dy; kick(); return; }   // ScrollCanvas
      if (ev.shiftKey && !dx) { G.px -= dy; kick(); return; }
      var f = Math.exp(-dy * 0.0012), z2 = clamp(G.zoom * f, O.minZoom, O.maxZoom), k = z2 / G.zoom;
      // zoom about the pointer (ZoomCanvas)
      G.px = (G.px + G.cx - p[0]) * k + p[0] - G.cx; G.py = (G.py + G.cy - p[1]) * k + p[1] - G.cy; G.zoom = z2;
      void scroll;
      kick();
    }
    var pinch = null;
    function onTouchStart(ev) { if (ev.touches.length === 2) { pinch = { d: dist(ev), z: G.zoom }; G.drag = null; } }
    function onTouchMove(ev) { if (ev.touches.length === 2 && pinch) { ev.preventDefault(); G.zoom = clamp(pinch.z * dist(ev) / pinch.d, O.minZoom, O.maxZoom); kick(); } }
    function dist(ev) { var a = ev.touches[0], b = ev.touches[1]; return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY); }
    function onContext(ev) { if (!on.context) return; var p = local(ev); if (O.within && !O.within(p[0], p[1])) return; ev.preventDefault(); var h = self.hitAt(p[0], p[1]); on.context(h ? h.id : null, ev.clientX, ev.clientY, ev); }
    // keys: arrows walk the selection to the nearest node that way; Enter focuses; Escape clears; +/-; 0 fits; r resets; z/Z undo/redo; p pins
    self.key = function (ev) {
      var k = ev.key, sel = G.selected[0] && G.byId[G.selected[0]];
      if (k === 'Escape') { if (G.selected.length) { self.select([]); return true; } return false; }
      if (k === '+' || k === '=') { G.zoom = clamp(G.zoom * 1.15, O.minZoom, O.maxZoom); kick(); return true; }
      if (k === '-' || k === '_') { G.zoom = clamp(G.zoom / 1.15, O.minZoom, O.maxZoom); kick(); return true; }
      if (k === '0') { self.fit(); return true; }
      if (k === 'r' || k === 'R') { self.reset(); return true; }
      if ((k === 'z' || k === 'Z') && (ev.metaKey || ev.ctrlKey)) { if (ev.shiftKey) self.redo(); else self.undo(); kick(); return true; }
      if (k === 'Enter' && sel) { self.focus(sel.id); return true; }
      if ((k === 'p' || k === 'P') && sel) { self.pin(sel.id); kick(); return true; }
      if (/^Arrow/.test(k)) {
        if (!sel) { var first = G.nodes.filter(function (n) { return !n.hidden; })[0]; if (first) self.select([first.id]); return true; }
        var dx = k === 'ArrowRight' ? 1 : k === 'ArrowLeft' ? -1 : 0, dy = k === 'ArrowDown' ? 1 : k === 'ArrowUp' ? -1 : 0, best = null, bd = 1e9;
        G.nodes.forEach(function (n) {
          if (n === sel || n.hidden) return;
          var ex = n.sx - sel.sx, ey = n.sy - sel.sy, along = ex * dx + ey * dy; if (along <= 0) return;
          var side = Math.abs(ex * dy - ey * dx), d = along + side * 2.2; if (d < bd) { bd = d; best = n; }
        });
        if (best) self.select([best.id]);
        return true;
      }
      return false;
    };
    self.attach = function (element) {
      el = element; G.el = el;
      el.addEventListener('pointermove', onMove); el.addEventListener('pointerdown', onDown); el.addEventListener('pointerup', onUp);
      el.addEventListener('pointercancel', function () { G.drag = null; setMoving(false); });
      el.addEventListener('pointerleave', function () { if (!G.drag) { G.pointer = null; self.setHover(null); if (on.tooltip) on.tooltip(null); kick(); } });
      el.addEventListener('dblclick', onDbl);
      el.addEventListener('wheel', onWheel, { passive: false });
      el.addEventListener('touchstart', onTouchStart, { passive: true });
      el.addEventListener('touchmove', onTouchMove, { passive: false });
      el.addEventListener('contextmenu', onContext);
      return self;
    };
    self.destroy = function () {
      if (!el) return;
      el.removeEventListener('pointermove', onMove); el.removeEventListener('pointerdown', onDown); el.removeEventListener('pointerup', onUp);
      el.removeEventListener('dblclick', onDbl); el.removeEventListener('wheel', onWheel); el.removeEventListener('contextmenu', onContext);
      el = null;
    };

    // ---------------------------------------------------------------- frames
    var onFrame = cfg.onFrame || null;
    function kick() { if (onFrame && !G.raf) G.raf = requestAnimationFrame(function (t) { G.raf = 0; if (G.anim) { G.anim(t); if (G.anim) kick(); } onFrame(t); }); }
    self.kick = kick;
    self.setOnFrame = function (fn) { onFrame = fn; };
    self.step = function (now) { var a = false; if (G.anim) { G.anim(now); a = !!G.anim; } return a || !!G.morph || G.selected.length > 0; };
    // the overlays the engine owns: a brush rectangle, a lasso, snap guides, a hull round the selection, the grid
    self.drawOverlays = function (ctx, ink) {
      ink = ink || '236,232,228';
      if (O.grid && G.mode === '2d') {
        var step = G.R * G.zoom * 0.25, ox = ((G.cx + G.px) % step + step) % step, oy = ((G.cy + G.py) % step + step) % step;
        ctx.strokeStyle = 'rgba(' + ink + ',.05)'; ctx.lineWidth = 1; ctx.beginPath();
        for (var gx = ox; gx < G.W; gx += step) { ctx.moveTo(gx, 0); ctx.lineTo(gx, G.H); }
        for (var gy = oy; gy < G.H; gy += step) { ctx.moveTo(0, gy); ctx.lineTo(G.W, gy); }
        ctx.stroke();
      }
      if (O.hull && G.selected.length > 1) {
        var hull = self.hullOf(G.selected, 16);
        if (hull.length > 2) { ctx.beginPath(); hull.forEach(function (p, i) { if (i) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]); }); ctx.closePath(); ctx.fillStyle = 'rgba(' + ink + ',.04)'; ctx.fill(); ctx.strokeStyle = 'rgba(' + ink + ',.35)'; ctx.setLineDash([3, 4]); ctx.stroke(); ctx.setLineDash([]); }
      }
      if (G.brush) { var b = G.brush; ctx.strokeStyle = 'rgba(' + ink + ',.6)'; ctx.setLineDash([3, 3]); ctx.strokeRect(Math.min(b[0], b[2]) + 0.5, Math.min(b[1], b[3]) + 0.5, Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1])); ctx.setLineDash([]); }
      if (G.lasso && G.lasso.length > 1) { ctx.beginPath(); G.lasso.forEach(function (p, i) { if (i) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]); }); ctx.strokeStyle = 'rgba(' + ink + ',.6)'; ctx.setLineDash([3, 3]); ctx.stroke(); ctx.setLineDash([]); }
      if (G.snap && G.drag) { ctx.strokeStyle = 'rgba(' + ink + ',.3)'; ctx.setLineDash([2, 4]); ctx.beginPath(); if (G.snap.x != null) { ctx.moveTo(G.snap.x, 0); ctx.lineTo(G.snap.x, G.H); } if (G.snap.y != null) { ctx.moveTo(0, G.snap.y); ctx.lineTo(G.W, G.snap.y); } ctx.stroke(); ctx.setLineDash([]); }
      if (O.fisheye && G.pointer && !G.moving) { ctx.strokeStyle = 'rgba(' + ink + ',.18)'; ctx.beginPath(); ctx.arc(G.pointer.x, G.pointer.y, O.fisheyeR, 0, TAU); ctx.stroke(); }
      if (O.edgeLens && G.pointer) { ctx.strokeStyle = 'rgba(' + ink + ',.14)'; ctx.setLineDash([2, 3]); ctx.beginPath(); ctx.arc(G.pointer.x, G.pointer.y, O.edgeLensR, 0, TAU); ctx.stroke(); ctx.setLineDash([]); }
    };
    self.set = function (k, v) { O[k] = v; kick(); };
    self.pulse = function (now) { return 0.5 + 0.5 * Math.sin((now || performance.now()) / 420); };   // a slow breath for what is selected
    self.legend = function () { return Object.keys(G.groups).map(function (k) { return { group: k, n: G.groups[k].length, collapsed: !!G.collapsed[k] }; }); };
    return self;
  }
  return { create: create, version: '0.2.0' };
});
