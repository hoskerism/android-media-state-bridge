package com.example.mediastatebridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NotificationListenerService is used purely as a host that gives us the
 * permission required by MediaSessionManager.getActiveSessions(). We do not
 * do anything with notifications themselves.
 *
 * Lifecycle contract
 * ──────────────────
 * • MQTT is started/stopped independently of the notification-listener binding.
 * • Session listener attachment only happens from onListenerConnected(), so we
 *   never race against the OS binding the service.
 * • addConnectedListener fires on every (re)connect, so online + discovery are
 *   always re-published after a broker blip.
 * • Sessions are keyed by MediaSession.Token, not MediaController object
 *   identity, so new wrapper instances for the same session are handled
 *   correctly.
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaBridge"
        const val ACTION_RESTART = "com.example.mediastatebridge.RESTART"

        fun requestRestart(ctx: Context) {
            ctx.startService(
                Intent(ctx, MediaListenerService::class.java).setAction(ACTION_RESTART)
            )
        }
    }

    private var mqtt: Mqtt3AsyncClient? = null

    // Keyed by stable session token, not MediaController object identity.
    private val sessions =
        ConcurrentHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private val handler = Handler(Looper.getMainLooper())
    private var device: String = "android_streamer"
    private var topicBase: String = "mediabridge/android_streamer"

    // True only while the OS has the notification listener bound. Session
    // attachment is gated on this flag so we never call getActiveSessions()
    // before onListenerConnected() has fired.
    @Volatile private var listenerConnected = false

    override fun onListenerConnected() {
        Log.i(TAG, "Listener connected")
        listenerConnected = true
        if (mqtt?.state == MqttClientState.CONNECTED) {
            // MQTT already up (e.g. reconnected before OS re-bound the listener).
            handler.post { attachSessionListener() }
        } else {
            // MQTT not running yet — start it. connectedListener will attach
            // sessions once the broker handshake completes.
            startMqtt()
        }
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Listener disconnected")
        listenerConnected = false
        detachSessionListener()
        stopMqtt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            // Re-read config and bounce MQTT. Do NOT touch the session listener
            // here — it is only managed by the onListenerConnected/Disconnected
            // callbacks to avoid racing the OS binding.
            stopMqtt()
            if (listenerConnected) startMqtt()
        }
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { /* unused */ }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* unused */ }

    // ── MQTT lifecycle ────────────────────────────────────────────────────────

    private fun startMqtt() {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        val port = (prefs.getString("port", "1883") ?: "1883").toIntOrNull() ?: 1883
        val user = prefs.getString("user", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        device = prefs.getString("device", "android_streamer") ?: "android_streamer"
        topicBase = "mediabridge/$device"

        if (host.isBlank()) {
            Log.w(TAG, "No broker configured; open the app and save settings")
            return
        }

        val client = MqttClient.builder()
            .useMqttVersion3()
            .identifier("mediabridge-$device-${UUID.randomUUID().toString().take(6)}")
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            // Fires on every successful connect, including automatic reconnects.
            // Ensures online + discovery are always re-published after a broker blip.
            .addConnectedListener {
                Log.i(TAG, "MQTT (re)connected to $host:$port")
                publish("$topicBase/availability", "online", retain = true)
                publishDiscovery()
                if (listenerConnected) handler.post { attachSessionListener() }
            }
            .willPublish()
                .topic("$topicBase/availability")
                .payload("offline".toByteArray(StandardCharsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
            .applyWillPublish()
            .buildAsync()

        mqtt = client

        val connect = if (user.isNotBlank()) {
            client.connectWith()
                .simpleAuth()
                .username(user)
                .password(pass.toByteArray(StandardCharsets.UTF_8))
                .applySimpleAuth()
        } else {
            client.connectWith()
        }

        connect.send().whenComplete { _, ex ->
            if (ex != null) {
                // Auto-reconnect will keep retrying; connectedListener fires
                // when it eventually succeeds.
                Log.e(TAG, "MQTT initial connect failed, will retry automatically", ex)
            }
        }
    }

    private fun stopMqtt() {
        try {
            mqtt?.publishWith()
                ?.topic("$topicBase/availability")
                ?.payload("offline".toByteArray(StandardCharsets.UTF_8))
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.retain(true)
                ?.send()
        } catch (_: Throwable) { /* ignore */ }
        try { mqtt?.disconnect() } catch (_: Throwable) { /* ignore */ }
        mqtt = null
    }

    // ── session listener lifecycle ────────────────────────────────────────────

    private val sessionChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            handler.post { rebindSessions(list ?: emptyList()) }
        }

    private fun attachSessionListener() {
        val component = ComponentName(this, MediaListenerService::class.java)
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        // Remove first to guard against duplicate registration (e.g. MQTT
        // reconnect fires while the listener is already attached).
        try { msm.removeOnActiveSessionsChangedListener(sessionChangedListener) } catch (_: Throwable) { /* ignore */ }
        msm.addOnActiveSessionsChangedListener(sessionChangedListener, component)
        rebindSessions(msm.getActiveSessions(component))
    }

    private fun detachSessionListener() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(sessionChangedListener)
        } catch (_: Throwable) { /* ignore */ }
        sessions.values.forEach { (c, cb) ->
            try { c.unregisterCallback(cb) } catch (_: Throwable) { /* ignore */ }
        }
        sessions.clear()
    }

    // ── session rebinding ─────────────────────────────────────────────────────

    private fun rebindSessions(controllers: List<MediaController>) {
        val incomingTokens = controllers.map { it.sessionToken }.toSet()

        // Unsubscribe from sessions that have vanished. Keying by token means
        // we correctly identify the same session even if the OS returns a new
        // MediaController wrapper instance.
        sessions.keys.filter { it !in incomingTokens }.forEach { token ->
            sessions.remove(token)?.let { (c, cb) ->
                try { c.unregisterCallback(cb) } catch (_: Throwable) { /* ignore */ }
                publishState(c.packageName, null, null)
            }
        }

        // Subscribe to sessions we haven't seen before.
        for (c in controllers) {
            if (sessions.containsKey(c.sessionToken)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    publishState(c.packageName, state, c.metadata)
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    publishState(c.packageName, c.playbackState, metadata)
                }
                override fun onSessionDestroyed() {
                    sessions.remove(c.sessionToken)
                    publishState(c.packageName, null, null)
                }
            }
            c.registerCallback(cb, handler)
            sessions[c.sessionToken] = Pair(c, cb)
            publishState(c.packageName, c.playbackState, c.metadata)
        }

        val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        publish("$topicBase/active", active?.packageName ?: "none", retain = true)
    }

    private fun publishState(pkg: String, state: PlaybackState?, meta: MediaMetadata?) {
        val s = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_NONE, null -> "none"
            else -> "other"
        }
        val safe = pkg.replace('.', '_')
        publish("$topicBase/app/$safe/state", s, retain = true)
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        publish("$topicBase/app/$safe/title", title, retain = true)
        Log.i(TAG, "$pkg -> $s ($title)")
    }

    private fun publish(topic: String, payload: String, retain: Boolean) {
        val c = mqtt ?: return
        try {
            c.publishWith()
                .topic(topic)
                .payload(payload.toByteArray(StandardCharsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .send()
        } catch (t: Throwable) {
            Log.w(TAG, "Publish failed for $topic", t)
        }
    }

    // ---------------- Home Assistant MQTT Discovery ----------------

    private fun publishDiscovery() {
        val apps = mapOf(
            "Netflix" to "com.netflix.ninja",
            "YouTube" to "com.google.android.youtube.tv",
            "Plex" to "com.plexapp.android",
            "Disney+" to "com.disney.disneyplus",
            "Prime Video" to "com.amazon.amazonvideo.livingroom",
            "Stan" to "au.com.stan.and",
            "Spotify" to "com.spotify.tv.android",
            "Kodi" to "org.xbmc.kodi"
        )
        val deviceBlock =
            """"device":{"identifiers":["$device"],"name":"$device","manufacturer":"MediaStateBridge","model":"Android TV"}"""

        for ((name, pkg) in apps) {
            val safe = pkg.replace('.', '_')
            val uniq = "${device}_$safe"
            val cfg = """
                {
                  "name":"$name state",
                  "unique_id":"$uniq",
                  "state_topic":"$topicBase/app/$safe/state",
                  "availability_topic":"$topicBase/availability",
                  $deviceBlock
                }
            """.trimIndent()
            publish("homeassistant/sensor/$uniq/config", cfg, retain = true)
        }

        val cfgActive = """
            {
              "name":"Active media app",
              "unique_id":"${device}_active",
              "state_topic":"$topicBase/active",
              "availability_topic":"$topicBase/availability",
              $deviceBlock
            }
        """.trimIndent()
        publish("homeassistant/sensor/${device}_active/config", cfgActive, retain = true)
    }
}
