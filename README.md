<div align="center">

<img src="docs/assets/pulsemesh-logo.png" alt="PulseMesh Icon" width="118" height="118" />

# PulseMesh

**Off-Grid Peer-to-Peer Android Mesh Messenger, Full-Duplex Voice Communicator & Tactical Offline Map**

[![Release](https://img.shields.io/github/v/release/Kostadinosk/PulseMesh?style=flat-square&logo=github&label=Release&color=19E68C)](https://github.com/Kostadinosk/PulseMesh/releases/tag/v1.5.5)
[![Platform](https://img.shields.io/badge/Android%20%2B%20iPhone%20PWA-API%2024--36%20%7C%20iOS%20Safari-38CFFF?style=flat-square&logo=android&logoColor=white)](https://github.com/Kostadinosk/PulseMesh/releases/tag/v1.5.5)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://github.com/Kostadinosk/PulseMesh)
[![Encryption](https://img.shields.io/badge/Crypto-Double%20Ratchet%20%2B%20AES--256--GCM-FFB02E?style=flat-square)](https://github.com/Kostadinosk/PulseMesh#cryptography--username-vaults)
[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-0088E6?style=flat-square)](LICENSE)

[**Live Website**](https://kostadinosk.github.io/PulseMesh/) &bull;
[**iPhone PWA & Web Node**](https://kostadinosk.github.io/PulseMesh/web/) &bull;
[**Download Full APK (v1.5.5)**](https://github.com/Kostadinosk/PulseMesh/releases/download/v1.5.5/app-full-release.apk) &bull;
[**Download Lite APK (v1.5.5)**](https://github.com/Kostadinosk/PulseMesh/releases/download/v1.5.5/app-lite-release.apk) &bull;
[**Contact & Field Reports**](https://kostadinosk.github.io/PulseMesh/contact.html) &bull;
[**Report an Issue**](https://github.com/Kostadinosk/PulseMesh/issues)

</div>

---

## Overview

**PulseMesh** (`com.KostadinosK.pulsemesh`) is a 100% FOSS, zero-infrastructure communication suite for Android and iOS/Web devices operating where cellular and internet networks are unavailable, degraded, or untrusted. By combining smartphone radios (**Bluetooth Low Energy `0xCAFE`/`0xCAFF` & L2CAP**, **Wi-Fi Direct & Auto-Hotspot subnets**, **USB/Ethernet tethering**, **LoRa / Meshtastic / MeshCore USB-OTG & BLE companions**, and an **Acoustic Modem 2.0**), PulseMesh discovers nearby nodes rapidly, routes multi-hop encrypted text and live voice streams, and synchronizes GPS waypoints on an offline tactical map.

## Two 100% FOSS Android Flavors

| Edition | APK Asset | Included FOSS Stack | Best For |
| :--- | :--- | :--- | :--- |
| **Full** | `app-full-release.apk` | Native FOSS WebRTC (Opus + AEC3 + NetEQ), SQLCipher encrypted Room DB, MapLibre Native offline vector maps, CameraX + ZXing live QR scanner | Daily drivers & flagship tactical deployments |
| **Lite** | `app-lite-release.apk` | Pure-AOSP lightweight build (Android Keystore field encryption, MediaCodec Opus / ADPCM voice, Canvas raster + radar map, ZXing gallery/URI pairing) | Low-end devices, minimal storage, fast offline APK beaming |

---

## Key Features (v1.5.0 & v1.5.5)

| Module | Highlights |
| :--- | :--- |
| **Offline Tactical Map 2.0** | MapLibre Native vector maps (`full`) + fixed Canvas raster/radar map (`lite`), `.mbtiles`/`.pmtiles` offline pack import, custom region downloader, Dark/Topo/Satellite/Radar layers, compass rotation, bearing/distance ruler, live breadcrumbs, GPX 1.1 & KML 2.2 import/export, and ATAK Cursor-on-Target (CoT) UDP multicast bridge (`239.2.3.1:6969`). |
| **Camera QR, NFC & Offline APK Beaming** | CameraX + ZXing live QR scanner (`full`) and gallery/URI decoder (`lite`) for 1-scan contact add, safety-number verification, group invites, and Wi-Fi Hotspot join; NFC Tap-to-Pair HCE (`AID F050554C53454D455348`) & Reader Mode; Offline P2P APK Beaming via built-in Hotspot Portal (`/download/pulsemesh.apk` + `.sha256`). |
| **External LoRa & Acoustic Modem 2.0** | Pure-AOSP USB-OTG serial & BLE GATT companion bridge for **Meshtastic** and **MeshCore** radios; **Bell 202 Acoustic Modem 2.0** (`"P2"` frames) with systematic **Reed-Solomon GF(2^8) FEC** (repairs up to 4 corrupted bytes per frame) and **AES-256-GCM** encryption, byte-compatible between Android and Web/iPhone PWA. |
| **Next-Gen Voice Hub & Calling** | Native WebRTC Opus + AEC3 (`full`) and MediaCodec Opus (`lite`), Android Telecom `ConnectionService`, lock-screen call cover, Bluetooth/headset/speaker route picker, Zello-style Tactical PTT channels with floor control, small-group conference mixing, and hardware volume-button PTT. |
| **Modular Vite Web & iPhone PWA** | Single-file Vite pipeline (`web-pwa/`) auto-synced to both Android Hotspot (`app/src/main/assets/pulsemesh_web.html`) and GitHub Pages (`docs/web/index.html`) with unified 4-tab UI, Signal Double Ratchet (`PMDR2:`), Cloudflare Durable Objects WebSocket + TURN, VAPID Web Push for iOS 16.4+ Home Screen PWAs, guided 1-tap iOS `.mobileconfig` Root CA trust, and EN/EL localization. |
| **Verified Updater & Security** | Zero-trust E2EE (Double Ratchet `PMDR2:`, ECDH P-256 + HKDF-SHA256, zero plaintext downgrades), Biometric/PIN App Lock, Android 14-16 (`targetSdk 36`) permission-gated Foreground Services, optional (recommended) `@username` `PV2:` vaults (600,000 PBKDF2 iterations), and fail-closed **SHA-256 + signing-certificate verified in-app APK updater** (`PackageInstaller`). |

---

## Multi-Transport Architecture

| Layer | Transport | Protocol / Interface | Primary Role |
| :---: | :--- | :--- | :--- |
| **1** | **BLE Advertising Beacons** | `0xCAFE` + `0xCAFF` Scan Response | Sub-second peer discovery, micro-degree GPS beacons, and swarm election. |
| **2** | **BLE GATT & L2CAP CoC** | Service `0x0000CAFF` + L2CAP CoC | Bi-directional multi-hop text, file chunks, key exchange, and pure-BLE voice bursts. |
| **3** | **Wi-Fi Direct & Auto-Hotspot** | `UDP 45888` / `TCP 45889` / mDNS / Portal `:8080` & SSL `:8443` | High-bandwidth local subnet routing, full-duplex voice, iPhone PWA portal, and offline APK beaming. |
| **4** | **IPv6 Link-Local & USB/Eth** | `ff02::1` / `usb0` / `rndis0` / `eth0` | Zero-DHCP ad-hoc wireless and zero-RF wired tactical tethering. |
| **5** | **LoRa Hardware Companion** | Meshtastic (`0x94 0xC3` Protobuf / BLE) & MeshCore (`<`/`>` / NUS) | Multi-kilometer off-grid hops via external USB-OTG or BLE LoRa transceivers. |
| **6** | **Acoustic Modem 2.0** | Bell 202 (`1200/2200 Hz`) + RS(255,k) FEC + AES-256-GCM | Air-gapped encrypted data across phone speakers/mics or handheld FM radios. |
| **7** | **Cloudflare Realtime Relay** | Durable Objects WebSocket + Cloudflare Calls TURN (`PM2`/`PMDR2`) | Optional encrypted internet bridge & iOS 16.4+ VAPID Web Push; hard-blocked in Off-Grid Mode. |

---

## Cryptography & `@username` Vaults

- **Forward-Secret Direct & Group E2EE**: Signal-style Double Ratchet (`PMDR2:`) and ECDH P-256 + RFC 5869 HKDF-SHA256 + AES-256-GCM with AEAD AAD binding and zero plaintext downgrade.
- **Optional (Recommended) `@username` Accounts**: PulseMesh works 100% anonymously offline. Claiming an optional `@username` signs the claim with ECDSA P-256 (`PM_CLAIM_V2`), protects key vaults (`PV2:`) with 600,000-iteration PBKDF2-HMAC-SHA256 + 128-bit Recovery Key (`PMRK-`), and enforces server-side challenge-response / lockout-protected vault unlock.
- **Verified Software Updates**: Every release ships `.sha256` checksums; the in-app updater verifies both SHA-256 digest and Android signing certificate match before invoking `PackageInstaller`.

---

## Building from Source

- **Prerequisites**: Android Studio (Ladybug or newer), JDK 21, Android SDK 36 (`minSdk = 24`), Node.js 22+ (for `web-pwa`).

```bash
git clone https://github.com/Kostadinosk/PulseMesh.git
cd PulseMesh

# Build & sync the modular Vite Web/iPhone PWA into Android assets + docs/web/
npm --prefix web-pwa ci
npm --prefix web-pwa run build:sync
npm --prefix web-pwa test

# Run Lite and Full Android unit test suites sequentially
./gradlew :app:testLiteDebugUnitTest --no-daemon
./gradlew :app:testFullDebugUnitTest --no-daemon

# Build Lite and Full release APKs
./gradlew :app:assembleLiteRelease :app:assembleFullRelease --no-daemon
```

---

## Permissions

PulseMesh requests runtime permissions strictly per active capability and gates Foreground Service types cleanly on Android 14-16 (`targetSdk 36`):
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`: BLE mesh discovery, GATT/L2CAP transport, and BLE LoRa companions
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`: BLE/Wi-Fi Direct discovery and optional tactical map GPS pins
- `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`: Local hotspot, Web Portal, and ATAK CoT multicast
- `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`: Full-duplex voice calls, PTT walkie-talkie, and Acoustic Modem 2.0
- `CAMERA`, `NFC`, `REQUEST_INSTALL_PACKAGES`: Optional QR scanning, NFC Tap-to-Pair HCE, and verified in-app APK updates

---

## Bug Reports, Field Tests & Contact

- **Website Contact & Report Builder**: [kostadinosk.github.io/PulseMesh/contact.html](https://kostadinosk.github.io/PulseMesh/contact.html)
- **GitHub Issues**: [github.com/Kostadinosk/PulseMesh/issues](https://github.com/Kostadinosk/PulseMesh/issues)
- **Instagram**: [@pulsemesh_app](https://www.instagram.com/pulsemesh_app/)
- **TikTok**: [@pulsemesh.app](https://www.tiktok.com/@pulsemesh.app)

---

## Disclaimer & Limitation of Liability

This software is provided strictly for educational, experimental, and research purposes. It is distributed **"AS IS"**, without warranty of any kind, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose, reliability, or security.

- **Not for Life-Safety Use**: Do not rely on PulseMesh as a primary means of communication in life-safety, search-and-rescue, medical, or critical emergency situations.
- **Root & Hardware Modifications**: Features that execute privileged (`su`) commands or alter radio power-management parameters are used entirely at the user's own risk.
- **Regulatory Compliance**: Users are solely responsible for ensuring that their use of this software, encryption, and radio transmissions complies with all applicable local laws and telecommunications regulations.
- **Non-Affiliation Notice**: PulseMesh (`com.KostadinosK.pulsemesh`) is an independent open-source off-grid mesh networking project by Konstantinos Kourtis (`@Kostadinosk`) and is not affiliated with, endorsed by, or connected to `pulsemesh.io` or any other commercial entity.

---

## License & Trademark Policy

Released under the **[GNU General Public License v3.0 (`GPL-3.0-only`)](LICENSE)**.

Pursuant to **GPL-3.0 Section 7(e)**, the **PulseMesh** name, custom app icon, and branding remain the exclusive property of Konstantinos Kourtis. See **[TRADEMARK.md](TRADEMARK.md)** for full brand reservation terms.
