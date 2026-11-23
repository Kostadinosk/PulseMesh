// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

/**
 * PulseMesh Zero-Trust WebCrypto Engine:
 * - Signal-style Double Ratchet (`PMDR2:`) byte-compatible with Android DoubleRatchetEngine.kt
 * - ECDH P-256 + AES-256-GCM Direct Messages (`E2EE_ECDH_V2:`) with ZERO plaintext downgrades
 * - AES-256-GCM Tactical Broadcast Channels (`PM2:`)
 * - PBKDF2-HMAC-SHA256 Encrypted Account Vaults (`PV1:`)
 */

const SUBTLE = globalThis.crypto.subtle;
const encUtf8 = (s) => new TextEncoder().encode(String(s || ""));
const decUtf8 = (b) => new TextDecoder().decode(b);

export const DM_PREFIX_ECDH = "E2EE_ECDH_V2:";
export const DR_PREFIX = "PMDR2:";
export const CHANNEL_PREFIX = "PM2:";
export const VAULT_PREFIX = "PV2:";
export const VAULT_PREFIX_LEGACY = "PV1:";

const ECDH_KDF_INFO = "PULSEMESH_ECDH_HKDF_V2";
const DEFAULT_DM_AAD = encUtf8("PM_DM_V2");
const CHANNEL_AES_SALT = "PULSEMESH_CHANNEL_AES_V2:";
const DR_INIT_INFO = "PM_DR_INIT_RK";
const DR_RK_INFO = "PULSEMESH_DR_RK_V2";
const PBKDF2_ITERATIONS = 600000;
const PBKDF2_LEGACY_ITERATIONS = 65536;
const MAX_SKIP = 100;

export function bytesToB64(bytes) {
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let bin = "";
  const chunk = 0x8000;
  for (let i = 0; i < arr.length; i += chunk) {
    bin += String.fromCharCode(...arr.subarray(i, i + chunk));
  }
  return btoa(bin);
}

