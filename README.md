# My Assistant - Offline P2P Video & Audio Calling via Wi-Fi Hotspot

**My Assistant** (`com.sarkar.myassistant`) is a high-performance, offline Peer-to-Peer (P2P) audio and video calling Android application powered by WebRTC (`io.github.webrtc-sdk:android:150.7871.04`). It operates completely **without internet connectivity, cellular data, or external cloud signaling servers**, communicating directly over a local Wi-Fi Hotspot or local area network (LAN).

---

## ✨ Features

- 📶 **100% Offline Calling**: No internet, STUN/TURN, or cloud servers required. Works anywhere using your device's built-in Portable Wi-Fi Hotspot.
- 📹 **HD Video & Low-Latency Audio**: Hardware-accelerated WebRTC media streaming with `SurfaceViewRenderer`, hardware video encoders/decoders, and audio processing (`MODE_IN_COMMUNICATION`).
- ⚡ **Local TCP Signaling Server & Client**: Built-in lightweight TCP socket exchange on port `8888` for instant WebRTC SDP Offer/Answer and ICE candidate negotiation.
- 🎯 **One-Tap Hotspot Connection**: Automatically detects device IP addresses and provides a default one-tap connection to `192.168.43.1` (the standard Android Hotspot gateway).
- 🎛️ **Full In-Call Controls**:
  - 🎤 Toggle Microphone (Mute / Unmute)
  - 📷 Toggle Camera Video (Enable / Disable video track)
  - 🔄 Flip Camera (Switch between Front and Back camera with auto-mirroring)
  - 🔊 Toggle Speakerphone (Switch between Loudspeaker and Earpiece)
  - 🔴 End Call / Hang Up
- 📱 **Picture-in-Picture (PIP) Floating Preview**: Elegant floating local video card with full-screen remote video rendering.
- 🤖 **CI/CD Automation**: Pre-configured GitHub Actions workflow (`.github/workflows/build.yml`) that builds the project with JDK 17 and Gradle 8.7 and publishes the debug APK as an artifact.

---

## 🛠️ Project Structure

```
├── .github/
│   └── workflows/
│       └── build.yml               # Automated CI/CD workflow for building debug APK
├── app/
│   ├── build.gradle.kts            # App-level build script with WebRTC dependency
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml # Permissions (CAMERA, RECORD_AUDIO, WIFI, etc.)
│   │   │   ├── java/com/sarkar/myassistant/
│   │   │   │   └── MainActivity.java # WebRTC lifecycle, camera/mic capture, TCP server & client
│   │   │   └── res/
│   │   │       ├── layout/activity_main.xml # SurfaceViewRenderers, status banners, controls
│   │   │       ├── values/
│   │   │       │   ├── strings.xml # App Name: "My Assistant"
│   │   │       │   └── themes.xml  # AppTheme definition
│   │   │       └── drawable/       # Vector icons and styling drawables
├── gradle/
│   └── libs.versions.toml          # Centralized version catalog
├── .gitignore                      # Standard Android gitignore
├── gradle.properties               # Memory & build tuning
├── metadata.json                   # AI Studio platform descriptor
├── README.md                       # Documentation & build instructions
└── settings.gradle.kts             # Repositories & project configuration
```

---

## 🚀 How to Use (Hotspot Calling)

Follow these 3 simple steps to call between two Android devices without internet:

### 1. Set Up the Wi-Fi Hotspot
- **Device 1 (Host)**: Turn on **Portable Wi-Fi Hotspot** in Android Settings. Note: On most Android devices, the hotspot host IP is automatically `192.168.43.1`.
- **Device 2 (Client)**: Turn on **Wi-Fi** and connect to Device 1's hotspot network.

### 2. Launch "My Assistant"
- Open **My Assistant** on both devices.
- Grant **Camera** and **Microphone** permissions when prompted.
- On Device 1, the app displays its local IP (e.g., `192.168.43.1:8888`) and confirms that the TCP signaling server is listening.

### 3. Start the Call
- On Device 2, tap **"Use Default Hotspot IP (192.168.43.1)"** (or type Device 1's IP address).
- Tap **"START P2P VIDEO CALL"**.
- The call connects instantly with bidirectional crystal-clear audio and video!

---

## 🔨 Build Instructions

### Prerequisites
- **JDK 17** or higher
- **Android SDK** with `compileSdk 34+`
- **Gradle 8.7+**

### Local Build (Terminal)
To build the debug APK using Gradle:

```bash
gradle assembleDebug
```

The output APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions (Automated CI/CD)
Pushing to `main` or triggering the workflow manually in GitHub (`Actions` tab > `Build Android Debug APK` > `Run workflow`) will:
1. Set up Ubuntu environment with JDK 17 & Gradle 8.7.
2. Compile and assemble the debug APK.
3. Upload the APK as an artifact named `app-debug-apk`.

---

## 🔒 Permissions & Security

Declared in `app/src/main/AndroidManifest.xml`:
- `android.permission.INTERNET`: Required for local network socket communication.
- `android.permission.ACCESS_NETWORK_STATE`: Resolves local network interfaces and IP addresses.
- `android.permission.ACCESS_WIFI_STATE`: Checks Wi-Fi and hotspot status.
- `android.permission.RECORD_AUDIO`: Captures microphone input for the WebRTC audio track.
- `android.permission.CAMERA`: Captures video stream from front or rear camera.
- `android.permission.MODIFY_AUDIO_SETTINGS`: Routes call audio through speakerphone or earpiece.

---

## 📜 License
Distributed under the Apache 2.0 License.
