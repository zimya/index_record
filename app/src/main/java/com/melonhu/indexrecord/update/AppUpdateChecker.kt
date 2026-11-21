package com.melonhu.indexrecord.update

import android.os.Build
import com.google.gson.Gson
import com.melonhu.indexrecord.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemoteVersionInfo(
    val versionName: String = "",
    val versionCode: Int = 0,
    val tag: String = "",
    val xposedTag: String = "",
    val changelog: String = "",
    val downloadBaseUrl: String = "",
    val assets: List<String> = emptyList()
)

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val assetFileName: String,
    val proxyDownloadUrl: String,
    val directDownloadUrl: String
) {
    val versionKey: String get() = "$versionName:$versionCode"
}

object AppUpdateChecker {
    private const val VERSION_URL = "https://indexrecord.melonhu.com/version.json"
    private const val PROXY_PREFIX = "https://proxy.melonhu.cn/"
    private const val RELEASES_FALLBACK_URL =
        "https://github.com/Xposed-Modules-Repo/com.melonhu.indexrecord/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun checkForUpdate(
        currentVersionName: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS
    ): AppUpdateInfo? {
        val request = Request.Builder()
            .url(VERSION_URL)
            .header("Accept", "application/json")
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("Empty response body")
        }

        val remote = gson.fromJson(body, RemoteVersionInfo::class.java)
            ?: throw IOException("Invalid version.json")

        if (!isNewerVersion(remote.versionName, remote.versionCode, currentVersionName, currentVersionCode)) {
            return null
        }

        val assetName = selectApkAsset(remote.assets, supportedAbis)
        val directUrl = if (assetName != null && remote.downloadBaseUrl.isNotBlank()) {
            "${remote.downloadBaseUrl.trimEnd('/')}/$assetName"
        } else {
            RELEASES_FALLBACK_URL
        }

        val resolvedVersion = remote.versionName.ifBlank { remote.tag.removePrefix("v") }
        return AppUpdateInfo(
            versionName = resolvedVersion,
            versionCode = remote.versionCode,
            changelog = remote.changelog,
            assetFileName = assetName ?: "index-record-$resolvedVersion.apk",
            proxyDownloadUrl = "$PROXY_PREFIX$directUrl",
            directDownloadUrl = directUrl
        )
    }

    fun downloadApk(
        url: String,
        targetFile: File,
        onProgress: (Int) -> Unit
    ): File {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        tempFile.delete()

        val request = Request.Builder().url(url).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            var lastReported = -1

            onProgress(if (totalBytes > 0) 0 else -1)
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            throw IOException("Failed to save APK")
        }
        return targetFile
    }

    internal fun isNewerVersion(
        remoteName: String,
        remoteCode: Int,
        currentName: String,
        currentCode: Int
    ): Boolean {
        val nameCmp = compareVersionNames(remoteName, currentName)
        if (nameCmp != 0) return nameCmp > 0
        return remoteCode > currentCode
    }

    internal fun compareVersionNames(a: String, b: String): Int {
        val partsA = parseVersionParts(a)
        val partsB = parseVersionParts(b)
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val pa = partsA.getOrElse(i) { 0 }
            val pb = partsB.getOrElse(i) { 0 }
            if (pa != pb) return pa.compareTo(pb)
        }
        return 0
    }

    private fun parseVersionParts(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.split('.').map { it.toIntOrNull() ?: 0 }
    }

    internal fun selectApkAsset(assets: List<String>, supportedAbis: Array<String>): String? {
        val apkAssets = assets.filter { it.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        for (abi in supportedAbis) {
            apkAssets.firstOrNull { it.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apkAssets.firstOrNull { it.contains("universal", ignoreCase = true) }
            ?: apkAssets.first()
    }
}
