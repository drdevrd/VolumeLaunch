package com.drdevrd.volumelaunch

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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

    private val fire = Runnable {
        holdFired = true
        launchTarget()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val locked = km.isKeyguardLocked || !pm.isInteractive
        if (!locked && !Prefs.alsoUnlocked(this) && !tracking) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    tracking = true
                    holdFired = false
                    handler.removeCallbacks(fire)
                    handler.postDelayed(fire, Prefs.holdMs(this).toLong())
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                handler.removeCallbacks(fire)
                if (!holdFired) {
                    // short press: behave like normal volume up
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                return true
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun launchTarget() {
        val pkg = Prefs.pkg(this) ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "volumelaunch:wake"
            )
            wl.acquire(5000)
        } catch (_: Exception) {}
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
    }
}
