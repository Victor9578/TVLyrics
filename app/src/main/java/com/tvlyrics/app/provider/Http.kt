package com.tvlyrics.app.provider

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    class HttpException(val code: Int, val body: String, val retryAfterMs: Long? = null) : IOException("HTTP $code")

    data class Response(val code: Int, val body: String)

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        request("GET", url, null, null, headers)

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Response =
        request("POST", url, json.toByteArray(Charsets.UTF_8), "application/json", headers)

    fun put(url: String, body: String, headers: Map<String, String> = emptyMap()): Response =
        request("PUT", url, body.toByteArray(Charsets.UTF_8), "application/json", headers)

    fun delete(url: String, headers: Map<String, String> = emptyMap()): Response =
        request("DELETE", url, null, null, headers)

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, encodeForm(form), "application/x-www-form-urlencoded", headers).body

    private fun encodeForm(form: Map<String, String>): ByteArray =
        form.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)

    private fun request(method: String, url: String, body: ByteArray?, contentType: String?, headers: Map<String, String>): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respBody = try { stream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: IOException) { "" }
        val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
        conn.disconnect()
        if (code !in 200..299) throw HttpException(code, respBody, retryAfter)
        return Response(code, respBody)
    }
}
