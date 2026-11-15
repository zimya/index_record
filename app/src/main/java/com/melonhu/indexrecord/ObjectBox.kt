package com.melonhu.indexrecord

import android.content.Context
import android.util.Log
import com.melonhu.indexrecord.data.model.MyObjectBox // 编译后自动生成的
import io.objectbox.BoxStore
import io.objectbox.android.AndroidObjectBrowser

/**
 * 全局单例，用于管理数据库连接
 */
object ObjectBox {
    lateinit var store: BoxStore
        private set

    val isInitialized: Boolean
        get() = ::store.isInitialized

    @Synchronized
    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = MyObjectBox.builder()
                .androidContext(context.applicationContext)
                .build()

            if (BuildConfig.DEBUG) {
                val started = AndroidObjectBrowser(store).start(context.applicationContext)
                Log.i("ObjectBox", "ObjectBrowser started: $started")
            }
        }
    }
}
