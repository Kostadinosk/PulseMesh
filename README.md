<div align="center">

<img src="docs/assets/pulsemesh-icon.png" alt="PulseMesh Icon" width="118" height="118" />

# PulseMesh

**Off-Grid Peer-to-Peer Android Mesh Messenger, Full-Duplex Voice Communicator & Tactical Offline Map**

[![Release](https://img.shields.io/github/v/release/Kostadinosk/PulseMesh?style=flat-square&logo=github&label=Release&color=19E68C)](https://github.com/Kostadinosk/PulseMesh/releases/tag/v1.0)
[![Platform](https://img.shields.io/badge/Android-7.0%2B%20(API%2024--36)-38CFFF?style=flat-square&logo=android&logoColor=white)](https://github.com/Kostadinosk/PulseMesh/releases/tag/v1.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://github.com/Kostadinosk/PulseMesh)
[![Encryption](https://img.shields.io/badge/Crypto-ECDH%20P--256%20%2B%20AES--256--GCM-FFB02E?style=flat-square)](https://github.com/Kostadinosk/PulseMesh#cryptography--username-vaults)
[![License: MIT](https://img.shields.io/badge/License-MIT-0088E6?style=flat-square)](LICENSE)

[**Live Website**](https://kostadinosk.github.io/PulseMesh/) &bull;
[**Download Release APK (v1.0)**](https://github.com/Kostadinosk/PulseMesh/releases/download/v1.0/app-release.apk) &bull;
[**Download Debug APK**](https://github.com/Kostadinosk/PulseMesh/releases/download/v1.0/app-debug.apk) &bull;
[**Contact & Field Reports**](https://kostadinosk.github.io/PulseMesh/contact.html) &bull;
[**Report an Issue**](https://github.com/Kostadinosk/PulseMesh/issues)

</div>

---

## Overview

**PulseMesh** (`com.KostadinosK.pulsemesh`) is a zero-infrastructure communication suite for Android devices operating where cellular and internet networks are unavailable, degraded, or untrusted. By combining the radios already built into standard smartphones—**Bluetooth Low Energy (`0xCAFE`/`0xCAFF`)**, **Wi-Fi Direct & Auto-Hotspot subnets**, **USB/Ethernet tethering**, and an **Acoustic AFSK modem**—PulseMesh discovers nearby nodes in under a second, routes multi-hop encrypted text and live voice streams, and synchronizes GPS waypoints on an offline Web Mercator tile map.

---

## Key Features

| Module | Highlights |
| :--- | :--- |
| **Multi-Hop DTN Messaging** | Broadcast channels, ECDH group chats, and direct messages with `TTL=7` flood routing, packet deduplication, delivery ACKs, and a persistent Delay-Tolerant Networking (DTN) outbox. |
| **Encrypted `@username` Accounts (`PMU2` / `PMB1`)** | Free cryptographic `@username` handles signed with ECDSA P-256 keys, PBKDF2-HMAC-SHA256 encrypted key vaults, and full-device migration (chats, contacts, keys) via PIN or one-time Recovery Key. |
| **Full-Duplex Voice & Walkie-Talkie (PTT)** | Adaptive 8 kHz G.711 μ-law and 4-bit IMA-ADPCM voice streaming over local Wi-Fi/Hotspot UDP or pure BLE GATT, with half-duplex echo ducking, lock-screen call controls, mini call bar, and voicemail capture. |
| **Offline Tactical Map (`EPSG:3857`)** | Regional Web Mercator tile pyramid downloader with local disk caching, shared tactical pins (`RALLY`, `HAZARD`, `SUPPLY`, `SOS`), live compass bearing HUD, and granular GPS privacy (`EXACT`, `COARSE`, `OFF`). |
| **Hardware & Root Pro Telemetry** | Auto-detection for **75+ phone profiles** (Snapdragon, Dimensity, Exynos, Tensor), live BLE TX/RX diagnostic panels, Pro RSSI charts, and optional root (`su`) radio power-save overrides in **Dark & Daylight Frutiger Aero** themes. |

---

## 8-Layer Multi-Transport Architecture

PulseMesh continuously negotiates the highest-bandwidth physical link between peers while deduplicating frames across all active transports:

| Layer | Transport | Protocol / Interface | Primary Role |
| :---: | :--- | :--- | :--- |
| **1** | **BLE Advertising Beacons** | `0xCAFE` (12/14B) + `0xCAFF` Scan Response | Sub-second peer discovery, `1e-7` micro-degree GPS beacons, and swarm role election. |
| **2** | **BLE GATT Mesh** | Service `0x0000CAFF` (160B serialized chunks) | Bi-directional multi-hop text, key exchange, and pure-BLE voice bursts with deterministic role staggering. |
| **3** | **BLE L2CAP CoC** | Connection-Oriented Channels | High-throughput BLE streaming between compatible Android 10+ devices. |
| **4** | **Wi-Fi Direct & Auto-Hotspot** | `UDP 45888` / `TCP 45889` / mDNS `_pulsemesh._udp.` | Zero-config local subnet multicast, full-duplex voice calls, and rapid media transfer. |
| **5** | **IPv6 Link-Local** | `ff02::1` Multicast | Zero-DHCP peer-to-peer frame routing across ad-hoc wireless interfaces. |
| **6** | **USB-C & Ethernet Bridge** | `usb0` / `rndis0` / `eth0` | Zero-RF wired tactical tethering between phones or field routers. |
| **7** | **Acoustic AFSK Modem** | Bell 202 (`1200/2200 Hz`) + Reed-Solomon FEC | Air-gapped audio transmission over phone speakers/microphones or 3.5mm audio cables. |
| **8** | **Hybrid Encrypted Relay** | Cloudflare D1 / `ntfy` (`PM2` & `PMU2` AES-GCM) | Optional encrypted bridge across distant mesh islands; hard-blocked at the socket layer in Off-Grid Mode. |

---

## Cryptography & `@username` Vaults

- **Direct & Group E2EE**: ECDH P-256 key agreement with HKDF-SHA256 derivation, fresh 96-bit random IVs per message, AES-256-GCM authenticated encryption, and Trust-On-First-Use (TOFU) safety fingerprint verification.
- **Self-Sovereign `@username` Claims**: Usernames are claimed with an ECDSA P-256 signature (`SHA256withECDSA`) over the owner's public keys and timestamp, preventing impersonation without a traditional account server.
- **Zero-Knowledge Device Migration (`PMB1:`)**: Account backups are compressed with GZIP and sealed with AES-256-GCM using a key derived via 120,000-iteration PBKDF2-HMAC-SHA256 from your PIN or Recovery Key.

---

## Quick Start & Building from Source

### Install Pre-Built APK
Grab [`app-release.apk`](https://github.com/Kostadinosk/PulseMesh/releases/download/v1.0/app-release.apk) from the [**v1.0 Release**](https://github.com/Kostadinosk/PulseMesh/releases/tag/v1.0) and install on two or more physical Android devices (Android 7.0 / API 24+).

### Build Locally with Gradle

- **Prerequisites**: Android Studio (Ladybug or newer), JDK 17+, Android SDK 36 (`minSdk = 24`).

```bash
git clone https://github.com/Kostadinosk/PulseMesh.git
cd PulseMesh

# Build debug APK
./gradlew :app:assembleDebug

# Run unit & Robolectric test suites
./gradlew :app:testDebugUnitTest
```

> **Note:** Physical Android hardware is recommended for testing BLE `0xCAFE` advertising, GATT role staggering, and Wi-Fi Direct / Auto-Hotspot negotiation.

---

## Permissions

PulseMesh requests runtime permissions strictly for active radio and voice transports:
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` — BLE mesh discovery and GATT transport
- `ACCESS_FINE_LOCATION` — Required by Android OS for BLE scanning, Wi-Fi Direct, and optional tactical map GPS pins
- `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` — Local hotspot & subnet discovery
- `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` — Full-duplex voice calls, PTT walkie-talkie, and acoustic AFSK modem

---

## Bug Reports, Field Tests & Contact

Tested PulseMesh on a new phone model, custom ROM, or long-range environment? Share your results or get in touch:

- **Website Contact & Report Builder**: [kostadinosk.github.io/PulseMesh/contact.html](https://kostadinosk.github.io/PulseMesh/contact.html)
- **GitHub Issues**: [github.com/Kostadinosk/PulseMesh/issues](https://github.com/Kostadinosk/PulseMesh/issues)
- **Instagram**: [@kkourtis_](https://www.instagram.com/pulsemesh_app/)
- **TikTok**: [@raspberrypi500](https://www.tiktok.com/@pulsemesh.app)

---

## Disclaimer & Limitation of Liability

This software is provided strictly for educational, experimental, and research purposes. It is distributed **"AS IS"**, without warranty of any kind, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose, reliability, or security.

- **Not for Life-Safety Use**: Do not rely on PulseMesh as a primary means of communication in life-safety, search-and-rescue, medical, or critical emergency situations. Radio range, packet delivery, and device compatibility vary significantly across hardware and environments.
- **Root & Hardware Modifications**: Features that execute privileged (`su`) commands or alter radio power-management parameters are used entirely at the user's own risk.
- **Regulatory Compliance**: Users are solely responsible for ensuring that their use of this software, encryption, and radio transmissions complies with all applicable local laws and telecommunications regulations.
- **Non-Affiliation Notice**: PulseMesh (`com.KostadinosK.pulsemesh`) is an independent open-source off-grid mesh networking project by Konstantinos Kourtis (`@Kostadinosk`) and is not affiliated with, endorsed by, or connected to `pulsemesh.io` (synchronized event/light-show audio streaming) or any other commercial entity.

In no event shall the author or contributors be held liable for any direct, indirect, incidental, special, exemplary, or consequential damages arising in any way out of the use or inability to use this software.

---

## License

Released under the [MIT License](LICENSE).
