package com.tvlyrics.app.provider

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom

// Spotify OAuth: 自定义 Client ID + PKCE（无 secret，任何人可用）
object SpotifyAuth {
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val REDIRECT_URI = "tvlyrics://callback"
    const val SCOPES = "user-read-currently-playing user-read-playback-state"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    }

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(value) = prefs.edit().putString("client_id", value).apply()

    private var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    private var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()

    private var tokenExpiresAt: Long
        get() = prefs.getLong("token_expires_at", 0)
        set(value) = prefs.edit().putLong("token_expires_at", value).apply()

    val isAuthorized: Boolean get() = refreshToken != null

    fun loginUrl(): String {
        val verifier = pkceVerifier()
        prefs.edit().putString("pkce_verifier", verifier).apply()
        return "$AUTH_URL?client_id=${enc(clientId)}&response_type=code" +
            "&redirect_uri=${enc(REDIRECT_URI)}&scope=${enc(SCOPES)}" +
            "&code_challenge_method=S256&code_challenge=${enc(pkceChallenge(verifier))}"
    }

    // 处理 tvlyrics://callback?code=...
    fun handleRedirect(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code") ?: return false
        val verifier = prefs.getString("pkce_verifier", "") ?: ""
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId,
            "code_verifier" to verifier,
        )
        val resp = Http.postForm(TOKEN_URL, form)
        return storeTokens(JSONObject(resp))
    }

    // 返回有效 access token，必要时刷新；刷新失败返回 null（需重新登录）
    fun accessToken(): String? {
        val token = accessToken ?: return refresh()
        if (System.currentTimeMillis() > tokenExpiresAt - 30_000) return refresh()
        return token
    }

    private fun refresh(): String? {
        val rt = refreshToken ?: return null
        val form = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to rt,
            "client_id" to clientId,
        )
        return try {
            val resp = Http.postForm(TOKEN_URL, form)
            storeTokens(JSONObject(resp))
            accessToken
        } catch (e: Exception) {
            null
        }
    }

    private fun storeTokens(obj: JSONObject): Boolean {
        val at = obj.optString("access_token")
        if (at.isEmpty()) return false
        accessToken = at
        tokenExpiresAt = System.currentTimeMillis() + obj.optInt("expires_in", 3600) * 1000L
        obj.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { refreshToken = it }
        return true
    }

    fun logout() = prefs.edit().clear().apply()

    private fun pkceVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
