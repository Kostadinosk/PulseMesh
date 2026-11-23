/**
 * PulseMesh Zero-Trust Atomic Username Relay, Durable Object Realtime Hub & TURN Minter
 * Cloudflare Workers + D1 (SQLite) + Durable Objects WebSocket Hibernation API
 *
 * Security model:
 * - Atomic first-come-first-serve username reservation (SQLite UNIQUE constraint)
 * - Public GET /v1/users/:username returns ONLY public identity fields (zero pinVault/recoveryVault/saltHex/authVerifierHash)
 * - Vault retrieval requires POST /v1/users/:username/vault with ECDSA challenge signature OR rate-limited unlock proof (5-fail lockout)
 * - Inbox reads/deletes require authenticated X-PulseMesh-Auth / X-PM-Auth token or ECDSA challenge signature (401 otherwise)
 * - Inbox writes verify recipient existence, 64 KB max envelope, and 500-message FIFO queue quota per recipient
 * - Realtime WebRTC signaling, voice frames, typing indicators, and inbox wakeups run over authenticated Durable Object WebSockets
 */

const USERNAME_RE = /^[a-z0-9_-]{3,24}$/;
const MAX_ENVELOPE_BYTES = 65536;
const MAX_BACKUP_BYTES = 524288;
const MAX_INBOX_PER_USER = 500;
const INBOX_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const CHALLENGE_TTL_MS = 60 * 1000;
const MAX_VAULT_FAILURES = 5;
const BASE_LOCKOUT_MS = 15 * 60 * 1000;

function jsonResponse(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers":
        "Content-Type, X-PM-Auth, X-PulseMesh-Auth, X-PM-Signature, X-PM-Nonce, X-PulseMesh-Unlock-Proof",
      ...extraHeaders,
    },
  });
}

function normalizeUsername(raw) {
  return String(raw || "")
    .trim()
    .replace(/^@/, "")
    .toLowerCase()
    .replace(/[^a-z0-9_-]/g, "")
    .slice(0, 24);
}

function b64ToBytes(b64) {
  const clean = String(b64 || "").replace(/\s+/g, "");
  const bin = atob(clean);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function constantTimeEqual(a, b) {
  const strA = String(a || "");
  const strB = String(b || "");
  if (!strA || strA.length !== strB.length) return false;
  let diff = 0;
  for (let i = 0; i < strA.length; i++) {
    diff |= strA.charCodeAt(i) ^ strB.charCodeAt(i);
  }
  return diff === 0;
}

function derToP1363(der) {
  if (der.length === 64 && der[0] !== 0x30) return der;
  if (der.length < 8 || der[0] !== 0x30) return null;
  let offset = 2;
  if (der[1] & 0x80) offset += der[1] & 0x7f;
  if (der[offset] !== 0x02) return null;
  const rLen = der[offset + 1];
  const rStart = offset + 2;
  const rBytes = der.slice(rStart, rStart + rLen);
  offset = rStart + rLen;
  if (der[offset] !== 0x02) return null;
  const sLen = der[offset + 1];
  const sStart = offset + 2;
  const sBytes = der.slice(sStart, sStart + sLen);

  const out = new Uint8Array(64);
  const copyPadded = (src, destOffset) => {
    const trimmed = src.length > 32 ? src.slice(src.length - 32) : src;
    out.set(trimmed, destOffset + (32 - trimmed.length));
  };
  copyPadded(rBytes, 0);
  copyPadded(sBytes, 32);
  return out;
}

async function verifyEcP256Signature(pubSpkiB64, message, sigB64) {
  try {
    const pubBytes = b64ToBytes(pubSpkiB64);
    const sigP1363 = derToP1363(b64ToBytes(sigB64));
    if (!sigP1363) return false;
    const key = await crypto.subtle.importKey(
      "spki",
      pubBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );
    const msgBytes = new TextEncoder().encode(message);
    return await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      sigP1363,
      msgBytes
    );
  } catch {
    return false;
  }
}

