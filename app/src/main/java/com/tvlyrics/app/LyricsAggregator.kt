package com.tvlyrics.app

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
    fun fetchAll(track: Track, limitPerSource: Int = 5): List<LyricSource> {
        val sources = mutableListOf<LyricSource>()
        val keyword = buildString {
            append(track.title)
            track.artists.firstOrNull()?.let { append(" ").append(it) }
        }

        // 1. QQ 音乐 - 取前 limitPerSource 个结果
        try {
            val results = QQMusicApi.search(keyword)
            Log.d("TVLyrics", "QQ search results: ${results.size} for '$keyword'")
            results.take(limitPerSource).forEach { candidate ->
                try {
                    val (raw, trans) = QQMusicApi.getLyrics(candidate.id)
                    val lines = parseQQ(raw, trans)
                    if (lines.isNotEmpty()) {
                        sources.add(LyricSource(
                            track = candidate,
                            source = "QQ音乐",
                            lines = lines
                        ))
                    }
                } catch (e: Exception) {
                    Log.e("TVLyrics", "QQ lyric fetch fail for ${candidate.id}", e)
                }
            }
        } catch (e: Exception) { Log.e("TVLyrics", "QQ search fail", e) }

        // 2. 网易云 - 取前 limitPerSource 个结果
        try {
            val results = NeteaseApi.search(keyword)
            Log.d("TVLyrics", "Netease search results: ${results.size} for '$keyword'")
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

    // QQ: lyric 字段可能是明文 LRC、明文 QRC XML、或加密 hex
    private fun parseQQ(raw: String, trans: String?): List<Line> {
        return parseQQForSearch(raw, trans)
    }

    // 公开给搜索使用
    fun parseQQForSearch(raw: String, trans: String?): List<Line> {
        val text = if (raw.contains("LyricContent")) {
            QrcDecrypt.extractContent(raw) ?: raw
        } else {
            QrcDecrypt.decodeQrc(raw)?.let { QrcDecrypt.extractContent(it) ?: it } ?: raw
        }
        val lines = if (text.contains("(") && Regex("\\[\\d+,-?\\d+\\]").containsMatchIn(text)) {
            Parsers.parseWordLyrics(text)
        } else {
            Parsers.parseLrc(text)
        }
        return Parsers.mergeTranslation(lines, trans)
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
