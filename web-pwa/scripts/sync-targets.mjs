import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const webPwaDir = path.resolve(__dirname, "..");
const repoRoot = path.resolve(webPwaDir, "..");

const distHtml = path.join(webPwaDir, "dist", "index.html");
const androidAssetHtml = path.join(repoRoot, "app", "src", "main", "assets", "pulsemesh_web.html");
const docsWebDir = path.join(repoRoot, "docs", "web");
const docsHtml = path.join(docsWebDir, "index.html");
const srcSw = path.join(webPwaDir, "public", "sw.js");
const dstSw = path.join(docsWebDir, "sw.js");
const srcManifest = path.join(webPwaDir, "public", "manifest.json");
const dstManifest = path.join(docsWebDir, "manifest.json");

if (!fs.existsSync(distHtml)) {
  console.error(`ERROR: Missing Vite build output at ${distHtml}. Run 'vite build' first.`);
  process.exit(1);
}

fs.mkdirSync(path.dirname(androidAssetHtml), { recursive: true });
fs.mkdirSync(docsWebDir, { recursive: true });

fs.copyFileSync(distHtml, androidAssetHtml);
fs.copyFileSync(distHtml, docsHtml);
fs.copyFileSync(srcSw, dstSw);
fs.copyFileSync(srcManifest, dstManifest);

function sha256File(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

const hashAndroid = sha256File(androidAssetHtml);
const hashDocs = sha256File(docsHtml);
if (hashAndroid !== hashDocs) {
  console.error("ERROR: SHA-256 mismatch between Android asset and docs/web/index.html!");
  process.exit(1);
}

const sizeKb = (fs.statSync(androidAssetHtml).size / 1024).toFixed(1);
console.log(`Synced Vite single-file PWA (${sizeKb} KB) to Android asset & docs/web/index.html`);
console.log(`SHA-256: ${hashAndroid}`);
