package com.melonhu.indexrecord.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.RawData
import io.objectbox.Box
import java.io.File
import java.io.FileNotFoundException
import com.melonhu.indexrecord.sync.RawPayloadCodec

class HookDataProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.melonhu.indexrecord.provider"
        val URI: Uri = Uri.parse("content://$AUTHORITY/raw_data")
        val PAYLOAD_URI: Uri = Uri.parse("content://$AUTHORITY/raw_payload")
    }

    private lateinit var rawDataBox: Box<RawData>

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            ObjectBox.init(ctx)
            rawDataBox = ObjectBox.store.boxFor(RawData::class.java)
        }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (context == null) return null
        val segments = uri.pathSegments ?: return null
        if (segments.size != 2 || segments[0] != "raw_payload") return null
        if (!mode.contains("w")) throw FileNotFoundException()
        enforceCallerAllowed()

        val token = segments[1]
        val dir = File(context!!.cacheDir, "hook_payload")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$token.bin")
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!::rawDataBox.isInitialized) return null
        values ?: return null
        try {
            enforceCallerAllowed()
            val url = values.getAsString("url") ?: ""
            val host = values.getAsString("hostApp") ?: ""
            val timestamp = (values.getAsLong("timestamp")
                ?: values.getAsString("timestamp")?.toLongOrNull())
                ?: System.currentTimeMillis()
            val payloadToken = values.getAsString("payloadToken")
            val json = if (!payloadToken.isNullOrBlank()) {
                val dir = File(context!!.cacheDir, "hook_payload")
                val file = File(dir, "$payloadToken.bin")
                if (!file.exists()) return null
                val bytes = file.readBytes()
                file.delete()
                RawPayloadCodec.encode(bytes, host)
            } else {
                values.getAsString("json") ?: ""
            }

            val entity = RawData(
                url = url,
                json = json,
                hostApp = host,
                timestamp = timestamp
            )
            val id = rawDataBox.put(entity)
            // 转存成功后由数据库变更订阅异步触发解析，不阻塞宿主的 Binder 调用。
            return Uri.withAppendedPath(uri, id.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun enforceCallerAllowed() {
        val ctx = context ?: return
        val uid = Binder.getCallingUid()
        val pkgs = ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        val allow = com.melonhu.indexrecord.data.PlatformCatalog.hostPackages.toSet() + ctx.packageName
        if (pkgs.none { it in allow }) throw SecurityException("caller not allowed")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
