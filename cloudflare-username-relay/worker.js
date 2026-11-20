/**
 * PulseMesh Free Atomic & Zero-Knowledge Encrypted Username Relay
 * Cloudflare Workers + D1 (SQLite) — $0/month Free Tier
 *
 * Security model:
 * - Atomic first-come-first-serve username reservation (SQLite UNIQUE constraint)
 * - Server NEVER sees plaintext PINs, Recovery Keys, or private keys
 * - PIN & Recovery Key protect PBKDF2 + AES-256-GCM encrypted account vaults client-side
 * - Claims & inbox ACKs are verified via ECDSA P-256 (SHA-256) at the edge
 * - Distant DMs are opaque PMU2 Ephemeral-Static ECDH + AES-256-GCM envelopes
 */

const USERNAME_RE = /^[a-z0-9_-]{3,24}$/;
const MAX_ENVELOPE_BYTES = 65536;
const MAX_BACKUP_BYTES = 524288; // 512 KB GZIP + AES-256-GCM encrypted account snapshot
const INBOX_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, X-PM-Auth, X-PM-Signature",
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

/**
 * Converts Java/Android ASN.1 DER ECDSA (r, s) signature into Web Crypto IEEE P1363 64-byte raw (r || s).
 */
function derToP1363(der) {
  if (der.length === 64 && der[0] !== 0x30) return der;
  if (der.length < 8 || der[0] !== 0x30) return null;
  let offset = 2;
  if (der[1] & 0x80) offset += (der[1] & 0x7f);
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

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return jsonResponse({ ok: true });

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";

    if (request.method === "GET" && (path === "/health" || path === "/v1/health")) {
      return jsonResponse({ ok: true, service: "pulsemesh-e2ee-relay", version: 2 });
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
      const signatureB64 = String(body.sig || body.signatureB64 || "").trim();
      const claimedAt = Number(body.claimedAt || Date.now());

      if (!nodeId || !publicKeyB64 || !saltHex || !pinVault || !recoveryVault || !authVerifierHash || !signatureB64) {
        return jsonResponse({ error: "Missing required v2 claim fields" }, 400);
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
           pin_vault, recovery_vault, auth_verifier_hash, signature_b64,
           claimed_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    // GET /v1/users/:username — Fetch public identity & encrypted vaults
    const userMatch = path.match(/^\/v1\/users\/([^/]+)$/);
    if (request.method === "GET" && userMatch) {
      const allowed = await checkRateLimit(env.DB, `lookup:${clientIp}`, 180, 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const username = normalizeUsername(decodeURIComponent(userMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const row = await env.DB.prepare(
        `SELECT username, node_id, display_name, public_key_b64, salt_hex,
                pin_vault, recovery_vault, auth_verifier_hash, signature_b64, claimed_at
         FROM users WHERE username = ?`
      )
        .bind(username)
        .first();

      if (!row) return jsonResponse({ error: "Not found" }, 404);

      return jsonResponse({
        v: 2,
        username: row.username,
        nodeId: row.node_id,
        displayName: row.display_name,
        publicKeyBase64: row.public_key_b64,
        saltHex: row.salt_hex,
        pinVault: row.pin_vault,
        recoveryVault: row.recovery_vault,
        authVerifierHash: row.auth_verifier_hash,
        sig: row.signature_b64,
        claimedAt: row.claimed_at,
      });
    }

    // PUT /v1/users/:username — Authenticated update (displayName / rotated EC key)
    if (request.method === "PUT" && userMatch) {
      const username = normalizeUsername(decodeURIComponent(userMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const existing = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash, claimed_at FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!existing) return jsonResponse({ error: "Not found" }, 404);

      const authToken = request.headers.get("X-PM-Auth") || "";
      const tokenHash = authToken ? await sha256LowerHex(`PM_AUTH_V2:${authToken.trim()}`) : "";
      const tokenValid = tokenHash && tokenHash === existing.auth_verifier_hash;

      let body;
      try {
        body = await request.json();
      } catch {
        return jsonResponse({ error: "Invalid JSON" }, 400);
      }

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

      if (!tokenValid && !oldKeySigned) {
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
           signature_b64 = ?,
           updated_at = ?
         WHERE username = ?`
      )
        .bind(
          newDisplay,
          newPubKey,
          String(body.pinVault || ""),
          String(body.recoveryVault || ""),
          newSig,
          Date.now(),
          username
        )
        .run();

      return jsonResponse({ ok: true, username });
    }

    // DELETE /v1/users/:username — Authenticated permanent deletion (requires X-PM-Auth or ECDSA signature)
    if (request.method === "DELETE" && userMatch) {
      const username = normalizeUsername(decodeURIComponent(userMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const existing = await env.DB.prepare(
        "SELECT public_key_b64, auth_verifier_hash FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!existing) return jsonResponse({ ok: true, username, alreadyDeleted: true });

      const authToken = request.headers.get("X-PM-Auth") || "";
      const tokenHash = authToken ? await sha256LowerHex(`PM_AUTH_V2:${authToken.trim()}`) : "";
      const tokenValid = tokenHash && tokenHash === existing.auth_verifier_hash;
      const sig = request.headers.get("X-PM-Signature") || "";
      const sigValid =
        sig &&
        (await verifyEcP256Signature(existing.public_key_b64, `DELETE|${username}`, sig));

      if (!tokenValid && !sigValid) {
        return jsonResponse({ error: "Unauthorized deletion" }, 403);
      }

      await env.DB.prepare("DELETE FROM inbox WHERE recipient = ?").bind(username).run();
      await env.DB.prepare("DELETE FROM users WHERE username = ?").bind(username).run();
      return jsonResponse({ ok: true, username, deleted: true });
    }

    // POST /v1/inbox/:username — Queue opaque PMU2 E2EE envelope
    const inboxMatch = path.match(/^\/v1\/inbox\/([^/]+)$/);
    if (request.method === "POST" && inboxMatch) {
      const allowed = await checkRateLimit(env.DB, `send:${clientIp}`, 120, 60 * 1000);
      if (!allowed) return jsonResponse({ error: "Rate limit exceeded" }, 429);

      const recipient = normalizeUsername(decodeURIComponent(inboxMatch[1]));
      if (!USERNAME_RE.test(recipient)) return jsonResponse({ error: "Invalid recipient" }, 400);

      const envelope = await request.text();
      if (!envelope || envelope.length > MAX_ENVELOPE_BYTES) {
        return jsonResponse({ error: "Invalid or oversized envelope" }, 400);
      }

      const now = Date.now();
      await env.DB.prepare("DELETE FROM inbox WHERE created_at < ?")
        .bind(now - INBOX_TTL_MS)
        .run();

      const res = await env.DB.prepare(
        "INSERT INTO inbox (recipient, envelope, created_at) VALUES (?, ?, ?)"
      )
        .bind(recipient, envelope, now)
        .run();

      return jsonResponse({ ok: true, id: res.meta?.last_row_id || 0 }, 201);
    }

    // GET /v1/inbox/:username?after=<cursor> — Fetch queued encrypted messages
    if (request.method === "GET" && inboxMatch) {
      const recipient = normalizeUsername(decodeURIComponent(inboxMatch[1]));
      if (!USERNAME_RE.test(recipient)) return jsonResponse({ error: "Invalid recipient" }, 400);

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

    // POST /v1/inbox/:username/ack — ECDSA-authenticated delete up to cursor
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
      const user = await env.DB.prepare("SELECT public_key_b64 FROM users WHERE username = ?")
        .bind(recipient)
        .first();

      if (!user || !(await verifyEcP256Signature(user.public_key_b64, `ACK|${recipient}|${upToId}`, sig))) {
        return jsonResponse({ error: "Unauthorized ACK" }, 403);
      }

      await env.DB.prepare("DELETE FROM inbox WHERE recipient = ? AND id <= ?")
        .bind(recipient, upToId)
        .run();

      return jsonResponse({ ok: true, ackedUpTo: upToId });
    }

    // PUT & GET /v1/users/:username/backup — Authenticated E2EE full account data backup (chats, contacts, groups, settings)
    const backupMatch = path.match(/^\/v1\/users\/([^/]+)\/backup$/);
    if (backupMatch) {
      const username = normalizeUsername(decodeURIComponent(backupMatch[1]));
      if (!USERNAME_RE.test(username)) return jsonResponse({ error: "Invalid username" }, 400);

      const existing = await env.DB.prepare(
        "SELECT auth_verifier_hash, backup_blob, backup_updated_at FROM users WHERE username = ?"
      )
        .bind(username)
        .first();
      if (!existing) return jsonResponse({ error: "Not found" }, 404);

      const authToken = request.headers.get("X-PM-Auth") || "";
      const tokenHash = authToken ? await sha256LowerHex(`PM_AUTH_V2:${authToken.trim()}`) : "";
      if (!tokenHash || tokenHash !== existing.auth_verifier_hash) {
        return jsonResponse({ error: "Unauthorized backup access" }, 403);
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
        if (!blob.startsWith("PMB1:") || blob.length > MAX_BACKUP_BYTES) {
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

    return jsonResponse({ error: "Not found" }, 404);
  },
};
