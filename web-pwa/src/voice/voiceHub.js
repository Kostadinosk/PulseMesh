// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

import { fetchTurnIceServers } from "../net/cloudflareRealtime.js";

/**
 * Unified WebRTC P2P Opus + PTT Voice Hub controller for PulseMesh Web / iPhone PWA.
 */
export function createVoiceHub({
  getCredentials,
  sendSignal,
  onStateChange,
  onRemoteAudioStream
}) {
  let pc = null;
  let localStream = null;
  let activePeerId = null;
  let isMuted = false;
  let callStartedAt = 0;
  let timerHandle = null;

  function emit(status, extra = {}) {
    onStateChange?.({
      status,
      peerId: activePeerId,
      isMuted,
      elapsedSec: callStartedAt ? Math.floor((Date.now() - callStartedAt) / 1000) : 0,
      ...extra
    });
  }

  async function ensurePeerConnection(peerId) {
    activePeerId = peerId;
    const creds = getCredentials?.() || {};
    const iceServers = await fetchTurnIceServers(creds);
    pc = new RTCPeerConnection({ iceServers });

    pc.onicecandidate = (ev) => {
      if (ev.candidate && activePeerId) {
        sendSignal?.(activePeerId, "RTC_ICE", JSON.stringify(ev.candidate));
      }
    };

    pc.ontrack = (ev) => {
      if (ev.streams && ev.streams[0]) {
        onRemoteAudioStream?.(ev.streams[0]);
      }
    };

    pc.onconnectionstatechange = () => {
      if (!pc) return;
      if (pc.connectionState === "connected") {
        callStartedAt = Date.now();
        if (timerHandle) clearInterval(timerHandle);
        timerHandle = setInterval(() => emit("connected"), 1000);
        emit("connected");
      } else if (pc.connectionState === "failed" || pc.connectionState === "disconnected" || pc.connectionState === "closed") {
        endCall();
      }
    };

    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      localStream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
      });
      localStream.getTracks().forEach((track) => pc.addTrack(track, localStream));
    }
    return pc;
  }

  async function startCall(peerId) {
    if (!peerId) return;
    endCall(false);
    emit("calling", { peerId });
    try {
      const conn = await ensurePeerConnection(peerId);
      const offer = await conn.createOffer({ offerToReceiveAudio: true });
      await conn.setLocalDescription(offer);
      sendSignal?.(peerId, "RTC_OFFER", JSON.stringify(offer));
    } catch (err) {
      emit("error", { error: err?.message || "WebRTC call requires HTTPS microphone access" });
    }
  }

  async function handleIncomingSignal(peerId, type, payload) {
    if (type === "RTC_OFFER") {
      try {
        const offer = JSON.parse(payload);
        const conn = await ensurePeerConnection(peerId);
        await conn.setRemoteDescription(new RTCSessionDescription(offer));
        const answer = await conn.createAnswer();
        await conn.setLocalDescription(answer);
        sendSignal?.(peerId, "RTC_ANSWER", JSON.stringify(answer));
        emit("connecting", { peerId });
      } catch (err) {
        emit("error", { error: err?.message || "Failed to accept WebRTC offer" });
      }
    } else if (type === "RTC_ANSWER" && pc) {
      try {
        const answer = JSON.parse(payload);
        await pc.setRemoteDescription(new RTCSessionDescription(answer));
      } catch {}
    } else if (type === "RTC_ICE" && pc) {
      try {
        const cand = JSON.parse(payload);
        await pc.addIceCandidate(new RTCIceCandidate(cand));
      } catch {}
    } else if (type === "CALL_END") {
      endCall(false);
    }
  }

  function toggleMute() {
    isMuted = !isMuted;
    if (localStream) {
      localStream.getAudioTracks().forEach((t) => {
        t.enabled = !isMuted;
      });
    }
    emit(pc ? "connected" : "idle");
    return isMuted;
  }

  function endCall(notifyPeer = true) {
    if (notifyPeer && activePeerId) {
      sendSignal?.(activePeerId, "CALL_END", "");
    }
    if (timerHandle) clearInterval(timerHandle);
    timerHandle = null;
    callStartedAt = 0;
    try { pc?.close(); } catch {}
    try { localStream?.getTracks().forEach((t) => t.stop()); } catch {}
    pc = null;
    localStream = null;
    activePeerId = null;
    isMuted = false;
    emit("idle");
  }

  return {
    startCall,
    handleIncomingSignal,
    toggleMute,
    endCall
  };
}
