package com.asterion.video.sheets

import android.util.Log
import com.asterion.video.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "SheetsVideoReader"
const val VIDEO_SS_ID = "1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc"

data class VideoScriptData(val sheetName: String, val videoMeta: VideoMeta, val scriptRows: List<ScriptDataRow>)

class SheetsVideoReader(private val accessToken: String, private val spreadsheetId: String = VIDEO_SS_ID) {
    private val client = OkHttpClient()
    private val base = "https://sheets.googleapis.com/v4/spreadsheets"

    suspend fun readScript(sheetName: String): Result<VideoScriptData> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$base/$spreadsheetId/values/${java.net.URLEncoder.encode(sheetName,"UTF-8")}"
            val resp = get(url) ?: throw Exception("Sheets 응답 없음")
            val values = resp.optJSONArray("values") ?: throw Exception("시트 비어있음")
            val rows = (0 until values.length()).map { i ->
                (0 until values.getJSONArray(i).length()).map { j -> values.getJSONArray(i).getString(j) }
            }
            val meta = parseVideoMeta(rows)
            val start = rows.indexOfFirst { it.getOrElse(0){""} == "Section" }.let { if (it >= 0) it + 1 else -1 }
            val scriptRows = if (start >= 0) parseScriptRows(rows, start) else emptyList()
            VideoScriptData(sheetName, meta, scriptRows)
        }
    }

    suspend fun readReadyRows(sheetName: String) = readScript(sheetName).map { it.copy(scriptRows = it.scriptRows.filter { r -> r.isReady }) }

    suspend fun updateStatus(sheetName: String, rowIndex: Int, status: String = "DONE"): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sheetRow = 7 + rowIndex
            val range = java.net.URLEncoder.encode("$sheetName!K$sheetRow", "UTF-8")
            val url = "$base/$spreadsheetId/values/$range?valueInputOption=USER_ENTERED"
            val body = org.json.JSONObject().apply { put("values", org.json.JSONArray().apply { put(org.json.JSONArray().apply { put(status) }) }) }
            val req = Request.Builder().url(url).addHeader("Authorization","Bearer $accessToken").addHeader("Content-Type","application/json")
                .put(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body.toString())).build()
            client.newCall(req).execute().isSuccessful
        }.getOrElse { false }
    }

    suspend fun listScriptSheets(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$base/$spreadsheetId?fields=sheets.properties.title"
            val resp = get(url) ?: return@runCatching emptyList<String>()
            val arr = resp.optJSONArray("sheets") ?: return@runCatching emptyList<String>()
            (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("properties").getString("title") }.filter { it.startsWith("VS_") }
        }.getOrElse { emptyList() }
    }

    private fun parseVideoMeta(rows: List<List<String>>): VideoMeta {
        val m = mutableMapOf<String,String>()
        for (r in rows) { if (r.getOrElse(0){""} == "Section") break; if (r.size >= 3 && r[1].isNotBlank()) m[r[1]] = r[2] }
        return VideoMeta(m["YouTube_Title"]?:"", m["Top_Watermark"]?:"ASTERION", m["Thumbnail_Text"]?:"", m["Main_BGM"]?:"default_bgm.mp3")
    }

    private fun parseScriptRows(rows: List<List<String>>, start: Int) = rows.drop(start).mapIndexedNotNull { idx, r ->
        val sec = r.getOrElse(0){""}
        if (sec.isBlank()) null
        else ScriptDataRow(idx, sec, r.getOrElse(1){"1"}.toIntOrNull()?:1, r.getOrElse(2){""},
            r.getOrElse(3){""},r.getOrElse(4){""},r.getOrElse(5){""},r.getOrElse(6){""},
            r.getOrElse(7){"default_bg.mp4|FADE|1.0|NONE"},r.getOrElse(8){"A"},r.getOrElse(9){"DEFAULT"},
            r.getOrElse(10){"READY"},r.getOrElse(11){""},r.getOrElse(12){"NONE"},r.getOrElse(13){"FADE"},
            r.getOrElse(14){"NONE"},r.getOrElse(15){"NONE"},r.getOrElse(16){"NONE"},r.getOrElse(17){"DEFAULT"})
    }

    private fun get(url: String): JSONObject? = Request.Builder().url(url).addHeader("Authorization","Bearer $accessToken").get().build()
        .let { client.newCall(it).execute().use { resp -> if (resp.isSuccessful) JSONObject(resp.body()!!.string()) else null } }
}
