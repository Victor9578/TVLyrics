package com.tvlyrics.app.provider

import com.tvlyrics.app.codec.Parsers
import com.tvlyrics.app.codec.QrcDecrypt
import com.tvlyrics.app.codec.QqDesLike
import com.tvlyrics.app.domain.Track
import com.tvlyrics.app.domain.Line
import com.tvlyrics.app.domain.Word
import com.tvlyrics.app.domain.Lyrics

import android.util.Base64
import android.util.Log
import org.json.JSONObject

object QQMusicApi {
    private const val SEARCH = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    // 参考项目接口：返回 XML 含 content/contentts/contentroma（原文/翻译/罗马音）
    private const val LYRIC = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"

    // 对齐稳定源的完整请求头（Chrome UA + c.y.qq.com Referer + netease cookie）
    private val headers = mapOf(
        "Referer" to "https://c.y.qq.com/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/63.0.3239.132 Safari/537.36",
        "Cookie" to "os=pc;osver=Microsoft-Windows-10-Professional-build-16299.125-64bit;appver=2.0.3.131777;channel=netease;__remember_me=true",
        "Accept" to "*/*",
        "Accept-Language" to "en-GB,en;q=0.9",
    )

    // 返回 Track 列表（id 为数字 musicid，用于歌词接口）
    fun search(keyword: String): List<Track> {
        val param = JSONObject()
            .put("num_per_page", "20").put("page_num", "1")
            .put("query", keyword).put("search_type", 0)
        val req = JSONObject()
            .put("method", "DoSearchForQQMusicDesktop")
            .put("module", "music.search.SearchCgiService")
            .put("param", param)
        // body 与稳定源 curl 完全一致（无 comm 字段——旧客户端签名疑似触发风控）
        val body = JSONObject().put("req_1", req).toString()
        val resp = Http.postJson(SEARCH, body, headers)
        val list = JSONObject(resp.body).optJSONObject("req_1")?.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val mid = s.optString("mid")
            if (mid.isEmpty()) continue
            out.add(Track(
                id = s.optLong("id").toString(), // 数字 musicid，歌词接口用
                title = s.optString("name"),
                artists = s.optJSONArray("singer")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList(),
                album = s.optJSONObject("album")?.optString("name") ?: "",
                durationMs = s.optLong("interval") * 1000,
            ))
        }
        return out
    }

    // 返回 (原文, 翻译, 罗马音)，均可能为 QRC hex 或明文
    fun getLyrics(musicId: String): Triple<String, String?, String?> {
        // POST form（对齐稳定源：参数在 body 不在 URL）
        val resp = Http.postForm(LYRIC, mapOf(
            "miniversion" to "82",
            "version" to "15",
            "lrctype" to "4",
            "musicid" to musicId,
        ), headers)
        Log.d("TVLyricsQQ", "lyric resp len=${resp.length}")
        val values = extractLyricValues(resp)
        val original = decodeContent(values["content"]) ?: return Triple("", null, null)
        val translation = values["contentts"]?.takeIf { it.isNotBlank() }?.let { decodeContent(it) }
        val romanization = values["contentroma"]?.takeIf { it.isNotBlank() }?.let { decodeContent(it) }
        return Triple(original, translation, romanization)
    }

    // 从 XML 提取 content/contentts/contentroma（CDATA 兼容）
    private fun extractLyricValues(xml: String): Map<String, String> =
        Regex("<(content|contentts|contentroma)\\b[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to m.groupValues[2].trim()
                    .removePrefix("<![CDATA[").removeSuffix("]]>").trim()
            }

    // hex 加密的 QRC 解密，明文直接返回
    private fun decodeContent(content: String?): String? {
        val raw = content?.trim() ?: return null
        if (raw.isEmpty()) return null
        return if (raw.length % 2 == 0 && raw.matches(Regex("^[0-9a-fA-F]+$"))) {
            try { QrcDecrypt.decodeHex(raw) } catch (e: Exception) { Log.e("TVLyricsQQ", "decrypt fail", e); null }
        } else raw
    }
}
