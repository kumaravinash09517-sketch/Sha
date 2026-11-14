# My Assistant - Offline P2P Video & Audio Calling + Telegram & Hardware Controls

**My Assistant** (`com.sarkar.myassistant`) is a production-ready Android application for **100% offline P2P audio and video calling over Wi-Fi Hotspot** (`192.168.43.x`), powered by WebRTC (`io.github.webrtc-sdk:android:150.7871.04`) and strict FIFO TCP signaling on port `8888`, along with integrated Telegram Admin notifications, smart connection diagnostics, and local/remote device hardware controls.

---

## Key Features

- **Offline Wi-Fi Hotspot P2P Calling**:
  - `PeerConnectionFactory.Options` configured with `disableNetworkMonitor = true` and `ADAPTER_TYPE_CELLULAR` ignore mask so Hotspot Host (`ap0`/`softap0`/`192.168.43.1`) and Hotspot Client (`192.168.43.x`) reliably gather IPv4 host ICE candidates without internet.
  - Wi-Fi `Network` socket binding (`NetworkDiagnostics.createBoundLocalSocket`) ensures TCP signaling bypasses mobile data routing when connected to an offline hotspot.
  - Strict FIFO outgoing writer (`SingleThreadExecutor`) and `pendingRemoteCandidates` queue ensure ICE candidates are never dropped before `setRemoteDescription` finishes.
- **Smart Diagnostics & Live Counters**:
  - Real-time UI counters for **Active Peers**, **Incoming Call Status**, **Connection Health**, and **Local/Remote ICE Candidates**.
  - Automatic failure diagnosis displaying exact causes on the UI (e.g., `Wrong IP address format`, `AP Isolation enabled`, `ICE Candidate gathering failed`, `Socket timeout`, or `Connection refused`).
- **Hardcoded Telegram Bot Admin Alerts**:
  - Sends automated status alerts via the Telegram Bot HTTP API on app launch, username registration, call initiation, and manual device status checks (gracefully skipped when completely offline).
  - Includes one-tap **Sync Admin Chat** via `/getUpdates` to auto-discover the admin chat ID.
- **Hardware & Remote Peer System Controls**:
  - **Torch / Flashlight ON/OFF** via `CameraManager`.
  - **Screen Lock Trigger** via `DevicePolicyManager` and `MyAdminReceiver`.
  - **Device Status Check** (model, Android version, battery %, local IPs, Wi-Fi transport, torch, admin lock, and ICE state).
  - Supports remote execution (`Peer Torch`, `Peer Status`, `Peer Lock`) over the active P2P signaling socket.
- **CI/CD Ready (`.github/workflows/build.yml`)**:
  - Configured with **JDK 21**, **Gradle 9.3.1**, **AGP 9.1.1**, Android SDK setup, and automatic `app-debug.apk` artifact upload.

---

## Build Instructions

```bash
./gradlew assembleDebug
```

Output APK location:
```
app/build/outputs/apk/debug/app-debug.apk
```
