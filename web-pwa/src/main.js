// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

import { getLanguage, setLanguage, t } from "./i18n.js";
import {
  DR_PREFIX,
  DM_PREFIX_ECDH,
  decryptChannelPayload,
  decryptDmEcdh,
  doubleRatchetDecrypt,
  doubleRatchetEncrypt,
  ecdhSharedBits,
  encryptChannelPayload,
  encryptDmEcdh,
  generateIdentityKeyPair,
  initDoubleRatchetInitiator,
  initDoubleRatchetResponder
} from "./crypto/doubleRatchet.js";
import {
  TYPE_CHAT,
  TYPE_SOS,
  createBell202Controller
} from "./acoustic/bell202v2.js";
import {
  DEFAULT_CLOUDFLARE_RELAY,
  claimUsernameAccount,
  createCloudflareRealtimeClient,
  lookupPublicUser,
  normalizeUsername,
  recoverUsernameAccount
} from "./net/cloudflareRealtime.js";
import {
  disableWebPush,
  enableWebPush,
  isIosDevice,
  isStandalonePwa
} from "./net/webPush.js";
import { createVoiceHub } from "./voice/voiceHub.js";
import { createTacticalMapController, exportWaypointsToGpx } from "./map/tacticalMap.js";
import { compute60DigitSafetyNumber } from "./crypto/safetyNumber.js";
import { exportEncryptedBackup, importEncryptedBackup } from "./crypto/encryptedBackup.js";
import { createCameraQrScanner, parsePulseMeshQrUri } from "./qr/qrScanner.js";
import { buildSafeCheckInText, createWebEmergencySiren } from "./emergency/sosKit.js";

const STORAGE_KEY = "pulsemesh_web_v155_state";

function loadConfig() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
  } catch {
    return {};
  }
}

function saveConfig(patch) {
  const next = Object.assign(loadConfig(), patch);
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {}
  return next;
}

function randomNodeId() {
  const n = Math.floor(0x1000 + Math.random() * 0xefff);
  return "0x" + n.toString(16).toUpperCase().padStart(4, "0");
}

function normId(id) {
  return String(id || "").trim().toUpperCase().replace(/^0X/, "");
}

function setupDialogFallbacks() {
  if (!("closedBy" in HTMLDialogElement.prototype)) {
    const outside = (e, dialog) => {
      const r = dialog.getBoundingClientRect();
      return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
    };
    for (const dialog of document.querySelectorAll('dialog[closedby="any"]')) {
      let pressedOutside = false;
      dialog.addEventListener("pointerdown", (e) => {
        pressedOutside = outside(e, dialog);
      });
      dialog.addEventListener("pointerup", (e) => {
        if (pressedOutside && outside(e, dialog)) dialog.close();
      });
    }
  }
  for (const dialog of document.querySelectorAll("dialog")) {
    dialog.addEventListener("close", () => {
      dialog.returnValue = "";
    });
  }
}

