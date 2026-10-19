package com.tvlyrics.app

import com.tvlyrics.app.codec.QqDesLike

import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

class RefProjectAsIsTest {
    // 晴天（musicid 97773）真实密文
    private val realHex by lazy {
        java.io.File(System.getProperty("user.dir") + "/../tmp/qq_hex.txt").readText().replace("\\s".toRegex(), "")
    }
    private val key = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }

    @Test
    fun `参考项目原版 QqDesLike 解密真实密文`() {
        val data = hexToBytes(realHex)
        val decrypted = QqDesLike.decrypt(data, key)
        println("decrypted head: " + decrypted.take(8).joinToString("") { "%02x".format(it) })
        // 参考项目 QrcCodec.decryptHex 的后续流程：zlib inflate
        val text = try {
            InflaterInputStream(ByteArrayInputStream(decrypted)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            "INFLATE FAILED: ${e.message}"
        }
        println("inflated: " + text.take(100))
    }
}
