package com.example.mediastatebridge

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal setup screen. Stores MQTT broker config in SharedPreferences and
 * provides a shortcut to the Notification Access settings page (which is the
 * permission that lets MediaListenerService call MediaSessionManager).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        fun add(v: View) = root.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        add(TextView(this).apply {
            text = "Media State Bridge"
            textSize = 24f
        })

        val brokerEt = EditText(this).apply {
            hint = "MQTT broker host (e.g. 192.168.1.10)"
            setText(prefs.getString("host", ""))
        }
        val portEt = EditText(this).apply {
            hint = "Port (1883)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("port", "1883"))
        }
        val userEt = EditText(this).apply {
            hint = "Username (optional)"
            setText(prefs.getString("user", ""))
        }
        val passEt = EditText(this).apply {
            hint = "Password (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", ""))
        }
        val deviceEt = EditText(this).apply {
            hint = "Device id (e.g. cinema_streamer)"
            setText(
                prefs.getString(
                    "device",
                    android.os.Build.MODEL.replace(' ', '_').lowercase()
                )
            )
        }
        add(brokerEt); add(portEt); add(userEt); add(passEt); add(deviceEt)

        add(Button(this).apply {
            text = "Save & restart bridge"
            setOnClickListener {
                prefs.edit()
                    .putString("host", brokerEt.text.toString().trim())
                    .putString("port", portEt.text.toString().trim())
                    .putString("user", userEt.text.toString())
                    .putString("pass", passEt.text.toString())
                    .putString("device", deviceEt.text.toString().trim())
                    .apply()
                MediaListenerService.requestRestart(this@MainActivity)
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })

        add(Button(this).apply {
            text = "Check Notification Access settings"
            setOnClickListener {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        })

        add(TextView(this).apply {
            text = "On Android 14+, the toggle here may be greyed out.\n" +
                "If so, grant access via ADB instead:\n" +
                "adb shell cmd notification allow_listener " +
                "com.example.mediastatebridge/" +
                "com.example.mediastatebridge.MediaListenerService"
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
