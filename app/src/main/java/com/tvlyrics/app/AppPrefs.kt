package com.tvlyrics.app

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tvlyrics", Context.MODE_PRIVATE)
    
    var lyricOffset: Int
        get() = prefs.getInt("lyricOffset", 0)
        set(value) = prefs.edit().putInt("lyricOffset", value).apply()
}
