// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

/**
 * PulseMesh v2.0 Encrypted Offline Backup (.pmbackup)
 * Interoperable byte-for-byte with Android EncryptedBackupManager.kt:
 * Envelope: PMBK2:<base64(salt16 || iv12 || aes256gcmCiphertextWithTag)>
 * KDF: PBKDF2-HMAC-SHA256 (600,000 iterations), AAD: "PM_BACKUP_V2"
 */
export const BACKUP_MAGIC_PREFIX = "PMBK2:";
const DEFAULT_ITERATIONS = 600_000;
const SALT_LEN = 16;
const IV_LEN = 12;
const encoder = new TextEncoder();
const decoder = new TextDecoder();
const AAD_BYTES = encoder.encode("PM_BACKUP_V2");

function toBase64(bytes) {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function fromBase64(b64) {
  const clean = b64.replace(/\s+/g, "");
  const bin = atob(clean);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function deriveAesKey(passphrase, salt, iterations) {
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    encoder.encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"]
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt, iterations, hash: "SHA-256" },
    keyMaterial,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

export async function exportEncryptedBackup(payload, passphrase, iterations = DEFAULT_ITERATIONS) {
  if (!passphrase || passphrase.length < 8) {
    throw new Error("Backup passphrase must be at least 8 characters.");
  }
  const plainBytes = encoder.encode(JSON.stringify({
    v: 2,
    exportedAt: Date.now(),
    callsign: payload.operatorCallsign || "Operator",
    nodeId: payload.nodeId || "",
    username: payload.claimedUsername || "",
    pub: payload.x25519PublicKey || "",
    priv: payload.x25519PrivateKey || "",
    ice: payload.iceMedicalInfo || "",
    channel: payload.meshChannel || "pulsemesh_live_primary",
    contacts: payload.contacts || [],
    groups: payload.groups || [],
    messages: payload.messages || []
  }));

  const salt = crypto.getRandomValues(new Uint8Array(SALT_LEN));
  const iv = crypto.getRandomValues(new Uint8Array(IV_LEN));
  const key = await deriveAesKey(passphrase, salt, iterations);

  const ct = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: AAD_BYTES, tagLength: 128 },
      key,
      plainBytes
    )
  );

  const out = new Uint8Array(SALT_LEN + IV_LEN + ct.length);
  out.set(salt, 0);
  out.set(iv, SALT_LEN);
  out.set(ct, SALT_LEN + IV_LEN);
  return BACKUP_MAGIC_PREFIX + toBase64(out);
}

export async function importEncryptedBackup(encodedEnvelope, passphrase, iterations = DEFAULT_ITERATIONS) {
  const trimmed = String(encodedEnvelope || "").trim();
  if (!trimmed.startsWith(BACKUP_MAGIC_PREFIX)) {
    throw new Error("Invalid .pmbackup header (expected PMBK2:).");
  }
  const raw = fromBase64(trimmed.slice(BACKUP_MAGIC_PREFIX.length));
  if (raw.length <= SALT_LEN + IV_LEN + 16) {
    throw new Error("Truncated .pmbackup payload.");
  }
  const salt = raw.slice(0, SALT_LEN);
  const iv = raw.slice(SALT_LEN, SALT_LEN + IV_LEN);
  const ct = raw.slice(SALT_LEN + IV_LEN);
  const key = await deriveAesKey(passphrase, salt, iterations);

  const plainBuf = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv, additionalData: AAD_BYTES, tagLength: 128 },
    key,
    ct
  );
  const obj = JSON.parse(decoder.decode(plainBuf));
  return {
    formatVersion: obj.v ?? 2,
    exportedAtEpochMs: obj.exportedAt ?? Date.now(),
    operatorCallsign: obj.callsign || "Operator",
    nodeId: obj.nodeId || "",
    claimedUsername: obj.username || "",
    x25519PublicKey: obj.pub || "",
    x25519PrivateKey: obj.priv || "",
    iceMedicalInfo: obj.ice || "",
    meshChannel: obj.channel || "pulsemesh_live_primary",
    contacts: Array.isArray(obj.contacts) ? obj.contacts : [],
    groups: Array.isArray(obj.groups) ? obj.groups : [],
    messages: Array.isArray(obj.messages) ? obj.messages : []
  };
}
