// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

/**
 * Systematic Reed-Solomon GF(2^8) Forward Error Correction.
 * Byte-for-byte identical to Android Kotlin `ReedSolomonFec.kt`:
 * - Primitive polynomial 0x11D (x^8 + x^4 + x^3 + x^2 + 1)
 * - Generator alpha = 2, FCR alpha^0 = 1
 * - Repairs up to t = floor(eccBytes / 2) = 4 corrupted bytes for DEFAULT_ECC_BYTES = 8
 */

export const DEFAULT_ECC_BYTES = 8;
const PRIMITIVE_POLY = 0x11d;

const GF_EXP = new Uint16Array(512);
const GF_LOG = new Uint16Array(256);

{
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if ((x & 0x100) !== 0) {
      x ^= PRIMITIVE_POLY;
    }
  }
  for (let i = 255; i < 512; i++) {
    GF_EXP[i] = GF_EXP[i - 255];
  }
}

function gfMul(a, b) {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

function gfDiv(a, b) {
  if (b === 0) throw new Error("Division by zero in GF(2^8)");
  if (a === 0) return 0;
  return GF_EXP[(GF_LOG[a] + 255 - GF_LOG[b]) % 255];
}

function gfEvalHighestFirst(poly, x) {
  let y = 0;
  for (let i = 0; i < poly.length; i++) {
    y = gfMul(y, x) ^ (poly[i] & 0xff);
  }
  return y;
}

function polyMul(p, q) {
  const out = new Uint8Array(p.length + q.length - 1);
  for (let i = 0; i < p.length; i++) {
    const pi = p[i];
    if (pi === 0) continue;
    for (let j = 0; j < q.length; j++) {
      const qj = q[j];
      if (qj !== 0) out[i + j] ^= gfMul(pi, qj);
    }
  }
  return out;
}

function generatorPoly(eccBytes) {
  let g = new Uint8Array([1]);
  for (let i = 0; i < eccBytes; i++) {
    g = polyMul(g, new Uint8Array([1, GF_EXP[i]]));
  }
  return g;
}

export function rsEncode(data, eccBytes = DEFAULT_ECC_BYTES) {
  if (!(data instanceof Uint8Array) || data.length === 0) {
    throw new Error("Reed-Solomon payload cannot be empty");
  }
  if (eccBytes < 2 || eccBytes > 32 || (eccBytes & 1) !== 0) {
    throw new Error("eccBytes must be an even integer in 2..32");
  }
  if (data.length + eccBytes > 255) {
    throw new Error("RS(255, k) codeword cannot exceed 255 bytes");
  }

  const gen = generatorPoly(eccBytes);
  const work = new Uint8Array(data.length + eccBytes);
  work.set(data, 0);

  for (let i = 0; i < data.length; i++) {
    const coef = work[i];
    if (coef !== 0) {
      for (let j = 1; j < gen.length; j++) {
        work[i + j] ^= gfMul(gen[j], coef);
      }
    }
  }

  const out = new Uint8Array(data.length + eccBytes);
  out.set(data, 0);
  out.set(work.subarray(data.length), data.length);
  return out;
}

export function rsDecodeWithStats(codeword, eccBytes = DEFAULT_ECC_BYTES) {
  if (eccBytes < 2 || eccBytes > 32 || (eccBytes & 1) !== 0) return null;
  if (!(codeword instanceof Uint8Array) || codeword.length <= eccBytes || codeword.length > 255) {
    return null;
  }

  const dataLen = codeword.length - eccBytes;
  const syndromes = new Uint8Array(eccBytes);
  let anyNonZero = false;
  for (let i = 0; i < eccBytes; i++) {
    const s = gfEvalHighestFirst(codeword, GF_EXP[i]);
    syndromes[i] = s;
    if (s !== 0) anyNonZero = true;
  }
  if (!anyNonZero) {
    return { data: codeword.slice(0, dataLen), correctedErrors: 0 };
  }

  // Berlekamp-Massey algorithm
  let c = new Uint8Array([1]);
  let b = new Uint8Array([1]);
  let l = 0;
  let m = 1;
  let bScalar = 1;

  for (let n = 0; n < eccBytes; n++) {
    let d = syndromes[n];
    for (let i = 1; i <= l; i++) {
      if (i < c.length) d ^= gfMul(c[i], syndromes[n - i]);
    }
    if (d === 0) {
      m += 1;
    } else {
      const t = c.slice();
      const factor = gfDiv(d, bScalar);
      const next = new Uint8Array(Math.max(c.length, b.length + m));
      next.set(c, 0);
      for (let i = 0; i < b.length; i++) {
        next[i + m] ^= gfMul(factor, b[i]);
      }
      c = next;
      if (2 * l <= n) {
        l = n + 1 - l;
        b = t;
        bScalar = d;
        m = 1;
      } else {
        m += 1;
      }
    }
  }

  if (l === 0 || l > eccBytes >> 1) return null;
  let actualDeg = -1;
  for (let i = c.length - 1; i >= 0; i--) {
    if (c[i] !== 0) {
      actualDeg = i;
      break;
    }
  }
  if (actualDeg !== l) return null;

  // Chien search
  const errIndices = [];
  const errX = [];
  const errXInv = [];

  for (let idx = 0; idx < codeword.length; idx++) {
    const power = codeword.length - 1 - idx;
    const xVal = GF_EXP[power % 255];
    const xInv = GF_EXP[(255 - (power % 255)) % 255];
    let evalVal = 0;
    let xPow = 1;
    for (let i = 0; i < c.length; i++) {
      if (c[i] !== 0) evalVal ^= gfMul(c[i], xPow);
      xPow = gfMul(xPow, xInv);
    }
    if (evalVal === 0) {
      if (errIndices.length >= l) return null;
      errIndices.push(idx);
      errX.push(xVal);
      errXInv.push(xInv);
    }
  }
  if (errIndices.length !== l) return null;

  // Error evaluator Omega(x) = (S(x) * Lambda(x)) mod x^eccBytes
  const omega = new Uint8Array(eccBytes);
  for (let i = 0; i < eccBytes; i++) {
    let acc = 0;
    const maxJ = Math.min(i, actualDeg);
    for (let j = 0; j <= maxJ; j++) {
      acc ^= gfMul(syndromes[i - j], c[j]);
    }
    omega[i] = acc;
  }

  // Forney magnitude recovery
  const corrected = codeword.slice();
  for (let k = 0; k < l; k++) {
    const xInv = errXInv[k];
    let omegaEval = 0;
    let xPow = 1;
    for (let i = 0; i < eccBytes; i++) {
      if (omega[i] !== 0) omegaEval ^= gfMul(omega[i], xPow);
      xPow = gfMul(xPow, xInv);
    }

    let derivEval = 0;
    let derivPow = 1;
    for (let i = 1; i <= actualDeg; i += 2) {
      if (c[i] !== 0) derivEval ^= gfMul(c[i], derivPow);
      derivPow = gfMul(derivPow, gfMul(xInv, xInv));
    }
    if (derivEval === 0) return null;

    const magnitude = gfMul(errX[k], gfDiv(omegaEval, derivEval));
    corrected[errIndices[k]] ^= magnitude;
  }

  // Strict post-correction syndrome verification
  for (let i = 0; i < eccBytes; i++) {
    if (gfEvalHighestFirst(corrected, GF_EXP[i]) !== 0) {
      return null;
    }
  }

  return { data: corrected.slice(0, dataLen), correctedErrors: l };
}

export function rsDecode(codeword, eccBytes = DEFAULT_ECC_BYTES) {
  const res = rsDecodeWithStats(codeword, eccBytes);
  return res ? res.data : null;
}
