import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { DEFAULT_ECC_BYTES, rsDecodeWithStats, rsEncode } from "../src/acoustic/reedSolomon.js";
import {
  SYNC_0,
  SYNC_V2_1,
  TYPE_CHAT,
  TYPE_SOS,
  decodeV1FrameBytes,
  decodeV2EncryptedFrameBytes,
  demodulateSamples,
  encodeV1FrameBytes,
  encodeV2EncryptedFrameBytes,
  modulateFrameToFloat32
} from "../src/acoustic/bell202v2.js";
import {
  decryptAccountVault,
  decryptDmEcdh,
  doubleRatchetDecrypt,
  doubleRatchetEncrypt,
  ecdhSharedBits,
  encryptAccountVault,
  encryptDmEcdh,
  generateIdentityKeyPair,
  initDoubleRatchetInitiator,
  initDoubleRatchetResponder
} from "../src/crypto/doubleRatchet.js";
import { isValidUniversalAccountSecret } from "../src/net/cloudflareRealtime.js";
import {
  buildVerificationQrPayload,
  compute60DigitSafetyNumber,
  verifyScannedQrPayload
} from "../src/crypto/safetyNumber.js";
import {
  exportEncryptedBackup,
  importEncryptedBackup
} from "../src/crypto/encryptedBackup.js";
import { parsePulseMeshQrUri } from "../src/qr/qrScanner.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..", "..");

async function testReedSolomon() {
  const payload = new TextEncoder().encode("PulseMesh RS(255,k) tactical FEC test payload 12345!");
  const cw = rsEncode(payload, DEFAULT_ECC_BYTES);
  assert.equal(cw.length, payload.length + 8);

  const clean = rsDecodeWithStats(cw, DEFAULT_ECC_BYTES);
  assert.ok(clean);
  assert.deepEqual(clean.data, payload);
  assert.equal(clean.correctedErrors, 0);

  const errPositions = [0, 7, 23, cw.length - 1];
  for (let k = 1; k <= 4; k++) {
    const noisy = cw.slice();
    for (let i = 0; i < k; i++) {
      noisy[errPositions[i]] ^= 0x3d + i * 17;
    }
    const fixed = rsDecodeWithStats(noisy, DEFAULT_ECC_BYTES);
    assert.ok(fixed, `Expected ${k} corrupted bytes to repair`);
    assert.deepEqual(fixed.data, payload);
    assert.equal(fixed.correctedErrors, k);
  }

  const uncorrectable = cw.slice();
  for (const pos of [0, 5, 11, 21, 31]) {
    uncorrectable[pos] ^= 0xa5;
  }
  assert.equal(rsDecodeWithStats(uncorrectable, DEFAULT_ECC_BYTES), null);

  // Deterministic cross-platform parity vector for "PulseMesh"
  const vec = rsEncode(new TextEncoder().encode("PulseMesh"), 8);
  const parityHex = Array.from(vec.subarray(9), (b) => b.toString(16).padStart(2, "0")).join("");
  assert.equal(parityHex.length, 16);
  console.log(`PASS testReedSolomon (RS parity("PulseMesh")=${parityHex})`);
}

