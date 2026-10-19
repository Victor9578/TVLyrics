package com.tvlyrics.app.codec

import com.tvlyrics.app.domain.Line
import com.tvlyrics.app.domain.Word

object Parsers {
    private val LRC_TIME = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val LRC_OFFSET = Regex("^\\[offset:\\s*(-?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    // 行头: [开始,时长]；词有两种格式：QRC 是 词(开始,时长)，YRC 是 ("词",开始,时长)
    private val LINE_HEAD = Regex("^\\[(\\d+),(\\d+)\\]")
    private val YRC_WORD = Regex("\\(\\\"([^\"]*)\\\",(\\d+),(\\d+)\\)")
    private val QRC_WORD = Regex("([^(]*?)\\((\\d+),(\\d+)\\)")

    fun parseLrc(text: String): List<Line> {
        val lines = mutableListOf<Line>()
        var offset = 0L
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            LRC_OFFSET.find(line)?.let {
                offset = it.groupValues[1].toLong()
                return@forEach
            }
            val times = LRC_TIME.findAll(line).toList()
            if (times.isEmpty()) return@forEach // [ti:] 等元数据行
            val content = line.substringAfterLast("]").trim()
            if (content.isEmpty()) return@forEach
            times.forEach { m ->
                val (min, sec, ms) = m.destructured
                val start = min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs(ms)
                lines.add(Line(start + offset, 0, content, null))
            }
        }
        return lines.sortedBy { it.startMs }
    }

    // 同时解析 QRC（词在括号外）和 YRC（词在引号内），跳过 {…} 信息行
    fun parseWordLyrics(text: String): List<Line> {
        val lines = mutableListOf<Line>()
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("{")) return@forEach
            val head = LINE_HEAD.find(line) ?: return@forEach
            val lineStart = head.groupValues[1].toLong()
            val lineDur = head.groupValues[2].toLong()
            val body = line.substring(head.value.length)
            // QRC 中空格是独立带时间词（如 Counting(0,13) (13,13)Stars），仅过滤空串
            val words = if (body.contains("\"")) {
                YRC_WORD.findAll(body).mapNotNull { m ->
                    if (m.groupValues[1].isEmpty()) null
                    else Word(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[2].toLong() + m.groupValues[3].toLong())
                }.toList()
            } else {
                QRC_WORD.findAll(body).mapNotNull { m ->
                    if (m.groupValues[1].isEmpty()) null
                    else Word(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[2].toLong() + m.groupValues[3].toLong())
                }.toList()
            }
            if (words.isEmpty()) return@forEach
            lines.add(Line(lineStart, lineStart + lineDur, words.joinToString("") { it.text }.trim(), words))
        }
        return lines.sortedBy { it.startMs }
    }

    // 翻译可能是 LRC 或 QRC 格式，自动识别后按行起始时间对齐（容差 300ms）
    fun mergeTranslation(lines: List<Line>, transLrc: String?): List<Line> {
        if (transLrc.isNullOrBlank()) return lines
        val trans = parseSecondary(transLrc).filter { it.text.isNotBlank() }
        if (trans.isEmpty() || lines.isEmpty()) return lines
        return alignSecondary(lines, trans) { l, t -> l.copy(translation = t.text) }
    }

    // 罗马音对齐
    fun mergeRomanization(lines: List<Line>, romaLrc: String?): List<Line> {
        if (romaLrc.isNullOrBlank()) return lines
        val roma = parseSecondary(romaLrc).filter { it.text.isNotBlank() }
        if (roma.isEmpty() || lines.isEmpty()) return lines
        return alignSecondary(lines, roma) { l, r -> l.copy(romanization = r.text) }
    }

    // 副轨道解析：QRC（[start,dur]词(...)）或 LRC（[mm:ss.xx]）
    private fun parseSecondary(text: String): List<Line> {
        return if (Regex("\\[\\d+,-?\\d+\\]").containsMatchIn(text)) {
            parseWordLyrics(text)
        } else {
            parseLrc(text)
        }
    }

    // 按时间戳对齐副轨道（参考 SecondaryLyricsAligner）
    private inline fun alignSecondary(
        originals: List<Line>,
        secondary: List<Line>,
        assign: (Line, Line) -> Line,
    ): List<Line> {
        val tolerance = 300L
        val timed = secondary.sortedBy { it.startMs }
        var cursor = 0
        return originals.map { original ->
            while (cursor < timed.size && timed[cursor].startMs < original.startMs - tolerance) cursor++
            var bestIndex = -1
            var bestDistance = Long.MAX_VALUE
            var index = cursor
            while (index < timed.size) {
                if (timed[index].startMs > original.startMs + tolerance) break
                val distance = kotlin.math.abs(timed[index].startMs - original.startMs)
                if (distance < bestDistance) { bestDistance = distance; bestIndex = index }
                index++
            }
            if (bestIndex < 0) original else {
                val hit = timed[bestIndex].also { cursor = bestIndex + 1 }
                assign(original, hit)
            }
        }
    }

    private fun fracMs(ms: String): Long = when (ms.length) {
        0 -> 0
        1 -> ms.toLong() * 100
        2 -> ms.toLong() * 10
        else -> ms.take(3).toLong()
    }
}
