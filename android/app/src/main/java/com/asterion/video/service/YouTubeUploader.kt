package com.asterion.video.service

import android.content.Context
import android.util.Log
import com.asterion.video.auth.YouTubeAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "YouTubeUploader"
private const val YT_UPLOAD_URL = "https://www.googleapis.com/upload/youtube/v3/videos"
private const val CHUNK_SIZE    = 5 * 1024 * 1024  // 5MB

/**
 * YouTube Data API v3 Resumable Upload
 *
 * 사용 전 youtube_credentials.json 설정 필요 (YouTubeAuth.kt 참고)
 *
 * privacyStatus:
 *   "private"   — 비공개 (기본값, 검토 후 수동 공개 권장)
 *   "unlisted"  — 링크 공유
 *   "public"    — 즉시 공개
 */
class YouTubeUploader(private val context: Context) {
    private val auth = YouTubeAuth(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = auth.isConfigured()

    suspend fun upload(
        videoFile    : File,
        title        : String,
        description  : String   = "",
        tags         : List<String> = emptyList(),
        categoryId   : String   = "27",       // Education
        privacyStatus: String   = "private",
        onProgress   : (String) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            onProgress("⚠️ YouTube: youtube_credentials.json 없음 — 업로드 건너뜀")
            return@withContext null
        }
        try {
            onProgress("🔐 YouTube: 인증 중...")
            val token = auth.getAccessToken()

            onProgress("📋 YouTube: 업로드 세션 시작...")
            val sessionUrl = startResumableSession(token, title, description, tags, categoryId, privacyStatus, videoFile.length())

            onProgress("⬆️ YouTube: 업로드 중 (${videoFile.length()/1024/1024}MB)...")
            val videoId = uploadChunked(sessionUrl, videoFile, onProgress)

            if (videoId != null) {
                onProgress("✅ YouTube 업로드 완료: https://youtu.be/$videoId (상태: $privacyStatus)")
            } else {
                onProgress("❌ YouTube: 업로드 완료 응답 없음")
            }
            videoId
        } catch (e: Exception) {
            Log.e(TAG, "YouTube 업로드 예외: $e")
            onProgress("❌ YouTube: ${e.message}")
            null
        }
    }

    private fun startResumableSession(
        token        : String,
        title        : String,
        description  : String,
        tags         : List<String>,
        categoryId   : String,
        privacyStatus: String,
        fileSize     : Long
    ): String {
        val meta = JSONObject().apply {
            put("snippet", JSONObject().apply {
                put("title", title)
                put("description", description)
                put("tags", JSONArray(tags))
                put("categoryId", categoryId)
                put("defaultLanguage", "ko")
            })
            put("status", JSONObject().apply {
                put("privacyStatus", privacyStatus)
                put("selfDeclaredMadeForKids", false)
            })
        }
        val resp = client.newCall(
            Request.Builder()
                .url("$YT_UPLOAD_URL?uploadType=resumable&part=snippet,status")
                .post(meta.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Upload-Content-Type", "video/mp4")
                .addHeader("X-Upload-Content-Length", fileSize.toString())
                .build()
        ).execute()
        if (!resp.isSuccessful)
            throw Exception("세션 시작 실패 ${resp.code}: ${resp.body?.string()?.take(200)}")
        return resp.header("Location")
            ?: throw Exception("Location 헤더 없음 — 업로드 URL 수신 실패")
    }

    private fun uploadChunked(
        sessionUrl: String,
        videoFile : File,
        onProgress: (String) -> Unit
    ): String? {
        val totalBytes = videoFile.length()
        var offset = 0L
        val buffer = ByteArray(CHUNK_SIZE)

        videoFile.inputStream().use { stream ->
            while (offset < totalBytes) {
                val bytesRead = stream.read(buffer)
                if (bytesRead <= 0) break
                val end  = offset + bytesRead - 1
                val chunk = buffer.copyOf(bytesRead)

                val resp = client.newCall(
                    Request.Builder()
                        .url(sessionUrl)
                        .put(chunk.toRequestBody("video/mp4".toMediaType()))
                        .addHeader("Content-Range", "bytes $offset-$end/$totalBytes")
                        .build()
                ).execute()

                when (resp.code) {
                    200, 201 -> {
                        val body = resp.body?.string() ?: return null
                        return JSONObject(body).optString("id").takeIf { it.isNotBlank() }
                    }
                    308 -> {  // Resume Incomplete — 계속
                        offset += bytesRead
                        val pct = (offset * 100 / totalBytes).toInt()
                        if (pct % 10 == 0)  // 10% 단위로만 로그
                            onProgress("⬆️ YouTube: ${pct}% (${offset/1024/1024}/${totalBytes/1024/1024}MB)")
                    }
                    else -> throw Exception("청크 업로드 실패 ${resp.code}: ${resp.body?.string()?.take(200)}")
                }
            }
        }
        return null
    }
}
