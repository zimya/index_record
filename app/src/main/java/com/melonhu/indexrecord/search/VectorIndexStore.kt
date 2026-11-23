package com.melonhu.indexrecord.search

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal data class VectorHit(
    val id: Long,
    val similarity: Float
)

internal class VectorIndexStore(
    private val indexFile: File,
    private val dimensions: Long = DEFAULT_DIMENSIONS
) : AutoCloseable {

    private val rwLock = ReentrantReadWriteLock()
    private var nativePtr: Long = 0L
    private var dirty: Boolean = false

    init {
        indexFile.parentFile?.mkdirs()
        nativePtr = openOrCreate()
    }

    private fun openOrCreate(): Long {
        if (indexFile.exists() && indexFile.length() > 0L) {
            val loaded = runCatching {
                UsearchNative.nativeOpen(
                    path = indexFile.absolutePath,
                    viewOnly = false,
                    expansionAdd = DEFAULT_EXPANSION_ADD,
                    expansionSearch = DEFAULT_EXPANSION_SEARCH
                )
            }.getOrNull()
            if (loaded != null && loaded != 0L) {
                return loaded
            }
        }
        return UsearchNative.nativeCreate(
            dimensions = dimensions,
            metric = "cos",
            quantization = "f16",
            capacity = 1024L,
            connectivity = DEFAULT_CONNECTIVITY,
            expansionAdd = DEFAULT_EXPANSION_ADD,
            expansionSearch = DEFAULT_EXPANSION_SEARCH
        )
    }

    fun size(): Long = rwLock.read {
        if (nativePtr == 0L) 0L else UsearchNative.nativeSize(nativePtr)
    }

    fun upsertTextVector(recordId: Long, vector: FloatArray) {
        if (vector.size.toLong() != dimensions) return
        rwLock.write {
            ensureOpen()
            UsearchNative.nativeAdd(nativePtr, textKey(recordId), vector)
            dirty = true
        }
    }

    fun upsertImageVector(imageId: Long, vector: FloatArray) {
        if (vector.size.toLong() != dimensions) return
        rwLock.write {
            ensureOpen()
            UsearchNative.nativeAdd(nativePtr, imageKey(imageId), vector)
            dirty = true
        }
    }

    fun removeTextVector(recordId: Long) {
        rwLock.write {
            if (nativePtr == 0L) return
            if (UsearchNative.nativeRemove(nativePtr, textKey(recordId))) {
                dirty = true
            }
        }
    }

    fun searchTextTopK(queryVector: FloatArray, limit: Int = 40): List<VectorHit> =
        searchByModality(queryVector, limit, expectedFlag = 0L)

    fun searchImageTopK(queryVector: FloatArray, limit: Int = 40): List<VectorHit> =
        searchByModality(queryVector, limit, expectedFlag = IMAGE_KEY_FLAG)

    fun searchTextInKeys(queryVector: FloatArray, candidateRecordIds: LongArray, limit: Int = 40): List<VectorHit> {
        if (limit <= 0 || candidateRecordIds.isEmpty() || queryVector.size.toLong() != dimensions) return emptyList()
        return rwLock.read {
            if (nativePtr == 0L || UsearchNative.nativeSize(nativePtr) == 0L) return@read emptyList()
            val countToFetch = minOf(limit, candidateRecordIds.size)
            val outKeys = LongArray(countToFetch)
            val outDistances = FloatArray(countToFetch)
            val count = UsearchNative.nativeSearchInKeys(
                ptr = nativePtr,
                query = queryVector,
                candidateKeys = candidateRecordIds,
                limit = countToFetch,
                outKeys = outKeys,
                outDistances = outDistances
            )
            val hits = ArrayList<VectorHit>(count)
            for (i in 0 until count) {
                val id = extractId(outKeys[i])
                val similarity = (1f - outDistances[i]).coerceIn(-1f, 1f)
                hits.add(VectorHit(id = id, similarity = similarity))
            }
            hits
        }
    }

    fun searchImageInKeys(queryVector: FloatArray, candidateImageIds: LongArray, limit: Int = 40): List<VectorHit> {
        if (limit <= 0 || candidateImageIds.isEmpty() || queryVector.size.toLong() != dimensions) return emptyList()
        val maskedKeys = LongArray(candidateImageIds.size) { i -> imageKey(candidateImageIds[i]) }
        return rwLock.read {
            if (nativePtr == 0L || UsearchNative.nativeSize(nativePtr) == 0L) return@read emptyList()
            val countToFetch = minOf(limit, candidateImageIds.size)
            val outKeys = LongArray(countToFetch)
            val outDistances = FloatArray(countToFetch)
            val count = UsearchNative.nativeSearchInKeys(
                ptr = nativePtr,
                query = queryVector,
                candidateKeys = maskedKeys,
                limit = countToFetch,
                outKeys = outKeys,
                outDistances = outDistances
            )
            val hits = ArrayList<VectorHit>(count)
            for (i in 0 until count) {
                val id = extractId(outKeys[i])
                val similarity = (1f - outDistances[i]).coerceIn(-1f, 1f)
                hits.add(VectorHit(id = id, similarity = similarity))
            }
            hits
        }
    }

    private fun searchByModality(
        queryVector: FloatArray,
        limit: Int,
        expectedFlag: Long
    ): List<VectorHit> {
        if (limit <= 0 || queryVector.size.toLong() != dimensions) return emptyList()
        return rwLock.read {
            if (nativePtr == 0L || UsearchNative.nativeSize(nativePtr) == 0L) {
                return@read emptyList()
            }
            val outKeys = LongArray(limit)
            val outDistances = FloatArray(limit)
            val count = UsearchNative.nativeSearchFiltered(
                ptr = nativePtr,
                query = queryVector,
                limit = limit,
                keyMask = IMAGE_KEY_FLAG,
                expectedValue = expectedFlag,
                outKeys = outKeys,
                outDistances = outDistances
            )
            val hits = ArrayList<VectorHit>(count)
            for (i in 0 until count) {
                val id = extractId(outKeys[i])
                val similarity = (1f - outDistances[i]).coerceIn(-1f, 1f)
                hits.add(VectorHit(id = id, similarity = similarity))
            }
            hits
        }
    }

    fun flushIfDirty() {
        rwLock.write {
            if (!dirty || nativePtr == 0L) return
            val tmpFile = File(indexFile.parentFile, "${indexFile.name}.tmp")
            UsearchNative.nativeSave(nativePtr, tmpFile.absolutePath)
            if (indexFile.exists()) {
                indexFile.delete()
            }
            if (!tmpFile.renameTo(indexFile)) {
                tmpFile.copyTo(indexFile, overwrite = true)
                tmpFile.delete()
            }
            dirty = false
        }
    }

    fun clear() {
        rwLock.write {
            if (nativePtr != 0L) {
                UsearchNative.nativeDestroy(nativePtr)
                nativePtr = 0L
            }
            if (indexFile.exists()) indexFile.delete()
            val tmpFile = File(indexFile.parentFile, "${indexFile.name}.tmp")
            if (tmpFile.exists()) tmpFile.delete()
            nativePtr = UsearchNative.nativeCreate(
                dimensions = dimensions,
                metric = "cos",
                quantization = "f16",
                capacity = 1024L,
                connectivity = DEFAULT_CONNECTIVITY,
                expansionAdd = DEFAULT_EXPANSION_ADD,
                expansionSearch = DEFAULT_EXPANSION_SEARCH
            )
            dirty = false
        }
    }

    private fun ensureOpen() {
        if (nativePtr == 0L) {
            nativePtr = openOrCreate()
        }
    }

    override fun close() {
        rwLock.write {
            if (dirty && nativePtr != 0L) {
                runCatching {
                    val tmpFile = File(indexFile.parentFile, "${indexFile.name}.tmp")
                    UsearchNative.nativeSave(nativePtr, tmpFile.absolutePath)
                    if (indexFile.exists()) indexFile.delete()
                    if (!tmpFile.renameTo(indexFile)) {
                        tmpFile.copyTo(indexFile, overwrite = true)
                        tmpFile.delete()
                    }
                }
                dirty = false
            }
            if (nativePtr != 0L) {
                UsearchNative.nativeDestroy(nativePtr)
                nativePtr = 0L
            }
        }
    }

    companion object {
        const val DEFAULT_DIMENSIONS = 768L
        const val DEFAULT_CONNECTIVITY = 16L
        const val DEFAULT_EXPANSION_ADD = 128L
        const val DEFAULT_EXPANSION_SEARCH = 64L
        const val VECTOR_INDEX_FILENAME = "record_vectors.usearch"
        private const val IMAGE_KEY_FLAG = 1L shl 62

        fun textKey(recordId: Long): Long = recordId
        fun imageKey(imageId: Long): Long = IMAGE_KEY_FLAG or imageId
        fun extractId(key: Long): Long = key and IMAGE_KEY_FLAG.inv()
    }
}
