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
    private var prevVolume = -1
    private var restoring = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != "android.media.VOLUME_CHANGED_ACTION") return
            val stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            val newV = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val oldV = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            // Only care about music/ring streams going UP
            if (stream != AudioManager.STREAM_MUSIC && stream != AudioManager.STREAM_RING) return
            if (restoring) return
            val km = Prefs.keyMode(c)
            val isUp = newV > oldV
            val isDown = newV < oldV
            val allow = when (km) {
                Prefs.KEY_UP -> isUp
                Prefs.KEY_DOWN -> isDown
                else -> isUp || isDown
            }
            if (!allow) return
            onEvent(oldV, stream)
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
            .setContentText("Hold Volume Up to open your app")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun onEvent(oldVolume: Int, stream: Int) {
        val now = SystemClock.uptimeMillis()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOff = !pm.isInteractive
        if (!screenOff && !Prefs.alsoUnlocked(this)) return
        if (now - lastFireAt < 2000) return

        // Window based grouping: if the last raise was long ago, start over
        if (count == 0 || now - lastAt > 500) {
            firstAt = now
            count = 1
            prevVolume = oldVolume
        } else {
            count++
        }
        lastAt = now

        val holdMs = Prefs.holdMs(this)
        val elapsed = now - firstAt

        // Android key auto-repeat sends first repeat after ~400ms then ~every 50-80ms.
        // So a real hold produces many events; a single tap produces just one.
        val neededCount = when {
            holdMs <= 400 -> 2
            holdMs <= 700 -> 3
            holdMs <= 1000 -> 5
            else -> 7
        }
        if (count >= neededCount && elapsed >= holdMs - 200) {
            lastFireAt = now
            count = 0
            // Restore volume
            try {
                restoring = true
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(stream, prevVolume, 0)
                main.postDelayed({ restoring = false }, 300)
            } catch (_: Exception) { restoring = false }
            launchTarget()
        }
    }

    private fun launchTarget() {
        val pkg = Prefs.pkg(this) ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "volumelaunch:wake")
            wl.acquire(5000)
        } catch (_: Exception) {}
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(intent) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
