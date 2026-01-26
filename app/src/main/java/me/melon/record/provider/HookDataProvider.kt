package me.melon.record.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import io.objectbox.Box

class HookDataProvider : ContentProvider() {

    companion object {
        // 定义 URI，Xposed 模块需要用到这个
        const val AUTHORITY = "me.melon.record.provider"
        val URI: Uri = Uri.parse("content://$AUTHORITY/raw_data")
    }

    private lateinit var rawDataBox: Box<RawData>

    override fun onCreate(): Boolean {
	    // 关键修复：获取 Context 并初始化 ObjectBox
	    // 因为 Provider 启动比 Application 早，必须在这里先初始化
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
            return Uri.withAppendedPath(uri, id.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // 其他方法虽然不用，但必须实现
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
