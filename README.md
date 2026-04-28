# Media State Bridge (Android TV → MQTT → Home Assistant)

Reports playback state (`playing` / `paused` / `buffering` / `stopped` / `none`)
for **all** apps that publish a `MediaSession` on a Google TV Streamer (or any
Android TV device on Android 12+), including **Netflix**, which the Cast
integration mis-reports.

It works by hosting a `NotificationListenerService` purely to obtain the
permission required to call `MediaSessionManager.getActiveSessions()` and
register `MediaController.Callback` listeners on each session. Events are
pushed over MQTT to Home Assistant using MQTT Discovery, so sensors appear
automatically.

This avoids ADB entirely — Google's Android 14 ADB lockdown does not affect
this API.

## Why not the Netflix Cast state?

The Netflix Android TV app reports `playing` to Cast from the moment the app
opens. There is no legitimate alternate Netflix build that fixes this. The
`MediaSession` it publishes on-device, however, *does* transition through
PLAYING / PAUSED / BUFFERING correctly — that is what this app reads.

## Build the APK (no local Android Studio required)

1. Push this folder to a (private) GitHub repo.
2. The workflow in `.github/workflows/build.yml` runs automatically and
   produces an artifact called **media-state-bridge-debug** on the run page.
   Download `app-debug.apk` from there.

If you prefer building locally, install JDK 17 + Android command-line tools,
then from this directory run:

```powershell
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Sideload onto the Google TV Streamer (4K, 2024)

1. On the streamer: **Settings → System → About → Android TV OS build** —
   click 7 times to enable Developer options.
2. **Settings → System → Developer options → USB debugging: On** (and
   **Wireless debugging: On**).
3. From a PC on the same LAN:

   ```powershell
   adb connect <streamer-ip>:5555
   adb install -r app-debug.apk
   ```

   (You'll get a pairing prompt on the TV the first time.)
4. Launch **Media State Bridge** from the apps row. Enter:
   - Broker host (your HA / Mosquitto IP)
   - Port (`1883`)
   - Username / password (if Mosquitto requires them)
   - Device id (e.g. `cinema_streamer`)
5. Tap **Open Notification Access settings**, find **Media State Bridge**,
   toggle it **On**, confirm.
6. Return to the app, tap **Save & restart bridge**.

## Verify

Subscribe to the topic tree from any MQTT client:

```text
mediabridge/cinema_streamer/availability        → "online"
mediabridge/cinema_streamer/active              → currently active package
mediabridge/cinema_streamer/app/com_netflix_ninja/state   → playing | paused | …
mediabridge/cinema_streamer/app/com_netflix_ninja/title   → episode title
```

In Home Assistant you should immediately see auto-discovered sensors under a
device named after your `device id`, e.g. `sensor.netflix_state`,
`sensor.youtube_state`, `sensor.active_media_app`, etc.

## Example Home Assistant automation (cinema lights)

```yaml
automation:
  - alias: Cinema lights — dim when Netflix plays
    trigger:
      - platform: state
        entity_id: sensor.netflix_state
        to: "playing"
        for: "00:00:01"
    action:
      - service: light.turn_on
        target: { entity_id: light.cinema }
        data: { brightness_pct: 5, transition: 2 }

  - alias: Cinema lights — raise when Netflix paused
    trigger:
      - platform: state
        entity_id: sensor.netflix_state
        to: "paused"
        for: "00:00:01"
    action:
      - service: light.turn_on
        target: { entity_id: light.cinema }
        data: { brightness_pct: 60, transition: 1 }
```

End-to-end latency is typically <1s; the `for: "00:00:01"` debounce avoids
flicker on brief BUFFERING transitions.

## Notes & caveats

- Notification Access is a one-time grant. On Android 14 it has not been
  observed to be auto-revoked for inactivity for sideloaded apps, but if it
  ever is, just toggle it back on.
- The package name list in `MediaListenerService.publishDiscovery()` covers
  the popular streaming apps. State is published for **every** app that
  publishes a session, even ones not in the list — they show up under
  `mediabridge/<device>/app/<package>/state`. Add them to the discovery list
  to get auto-created HA sensors.
- The app uses MQTT v3.1.1 over plain TCP. If your broker requires TLS, swap
  `serverPort(port)` for `.serverPort(port).sslWithDefaultConfig()` in
  `MediaListenerService.kt`.
- Keep the app on the device — uninstalling removes the Notification Access
  grant and stops the bridge.
