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
            .setContentTitle("ASTERION 렌더링 중")
            .setContentText("영상을 제작하고 있습니다...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)   // 사용자가 직접 닫기 불가
            .setSilent(true)    // 소리/진동 없음
            .build()

        // API 29+: foregroundServiceType 명시 (targetSdk 35 대응)
        // API 26~28: 2-인자 형식 사용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

    override fun onBind(intent: Intent?): IBinder? = null
}
