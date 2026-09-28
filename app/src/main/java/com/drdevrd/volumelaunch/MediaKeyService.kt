package com.drdevrd.volumelaunch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

class MediaKeyService : Service() {

    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private var firstRaiseAt = 0L
    private var raiseCount = 0
    private var lastFireAt = 0L
    private var lastEventAt = 0L

    override fun onBind(p: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        startSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startInForeground() {
        val ch = "vl_bg"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(ch) == null) {
                val c = NotificationChannel(ch, "Volume Launch background",
                    NotificationManager.IMPORTANCE_MIN)
                c.setShowBadge(false)
                nm.createNotificationChannel(c)
            }
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, ch)
            .setContentTitle("Volume Launch active")
            .setContentText("Hold Volume Up to open your chosen app")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    private fun startSession() {
        val s = MediaSession(this, "VolumeLaunch")
        val pb = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .setActions(PlaybackState.ACTION_PLAY_PAUSE)
            .build()
        s.setPlaybackState(pb)
        s.isActive = true
        val vp = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction > 0) onRaise()
                currentVolume = 50 // stay in middle so system does not adjust real volume
            }
            override fun onSetVolumeTo(volume: Int) {
                currentVolume = 50
            }
        }
        s.setPlaybackToRemote(vp)
        session = s
    }

    private fun onRaise() {
        val ctx = this
        val now = SystemClock.uptimeMillis()

        // Only react when screen is off or locked (or user opted-in via toggle)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOff = !pm.isInteractive
        val alsoUnlocked = Prefs.alsoUnlocked(ctx)
        if (!screenOff && !alsoUnlocked) return

        // Debounce: ignore rapid follow-up fires
        if (now - lastFireAt < 1500) return

        // Reset window if a long gap since last event
        if (now - lastEventAt > 400 || raiseCount == 0) {
            firstRaiseAt = now
            raiseCount = 1
        } else {
            raiseCount++
        }
        lastEventAt = now

        val holdMs = Prefs.holdMs(ctx)
        val elapsed = now - firstRaiseAt

        // Android key repeat gives ~1 event per 50-70 ms while held.
        // Fire when we have enough repeats within the hold window.
        val needed = when {
            holdMs <= 400 -> 4
            holdMs <= 700 -> 6
            holdMs <= 1000 -> 9
            else -> 12
        }
        if (raiseCount >= needed && elapsed >= holdMs - 150) {
            lastFireAt = now
            raiseCount = 0
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
        try { session?.isActive = false; session?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