async function boot() {
  setupDialogFallbacks();

  const logoImg = document.getElementById("brandLogoImg");
  logoImg?.addEventListener("error", () => {
    if (!logoImg.dataset.fallbackApplied) {
      logoImg.dataset.fallbackApplied = "1";
      logoImg.src = "/api/icon.png";
    }
  });

  const saved = loadConfig();
  const state = {
    nodeId: saved.nodeId || randomNodeId(),
    callsign: saved.callsign || (isIosDevice() ? "iPhone-Node" : "Web-Node"),
    passphrase: saved.passphrase || "pulsemesh-tactical",
    claimedUsername: saved.claimedUsername || "",
    authToken: saved.authToken || "",
    privKeyB64: saved.privKeyB64 || "",
    pubKeyB64: saved.pubKeyB64 || "",
    fingerprint: saved.fingerprint || "",
    theme: saved.theme || "dark",
    lang: saved.lang || "en",
    isLocalHost: false,
    activeThreadId: "channel_primary",
    peers: new Map(),
    messages: [],
    waypoints: [],
    breadcrumbs: [],
    ratchetSessions: new Map(),
    userLat: null,
    userLon: null
  };

  if (globalThis.crypto?.subtle && (!state.privKeyB64 || !state.pubKeyB64)) {
    const kp = await generateIdentityKeyPair();
    state.privKeyB64 = kp.privKeyB64;
    state.pubKeyB64 = kp.pubKeyB64;
    state.fingerprint = kp.fingerprint;
    saveConfig({
      nodeId: state.nodeId,
      privKeyB64: state.privKeyB64,
      pubKeyB64: state.pubKeyB64,
      fingerprint: state.fingerprint
    });
  }

  // Theme handled by <meta name="color-scheme"> + data-preset
  setLanguage(state.lang);

  // Sync settings inputs
  const setCallsignInput = document.getElementById("setCallsignInput");
  const setPassphraseInput = document.getElementById("setPassphraseInput");
  const setThemeSelect = document.getElementById("setThemeSelect");
  const setLangSelect = document.getElementById("setLangSelect");
  const transportPill = document.getElementById("transportStatusPill");
  const topIdentity = document.getElementById("topNodeIdentity");
  const mapStatusPill = document.getElementById("mapStatusPill");

  function refreshHeaderIdentity() {
    setCallsignInput.value = state.callsign;
    setPassphraseInput.value = state.passphrase;
    setThemeSelect.value = state.theme;
    setLangSelect.value = state.lang;
    const handle = state.claimedUsername ? `@${state.claimedUsername}` : state.callsign;
    topIdentity.textContent = `${handle} (${state.nodeId}) • ${state.fingerprint || "E2EE"}`;
  }
  refreshHeaderIdentity();

  // Register Service Worker for offline PWA + iOS 16.4+ Web Push
  if ("serviceWorker" in navigator && location.protocol === "https:") {
    navigator.serviceWorker.register("./sw.js").catch(() => {});
  }

  // 4-Tab Navigation
  const navButtons = document.querySelectorAll(".nav-btn");
  const tabPanes = document.querySelectorAll(".tab-pane");
  let mapCtrl = null;

  function selectTab(tabName) {
    navButtons.forEach((btn) => btn.classList.toggle("active", btn.dataset.tab === tabName));
    tabPanes.forEach((pane) => pane.classList.toggle("active", pane.id === `pane-${tabName}`));
    if (tabName === "map") {
      setTimeout(() => mapCtrl?.resize(), 20);
    }
  }
  navButtons.forEach((btn) => btn.addEventListener("click", () => selectTab(btn.dataset.tab)));

  // Tactical Map 2.0
  const mapCanvas = document.getElementById("tacticalMapCanvas");
  mapCtrl = createTacticalMapController({
    canvas: mapCanvas,
    getState: () => ({
      isLocalHost: state.isLocalHost,
      waypoints: state.waypoints,
      breadcrumbs: state.breadcrumbs,
      peers: [
        ...(state.userLat != null ? [{ nodeId: state.nodeId, callsign: state.callsign, lat: state.userLat, lon: state.userLon, isSelf: true }] : []),
        ...Array.from(state.peers.values())
      ]
    }),
    onDropWaypoint: (coords) => {
      const wp = {
        id: `wp_${Date.now()}`,
        title: `PIN ${state.waypoints.length + 1}`,
        type: "RALLY",
        lat: coords.lat,
        lon: coords.lon
      };
      state.waypoints.push(wp);
      mapStatusPill.textContent = `Dropped ${wp.title} @ ${coords.lat.toFixed(4)}, ${coords.lon.toFixed(4)}`;
      mapCtrl.render();
      sendLocalHostPacket({
        type: "WAYPOINT",
        dstNodeId: "ALL",
        payload: JSON.stringify(wp),
        lat: coords.lat,
        lng: coords.lon
      });
    },
    onRulerUpdate: (txt) => {
      mapStatusPill.textContent = txt || t("mapDropHint");
    }
  });
  window.addEventListener("resize", () => mapCtrl.resize());
  mapCtrl.resize();

  document.getElementById("mapLayerDarkBtn").addEventListener("click", () => mapCtrl.setLayer("dark"));
  document.getElementById("mapLayerTopoBtn").addEventListener("click", () => mapCtrl.setLayer("topo"));
  document.getElementById("mapLayerRadarBtn").addEventListener("click", () => mapCtrl.setLayer("radar"));
  document.getElementById("mapRulerBtn").addEventListener("click", () => mapCtrl.toggleRuler());
  document.getElementById("mapZoomInBtn").addEventListener("click", () => mapCtrl.zoomIn());
  document.getElementById("mapZoomOutBtn").addEventListener("click", () => mapCtrl.zoomOut());
  document.getElementById("mapExportGpxBtn").addEventListener("click", () => {
    const gpx = exportWaypointsToGpx(state.waypoints, state.breadcrumbs);
    const blob = new Blob([gpx], { type: "application/gpx+xml" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "pulsemesh_tactical.gpx";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1500);
    mapStatusPill.textContent = `Exported GPX (${state.waypoints.length} pins)`;
  });

  if (navigator.geolocation) {
    navigator.geolocation.watchPosition(
      (pos) => {
        state.userLat = pos.coords.latitude;
        state.userLon = pos.coords.longitude;
        state.breadcrumbs.push({ lat: state.userLat, lon: state.userLon });
        if (state.breadcrumbs.length > 250) state.breadcrumbs.shift();
        mapCtrl.setCenter(state.userLat, state.userLon);
      },
      () => {},
      { enableHighAccuracy: false, maximumAge: 15000 }
    );
  }

  // Rendering Threads, Peers & Messages (Strictly safe DOM textContent, zero innerHTML XSS)
  const threadListEl = document.getElementById("threadListContainer");
  const peerListEl = document.getElementById("peerListContainer");
  const messageLogEl = document.getElementById("messageLogContainer");
  const activeThreadTitleEl = document.getElementById("activeThreadTitle");
  const voicePeerSelect = document.getElementById("voicePeerSelect");

  function renderPeersAndThreads() {
    threadListEl.textContent = "";
    peerListEl.textContent = "";
    voicePeerSelect.textContent = "";

    const threads = [{ id: "channel_primary", label: "#Primary Tactical Mesh" }];
    for (const peer of state.peers.values()) {
      threads.push({ id: `dm_${peer.nodeId}`, label: `DM: ${peer.callsign || peer.nodeId}` });

      const peerRow = document.createElement("div");
      peerRow.className = "status-note";
      peerRow.textContent = `${peer.callsign || peer.nodeId} (${peer.nodeId})${peer.username ? ` @${peer.username}` : ""}`;
      peerListEl.appendChild(peerRow);

      const opt = document.createElement("option");
      opt.value = peer.nodeId;
      opt.textContent = `${peer.callsign || peer.nodeId} (${peer.nodeId})`;
      voicePeerSelect.appendChild(opt);
    }

    if (!voicePeerSelect.options.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No peers discovered yet";
      voicePeerSelect.appendChild(opt);
    }

    for (const th of threads) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = `btn thread-btn${state.activeThreadId === th.id ? " active" : ""}`;
      btn.textContent = th.label;
      btn.addEventListener("click", () => {
        state.activeThreadId = th.id;
        activeThreadTitleEl.textContent = th.label;
        renderPeersAndThreads();
        renderMessages();
      });
      threadListEl.appendChild(btn);
    }
  }

  function renderMessages() {
    messageLogEl.textContent = "";
    const filtered = state.messages.filter((m) => m.threadId === state.activeThreadId);
    for (const m of filtered) {
      const bubble = document.createElement("div");
      bubble.className = `msg-bubble${m.isSelf ? " self" : ""}`;
      const meta = document.createElement("div");
      meta.className = "msg-meta";
      meta.textContent = `${m.sender} • ${m.transport} • ${new Date(m.ts).toLocaleTimeString([], { hour12: false })}`;
      const body = document.createElement("div");
      body.textContent = m.text;
      bubble.appendChild(meta);
      bubble.appendChild(body);
      messageLogEl.appendChild(bubble);
    }
    messageLogEl.scrollTop = messageLogEl.scrollHeight;
  }

  function appendMessage({ threadId = "channel_primary", sender, text, transport = "MESH", isSelf = false }) {
    state.messages.push({ threadId, sender, text, transport, isSelf, ts: Date.now() });
    if (state.messages.length > 400) state.messages.shift();
    renderMessages();
  }

  // Local Android Hotspot Transport (/api/info, /api/register, /api/send, /ws, /api/apk/info)
  let localWs = null;

  async function sendLocalHostPacket(pkt) {
    if (!state.isLocalHost) return false;
    const body = {
      srcNodeId: state.nodeId,
      srcCallsign: state.callsign,
      dstNodeId: pkt.dstNodeId || "ALL",
      type: pkt.type || "CHAT",
      payload: pkt.payload || "",
      lat: pkt.lat ?? state.userLat,
      lng: pkt.lng ?? state.userLon,
      timestamp: Date.now()
    };
    if (localWs && localWs.readyState === WebSocket.OPEN) {
      localWs.send(JSON.stringify(body));
      return true;
    }
    try {
      const res = await fetch("/api/send", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      });
      return res.ok;
    } catch {
      return false;
    }
  }

  async function handleIncomingWirePacket(pkt, transportHint = "LOCAL WS") {
    if (!pkt || !pkt.type) return;
    const srcId = pkt.srcNodeId || pkt.srcId || "0x0000";
    if (normId(srcId) === normId(state.nodeId)) return;

    if (pkt.type === "RTC_OFFER" || pkt.type === "RTC_ANSWER" || pkt.type === "RTC_ICE" || pkt.type === "CALL_END") {
      voiceHub.handleIncomingSignal(srcId, pkt.type, pkt.payload || "");
      return;
    }

    if (srcId && srcId !== "0x0000") {
      const prev = state.peers.get(normId(srcId)) || { nodeId: srcId };
      state.peers.set(normId(srcId), {
        ...prev,
        nodeId: srcId,
        callsign: pkt.srcCallsign || prev.callsign || srcId,
        publicKeyBase64: pkt.publicKeyBase64 || pkt.pk || prev.publicKeyBase64 || "",
        lat: typeof pkt.lat === "number" ? pkt.lat : prev.lat,
        lon: typeof pkt.lng === "number" ? pkt.lng : prev.lon
      });
      renderPeersAndThreads();
      mapCtrl.render();
    }

    let text = String(pkt.payload || "");
    let threadId = "channel_primary";
    if (pkt.type === "E2EE_DM") {
      threadId = `dm_${srcId}`;
      const peer = state.peers.get(normId(srcId));
      const peerPub = pkt.pk || peer?.publicKeyBase64 || "";
      if (text.startsWith(DR_PREFIX) && peerPub && state.privKeyB64) {
        let sess = state.ratchetSessions.get(normId(srcId));
        if (!sess) {
          const root = await ecdhSharedBits(state.privKeyB64, peerPub);
          sess = await initDoubleRatchetResponder(normId(srcId), root, state.privKeyB64, state.pubKeyB64);
        }
        const dec = await doubleRatchetDecrypt(sess, text);
        if (dec) {
          state.ratchetSessions.set(normId(srcId), dec.session);
          text = dec.plaintext;
        } else {
          text = "[Double Ratchet Decryption Failed]";
        }
      } else if (text.startsWith(DM_PREFIX_ECDH)) {
        text = await decryptDmEcdh(text, state.privKeyB64, peerPub);
      } else {
        text = "[Encrypted / Key Verification Failed]";
      }
    } else if (text.startsWith("PM2:")) {
      const dec = await decryptChannelPayload(text, state.passphrase);
      text = dec || "[Channel Decryption Failed]";
    }

    if (pkt.type === "CHAT" || pkt.type === "E2EE_DM" || pkt.type === "SOS") {
      appendMessage({
        threadId,
        sender: pkt.srcCallsign || srcId,
        text,
        transport: transportHint,
        isSelf: false
      });
    }
  }

  async function probeAndroidHotspotHost() {
    try {
      const infoRes = await fetch("/api/info", { cache: "no-store" });
      if (!infoRes.ok) return;
      const info = await infoRes.json();
      if (!info || !info.pulsemeshHost) return;
      state.isLocalHost = true;
      transportPill.textContent = `HOTSPOT HOST (${info.callsign || "Android"})`;

      await fetch("/api/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          nodeId: state.nodeId,
          callsign: state.callsign,
          publicKeyBase64: state.pubKeyB64,
          username: state.claimedUsername
        })
      }).catch(() => {});

      // Probe Offline P2P APK Beaming metadata
      const apkBox = document.getElementById("apkBeamInfoBox");
      fetch("/api/apk/info")
        .then((r) => (r.ok ? r.json() : null))
        .then((apk) => {
          if (apk && apk.available) {
            const mb = (Number(apk.sizeBytes || 0) / (1024 * 1024)).toFixed(2);
            apkBox.textContent = `Ready: ${apk.fileName || "pulsemesh.apk"} (${mb} MB) • SHA-256: ${String(apk.sha256 || "").slice(0, 16)}...`;
          }
        })
        .catch(() => {});

      const wsProto = location.protocol === "https:" ? "wss:" : "ws:";
      localWs = new WebSocket(`${wsProto}//${location.host}/ws?clientNodeId=${encodeURIComponent(state.nodeId)}`);
      localWs.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          handleIncomingWirePacket(msg, "HOTSPOT WS");
        } catch {}
      };
    } catch {
      // Running online on GitHub Pages or standalone PWA
    }
  }

  // Cloudflare Durable Objects Realtime WebSocket Client
  const cfRealtime = createCloudflareRealtimeClient({
    relayBase: DEFAULT_CLOUDFLARE_RELAY,
    getCredentials: () => ({ username: state.claimedUsername, authToken: state.authToken }),
    onStatus: (st) => {
      if (!state.isLocalHost) {
        transportPill.textContent = `CF REALTIME: ${String(st).toUpperCase()}`;
      }
    },
    onMessage: (msg) => {
      if (msg && msg.type && msg.type !== "PONG" && msg.type !== "REALTIME_READY") {
        handleIncomingWirePacket(msg, "CF DO WS");
      }
    }
  });

  // Unified Voice Hub + Active Call native <dialog>
  const callDialog = document.getElementById("callDialog");
  const callPeerLabel = document.getElementById("callPeerLabel");
  const callTimerLabel = document.getElementById("callTimerLabel");
  const voiceStatusBox = document.getElementById("voiceStatusBox");
  const remoteAudioEl = document.getElementById("remoteCallAudio");

  const voiceHub = createVoiceHub({
    getCredentials: () => ({
      relayBase: DEFAULT_CLOUDFLARE_RELAY,
      username: state.claimedUsername,
      authToken: state.authToken
    }),
    sendSignal: (peerId, type, payload) => {
      const pkt = { dstNodeId: peerId, type, payload, srcNodeId: state.nodeId, srcCallsign: state.callsign };
      if (!sendLocalHostPacket(pkt)) {
        cfRealtime.send(pkt);
      }
    },
    onRemoteAudioStream: (stream) => {
      remoteAudioEl.srcObject = stream;
    },
    onStateChange: (cs) => {
      const mm = String(Math.floor((cs.elapsedSec || 0) / 60)).padStart(2, "0");
      const ss = String((cs.elapsedSec || 0) % 60).padStart(2, "0");
      callTimerLabel.textContent = `${mm}:${ss}`;
      if (cs.status === "calling" || cs.status === "connecting" || cs.status === "connected") {
        callPeerLabel.textContent = cs.peerId || "Peer";
        if (!callDialog.open) callDialog.showModal();
        voiceStatusBox.textContent = `Call ${cs.status.toUpperCase()} (${mm}:${ss})`;
      } else {
        if (callDialog.open) callDialog.close();
        voiceStatusBox.textContent = cs.error || "Voice Hub ready (WebRTC Opus + TURN).";
      }
    }
  });

  document.getElementById("startWebRtcCallBtn").addEventListener("click", () => {
    const target = voicePeerSelect.value;
    if (!target) {
      voiceStatusBox.textContent = "Discover or add a peer before placing a WebRTC call.";
      return;
    }
    voiceHub.startCall(target);
  });
  document.getElementById("callMuteBtn").addEventListener("click", (e) => {
    const muted = voiceHub.toggleMute();
    e.currentTarget.textContent = muted ? "Unmute" : "Mute";
  });
  document.getElementById("callHangupBtn").addEventListener("click", () => voiceHub.endCall(true));

  // Bell 202 Acoustic Modem 2.0 (RS-FEC + AES-256-GCM)
  const bell202StatusBox = document.getElementById("bell202StatusBox");
  const bell202RxBtn = document.getElementById("bell202RxToggleBtn");
  const bell202Ctrl = createBell202Controller({
    getNodeId: () => state.nodeId,
    getPassphrase: () => state.passphrase,
    onStatus: (s) => {
      bell202StatusBox.textContent = s;
    },
    onDecoded: (frame) => {
      const badge = frame.isEncrypted ? `ACOUSTIC 2.0 E2EE (RS:${frame.fecCorrectedBytes}B)` : "ACOUSTIC V1";
      appendMessage({
        threadId: "channel_primary",
        sender: frame.srcNodeIdHex,
        text: frame.text,
        transport: badge,
        isSelf: false
      });
      bell202StatusBox.textContent = `RX ${badge} from ${frame.srcNodeIdHex}: ${frame.text}`;
    }
  });

  document.getElementById("bell202TxBtn").addEventListener("click", async () => {
    const input = document.getElementById("bell202MsgInput");
    const msg = input.value.trim() || `Ping from ${state.callsign}`;
    await bell202Ctrl.transmit(TYPE_CHAT, msg);
  });
  document.getElementById("bell202SosBtn").addEventListener("click", async () => {
    await bell202Ctrl.transmit(TYPE_SOS, `SOS from ${state.callsign} (${state.nodeId})`);
  });
  bell202RxBtn.addEventListener("click", async () => {
    const on = await bell202Ctrl.toggleRx();
    bell202RxBtn.textContent = on ? t("acousticRxOn") : t("acousticRxOff");
  });

  // Chat send form (Zero-trust PM2 / PMDR2 / E2EE_ECDH_V2)
  document.getElementById("chatSendForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const input = document.getElementById("chatMessageInput");
    const text = input.value.trim();
    if (!text) return;
    input.value = "";

    let wireType = "CHAT";
    let dstNodeId = "ALL";
    let wirePayload = text;

    if (state.activeThreadId.startsWith("dm_")) {
      dstNodeId = state.activeThreadId.slice(3);
      wireType = "E2EE_DM";
      const peer = state.peers.get(normId(dstNodeId));
      if (!peer || !peer.publicKeyBase64 || !state.privKeyB64) {
        appendMessage({
          threadId: state.activeThreadId,
          sender: "System",
          text: "Zero-trust E2EE requires peer public key before sending a DM.",
          transport: "ZERO-TRUST",
          isSelf: false
        });
        return;
      }
      let sess = state.ratchetSessions.get(normId(dstNodeId));
      if (!sess) {
        const root = await ecdhSharedBits(state.privKeyB64, peer.publicKeyBase64);
        sess = await initDoubleRatchetInitiator(normId(dstNodeId), root, peer.publicKeyBase64);
      }
      const enc = await doubleRatchetEncrypt(sess, text);
      if (enc) {
        state.ratchetSessions.set(normId(dstNodeId), enc.session);
        wirePayload = enc.payload;
      } else {
        wirePayload = await encryptDmEcdh(text, state.privKeyB64, peer.publicKeyBase64);
      }
      if (!wirePayload) return;
    } else if (globalThis.crypto?.subtle) {
      wirePayload = await encryptChannelPayload(text, state.passphrase);
    }

    appendMessage({
      threadId: state.activeThreadId,
      sender: state.callsign,
      text,
      transport: wireType === "E2EE_DM" ? "PMDR2 E2EE" : "PM2 E2EE",
      isSelf: true
    });
    const sentLocal = await sendLocalHostPacket({ dstNodeId, type: wireType, payload: wirePayload });
    if (!sentLocal) {
      cfRealtime.send({ dstNodeId, type: wireType, payload: wirePayload, srcNodeId: state.nodeId, srcCallsign: state.callsign });
    }
  });

  // Add DM peer by @username or 0xNodeId
  document.getElementById("startDmBtn").addEventListener("click", async () => {
    const input = document.getElementById("newDmTargetInput");
    const raw = input.value.trim();
    if (!raw) return;
    input.value = "";
    if (raw.startsWith("@") || !raw.toLowerCase().startsWith("0x")) {
      const rec = await lookupPublicUser(DEFAULT_CLOUDFLARE_RELAY, raw);
      if (rec && rec.nodeId) {
        state.peers.set(normId(rec.nodeId), {
          nodeId: rec.nodeId,
          callsign: rec.displayName || `@${rec.username}`,
          username: rec.username,
          publicKeyBase64: rec.publicKeyBase64 || ""
        });
        state.activeThreadId = `dm_${rec.nodeId}`;
        renderPeersAndThreads();
        renderMessages();
        return;
      }
    }
    const cleanId = raw.startsWith("0x") || raw.startsWith("0X") ? raw : `0x${raw}`;
    state.peers.set(normId(cleanId), { nodeId: cleanId, callsign: cleanId });
    state.activeThreadId = `dm_${cleanId}`;
    renderPeersAndThreads();
    renderMessages();
  });

  // Top PING & SOS buttons
  document.getElementById("topPingBtn").addEventListener("click", () => {
    sendLocalHostPacket({ type: "HEARTBEAT", dstNodeId: "ALL", payload: "PING" });
    mapStatusPill.textContent = "Mesh ping transmitted.";
  });
  // v2.0.0 Theme Preset & 3-State Color Scheme Override (per modern-web-guidance dark-mode-toggle)
  const colorSchemeMeta = document.querySelector("meta[name=\"color-scheme\"]");
  const setPresetSelect = document.getElementById("setPresetSelect");

  function applyColorScheme(mode, persist = true) {
    const normalized = mode === "dark" || mode === "light" ? mode : "system";
    if (colorSchemeMeta) {
      colorSchemeMeta.content = normalized === "system" ? "light dark" : normalized;
    }
    if (persist) {
      try {
        if (normalized === "system") localStorage.removeItem("color-scheme");
        else localStorage.setItem("color-scheme", normalized);
      } catch {}
    }
    if (setThemeSelect) setThemeSelect.value = normalized;
  }

  function applyThemePreset(preset, persist = true) {
    const valid = ["practical", "messenger", "field-ops", "aero"].includes(preset) ? preset : "practical";
    document.documentElement.dataset.preset = valid;
    if (persist) {
      try { localStorage.setItem("pulsemesh_theme_preset", valid); } catch {}
    }
    if (setPresetSelect) setPresetSelect.value = valid;
  }

  try {
    applyColorScheme(localStorage.getItem("color-scheme") || "system", false);
    applyThemePreset(localStorage.getItem("pulsemesh_theme_preset") || "practical", false);
  } catch {}

  window.addEventListener("storage", (ev) => {
    if (ev.key === "color-scheme") applyColorScheme(ev.newValue || "system", false);
    if (ev.key === "pulsemesh_theme_preset") applyThemePreset(ev.newValue || "practical", false);
  });

  // v2.0.0 Emergency SOS Kit Dialog (Broadcast SOS, Web Audio Rescue Siren, "I am Safe" Check-In)
  const sosDialog = document.getElementById("sosDialog");
  const webSiren = createWebEmergencySiren();
  document.getElementById("topSosBtn").addEventListener("click", () => {
    sosDialog?.showModal();
  });
  document.getElementById("sosBroadcastBtn")?.addEventListener("click", () => {
    const sosText = `EMERGENCY SOS from ${state.callsign} (${state.nodeId}) • GPS ${state.lat?.toFixed(5) || "0"}, ${state.lon?.toFixed(5) || "0"}`;
    appendMessage({ threadId: "channel_primary", sender: state.callsign, text: sosText, transport: "SOS", isSelf: true });
    sendLocalHostPacket({ type: "SOS", dstNodeId: "ALL", payload: sosText });
    cfRealtime.send({ dstNodeId: "ALL", type: "SOS", payload: sosText, srcNodeId: state.nodeId, srcCallsign: state.callsign });
    sosDialog?.close();
  });
  document.getElementById("sosSirenBtn")?.addEventListener("click", (e) => {
    webSiren.toggle();
    e.currentTarget.textContent = webSiren.isActive() ? "STOP RESCUE SIREN" : "Toggle Rescue Siren (Web Audio)";
  });
  document.getElementById("sosImSafeBtn")?.addEventListener("click", () => {
    webSiren.stop();
    const safeText = buildSafeCheckInText(state.callsign, state.lat, state.lon);
    appendMessage({ threadId: "channel_primary", sender: state.callsign, text: safeText, transport: "CHECK-IN", isSelf: true });
    sendLocalHostPacket({ type: "CHAT", dstNodeId: "ALL", payload: safeText });
    sosDialog?.close();
  });

  // v2.0.0 QR Camera Scanner & Invite URI Connect Hub Dialog
  const qrDialog = document.getElementById("qrConnectDialog");
  const qrVideo = document.getElementById("qrCameraVideo");
  const qrStatus = document.getElementById("qrScannerStatus");
  const qrManualInput = document.getElementById("qrManualUriInput");
  const applyParsedQr = (rawUri) => {
    const parsed = parsePulseMeshQrUri(rawUri);
    if (parsed.kind === "peer") {
      const id = parsed.nodeId || `0x${(parsed.username || "PEER").slice(0, 6).toUpperCase()}`;
      state.peers.set(normId(id), {
        nodeId: id,
        callsign: parsed.callsign,
        username: parsed.username,
        publicKeyBase64: parsed.publicKey || ""
      });
      state.activeThreadId = `dm_${id}`;
      renderPeersAndThreads();
      renderMessages();
      qrStatus.textContent = `Connected to ${parsed.callsign} (${id})`;
      qrDialog?.close();
    } else if (parsed.kind === "verify") {
      qrStatus.textContent = `Safety Number QR scanned for ${parsed.peer} (${parsed.safetyNumber.slice(0, 10)}...)`;
    } else {
      qrStatus.textContent = "Unrecognized QR format. Expected pulsemesh://join?... or @username.";
    }
  };
  const camScanner = createCameraQrScanner(qrVideo, applyParsedQr, (msg) => {
    if (qrStatus) qrStatus.textContent = msg;
  });
  document.getElementById("topQrConnectBtn")?.addEventListener("click", () => qrDialog?.showModal());
  document.getElementById("startQrCamBtn")?.addEventListener("click", async () => {
    if (qrVideo) qrVideo.hidden = false;
    await camScanner.start();
  });
  document.getElementById("copyMyInviteBtn")?.addEventListener("click", async () => {
    const u = state.claimedUsername ? `&user=${encodeURIComponent(state.claimedUsername)}` : "";
    const k = state.pubKeyB64 ? `&pub=${encodeURIComponent(state.pubKeyB64)}` : "";
    const uri = `pulsemesh://join?id=${encodeURIComponent(state.nodeId)}&name=${encodeURIComponent(state.callsign)}${u}${k}`;
    try {
      await navigator.clipboard.writeText(uri);
      qrStatus.textContent = "Copied your PulseMesh invite URI to clipboard.";
    } catch {
      if (qrManualInput) qrManualInput.value = uri;
      qrStatus.textContent = "Invite URI placed in the input box below.";
    }
  });
  document.getElementById("applyQrUriBtn")?.addEventListener("click", () => {
    if (qrManualInput?.value) applyParsedQr(qrManualInput.value);
  });
  qrDialog?.addEventListener("close", () => {
    camScanner.stop();
    if (qrVideo) qrVideo.hidden = true;
  });

  // v2.0.0 Interactive 60-Digit Safety Number Verification Dialog
  const safetyDialog = document.getElementById("safetyNumberDialog");
  const safetyGrid = document.getElementById("safetyNumberGrid");
  const safetySub = document.getElementById("safetyPeerSubtitle");
  document.getElementById("openSafetyNumberBtn")?.addEventListener("click", async () => {
    const peerId = state.activeThreadId.startsWith("dm_") ? state.activeThreadId.slice(3) : "ALL";
    const peer = state.peers.get(normId(peerId));
    const peerPub = peer?.publicKeyBase64 || peerId;
    const sn = await compute60DigitSafetyNumber(state.nodeId, state.pubKeyB64 || state.nodeId, peerId, peerPub);
    if (safetySub) {
      safetySub.textContent = `Compare these 12 five-digit blocks with ${peer?.callsign || peerId} (matches Android PulseMesh v2.0).`;
    }
    if (safetyGrid) {
      safetyGrid.innerHTML = "";
      for (const blk of sn.split(" ")) {
        const span = document.createElement("span");
        span.textContent = blk;
        safetyGrid.appendChild(span);
      }
    }
    safetyDialog?.showModal();
  });

  // v2.0.0 Global & In-Chat Message Search Filtering
  const filterDomItems = (containerId, query) => {
    const q = String(query || "").trim().toLowerCase();
    const box = document.getElementById(containerId);
    if (!box) return;
    for (const child of box.children) {
      child.hidden = Boolean(q && !child.textContent.toLowerCase().includes(q));
    }
  };
  document.getElementById("globalChatSearchInput")?.addEventListener("input", (e) => {
    filterDomItems("threadListContainer", e.target.value);
    filterDomItems("peerListContainer", e.target.value);
  });
  document.getElementById("inChatSearchInput")?.addEventListener("input", (e) => {
    filterDomItems("messageLogContainer", e.target.value);
  });

  // v2.0.0 Voice Note Recorder (MediaRecorder Opus/WebM) & Compressed Photo/File Sharing
  let activeRecorder = null;
  let recordedChunks = [];
  const voiceNoteBtn = document.getElementById("chatVoiceNoteBtn");
  voiceNoteBtn?.addEventListener("click", async () => {
    if (activeRecorder && activeRecorder.state === "recording") {
      activeRecorder.stop();
      voiceNoteBtn.textContent = "🎙";
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      appendMessage({ threadId: state.activeThreadId, sender: "System", text: "Voice note recording requires HTTPS or iOS Root CA trust.", transport: "AUDIO", isSelf: false });
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      recordedChunks = [];
      activeRecorder = new MediaRecorder(stream);
      activeRecorder.ondataavailable = (ev) => { if (ev.data.size > 0) recordedChunks.push(ev.data); };
      activeRecorder.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        const blob = new Blob(recordedChunks, { type: activeRecorder.mimeType || "audio/webm" });
        const url = URL.createObjectURL(blob);
        appendMessage({ threadId: state.activeThreadId, sender: state.callsign, text: `[Voice Note ${(blob.size / 1024).toFixed(1)} KB]`, transport: "VOICE NOTE", isSelf: true });
        const log = document.getElementById("messageLogContainer");
        if (log) {
          const audioEl = document.createElement("audio");
          audioEl.controls = true;
          audioEl.src = url;
          audioEl.style.maxWidth = "260px";
          log.appendChild(audioEl);
          log.scrollTop = log.scrollHeight;
        }
      };
      activeRecorder.start();
      voiceNoteBtn.textContent = "⏹";
    } catch (err) {
      appendMessage({ threadId: state.activeThreadId, sender: "System", text: `Mic error: ${err.message || err}`, transport: "AUDIO", isSelf: false });
    }
  });

  document.getElementById("chatMediaFileInput")?.addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = "";
    appendMessage({
      threadId: state.activeThreadId,
      sender: state.callsign,
      text: `[Attachment: ${file.name} (${(file.size / 1024).toFixed(1)} KB)]`,
      transport: "FILE",
      isSelf: true
    });
    if (file.type.startsWith("image/")) {
      const imgUrl = URL.createObjectURL(file);
      const log = document.getElementById("messageLogContainer");
      if (log) {
        const img = document.createElement("img");
        img.src = imgUrl;
        img.alt = file.name;
        img.className = "msg-img";
        log.appendChild(img);
        log.scrollTop = log.scrollHeight;
      }
    }
  });

  // v2.0.0 GPX Import onto Tactical Map 2.0
  document.getElementById("mapImportGpxInput")?.addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = "";
    const xml = await file.text();
    const doc = new DOMParser().parseFromString(xml, "application/xml");
    let imported = 0;
    for (const pt of doc.querySelectorAll("wpt, trkpt")) {
      const lat = parseFloat(pt.getAttribute("lat") || "");
      const lon = parseFloat(pt.getAttribute("lon") || "");
      if (Number.isFinite(lat) && Number.isFinite(lon)) {
        const name = pt.querySelector("name")?.textContent || `GPX #${imported + 1}`;
        state.waypoints.push({ lat, lon, label: name });
        imported++;
      }
    }
    mapCtrl.render();
    mapStatusPill.textContent = `Imported ${imported} GPX points from ${file.name}.`;
  });

  // v2.0.0 Encrypted Offline .pmbackup Export & Import
  const backupPassInput = document.getElementById("backupPassphraseInput");
  const backupStatusNote = document.getElementById("backupStatusNote");
  document.getElementById("exportBackupBtn")?.addEventListener("click", async () => {
    const pass = backupPassInput?.value || "";
    try {
      const env = await exportEncryptedBackup({
        operatorCallsign: state.callsign,
        nodeId: state.nodeId,
        claimedUsername: state.claimedUsername,
        x25519PublicKey: state.pubKeyB64,
        x25519PrivateKey: state.privKeyB64,
        meshChannel: state.passphrase
      }, pass);
      const blob = new Blob([env], { type: "text/plain;charset=utf-8" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `pulsemesh_${state.callsign.replace(/[^a-zA-Z0-9_-]/g, "") || "node"}.pmbackup`;
      a.click();
      if (backupStatusNote) backupStatusNote.textContent = "Exported AES-256-GCM encrypted .pmbackup.";
    } catch (err) {
      if (backupStatusNote) backupStatusNote.textContent = err.message || "Export failed.";
    }
  });
  document.getElementById("importBackupFileInput")?.addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = "";
    const pass = backupPassInput?.value || "";
    try {
      const raw = await file.text();
      const restored = await importEncryptedBackup(raw, pass);
      state.callsign = restored.operatorCallsign;
      state.nodeId = restored.nodeId || state.nodeId;
      state.claimedUsername = restored.claimedUsername;
      if (restored.x25519PublicKey && restored.x25519PrivateKey) {
        state.pubKeyB64 = restored.x25519PublicKey;
        state.privKeyB64 = restored.x25519PrivateKey;
      }
      saveConfig({
        callsign: state.callsign,
        nodeId: state.nodeId,
        claimedUsername: state.claimedUsername,
        pubKeyB64: state.pubKeyB64,
        privKeyB64: state.privKeyB64
      });
      refreshHeaderIdentity();
      if (backupStatusNote) backupStatusNote.textContent = `Restored ${state.callsign} (${state.nodeId}) from .pmbackup.`;
    } catch (err) {
      if (backupStatusNote) backupStatusNote.textContent = `Restore failed: ${err.message || err}`;
    }
  });

  // Settings Controls (4 ThemePresets + 3-state color-scheme + Language)
  setPresetSelect?.addEventListener("change", () => applyThemePreset(setPresetSelect.value, true));
  setThemeSelect?.addEventListener("change", () => applyColorScheme(setThemeSelect.value, true));
  document.getElementById("saveSettingsBtn").addEventListener("click", () => {
    state.callsign = setCallsignInput.value.trim() || state.callsign;
    state.passphrase = setPassphraseInput.value.trim() || "pulsemesh-tactical";
    applyThemePreset(setPresetSelect?.value || "practical", true);
    applyColorScheme(setThemeSelect?.value || "system", true);
    state.lang = setLangSelect.value === "el" ? "el" : "en";
    setLanguage(state.lang);
    saveConfig({
      callsign: state.callsign,
      passphrase: state.passphrase,
      lang: state.lang
    });
    refreshHeaderIdentity();
  });

  document.getElementById("purgePeersBtn").addEventListener("click", () => {
    state.peers.clear();
    renderPeersAndThreads();
    mapCtrl.render();
  });

  // Optional (Recommended) @username Account Dialog — Defaults to Reserve / Claim New!
  const accountDialog = document.getElementById("accountDialog");
  const accountAuthForm = document.getElementById("accountAuthForm");
  const modeReserveBtn = document.getElementById("modeReserveBtn");
  const modeRecoverBtn = document.getElementById("modeRecoverBtn");
  const accountUsernameInput = document.getElementById("accountUsernameInput");
  const accountSecretInput = document.getElementById("accountSecretInput");
  const toggleAccountSecretBtn = document.getElementById("toggleAccountSecretBtn");
  const closeAccountDialogBtn = document.getElementById("closeAccountDialogBtn");
  const accountSubmitBtn = document.getElementById("accountSubmitBtn");
  const accountStatusBox = document.getElementById("accountStatusBox");
  let accountRecoverMode = false;

  function setAccountMode(recover) {
    accountRecoverMode = Boolean(recover);
    modeReserveBtn.className = `btn${!accountRecoverMode ? " btn-emerald" : ""}`;
    modeRecoverBtn.className = `btn${accountRecoverMode ? " btn-primary" : ""}`;
    accountSecretInput.setAttribute("autocomplete", accountRecoverMode ? "current-password" : "new-password");
    accountSubmitBtn.textContent = accountRecoverMode ? t("signInSubmitBtn") : t("claimSubmitBtn");
    accountStatusBox.textContent = accountRecoverMode
      ? "Mode: Sign In / Recover with Password, 4-8 Digit PIN, or PMRK-... Key"
      : "Mode: Reserve New @username with Password (6+ chars) or 4-8 Digit PIN";
  }

  function openAccountDialog() {
    setAccountMode(false);
    accountUsernameInput.value = state.claimedUsername || "";
    accountSecretInput.value = "";
    accountSecretInput.type = "password";
    if (toggleAccountSecretBtn) toggleAccountSecretBtn.textContent = "Show";
    accountDialog.showModal();
  }

  if (toggleAccountSecretBtn) {
    toggleAccountSecretBtn.addEventListener("click", () => {
      const showing = accountSecretInput.type === "text";
      accountSecretInput.type = showing ? "password" : "text";
      toggleAccountSecretBtn.textContent = showing ? "Show" : "Hide";
      toggleAccountSecretBtn.setAttribute("aria-label", showing ? "Show password" : "Hide password");
    });
  }
  if (closeAccountDialogBtn) {
    closeAccountDialogBtn.addEventListener("click", () => accountDialog.close("close"));
  }

  document.getElementById("topSetupBtn").addEventListener("click", openAccountDialog);
  document.getElementById("openAccountDialogBtn").addEventListener("click", openAccountDialog);
  modeReserveBtn.addEventListener("click", () => setAccountMode(false));
  modeRecoverBtn.addEventListener("click", () => setAccountMode(true));

  accountAuthForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const uname = normalizeUsername(accountUsernameInput.value);
    const secret = accountSecretInput.value.trim();
    accountStatusBox.textContent = accountRecoverMode ? `Unlocking @${uname}...` : `Reserving @${uname}...`;
    try {
      if (accountRecoverMode) {
        const { username, vault } = await recoverUsernameAccount({ username: uname, secret });
        state.claimedUsername = username;
        state.nodeId = vault.nid;
        state.callsign = vault.dn || `@${username}`;
        state.authToken = vault.tok || "";
        state.privKeyB64 = vault.priv;
        state.pubKeyB64 = vault.pub;
        saveConfig({
          claimedUsername: state.claimedUsername,
          nodeId: state.nodeId,
          callsign: state.callsign,
          authToken: state.authToken,
          privKeyB64: state.privKeyB64,
          pubKeyB64: state.pubKeyB64
        });
        accountStatusBox.textContent = `Signed in as @${username}!`;
      } else {
        const res = await claimUsernameAccount({
          username: uname,
          pin: secret,
          nodeId: state.nodeId,
          displayName: state.callsign,
          privKeyB64: state.privKeyB64,
          pubKeyB64: state.pubKeyB64
        });
        state.claimedUsername = res.username;
        state.authToken = res.authToken;
        saveConfig({ claimedUsername: state.claimedUsername, authToken: state.authToken });
        accountStatusBox.textContent = `Reserved @${res.username}! Save Recovery Key: ${res.recoveryKey}`;
      }
      refreshHeaderIdentity();
      cfRealtime.connect();
    } catch (err) {
      accountStatusBox.textContent = `Error: ${err?.message || "Request failed"}`;
    }
  });

  // VAPID Web Push controls
  const pushStatusNote = document.getElementById("pushStatusNote");
  document.getElementById("enablePushBtn").addEventListener("click", async () => {
    try {
      await enableWebPush({
        relayBase: DEFAULT_CLOUDFLARE_RELAY,
        username: state.claimedUsername,
        authToken: state.authToken
      });
      pushStatusNote.textContent = `Web Push enabled for @${state.claimedUsername} (${isStandalonePwa() ? "Standalone PWA" : "Browser"}).`;
    } catch (err) {
      pushStatusNote.textContent = err?.message || "Failed to enable Web Push.";
    }
  });
  document.getElementById("disablePushBtn").addEventListener("click", async () => {
    await disableWebPush({
      relayBase: DEFAULT_CLOUDFLARE_RELAY,
      username: state.claimedUsername,
      authToken: state.authToken
    });
    pushStatusNote.textContent = "Web Push disabled.";
  });

  renderPeersAndThreads();
  renderMessages();
  await probeAndroidHotspotHost();
  if (state.claimedUsername && state.authToken) {
    cfRealtime.connect();
  }
}

boot();
