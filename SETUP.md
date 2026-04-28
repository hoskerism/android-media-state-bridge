# Setup Guide – Google TV Streamer (Android 14)

The app works perfectly once sideloaded and configured, but two extra steps are required on the Google TV Streamer because of Android 14 restrictions.

## Prerequisites

- Google TV Streamer with Developer Options enabled and Wireless Debugging turned on.
- ADB installed on your computer (platform-tools from Google).
- Home Assistant OS with the Mosquitto broker add-on installed.

## Step 1: Install the APK

1. Download the APK from this repo (`media-state-bridge-debug.apk`).
2. Connect to the streamer:

```bash
adb connect YOUR_TV_IP:5555
```

3. Install the app:

```bash
adb install -r media-state-bridge-debug.apk
```

## Step 2: Configure Mosquitto (required – no anonymous access)

In Home Assistant go to: **Settings → Add-ons → Mosquitto broker → Configuration**.

Add or edit the `logins` section:

```yaml
logins:
  - username: google_tv_streamer
    password: YourStrongPasswordHere
```

> **Note:** The latest Mosquitto broker add-on has a GUI for adding logins. You may not need to edit the YAML manually.

Click **Save** then **Restart** the Mosquitto add-on.

## Step 3: Grant Notification Access (required on Android 14)

The in-app toggle is read-only on the TV. Grant it via ADB:

```bash
adb shell cmd notification allow_listener com.example.mediastatebridge/com.example.mediastatebridge.MediaListenerService
```

## Step 4: Configure the app on the TV

1. Open **Media State Bridge**.
2. Fill in:
   - **MQTT Broker:** `YOUR_HA_IP` (port 1883)
   - **Username & Password:** exactly as set in Mosquitto
   - **Device ID:** `google_tv_streamer` (or any name you prefer)
3. Tap **Save & restart bridge**.

## Step 5: Verify in Home Assistant

- Go to **Settings → Devices & Services**.
- You should see a new device called `google_tv_streamer` containing:
  - `sensor.netflix_state`
  - `sensor.active_media_app`
  - Sensors for Disney+, YouTube, Plex, Prime Video, etc.

Play/pause Netflix — the state updates within ~1 second.

## Troubleshooting

- **No sensors appear** → check Mosquitto logs for "not authorised". Fix credentials and restart bridge in the app.
- **Listener not connected** → re-run the ADB notification command.
- **Still nothing** → run:
  - Windows: `adb logcat | findstr /i "MediaBridge"`
  - macOS/Linux: `adb logcat | grep -i MediaBridge`

...after restarting the bridge and paste the output.

---

Once set up, the sensors are fully automatic via MQTT Discovery and work reliably with Netflix, YouTube, etc.
