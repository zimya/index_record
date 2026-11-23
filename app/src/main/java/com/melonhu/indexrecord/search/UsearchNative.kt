package com.melonhu.indexrecord.search

internal object UsearchNative {
    init {
        System.loadLibrary("usearch_jni")
    }

    external fun nativeCreate(
        dimensions: Long,
        metric: String,
        quantization: String,
        capacity: Long,
        connectivity: Long,
        expansionAdd: Long,
        expansionSearch: Long
    ): Long

    external fun nativeOpen(
        path: String,
        viewOnly: Boolean,
        expansionAdd: Long = 128L,
        expansionSearch: Long = 64L
    ): Long

    external fun nativeDestroy(ptr: Long)

    external fun nativeSave(ptr: Long, path: String)

    external fun nativeSize(ptr: Long): Long

    external fun nativeDimensions(ptr: Long): Long

    external fun nativeContains(ptr: Long, key: Long): Boolean

    external fun nativeRemove(ptr: Long, key: Long): Boolean

    external fun nativeAdd(ptr: Long, key: Long, vector: FloatArray)

    external fun nativeSearchFiltered(
        ptr: Long,
        query: FloatArray,
        limit: Int,
        keyMask: Long,
        expectedValue: Long,
        outKeys: LongArray,
        outDistances: FloatArray
    ): Int

    external fun nativeSearchInKeys(
        ptr: Long,
        query: FloatArray,
        candidateKeys: LongArray,
        limit: Int,
        outKeys: LongArray,
        outDistances: FloatArray
    ): Int
}
