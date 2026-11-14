// ==UserScript==
// @name         Floating Subtitle Sync
// @namespace    floating-subtitle-player
// @version      1.0
// @description  Ngirim posisi audio/video di halaman ini ke Floating Subtitle Player (cuma ke 127.0.0.1 di komputermu sendiri) biar subtitle auto-sync.
// @match        *://*/*
// @run-at       document-start
// @grant        GM_xmlhttpRequest
// @grant        unsafeWindow
// @connect      127.0.0.1
// @connect      localhost
// ==/UserScript==

(function () {
  'use strict';

  const BASE = 'http://127.0.0.1:41730';
  const ID = Math.random().toString(36).slice(2, 10);   // satu id per frame/halaman
  const PAGE = (typeof unsafeWindow !== 'undefined' && unsafeWindow) || window;
  const EVENTS = ['play', 'playing', 'pause', 'seeking', 'seeked', 'waiting', 'ratechange',
                  'ended', 'loadedmetadata', 'durationchange', 'emptied', 'volumechange'];

  const refs = [];                  // WeakRef ke semua <audio>/<video> yang kepantau
  const seen = new WeakSet();
  const touched = new WeakMap();    // elemen -> waktu event terakhir
  let lastEl = null, lastIntent = false, nextOk = 0, backoff = 0, beat = 0;

  // ---------------------------------------------------------------- kirim ke program
  function send(msg, force) {
    if (!force && Date.now() < nextOk) return;
    const body = JSON.stringify(msg);
    const ok = function () { backoff = 0; nextOk = 0; };
    const fail = function () { backoff = Math.min((backoff || 500) * 2, 5000); nextOk = Date.now() + backoff; };
    const gmx = (typeof GM_xmlhttpRequest === 'function') ? GM_xmlhttpRequest
              : (typeof GM !== 'undefined' && GM && GM.xmlHttpRequest) ? GM.xmlHttpRequest.bind(GM) : null;
    try {
      if (gmx) {
        gmx({ method: 'POST', url: BASE + '/sync', data: body, headers: { 'Content-Type': 'text/plain' },
              timeout: 3000, onload: ok, onerror: fail, ontimeout: fail });
      } else {
        fetch(BASE + '/sync', { method: 'POST', body: body, mode: 'no-cors',
                                headers: { 'Content-Type': 'text/plain' } }).then(ok, fail);
      }
    } catch (e) { fail(); }
  }

  // ---------------------------------------------------------------- pilih elemen media
  function track(el) {
    if (!el || seen.has(el)) return;
    seen.add(el);
    refs.push(new WeakRef(el));
    EVENTS.forEach(function (ev) { el.addEventListener(ev, onEvent, true); });
  }

  function scan() {
    document.querySelectorAll('audio,video').forEach(track);
  }

  function cmp(a, b) {
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
    return 0;
  }

  function pick() {
    let best = null, bestKey = null;
    for (let i = refs.length - 1; i >= 0; i--) {
      const el = refs[i].deref();
      if (!el) { refs.splice(i, 1); continue; }
      const d = el.duration;
      if (!isFinite(d) || d < 10) continue;          // belum siap / efek suara / live stream
      const key = [
        (!el.paused && !el.ended) ? 1 : 0,           // yang lagi jalan menang
        (!el.muted && el.volume > 0) ? 1 : 0,        // yang kedengeran menang (bukan video iklan/background)
        touched.get(el) || 0,                        // yang paling baru disentuh
        d
      ];
      if (!bestKey || cmp(key, bestKey) > 0) { best = el; bestKey = key; }
    }
    return best;
  }

  function getTitle() {
    try {
      const m = navigator.mediaSession && navigator.mediaSession.metadata;
      if (m && m.title) return String(m.title);
    } catch (e) {}
    let t = document.title;
    if (!t) { try { t = window.top.document.title; } catch (e) {} }
    return t || location.hostname;
  }

  // ---------------------------------------------------------------- laporan posisi
  function report(type) {
    const el = pick();
    if (!el) { lastEl = null; lastIntent = false; return; }
    const intent = !el.paused && !el.ended;
    // 'play' = elemen yang kita laporin BARU mulai diputar (atau baru kepilih sambil lagi jalan).
    // Cuma ini yang boleh ngambil alih sync dari sumber lain; nama event mentah sengaja gak dipake,
    // biar event 'play' dari video lain (iklan/background) gak ikut kehitung.
    let ev = (type === 'play' || type === 'playing') ? 'hb' : type;
    if (intent && (el !== lastEl || !lastIntent)) ev = 'play';
    lastEl = el; lastIntent = intent;
    send({
      id: ID, ev: ev,
      t: el.currentTime, rate: el.playbackRate, dur: el.duration,
      paused: !(intent && el.readyState >= 3),       // lagi buffering = belum jalan
      muted: !!el.muted || el.volume === 0,
      title: getTitle()
    }, type !== 'hb');
  }

  function onEvent(e) {
    touched.set(e.target, performance.now());
    report(e.type);
  }

  // elemen yang dibikin lewat `new Audio()` (gak nempel di halaman) ketangkep dari sini
  try {
    const proto = PAGE.HTMLMediaElement.prototype;
    const orig = proto.play;
    proto.play = function () {
      try { track(this); } catch (e) {}
      return orig.apply(this, arguments);
    };
  } catch (e) {}

  // elemen yang nempel di halaman: tangkep event-nya dari document
  ['play', 'playing', 'loadedmetadata'].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      const t = e.target;
      if (t && (t.tagName === 'AUDIO' || t.tagName === 'VIDEO')) track(t);
    }, true);
  });

  // detak jantung: tiap 1 detik pas lagi play, tiap 3 detik pas pause
  setInterval(function () {
    beat++;
    scan();
    const el = pick();
    if (!el) return;
    if ((!el.paused && !el.ended) || beat % 3 === 0) report('hb');
  }, 1000);
})();
