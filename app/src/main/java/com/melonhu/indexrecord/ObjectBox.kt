package com.melonhu.indexrecord

import android.content.Context

import com.melonhu.indexrecord.data.model.MyObjectBox // 编译后自动生成的
import io.objectbox.BoxStore

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
        }
    }
}
