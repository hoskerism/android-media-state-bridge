package com.example.mediastatebridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NotificationListenerService is used purely as a host that gives us the
 * permission required by MediaSessionManager.getActiveSessions(). We do not
 * do anything with notifications themselves.
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
    private val controllers = ConcurrentHashMap<MediaController, MediaController.Callback>()
    private val handler = Handler(Looper.getMainLooper())
    private var device: String = "android_streamer"
    private var topicBase: String = "mediabridge/android_streamer"

    override fun onListenerConnected() {
        Log.i(TAG, "Listener connected")
        startBridge()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Listener disconnected")
        stopBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            stopBridge()
            startBridge()
        }
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { /* unused */ }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* unused */ }

    // ---------------- bridge lifecycle ----------------

    private fun startBridge() {
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

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("mediabridge-$device-${UUID.randomUUID().toString().take(6)}")
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .willPublish()
            .topic("$topicBase/availability")
            .payload("offline".toByteArray(StandardCharsets.UTF_8))
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()

        val client = builder.buildAsync()

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
                Log.e(TAG, "MQTT connect failed", ex)
                return@whenComplete
            }
            Log.i(TAG, "MQTT connected to $host:$port")
            publish("$topicBase/availability", "online", retain = true)
            publishDiscovery()
            handler.post { attachSessionListener() }
        }
        mqtt = client
    }

    private fun stopBridge() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Throwable) { /* ignore */ }

        controllers.forEach { (c, cb) ->
            try { c.unregisterCallback(cb) } catch (_: Throwable) { /* ignore */ }
        }
        controllers.clear()

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

    // ---------------- media session plumbing ----------------

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        handler.post { rebindSessions(list ?: emptyList()) }
    }

    private fun attachSessionListener() {
        val component = ComponentName(this, MediaListenerService::class.java)
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.addOnActiveSessionsChangedListener(sessionListener, component)
        rebindSessions(msm.getActiveSessions(component))
    }

    private fun rebindSessions(sessions: List<MediaController>) {
        val current = sessions.toSet()

        // Remove vanished sessions.
        controllers.keys.filter { it !in current }.forEach { gone ->
            controllers.remove(gone)?.let {
                try { gone.unregisterCallback(it) } catch (_: Throwable) { /* ignore */ }
            }
            publishState(gone.packageName, null, null)
        }

        // Subscribe to new sessions.
        for (c in sessions) {
            if (controllers.containsKey(c)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    publishState(c.packageName, state, c.metadata)
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    publishState(c.packageName, c.playbackState, metadata)
                }
                override fun onSessionDestroyed() {
                    controllers.remove(c)
                    publishState(c.packageName, null, null)
                }
            }
            c.registerCallback(cb, handler)
            controllers[c] = cb
            publishState(c.packageName, c.playbackState, c.metadata)
        }

        val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
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
