package com.subwave.radio.data

import android.content.Context

private const val PREFS_NAME = "icecast_player_prefs"
private const val KEY_LAST_SERVER = "last_successful_server"

/** Persists the raw (un-normalized) address the user typed, but only on a successful connect. */
object ServerPrefs {

    fun saveLastServer(context: Context, rawInput: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SERVER, rawInput)
            .apply()
    }

    fun getLastServer(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SERVER, null)
    }
}
