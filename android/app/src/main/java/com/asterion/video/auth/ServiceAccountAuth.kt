package com.asterion.video.auth

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val SCOPE = "https://www.googleapis.com/auth/spreadsheets"

// 서비스 계정 키 파일 검색 순서
// 1순위: 내장저장소/Documents/work/ASTERION/service_account.json
// 2순위: assets/service_account.json (재빌드 필요)
private val KEY_PATHS = listOf(
    File(Environment.getExternalStorageDirectory(), "Documents/work/ASTERION/service_account.json"),
    File(Environment.getExternalStorageDirectory(), "Documents/work/ASTERION/YouTube/service_account.json")
)

private var cachedToken = ""
private var tokenExpiry = 0L

class ServiceAccountAuth(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 키 파일 위치 반환 (없으면 null)
    fun keyFilePath(): String? = KEY_PATHS.firstOrNull { it.exists() }?.absolutePath
        ?: try { context.assets.open("service_account.json"); "assets/service_account.json" } catch(e: Exception) { null }

    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000L
        if (cachedToken.isNotBlank() && tokenExpiry - now > 300) return@withContext cachedToken
        val json = loadKeyJson()
        val jwt  = buildJwt(json.getString("client_email"), json.getString("private_key"))
        exchangeJwtForToken(jwt).also { cachedToken = it; tokenExpiry = now + 3600 }
    }

    private fun loadKeyJson(): JSONObject {
        // 1. 외부 저장소 검색
        KEY_PATHS.forEach { f -> if (f.exists()) return JSONObject(f.readText()) }
        // 2. assets 폴대
        return try {
            JSONObject(context.assets.open("service_account.json").bufferedReader().readText())
        } catch(e: Exception) {
            throw Exception(
                "service_account.json 없음\n" +
                "아래 경로에 파일을 저장하세요:\n" +
                "→ 내장저장소/Documents/work/ASTERION/service_account.json"
            )
        }
    }

    private fun buildJwt(email: String, pem: String): String {
        val now = System.currentTimeMillis() / 1000L
        val h = b64(JSONObject().apply { put("alg","RS256"); put("typ","JWT") }.toString().toByteArray())
        val p = b64(JSONObject().apply { put("iss",email); put("scope",SCOPE); put("aud",TOKEN_URL); put("iat",now); put("exp",now+3600) }.toString().toByteArray())
        val cleaned = pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replace("\\n","").replace("\n","").trim()
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(cleaned)))
        val sig = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$h.$p".toByteArray(Charsets.US_ASCII)) }.sign()
        return "$h.$p.${b64(sig)}"
    }

    private fun exchangeJwtForToken(jwt: String): String {
        val body = "grant_type=${java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer","UTF-8")}&assertion=$jwt"
        val req = Request.Builder().url(TOKEN_URL)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val rb = resp.body?.string() ?: throw Exception("토큰 응답 없음")
        if (!resp.isSuccessful) throw Exception("토큰 실패 ${resp.code}: $rb")
        return JSONObject(rb).getString("access_token")
    }

    private fun b64(d: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(d)
}
