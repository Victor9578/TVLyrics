package com.tvlyrics.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tvlyrics", Context.MODE_PRIVATE)

    // 状态化：修改后 Compose 自动重组（歌词对时立即生效）
    private val _lyricOffset = mutableIntStateOf(prefs.getInt("lyricOffset", 0))
    var lyricOffset: Int
        get() = _lyricOffset.intValue
        set(value) {
            _lyricOffset.intValue = value
            prefs.edit().putInt("lyricOffset", value).apply()
        }
}
