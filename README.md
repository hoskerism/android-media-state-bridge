# Media State Bridge (Android TV → MQTT → Home Assistant)

Publishes real-time playback state (`playing` / `paused` / `buffering` / `idle`)
from a Google TV Streamer (or any Android TV device on Android 12+) to Home
Assistant via MQTT — including **Netflix**, which the Cast integration
mis-reports.

## The problem

After the Android 14 update, Google nerfed ADB access on streaming devices.
Home Assistant's ADB integration can no longer read playback state. The Cast
integration also doesn't help — Netflix reports `playing` from the moment the
app is opened, regardless of whether content is actually playing.

## How it works

The app hosts a `NotificationListenerService` purely to obtain the permission
required to call `MediaSessionManager.getActiveSessions()`. It registers
`MediaController.Callback` listeners on every active media session and pushes
state changes to your MQTT broker. Home Assistant discovers the sensors
automatically via MQTT Discovery.

This avoids ADB entirely — Google's Android 14 lockdown does not affect this
API. Netflix's on-device `MediaSession` *does* correctly transition through
PLAYING / PAUSED / BUFFERING — that is what this app reads.

## Sensors published

| Sensor | Description |
|--------|-------------|
| `sensor.<device>_tv_state` | Consolidated state: `playing` / `paused` / `buffering` / `idle` |
| `sensor.<device>_active` | Package name of the currently active media app |

Per-app state is also available on the MQTT topic tree at
`mediabridge/<device>/app/<package>/state` for advanced automations.

## Prerequisites

- Google TV Streamer (4K, 2024) or any Android TV device with Developer Options enabled
- ADB installed on your computer ([platform-tools](https://developer.android.com/tools/releases/platform-tools))
- Home Assistant with the **Mosquitto broker** add-on (or any MQTT broker)
- Streamer and HA on the same LAN (wired or wireless)

## Step 1: Download the APK

Download `media-state-bridge-debug.apk` from the
[latest Actions build](../../actions) (click the run → scroll to **Artifacts**
→ download the zip).

Or build locally (requires JDK 17 + Android command-line tools):

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/media-state-bridge-debug.apk
```

## Step 2: Enable Developer Options on the Streamer

1. **Settings → System → About → Android TV OS build** — click 7 times.
2. **Settings → System → Developer options → USB debugging: On** and
   **Wireless debugging: On**.

## Step 3: Install the APK

```bash
adb connect <streamer-ip>:5555
adb install -r media-state-bridge-debug.apk
```

You'll get a pairing prompt on the TV the first time.

## Step 4: Configure Mosquitto

Ensure your broker has a login for the bridge. In Home Assistant go to
**Settings → Apps (or Add-ons) → Mosquitto broker → Configuration** and add:

```yaml
logins:
  - username: google_tv_streamer
    password: YourStrongPasswordHere
```

Save and restart the Mosquitto add-on.

## Step 5: Grant Notification Access

On Android 14, the in-app toggle is greyed out. Grant via ADB instead:

```bash
adb shell cmd notification allow_listener com.example.mediastatebridge/com.example.mediastatebridge.MediaListenerService
```

## Step 6: Configure the app

1. Open **Media State Bridge** on the TV.
2. Fill in:
   - **MQTT Broker:** your HA IP (e.g. `192.168.1.10`)
   - **Port:** `1883`
   - **Username / Password:** as configured in Mosquitto
   - **Device ID:** e.g. `google_tv_streamer`
3. Tap **Save & restart bridge**.

## Step 7: Verify

In Home Assistant, go to **Settings → Devices & Services**. You should see a
device matching your Device ID with `sensor.<device>_tv_state` and
`sensor.<device>_active`.

Play/pause Netflix — the state updates within ~1 second.

You can also check MQTT directly:

```text
mediabridge/<device>/availability  → "online"
mediabridge/<device>/tv_state      → "playing" | "paused" | "buffering" | "idle"
mediabridge/<device>/active        → "com.netflix.ninja"
```

## Example automation (cinema lights)

```yaml
automation:
  - alias: Cinema lights — dim when playing
    trigger:
      - platform: state
        entity_id: sensor.google_tv_streamer_tv_state
        to: "playing"
        for: "00:00:01"
    action:
      - service: light.turn_on
        target: { entity_id: light.cinema }
        data: { brightness_pct: 5, transition: 2 }

  - alias: Cinema lights — raise when paused
    trigger:
      - platform: state
        entity_id: sensor.google_tv_streamer_tv_state
        to: "paused"
        for: "00:00:01"
    action:
      - service: light.turn_on
        target: { entity_id: light.cinema }
        data: { brightness_pct: 60, transition: 1 }
```

The `for: "00:00:01"` debounce avoids flicker during brief buffering
transitions. End-to-end latency is typically <1 second.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| No sensors appear in HA | Check Mosquitto logs for "not authorised". Fix credentials and tap Save in the app. |
| Listener not connected | Re-run the ADB `allow_listener` command from Step 5. |
| State stuck on `idle` | Run `adb logcat -s MediaBridge` while playing content to confirm events are flowing. |

## Notes

- Notification Access has not been observed to auto-revoke on Android 14 for
  sideloaded apps. If it ever does, re-run the ADB command from Step 5.
- Per-app state is published to `mediabridge/<device>/app/<package>/state`
  even without discovery entries, so you can create manual MQTT sensors for
  any app.
- MQTT uses plain TCP (port 1883). For TLS, add `.sslWithDefaultConfig()` in
  `MediaListenerService.kt`.
- Uninstalling the app removes the Notification Access grant.
