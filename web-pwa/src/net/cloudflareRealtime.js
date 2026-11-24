// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

import {
  decryptAccountVault,
  deriveVaultUnlockProof,
  encryptAccountVault,
  hashVaultUnlockProof,
  sha256LowerHex,
  signMessageWithEcKey
} from "../crypto/doubleRatchet.js";

export const DEFAULT_CLOUDFLARE_RELAY = "https://pulsemesh-username-relay.pulsemesh.workers.dev";

export function normalizeUsername(raw) {
  return String(raw || "")
    .trim()
    .replace(/^@/, "")
    .toLowerCase()
    .replace(/[^a-z0-9_-]/g, "")
    .slice(0, 24);
}

export function normalizeRecoveryKey(raw) {
  return String(raw || "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
}

/**
 * Universal account secret validator shared identically with Android TacticalE2eeManager:
 * accepts either a 4-8 digit numeric PIN OR a 6-64 character password.
 */
export function isValidUniversalAccountSecret(raw) {
  const s = String(raw || "").trim();
  return /^\d{4,8}$/.test(s) || (s.length >= 6 && s.length <= 64);
}

export function randomHex(byteCount = 16) {
  const arr = globalThis.crypto.getRandomValues(new Uint8Array(byteCount));
  return Array.from(arr, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function generateRecoveryKey() {
  const hex = randomHex(16).toUpperCase();
  const parts = [];
  for (let i = 0; i < 32; i += 4) parts.push(hex.slice(i, i + 4));
  return `PMRK-${parts.join("-")}`;
}

export async function lookupPublicUser(relayBase, username) {
  const norm = normalizeUsername(username);
  if (norm.length < 3) return null;
  const res = await fetch(`${relayBase}/v1/users/${encodeURIComponent(norm)}`, {
    headers: { Accept: "application/json" }
  });
  if (!res.ok) return null;
  const data = await res.json();
  return data && data.found ? data.record : null;
}

export async function claimUsernameAccount({
  relayBase = DEFAULT_CLOUDFLARE_RELAY,
  username,
  pin,
  nodeId,
  displayName,
  privKeyB64,
  pubKeyB64
}) {
  const norm = normalizeUsername(username);
  if (norm.length < 3) throw new Error("Username must be 3-24 characters");
  const cleanPin = String(pin || "").trim();
  if (!isValidUniversalAccountSecret(cleanPin)) {
    throw new Error("Enter a 4-8 digit PIN or a password (6-64 characters)");
  }

  const saltHex = randomHex(16);
  const recoveryKey = generateRecoveryKey();
  const authToken = randomHex(32);
  const authVerifierHash = await sha256LowerHex(`PM_AUTH_V2:${authToken}`);
  const unlockProof = await deriveVaultUnlockProof(norm, cleanPin);
  const unlockVerifierHash = await hashVaultUnlockProof(unlockProof);

  const vaultPayload = {
    priv: privKeyB64,
    pub: pubKeyB64,
    nid: nodeId,
    dn: displayName || `@${norm}`,
    tok: authToken,
    rk: recoveryKey,
    ep: 1
  };
  const pinVault = await encryptAccountVault(norm, saltHex, cleanPin, vaultPayload);
  const recoveryVault = await encryptAccountVault(norm, saltHex, normalizeRecoveryKey(recoveryKey), vaultPayload);
  const claimedAt = Date.now();
  const canonical = `PM_CLAIM_V2|${norm}|${nodeId}|${pubKeyB64}|${claimedAt}`;
  const sig = await signMessageWithEcKey(privKeyB64, canonical);

  const res = await fetch(`${relayBase}/v1/users/claim`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      username: norm,
      nodeId,
      displayName: displayName || `@${norm}`,
      publicKeyBase64: pubKeyB64,
      saltHex,
      pinVault,
      recoveryVault,
      authVerifierHash,
      unlockVerifierHash,
      claimedAt,
      sig
    })
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(body.error || `HTTP ${res.status}`);
  }
  return { username: norm, recoveryKey, authToken };
}

export async function recoverUsernameAccount({
  relayBase = DEFAULT_CLOUDFLARE_RELAY,
  username,
  secret
}) {
  const norm = normalizeUsername(username);
  if (norm.length < 3) throw new Error("Username must be 3-24 characters");
  const cleanSecret = String(secret || "").trim();
  const isRecoveryKey = /^PMRK-/i.test(cleanSecret);
  if (!cleanSecret || (!isRecoveryKey && !isValidUniversalAccountSecret(cleanSecret))) {
    throw new Error("Password (6+ chars), 4-8 digit PIN, or PMRK-... Recovery Key is required");
  }
  const unlockSecret = isRecoveryKey ? normalizeRecoveryKey(cleanSecret) : cleanSecret;
  const unlockProof = await deriveVaultUnlockProof(norm, unlockSecret);

  const res = await fetch(`${relayBase}/v1/users/${encodeURIComponent(norm)}/vault`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-PulseMesh-Unlock-Proof": unlockProof
    },
    body: JSON.stringify({ unlockProof })
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.ok) {
    throw new Error(data.error || `Vault unlock failed (HTTP ${res.status})`);
  }

  let vault = null;
  if (data.pinVault && !isRecoveryKey) {
    vault = await decryptAccountVault(norm, data.saltHex, cleanSecret, data.pinVault);
  }
  if (!vault && data.recoveryVault) {
    vault = await decryptAccountVault(norm, data.saltHex, normalizeRecoveryKey(cleanSecret), data.recoveryVault);
  }
  if (!vault) {
    throw new Error("Incorrect Password, PIN, or Recovery Key");
  }
  return { username: norm, vault };
}

