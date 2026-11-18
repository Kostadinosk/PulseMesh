# PulseMesh Free Encrypted Username Relay (Cloudflare Workers + D1)

This is a **100% free ($0/month, no credit card)** serverless backend for PulseMesh's `@username` registry and encrypted distant messaging inbox.

## What It Gives You
- **Permanent Atomic First-Come-First-Serve `@username` Locks** via SQLite `UNIQUE` constraint (never expires after 12 hours).
- **Zero-Knowledge Security**: Your 4–6 digit PIN, Recovery Key (`PMRK-...`), and EC P-256 private key **never leave your phone unencrypted**. Only PBKDF2-HMAC-SHA256 + AES-256-GCM encrypted account vaults and ECDSA-signed public identities are stored.
- **Encrypted Full-Device Migration**: Chats, contacts, trusted peer public keys, mesh groups, and app settings are GZIP-compressed and AES-256-GCM encrypted client-side (`PMB1:`) so logging in on a new phone with your PIN **or** Recovery Key moves all of your data seamlessly.
- **End-to-End Encrypted Async Inbox (`PMU2`)**: Distant messages use Ephemeral-Static ECDH + AES-256-GCM and stay queued for up to 30 days until your phone acknowledges delivery (`ACK`).
- **Free Tier Limits**: 100,000 requests/day & 5 GB D1 storage at `$0/month`.

## 3-Step Free Deployment
1. Install / run Wrangler and log in to a free Cloudflare account:
   ```bash
   npx wrangler login
   ```
2. Create the free D1 database and apply the schema:
   ```bash
   cd cloudflare-username-relay
   npx wrangler d1 create pulsemesh_registry
   # Copy the returned database_id into wrangler.toml
   npx wrangler d1 execute pulsemesh_registry --remote --file=./schema.sql
   ```
3. Deploy the Worker:
   ```bash
   npx wrangler deploy
   ```
4. Copy the printed `https://pulsemesh-username-relay.<your-subdomain>.workers.dev` URL, open **PulseMesh → Settings → Operator Identity & Unique @Username → Custom Free Relay URL**, paste it, and tap **Test & Save**.
