package me.melon.record.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import io.objectbox.Box
import me.melon.record.logic.DataProcessor

class HookDataProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "me.melon.record.provider"
        val URI: Uri = Uri.parse("content://$AUTHORITY/raw_data")
    }

    private lateinit var rawDataBox: Box<RawData>
    private val providerScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            ObjectBox.init(ctx)
            rawDataBox = ObjectBox.store.boxFor(RawData::class.java)
        }
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!::rawDataBox.isInitialized) return null
        values ?: return null
        try {
            val url = values.getAsString("url")
            val json = values.getAsString("json")
            val host = values.getAsString("hostApp")

            val entity = RawData(
                url = url,
                json = json,
                hostApp = host
            )
            val id = rawDataBox.put(entity)
            
            // 【核心改进】异步触发数据解析，不需要等 UI 启动
            providerScope.launch {
                try {
                    DataProcessor.processPendingData()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return Uri.withAppendedPath(uri, id.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
