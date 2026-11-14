package com.melonhu.indexrecord.provider

import android.app.Service
import android.content.Intent
import android.os.IBinder

class SyncHostService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
