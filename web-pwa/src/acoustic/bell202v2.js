// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

import { DEFAULT_ECC_BYTES, rsDecodeWithStats, rsEncode } from "./reedSolomon.js";
import { sha256Bytes } from "../crypto/doubleRatchet.js";

/**
 * Acoustic Modem 2.0 (Bell 202 AFSK + Reed-Solomon GF(2^8) + AES-256-GCM).
 * Wire-compatible byte-for-byte with Android `Bell202AcousticModem.kt` for both:
 * - `"P2"` v2 frames (RS FEC + AES-256-GCM)
 * - `"PM"` v1 legacy frames (CRC-16/CCITT-FALSE)
 */

export const SAMPLE_RATE_DEFAULT = 44100;
export const SYMBOL_DURATION_MS = 20;
export const PREAMBLE_SYMBOLS = 4;

export const FREQ_PREAMBLE_HZ = 1650.0;
export const FREQ_MARK_1_HZ = 1200.0;
export const FREQ_SPACE_0_HZ = 2200.0;

export const SYNC_0 = 0x50; // 'P'
export const SYNC_1 = 0x4d; // 'M'
export const SYNC_V2_1 = 0x32; // '2'
export const FLAG_ENCRYPTED = 0x80;
export const V2_ECC_BYTES = DEFAULT_ECC_BYTES;
export const MAX_V2_PLAIN_BYTES = 72;
export const MAX_V2_CODEWORD_BYTES = 240;
const MIN_V2_CODEWORD_BYTES = 1 + 2 + 8 + 16 + V2_ECC_BYTES; // 35 bytes

export const TYPE_CHAT = 0x01;
export const TYPE_SOS = 0x02;
export const TYPE_CALL_RING = 0x03;
export const TYPE_KEY_VERIFY = 0x04;

const encUtf8 = (s) => new TextEncoder().encode(String(s || ""));
const decUtf8 = (b) => new TextDecoder().decode(b);
const SUBTLE = globalThis.crypto.subtle;

export function parseNodeIdShort(hexId) {
  const cleaned = String(hexId || "").trim().replace(/^0x/i, "").replace(/[^0-9a-f]/gi, "");
  if (!cleaned) return 0x0001;
  const tail = cleaned.slice(-4);
  const v = parseInt(tail, 16);
  return Number.isFinite(v) ? v & 0xffff : 0x0001;
}