export async function fetchTurnIceServers({
  relayBase = DEFAULT_CLOUDFLARE_RELAY,
  username,
  authToken
}) {
  const fallback = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];
  if (!username || !authToken) return fallback;
  try {
    const res = await fetch(`${relayBase}/v1/turn/credentials`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-PulseMesh-Auth": authToken
      },
      body: JSON.stringify({ username })
    });
    if (!res.ok) return fallback;
    const data = await res.json();
    if (Array.isArray(data.iceServers) && data.iceServers.length) return data.iceServers;
  } catch {
    // Fall back gracefully to STUN
  }
  return fallback;
}

export function createCloudflareRealtimeClient({
  relayBase = DEFAULT_CLOUDFLARE_RELAY,
  getCredentials,
  onMessage,
  onStatus
}) {
  let ws = null;
  let reconnectTimer = null;
  let pingTimer = null;
  let closedByUser = false;
  let retryMs = 2000;

  function disconnect() {
    closedByUser = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    if (pingTimer) clearInterval(pingTimer);
    reconnectTimer = null;
    pingTimer = null;
    if (ws) {
      try { ws.close(); } catch {}
      ws = null;
    }
    onStatus?.("offline");
  }

  function connect(room = "") {
    closedByUser = false;
    const creds = getCredentials?.();
    if (!creds || !creds.username || !creds.authToken) {
      onStatus?.("idle");
      return;
    }
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

    const wsBase = relayBase.replace(/^http/, "ws");
    const targetRoom = room || `inbox:${creds.username}`;
    const url = `${wsBase}/v1/realtime/ws?username=${encodeURIComponent(creds.username)}&token=${encodeURIComponent(creds.authToken)}&room=${encodeURIComponent(targetRoom)}`;
    onStatus?.("connecting");

    try {
      ws = new WebSocket(url);
      ws.onopen = () => {
        retryMs = 2000;
        onStatus?.("connected");
        if (pingTimer) clearInterval(pingTimer);
        pingTimer = setInterval(() => {
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: "PING", ts: Date.now() }));
          }
        }, 25000);
      };
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          onMessage?.(msg);
        } catch {}
      };
      ws.onclose = () => {
        if (pingTimer) clearInterval(pingTimer);
        pingTimer = null;
        onStatus?.("disconnected");
        if (!closedByUser) {
          reconnectTimer = setTimeout(() => {
            retryMs = Math.min(retryMs * 2, 30000);
            connect(room);
          }, retryMs);
        }
      };
      ws.onerror = () => {
        try { ws?.close(); } catch {}
      };
    } catch {
      onStatus?.("error");
    }
  }

  function send(obj) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    ws.send(JSON.stringify(obj));
    return true;
  }

  return { connect, disconnect, send };
}