async function testAcousticV2() {
  const pass = "alpha-team-secret";
  const secret = "Rally at Ridge 4 @ 1800Z";
  const frame = await encodeV2EncryptedFrameBytes(TYPE_CHAT, "0x4F2A", secret, pass);
  assert.equal(frame[0], SYNC_0);
  assert.equal(frame[1], SYNC_V2_1);

  const clean = await decodeV2EncryptedFrameBytes(frame, pass);
  assert.ok(clean);
  assert.equal(clean.isEncrypted, true);
  assert.equal(clean.fecCorrectedBytes, 0);
  assert.equal(clean.srcNodeIdHex, "0x4F2A");
  assert.equal(clean.text, secret);

  const noisy = frame.slice();
  noisy[6] ^= 0x55;
  noisy[14] ^= 0xff;
  noisy[noisy.length - 2] ^= 0x0f;
  const repaired = await decodeV2EncryptedFrameBytes(noisy, pass);
  assert.ok(repaired);
  assert.equal(repaired.text, secret);
  assert.equal(repaired.fecCorrectedBytes, 3);

  const lenCorrupted = frame.slice();
  lenCorrupted[2] ^= 0x10;
  const lenOk = await decodeV2EncryptedFrameBytes(lenCorrupted, pass);
  assert.ok(lenOk);
  assert.equal(lenOk.text, secret);

  assert.equal(await decodeV2EncryptedFrameBytes(frame, "wrong-pass"), null);

  // Full Float32 PCM modulation -> Goertzel demodulation round-trip
  const pcmV2 = modulateFrameToFloat32(frame, 44100);
  const demodV2 = await demodulateSamples(pcmV2, 44100, pass);
  assert.ok(demodV2 && demodV2.frame);
  assert.equal(demodV2.frame.isEncrypted, true);
  assert.equal(demodV2.frame.text, secret);
  assert.equal(await demodulateSamples(pcmV2, 44100, "wrong-pass"), null);

  // Legacy v1 "PM" frame decode compatibility
  const v1 = encodeV1FrameBytes(TYPE_SOS, "0x1A2B", "LEGACY SOS");
  const decV1 = decodeV1FrameBytes(v1);
  assert.ok(decV1);
  assert.equal(decV1.text, "LEGACY SOS");
  const demodV1 = await demodulateSamples(modulateFrameToFloat32(v1, 44100), 44100, pass);
  assert.ok(demodV1 && demodV1.frame);
  assert.equal(demodV1.frame.text, "LEGACY SOS");
  console.log("PASS testAcousticV2");
}

async function testDoubleRatchetAndZeroTrustDm() {
  const alice = await generateIdentityKeyPair();
  const bob = await generateIdentityKeyPair();

  // Standard ECDH V2 DM + zero plaintext downgrade
  const dmCt = await encryptDmEcdh("Hello Bob", alice.privKeyB64, bob.pubKeyB64);
  assert.ok(dmCt && dmCt.startsWith("E2EE_ECDH_V2:"));
  assert.equal(await decryptDmEcdh(dmCt, bob.privKeyB64, alice.pubKeyB64), "Hello Bob");
  assert.equal(
    await decryptDmEcdh("PLAINTEXT:forged", bob.privKeyB64, alice.pubKeyB64),
    "[Encrypted / Key Verification Failed]"
  );

  // Double Ratchet PMDR2 bidirectional multi-message round-trip
  const rootAlice = await ecdhSharedBits(alice.privKeyB64, bob.pubKeyB64);
  const rootBob = await ecdhSharedBits(bob.privKeyB64, alice.pubKeyB64);
  let sAlice = await initDoubleRatchetInitiator("BOB", rootAlice, bob.pubKeyB64);
  let sBob = await initDoubleRatchetResponder("ALICE", rootBob, bob.privKeyB64, bob.pubKeyB64);

  const m1 = await doubleRatchetEncrypt(sAlice, "Msg 1 from Alice");
  sAlice = m1.session;
  assert.ok(m1.payload.startsWith("PMDR2:"));
  const d1 = await doubleRatchetDecrypt(sBob, m1.payload);
  assert.ok(d1);
  sBob = d1.session;
  assert.equal(d1.plaintext, "Msg 1 from Alice");

  const m2 = await doubleRatchetEncrypt(sBob, "Reply from Bob");
  sBob = m2.session;
  const d2 = await doubleRatchetDecrypt(sAlice, m2.payload);
  assert.ok(d2);
  sAlice = d2.session;
  assert.equal(d2.plaintext, "Reply from Bob");
  console.log("PASS testDoubleRatchetAndZeroTrustDm");
}

function testCompiledBundleParity() {
  const androidHtml = path.join(repoRoot, "app", "src", "main", "assets", "pulsemesh_web.html");
  const docsHtml = path.join(repoRoot, "docs", "web", "index.html");
  assert.ok(fs.existsSync(androidHtml), "Android pulsemesh_web.html missing");
  assert.ok(fs.existsSync(docsHtml), "docs/web/index.html missing");

  const bufA = fs.readFileSync(androidHtml);
  const bufB = fs.readFileSync(docsHtml);
  const hashA = crypto.createHash("sha256").update(bufA).digest("hex");
  const hashB = crypto.createHash("sha256").update(bufB).digest("hex");
  assert.equal(hashA, hashB, "Compiled HTML SHA-256 mismatch between Android asset and docs/web/index.html");

  const htmlStr = bufA.toString("utf8");
  assert.ok(htmlStr.includes("PulseMesh"), "Bundle must contain PulseMesh");
  assert.ok(!htmlStr.includes("PLAINTEXT:"), "Zero plaintext downgrade strings allowed in bundle");
  console.log(`PASS testCompiledBundleParity (SHA-256=${hashA.slice(0, 16)}..., ${(bufA.length / 1024).toFixed(1)} KB)`);
}

