package com.asterion.video.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

// 추후 구현 예정 — 현재는 빌드 오류 방지용 stub
class RenderForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
