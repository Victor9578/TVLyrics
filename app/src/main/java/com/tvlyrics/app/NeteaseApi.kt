package com.tvlyrics.app

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NeteaseApi {
    // 网易云 weapi 加密（社区公开的固定密钥）
    private val secretKey = "0CoJUm6Qyw8W8jud".toByteArray(Charsets.UTF_8)
    private val presetKey = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)
    private val iv = "0102030405060708".toByteArray(Charsets.UTF_8)

    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0 Mobile Safari/537.36",
        "Cookie" to "os=pc; appver=8.0.0",
    )

    fun search(keyword: String): List<Track> {
        val data = JSONObject()
            .put("s", keyword).put("type", 1).put("offset", 0).put("total", "true").put("limit", 20)
        val resp = Http.postForm("https://music.163.com/weapi/cloudsearch/get/web?csrf_token=", weapi(data))
        val obj = JSONObject(resp)
        val result = obj.optJSONObject("result") ?: return emptyList()
        val songs = result.optJSONArray("songs") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            out.add(Track(
                id = s.getLong("id").toString(),
                title = s.optString("name"),
                artists = s.optJSONArray("ar")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList(),
                album = s.optJSONObject("al")?.optString("name") ?: "",
                durationMs = s.optLong("dt"),
            ))
        }
        return out
    }

    // 返回 (lrc, 逐字yrc, 翻译lrc)
    fun getLyrics(songId: String): Triple<String, String?, String?> {
        val data = JSONObject().put("id", songId.toLong()).put("lv", -1).put("tv", -1).put("yv", -1).put("rv", -1)
        val resp = Http.postForm("https://music.163.com/weapi/song/lyric?csrf_token=", weapi(data))
        val obj = JSONObject(resp)
        val lrc = obj.optJSONObject("lrc")?.optString("lyric") ?: ""
        val yrc = obj.optJSONObject("yrc")?.optString("lyric")
        val trans = obj.optJSONObject("tlyric")?.optString("lyric")
        return Triple(lrc, yrc, trans)
    }

    private fun weapi(data: JSONObject): Map<String, String> {
        val text = data.toString()
        val params = Base64.encodeToString(aes(aes(text.toByteArray(Charsets.UTF_8), "CBC", presetKey, iv), "CBC", secretKey, iv), Base64.NO_WRAP)
        val encSecKey = "010001,00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
        return mapOf("params" to params, "encSecKey" to encSecKey)
    }

    private fun aes(data: ByteArray, mode: String, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/$mode/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
