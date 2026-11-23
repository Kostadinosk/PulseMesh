// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

/**
 * Signal-inspired symmetric 60-digit Safety Number matching Android SafetyNumberUtil.kt
 * digit-for-digit (5,200 iterated SHA-512 rounds over [0x00, 0x02, keyBytes, idBytes]).
 */
const HASH_ITERATIONS = 5200;
const VERSION_BYTE = 0x02;
const encoder = new TextEncoder();

function concatBytes(...arrays) {
  const total = arrays.reduce((sum, a) => sum + a.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) {
    out.set(a, off);
    off += a.length;
  }
  return out;
}

async function derive30Digits(nodeId, publicKey) {
  const idBytes = encoder.encode(String(nodeId || "").trim().toUpperCase());
  const keyBytes = encoder.encode(String(publicKey || "").trim());
  const header = new Uint8Array([0x00, VERSION_BYTE]);

  let digest = new Uint8Array(
    await crypto.subtle.digest("SHA-512", concatBytes(header, keyBytes, idBytes))
  );

  for (let i = 1; i < HASH_ITERATIONS; i++) {
    digest = new Uint8Array(
      await crypto.subtle.digest("SHA-512", concatBytes(digest, keyBytes))
    );
  }

  let out = "";
  for (let chunk = 0; chunk < 6; chunk++) {
    const off = chunk * 5;
    let value = 0n;
    for (let b = 0; b < 5; b++) {
      value = (value << 8n) | BigInt(digest[off + b]);
    }
    out += String(value % 100000n).padStart(5, "0");
  }
  return out;
}

export async function compute60DigitSafetyNumber(
  localNodeId,
  localPublicKey,
  peerNodeId,
  peerPublicKey
) {
  const localDigits = await derive30Digits(localNodeId, localPublicKey);
  const peerDigits = await derive30Digits(peerNodeId, peerPublicKey);
  const combined = localDigits <= peerDigits
    ? localDigits + peerDigits
    : peerDigits + localDigits;
  return combined.match(/.{1,5}/g).join(" ");
}

export function buildVerificationQrPayload(localNodeId, peerNodeId, safetyNumber) {
  const compact = String(safetyNumber || "").replace(/\s+/g, "");
  return `pulsemesh://verify?from=${encodeURIComponent(localNodeId)}&peer=${encodeURIComponent(peerNodeId)}&sn=${compact}`;
}

export function verifyScannedQrPayload(scannedRaw, expectedSafetyNumber) {
  const trimmed = String(scannedRaw || "").trim();
  if (!trimmed.startsWith("pulsemesh://verify")) return false;
  const snMatch = trimmed.match(/[?&]sn=([0-9]{60})(?:&|$)/);
  const expected = String(expectedSafetyNumber || "").replace(/\s+/g, "");
  return Boolean(snMatch && snMatch[1] === expected);
}
