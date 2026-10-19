package com.tvlyrics.app.codec

import com.tvlyrics.app.domain.Line

import android.util.Base64
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object QrcDecrypt {
    // QQ 音乐 QRC 的 3DES 密钥（社区公开）
    private val KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    // 直接解 hex 负载（新接口 lyric_download.fcg 返回原始 hex）：hex → QqDesLike → zlib → 文本
    fun decodeHex(hex: String): String? = try {
        val trimmed = hex.trim()
        require(trimmed.length % 2 == 0 && trimmed.length > 16) { "invalid hex" }
        inflate(QqDesLike.decrypt(hexToBytes(trimmed), KEY))
    } catch (e: Exception) {
        null
    }

    // qrc 字段 base64 解码后：明文 XML，或 hex 字符串（hex → 3DES → zlib → XML）
    fun decodeQrc(base64: String): String? = try {
        val decoded = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8).trim()
        when {
            decoded.startsWith("<?xml", ignoreCase = true) || decoded.startsWith("<QrcInfos", ignoreCase = true) -> decoded
            decoded.length > 16 && decoded.length % 2 == 0 && decoded.matches(Regex("^[0-9a-fA-F]+$")) -> {
                inflate(tripleDes(hexToBytes(decoded)))
            }
            else -> null
        }
    } catch (e: Exception) {
        null // 解密失败则回退 LRC
    }

    // 从 QRC XML 中提取 LyricContent 属性并反转义
    fun extractContent(xml: String): String? {
        val m = Regex("LyricContent=\"([\\s\\S]*?)\"").find(xml) ?: return null
        return m.groupValues[1]
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun tripleDes(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "DESede"))
        return cipher.doFinal(data)
    }

    private fun inflate(data: ByteArray): String =
        InflaterInputStream(ByteArrayInputStream(data)).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}
