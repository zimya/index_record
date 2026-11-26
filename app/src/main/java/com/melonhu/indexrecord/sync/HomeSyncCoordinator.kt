package com.melonhu.indexrecord.sync

import android.app.Application
import android.app.BroadcastOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData

/** Owns host wake-up and the bounded foreground drain; tied to the caller's lifecycle scope. */
class HomeSyncCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    store: BoxStore = ObjectBox.store,
    private val drainPending: suspend () -> Unit = {}
) {
    private val rawBox = store.boxFor(RawData::class.java)
    private val refreshMutex = Mutex()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun start() {
        scope.launch(Dispatchers.IO) {
            sendSyncBroadcast()
        }
    }

    private fun sendSyncBroadcast() {
        RecordSyncBridge.init(application)
        val bundle = Bundle().apply { putBinder("sync_binder", RecordSyncBridge) }
        val options = if (Build.VERSION.SDK_INT >= 34) {
            BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE).toBundle()
        } else null
        for (pkg in PlatformCatalog.hostPackages) {
            try {
                val intent = Intent("com.melonhu.indexrecord.ACTION_SYNC").apply {
                    setPackage(pkg)
                    putExtras(bundle)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                application.sendBroadcast(intent, null, options)
            } catch (e: Exception) {
                Log.w("HomeSyncCoordinator", "Failed to request sync from $pkg", e)
            }
        }
        try {
            val intent = Intent("com.melonhu.indexrecord.ACTION_SYNC").apply {
                putExtras(bundle)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            application.sendBroadcast(intent, null, options)
        } catch (e: Exception) {
            Log.w("HomeSyncCoordinator", "Failed to broadcast sync request", e)
        }
    }

    suspend fun refresh(): Boolean {
        if (!refreshMutex.tryLock()) return false
        _isRefreshing.value = true
        try {
            withContext(Dispatchers.IO) {
                sendSyncBroadcast()
                val start = SystemClock.elapsedRealtime()
                var hasReceivedAny = rawBox.count() > 0
                var lastPending = -1L
                var stableStart = start
                while (SystemClock.elapsedRealtime() - start < 15_000L) {
                    drainPending()
                    val pending = rawBox.count()
                    val now = SystemClock.elapsedRealtime()
                    if (pending > 0) hasReceivedAny = true
                    if (pending != lastPending) {
                        lastPending = pending
                        stableStart = now
                    }
                    if (!hasReceivedAny) {
                        if (now - start >= 1_500L && pending == 0L) break
                    } else if (pending == 0L && now - stableStart >= 800L) break
                    delay(150)
                }
                drainPending()
            }
            return true
        } finally {
            _isRefreshing.value = false
            refreshMutex.unlock()
            scope.launch(Dispatchers.IO) {
                if (rawBox.count() > 0) drainPending()
            }
        }
    }
}
