package com.asterion.video.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * YouTube Data API v3 OAuth 2.0 인증
 *
 * youtube_credentials.json 형식:
 * {
 *   "client_id": "...apps.googleusercontent.com",
 *   "client_secret": "...",
 *   "refresh_token": "..."
 * }
 *
 * 설정 방법:
 * 1. Google Cloud Console → OAuth 2.0 클라이언트 ID (웹 애플리케이션) 생성
 * 2. https://developers.google.com/oauthplayground 에서 refresh_token 획득
 *    - YouTube Data API v3 → https://www.googleapis.com/auth/youtube.upload 선택
 * 3. 위 JSON을 GitHub Secrets → YOUTUBE_CREDENTIALS_JSON 에 등록
 *    (빌드 시 assets/youtube_credentials.json 으로 자동 주입)
 * 4. 또는 디바이스 Android/data/com.asterion.video/files/youtube_credentials.json 에 직접 저장
 */
private const val YT_TOKEN_URL  = "https://oauth2.googleapis.com/token"
private const val YT_CREDS_FILE = "youtube_credentials.json"

private var ytCachedToken = ""
private var ytTokenExpiry = 0L

class YouTubeAuth(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun credentialStatus(): String = when {
        loadCredsOrNull() != null -> "✅ YouTube 인증 키 인식"
        else -> "❌ youtube_credentials.json 없음 — README 참고"
    }

    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000L
        if (ytCachedToken.isNotBlank() && ytTokenExpiry - now > 300) return@withContext ytCachedToken
        val creds = loadCreds()
        refreshAccessToken(
            clientId     = creds.getString("client_id"),
            clientSecret = creds.getString("client_secret"),
            refreshToken = creds.getString("refresh_token")
        ).also { ytCachedToken = it; ytTokenExpiry = now + 3600 }
    }

    fun isConfigured(): Boolean = loadCredsOrNull() != null

    private fun loadCredsOrNull(): JSONObject? = try {
        JSONObject(context.assets.open(YT_CREDS_FILE).bufferedReader().readText())
    } catch (_: Exception) {
        val ext = File(context.getExternalFilesDir(null), YT_CREDS_FILE)
        if (ext.exists()) JSONObject(ext.readText()) else null
    }

    private fun loadCreds() = loadCredsOrNull()
        ?: throw Exception("youtube_credentials.json 없음\nGitHub Secret: YOUTUBE_CREDENTIALS_JSON")

    private fun refreshAccessToken(clientId: String, clientSecret: String, refreshToken: String): String {
        val body = "grant_type=refresh_token" +
            "&client_id=${enc(clientId)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&refresh_token=${enc(refreshToken)}"
        val resp = client.newCall(
            Request.Builder().url(YT_TOKEN_URL)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
        ).execute()
        val rb = resp.body?.string() ?: throw Exception("YouTube 토큰 응답 없음")
        if (!resp.isSuccessful) throw Exception("YouTube 토큰 실패 ${resp.code}: $rb")
        return JSONObject(rb).getString("access_token")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
