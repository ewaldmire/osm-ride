package com.ewaldmire.osmride.ui.settings

import android.content.Context

/** Small standalone settings, shared between the Settings screen and the workout importer
 * (which needs FTP to convert %FTP-based .mrc/.zwo workouts to absolute watts). */
object SettingsPrefs {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_FTP_WATTS = "ftp_watts"

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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
