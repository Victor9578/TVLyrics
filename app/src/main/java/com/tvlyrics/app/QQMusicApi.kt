package com.tvlyrics.app

import android.util.Base64
import org.json.JSONObject

object QQMusicApi {
    private const val SEARCH = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val LYRIC = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"

    private val headers = mapOf(
        "Referer" to "https://c.y.qq.com/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
    )

    // 返回 (songMid, songId, title, artists, album, durationMs)
    fun search(keyword: String): List<Track> {
        val param = JSONObject()
            .put("num_per_page", 20).put("page_num", 1)
            .put("query", keyword).put("search_type", 0)
        val req = JSONObject()
            .put("method", "DoSearchForQQMusicDesktop")
            .put("module", "music.search.SearchCgiService")
            .put("param", param)
        val body = JSONObject().put("req_1", req).toString()
        val resp = Http.postJson(SEARCH, body, headers)
        val list = JSONObject(resp.body).getJSONObject("req_1").getJSONObject("data").getJSONObject("body")
            .getJSONObject("song").optJSONArray("data")?.getJSONObject(0)?.optJSONArray("list") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i)
            out.add(Track(
                id = s.getString("songmid"),
                title = s.optString("name"),
                artists = s.optJSONArray("singer")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList(),
                album = s.optJSONObject("album")?.optString("name") ?: "",
                durationMs = s.optLong("interval") * 1000,
            ))
        }
        return out
    }

    // 返回 (qrcOrLrc, 翻译LRC)
    fun getLyrics(songMid: String): Pair<String, String?> {
        val url = "$LYRIC?g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8" +
            "&notice=0&platform=yqq&needNewCode=0&songmid=$songMid"
        val resp = Http.get(url, headers)
        val json = resp.body.trim().removePrefix("MusicJsonCallback_lrc(").removeSuffix(")")
        val obj = JSONObject(json)
        val lyric = obj.optString("lyric").ifEmpty { return "" to null }
        val decoded = String(Base64.decode(lyric, Base64.DEFAULT), Charsets.UTF_8)
        val trans = obj.optString("trans").takeIf { it.isNotBlank() }?.let {
            String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
        }
        return decoded to trans
    }
}
