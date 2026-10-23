package com.tvlyrics.app.domain

import com.tvlyrics.app.codec.Parsers
import com.tvlyrics.app.provider.QQMusicApi
import com.tvlyrics.app.provider.NeteaseApi
import com.tvlyrics.app.provider.AppleMusicApi
import com.tvlyrics.app.codec.QrcDecrypt

// 歌词聚合：获取所有可用歌词版本
import android.util.Log

// 带歌曲信息的歌词源
data class LyricSource(
    val track: Track,
    val source: String,
    val lines: List<Line>
)

object LyricsAggregator {
    // 获取所有可用歌词版本（每个源的前N个结果）
    fun fetchAll(track: Track, limitPerSource: Int = 3): List<LyricSource> {
        val sources = mutableListOf<LyricSource>()
        val keyword = buildString {
            append(track.title)
            track.artists.firstOrNull()?.let { append(" ").append(it) }
        }

        // 0. Apple Music（AMLL）- Spotify ID 精确匹配，优先级最高
        try {
            val record = AppleMusicApi.findBySpotifyId(AppleMusicApi.appContext, track.id)
            if (record != null) {
                Log.d("TVLyrics", "AMLL exact match: ${record.title}")
                val lines = AppleMusicApi.getLyrics(record)
                Log.d("TVLyrics", "AMLL lyric lines: ${lines.size}")
                if (lines.isNotEmpty()) {
                    sources.add(LyricSource(
                        Track(record.id, record.title, record.artists, record.album, 0),
                        "Apple Music", lines
                    ))
                }
            }
        } catch (e: Exception) { Log.e("TVLyrics", "AMLL fail", e) }

        // 1. QQ 音乐 - 三级重试：关键词→纯歌名→延迟2.5秒再试（风控是瞬断，等一下就恢复）
        try {
            var results = QQMusicApi.search(keyword)
            Log.d("TVLyrics", "QQ search results: ${results.size} for '$keyword'")
            if (results.isEmpty()) {
                results = QQMusicApi.search(track.title)
                Log.d("TVLyrics", "QQ retry with title only: ${results.size}")
            }
            if (results.isEmpty()) {
                Thread.sleep(2500) // 风控瞬断：等 2.5s 再试一次
                results = QQMusicApi.search(keyword)
                Log.d("TVLyrics", "QQ retry after delay: ${results.size}")
            }
            var fetched = 0
            for (candidate in results) {
                if (fetched >= limitPerSource) break
                try {
                    Thread.sleep(600) // 防止 -1901 反作弊拦截
                    val (raw, trans, roma) = QQMusicApi.getLyrics(candidate.id)
                    val lines = parseQQ(raw, trans, roma)
                    Log.d("TVLyrics", "QQ lyric ${candidate.title}: ${lines.size} lines")
                    if (lines.isNotEmpty()) {
                        sources.add(LyricSource(candidate, "QQ音乐", lines))
                        fetched++
                    }
                } catch (e: Exception) {
                    Log.e("TVLyrics", "QQ lyric fetch fail for ${candidate.id}", e)
                }
            }
        } catch (e: Exception) { Log.e("TVLyrics", "QQ search fail", e) }

        // 2. 网易云 - "歌名 歌手"搜不到时降级用纯歌名再搜
        try {
            var results = NeteaseApi.search(keyword)
            Log.d("TVLyrics", "Netease search results: ${results.size} for '$keyword'")
            if (results.isEmpty()) {
                results = NeteaseApi.search(track.title)
                Log.d("TVLyrics", "Netease retry with title only: ${results.size}")
            }
            results.take(limitPerSource).forEach { candidate ->
                try {
                    val (lrc, yrc, trans) = NeteaseApi.getLyrics(candidate.id)
                    val lines = if (!yrc.isNullOrBlank()) {
                        val parsed = Parsers.parseWordLyrics(yrc)
                        if (parsed.isEmpty()) {
                            Parsers.parseLrc(lrc).let { Parsers.mergeTranslation(it, trans) }
                        } else {
                            Parsers.mergeTranslation(parsed, trans)
                        }
                    } else {
                        Parsers.parseLrc(lrc).let { Parsers.mergeTranslation(it, trans) }
                    }
                    if (lines.isNotEmpty()) {
                        sources.add(LyricSource(
                            track = candidate,
                            source = "网易云",
                            lines = lines
                        ))
                    }
                } catch (e: Exception) {
                    Log.e("TVLyrics", "Netease lyric fetch fail for ${candidate.id}", e)
                }
            }
        } catch (e: Exception) { Log.e("TVLyrics", "Netease search fail", e) }

        return sources
    }

    // 保留原来的方法兼容
    fun fetch(track: Track): Lyrics? {
        val sources = fetchAll(track, limitPerSource = 1)
        return sources.firstOrNull()?.let { Lyrics(it.lines, "${it.source} · ${it.track.title}") }
    }

    // QQ: 原文可能是明文 LRC、明文 QRC XML、或加密 hex；翻译/罗马音同理，按行起始时间对齐
    private fun parseQQ(raw: String, trans: String?, roma: String? = null): List<Line> {
        return parseQQForSearch(raw, trans, roma)
    }

    // 公开给搜索使用
    fun parseQQForSearch(raw: String, trans: String?, roma: String? = null): List<Line> {
        val text = extractQqText(raw)
        val lines = if (text.contains("(") && Regex("\\[\\d+,-?\\d+\\]").containsMatchIn(text)) {
            Parsers.parseWordLyrics(text)
        } else {
            Parsers.parseLrc(text)
        }
        val transText = trans?.let { extractQqText(it) }
        val romaText = roma?.let { extractQqText(it) }
        var merged = Parsers.mergeTranslation(lines, transText)
        if (romaText != null) merged = Parsers.mergeRomanization(merged, romaText)
        return merged
    }

    // QRC XML 提取 LyricContent，明文直接返回
    private fun extractQqText(raw: String): String {
        return if (raw.contains("LyricContent")) {
            QrcDecrypt.extractContent(raw) ?: raw
        } else raw
    }

    // 粗匹配：标题包含 + 时长差 < 3s（时长 0 视为未知，跳过）
    private fun match(candidate: Track, target: Track): Boolean {
        val titleOk = candidate.title.replace(" ", "").contains(target.title.replace(" ", ""), true) ||
            target.title.replace(" ", "").contains(candidate.title.replace(" ", ""), true)
        val durationOk = candidate.durationMs == 0L || target.durationMs == 0L ||
            kotlin.math.abs(candidate.durationMs - target.durationMs) < 3000
        return titleOk && durationOk
    }
}
