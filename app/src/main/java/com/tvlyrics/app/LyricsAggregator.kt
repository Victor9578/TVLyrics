package com.tvlyrics.app

// 歌词聚合：QQ 优先（逐字 QRC + 翻译），失败降级网易云（YRC + 翻译），最后 LRC
import android.util.Log

object LyricsAggregator {
    fun fetch(track: Track): Lyrics? {
        val keyword = buildString {
            append(track.title)
            track.artists.firstOrNull()?.let { append(" ").append(it) }
        }

        // 1. QQ 音乐
        try {
            val results = QQMusicApi.search(keyword)
            Log.d("TVLyrics", "QQ search results: ${results.size} for '$keyword'")
            val best = results.firstOrNull { match(it, track) } ?: results.firstOrNull()
            if (best != null) {
                val (raw, trans) = QQMusicApi.getLyrics(best.id)
                Log.d("TVLyrics", "QQ lyrics len=${raw.length}")
                val lines = parseQQ(raw, trans)
                if (lines.isNotEmpty()) return Lyrics(lines, "QQ 音乐")
            }
        } catch (e: Exception) { Log.e("TVLyrics", "QQ fail", e) }

        // 2. 网易云
        try {
            val results = NeteaseApi.search(keyword)
            Log.d("TVLyrics", "Netease search results: ${results.size} for '$keyword'")
            val best = results.firstOrNull { match(it, track) } ?: results.firstOrNull()
            if (best != null) {
                val (lrc, yrc, trans) = NeteaseApi.getLyrics(best.id)
                Log.d("TVLyrics", "Netease lyrics lrcLen=${lrc.length} yrcLen=${yrc?.length}")
                val lines = if (!yrc.isNullOrBlank()) {
                    Parsers.mergeTranslation(Parsers.parseWordLyrics(yrc), trans)
                } else {
                    Parsers.parseLrc(lrc).let { Parsers.mergeTranslation(it, trans) }
                }
                if (lines.isNotEmpty()) return Lyrics(lines, "网易云音乐")
            }
        } catch (e: Exception) { Log.e("TVLyrics", "Netease fail", e) }

        return null
    }

    // QQ: lyric 字段可能是明文 LRC、明文 QRC XML、或加密 hex
    private fun parseQQ(raw: String, trans: String?): List<Line> {
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
