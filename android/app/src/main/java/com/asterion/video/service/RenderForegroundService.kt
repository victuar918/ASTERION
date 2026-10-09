package com.asterion.video.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 장시간 렌더링(69씬 기준 수십 분) 시 Android 시스템의 백그라운드 프로세스 킬 방지.
 * Activity가 렌더링 시작/종료 시 직접 start/stop.
 */
class RenderForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "asterion_render_channel"
        const val NOTIF_ID   = 1001
    }

    override fun onCreate() {
        super.onCreate()
        // NotificationChannel은 API 26+ 필수
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "영상 렌더링",
                NotificationManager.IMPORTANCE_LOW  // 소리/진동 없음
            ).apply {
                description = "ASTERION 영상 렌더링 진행 중"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ASTERION 영상 자동화")
            .setContentText("렌더링 또는 자동 대기 중")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)   // 사용자가 직접 닫기 불가
            .setSilent(true)    // 소리/진동 없음
            .build()

        // API 29+: foregroundServiceType 명시 (targetSdk 35 대응)
        // API 26~28: 2-인자 형식 사용
        // v3.51: dataSync는 Android 15+에서 하루 6시간 제한(초과 시 앱 강제 종료) → 시간 제한 없는 specialUse로 변경
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // START_NOT_STICKY: 킬 시 자동 재시작 안 함
        // (재시작 시 렌더링 없이 알림만 뜨는 상황 방지)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // stopService() 호출 시 알림 자동 제거되지만 명시적으로 처리
        @Suppress("DEPRECATION")
        stopForeground(true)  // boolean 형식: API 26+ 호환 (API 33+ deprecated이나 작동함)
    }

    // v3.51: 안전망 — 시스템이 시간 제한을 걸어오면 크래시 대신 스스로 정리 (Android 15+)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
