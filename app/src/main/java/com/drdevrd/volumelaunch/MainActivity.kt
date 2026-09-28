package com.drdevrd.volumelaunch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var appLabel: TextView
    private lateinit var holdLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun tv(t: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }

        root.addView(tv("VOLUME UP HOLD LAUNCHER", 22f, true))
        status = tv("")
        root.addView(status)

        root.addView(btn("1. ENABLE ACCESSIBILITY SERVICE") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(btn("2. ALLOW NOTIFICATION (NEEDED)") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })

        root.addView(tv("WHICH KEY", 14f, true))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rUp = RadioButton(this).apply { text = "VOL UP"; id = 101 }
        val rDown = RadioButton(this).apply { text = "VOL DOWN"; id = 102 }
        val rEither = RadioButton(this).apply { text = "EITHER"; id = 103 }
        rg.addView(rUp); rg.addView(rDown); rg.addView(rEither)
        when (Prefs.keyMode(this)) {
            Prefs.KEY_UP -> rUp.isChecked = true
            Prefs.KEY_DOWN -> rDown.isChecked = true
            else -> rEither.isChecked = true
        }
        rg.setOnCheckedChangeListener { _, id ->
            Prefs.setKeyMode(this, when (id) {
                101 -> Prefs.KEY_UP
                102 -> Prefs.KEY_DOWN
                else -> Prefs.KEY_EITHER
            })
        }
        root.addView(rg)

        appLabel = tv("")
        root.addView(appLabel)
        root.addView(btn("3. CHOOSE APP TO OPEN") { pickApp() })

        holdLabel = tv("")
        root.addView(holdLabel)
        val seek = SeekBar(this).apply {
            max = 1200
            progress = Prefs.holdMs(this@MainActivity) - 300
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    Prefs.setHoldMs(this@MainActivity, p + 300); refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seek)

        root.addView(CheckBox(this).apply {
            text = "ALSO WORK WHEN PHONE IS UNLOCKED"
            isChecked = Prefs.alsoUnlocked(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAlsoUnlocked(this@MainActivity, c) }
        })

        root.addView(btn("4. BATTERY: UNRESTRICTED") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        })

        root.addView(btn("5. START / RESTART BACKGROUND SERVICE") { startBg() })

        root.addView(tv("HOW LOCKED-SCREEN DETECTION WORKS: THE APP KEEPS A LOW-PRIORITY NOTIFICATION AND A MEDIA SESSION RUNNING. WHEN YOU HOLD VOLUME UP, ANDROID SENDS REPEAT KEYS TO IT. IF THE ONEPLUS BATTERY SETTING IS RESTRICTED, ANDROID KILLS IT.", 13f))
        root.addView(tv("SETUP CHECKLIST FOR ONEPLUS 13S:\n• BATTERY = UNRESTRICTED\n• AUTO-LAUNCH = ON (IN BATTERY SETTINGS)\n• LOCK SCREEN NOTIFICATIONS FROM THIS APP = ALLOW", 13f))

        setContentView(ScrollView(this).apply { addView(root) })

        startBg()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun startBg() {
        val svc = Intent(this, MediaKeyService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            Toast.makeText(this, "Background service started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun serviceEnabled(): Boolean {
        val cn = ComponentName(this, VolumeService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val sp = TextUtils.SimpleStringSplitter(':')
        sp.setString(enabled)
        while (sp.hasNext()) if (sp.next().equals(cn, true)) return true
        return false
    }

    private fun refresh() {
        status.text = if (serviceEnabled()) "ACCESSIBILITY: ON" else "ACCESSIBILITY: OFF"
        val pkg = Prefs.pkg(this)
        val name = pkg?.let {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
            catch (e: Exception) { it }
        } ?: "NONE CHOSEN"
        appLabel.text = "APP: $name"
        holdLabel.text = "HOLD TIME: ${Prefs.holdMs(this)} MS"
    }

    private fun pickApp() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("CHOOSE APP")
            .setItems(apps.map { it.first }.toTypedArray()) { _, w ->
                Prefs.setPkg(this, apps[w].second); refresh()
            }.show()
    }
}
