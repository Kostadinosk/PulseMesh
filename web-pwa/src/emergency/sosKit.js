// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

/**
 * PulseMesh v2.0 Web Emergency Kit:
 * - High-visibility alternating rescue siren using Web Audio API
 * - Standardized SOS & "I'm Safe" check-in payload builders
 */
export function createWebEmergencySiren() {
  let ctx = null;
  let osc = null;
  let gain = null;
  let timer = null;
  let active = false;

  function start() {
    if (active) return;
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return;
    ctx = new AudioCtx();
    osc = ctx.createOscillator();
    gain = ctx.createGain();
    osc.type = "sawtooth";
    osc.frequency.value = 920;
    gain.gain.value = 0.25;
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    active = true;

    let high = false;
    timer = window.setInterval(() => {
      if (!osc || !ctx) return;
      high = !high;
      osc.frequency.setTargetAtTime(high ? 1680 : 920, ctx.currentTime, 0.08);
    }, 420);
  }

  function stop() {
    active = false;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    try { osc?.stop(); } catch { /* noop */ }
    try { osc?.disconnect(); } catch { /* noop */ }
    try { gain?.disconnect(); } catch { /* noop */ }
    try { ctx?.close(); } catch { /* noop */ }
    osc = null;
    gain = null;
    ctx = null;
  }

  return {
    start,
    stop,
    toggle: () => (active ? stop() : start()),
    isActive: () => active
  };
}

export function buildSafeCheckInText(callsign, lat, lon) {
  const gps = Number.isFinite(lat) && Number.isFinite(lon) && (lat !== 0 || lon !== 0)
    ? `GPS ${lat.toFixed(5)}, ${lon.toFixed(5)}`
    : "GPS pending";
  return `[CHECK-IN: I AM SAFE] ${callsign || "WebNode"} • ${gps}`;
}
