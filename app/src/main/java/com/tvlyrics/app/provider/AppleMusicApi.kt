package com.tvlyrics.app.provider

import com.tvlyrics.app.domain.Track
import com.tvlyrics.app.domain.Line
import com.tvlyrics.app.domain.Word

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

// Apple Music 歌词源：基于 AMLL TTML DB（开源 Apple Music 逐字歌词库）
// 索引含 spotifyId，可直接精确匹配当前播放曲目
object AppleMusicApi {
    private const val TAG = "TVLyricsAM"
    lateinit var appContext: Context private set

    private val INDEX_MIRRORS = listOf(
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/index.jsonl",
        "https://fastly.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics/index.jsonl",
        "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics/index.jsonl",
    )
    private val RAW_MIRRORS = listOf(
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics",
        "https://fastly.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics",
        "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics",
    )

    private const val INDEX_TTL_MS = 24 * 60 * 60 * 1000L // 1天

    class AmllRecord(val id: String, val title: String, val artists: List<String>, val album: String, val spotifyIds: List<String>)

    private var records: List<AmllRecord>? = null
    private var spotifyIndex: Map<String, AmllRecord>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        Thread {
            try {
                loadIndex(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "index init fail", e)
            }
        }.start()
    }

    @Synchronized
    private fun loadIndex(context: Context): List<AmllRecord>? {
        if (records != null) return records
        val file = File(context.filesDir, "amll-index.jsonl")
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > INDEX_TTL_MS
        if (stale) {
            val content = INDEX_MIRRORS.firstNotNullOfOrNull { url ->
                try {
                    Log.d(TAG, "downloading index from $url")
                    Http.get(url).body.takeIf { it.isNotBlank() }
                } catch (e: Exception) { Log.e(TAG, "index mirror fail: $url", e); null }
            }
            if (content != null) {
                Log.d(TAG, "index downloaded: ${content.length} chars")
                try {
                    file.writeText(content)
                } catch (_: Exception) {}
            } else {
                Log.e(TAG, "all index mirrors failed")
            }
        }
        if (!file.exists()) return null
        val list = mutableListOf<AmllRecord>()
        val spotify = mutableMapOf<String, AmllRecord>()
        file.readLines().forEach { line ->
            try {
                val obj = JSONObject(line)
                val id = obj.optString("id")
                if (id.isEmpty()) return@forEach
                val meta = obj.optJSONArray("metadata") ?: return@forEach
                var title = ""; var album = ""
                val artists = mutableListOf<String>()
                val spotifyIds = mutableListOf<String>()
                for (i in 0 until meta.length()) {
                    val pair = meta.optJSONArray(i) ?: continue
                    val key = pair.optString(0)
                    val values = pair.optJSONArray(1) ?: continue
                    when (key) {
                        "musicName" -> title = values.optString(0)
                        "album" -> if (album.isEmpty()) album = values.optString(0)
                        "artists" -> for (j in 0 until values.length()) artists.add(values.optString(j))
                        "spotifyId" -> for (j in 0 until values.length()) spotifyIds.add(values.optString(j))
                    }
                }
                if (title.isEmpty()) return@forEach
                val rec = AmllRecord(id, title, artists, album, spotifyIds)
                list.add(rec)
                spotifyIds.forEach { spotify[it] = rec }
            } catch (_: Exception) {}
        }
        records = list
        spotifyIndex = spotify
        Log.d(TAG, "AMLL index loaded: ${list.size} records, ${spotify.size} spotify ids")
        return list
    }

    // Spotify ID 精确匹配（最快路径）
    fun findBySpotifyId(context: Context, spotifyId: String): AmllRecord? {
        loadIndex(context) ?: return null
        return spotifyIndex?.get(spotifyId)
    }

    // 文本搜索（手动搜索用）
    fun search(context: Context, keyword: String, limit: Int = 10): List<AmllRecord> {
        val all = loadIndex(context) ?: return emptyList()
        val kw = keyword.lowercase().replace(" ", "")
        if (kw.isEmpty()) return emptyList()
        return all.filter { rec ->
            rec.title.lowercase().replace(" ", "").contains(kw) ||
                rec.artists.any { it.lowercase().replace(" ", "").contains(kw) }
        }.take(limit)
    }

    // 获取并解析 TTML 歌词
    fun getLyrics(record: AmllRecord): List<Line> {
        val raw = RAW_MIRRORS.firstNotNullOfOrNull { base ->
            try {
                Http.get("$base/${record.id}.ttml").body
            } catch (_: Exception) { null }
        } ?: return emptyList()
        return parseTtml(raw)
    }

    // TTML 解析：<p begin end><span begin end>字</span><span role="x-translation">译</span></p>
    private fun parseTtml(raw: String): List<Line> {
        val lines = mutableListOf<Line>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(raw.reader())
            var inP = false
            var lineStart = 0L; var lineEnd = 0L
            val words = mutableListOf<Word>()
            val text = StringBuilder()
            var translation: String? = null

            fun flush() {
                val t = text.toString().trim()
                if (t.isNotEmpty()) {
                    lines.add(Line(
                        startMs = lineStart,
                        endMs = lineEnd,
                        text = t,
                        words = words.toList().ifEmpty { null },
                        translation = translation,
                    ))
                }
                words.clear(); text.clear(); translation = null
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "p" -> {
                            flush()
                            inP = true
                            lineStart = parseTtmlTime(parser.getAttributeValue(null, "begin"))
                            lineEnd = parseTtmlTime(parser.getAttributeValue(null, "end"))
                        }
                        "span" -> if (inP) {
                            val role = parser.getAttributeValue(null, "role")
                            if (role == "x-translation" || role == "translation") {
                                // 翻译：取 span 的文本
                                val depth = 1
                                val sb = StringBuilder()
                                var e2 = parser.next()
                                while (e2 != XmlPullParser.END_DOCUMENT) {
                                    if (e2 == XmlPullParser.TEXT && depth == 1) sb.append(parser.text)
                                    if (e2 == XmlPullParser.START_TAG) { if (depth == 1) break; }
                                    if (e2 == XmlPullParser.END_TAG) break
                                    e2 = parser.next()
                                }
                                translation = sb.toString().trim().ifEmpty { null }
                            } else if (role == "x-roman" || role == "romanization") {
                                // 罗马音：跳过
                                parser.nextText()
                            } else {
                                val begin = parser.getAttributeValue(null, "begin")
                                val end = parser.getAttributeValue(null, "end")
                                val w = parser.nextText().trim()
                                if (w.isNotEmpty() && begin != null && end != null) {
                                    val s = parseTtmlTime(begin)
                                    val e = parseTtmlTime(end)
                                    words.add(Word(w, s, e))
                                    text.append(w)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "p") {
                        flush()
                        inP = false
                    }
                }
                event = parser.next()
            }
            flush()
        } catch (e: Exception) {
            Log.e(TAG, "ttml parse fail", e)
        }
        return lines
    }

    // TTML 时间：00:00.917 / 00:01:02.917 / 秒数
    private fun parseTtmlTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d+))?$").find(value)?.let { m ->
            val frac = m.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            return m.groupValues[1].toLong() * 3_600_000 + m.groupValues[2].toLong() * 60_000 + m.groupValues[3].toLong() * 1_000 + frac
        }
        Regex("^(\\d+):(\\d{1,2})(?:[.,](\\d+))?$").find(value)?.let { m ->
            val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            return m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 + frac
        }
        Regex("^([0-9.]+)(ms|s|m|h)$").find(value)?.let { m ->
            val mult = when (m.groupValues[2]) {
                "ms" -> 1.0; "s" -> 1_000.0; "m" -> 60_000.0; else -> 3_600_000.0
            }
            return (m.groupValues[1].toDouble() * mult).toLong()
        }
        return value.replace(',', '.').toDoubleOrNull()?.times(1000)?.toLong() ?: 0
    }
}
