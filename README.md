# 🎤 Karaoke Mic — Android App

Turn your phone into a Bluetooth karaoke microphone for YouTube.

## How It Works
- Captures mic → applies reverb/echo/pitch → outputs to Bluetooth A2DP speaker
- YouTube audio also routes to the same BT speaker via Android's audio mixer
- Runs as a foreground service (stays active while you use YouTube)
- Phone speaker stays silent — no echo!

## Build Instructions

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Java 17+

### Steps
1. Open Android Studio
2. File → Open → select the `KaraokeMic` folder
3. Wait for Gradle sync to complete
4. Connect your Android phone (USB debugging on) OR use an emulator
5. Click ▶ Run

### Build APK manually
```bash
./gradlew assembleDebug
# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

### Install on device via ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage
1. Connect a Bluetooth speaker to your phone
2. Open YouTube and find a karaoke video
3. Open Karaoke Mic app → tap **Start Singing**
4. Switch back to YouTube and play the video
5. Your voice (with effects) + YouTube music both play through BT speaker

## Effects
| Slider | Range | Description |
|--------|-------|-------------|
| Volume/Gain | 0–150% | Mic volume boost |
| Reverb | 0–100% | Hall/room reverb simulation |
| Echo | 0–100% | 300ms delay echo |
| Pitch | -6 to +6 | Semitone pitch shift |

## Permissions Required
- **Record Audio** — for microphone input
- **Bluetooth Connect** — to detect BT speaker connection
- **Foreground Service** — to run in background
- **Post Notifications** — for persistent service notification

## Min Android Version
Android 8.0 (API 26) or higher

## Notes
- Bluetooth A2DP latency is ~100-200ms — your voice will have a slight natural delay
  which actually sounds good, like singing with stage monitors
- For best results, position the BT speaker away from you (across the room)
- The app does NOT mute YouTube — both voice and music mix through the BT speaker