export function b64ToBytes(b64) {
  const bin = atob(String(b64 || "").replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function sha256Bytes(data) {
  const buf = typeof data === "string" ? encUtf8(data) : data;
  return new Uint8Array(await SUBTLE.digest("SHA-256", buf));
}

export async function sha256LowerHex(data) {
  const digest = await sha256Bytes(data);
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function format12HexFingerprint(hashBytes) {
  const hex = Array.from(new Uint8Array(hashBytes).slice(0, 6))
    .map((b) => b.toString(16).padStart(2, "0").toUpperCase())
    .join("");
  return `${hex.slice(0, 4)}-${hex.slice(4, 8)}-${hex.slice(8, 12)}`;
}

export async function generateIdentityKeyPair() {
  const kp = await SUBTLE.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const spki = new Uint8Array(await SUBTLE.exportKey("spki", kp.publicKey));
  const pkcs8 = new Uint8Array(await SUBTLE.exportKey("pkcs8", kp.privateKey));
  const privKeyB64 = bytesToB64(pkcs8);
  const pubKeyB64 = bytesToB64(spki);
  const fingerprint = format12HexFingerprint(await sha256Bytes(spki));
  return { privKeyB64, pubKeyB64, fingerprint };
}

export async function aesGcmEncryptB64(plainBytes, rawKeyBytesOrCryptoKey, aadBytes = null) {
  const key = rawKeyBytesOrCryptoKey instanceof Uint8Array
    ? await SUBTLE.importKey("raw", rawKeyBytesOrCryptoKey, { name: "AES-GCM" }, false, ["encrypt"])
    : rawKeyBytesOrCryptoKey;
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const params = aadBytes && aadBytes.length
    ? { name: "AES-GCM", iv, additionalData: aadBytes, tagLength: 128 }
    : { name: "AES-GCM", iv, tagLength: 128 };
  const ct = new Uint8Array(await SUBTLE.encrypt(params, key, plainBytes));
  const combined = new Uint8Array(12 + ct.length);
  combined.set(iv, 0);
  combined.set(ct, 12);
  return bytesToB64(combined);
}

export async function aesGcmDecryptBytes(b64, rawKeyBytesOrCryptoKey, aadBytes = null) {
  const combined = b64ToBytes(b64);
  if (combined.length <= 12) throw new Error("Ciphertext too short");
  const key = rawKeyBytesOrCryptoKey instanceof Uint8Array
    ? await SUBTLE.importKey("raw", rawKeyBytesOrCryptoKey, { name: "AES-GCM" }, false, ["decrypt"])
    : rawKeyBytesOrCryptoKey;
  const iv = combined.subarray(0, 12);
  const ct = combined.subarray(12);
  const params = aadBytes && aadBytes.length
    ? { name: "AES-GCM", iv, additionalData: aadBytes, tagLength: 128 }
    : { name: "AES-GCM", iv, tagLength: 128 };
  return new Uint8Array(await SUBTLE.decrypt(params, key, ct));
}

// =========================================================================
// CHANNEL AES & ACCOUNT VAULTS
// =========================================================================

export async function deriveChannelAesKey(passphrase) {
  const clean = String(passphrase || "").trim() || "pulsemesh-tactical";
  const keyBytes = await sha256Bytes(CHANNEL_AES_SALT + clean);
  return SUBTLE.importKey("raw", keyBytes, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

export async function encryptChannelPayload(jsonStr, passphrase) {
  const key = await deriveChannelAesKey(passphrase);
  return CHANNEL_PREFIX + (await aesGcmEncryptB64(encUtf8(jsonStr), key));
}

export async function decryptChannelPayload(envelope, passphrase) {
  const trimmed = String(envelope || "").trim();
  if (!trimmed.startsWith(CHANNEL_PREFIX)) return null;
  try {
    const key = await deriveChannelAesKey(passphrase);
    const plain = await aesGcmDecryptBytes(trimmed.slice(CHANNEL_PREFIX.length), key);
    return decUtf8(plain);
  } catch {
    return null;
  }
}

export async function derivePbkdf2VaultKey(username, saltHex, secret, iterations = PBKDF2_ITERATIONS) {
  const norm = String(username || "").trim().replace(/^@/, "").toLowerCase();
  const saltBytes = encUtf8(`PM_VAULT_V2:${norm}:${saltHex}`);
  const baseKey = await SUBTLE.importKey("raw", encUtf8(secret), "PBKDF2", false, ["deriveKey"]);
  return SUBTLE.deriveKey(
    { name: "PBKDF2", salt: saltBytes, iterations, hash: "SHA-256" },
    baseKey,
    { name: "AES-GCM", length: 256 },
    true,
    ["encrypt", "decrypt"]
  );
}

export async function deriveVaultUnlockProof(username, secret) {
  const norm = String(username || "").trim().replace(/^@/, "").toLowerCase();
  const salt = `PM_UNLOCK_SALT_V3:${norm}`;
  const key = await derivePbkdf2VaultKey(norm, salt, String(secret || "").trim(), 100000);
  const raw = new Uint8Array(await SUBTLE.exportKey("raw", key));
  return Array.from(raw, (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function hashVaultUnlockProof(unlockProof) {
  return sha256LowerHex(`PM_VAULT_PROOF_V3:${String(unlockProof || "").trim()}`);
}

export async function signMessageWithEcKey(privKeyB64, message) {
  const ecdhPriv = await SUBTLE.importKey(
    "pkcs8",
    b64ToBytes(privKeyB64),
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"]
  );
  const jwk = await SUBTLE.exportKey("jwk", ecdhPriv);
  delete jwk.key_ops;
  const ecdsaPriv = await SUBTLE.importKey(
    "jwk",
    jwk,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"]
  );
  const sig = new Uint8Array(
    await SUBTLE.sign({ name: "ECDSA", hash: "SHA-256" }, ecdsaPriv, encUtf8(message))
  );
  return bytesToB64(sig);
}

export async function encryptAccountVault(username, saltHex, secret, payloadObj) {
  const norm = String(username || "").trim().replace(/^@/, "").toLowerCase();
  const key = await derivePbkdf2VaultKey(norm, saltHex, secret, PBKDF2_ITERATIONS);
  const aad = encUtf8(`PM_VAULT_V3:${norm}:${saltHex}`);
  return VAULT_PREFIX + (await aesGcmEncryptB64(encUtf8(JSON.stringify(payloadObj)), key, aad));
}

export async function decryptAccountVault(username, saltHex, secret, encVault) {
  const trimmed = String(encVault || "").trim();
  const norm = String(username || "").trim().replace(/^@/, "").toLowerCase();
  if (!saltHex || !secret) return null;
  const isV2 = trimmed.startsWith(VAULT_PREFIX);
  const isV1 = trimmed.startsWith(VAULT_PREFIX_LEGACY);
  if (!isV2 && !isV1) return null;
  try {
    const iters = isV2 ? PBKDF2_ITERATIONS : PBKDF2_LEGACY_ITERATIONS;
    const prefix = isV2 ? VAULT_PREFIX : VAULT_PREFIX_LEGACY;
    const aad = isV2 ? encUtf8(`PM_VAULT_V3:${norm}:${saltHex}`) : null;
    const key = await derivePbkdf2VaultKey(norm, saltHex, secret, iters);
    const plain = await aesGcmDecryptBytes(trimmed.slice(prefix.length), key, aad);
    const obj = JSON.parse(decUtf8(plain));
    if (!obj.priv || !obj.pub || !obj.nid) return null;
    return obj;
  } catch {
    return null;
  }
}

// =========================================================================
// ECDH P-256 DIRECT MESSAGES (ZERO PLAINTEXT DOWNGRADE)
// =========================================================================

export async function ecdhSharedBits(myPrivB64, peerPubB64) {
  const priv = await SUBTLE.importKey("pkcs8", b64ToBytes(myPrivB64), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const pub = await SUBTLE.importKey("spki", b64ToBytes(peerPubB64), { name: "ECDH", namedCurve: "P-256" }, false, []);
  return new Uint8Array(await SUBTLE.deriveBits({ name: "ECDH", public: pub }, priv, 256));
}

export async function deriveEcdhAesKey(myPrivB64, peerPubB64) {
  const shared = await ecdhSharedBits(myPrivB64, peerPubB64);
  const aesBytes = await hkdfDeriveBytes(shared, new Uint8Array(0), encUtf8(ECDH_KDF_INFO), 32);
  return SUBTLE.importKey("raw", aesBytes, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

export async function encryptDmEcdh(plaintext, myPrivB64, peerPubB64) {
  if (!myPrivB64 || !peerPubB64) return null;
  try {
    const key = await deriveEcdhAesKey(myPrivB64, peerPubB64);
    return DM_PREFIX_ECDH + (await aesGcmEncryptB64(encUtf8(plaintext), key, DEFAULT_DM_AAD));
  } catch {
    return null;
  }
}

export async function decryptDmEcdh(payload, myPrivB64, peerPubB64) {
  const trimmed = String(payload || "").trim();
  if (!trimmed) return "";
  if (!trimmed.startsWith(DM_PREFIX_ECDH)) {
    return "[Encrypted / Key Verification Failed]";
  }
  if (!myPrivB64 || !peerPubB64) return "[E2EE DM - Key Exchange Pending]";
  try {
    const key = await deriveEcdhAesKey(myPrivB64, peerPubB64);
    const plain = await aesGcmDecryptBytes(trimmed.slice(DM_PREFIX_ECDH.length), key, DEFAULT_DM_AAD);
    return decUtf8(plain);
  } catch {
    return "[Encrypted / Key Verification Failed]";
  }
}

// =========================================================================
// DOUBLE RATCHET ENGINE (PMDR2: - EXACT PARITY WITH ANDROID)
// =========================================================================

async function hkdfDeriveBytes(ikmBytes, saltBytes, infoBytes, length = 32) {
  const baseKey = await SUBTLE.importKey("raw", ikmBytes, "HKDF", false, ["deriveBits"]);
  const bits = await SUBTLE.deriveBits(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: saltBytes && saltBytes.length ? saltBytes : new Uint8Array(0),
      info: infoBytes
    },
    baseKey,
    length * 8
  );
  return new Uint8Array(bits);
}

async function kdfRootKey(rootKeyBytes, dhOutBytes) {
  const out = await hkdfDeriveBytes(dhOutBytes, rootKeyBytes, encUtf8(DR_RK_INFO), 64);
  return [out.subarray(0, 32), out.subarray(32, 64)];
}

async function kdfChainKey(chainKeyBytes) {
  const macKey = await SUBTLE.importKey("raw", chainKeyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const msgKey = new Uint8Array(await SUBTLE.sign("HMAC", macKey, new Uint8Array([0x01])));
  const nextChain = new Uint8Array(await SUBTLE.sign("HMAC", macKey, new Uint8Array([0x02])));
  return [nextChain, msgKey];
}

function buildHeaderAad(dhPub, n, pn, extraAad = null) {
  const head = encUtf8(`PMDR2|${dhPub}|${n}|${pn}|`);
  if (!extraAad || !extraAad.length) return head;
  const out = new Uint8Array(head.length + extraAad.length);
  out.set(head, 0);
  out.set(extraAad, head.length);
  return out;
}

export async function initDoubleRatchetInitiator(peerId, rootSharedSecretBytes, peerDhPubB64) {
  const dhs = await generateIdentityKeyPair();
  const dhOut = await ecdhSharedBits(dhs.privKeyB64, peerDhPubB64);
  const initialRk = await hkdfDeriveBytes(rootSharedSecretBytes, new Uint8Array(0), encUtf8(DR_INIT_INFO), 32);
  const [rk, cks] = await kdfRootKey(initialRk, dhOut);
  return {
    peerId,
    rk: bytesToB64(rk),
    cks: bytesToB64(cks),
    ckr: "",
    dhsPriv: dhs.privKeyB64,
    dhsPub: dhs.pubKeyB64,
    dhrPub: peerDhPubB64.trim(),
    ns: 0,
    nr: 0,
    pn: 0,
    skipped: {}
  };
}

export async function initDoubleRatchetResponder(peerId, rootSharedSecretBytes, localDhPrivB64, localDhPubB64) {
  const initialRk = await hkdfDeriveBytes(rootSharedSecretBytes, new Uint8Array(0), encUtf8(DR_INIT_INFO), 32);
  return {
    peerId,
    rk: bytesToB64(initialRk),
    cks: "",
    ckr: "",
    dhsPriv: localDhPrivB64.trim(),
    dhsPub: localDhPubB64.trim(),
    dhrPub: "",
    ns: 0,
    nr: 0,
    pn: 0,
    skipped: {}
  };
}

export async function doubleRatchetEncrypt(session, plaintextUtf8OrBytes, extraAad = null) {
  let s = { ...session, skipped: { ...(session.skipped || {}) } };
  if (!s.cks) {
    if (!s.dhrPub) return null;
    const nextDh = await generateIdentityKeyPair();
    const dhOut = await ecdhSharedBits(nextDh.privKeyB64, s.dhrPub);
    const [newRk, newCks] = await kdfRootKey(b64ToBytes(s.rk), dhOut);
    s.rk = bytesToB64(newRk);
    s.cks = bytesToB64(newCks);
    s.dhsPriv = nextDh.privKeyB64;
    s.dhsPub = nextDh.pubKeyB64;
    s.pn = s.ns;
    s.ns = 0;
  }

  const [nextCks, msgKey] = await kdfChainKey(b64ToBytes(s.cks));
  const n = s.ns;
  const aad = buildHeaderAad(s.dhsPub, n, s.pn, extraAad);
  const plain = typeof plaintextUtf8OrBytes === "string" ? encUtf8(plaintextUtf8OrBytes) : plaintextUtf8OrBytes;
  const ctB64 = await aesGcmEncryptB64(plain, msgKey, aad);

  s.cks = bytesToB64(nextCks);
  s.ns = n + 1;

  const envJson = JSON.stringify({ dh: s.dhsPub, n, pn: s.pn, ct: ctB64 });
  const payload = DR_PREFIX + bytesToB64(encUtf8(envJson));
  return { payload, session: s };
}

async function skipMessageKeys(s, untilN) {
  if (untilN - s.nr > MAX_SKIP) return null;
  if (!s.ckr) {
    return untilN === s.nr ? s : null;
  }
  let curCk = b64ToBytes(s.ckr);
  let curN = s.nr;
  while (curN < untilN) {
    const [nextCk, mk] = await kdfChainKey(curCk);
    curCk = nextCk;
    s.skipped[`${s.dhrPub}|${curN}`] = bytesToB64(mk);
    const keys = Object.keys(s.skipped);
    if (keys.length > MAX_SKIP) {
      delete s.skipped[keys[0]];
    }
    curN++;
  }
  s.ckr = bytesToB64(curCk);
  s.nr = curN;
  return s;
}

async function dhRatchetStep(s, newRemoteDhPub) {
  const dhRecv = await ecdhSharedBits(s.dhsPriv, newRemoteDhPub);
  const [rk1, ckr] = await kdfRootKey(b64ToBytes(s.rk), dhRecv);
  const nextDh = await generateIdentityKeyPair();
  const dhSend = await ecdhSharedBits(nextDh.privKeyB64, newRemoteDhPub);
  const [rk2, cks] = await kdfRootKey(rk1, dhSend);
  return {
    ...s,
    rk: bytesToB64(rk2),
    cks: bytesToB64(cks),
    ckr: bytesToB64(ckr),
    dhsPriv: nextDh.privKeyB64,
    dhsPub: nextDh.pubKeyB64,
    dhrPub: newRemoteDhPub,
    pn: s.ns,
    ns: 0,
    nr: 0
  };
}

export async function doubleRatchetDecrypt(session, payload, extraAad = null) {
  const trimmed = String(payload || "").trim();
  if (!trimmed.startsWith(DR_PREFIX)) return null;
  let env;
  try {
    env = JSON.parse(decUtf8(b64ToBytes(trimmed.slice(DR_PREFIX.length))));
  } catch {
    return null;
  }
  if (!env || !env.dh || typeof env.n !== "number" || !env.ct) return null;
  const aad = buildHeaderAad(env.dh, env.n, env.pn || 0, extraAad);
  let s = { ...session, skipped: { ...(session.skipped || {}) } };

  const skipKey = `${env.dh}|${env.n}`;
  if (s.skipped[skipKey]) {
    try {
      const plain = await aesGcmDecryptBytes(env.ct, b64ToBytes(s.skipped[skipKey]), aad);
      delete s.skipped[skipKey];
      return { plaintextBytes: plain, plaintext: decUtf8(plain), session: s };
    } catch {
      return null;
    }
  }

  try {
    if (env.dh !== s.dhrPub) {
      s = await skipMessageKeys(s, env.pn || 0);
      if (!s) return null;
      s = await dhRatchetStep(s, env.dh);
    }
    s = await skipMessageKeys(s, env.n);
    if (!s || !s.ckr) return null;
    const [nextCkr, msgKey] = await kdfChainKey(b64ToBytes(s.ckr));
    const plain = await aesGcmDecryptBytes(env.ct, msgKey, aad);
    s.ckr = bytesToB64(nextCkr);
    s.nr = s.nr + 1;
    return { plaintextBytes: plain, plaintext: decUtf8(plain), session: s };
  } catch {
    return null;
  }
}
