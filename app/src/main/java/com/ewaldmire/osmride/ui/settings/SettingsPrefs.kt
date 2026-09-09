package com.ewaldmire.osmride.ui.settings

import android.content.Context

/** Small standalone settings, shared between the Settings/Profile/Body Metrics screens and the
 * workout importer (which needs FTP to convert %FTP-based .mrc/.zwo workouts to absolute watts).
 * Neck/height are one-time-ish profile inputs (unlike weight/waist, not something tracked as a
 * trend over time) needed only for the Navy body-fat estimate. */
object SettingsPrefs {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_FTP_WATTS = "ftp_watts"
    private const val KEY_NECK_CM = "neck_cm"
    private const val KEY_HEIGHT_CM = "height_cm"

    fun getFtpWatts(context: Context): Int? {
        val value = prefs(context).getInt(KEY_FTP_WATTS, -1)
        return if (value > 0) value else null
    }

    fun setFtpWatts(context: Context, watts: Int?) {
        val editor = prefs(context).edit()
        if (watts == null || watts <= 0) {
            editor.remove(KEY_FTP_WATTS)
        } else {
            editor.putInt(KEY_FTP_WATTS, watts)
        }
        editor.apply()
    }

    fun getNeckCm(context: Context): Double? {
        val value = prefs(context).getFloat(KEY_NECK_CM, -1f)
        return if (value > 0f) value.toDouble() else null
    }

    fun setNeckCm(context: Context, cm: Double?) {
        setFloatOrRemove(context, KEY_NECK_CM, cm)
    }

    fun getHeightCm(context: Context): Double? {
        val value = prefs(context).getFloat(KEY_HEIGHT_CM, -1f)
        return if (value > 0f) value.toDouble() else null
    }

    fun setHeightCm(context: Context, cm: Double?) {
        setFloatOrRemove(context, KEY_HEIGHT_CM, cm)
    }

    private fun setFloatOrRemove(context: Context, key: String, value: Double?) {
        val editor = prefs(context).edit()
        if (value == null || value <= 0.0) {
            editor.remove(key)
        } else {
            editor.putFloat(key, value.toFloat())
        }
        editor.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
