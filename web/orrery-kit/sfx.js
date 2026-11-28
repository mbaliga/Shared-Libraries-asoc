/* asoc sfx — the sounds of moving through an interface, made on the device.
   Square and triangle waves with fast envelopes, in the manner of a games console. Nothing is loaded.
   API: var s = ASOC.sfx.create({ store }) → s.play(kind), s.on(), s.setOn(v), s.vol(), s.setVol(0..100), s.define(kind, fn(note))
   Kinds: move, hover, tick, select, back, open, close, done, nope. `store` is { get(key, fallback), set(key, value) } or omitted. */
(function (root, factory) {
  var api = factory();
  root.ASOC = root.ASOC || {}; root.ASOC.sfx = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';
  function create(opts) {
    opts = opts || {};
    var store = opts.store || { get: function (k, f) { return f; }, set: function () {} };
    var ctx = null, master = null, last = 0, lastKind = '';
    var on = store.get('sfx', true), vol = store.get('sfxVol', 40);
    function ensure() {
      if (ctx) return ctx;
      try {
        var AC = (typeof window !== 'undefined') && (window.AudioContext || window.webkitAudioContext);
        if (!AC) return null;
        ctx = new AC(); master = ctx.createGain(); master.gain.value = vol / 100 * 0.25; master.connect(ctx.destination);
      } catch (e) { ctx = null; }
      return ctx;
    }
    function note(type, f, at, len, level, slideTo) {
      var c = ensure(); if (!c) return;
      var o = c.createOscillator(), g = c.createGain(), t = c.currentTime + at;
      o.type = type; o.frequency.setValueAtTime(f, t);
      if (slideTo) o.frequency.exponentialRampToValueAtTime(slideTo, t + len);
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(level, t + 0.006);
      g.gain.exponentialRampToValueAtTime(0.0001, t + len);
      o.connect(g); g.connect(master);
      o.start(t); o.stop(t + len + 0.02);
    }
    var SOUNDS = {
      move: function () { note('square', 1480, 0, 0.045, 0.22); },
      hover: function () { note('square', 1760, 0, 0.03, 0.09); },
      tick: function () { note('triangle', 880, 0, 0.06, 0.4); },
      select: function () { note('square', 659, 0, 0.06, 0.3); note('square', 988, 0.06, 0.08, 0.3); },
      back: function () { note('square', 988, 0, 0.06, 0.26); note('square', 659, 0.06, 0.08, 0.26); },
      open: function () { note('triangle', 330, 0, 0.14, 0.35, 660); },
      close: function () { note('triangle', 660, 0, 0.12, 0.3, 330); },
      done: function () { note('square', 523, 0, 0.07, 0.3); note('square', 659, 0.07, 0.07, 0.3); note('square', 784, 0.14, 0.12, 0.34); },
      nope: function () { note('square', 110, 0, 0.09, 0.3); }
    };
    var GAP = { hover: 70, move: 40 };
    var api = {
      play: function (kind) {
        if (!on || !SOUNDS[kind]) return;
        var now = (typeof performance !== 'undefined' ? performance.now() : Date.now());
        if (now - last < (GAP[kind] || 30) && (kind === lastKind || kind === 'hover')) return;
        last = now; lastKind = kind;
        try { if (ctx && ctx.state === 'suspended') ctx.resume(); SOUNDS[kind](); } catch (e) { /* silence is fine */ }
      },
      on: function () { return on; },
      setOn: function (v) { on = !!v; store.set('sfx', on); if (on) api.play('select'); },
      vol: function () { return vol; },
      setVol: function (v) { vol = Math.max(0, Math.min(100, v)); store.set('sfxVol', vol); if (master) master.gain.value = vol / 100 * 0.25; api.play('tick'); },
      define: function (kind, fn) { SOUNDS[kind] = function () { fn(note); }; },
      ensure: ensure
    };
    if (typeof window !== 'undefined') ['pointerdown', 'keydown', 'touchstart'].forEach(function (ev) { window.addEventListener(ev, function () { if (on) ensure(); }, { passive: true, once: true }); });
    return api;
  }
  return { create: create, version: '0.1.0' };
});
