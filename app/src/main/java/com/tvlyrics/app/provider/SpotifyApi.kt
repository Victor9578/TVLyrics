package com.tvlyrics.app.provider

import com.tvlyrics.app.domain.Track

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
    const val POLL_INTERVAL_MS = 5_000L
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
        val album = item.optJSONObject("album")
        val albumArtUrl = album?.optJSONArray("images")?.let { imgs ->
            if (imgs.length() > 0) imgs.getJSONObject(0).optString("url") else null
        }
        return Track(
            id = item.optString("id"),
            title = item.optString("name"),
            artists = artists,
            album = album?.optString("name") ?: "",
            durationMs = item.optLong("duration_ms"),
            albumArtUrl = albumArtUrl,
        )
    }

    // 收藏歌曲
    fun likeTrack(accessToken: String, trackId: String) {
        Http.put(
            "https://api.spotify.com/v1/me/tracks?ids=$trackId",
            "",
            mapOf("Authorization" to "Bearer $accessToken", "Content-Length" to "0")
        )
    }

    // 取消收藏
    fun unlikeTrack(accessToken: String, trackId: String) {
        Http.delete(
            "https://api.spotify.com/v1/me/tracks?ids=$trackId",
            mapOf("Authorization" to "Bearer $accessToken")
        )
    }

    // 检查是否已收藏（正确接口：/me/tracks/contains 返回 [true/false]）
    fun isLiked(accessToken: String, trackId: String): Boolean {
        val resp = Http.get(
            "https://api.spotify.com/v1/me/tracks/contains?ids=$trackId",
            mapOf("Authorization" to "Bearer $accessToken")
        )
        if (resp.code != 200) return false
        val arr = org.json.JSONArray(resp.body)
        return arr.length() > 0 && arr.optBoolean(0)
    }

    // 切换收藏状态
    fun toggleLike(accessToken: String, trackId: String): Boolean {
        val liked = isLiked(accessToken, trackId)
        if (liked) unlikeTrack(accessToken, trackId) else likeTrack(accessToken, trackId)
        return !liked
    }

    // 暂停播放
    fun pause(accessToken: String) {
        Http.put(
            "https://api.spotify.com/v1/me/player/pause",
            "",
            mapOf("Authorization" to "Bearer $accessToken")
        )
    }

    // 继续播放
    fun resume(accessToken: String) {
        Http.put(
            "https://api.spotify.com/v1/me/player/play",
            "",
            mapOf("Authorization" to "Bearer $accessToken")
        )
    }

    // 获取正在播放的队列（含当前播放 + 待播列表）
    fun getQueue(accessToken: String): Pair<Track?, List<Track>> {
        val resp = Http.get(
            "https://api.spotify.com/v1/me/player/queue",
            mapOf("Authorization" to "Bearer $accessToken")
        )
        if (resp.code != 200) return null to emptyList()
        val obj = JSONObject(resp.body)
        val current = parseTrack(obj.optJSONObject("currently_playing"))
        val items = obj.optJSONArray("queue") ?: return current to emptyList()
        val queue = mutableListOf<Track>()
        for (i in 0 until items.length()) {
            parseTrack(items.optJSONObject(i))?.let { queue.add(it) }
        }
        return current to queue
    }

    // 获取用户播放列表
    fun getUserPlaylists(accessToken: String): List<Pair<String, String>> {
        val resp = Http.get(
            "https://api.spotify.com/v1/me/playlists?limit=20",
            mapOf("Authorization" to "Bearer $accessToken")
        )
        if (resp.code != 200) return emptyList()
        val obj = JSONObject(resp.body)
        val items = obj.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val p = items.getJSONObject(i)
            p.optString("name") to p.optString("id")
        }
    }

    // 获取播放列表的歌曲
    fun getPlaylistTracks(accessToken: String, playlistId: String): List<Track> {
        val resp = Http.get(
            "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=50",
            mapOf("Authorization" to "Bearer $accessToken")
        )
        if (resp.code != 200) return emptyList()
        val obj = JSONObject(resp.body)
        val items = obj.optJSONArray("items") ?: return emptyList()
        val tracks = mutableListOf<Track>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i).optJSONObject("track") ?: continue
            if (item.optString("type") != "track") continue
            val artists = item.optJSONArray("artists")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optString("name") }
            } ?: emptyList()
            val album = item.optJSONObject("album")
            val albumArtUrl = album?.optJSONArray("images")?.let { imgs ->
                if (imgs.length() > 0) imgs.getJSONObject(0).optString("url") else null
            }
            tracks.add(Track(
                id = item.optString("id"),
                title = item.optString("name"),
                artists = artists,
                album = album?.optString("name") ?: "",
                durationMs = item.optLong("duration_ms"),
                albumArtUrl = albumArtUrl,
            ))
        }
        return tracks
    }

    // 播放指定歌曲（通过添加到队列）
    fun playTrack(accessToken: String, trackUri: String) {
        Http.postJson(
            "https://api.spotify.com/v1/me/player/queue?uri=$trackUri",
            "",
            mapOf("Authorization" to "Bearer $accessToken")
        )
    }
}
