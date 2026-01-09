# Visual Mapper Android Companion

**Android companion app for Visual Mapper - enables local device control and sensor capture.**

[![Android](https://img.shields.io/badge/android-11%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9%2B-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## What is this?

The Visual Mapper Android Companion app runs on your Android device and provides:

- **Accessibility Service** - Captures UI elements and screen state
- **Local Action Execution** - Performs taps, swipes, and gestures
- **MQTT Communication** - Sends sensor data directly to Home Assistant
- **Flow Execution** - Runs automation flows locally on the device

This app works alongside the [Visual Mapper Server](https://github.com/botts7/visual-mapper) to provide a complete Android automation solution for Home Assistant.

---

## Features

| Feature | Description |
|---------|-------------|
| **UI Element Detection** | Captures all visible UI elements via Accessibility Service |
| **Sensor Publishing** | Publishes element values as MQTT sensors |
| **Action Execution** | Taps, swipes, scrolls, and text input |
| **Flow Runner** | Executes multi-step automation flows |
| **App Explorer** | AI-assisted app navigation and mapping |
| **Privacy Controls** | Configurable app exclusions and consent management |
| **Audit Logging** | Tracks all accessibility service usage |

---

## Requirements

- Android 11 or higher
- Accessibility Service permission
- Network access to Visual Mapper server or MQTT broker

---

## Installation

### Option 1: Build from Source

```bash
# Clone the repository
git clone https://github.com/botts7/visual-mapper-android.git
cd visual-mapper-android

# Build debug APK
./gradlew assembleDebug

# Install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option 2: Download APK

Download the latest APK from [Releases](https://github.com/botts7/visual-mapper-android/releases).

---

## Setup

1. **Install the app** on your Android device
2. **Grant Accessibility Permission** when prompted
3. **Configure Server URL** - Enter your Visual Mapper server address
4. **Configure MQTT** (optional) - For direct Home Assistant integration

---

## Project Structure

```
visual-mapper-android/
├── app/
│   └── src/main/
│       ├── java/com/visualmapper/companion/
│       │   ├── accessibility/    # Accessibility service
│       │   ├── explorer/         # App exploration & AI
│       │   ├── mqtt/             # MQTT client
│       │   ├── sensor/           # Sensor capture
│       │   ├── security/         # Privacy & consent
│       │   ├── storage/          # Local database
│       │   └── ui/               # Activities & fragments
│       └── res/                  # Resources
├── build.gradle.kts
└── README.md
```

---

## Related Repositories

| Repository | Description |
|------------|-------------|
| [visual-mapper](https://github.com/botts7/visual-mapper) | Main server application |
| [visual-mapper-android](https://github.com/botts7/visual-mapper-android) | Android companion app (this repo) |
| [visual-mapper-addon](https://github.com/botts7/visual-mapper-addon) | Home Assistant add-on |

---

## Privacy & Security

The app includes several privacy features:

- **App Exclusions** - Exclude sensitive apps from monitoring
- **Consent Management** - Per-app consent for data collection
- **Audit Log** - View all accessibility service activity
- **PIN Lock** - Optional app lock screen

---

## License

MIT License - see [LICENSE](LICENSE) for details.

---

## Support

- **Issues:** [GitHub Issues](https://github.com/botts7/visual-mapper-android/issues)
