package com.drdevrd.volumelaunch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

class MediaKeyService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var firstAt = 0L
    private var lastAt = 0L
    private var count = 0
    private var lastFireAt = 0L
    private var origVolume = -1
    private var origStream = -1
    private var suppress = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != "android.media.VOLUME_CHANGED_ACTION") return
            val stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            val newV = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val oldV = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (stream != AudioManager.STREAM_MUSIC && stream != AudioManager.STREAM_RING) return
            if (suppress) return
            val km = Prefs.keyMode(c)
            val isUp = newV > oldV
            val isDown = newV < oldV
            val allow = when (km) {
                Prefs.KEY_UP -> isUp
                Prefs.KEY_DOWN -> isDown
                else -> isUp || isDown
            }
            if (!allow) return
            onEvent(oldV, newV, stream)
        }
    }

    override fun onBind(p: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startInForeground() {
        val ch = "vl_bg"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(NotificationChannel(ch,
                "Volume Launch background", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, ch)
            .setContentTitle("Volume Launch active")
            .setContentText("Hold Volume key to open your app")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun onEvent(oldV: Int, newV: Int, stream: Int) {
        val now = SystemClock.uptimeMillis()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOff = !pm.isInteractive
        if (!screenOff && !Prefs.alsoUnlocked(this)) return
        if (now - lastFireAt < 2000) return

        if (count == 0 || now - lastAt > 500) {
            firstAt = now
            count = 1
            origVolume = oldV
            origStream = stream
        } else {
            count++
        }
        lastAt = now

        // Push volume back so the NEXT auto-repeat can raise it again and fire another broadcast.
        // Without this, once volume caps at max (or bottom), no more events fire and hold cannot be measured.
        try {
            suppress = true
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(stream, origVolume, 0)
            main.postDelayed({ suppress = false }, 50)
        } catch (_: Exception) { suppress = false }

        val holdMs = Prefs.holdMs(this)
        val elapsed = now - firstAt
        val needed = when {
            holdMs <= 400 -> 2
            holdMs <= 700 -> 3
            holdMs <= 1000 -> 4
            else -> 5
        }
        if (count >= needed && elapsed >= holdMs - 250) {
            lastFireAt = now
            count = 0
            fireLaunch()
        }
    }

    private fun fireLaunch() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        val wake = Intent(this, WakeActivity::class.java)
        wake.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { startActivity(wake) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
