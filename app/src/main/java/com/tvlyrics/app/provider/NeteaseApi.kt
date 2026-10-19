package com.tvlyrics.app.provider

import com.tvlyrics.app.codec.Parsers
import com.tvlyrics.app.domain.Track

import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NeteaseApi {
    private const val TAG = "TVLyricsNE"
    private const val EAPI_KEY = "e82ckenh8dichen8"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 9; PCT-AL10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.64 HuaweiBrowser/10.0.3.311 Mobile Safari/537.36",
        "Referer" to "https://music.163.com/",
    )

    // EAPI 搜索（Lyricify SearchNew 同款）
    fun search(keyword: String): List<Track> {
        val data = linkedMapOf(
            "s" to keyword,
            "type" to "1",
            "limit" to "30",
            "offset" to "0",
            "total" to "true",
        )
        val raw = eapiPost("https://interface.music.163.com/eapi/cloudsearch/pc", data)
        Log.d(TAG, "search resp: " + raw.take(200))
        val obj = JSONObject(raw)
        if (obj.optInt("code", -1) != 200) {
            Log.e(TAG, "search code=${obj.optInt("code")}")
            return emptyList()
        }
        val songs = obj.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            out.add(Track(
                id = s.optLong("id").toString(),
                title = s.optString("name"),
                artists = s.optJSONArray("ar")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList(),
                album = s.optJSONObject("al")?.optString("name") ?: "",
                durationMs = s.optLong("dt"),
            ))
        }
        return out
    }

    // EAPI 歌词（Lyricify GetLyricNew 同款）
    fun getLyrics(songId: String): Triple<String, String?, String?> {
        val data = linkedMapOf(
            "id" to songId,
            "cp" to "false",
            "lv" to "0",
            "kv" to "0",
            "tv" to "0",
            "rv" to "0",
            "yv" to "0",
            "ytv" to "0",
            "yrv" to "0",
            "csrf_token" to "",
        )
        val raw = eapiPost("https://interface3.music.163.com/eapi/song/lyric/v1", data)
        Log.d(TAG, "lyric resp: " + raw.take(150))
        val obj = JSONObject(raw)
        // 网易云返回两种格式：顶层 lyric 或嵌套 lrc.lyric
        val lrc = obj.optJSONObject("lrc")?.optString("lyric")
            ?: obj.optString("lyric") // 有时歌词直接在顶层
        val yrc = obj.optJSONObject("yrc")?.optString("lyric")
        val trans = obj.optJSONObject("tlyric")?.optString("lyric")
        return Triple(lrc, yrc, trans)
    }

    // EapiHelper.EApi() 逐行移植 —— 关键：所有 JSON 必须按插入顺序构造
    private fun eapiPost(url: String, data: LinkedHashMap<String, String>): String {
        // 1. 构造 header JSON（按插入顺序）
        val now = System.currentTimeMillis() / 1000
        val requestId = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt().toString().padStart(4, '0')}"
        val headerJson = buildJsonString(linkedMapOf(
            "__csrf" to "",
            "appver" to "8.0.0",
            "buildver" to now.toString(),
            "channel" to "",
            "deviceId" to "",
            "mobilename" to "",
            "resolution" to "1920x1080",
            "os" to "android",
            "osver" to "",
            "requestId" to requestId,
            "versioncode" to "140",
            "MUSIC_U" to "",
        ))

        // 2. 把 header 加入 data（保持 data 原有顺序 + header 在最后）
        val dataWithHeader = LinkedHashMap(data)
        dataWithHeader["header"] = headerJson

        // 3. EAPI 加密
        val urlPath = url
            .replace("https://interface3.music.163.com/e", "/")
            .replace("https://interface.music.163.com/e", "/")
        val text = buildJsonString(dataWithHeader)
        val message = "nobody${urlPath}use${text}md5forencrypt"
        val digest = md5Hex(message)
        val payload = "$urlPath-36cd479b6b5-${text}-36cd479b6b5-$digest"
        val params = aesEcbEncryptHex(payload.toByteArray(Charsets.UTF_8))

        // 4. Cookie
        val cookie = "__csrf=; appver=8.0.0; buildver=$now; channel=; deviceId=; mobilename=; " +
            "resolution=1920x1080; os=android; osver=; requestId=$requestId; versioncode=140; MUSIC_U="

        // 5. 发起请求
        val reqHeaders = headers + ("Cookie" to cookie)
        return Http.postForm(url, mapOf("params" to params), reqHeaders)
    }

    // 按 LinkedHashMap 的插入顺序构造 JSON（避免 JSONObject 自动排序）
    private fun buildJsonString(map: LinkedHashMap<String, String>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        return "{$entries}"
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun aesEcbEncryptHex(data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.US_ASCII), "AES"))
        return cipher.doFinal(data)
            .joinToString("") { "%02X".format(it) }
    }
}
