package me.melon.record.sync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import io.objectbox.Box
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import me.melon.record.logic.DataProcessor
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

object RecordSyncBridge : Binder() {
    private const val TAG = "RecordSyncBridge"
    const val DESCRIPTOR = "me.melon.record.sync.IRecordSyncBridge"
    const val TRANSACTION_SYNC_FILE = Binder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_SYNC_STRING = Binder.FIRST_CALL_TRANSACTION + 2

    private var rawDataBox: Box<RawData>? = null

    fun init(context: Context) {
        try {
            ObjectBox.init(context)
            if (ObjectBox.store != null) {
                rawDataBox = ObjectBox.store.boxFor(RawData::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing RecordSyncBridge", e)
        }
    }

    fun attachToBundle(bundle: Bundle) {
        bundle.putBinder("sync_binder", this)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_SYNC_FILE -> {
                data.enforceInterface(DESCRIPTOR)
                val url = data.readString().orEmpty()
                val hostApp = data.readString().orEmpty()
                val timestamp = data.readLong()
                val hasPfd = data.readInt() != 0
                val pfd = if (hasPfd) {
                    ParcelFileDescriptor.CREATOR.createFromParcel(data)
                } else null

                var success = false
                if (pfd != null) {
                    try {
                        val json = FileInputStream(pfd.fileDescriptor).use { fis ->
                            val bytes = fis.readBytes()
                            String(bytes, StandardCharsets.UTF_8)
                        }
                        success = saveRawData(url, hostApp, json, timestamp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading payload from PFD", e)
                    } finally {
                        try { pfd.close() } catch (_: Exception) {}
                    }
                }

                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                return true
            }
            TRANSACTION_SYNC_STRING -> {
                data.enforceInterface(DESCRIPTOR)
                val url = data.readString().orEmpty()
                val hostApp = data.readString().orEmpty()
                val timestamp = data.readLong()
                val json = data.readString().orEmpty()

                val success = saveRawData(url, hostApp, json, timestamp)
                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }

    private fun saveRawData(url: String, hostApp: String, json: String, timestamp: Long): Boolean {
        if (json.isBlank()) return false
        val box = rawDataBox ?: run {
            try {
                if (ObjectBox.store != null) {
                    ObjectBox.store.boxFor(RawData::class.java).also { rawDataBox = it }
                } else null
            } catch (_: Exception) {
                null
            }
        } ?: return false

        return try {
            val entity = RawData(
                url = url,
                json = json,
                hostApp = hostApp,
                timestamp = if (timestamp > 0) timestamp else System.currentTimeMillis()
            )
            box.put(entity)
            DataProcessor.requestProcessing()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting RawData entity", e)
            false
        }
    }
}
