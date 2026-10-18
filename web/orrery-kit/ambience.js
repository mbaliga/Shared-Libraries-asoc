/* asoc ambience — background sound and prompt-made music from a few oscillators and shaped noise. No samples, no
   network, nothing fetched: everything here is synthesised on the device, so it is the same in every app and
   costs nothing to carry. Two parts:

   ASOC.ambience.create({ store })      → { set(kind), kind(), level(v), kinds }
     kinds: 'rain' 'storm' 'fire' 'wind' 'forest' 'sea' 'off'
     Filtered noise with slow modulation and random events (drops, pops, gusts, rumbles, birds, waves).

   ASOC.muse.create({ store })          → { play(prompt), stop(), playing(), level(v), describe(prompt) }
     A procedural composer: the prompt's words pick tempo, mode, instruments and patterns ("heroic questing",
     "fast techno race", "quiet rain piano", "menacing drone"). It is a rule-based engine, not a model — describe()
     returns the reading it made of the prompt so the host can show it. A model that writes music belongs to the
     routing engine, with the user's own key, later; this plays in the meantime and never phones anyone.

   Both honour the page's audio context lazily (created on first user gesture) and a stored level. */
(function (root) {
  'use strict';
  var ASOC = root.ASOC = root.ASOC || {};
  function ctxOf() { var AC = root.AudioContext || root.webkitAudioContext; if (!AC) return null; if (!ASOC._actx) ASOC._actx = new AC(); if (ASOC._actx.state === 'suspended') ASOC._actx.resume(); return ASOC._actx; }
  function noiseBuf(ac, colour) {
    var n = ac.sampleRate * 2, b = ac.createBuffer(1, n, ac.sampleRate), d = b.getChannelData(0), last = 0, b0 = 0, b1 = 0, b2 = 0;
    for (var i = 0; i < n; i++) {
      var w = Math.random() * 2 - 1;
      if (colour === 'brown') { last = (last + 0.02 * w) / 1.02; d[i] = last * 3.5; }
      else if (colour === 'pink') { b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913; d[i] = (b0 + b1 + b2 + w * 0.1848) * 0.2; }
      else d[i] = w;
    }
    return b;
  }
  function src(ac, colour) { var s = ac.createBufferSource(); s.buffer = noiseBuf(ac, colour); s.loop = true; return s; }
  function lfo(ac, hz, depth, target) { var o = ac.createOscillator(), g = ac.createGain(); o.frequency.value = hz; g.gain.value = depth; o.connect(g); g.connect(target); o.start(); return o; }

  // ---------------------------------------------------------------- ambience
  function createAmbience(cfg) {
    cfg = cfg || {};
    var store = cfg.store || { get: function (k, d) { return d; }, set: function () {} };
    var A = { kind: store.get('ambience', 'off'), level: store.get('ambienceLevel', 0.5), nodes: [], master: null, timers: [], ac: null };
    function stop() { A.timers.forEach(clearTimeout); A.timers = []; A.nodes.forEach(function (n) { try { n.stop && n.stop(); n.disconnect(); } catch (e) {} }); A.nodes = []; if (A.master) { try { A.master.disconnect(); } catch (e) {} A.master = null; } }
    function later(fn, ms) { var t = setTimeout(fn, ms); A.timers.push(t); return t; }
    function master(ac) { var g = ac.createGain(); g.gain.value = 0; g.connect(ac.destination); g.gain.linearRampToValueAtTime(A.level * 0.6, ac.currentTime + 2.5); A.master = g; return g; }
    function band(ac, colour, type, hz, q, gain, out) { var s = src(ac, colour), f = ac.createBiquadFilter(), g = ac.createGain(); f.type = type; f.frequency.value = hz; f.Q.value = q || 0.7; g.gain.value = gain; s.connect(f); f.connect(g); g.connect(out); s.start(); A.nodes.push(s, f, g); return { s: s, f: f, g: g }; }
    function blip(ac, out, hz, dur, gain, type) { var o = ac.createOscillator(), g = ac.createGain(); o.type = type || 'sine'; o.frequency.value = hz; g.gain.value = 0; o.connect(g); g.connect(out); var t = ac.currentTime; g.gain.linearRampToValueAtTime(gain, t + 0.005); g.gain.exponentialRampToValueAtTime(0.0001, t + dur); o.start(t); o.stop(t + dur + 0.02); }
    function burst(ac, out, colour, hz, dur, gain) { var s = src(ac, colour), f = ac.createBiquadFilter(), g = ac.createGain(); f.type = 'bandpass'; f.frequency.value = hz; f.Q.value = 1.2; g.gain.value = 0; s.connect(f); f.connect(g); g.connect(out); var t = ac.currentTime; g.gain.linearRampToValueAtTime(gain, t + dur * 0.2); g.gain.exponentialRampToValueAtTime(0.0001, t + dur); s.start(t); s.stop(t + dur + 0.05); }
    var KINDS = {
      off: function () {},
      rain: function (ac, out) {
        var r = band(ac, 'pink', 'highpass', 900, 0.5, 0.35, out); lfo(ac, 0.07, 0.08, r.g.gain);
        band(ac, 'brown', 'lowpass', 300, 0.5, 0.25, out);
        (function drop() { blip(ac, out, 2400 + Math.random() * 3000, 0.03 + Math.random() * 0.05, 0.04 + Math.random() * 0.05, 'triangle'); later(drop, 40 + Math.random() * 180); })();
      },
      storm: function (ac, out) {
        KINDS.rain(ac, out);
        var w = band(ac, 'pink', 'bandpass', 420, 0.6, 0.3, out); lfo(ac, 0.05, 240, w.f.frequency); lfo(ac, 0.11, 0.12, w.g.gain);
        (function thunder() { burst(ac, out, 'brown', 70 + Math.random() * 60, 2.5 + Math.random() * 4, 0.9); later(thunder, 9000 + Math.random() * 25000); })();
      },
      fire: function (ac, out) {
        var h = band(ac, 'brown', 'lowpass', 220, 0.4, 0.5, out); lfo(ac, 0.19, 0.12, h.g.gain);
        band(ac, 'pink', 'bandpass', 1800, 0.8, 0.07, out);
        (function crackle() { var n = 1 + Math.floor(Math.random() * 3); for (var i = 0; i < n; i++) later(function () { burst(ac, out, 'white', 2500 + Math.random() * 4000, 0.02 + Math.random() * 0.06, 0.25 + Math.random() * 0.3); }, Math.random() * 120); later(crackle, 120 + Math.random() * 900); })();
      },
      wind: function (ac, out) {
        var w = band(ac, 'pink', 'bandpass', 500, 0.9, 0.35, out); lfo(ac, 0.08, 320, w.f.frequency); lfo(ac, 0.13, 0.15, w.g.gain);
        var w2 = band(ac, 'pink', 'bandpass', 1200, 2.5, 0.08, out); lfo(ac, 0.21, 700, w2.f.frequency);
      },
      forest: function (ac, out) {
        var w = band(ac, 'pink', 'bandpass', 700, 0.8, 0.12, out); lfo(ac, 0.06, 300, w.f.frequency);
        (function bird() { var f0 = 1800 + Math.random() * 2200, n = 2 + Math.floor(Math.random() * 4); for (var i = 0; i < n; i++) later(function () { blip(ac, out, f0 * (0.9 + Math.random() * 0.25), 0.08 + Math.random() * 0.1, 0.05, 'sine'); }, i * (90 + Math.random() * 80)); later(bird, 1500 + Math.random() * 6000); })();
        (function leaves() { burst(ac, out, 'pink', 3000, 0.6 + Math.random() * 1.2, 0.1); later(leaves, 2000 + Math.random() * 7000); })();
      },
      sea: function (ac, out) {
        var w = band(ac, 'brown', 'lowpass', 500, 0.5, 0.0, out), s = band(ac, 'pink', 'highpass', 1500, 0.5, 0.0, out);
        (function wave() { var t = ac.currentTime, d = 5 + Math.random() * 4; w.g.gain.cancelScheduledValues(t); w.g.gain.setValueAtTime(0.05, t); w.g.gain.linearRampToValueAtTime(0.5, t + d * 0.45); w.g.gain.linearRampToValueAtTime(0.05, t + d); s.g.gain.cancelScheduledValues(t); s.g.gain.setValueAtTime(0.0, t); s.g.gain.linearRampToValueAtTime(0.18, t + d * 0.5); s.g.gain.linearRampToValueAtTime(0.0, t + d); later(wave, d * 1000 + Math.random() * 1500); })();
      }
    };
    function start() {
      stop(); if (A.kind === 'off') return;
      var ac = ctxOf(); if (!ac) return; A.ac = ac;
      KINDS[A.kind](ac, master(ac));
    }
    return {
      kinds: Object.keys(KINDS),
      kind: function () { return A.kind; },
      set: function (k) { A.kind = KINDS[k] ? k : 'off'; store.set('ambience', A.kind); start(); },
      level: function (v) { if (v == null) return A.level; A.level = Math.max(0, Math.min(1, v)); store.set('ambienceLevel', A.level); if (A.master) A.master.gain.linearRampToValueAtTime(A.level * 0.6, A.ac.currentTime + 0.2); return A.level; },
      stop: stop, resume: function () { if (A.kind !== 'off' && !A.master) start(); }
    };
  }

  // ---------------------------------------------------------------- muse: music from a prompt, by rule
  var MODES = { ionian: [0, 2, 4, 5, 7, 9, 11], dorian: [0, 2, 3, 5, 7, 9, 10], aeolian: [0, 2, 3, 5, 7, 8, 10], lydian: [0, 2, 4, 6, 7, 9, 11], phrygian: [0, 1, 3, 5, 7, 8, 10], pent: [0, 2, 4, 7, 9] };
  var WORDS = [
    // [regex, patch] — the first wins for each field; later ones fill what is still unset
    [/techno|rave|race|racing|fast|drive|chase|sprint|hurry|speed/, { bpm: 140, mode: 'phrygian', drums: 'four', bass: 'saw', lead: 'arp', pad: 'none', mood: 'fast techno' }],
    [/hero|heroic|quest|epic|adventure|march|brave|triumph/, { bpm: 92, mode: 'dorian', drums: 'march', bass: 'sub', lead: 'brass', pad: 'strings', mood: 'heroic questing' }],
    [/calm|rain|quiet|soft|sleep|lofi|lo-fi|study|focus|ambient|still/, { bpm: 68, mode: 'lydian', drums: 'brush', bass: 'none', lead: 'piano', pad: 'warm', mood: 'calm, for focus' }],
    [/dark|menac|dread|tense|horror|danger|drone|ominous/, { bpm: 60, mode: 'phrygian', drums: 'none', bass: 'drone', lead: 'none', pad: 'cold', mood: 'dark drone' }],
    [/happy|bright|sunny|play|joy|bounce|pop/, { bpm: 118, mode: 'ionian', drums: 'pop', bass: 'pluck', lead: 'arp', pad: 'warm', mood: 'bright and bouncy' }],
    [/sad|melanch|loss|grief|slow|blue/, { bpm: 64, mode: 'aeolian', drums: 'none', bass: 'sub', lead: 'piano', pad: 'strings', mood: 'slow and blue' }],
    [/space|cosmic|star|nebula|orbit|float/, { bpm: 76, mode: 'lydian', drums: 'none', bass: 'sub', lead: 'arp', pad: 'cold', mood: 'floating' }],
    [/jazz|swing|smoky|lounge/, { bpm: 104, mode: 'dorian', drums: 'brush', bass: 'walk', lead: 'piano', pad: 'none', mood: 'smoky swing' }],
    [/folk|village|tavern|pent|east|zen/, { bpm: 96, mode: 'pent', drums: 'hand', bass: 'pluck', lead: 'pluck', pad: 'none', mood: 'a village tune' }]
  ];
  function readPrompt(p) {
    var out = {}, s = String(p || '').toLowerCase(), hits = [];
    WORDS.forEach(function (w) { if (w[0].test(s)) { hits.push(w[1].mood); Object.keys(w[1]).forEach(function (k) { if (out[k] == null) out[k] = w[1][k]; }); } });
    var m = s.match(/(\d{2,3})\s*bpm/); if (m) out.bpm = +m[1];
    if (/minor/.test(s)) out.mode = 'aeolian'; if (/major/.test(s)) out.mode = 'ionian';
    if (/no drums|without drums|drumless/.test(s)) out.drums = 'none';
    var d = { bpm: 96, mode: 'dorian', drums: 'light', bass: 'sub', lead: 'arp', pad: 'warm', mood: 'a steady mood' };
    Object.keys(d).forEach(function (k) { if (out[k] == null) out[k] = d[k]; });
    out.read = hits.length ? hits.join(', ') : 'nothing I know a word for, so a steady default';
    return out;
  }
  function createMuse(cfg) {
    cfg = cfg || {};
    var store = cfg.store || { get: function (k, d) { return d; }, set: function () {} };
    var M = { on: false, level: store.get('museLevel', 0.5), ac: null, master: null, timer: 0, step: 0, plan: null, nodes: [] };
    function stop() { clearTimeout(M.timer); M.on = false; if (M.master) { try { M.master.gain.linearRampToValueAtTime(0, M.ac.currentTime + 0.6); } catch (e) {} var g = M.master; setTimeout(function () { try { g.disconnect(); } catch (e) {} }, 800); M.master = null; } M.nodes.forEach(function (n) { try { n.stop(); } catch (e) {} }); M.nodes = []; }
    function voice(ac, out, type, hz, t, dur, gain, attack, filt) {
      var o = ac.createOscillator(), g = ac.createGain(), f = ac.createBiquadFilter();
      o.type = type; o.frequency.value = hz; f.type = 'lowpass'; f.frequency.value = filt || 2400; f.Q.value = 0.8;
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(gain, t + (attack || 0.01)); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      o.connect(f); f.connect(g); g.connect(out); o.start(t); o.stop(t + dur + 0.05);
    }
    function drum(ac, out, kind, t) {
      if (kind === 'kick') { var o = ac.createOscillator(), g = ac.createGain(); o.frequency.setValueAtTime(150, t); o.frequency.exponentialRampToValueAtTime(42, t + 0.12); g.gain.setValueAtTime(0.9, t); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.28); o.connect(g); g.connect(out); o.start(t); o.stop(t + 0.3); }
      else { var s = src(ac, kind === 'snare' ? 'pink' : 'white'), f = ac.createBiquadFilter(), g2 = ac.createGain(); f.type = kind === 'hat' ? 'highpass' : 'bandpass'; f.frequency.value = kind === 'hat' ? 7000 : 1800; g2.gain.setValueAtTime(kind === 'hat' ? 0.18 : 0.45, t); g2.gain.exponentialRampToValueAtTime(0.0001, t + (kind === 'hat' ? 0.05 : 0.16)); s.connect(f); f.connect(g2); g2.connect(out); s.start(t); s.stop(t + 0.2); }
    }
    function hz(root, deg, oct, mode) { var sc = MODES[mode] || MODES.dorian, n = sc[((deg % sc.length) + sc.length) % sc.length] + 12 * (Math.floor(deg / sc.length) + oct); return root * Math.pow(2, n / 12); }
    function schedule() {
      if (!M.on) return;
      var ac = M.ac, P = M.plan, beat = 60 / P.bpm, t = M.next, step = M.step, out = M.master;
      var bar = Math.floor(step / 16), s16 = step % 16, chord = [0, 3, 4, 5][bar % 4] * 0;   // progression by degree: I, iv-ish, V-ish, vi-ish
      var prog = [0, 5, 3, 4], deg0 = prog[bar % 4];
      // drums
      if (P.drums === 'four') { if (s16 % 4 === 0) drum(ac, out, 'kick', t); if (s16 % 2 === 1) drum(ac, out, 'hat', t); if (s16 % 8 === 4) drum(ac, out, 'snare', t); }
      else if (P.drums === 'march') { if (s16 % 4 === 0 || s16 === 6 || s16 === 10) drum(ac, out, 'kick', t); if (s16 % 8 === 4) drum(ac, out, 'snare', t); if (s16 % 4 === 2) drum(ac, out, 'hat', t); }
      else if (P.drums === 'pop') { if (s16 === 0 || s16 === 8 || s16 === 11) drum(ac, out, 'kick', t); if (s16 % 8 === 4) drum(ac, out, 'snare', t); if (s16 % 2 === 0) drum(ac, out, 'hat', t); }
      else if (P.drums === 'brush' || P.drums === 'light' || P.drums === 'hand') { if (s16 % 8 === 0) drum(ac, out, 'kick', t); if (s16 % 4 === 2 && P.drums !== 'brush') drum(ac, out, 'hat', t); if (P.drums === 'hand' && s16 % 8 === 4) drum(ac, out, 'snare', t); }
      // bass
      var rootHz = 110 * Math.pow(2, (P.key || 0) / 12);
      if (P.bass === 'saw' && s16 % 2 === 0) voice(ac, out, 'sawtooth', hz(rootHz, deg0, -1, P.mode), t, beat * 0.45, 0.32, 0.005, 900);
      else if (P.bass === 'sub' && s16 % 8 === 0) voice(ac, out, 'sine', hz(rootHz, deg0, -1, P.mode), t, beat * 1.9, 0.5, 0.02, 400);
      else if (P.bass === 'pluck' && s16 % 4 === 0) voice(ac, out, 'triangle', hz(rootHz, deg0, 0, P.mode), t, beat * 0.6, 0.35, 0.005, 1400);
      else if (P.bass === 'walk' && s16 % 4 === 0) voice(ac, out, 'triangle', hz(rootHz, deg0 + [0, 2, 4, 5][(s16 / 4) | 0], 0, P.mode), t, beat * 0.8, 0.32, 0.01, 1200);
      else if (P.bass === 'drone' && s16 === 0) voice(ac, out, 'sawtooth', hz(rootHz, 0, -1, P.mode), t, beat * 16, 0.22, 2, 500);
      // pad: a chord held across the bar
      if (P.pad !== 'none' && s16 === 0) [0, 2, 4].forEach(function (d) { voice(ac, out, P.pad === 'cold' ? 'sine' : P.pad === 'strings' ? 'sawtooth' : 'triangle', hz(rootHz, deg0 + d, 1, P.mode), t, beat * 4 * 0.98, P.pad === 'strings' ? 0.07 : 0.12, beat * 1.2, P.pad === 'cold' ? 1600 : 1100); });
      // lead
      if (P.lead === 'arp' && s16 % 2 === 0) voice(ac, out, 'square', hz(rootHz, deg0 + [0, 2, 4, 7, 4, 2][(s16 / 2) % 6], 2, P.mode), t, beat * 0.4, 0.09, 0.005, 2600);
      else if (P.lead === 'brass' && (s16 === 0 || s16 === 6 || s16 === 12)) voice(ac, out, 'sawtooth', hz(rootHz, deg0 + [0, 4, 2][(s16 / 6) | 0], 2, P.mode), t, beat * 1.4, 0.1, 0.08, 1800);
      else if (P.lead === 'piano' && (s16 % 4 === 0 || (s16 % 8 === 6 && Math.random() < 0.6))) voice(ac, out, 'triangle', hz(rootHz, deg0 + [0, 2, 4, 6][Math.floor(Math.random() * 4)], 2, P.mode), t, beat * 1.6, 0.14, 0.004, 3000);
      else if (P.lead === 'pluck' && s16 % 2 === 0 && Math.random() < 0.7) voice(ac, out, 'triangle', hz(rootHz, deg0 + Math.floor(Math.random() * 5), 2, P.mode), t, beat * 0.5, 0.12, 0.003, 2500);
      M.step++; M.next += beat / 4;
      var ahead = (M.next - ac.currentTime) * 1000;
      M.timer = setTimeout(schedule, Math.max(10, ahead - 120));
    }
    return {
      describe: readPrompt,
      playing: function () { return M.on; },
      play: function (prompt) {
        stop(); var ac = ctxOf(); if (!ac) return null;
        M.ac = ac; M.plan = readPrompt(prompt); M.plan.key = Math.floor(Math.random() * 5) - 2; M.on = true; M.step = 0; M.next = ac.currentTime + 0.1;
        M.master = ac.createGain(); M.master.gain.value = 0; M.master.connect(ac.destination); M.master.gain.linearRampToValueAtTime(M.level * 0.5, ac.currentTime + 1.5);
        store.set('musePrompt', String(prompt || ''));
        schedule(); return M.plan;
      },
      stop: stop,
      level: function (v) { if (v == null) return M.level; M.level = Math.max(0, Math.min(1, v)); store.set('museLevel', M.level); if (M.master) M.master.gain.linearRampToValueAtTime(M.level * 0.5, M.ac.currentTime + 0.2); return M.level; }
    };
  }
  ASOC.ambience = { create: createAmbience, version: '0.1.0' };
  ASOC.muse = { create: createMuse, version: '0.1.0', read: readPrompt };
  if (typeof module !== 'undefined' && module.exports) module.exports = { ambience: ASOC.ambience, muse: ASOC.muse };
})(typeof window !== 'undefined' ? window : globalThis);
