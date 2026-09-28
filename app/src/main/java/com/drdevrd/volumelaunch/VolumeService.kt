package com.drdevrd.volumelaunch

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var holdFired = false
    private var tracking = false
    private var trackedKey = 0

    private val fire = Runnable {
        holdFired = true
        launchTarget()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val svc = Intent(this, MediaKeyService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun watches(keyCode: Int): Boolean {
        val km = Prefs.keyMode(this)
        val isUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        return when (km) {
            Prefs.KEY_UP -> isUp
            Prefs.KEY_DOWN -> isDown
            else -> isUp || isDown
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!watches(event.keyCode)) return false

        val kg = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val locked = kg.isKeyguardLocked || !pm.isInteractive
        if (locked) return false
        if (!Prefs.alsoUnlocked(this) && !tracking) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    tracking = true
                    trackedKey = event.keyCode
                    holdFired = false
                    handler.removeCallbacks(fire)
                    handler.postDelayed(fire, Prefs.holdMs(this).toLong())
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!tracking || event.keyCode != trackedKey) return false
                tracking = false
                handler.removeCallbacks(fire)
                if (!holdFired) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                        AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                }
                return true
            }
        }
        return false
    }

    private fun launchTarget() {
        val pkg = Prefs.pkg(this) ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
    }
}