export function crc16Ccitt(bytes, offset = 0, length = bytes.length - offset) {
  let crc = 0xffff;
  for (let i = offset; i < offset + length; i++) {
    crc ^= (bytes[i] & 0xff) << 8;
    for (let bit = 0; bit < 8; bit++) {
      crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc & 0xffff;
}

export async function deriveAcousticV2KeyBytes(passphrase) {
  const clean = String(passphrase || "").trim() || "pulsemesh-tactical";
  return sha256Bytes(`PULSEMESH_ACOUSTIC_V2:${clean}`);
}

function buildV2GcmIv(nonce8) {
  return new Uint8Array([
    0x50, 0x4d, 0x41, 0x32,
    nonce8[0], nonce8[1], nonce8[2], nonce8[3],
    nonce8[4], nonce8[5], nonce8[6], nonce8[7]
  ]);
}

function buildV2Aad(flagsByte, srcHi, srcLo) {
  return new Uint8Array([SYNC_0, SYNC_V2_1, flagsByte & 0xff, srcHi & 0xff, srcLo & 0xff]);
}

export async function encodeV2EncryptedFrameBytes(
  typeByte,
  srcNodeIdHex,
  text,
  passphrase = "pulsemesh-tactical",
  nonceOverride = null
) {
  let plain = encUtf8(text);
  if (plain.length > MAX_V2_PLAIN_BYTES) plain = plain.slice(0, MAX_V2_PLAIN_BYTES);

  const srcShort = parseNodeIdShort(srcNodeIdHex);
  const srcHi = (srcShort >>> 8) & 0xff;
  const srcLo = srcShort & 0xff;
  const flagsByte = FLAG_ENCRYPTED | (typeByte & 0x0f);

  const nonce8 = nonceOverride && nonceOverride.length >= 8
    ? nonceOverride.slice(0, 8)
    : globalThis.crypto.getRandomValues(new Uint8Array(8));

  const keyBytes = await deriveAcousticV2KeyBytes(passphrase);
  const key = await SUBTLE.importKey("raw", keyBytes, { name: "AES-GCM" }, false, ["encrypt"]);
  const ciphertext = new Uint8Array(
    await SUBTLE.encrypt(
      { name: "AES-GCM", iv: buildV2GcmIv(nonce8), additionalData: buildV2Aad(flagsByte, srcHi, srcLo), tagLength: 128 },
      key,
      plain
    )
  );

  const block = new Uint8Array(11 + ciphertext.length);
  block[0] = flagsByte & 0xff;
  block[1] = srcHi;
  block[2] = srcLo;
  block.set(nonce8, 3);
  block.set(ciphertext, 11);

  const codeword = rsEncode(block, V2_ECC_BYTES);
  const cwLen = codeword.length;
  const out = new Uint8Array(4 + cwLen);
  out[0] = SYNC_0;
  out[1] = SYNC_V2_1;
  out[2] = cwLen & 0xff;
  out[3] = (cwLen ^ 0xff) & 0xff;
  out.set(codeword, 4);
  return out;
}

export async function decodeV2EncryptedFrameBytes(frame, passphrase = "pulsemesh-tactical") {
  if (!(frame instanceof Uint8Array) || frame.length < 4 + MIN_V2_CODEWORD_BYTES) return null;
  if (frame[0] !== SYNC_0 || frame[1] !== SYNC_V2_1) return null;

  const lenA = frame[2] & 0xff;
  const lenB = (frame[3] & 0xff) ^ 0xff;
  const candidates = [];
  for (const cand of [lenA, lenB]) {
    if (!candidates.includes(cand) && cand >= MIN_V2_CODEWORD_BYTES && cand <= MAX_V2_CODEWORD_BYTES && 4 + cand <= frame.length) {
      candidates.push(cand);
    }
  }
  if (!candidates.length) return null;

  const keyBytes = await deriveAcousticV2KeyBytes(passphrase);
  const key = await SUBTLE.importKey("raw", keyBytes, { name: "AES-GCM" }, false, ["decrypt"]);

  for (const cwLen of candidates) {
    const codeword = frame.subarray(4, 4 + cwLen);
    const rs = rsDecodeWithStats(codeword, V2_ECC_BYTES);
    if (!rs) continue;
    const block = rs.data;
    if (block.length < 27) continue;

    const flagsByte = block[0] & 0xff;
    if ((flagsByte & FLAG_ENCRYPTED) === 0) continue;

    const srcHi = block[1] & 0xff;
    const srcLo = block[2] & 0xff;
    const srcShort = (srcHi << 8) | srcLo;
    const nonce8 = block.subarray(3, 11);
    const ciphertext = block.subarray(11);

    try {
      const plain = new Uint8Array(
        await SUBTLE.decrypt(
          { name: "AES-GCM", iv: buildV2GcmIv(nonce8), additionalData: buildV2Aad(flagsByte, srcHi, srcLo), tagLength: 128 },
          key,
          ciphertext
        )
      );
      return {
        typeByte: flagsByte & 0x0f,
        srcNodeIdHex: "0x" + srcShort.toString(16).toUpperCase().padStart(4, "0"),
        text: decUtf8(plain),
        isEncrypted: true,
        fecCorrectedBytes: rs.correctedErrors
      };
    } catch {
      continue;
    }
  }
  return null;
}

export function encodeV1FrameBytes(typeByte, srcNodeIdHex, text) {
  let payload = encUtf8(text);
  if (payload.length > 96) payload = payload.slice(0, 96);
  const srcShort = parseNodeIdShort(srcNodeIdHex);
  const body = new Uint8Array(4 + payload.length);
  body[0] = typeByte & 0xff;
  body[1] = (srcShort >>> 8) & 0xff;
  body[2] = srcShort & 0xff;
  body[3] = payload.length & 0xff;
  body.set(payload, 4);
  const crc = crc16Ccitt(body);
  const out = new Uint8Array(2 + body.length + 2);
  out[0] = SYNC_0;
  out[1] = SYNC_1;
  out.set(body, 2);
  out[2 + body.length] = (crc >>> 8) & 0xff;
  out[2 + body.length + 1] = crc & 0xff;
  return out;
}

export function decodeV1FrameBytes(frame) {
  if (!(frame instanceof Uint8Array) || frame.length < 8) return null;
  if (frame[0] !== SYNC_0 || frame[1] !== SYNC_1) return null;
  const typeByte = frame[2] & 0xff;
  const srcShort = ((frame[3] & 0xff) << 8) | (frame[4] & 0xff);
  const pLen = frame[5] & 0xff;
  if (pLen > 96 || frame.length < 2 + 4 + pLen + 2) return null;
  const calc = crc16Ccitt(frame, 2, 4 + pLen);
  const rx = ((frame[6 + pLen] & 0xff) << 8) | (frame[7 + pLen] & 0xff);
  if (calc !== rx) return null;
  return {
    typeByte,
    srcNodeIdHex: "0x" + srcShort.toString(16).toUpperCase().padStart(4, "0"),
    text: decUtf8(frame.subarray(6, 6 + pLen)),
    isEncrypted: false,
    fecCorrectedBytes: 0
  };
}

export function goertzelMagSqFloat(samples, offset, length, targetFreqHz, sampleRate) {
  if (length <= 0 || offset + length > samples.length) return 0;
  const k = Math.floor(0.5 + (length * targetFreqHz) / sampleRate);
  const omega = (2 * Math.PI * k) / length;
  const coeff = 2 * Math.cos(omega);
  let q0 = 0;
  let q1 = 0;
  let q2 = 0;
  for (let i = 0; i < length; i++) {
    q0 = coeff * q1 - q2 + samples[offset + i];
    q2 = q1;
    q1 = q0;
  }
  return q1 * q1 + q2 * q2 - q1 * q2 * coeff;
}

export function modulateFrameToFloat32(frameBytes, sampleRate = SAMPLE_RATE_DEFAULT) {
  const symSamples = Math.floor((sampleRate * SYMBOL_DURATION_MS) / 1000);
  const totalSymbols = PREAMBLE_SYMBOLS + frameBytes.length * 8;
  const out = new Float32Array(totalSymbols * symSamples);
  const rampSamples = Math.floor((sampleRate * 2) / 1000);
  let phase = 0;
  let idx = 0;

  const appendSymbol = (freqHz, isFirst, isLast) => {
    const dPhase = (2 * Math.PI * freqHz) / sampleRate;
    for (let i = 0; i < symSamples; i++) {
      let env = 1.0;
      if (isFirst && i < rampSamples) env = 0.5 * (1 - Math.cos((Math.PI * i) / rampSamples));
      else if (isLast && i > symSamples - rampSamples) {
        env = 0.5 * (1 - Math.cos((Math.PI * (symSamples - i)) / rampSamples));
      }
      out[idx++] = Math.sin(phase) * env * 0.55;
      phase += dPhase;
      if (phase >= 2 * Math.PI) phase -= 2 * Math.PI;
    }
  };

  for (let p = 0; p < PREAMBLE_SYMBOLS; p++) {
    appendSymbol(FREQ_PREAMBLE_HZ, p === 0, false);
  }
  const totalBits = frameBytes.length * 8;
  for (let bitPos = 0; bitPos < totalBits; bitPos++) {
    const byteIdx = Math.floor(bitPos / 8);
    const bitInByte = 7 - (bitPos % 8);
    const bit = (frameBytes[byteIdx] >>> bitInByte) & 1;
    appendSymbol(bit === 1 ? FREQ_MARK_1_HZ : FREQ_SPACE_0_HZ, false, bitPos === totalBits - 1);
  }
  return out;
}

export async function demodulateSamples(samples, sampleRate = SAMPLE_RATE_DEFAULT, passphrase = "pulsemesh-tactical") {
  const symSamples = Math.floor((sampleRate * SYMBOL_DURATION_MS) / 1000);
  if (samples.length < symSamples * (PREAMBLE_SYMBOLS + 64)) return null;

  const step = Math.max(1, Math.floor(symSamples / 4));
  let preambleStart = -1;
  let consecutive = 0;
  let offset = 0;

  while (offset + symSamples <= samples.length) {
    const pPre = goertzelMagSqFloat(samples, offset, symSamples, FREQ_PREAMBLE_HZ, sampleRate);
    const p1 = goertzelMagSqFloat(samples, offset, symSamples, FREQ_MARK_1_HZ, sampleRate);
    const p0 = goertzelMagSqFloat(samples, offset, symSamples, FREQ_SPACE_0_HZ, sampleRate);
    if (pPre > 2.0 && pPre > p1 * 1.4 && pPre > p0 * 1.4) {
      if (consecutive === 0) preambleStart = offset;
      consecutive++;
      offset += symSamples;
      if (consecutive >= PREAMBLE_SYMBOLS - 1) {
        while (offset + symSamples <= samples.length) {
          const pp = goertzelMagSqFloat(samples, offset, symSamples, FREQ_PREAMBLE_HZ, sampleRate);
          const pm = goertzelMagSqFloat(samples, offset, symSamples, FREQ_MARK_1_HZ, sampleRate);
          const ps = goertzelMagSqFloat(samples, offset, symSamples, FREQ_SPACE_0_HZ, sampleRate);
          if (pp > pm * 1.3 && pp > ps * 1.3) offset += symSamples;
          else break;
        }
        break;
      }
    } else {
      consecutive = 0;
      preambleStart = -1;
      offset += step;
    }
  }
  if (preambleStart < 0) return null;

  const readByteAt = (pos) => {
    if (pos + symSamples * 8 > samples.length) return null;
    let b = 0;
    for (let bitIdx = 0; bitIdx < 8; bitIdx++) {
      const sOff = pos + bitIdx * symSamples;
      const pMark = goertzelMagSqFloat(samples, sOff, symSamples, FREQ_MARK_1_HZ, sampleRate);
      const pSpace = goertzelMagSqFloat(samples, sOff, symSamples, FREQ_SPACE_0_HZ, sampleRate);
      b = (b << 1) | (pMark >= pSpace ? 1 : 0);
    }
    return b & 0xff;
  };

  let cursor = offset;
  const sync0 = readByteAt(cursor);
  cursor += symSamples * 8;
  const sync1 = readByteAt(cursor);
  cursor += symSamples * 8;
  if (sync0 !== SYNC_0) return null;

  if (sync1 === SYNC_V2_1) {
    const lenRaw = readByteAt(cursor);
    cursor += symSamples * 8;
    const lenInv = readByteAt(cursor);
    cursor += symSamples * 8;
    if (lenRaw === null || lenInv === null) return null;
    const lenAlt = lenInv ^ 0xff;
    const candidates = [];
    for (const c of [lenRaw, lenAlt]) {
      if (!candidates.includes(c) && c >= MIN_V2_CODEWORD_BYTES && c <= MAX_V2_CODEWORD_BYTES) {
        candidates.push(c);
      }
    }
    for (const cwLen of candidates) {
      const full = new Uint8Array(4 + cwLen);
      full[0] = SYNC_0;
      full[1] = SYNC_V2_1;
      full[2] = cwLen;
      full[3] = cwLen ^ 0xff;
      let c = cursor;
      let ok = true;
      for (let i = 0; i < cwLen; i++) {
        const v = readByteAt(c);
        if (v === null) {
          ok = false;
          break;
        }
        full[4 + i] = v;
        c += symSamples * 8;
      }
      if (ok) {
        const decoded = await decodeV2EncryptedFrameBytes(full, passphrase);
        if (decoded) return { frame: decoded, consumedTo: c };
      }
    }
    return null;
  }

  if (sync1 !== SYNC_1) return null;
  const typeByte = readByteAt(cursor);
  cursor += symSamples * 8;
  const srcHi = readByteAt(cursor);
  cursor += symSamples * 8;
  const srcLo = readByteAt(cursor);
  cursor += symSamples * 8;
  const pLen = readByteAt(cursor);
  cursor += symSamples * 8;
  if (typeByte === null || srcHi === null || srcLo === null || pLen === null || pLen > 96) return null;

  const full = new Uint8Array(6 + pLen + 2);
  full[0] = SYNC_0;
  full[1] = SYNC_1;
  full[2] = typeByte;
  full[3] = srcHi;
  full[4] = srcLo;
  full[5] = pLen;
  for (let i = 0; i < pLen + 2; i++) {
    const v = readByteAt(cursor);
    if (v === null) return null;
    full[6 + i] = v;
    cursor += symSamples * 8;
  }
  const decoded = decodeV1FrameBytes(full);
  return decoded ? { frame: decoded, consumedTo: cursor } : null;
}

export function createBell202Controller({ getNodeId, getPassphrase, onDecoded, onStatus }) {
  let audioCtx = null;
  let listening = false;
  let stream = null;
  let sourceNode = null;
  let procNode = null;
  let sampleBuf = [];
  let decodingBusy = false;

  const ensureAudioCtx = () => {
    if (!audioCtx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      audioCtx = new AC();
    }
    if (audioCtx.state === "suspended") audioCtx.resume().catch(() => {});
    return audioCtx;
  };

  async function transmit(typeByte, text) {
    const ctx = ensureAudioCtx();
    const frame = await encodeV2EncryptedFrameBytes(typeByte, getNodeId(), text, getPassphrase());
    const floatPcm = modulateFrameToFloat32(frame, ctx.sampleRate);
    const buffer = ctx.createBuffer(1, floatPcm.length, ctx.sampleRate);
    buffer.copyToChannel(floatPcm, 0, 0);
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(ctx.destination);
    src.start();
    onStatus?.(`TX Bell 202 V2 (${frame.length}B, RS-FEC+GCM) -> ${(floatPcm.length / ctx.sampleRate).toFixed(1)}s`);
  }

  async function toggleRx() {
    if (listening) {
      listening = false;
      try { procNode?.disconnect(); } catch {}
      try { sourceNode?.disconnect(); } catch {}
      try { stream?.getTracks().forEach((t) => t.stop()); } catch {}
      procNode = null;
      sourceNode = null;
      stream = null;
      sampleBuf = [];
      onStatus?.("Acoustic RX stopped.");
      return false;
    }

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      onStatus?.("Microphone requires HTTPS (:8443) on iOS/desktop.");
      return false;
    }

    const ctx = ensureAudioCtx();
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false }
    });
    sourceNode = ctx.createMediaStreamSource(stream);
    procNode = ctx.createScriptProcessor(4096, 1, 1);
    sampleBuf = [];
    listening = true;

    const maxSamples = Math.floor(ctx.sampleRate * 14);
    procNode.onaudioprocess = (ev) => {
      if (!listening) return;
      const input = ev.inputBuffer.getChannelData(0);
      for (let i = 0; i < input.length; i++) sampleBuf.push(input[i]);
      if (sampleBuf.length > maxSamples) {
        sampleBuf.splice(0, sampleBuf.length - maxSamples);
      }
      if (!decodingBusy && sampleBuf.length >= Math.floor(ctx.sampleRate * 3)) {
        decodingBusy = true;
        const snapshot = new Float32Array(sampleBuf);
        demodulateSamples(snapshot, ctx.sampleRate, getPassphrase())
          .then((res) => {
            if (res && res.frame) {
              sampleBuf.splice(0, Math.min(res.consumedTo, sampleBuf.length));
              onDecoded?.(res.frame);
            }
          })
          .finally(() => {
            decodingBusy = false;
          });
      }
    };

    sourceNode.connect(procNode);
    procNode.connect(ctx.destination);
    onStatus?.(`Acoustic V2 RX active @ ${ctx.sampleRate} Hz`);
    return true;
  }

  return {
    transmit,
    toggleRx,
    isListening: () => listening
  };
}
