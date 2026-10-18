package com.tvlyrics.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test
    fun `lrc 基本解析与 offset`() {
        val lrc = "[offset:500]\n[00:01.50]第一句\n[00:05.00]第二句\n[00:10]第三句"
        val lines = Parsers.parseLrc(lrc)
        assertEquals(3, lines.size)
        assertEquals(2000, lines[0].startMs) // 1500 + 500
        assertEquals("第三句", lines[2].text)
        assertEquals(10_000 + 500, lines[2].startMs)
    }

    @Test
    fun `qrc 逐字解析`() {
        val qrc = "[1234,2000]你好(1234,500)世界(1734,1000)"
        val lines = Parsers.parseWordLyrics(qrc)
        assertEquals(1, lines.size)
        val words = lines[0].words!!
        assertEquals(2, words.size)
        assertEquals("你好", words[0].text)
        assertEquals(1234, words[0].startMs)
        assertEquals(1734, words[0].endMs)
        assertEquals(2734, words[0].endMs + 0) // 1734+1000
        assertEquals(3234, lines[0].endMs)
    }

    @Test
    fun `yrc 逐字解析并跳过信息行`() {
        val yrc = "{\"t\":0,\"c\":[{\"tx\":\"作词:某人\"}]}\n[1000,2000](\"你\",1000,500)(\"好\",1500,500)\n{\"t\":9000,\"c\":[{\"tx\":\"纯音乐\"}]}"
        val lines = Parsers.parseWordLyrics(yrc)
        assertEquals(1, lines.size)
        assertEquals("你好", lines[0].text)
        assertEquals(2, lines[0].words!!.size)
    }

    @Test
    fun `翻译按时间对齐`() {
        val lines = listOf(
            Line(1000, 3000, "hello", null),
            Line(5000, 8000, "world", null),
        )
        val trans = "[00:01.00]你好\n[00:05.00]世界"
        val merged = Parsers.mergeTranslation(lines, trans)
        assertEquals("你好", merged[0].translation)
        assertEquals("世界", merged[1].translation)
    }

    @Test
    fun `qrc xml 提取与反转义`() {
        val xml = "<QrcInfos><QrcHeadInfo TitleInfo=\"\"/>\n<LyricInfo LyricCount=\"1\">\n" +
            "<Lyric_1 LyricType=\"1\" LyricContent=\"[0,2000]&lt;你好&gt;(0,500)世界(500,1000)\"/>\n" +
            "</LyricInfo></QrcInfos>"
        val content = QrcDecrypt.extractContent(xml)
        assertNotNull(content)
        assertTrue(content!!.startsWith("[0,2000]<你好>"))
        val lines = Parsers.parseWordLyrics(content)
        assertEquals(1, lines.size)
        assertEquals(2, lines[0].words!!.size)
    }
}
