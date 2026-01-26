package me.melon.record

import android.content.Context
import me.melon.record.data.model.MyObjectBox // 编译后自动生成的
import io.objectbox.BoxStore

/**
 * 全局单例，用于管理数据库连接
 */
object ObjectBox {
    lateinit var store: BoxStore
        private set

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = MyObjectBox.builder()
                .androidContext(context.applicationContext)
                .build()
        }
    }
}