async function sha256LowerHex(input) {
  const bytes = new TextEncoder().encode(input);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(byteCount = 32) {
  const arr = new Uint8Array(byteCount);
  crypto.getRandomValues(arr);
  return Array.from(arr, (b) => b.toString(16).padStart(2, "0")).join("");
}

async function checkRateLimit(db, bucket, maxHits, windowMs) {
  const now = Date.now();
  const row = await db
    .prepare("SELECT hits, window_start FROM rate_limits WHERE bucket = ?")
    .bind(bucket)
    .first();
  if (!row || now - row.window_start > windowMs) {
    await db
      .prepare(
        "INSERT INTO rate_limits (bucket, hits, window_start) VALUES (?, 1, ?) ON CONFLICT(bucket) DO UPDATE SET hits = 1, window_start = excluded.window_start"
      )
      .bind(bucket, now)
      .run();
    return true;
  }
  if (row.hits >= maxHits) return false;
  await db
    .prepare("UPDATE rate_limits SET hits = hits + 1 WHERE bucket = ?")
    .bind(bucket)
    .run();
  return true;
}

async function consumeValidChallenge(db, username, nonce) {
  if (!nonce || nonce.length < 16) return false;
  const now = Date.now();
  await db.prepare("DELETE FROM auth_challenges WHERE expires_at < ?").bind(now).run();
  const row = await db
    .prepare("SELECT nonce, username, expires_at FROM auth_challenges WHERE nonce = ? AND username = ?")
    .bind(nonce, username)
    .first();
  if (!row || row.expires_at < now) return false;
  await db.prepare("DELETE FROM auth_challenges WHERE nonce = ?").bind(nonce).run();
  return true;
}

async function verifyUserRequestAuth(request, env, username, userRow, bodyObj = null) {
  const authToken = (
    request.headers.get("X-PulseMesh-Auth") ||
    request.headers.get("X-PM-Auth") ||
    (bodyObj && bodyObj.authToken) ||
    ""
  ).trim();
  if (authToken && userRow.auth_verifier_hash) {
    const tokenHash = await sha256LowerHex(`PM_AUTH_V2:${authToken}`);
    if (constantTimeEqual(tokenHash, userRow.auth_verifier_hash)) {
      return { ok: true, mode: "token" };
    }
  }

  const nonce = (
    request.headers.get("X-PM-Nonce") ||
    (bodyObj && bodyObj.nonce) ||
    ""
  ).trim();
  const sig = (
    request.headers.get("X-PM-Signature") ||
    (bodyObj && bodyObj.sig) ||
    ""
  ).trim();
  if (nonce && sig && userRow.public_key_b64) {
    const sigOk = await verifyEcP256Signature(
      userRow.public_key_b64,
      `PM_AUTH_CHALLENGE|${username}|${nonce}`,
      sig
    );
    if (sigOk && (await consumeValidChallenge(env.DB, username, nonce))) {
      return { ok: true, mode: "ecdsa_challenge" };
    }
  }

  return { ok: false, mode: "none" };
}

export class PulseMeshRealtimeHub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/notify") {
      try {
        const payload = await request.text();
        for (const ws of this.ctx.getWebSockets()) {
          try {
            ws.send(payload);
          } catch {
            // Closed sockets are evicted automatically by runtime
          }
        }
        return jsonResponse({ ok: true, recipients: this.ctx.getWebSockets().length });
      } catch {
        return jsonResponse({ error: "Notify failed" }, 500);
      }
    }

    const upgrade = request.headers.get("Upgrade") || "";
    if (upgrade.toLowerCase() !== "websocket") {
      return jsonResponse({ error: "Expected WebSocket upgrade" }, 426);
    }

    const verifiedUser = normalizeUsername(request.headers.get("X-Verified-Username") || "");
    if (!USERNAME_RE.test(verifiedUser)) {
      return jsonResponse({ error: "Unauthorized realtime peer" }, 401);
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server, [verifiedUser]);

    try {
      server.send(
        JSON.stringify({
          type: "REALTIME_READY",
          username: verifiedUser,
          peersOnline: this.ctx.getWebSockets().length,
          timestamp: Date.now(),
        })
      );
    } catch {
      // Ignore initial welcome send errors
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    const tags = this.ctx.getTags(ws);
    const sender = tags && tags.length > 0 ? tags[0] : "";

    if (typeof message !== "string") {
      // Binary frame: relay encrypted voice/DTN binary frames directly to connected peers in the hub
      for (const peer of this.ctx.getWebSockets()) {
        if (peer !== ws) {
          try {
            peer.send(message);
          } catch {
            // Ignore dropped peer socket
          }
        }
      }
      return;
    }

    let parsed;
    try {
      parsed = JSON.parse(message);
    } catch {
      return;
    }

    if (parsed.type === "PING") {
      try {
        ws.send(JSON.stringify({ type: "PONG", timestamp: Date.now() }));
      } catch {
        // Ignore
      }
      return;
    }

    const target = parsed.dstUsername ? normalizeUsername(parsed.dstUsername) : "";
    const outPayload = JSON.stringify({
      ...parsed,
      verifiedSender: sender,
      serverTs: Date.now(),
    });

    for (const peer of this.ctx.getWebSockets()) {
      if (peer === ws) continue;
      if (target) {
        const peerTags = this.ctx.getTags(peer);
        if (!peerTags.includes(target)) continue;
      }
      try {
        peer.send(outPayload);
      } catch {
        // Ignore dropped socket
      }
    }
  }

  async webSocketClose(ws) {
    try {
      ws.close(1000, "Closed");
    } catch {
      // Ignore
    }
  }

  async webSocketError() {
    // Handled cleanly by runtime hibernation cleanup
  }
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return jsonResponse({ ok: true });

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";

    if (request.method === "GET" && (path === "/health" || path === "/v1/health")) {
      return jsonResponse({
        ok: true,
        service: "pulsemesh-e2ee-relay",
        version: 3,
        realtimeWs: Boolean(env.REALTIME_HUB),
        turnConfigured: Boolean(env.CF_CALLS_TURN_TOKEN_ID && env.CF_CALLS_TURN_API_TOKEN),
      });
    }

    // POST /v1/users/claim — Atomic first-come-first-serve claim
    if (request.method === "POST" && path === "/v1/users/claim") {
      const allowed = await checkRateLimit(env.DB, `claim:${clientIp}`, 30, 60 * 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      let body;
      try {
        body = await request.json();
      } catch {
        return jsonResponse({ error: "Invalid JSON" }, 400);
      }

      const username = normalizeUsername(body.username);
      if (!USERNAME_RE.test(username)) {
        return jsonResponse({ error: "Invalid username" }, 400);
      }

      const nodeId = String(body.nodeId || "").trim();
      const displayName = String(body.displayName || `@${username}`).trim().slice(0, 48);
      const publicKeyB64 = String(body.publicKeyBase64 || body.publicKeyB64 || "").trim();
      const saltHex = String(body.saltHex || "").trim();
      const pinVault = String(body.pinVault || "").trim();
      const recoveryVault = String(body.recoveryVault || "").trim();
      const authVerifierHash = String(body.authVerifierHash || "").trim();
      const unlockVerifierHash = String(body.unlockVerifierHash || "").trim();
      const signatureB64 = String(body.sig || body.signatureB64 || "").trim();
      const claimedAt = Number(body.claimedAt || Date.now());

      if (!nodeId || !publicKeyB64 || !saltHex || !pinVault || !recoveryVault || !authVerifierHash || !signatureB64) {
        return jsonResponse({ error: "Missing required claim fields" }, 400);
      }

      const canonical = `PM_CLAIM_V2|${username}|${nodeId}|${publicKeyB64}|${claimedAt}`;
      const sigOk = await verifyEcP256Signature(publicKeyB64, canonical, signatureB64);
      if (!sigOk) {
        return jsonResponse({ error: "Invalid ECDSA claim signature" }, 400);
      }

      const now = Date.now();
      const result = await env.DB.prepare(
        `INSERT INTO users (
           username, node_id, display_name, public_key_b64, salt_hex,
           pin_vault, recovery_vault, auth_verifier_hash, unlock_verifier_hash, signature_b64,
           claimed_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(username) DO NOTHING`
      )
        .bind(
          username,
          nodeId,
          displayName,
          publicKeyB64,
          saltHex,
          pinVault,
          recoveryVault,
          authVerifierHash,
          unlockVerifierHash,
          signatureB64,
          claimedAt,
          now
        )
        .run();

      if (!result.meta || result.meta.changes === 0) {
        return jsonResponse({ error: "Username already claimed", code: "ALREADY_CLAIMED" }, 409);
      }

      return jsonResponse({ ok: true, username, claimedAt }, 201);
    }

    // POST /v1/users/:username/challenge — Issue single-use 60s auth challenge nonce
    const challengeMatch = path.match(/^\/v1\/users\/([^/]+)\/challenge$/);
    if (request.method === "POST" && challengeMatch) {
      const allowed = await checkRateLimit(env.DB, `challenge:${clientIp}`, 60, 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const username = normalizeUsername(decodeURIComponent(challengeMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const existing = await env.DB.prepare("SELECT username FROM users WHERE username = ?")
        .bind(username)
        .first();
      if (!existing) return jsonResponse({ error: "Not found" }, 404);

      const now = Date.now();
      const expiresAt = now + CHALLENGE_TTL_MS;
      const nonce = randomHex(32);
      await env.DB.prepare("DELETE FROM auth_challenges WHERE expires_at < ?").bind(now).run();
      await env.DB.prepare(
        "INSERT INTO auth_challenges (nonce, username, created_at, expires_at) VALUES (?, ?, ?, ?)"
      )
        .bind(nonce, username, now, expiresAt)
        .run();

      return jsonResponse({ ok: true, username, nonce, expiresAt });
    }

    // POST /v1/users/:username/vault — Authenticated / rate-limited & lockout-protected vault retrieval
    const vaultMatch = path.match(/^\/v1\/users\/([^/]+)\/vault$/);
    if (request.method === "POST" && vaultMatch) {
      const ipAllowed = await checkRateLimit(env.DB, `vault:${clientIp}`, 30, 15 * 60 * 1000);
      if (!ipAllowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const username = normalizeUsername(decodeURIComponent(vaultMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const now = Date.now();
      const lockout = await env.DB.prepare(
        "SELECT failed_attempts, locked_until FROM vault_lockouts WHERE username = ?"
      )
        .bind(username)
        .first();
      if (lockout && lockout.locked_until > now) {
        return jsonResponse(
          {
            error: "Vault temporarily locked due to failed unlock attempts",
            retryAfterMs: lockout.locked_until - now,
          },
          423
        );
      }

      const row = await env.DB.prepare(
        `SELECT username, public_key_b64, salt_hex, pin_vault, recovery_vault,
                auth_verifier_hash, unlock_verifier_hash
         FROM users WHERE username = ?`
      )
        .bind(username)
        .first();
      if (!row) return jsonResponse({ error: "Not found" }, 404);

      let body = {};
      try {
        body = await request.json();
      } catch {
        body = {};
      }

      let authorized = false;

      // 1. Challenge ECDSA signature or auth token check
      const stdAuth = await verifyUserRequestAuth(request, env, username, row, body);
      if (stdAuth.ok) {
        authorized = true;
      }

      // 2. Explicit ECDSA signature over PM_VAULT_CHALLENGE|<username>|<nonce>
      if (!authorized && body && body.nonce && body.sig) {
        const sigOk = await verifyEcP256Signature(
          row.public_key_b64,
          `PM_VAULT_CHALLENGE|${username}|${String(body.nonce).trim()}`,
          String(body.sig).trim()
        );
        if (sigOk && (await consumeValidChallenge(env.DB, username, String(body.nonce).trim()))) {
          authorized = true;
        }
      }

      // 3. Server-verified unlock proof (passphrase/PIN/recovery-key derived client-side)
      const unlockProof = (
        request.headers.get("X-PulseMesh-Unlock-Proof") ||
        (body && body.unlockProof) ||
        ""
      ).trim();
      if (!authorized && unlockProof && row.unlock_verifier_hash) {
        const computedHash = await sha256LowerHex(`PM_VAULT_PROOF_V3:${unlockProof}`);
        if (constantTimeEqual(computedHash, row.unlock_verifier_hash)) {
          authorized = true;
        }
      }

      if (!authorized) {
        const prevFailures = lockout ? Number(lockout.failed_attempts || 0) : 0;
        const nextFailures = prevFailures + 1;
        const lockedUntil =
          nextFailures >= MAX_VAULT_FAILURES
            ? now + BASE_LOCKOUT_MS * Math.pow(2, Math.min(4, nextFailures - MAX_VAULT_FAILURES))
            : 0;
        await env.DB.prepare(
          `INSERT INTO vault_lockouts (username, failed_attempts, locked_until) VALUES (?, ?, ?)
           ON CONFLICT(username) DO UPDATE SET failed_attempts = excluded.failed_attempts, locked_until = excluded.locked_until`
        )
          .bind(username, nextFailures, lockedUntil)
          .run();
        return jsonResponse(
          {
            error: "Unauthorized vault unlock",
            remainingAttempts: Math.max(0, MAX_VAULT_FAILURES - nextFailures),
          },
          401
        );
      }

      if (lockout) {
        await env.DB.prepare("DELETE FROM vault_lockouts WHERE username = ?").bind(username).run();
      }

      return jsonResponse({
        ok: true,
        v: 3,
        username: row.username,
        saltHex: row.salt_hex,
        pinVault: row.pin_vault,
        recoveryVault: row.recovery_vault,
      });
    }

    // GET /v1/users/:username — Fetch STRICTLY public identity fields only (never expose vaults/salts/verifier hashes)
    const userMatch = path.match(/^\/v1\/users\/([^/]+)$/);
    if (request.method === "GET" && userMatch) {
      const allowed = await checkRateLimit(env.DB, `lookup:${clientIp}`, 180, 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const username = normalizeUsername(decodeURIComponent(userMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const row = await env.DB.prepare(
        `SELECT username, node_id, display_name, public_key_b64, signature_b64, claimed_at
         FROM users WHERE username = ?`
      )
        .bind(username)
        .first();

      if (!row) return jsonResponse({ error: "Not found" }, 404);

      return jsonResponse({
        v: 3,
        username: row.username,
        nodeId: row.node_id,
        displayName: row.display_name,
        publicKeyBase64: row.public_key_b64,
        sig: row.signature_b64,
        claimedAt: row.claimed_at,
      });
    }

    // PUT /v1/users/:username — Authenticated update (displayName / rotated EC key / vaults)
    if (request.method === "PUT" && userMatch) {
      const username = normalizeUsername(decodeURIComponent(userMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const existing = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash, claimed_at FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!existing) return jsonResponse({ error: "Not found" }, 404);

      let body;
      try {
        body = await request.json();
      } catch {
        return jsonResponse({ error: "Invalid JSON" }, 400);
      }

      const authRes = await verifyUserRequestAuth(request, env, username, existing);
      const newNodeId = String(body.nodeId || "").trim();
      const newDisplay = String(body.displayName || `@${username}`).trim().slice(0, 48);
      const newPubKey = String(body.publicKeyBase64 || body.publicKeyB64 || "").trim();
      const newSig = String(body.sig || body.signatureB64 || "").trim();
      const oldSig = request.headers.get("X-PM-Signature") || "";

      const oldKeySigned =
        oldSig &&
        (await verifyEcP256Signature(
          existing.public_key_b64,
          `PM_CLAIM_V2|${username}|${newNodeId}|${newPubKey}|${existing.claimed_at}`,
          oldSig
        ));

      if (!authRes.ok && !oldKeySigned) {
        return jsonResponse({ error: "Unauthorized update" }, 403);
      }

      const canonical = `PM_CLAIM_V2|${username}|${newNodeId}|${newPubKey}|${existing.claimed_at}`;
      if (!(await verifyEcP256Signature(newPubKey, canonical, newSig))) {
        return jsonResponse({ error: "Invalid claim signature" }, 400);
      }

      await env.DB.prepare(
        `UPDATE users SET
           display_name = ?,
           public_key_b64 = ?,
           pin_vault = COALESCE(NULLIF(?, ''), pin_vault),
           recovery_vault = COALESCE(NULLIF(?, ''), recovery_vault),
           unlock_verifier_hash = COALESCE(NULLIF(?, ''), unlock_verifier_hash),
           signature_b64 = ?,
           updated_at = ?
         WHERE username = ?`
      )
        .bind(
          newDisplay,
          newPubKey,
          String(body.pinVault || ""),
          String(body.recoveryVault || ""),
          String(body.unlockVerifierHash || ""),
          newSig,
          Date.now(),
          username
        )
        .run();

      return jsonResponse({ ok: true, username });
    }

    // DELETE /v1/users/:username — Authenticated permanent deletion
    if (request.method === "DELETE" && userMatch) {
      const username = normalizeUsername(decodeURIComponent(userMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const existing = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!existing) return jsonResponse({ ok: true, username, alreadyDeleted: true });

      const authRes = await verifyUserRequestAuth(request, env, username, existing);
      const sig = request.headers.get("X-PM-Signature") || "";
      const sigValid =
        sig &&
        (await verifyEcP256Signature(existing.public_key_b64, `DELETE|${username}`, sig));

      if (!authRes.ok && !sigValid) {
        return jsonResponse({ error: "Unauthorized deletion" }, 403);
      }

      await env.DB.prepare("DELETE FROM inbox WHERE recipient = ?").bind(username).run();
      await env.DB.prepare("DELETE FROM vault_lockouts WHERE username = ?").bind(username).run();
      await env.DB.prepare("DELETE FROM auth_challenges WHERE username = ?").bind(username).run();
      await env.DB.prepare("DELETE FROM users WHERE username = ?").bind(username).run();
      return jsonResponse({ ok: true, username, deleted: true });
    }

    // Inbox endpoints: /v1/inbox/:username
    const inboxMatch = path.match(/^\/v1\/inbox\/([^/]+)$/);
    if (request.method === "POST" && inboxMatch) {
      const ipAllowed = await checkRateLimit(env.DB, `send:${clientIp}`, 120, 60 * 1000);
      if (!ipAllowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const recipient = normalizeUsername(decodeURIComponent(inboxMatch[1]));
      if (!USERNAME_RE.test(recipient)) return jsonResponse({ error: "Invalid recipient" }, 400);

      const recRateAllowed = await checkRateLimit(env.DB, `recv_in:${recipient}`, 300, 60 * 1000);
      if (!recRateAllowed) return jsonResponse({ error: "Recipient rate limit exceeded" }, 429);

      const userExists = await env.DB.prepare("SELECT username FROM users WHERE username = ?")
        .bind(recipient)
        .first();
      if (!userExists) return jsonResponse({ error: "Recipient not found" }, 404);

      const contentLenHeader = Number(request.headers.get("Content-Length") || 0);
      if (contentLenHeader > MAX_ENVELOPE_BYTES) {
        return jsonResponse({ error: "Envelope exceeds 64 KB limit" }, 413);
      }

      const envelope = await request.text();
      if (!envelope) return jsonResponse({ error: "Empty envelope" }, 400);
      const byteLength = new TextEncoder().encode(envelope).length;
      if (byteLength > MAX_ENVELOPE_BYTES) {
        return jsonResponse({ error: "Envelope exceeds 64 KB limit" }, 413);
      }

      const now = Date.now();
      await env.DB.prepare("DELETE FROM inbox WHERE created_at < ?")
        .bind(now - INBOX_TTL_MS)
        .run();

      const countRow = await env.DB.prepare("SELECT COUNT(*) AS cnt FROM inbox WHERE recipient = ?")
        .bind(recipient)
        .first();
      const cnt = Number(countRow?.cnt || 0);
      if (cnt >= MAX_INBOX_PER_USER) {
        const overflow = cnt - MAX_INBOX_PER_USER + 1;
        await env.DB.prepare(
          `DELETE FROM inbox WHERE id IN (
             SELECT id FROM inbox WHERE recipient = ? ORDER BY id ASC LIMIT ?
           )`
        )
          .bind(recipient, overflow)
          .run();
      }

      const res = await env.DB.prepare(
        "INSERT INTO inbox (recipient, envelope, created_at) VALUES (?, ?, ?)"
      )
        .bind(recipient, envelope, now)
        .run();
      const insertedId = res.meta?.last_row_id || 0;

      // Wake any connected Durable Object realtime WebSocket listeners for this user's inbox
      if (env.REALTIME_HUB) {
        try {
          const stub = env.REALTIME_HUB.get(env.REALTIME_HUB.idFromName(`inbox:${recipient}`));
          await stub.fetch(
            new Request("https://realtime.internal/notify", {
              method: "POST",
              body: JSON.stringify({
                type: "INBOX_WAKEUP",
                id: insertedId,
                recipient,
                createdAt: now,
              }),
            })
          );
        } catch {
          // Non-fatal wakeup failure
        }
      }

      return jsonResponse({ ok: true, id: insertedId }, 201);
    }

    // GET /v1/inbox/:username — Authenticated fetch only (401 without valid auth)
    if (request.method === "GET" && inboxMatch) {
      const allowed = await checkRateLimit(env.DB, `inbox_get:${clientIp}`, 180, 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const recipient = normalizeUsername(decodeURIComponent(inboxMatch[1]));
      if (!USERNAME_RE.test(recipient)) return jsonResponse({ error: "Invalid recipient" }, 400);

      const userRow = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(recipient)
        .first();
      if (!userRow) return jsonResponse({ error: "Unauthorized" }, 401);

      const authRes = await verifyUserRequestAuth(request, env, recipient, userRow);
      if (!authRes.ok) return jsonResponse({ error: "Unauthorized inbox access" }, 401);

      const afterId = Math.max(0, Number(url.searchParams.get("after") || 0));
      const { results } = await env.DB.prepare(
        "SELECT id, envelope, created_at FROM inbox WHERE recipient = ? AND id > ? ORDER BY id ASC LIMIT 100"
      )
        .bind(recipient, afterId)
        .all();

      return jsonResponse({
        messages: results || [],
        nextCursor: results && results.length ? results[results.length - 1].id : afterId,
      });
    }

    // DELETE /v1/inbox/:username — Authenticated cleanup
    if (request.method === "DELETE" && inboxMatch) {
      const recipient = normalizeUsername(decodeURIComponent(inboxMatch[1]));
      if (!USERNAME_RE.test(recipient)) return jsonResponse({ error: "Invalid recipient" }, 400);

      const userRow = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(recipient)
        .first();
      if (!userRow) return jsonResponse({ error: "Unauthorized" }, 401);

      const authRes = await verifyUserRequestAuth(request, env, recipient, userRow);
      if (!authRes.ok) return jsonResponse({ error: "Unauthorized inbox delete" }, 401);

      const upToIdParam = url.searchParams.get("upToId");
      if (upToIdParam !== null) {
        const upToId = Number(upToIdParam || 0);
        await env.DB.prepare("DELETE FROM inbox WHERE recipient = ? AND id <= ?")
          .bind(recipient, upToId)
          .run();
      } else {
        await env.DB.prepare("DELETE FROM inbox WHERE recipient = ?").bind(recipient).run();
      }
      return jsonResponse({ ok: true, deleted: true });
    }

    // POST /v1/inbox/:username/ack — Authenticated delete up to cursor
    const ackMatch = path.match(/^\/v1\/inbox\/([^/]+)\/ack$/);
    if (request.method === "POST" && ackMatch) {
      const recipient = normalizeUsername(decodeURIComponent(ackMatch[1]));
      if (!USERNAME_RE.test(recipient)) return jsonResponse({ error: "Invalid recipient" }, 400);

      let body;
      try {
        body = await request.json();
      } catch {
        return jsonResponse({ error: "Invalid JSON" }, 400);
      }
      const upToId = Number(body.upToId || 0);
      const sig = String(body.sig || "");
      const user = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(recipient)
        .first();
      if (!user) return jsonResponse({ error: "Unauthorized ACK" }, 401);

      const authRes = await verifyUserRequestAuth(request, env, recipient, user, body);
      const legacyAckSigOk =
        sig && (await verifyEcP256Signature(user.public_key_b64, `ACK|${recipient}|${upToId}`, sig));

      if (!authRes.ok && !legacyAckSigOk) {
        return jsonResponse({ error: "Unauthorized ACK" }, 401);
      }

      await env.DB.prepare("DELETE FROM inbox WHERE recipient = ? AND id <= ?")
        .bind(recipient, upToId)
        .run();

      return jsonResponse({ ok: true, ackedUpTo: upToId });
    }

    // PUT & GET /v1/users/:username/backup — Authenticated E2EE backup
    const backupMatch = path.match(/^\/v1\/users\/([^/]+)\/backup$/);
    if (backupMatch) {
      const username = normalizeUsername(decodeURIComponent(backupMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const existing = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash, backup_blob, backup_updated_at FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!existing) return jsonResponse({ error: "Not found" }, 404);

      const authRes = await verifyUserRequestAuth(request, env, username, existing);
      if (!authRes.ok) {
        return jsonResponse({ error: "Unauthorized backup access" }, 401);
      }

      if (request.method === "GET") {
        return jsonResponse({
          ok: true,
          username,
          backupBlob: existing.backup_blob || "",
          updatedAt: existing.backup_updated_at || 0,
        });
      }

      if (request.method === "PUT") {
        let body;
        try {
          body = await request.json();
        } catch {
          return jsonResponse({ error: "Invalid JSON" }, 400);
        }
        const blob = String(body.backupBlob || "").trim();
        if ((!blob.startsWith("PMB1:") && !blob.startsWith("PMB2:")) || blob.length > MAX_BACKUP_BYTES) {
          return jsonResponse({ error: "Invalid or oversized encrypted backup" }, 400);
        }
        const now = Date.now();
        await env.DB.prepare(
          "UPDATE users SET backup_blob = ?, backup_updated_at = ?, updated_at = ? WHERE username = ?"
        )
          .bind(blob, now, now, username)
          .run();
        return jsonResponse({ ok: true, username, updatedAt: now });
      }
    }

    // GET /v1/realtime/ws — Authenticated Durable Object WebSocket Hub
    if (request.method === "GET" && path === "/v1/realtime/ws") {
      if (!env.REALTIME_HUB) {
        return jsonResponse({ error: "Realtime hub not bound" }, 503);
      }

      const username = normalizeUsername(
        url.searchParams.get("username") || request.headers.get("X-PM-Username") || ""
      );
      if (!USERNAME_RE.test(username)) {
        return jsonResponse({ error: "Missing or invalid username" }, 400);
      }

      const userRow = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!userRow) return jsonResponse({ error: "Unauthorized" }, 401);

      const qsToken = url.searchParams.get("token") || "";
      const qsNonce = url.searchParams.get("nonce") || "";
      const qsSig = url.searchParams.get("sig") || "";
      const authRes = await verifyUserRequestAuth(request, env, username, userRow, {
        authToken: qsToken,
        nonce: qsNonce,
        sig: qsSig,
      });
      if (!authRes.ok) return jsonResponse({ error: "Unauthorized WebSocket connection" }, 401);

      const roomRaw = String(url.searchParams.get("room") || `inbox:${username}`).trim();
      const safeRoom = roomRaw.replace(/[^a-zA-Z0-9_:-]/g, "").slice(0, 64) || `inbox:${username}`;
      const stub = env.REALTIME_HUB.get(env.REALTIME_HUB.idFromName(safeRoom));

      const forwardHeaders = new Headers(request.headers);
      forwardHeaders.set("X-Verified-Username", username);
      return stub.fetch(new Request(request.url, { method: "GET", headers: forwardHeaders }));
    }

    // POST /v1/turn/credentials — Authenticated Cloudflare Calls TURN ICE server minting (with STUN fallback)
    if (request.method === "POST" && path === "/v1/turn/credentials") {
      const username = normalizeUsername(
        url.searchParams.get("username") || request.headers.get("X-PM-Username") || ""
      );
      let body = {};
      try {
        body = await request.json();
      } catch {
        body = {};
      }
      const targetUser = username || normalizeUsername(body.username || "");
      if (!USERNAME_RE.test(targetUser)) {
        return jsonResponse({ error: "Missing username" }, 400);
      }

      const allowed = await checkRateLimit(env.DB, `turn:${targetUser}:${clientIp}`, 30, 60 * 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const userRow = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(targetUser)
        .first();
      if (!userRow) return jsonResponse({ error: "Unauthorized" }, 401);

      const authRes = await verifyUserRequestAuth(request, env, targetUser, userRow, body);
      if (!authRes.ok) return jsonResponse({ error: "Unauthorized TURN request" }, 401);

      if (env.CF_CALLS_TURN_TOKEN_ID && env.CF_CALLS_TURN_API_TOKEN) {
        try {
          const cfResp = await fetch(
            `https://rtc.live.cloudflare.com/v1/turn/keys/${env.CF_CALLS_TURN_TOKEN_ID}/credentials/generate-ice-servers`,
            {
              method: "POST",
              headers: {
                Authorization: `Bearer ${env.CF_CALLS_TURN_API_TOKEN}`,
                "Content-Type": "application/json",
              },
              body: JSON.stringify({ ttl: 14400 }),
            }
          );
          if (cfResp.ok) {
            const turnData = await cfResp.json();
            return jsonResponse({
              ok: true,
              provider: "cloudflare-turn",
              iceServers: Array.isArray(turnData.iceServers) ? turnData.iceServers : [turnData.iceServers],
            });
          }
        } catch {
          // Fall back gracefully to public STUN if TURN API is temporarily unreachable
        }
      }

      return jsonResponse({
        ok: true,
        provider: "stun-fallback",
        iceServers: [
          {
            urls: [
              "stun:stun.cloudflare.com:3478",
              "stun:stun.l.google.com:19302",
            ],
          },
        ],
      });
    }

    // GET /v1/push/vapid-public-key — Public Web Push VAPID key discovery for iOS 16.4+ / desktop PWAs
    if (request.method === "GET" && path === "/v1/push/vapid-public-key") {
      const vapidPublicKey = String(env.VAPID_PUBLIC_KEY || "").trim();
      return jsonResponse({
        ok: true,
        enabled: Boolean(vapidPublicKey),
        vapidPublicKey,
      });
    }

    // POST & DELETE /v1/push/subscribe — Authenticated Web Push subscription management
    if (path === "/v1/push/subscribe" && (request.method === "POST" || request.method === "DELETE")) {
      let body = {};
      try {
        body = await request.json();
      } catch {
        return jsonResponse({ error: "Invalid JSON" }, 400);
      }

      const username = normalizeUsername(
        url.searchParams.get("username") || request.headers.get("X-PM-Username") || body.username || ""
      );
      if (!USERNAME_RE.test(username)) {
        return jsonResponse({ error: "Invalid username" }, 400);
      }

      const userRow = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!userRow) return jsonResponse({ error: "Unauthorized" }, 401);

      const authRes = await verifyUserRequestAuth(request, env, username, userRow, body);
      if (!authRes.ok) {
        return jsonResponse({ error: "Unauthorized push subscription access" }, 401);
      }

      const sub = body.subscription && typeof body.subscription === "object" ? body.subscription : body;
      const endpoint = String(sub.endpoint || "").trim();

      if (request.method === "DELETE") {
        if (endpoint) {
          await env.DB.prepare("DELETE FROM push_subscriptions WHERE username = ? AND endpoint = ?")
            .bind(username, endpoint)
            .run();
        } else {
          await env.DB.prepare("DELETE FROM push_subscriptions WHERE username = ?")
            .bind(username)
            .run();
        }
        return jsonResponse({ ok: true, unsubscribed: true });
      }

      const keys = sub.keys && typeof sub.keys === "object" ? sub.keys : {};
      const p256dh = String(keys.p256dh || "").trim();
      const auth = String(keys.auth || "").trim();
      if (!endpoint.startsWith("https://") || endpoint.length > 2048 || !p256dh || !auth) {
        return jsonResponse({ error: "Invalid Web Push subscription payload" }, 400);
      }

      const allowed = await checkRateLimit(env.DB, `push_sub:${username}:${clientIp}`, 30, 60 * 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const now = Date.now();
      await env.DB.prepare(
        `INSERT INTO push_subscriptions (username, endpoint, p256dh, auth, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(endpoint) DO UPDATE SET
           username = excluded.username,
           p256dh = excluded.p256dh,
           auth = excluded.auth,
           updated_at = excluded.updated_at`
      )
        .bind(username, endpoint, p256dh, auth, now, now)
        .run();

      // FIFO cap at 5 push subscriptions per username
      const cntRow = await env.DB.prepare(
        "SELECT COUNT(*) AS cnt FROM push_subscriptions WHERE username = ?"
      )
        .bind(username)
        .first();
      const cnt = Number(cntRow?.cnt || 0);
      if (cnt > 5) {
        await env.DB.prepare(
          `DELETE FROM push_subscriptions WHERE id IN (
             SELECT id FROM push_subscriptions WHERE username = ? ORDER BY updated_at ASC LIMIT ?
           )`
        )
          .bind(username, cnt - 5)
          .run();
      }

      return jsonResponse({ ok: true, subscribed: true });
    }

    return jsonResponse({ error: "Not found" }, 404);
  },
};
