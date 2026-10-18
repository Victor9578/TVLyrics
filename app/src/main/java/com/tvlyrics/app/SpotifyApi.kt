package com.tvlyrics.app

import org.json.JSONObject

// 播放状态：一次轮询快照 + 本地时钟插值，UI 每帧用 progressMs() 取当前进度
data class PlaybackState(
    val track: Track?,
    val isPlaying: Boolean,
    val progressMsAtSnapshot: Long,
    val wallClockAtSnapshot: Long,
) {
    fun progressMs(now: Long = System.currentTimeMillis()): Long {
        if (!isPlaying) return progressMsAtSnapshot
        return progressMsAtSnapshot + (now - wallClockAtSnapshot)
    }

    companion object {
        val EMPTY = PlaybackState(null, false, 0, System.currentTimeMillis())
    }
}

object SpotifyApi {
    // 轮询间隔：正常 10s；429 时按 Retry-After 退避（上限 120s）
    const val POLL_INTERVAL_MS = 10_000L
    const val MAX_BACKOFF_MS = 120_000L

    fun getPlayback(accessToken: String): PlaybackState {
        val resp = Http.get(
            "https://api.spotify.com/v1/me/player/currently-playing?additional_types=track",
            mapOf("Authorization" to "Bearer $accessToken"),
        )
        if (resp.code == 204 || resp.body.isEmpty()) return PlaybackState.EMPTY // 没在播放
        val obj = JSONObject(resp.body)
        if (!obj.optBoolean("is_playing", false)) {
            val item = obj.optJSONObject("item")
            return PlaybackState(parseTrack(item), false, obj.optLong("progress_ms", 0), System.currentTimeMillis())
        }
        val item = obj.optJSONObject("item") ?: return PlaybackState.EMPTY
        return PlaybackState(
            track = parseTrack(item),
            isPlaying = true,
            progressMsAtSnapshot = obj.optLong("progress_ms", 0),
            wallClockAtSnapshot = System.currentTimeMillis(),
        )
    }

    private fun parseTrack(item: JSONObject?): Track? {
        if (item == null || item.optString("type") != "track") return null
        val artists = item.optJSONArray("artists")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it).optString("name") }
        } ?: emptyList()
        return Track(
            id = item.optString("id"),
            title = item.optString("name"),
            artists = artists,
            album = item.optJSONObject("album")?.optString("name") ?: "",
            durationMs = item.optLong("duration_ms"),
        )
    }
}
