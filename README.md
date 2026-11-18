# PulseMesh

PulseMesh is an Android application for off-grid peer-to-peer communication when cellular and internet infrastructure are unavailable or untrusted. It uses the radios already present on standard Android hardware—Bluetooth Low Energy, Classic Bluetooth RFCOMM, Wi-Fi Direct / local hotspot subnets, and audio AFSK—to discover nearby nodes, route text and voice traffic, and share GPS coordinates on an offline tile map.

## Architecture & Transports

PulseMesh runs a multi-transport routing engine that automatically discovers peers and selects the highest-bandwidth link available:

- **BLE 1M & Coded PHY (`0xCAFE` / `0xCAFF`)**: Low-overhead 12-byte discovery beacons, scan-response micro-frames, and bi-directional GATT server/client packet chunking with deterministic connection staggering to avoid GATT race conditions across different OEM Bluetooth stacks.
- **Local IP Subnet & Wi-Fi Direct (`UDP 45888` / `TCP 45889`)**: Multicast, broadcast, and mDNS (`_pulsemesh._udp.`) peer discovery across local Wi-Fi hotspots, Wi-Fi Direct groups, IPv6 link-local (`ff02::1`), and USB/Ethernet interfaces (`usb0`, `rndis0`, `eth0`).
- **Bluetooth Classic RFCOMM**: Fallback stream sockets for bonded devices.
- **Acoustic AFSK Modem**: Bell 202-style audio frequency-shift keying (1200/2200 Hz) with Reed-Solomon FEC for air-gapped transmission over phone speakers and microphones or wired audio cables.
- **Optional Hybrid Relay**: When internet access is explicitly enabled, encrypted packets can bridge across distant mesh islands via an `ntfy`-compatible pub/sub endpoint. When Off-Grid Mode is active, all HTTP/network calls are hard-blocked at the transport layer.

## Core Capabilities

- **Messaging & Store-and-Forward DTN**: Broadcast channels, group chats, and direct messages. Unacknowledged packets are queued in a Delay-Tolerant Networking (DTN) outbox and flushed automatically when a target peer or relay node comes into range.
- **Cryptography**: End-to-end encryption using X25519 key agreement, HKDF-SHA256 ratcheting, and AES-256-GCM authenticated encryption, with out-of-band safety number verification.
- **Voice & Walkie-Talkie (PTT)**: Low-bitrate µ-law / sub-frame voice bursts for push-to-talk over constrained links, plus full-duplex UDP audio streaming when nodes share a local Wi-Fi/hotspot subnet.
- **Offline Mapping**: Web Mercator (`EPSG:3857`) tile pyramid downloader and renderer with local disk caching, shared waypoints (`RALLY`, `HAZARD`, `SUPPLY`, `SOS`), and live compass/barometric altitude tracking.
- **Hardware & Root Telemetry**: Runtime detection for Qualcomm, MediaTek, Exynos, and Google Tensor radios, with optional `su` scripts on rooted devices to disable Wi-Fi power-save states and background BLE scan throttling.

## Build Requirements

- Android Studio (Ladybug or newer recommended)
- JDK 17+
- Android SDK 35 / 36 (`minSdk = 24`, Android 7.0+)

### Building Locally

1. Clone the repository and open the root directory in Android Studio.
2. In `app/build.gradle.kts`, either provide a local `debug.keystore` in the project root or comment out `signingConfig = signingConfigs.getByName("debugConfig")` under `buildTypes.debug` to use your default `~/.android/debug.keystore`.
3. Build and install on two or more physical Android devices (BLE advertising and Wi-Fi Direct require physical hardware and cannot be tested solely on emulators):
   ```bash
   ./gradlew installDebug
   ```

## Permissions

The app requests runtime permissions for Bluetooth scanning/advertising (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`), fine location (`ACCESS_FINE_LOCATION`, required by Android for BLE scanning and Wi-Fi Direct), local network state (`NEARBY_WIFI_DEVICES`), and microphone access (`RECORD_AUDIO` for PTT and acoustic modem).

## Bug Reports, Feedback & Contact

If you run into a bug, have hardware test results to share, or want to suggest an improvement, please open an issue in this repository or reach out directly:

- **GitHub Issues**: Open an issue on this repository
- **Instagram**: [@kkourtis_](https://www.instagram.com/kkourtis_/)
- **TikTok**: [@raspberrypi500](https://www.tiktok.com/@raspberrypi500)

## Disclaimer & Limitation of Liability

This software is provided strictly for educational, experimental, and research purposes. It is distributed "AS IS", without warranty of any kind, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose, reliability, or security.

- **Not for Life-Safety Use**: Do not rely on PulseMesh as a primary means of communication in life-safety, search-and-rescue, medical, or critical emergency situations. Radio range, packet delivery, and device compatibility vary significantly across hardware and environments.
- **Root & Hardware Modifications**: Features that execute privileged (`su`) commands or alter radio power-management parameters are used entirely at the user's own risk.
- **Regulatory Compliance**: Users are solely responsible for ensuring that their use of this software, encryption, and radio transmissions complies with all applicable local laws and telecommunications regulations.

In no event shall the author or contributors be held liable for any direct, indirect, incidental, special, exemplary, or consequential damages (including, without limitation, device damage, data loss, network disruption, or personal injury) arising in any way out of the use or inability to use this software.

## License

MIT
