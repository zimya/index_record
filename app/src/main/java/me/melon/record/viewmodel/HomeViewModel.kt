package me.melon.record.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.objectbox.android.AndroidScheduler
import io.objectbox.query.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    init {
        // 1. 发送广播，通知宿主 App（如知乎）推送积压的数据
        val intent = Intent("me.melon.record.ACTION_SYNC")
        application.sendBroadcast(intent)

        // 2. 在应用启动（ViewModel初始化）时，处理所有本地已有的 RawData
        viewModelScope.launch(Dispatchers.IO) {
            try {
                me.melon.record.logic.DataProcessor.processPendingData()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)

    // UI State: 由于改为了后台自动解析，我们不再需要手动刷新控制
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // 监听 UniversalRecord 数据库变化
    val recordsFlow: Flow<List<UniversalRecord>> = callbackFlow {
        val query: Query<UniversalRecord> = recordBox.query()
            .orderDesc(UniversalRecord_.captureTime)
            .build()

        val subscription = query.subscribe()
            .on(AndroidScheduler.mainThread())
            .observer { data -> trySend(data) }

        trySend(query.find())

        awaitClose {
            subscription.cancel()
            query.close()
        }
    }.flowOn(Dispatchers.IO)

    // 如果用户非要下拉刷新，可以手动触发重试同步
    fun refreshData() {
        _isRefreshing.value = true
        // 1. 发送广播，通知宿主 App（如知乎）推送积压的数据
        val intent = Intent("me.melon.record.ACTION_SYNC")
        getApplication<Application>().sendBroadcast(intent)

        // 2. 处理所有本地已有的 RawData
        viewModelScope.launch(Dispatchers.IO) {
            try {
                me.melon.record.logic.DataProcessor.processPendingData()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
