---
name: pulsemesh-build-release
description: >-
  Run PulseMesh Android unit tests, Vite Web PWA dual-target builds, signed
  16KB-aligned APK audits, and GitHub draft or public releases reliably. Use
  this skill whenever building, testing, syncing web-pwa, or packaging a
  PulseMesh release.
---

# PulseMesh Build, Test & Release Runbook

## 1. Modular Vite Web / iPhone PWA Sync & Test
1. Build and sync single-file HTML + service worker + manifest to both Android assets and GitHub Pages:
   `npm --prefix web-pwa run build:sync`
2. Run cross-platform Web PWA tests (Double Ratchet, Acoustic 2.0, Kotlin/JS Reed-Solomon parity `556d4e7478d817d9`, bundle SHA-256 parity):
   `node web-pwa/scripts/test-web-pwa.mjs`
3. Verify syntax on Worker and Service Worker:
   `node --check cloudflare-username-relay/worker.js && node --check docs/web/sw.js`
4. Confirm byte-for-byte parity:
   `diff -q app/src/main/assets/pulsemesh_web.html docs/web/index.html`

## 2. Android Unit Tests (MUST Run Sequentially)
Both flavors bind `PulseMeshWebServer` on port `8080`, so never run Lite and Full unit tests in parallel. Always use Android Studio JBR and `--no-daemon`:
1. `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testLiteDebugUnitTest --no-daemon`
2. `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testFullDebugUnitTest --no-daemon`
3. Parse `app/build/test-results/{testLiteDebugUnitTest,testFullDebugUnitTest}/TEST-*.xml` and verify `failures=0`, `errors=0`, `skipped=0`.

## 3. Signed Release APKs & Audits
1. Build with transient signing environment variables (`KEYSTORE_PATH`, `KEY_ALIAS`, `STORE_PASSWORD`, `KEY_PASSWORD`) and `--no-daemon`:
   `./gradlew :app:assembleLiteRelease :app:assembleFullRelease --no-daemon`
2. Verify signatures on both APKs with SDK `apksigner verify --print-certs -v`.
3. Verify 16KB ZIP alignment on both APKs:
   `zipalign -c -P 16 4 app/build/outputs/apk/lite/release/app-lite-release.apk`
   `zipalign -c -P 16 4 app/build/outputs/apk/full/release/app-full-release.apk`
4. Audit every 64-bit `.so` inside both APKs for `PT_LOAD p_align = 0x4000` and uncompressed storage (`compress_type == 0`).
5. Audit `app-full-release.apk` DEX files to confirm R8 kept critical JNI/SDK classes (`Lorg/jni_zero/JniInit;`, `Lnet/zetetic/database/sqlcipher/SupportOpenHelperFactory;`, `Lorg/maplibre/android/MapLibre;`, `Landroidx/camera/core/CameraX;`, `Lcom/google/zxing/MultiFormatReader;`).
6. Generate `app-lite-release.apk.sha256`, `app-full-release.apk.sha256`, and `SHA256SUMS.txt` in scratch storage (never commit APKs or secrets).

## 4. Safe Staging & Publishing
- Confirm staged diff contains zero `.github/workflows/`, `local.properties`, `.jks`, `.keystore`, `node_modules/`, `.apk`, or password strings.
- For private pre-release testing, stage signed APKs via `gh release create <tag> --draft ...` and wait for explicit user approval before publishing.