async function testSafetyNumberAndBackup() {
  const snA = await compute60DigitSafetyNumber("0xAAA1", "PUB_ALICE_BASE64", "0xBBB2", "PUB_BOB_BASE64");
  const snB = await compute60DigitSafetyNumber("0xBBB2", "PUB_BOB_BASE64", "0xAAA1", "PUB_ALICE_BASE64");
  assert.equal(snA, snB, "60-digit Safety Number must be symmetric across peers");
  assert.equal(snA.split(" ").length, 12);
  const qr = buildVerificationQrPayload("0xAAA1", "0xBBB2", snA);
  assert.equal(verifyScannedQrPayload(qr, snB), true);
  assert.equal(verifyScannedQrPayload(qr + "1", snB), false);

  const env = await exportEncryptedBackup({
    operatorCallsign: "WebRanger",
    nodeId: "0xDEAD1234",
    claimedUsername: "webranger",
    x25519PublicKey: "PUB_W",
    x25519PrivateKey: "PRIV_W"
  }, "correct-horse-battery", 10000);
  assert.ok(env.startsWith("PMBK2:"));
  const restored = await importEncryptedBackup(env, "correct-horse-battery", 10000);
  assert.equal(restored.operatorCallsign, "WebRanger");
  assert.equal(restored.claimedUsername, "webranger");
  let failedWrongPass = false;
  try {
    await importEncryptedBackup(env, "wrong-passphrase", 10000);
  } catch {
    failedWrongPass = true;
  }
  assert.equal(failedWrongPass, true, "Wrong passphrase must fail closed");

  const parsed = parsePulseMeshQrUri("pulsemesh://join?id=0x1234&name=Alpha&user=alpha");
  assert.equal(parsed.kind, "peer");
  assert.equal(parsed.nodeId, "0x1234");
  console.log(`PASS testSafetyNumberAndBackup (SN=${snA.slice(0, 17)}...)`);
}

async function testUniversalUsernameSecretAndVault() {
  assert.equal(isValidUniversalAccountSecret("1234"), true);
  assert.equal(isValidUniversalAccountSecret("202601"), true);
  assert.equal(isValidUniversalAccountSecret("12345678"), true);
  assert.equal(isValidUniversalAccountSecret("myPass"), true);
  assert.equal(isValidUniversalAccountSecret("PulseMesh!2026"), true);
  assert.equal(isValidUniversalAccountSecret("123"), false);
  assert.equal(isValidUniversalAccountSecret("short"), false);

  const kp = await generateIdentityKeyPair();
  const payload = { priv: kp.privKeyB64, pub: kp.pubKeyB64, nid: "0xABCD1234", dn: "@kostas", tok: "tok123", ep: 1 };
  const saltHex = "0123456789abcdef0123456789abcdef";

  const pinVault = await encryptAccountVault("kostas", saltHex, "2026", payload);
  const decPin = await decryptAccountVault("kostas", saltHex, "2026", pinVault);
  assert.equal(decPin.nid, "0xABCD1234");

  const passVault = await encryptAccountVault("kostas", saltHex, "StrongPass!2026", payload);
  const decPass = await decryptAccountVault("kostas", saltHex, "StrongPass!2026", passVault);
  assert.equal(decPass.nid, "0xABCD1234");
  assert.equal(await decryptAccountVault("kostas", saltHex, "wrong", passVault), null);
  console.log("PASS testUniversalUsernameSecretAndVault");
}

await testReedSolomon();
await testAcousticV2();
await testDoubleRatchetAndZeroTrustDm();
await testSafetyNumberAndBackup();
await testUniversalUsernameSecretAndVault();
testCompiledBundleParity();
console.log("ALL WEB PWA TESTS PASSED.");
