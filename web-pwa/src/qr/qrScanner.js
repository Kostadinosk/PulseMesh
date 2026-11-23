// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

/**
 * PulseMesh v2.0 Browser QR Camera Scanner + URI Router
 * Uses native BarcodeDetector when available with graceful paste/manual fallback.
 */
export function parsePulseMeshQrUri(rawText) {
  const raw = String(rawText || "").trim();
  if (!raw) return { kind: "empty" };
  if (raw.startsWith("pulsemesh://join")) {
    const query = raw.split("?")[1] || "";
    const params = new URLSearchParams(query);
    return {
      kind: "peer",
      nodeId: params.get("id") || "",
      callsign: params.get("name") || params.get("user") || params.get("id") || "Mesh Peer",
      username: params.get("user") || "",
      publicKey: params.get("pub") || "",
      groupId: params.get("group") || ""
    };
  }
  if (raw.startsWith("pulsemesh://verify")) {
    const query = raw.split("?")[1] || "";
    const params = new URLSearchParams(query);
    return {
      kind: "verify",
      from: params.get("from") || "",
      peer: params.get("peer") || "",
      safetyNumber: params.get("sn") || ""
    };
  }
  if (raw.startsWith("@") || raw.startsWith("0x")) {
    return { kind: "peer", nodeId: raw.startsWith("0x") ? raw : "", username: raw.replace(/^@/, ""), callsign: raw };
  }
  return { kind: "unknown", raw };
}

export function createCameraQrScanner(videoEl, onDetected, onStatus) {
  let stream = null;
  let rafId = 0;
  let running = false;

  async function start() {
    if (!navigator.mediaDevices?.getUserMedia) {
      onStatus?.("Camera API unavailable on this origin; paste a pulsemesh:// invite link below.");
      return false;
    }
    if (typeof window.BarcodeDetector !== "function") {
      onStatus?.("Browser native BarcodeDetector not supported on Safari/Firefox; paste a pulsemesh:// invite link below.");
      return false;
    }
    try {
      const detector = new window.BarcodeDetector({ formats: ["qr_code"] });
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "environment" } },
        audio: false
      });
      videoEl.srcObject = stream;
      await videoEl.play();
      running = true;
      onStatus?.("Camera active — point at a PulseMesh QR code.");

      const tick = async () => {
        if (!running) return;
        try {
          if (videoEl.readyState >= 2) {
            const codes = await detector.detect(videoEl);
            if (codes && codes.length > 0 && codes[0].rawValue) {
              stop();
              onDetected(codes[0].rawValue);
              return;
            }
          }
        } catch {
          // ignore transient frame errors
        }
        rafId = requestAnimationFrame(tick);
      };
      rafId = requestAnimationFrame(tick);
      return true;
    } catch (err) {
      onStatus?.(`Camera permission denied or unavailable (${err.message || err}). Paste invite URI below.`);
      stop();
      return false;
    }
  }

  function stop() {
    running = false;
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = 0;
    }
    if (stream) {
      stream.getTracks().forEach((t) => t.stop());
      stream = null;
    }
    if (videoEl) {
      videoEl.srcObject = null;
    }
  }

  return { start, stop, isRunning: () => running };
}
