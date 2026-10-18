package com.tvlyrics.app

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NeteaseApi {
    private const val TAG = "TVLyricsNE"

    // 对照 Lyricify-Lyrics-Helper BaseApi.cs 的通用头
    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/63.0.3239.132 Safari/537.36",
        "Cookie" to "os=pc;osver=Microsoft-Windows-10-Professional-build-16299.125-64bit;appver=2.0.3.131777;channel=netease;__remember_me=true",
    )

    // 旧版明文搜索接口（Helper Api.Search 同款，GET 无需加密）
    fun search(keyword: String): List<Track> {
        val url = "https://music.163.com/api/search/get/web?csrf_token=hlpretag=&hlposttag=" +
            "&s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&total=true&limit=20"
        val resp = Http.get(url, headers)
        Log.d(TAG, "search resp: " + resp.body.take(200))
        val obj = JSONObject(resp.body)
        val songs = obj.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            out.add(Track(
                id = s.optLong("id").toString(),
                title = s.optString("name"),
                artists = s.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList(),
                album = s.optJSONObject("album")?.optString("name") ?: "",
                durationMs = s.optLong("duration"),
            ))
        }
        return out
    }

    // EAPI 歌词接口（Helper GetLyricNew 同款，含 YRC 逐字 + 翻译）
    fun getLyrics(songId: String): Triple<String, String?, String?> {
        val data = linkedMapOf(
            "id" to songId,
            "cp" to "false",
            "lv" to "0", "kv" to "0", "tv" to "0", "rv" to "0",
            "yv" to "0", "ytv" to "0", "yrv" to "0",
            "csrf_token" to "",
        )
        val resp = eapiPost("https://interface3.music.163.com/eapi/song/lyric/v1", data)
        Log.d(TAG, "lyric resp: " + resp.take(150))
        val obj = JSONObject(resp)
        val lrc = obj.optJSONObject("lrc")?.optString("lyric") ?: ""
        val yrc = obj.optJSONObject("yrc")?.optString("lyric")
        val trans = obj.optJSONObject("tlyric")?.optString("lyric")
        return Triple(lrc, yrc, trans)
    }

    // EAPI 加密，对照 Helper EapiHelper.cs 逐行移植
    private fun eapiPost(url: String, data: Map<String, String>): String {
        // EApi() 内的 url 归一化: https://interface3.music.163.com/e... -> /api/...
        val apiPath = url
            .replace("https://interface3.music.163.com/e", "/")
            .replace("https://interface.music.163.com/e", "/")

        val now = System.currentTimeMillis() / 1000
        // header dict 顺序必须与 C# 一致（digest 依赖序列化结果）
        val header = buildString {
            append("{")
            append("\"__csrf\":\"\",")
            append("\"appver\":\"8.0.0\",")
            append("\"buildver\":\"$now\",")
            append("\"channel\":\"\",")
            append("\"deviceId\":\"\",")
            append("\"mobilename\":\"\",")
            append("\"resolution\":\"1920x1080\",")
            append("\"os\":\"android\",")
            append("\"osver\":\"\",")
            append("\"requestId\":\"${now * 1000}_0001\",")
            append("\"versioncode\":\"140\",")
            append("\"MUSIC_U\":\"\"")
            append("}")
        }

        // 手动按插入序拼 JSON（org.json.JSONObject 不保序，会导致 digest 不匹配）
        val text = buildString {
            append("{")
            data.entries.forEach { (k, v) -> append("\"$k\":\"$v\",") }
            append("\"header\":\"").append(header.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
            append("}")
        }

        val digest = md5Hex("nobody${apiPath}use${text}md5forencrypt")
        val payload = "${apiPath}-36cd479b6b5-${text}-36cd479b6b5-${digest}"
        val params = aesEcbHex(payload)

        val cookie = "__csrf=; appver=8.0.0; buildver=$now; channel=; deviceId=; " +
            "mobilename=; resolution=1920x1080; os=android; osver=; " +
            "requestId=${now * 1000}_0001; versioncode=140; MUSIC_U="

        val eapiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 9; PCT-AL10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.64 HuaweiBrowser/10.0.3.311 Mobile Safari/537.36",
            "Referer" to "https://music.163.com/",
            "Cookie" to cookie,
        )
        return Http.postForm(url, mapOf("params" to params), eapiHeaders)
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun aesEcbHex(data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.US_ASCII), "AES"))
        return cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }
}
