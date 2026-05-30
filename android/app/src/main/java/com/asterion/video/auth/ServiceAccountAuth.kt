package com.asterion.video.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val KEY_ASSET = "service_account.json"
private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val SCOPE = "https://www.googleapis.com/auth/spreadsheets"

private var cachedToken = ""
private var tokenExpiry = 0L

class ServiceAccountAuth(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000L
        if (cachedToken.isNotBlank() && tokenExpiry - now > 300) return@withContext cachedToken
        val json = loadKeyJson()
        val jwt  = buildJwt(json.getString("client_email"), json.getString("private_key"))
        exchangeJwtForToken(jwt).also { cachedToken = it; tokenExpiry = now + 3600 }
    }

    private fun loadKeyJson(): JSONObject = try {
        JSONObject(context.assets.open(KEY_ASSET).bufferedReader().readText())
    } catch(e: Exception) {
        throw Exception("service_account.json 없음 — assets/ 에 배치 필요")
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
        val req = Request.Builder()
            .url(TOKEN_URL)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val rb = resp.body?.string() ?: throw Exception("토큰 응답 없음")
        if (!resp.isSuccessful) throw Exception("토큰 실패 ${resp.code}: $rb")
        return JSONObject(rb).getString("access_token")
    }

    private fun b64(d: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(d)
}
