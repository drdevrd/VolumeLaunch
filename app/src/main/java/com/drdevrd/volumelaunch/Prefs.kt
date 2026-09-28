package com.drdevrd.volumelaunch

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("vl", Context.MODE_PRIVATE)
    fun pkg(c: Context): String? = sp(c).getString("pkg", null)
    fun setPkg(c: Context, v: String) = sp(c).edit().putString("pkg", v).apply()
    fun holdMs(c: Context): Int = sp(c).getInt("hold", 600)
    fun setHoldMs(c: Context, v: Int) = sp(c).edit().putInt("hold", v).apply()
    fun alsoUnlocked(c: Context): Boolean = sp(c).getBoolean("unlocked", false)
    fun setAlsoUnlocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("unlocked", v).apply()
}
