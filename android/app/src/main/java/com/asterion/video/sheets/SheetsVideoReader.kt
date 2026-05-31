package com.asterion.video.sheets

import android.util.Log
import com.asterion.video.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "SheetsVideoReader"
const val VIDEO_SS_ID = "1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc"

data class VideoScriptData(val sheetName: String, val videoMeta: VideoMeta, val scriptRows: List<ScriptDataRow>)

class SheetsVideoReader(private val accessToken: String, private val spreadsheetId: String = VIDEO_SS_ID) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val base = "https://sheets.googleapis.com/v4/spreadsheets"

    suspend fun readScript(sheetName: String): Result<VideoScriptData> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(sheetName, "UTF-8")
            val resp = get("$base/$spreadsheetId/values/$encoded")
                ?: throw Exception("Sheets 응답 없음: $sheetName")
            val values = resp.optJSONArray("values")
                ?: throw Exception("시트 비어있음: $sheetName")
            val rows = (0 until values.length()).map { i ->
                val row = values.getJSONArray(i)
                (0 until row.length()).map { j -> row.getString(j) }
            }
            val meta  = parseVideoMeta(rows)
            val start = rows.indexOfFirst { it.getOrElse(0){""} == "Section" }
                .let { if (it >= 0) it + 1 else -1 }
            val script = if (start >= 0) parseScriptRows(rows, start) else emptyList()
            Log.i(TAG, "readScript: $sheetName rows=${script.size} ready=${script.count{it.isReady}}")
            VideoScriptData(sheetName, meta, script)
        }
    }

    suspend fun readReadyRows(sheetName: String) =
        readScript(sheetName).map { it.copy(scriptRows = it.scriptRows.filter { r -> r.isReady }) }

    suspend fun updateStatus(sheetName: String, rowIndex: Int, status: String = "DONE"): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // rowIndex=0 → 시트 7행 (Video_Meta 5행 + 헤더 1행 + 1-index)
                val sheetRow = 7 + rowIndex
                val encoded  = java.net.URLEncoder.encode("$sheetName!K$sheetRow", "UTF-8")
                val url  = "$base/$spreadsheetId/values/$encoded?valueInputOption=USER_ENTERED"
                val body = JSONObject().apply {
                    put("values", org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply { put(status) })
                    })
                }
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .put(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().isSuccessful
            }.getOrElse { false }
        }

    suspend fun listScriptSheets(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = get("$base/$spreadsheetId?fields=sheets.properties.title")
                ?: return@runCatching emptyList<String>()
            val arr  = resp.optJSONArray("sheets") ?: return@runCatching emptyList<String>()
            (0 until arr.length())
                .map { arr.getJSONObject(it).getJSONObject("properties").getString("title") }
                .filter { it.startsWith("VS_") }
        }.getOrElse { Log.e(TAG, "listScriptSheets: $it"); emptyList() }
    }

    private fun parseVideoMeta(rows: List<List<String>>): VideoMeta {
        val m = mutableMapOf<String, String>()
        for (r in rows) {
            val col0 = r.getOrElse(0){""}
            if (col0 == "Section") break
            // 시트 형식: A열=Key, B열=Value
            val key   = col0
            val value = r.getOrElse(1){""}
            if (key.isNotBlank() && key != "Key") m[key] = value
        }
        return VideoMeta(
            youtubeTitle  = m["YouTube_Title"] ?: "",
            topWatermark  = m["Top_Watermark"] ?: "ASTERION",
            thumbnailText = m["Thumbnail_Text"] ?: "",
            mainBgm       = m["Main_BGM"] ?: "bgm01.mp3"
        )
    }

    private fun parseScriptRows(rows: List<List<String>>, start: Int) =
        rows.drop(start).mapIndexedNotNull { idx, r ->
            val sec = r.getOrElse(0){""}
            if (sec.isBlank()) null
            else ScriptDataRow(
                rowIndex       = idx,
                section        = sec,
                speaker        = r.getOrElse(1){"1"}.toIntOrNull() ?: 1,
                cardMain       = r.getOrElse(2){""},
                cardSub        = r.getOrElse(3){""},
                cardDesc       = r.getOrElse(4){""},
                highlightWord  = r.getOrElse(5){""},
                script         = r.getOrElse(6){""},
                bgFile         = r.getOrElse(7){"VedicEnergyByPlanet_XRP_MovingChart.mp4|FADE|1.0|NONE"},
                animation      = r.getOrElse(8){"A"},
                cardStyle      = r.getOrElse(9){"DEFAULT"},
                status         = r.getOrElse(10){"READY"},
                note           = r.getOrElse(11){""},
                bgEffect       = r.getOrElse(12){"NONE"},
                bgTransition   = r.getOrElse(13){"FADE"},
                cardExtraEffect= r.getOrElse(14){"NONE"},
                lottieFile     = r.getOrElse(15){"NONE"},
                stickerFile    = r.getOrElse(16){"NONE"},
                gradientPreset = r.getOrElse(17){"DEFAULT"}
            )
        }

    private fun get(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get().build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}")
            else { Log.e(TAG, "GET ${resp.code}: $url"); null }
        }
    }
}
