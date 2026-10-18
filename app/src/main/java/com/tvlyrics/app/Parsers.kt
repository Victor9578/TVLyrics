package com.tvlyrics.app

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
            val words = if (body.contains("\"")) {
                YRC_WORD.findAll(body).mapNotNull { m ->
                    val txt = m.groupValues[1].trim()
                    if (txt.isEmpty()) null
                    else Word(txt, m.groupValues[2].toLong(), m.groupValues[2].toLong() + m.groupValues[3].toLong())
                }.toList()
            } else {
                QRC_WORD.findAll(body).mapNotNull { m ->
                    val txt = m.groupValues[1].trim()
                    if (txt.isEmpty()) null
                    else Word(txt, m.groupValues[2].toLong(), m.groupValues[2].toLong() + m.groupValues[3].toLong())
                }.toList()
            }
            if (words.isEmpty()) return@forEach
            lines.add(Line(lineStart, lineStart + lineDur, words.joinToString("") { it.text }, words))
        }
        return lines.sortedBy { it.startMs }
    }

    // 翻译是 LRC，按行起始时间对齐（容差 300ms）
    fun mergeTranslation(lines: List<Line>, transLrc: String?): List<Line> {
        if (transLrc.isNullOrBlank()) return lines
        val trans = parseLrc(transLrc).filter { it.text.isNotBlank() }
        if (trans.isEmpty() || lines.isEmpty()) return lines
        return lines.map { l ->
            val hit = trans.firstOrNull { kotlin.math.abs(it.startMs - l.startMs) <= 300 }
            if (hit != null) l.copy(translation = hit.text) else l
        }
    }

    private fun fracMs(ms: String): Long = when (ms.length) {
        0 -> 0
        1 -> ms.toLong() * 100
        2 -> ms.toLong() * 10
        else -> ms.take(3).toLong()
    }
}
