package me.melon.record.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.objectbox.android.AndroidScheduler
import io.objectbox.query.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.melon.record.ObjectBox
import me.melon.record.data.model.Article
import me.melon.record.data.model.Article_
import me.melon.record.logic.DataProcessor

class HomeViewModel : ViewModel() {

    private val articleBox = ObjectBox.store.boxFor(Article::class.java)

    // UI State: 正在刷新
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // 监听 Article 数据库变化
    val articlesFlow: Flow<List<Article>> = callbackFlow {
        val query: Query<Article> = articleBox.query()
            .orderDesc(Article_.createTime)
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

    init {
        // App 启动时自动处理一次
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            try {
                // 执行核心解析逻辑
                DataProcessor.processPendingData()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
