package com.melonhu.indexrecord.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.JsonParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.bodyFormat
import com.melonhu.indexrecord.markdown.collectImageUrls
import com.melonhu.indexrecord.markdown.contentToMdAst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class ProcessedImageData(
    val cachedFile: File,
    val jpegBytesForVision: ByteArray,
    val ocrText: String
)

internal class ImageSearchProcessor(context: Context) {

    private val cacheDir = File(context.cacheDir, "search_image_cache").apply { mkdirs() }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val updated = when {
                url.contains("doubanio.com") || url.contains("douban.com") -> {
                    request.newBuilder()
                        .header(
                            "User-Agent",
                            "api-client/1 com.douban.frodo/7.132.0(360) Android/36 udid/db87f91e63b18b9f94b50c897f792525533f0517"
                        )
                        .header("Referer", "https://frodo.douban.com/")
                        .build()
                }
                url.contains("coolapk.com") -> {
                    request.newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14; 24129PN74C Build/UKQ1.231003.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36 #CoolMarket#14.5.3"
                        )
                        .build()
                }
                else -> request
            }
            chain.proceed(updated)
        }
        .build()

    private val ocrRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun downloadAndPrepare(imageUrl: String): File? = withContext(Dispatchers.IO) {
        val cleanUrl = imageUrl.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext null
        }
        cacheDir.mkdirs()
        val target = cachedFileFor(cleanUrl)
        if (target.exists() && target.length() > 0L) {
            return@withContext target
        }

        val tmp = File(cacheDir, "${target.name}.tmp")
        try {
            val request = Request.Builder().url(cleanUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Image download HTTP ${response.code} for $cleanUrl")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val contentLength = body.contentLength()
                if (contentLength > MAX_DOWNLOAD_BYTES) {
                    Log.w(TAG, "Image too large ($contentLength bytes) for $cleanUrl")
                    return@withContext null
                }
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_DOWNLOAD_BYTES) {
                                tmp.delete()
                                return@withContext null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (tmp.length() <= 0L) {
                tmp.delete()
                return@withContext null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Log.w(TAG, "Failed to download image: $cleanUrl", t)
            null
        }
    }

    suspend fun extractOcrText(imageFile: File): String? {
        val ocrBitmap = withContext(Dispatchers.Default) {
            decodeSampledBitmap(imageFile, maxDimension = 1024)
        } ?: return null

        return try {
            runOcr(ocrBitmap)
        } finally {
            if (!ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
        }
    }

    suspend fun encodeVisionBytes(imageFile: File): ByteArray? = withContext(Dispatchers.Default) {
        val bitmap = decodeSampledBitmap(imageFile, maxDimension = 512) ?: return@withContext null
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    fun cachedFileFor(imageUrl: String): File = File(cacheDir, "${sha256(imageUrl)}.img")

    fun getCacheSizeBytes(): Long {
        val files = cacheDir.listFiles() ?: return 0L
        var total = 0L
        for (file in files) {
            if (file.isFile) total += file.length()
        }
        return total
    }

    fun clearCache(): Long {
        val files = cacheDir.listFiles() ?: return 0L
        var freed = 0L
        for (file in files) {
            if (file.isFile) {
                freed += file.length()
                file.delete()
            }
        }
        return freed
    }

    private suspend fun runOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        ocrRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (cont.isActive) {
                    cont.resume(visionText.text.trim())
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ML Kit OCR failed", e)
                if (cont.isActive) {
                    cont.resume("")
                }
            }
    }

    private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (
                bounds.outWidth / sampleSize > maxDimension * 2 ||
                bounds.outHeight / sampleSize > maxDimension * 2
            ) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            scaleDownIfNeeded(decoded, maxDimension)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to decode bitmap ${file.name}", t)
            null
        }
    }

    private fun encodeForVision(bitmap: Bitmap, maxDimension: Int): ByteArray {
        val scaled = scaleDownIfNeeded(bitmap, maxDimension)
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = maxOf(width, height)
        if (maxSide <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / maxSide.toFloat()
        val newW = (width * ratio).toInt().coerceAtLeast(1)
        val newH = (height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        return scaled
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val hex = (b.toInt() and 0xFF).toString(16)
                if (hex.length == 1) append('0')
                append(hex)
            }
        }
    }

    companion object {
        private const val TAG = "ImageSearchProcessor"
        private const val MAX_DOWNLOAD_BYTES = 15L * 1024L * 1024L

        fun extractImageUrls(record: UniversalRecord): List<String> {
            val urls = LinkedHashSet<String>()
            runCatching {
                val format = record.bodyFormat()
                val ast = contentToMdAst(record.content, format.renderingFormat, format.enableMath)
                for (url in ast.collectImageUrls()) {
                    val trimmed = url.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        urls.add(trimmed)
                    }
                }
            }
            runCatching {
                if (record.extraMetadata.isNotBlank() && record.extraMetadata != "{}") {
                    val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
                    for (key in listOf("title_image", "message_cover")) {
                        val value = obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.asString?.trim().orEmpty()
                        if (value.startsWith("http://") || value.startsWith("https://")) {
                            urls.add(value)
                        }
                    }
                }
            }
            return urls.toList()
        }
    }
}
