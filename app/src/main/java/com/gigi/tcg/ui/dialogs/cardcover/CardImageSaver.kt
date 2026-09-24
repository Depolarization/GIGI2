// 卡面下载：设计文档 §4.4 —— web 版 download.ts 的 fallback（新标签打开原图）分支整体删除，
// 因为原生侧 OkHttp 直连不受 CORS 约束。失败即失败，由调用方 toast 归因，不做任何回退。
// 落相册两条路径：Q+ 走 MediaStore(RELATIVE_PATH=Pictures/GIGI, IS_PENDING)；
// API 24-28 走公共目录 File + MediaScanner（需 WRITE_EXTERNAL_STORAGE，见 hasWriteExternalPermission）。

package com.gigi.tcg.ui.dialogs.cardcover

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 图床 act-upload.mihoyo.com 无 CORS 限制，直连即可；与 MihoyoClient 分离（不进 Cookie 注入链） */
private const val DOWNLOAD_CONNECT_TIMEOUT_S = 10L
private const val DOWNLOAD_READ_TIMEOUT_S = 30L

/** 相册目录名（Q+ RELATIVE_PATH 与 <Q 公共目录子文件夹共用）；ALBUM_PARENT 即 Environment.DIRECTORY_PICTURES */
private const val ALBUM_PARENT = "Pictures"
private const val ALBUM_NAME = "GIGI"
private const val STORAGE_PERMISSION_MESSAGE = "需要存储权限才能保存卡面"
private const val WRITE_FAILED_MESSAGE = "相册写入失败"
private const val FALLBACK_NAME = "卡面"

/** 卡名可能很长且含非法字符：清洗后截断，避免 MediaStore insert / File 创建失败 */
private const val MAX_BASE_NAME_CHARS = 60
private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

fun requiresWriteExternalPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** Sheet 侧据此决定是否申请运行时权限（Q+ 由 MediaStore 代理写入，无需权限） */
fun hasWriteExternalPermission(context: Context): Boolean =
    !requiresWriteExternalPermission() ||
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

/** 下载文件名 {name}.{png|gif}，非法文件名字符替换为下划线 */
internal fun coverFileName(name: String, format: CoverFormat): String {
    val cleaned = ILLEGAL_FILE_NAME_CHARS.replace(name, "_")
        .trim()
        .trimEnd('.')
        .take(MAX_BASE_NAME_CHARS)
        .ifBlank { FALLBACK_NAME }
    return "$cleaned.${format.extension}"
}

class CardImageSaver(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    suspend fun save(url: String, name: String, format: CoverFormat) = withContext(Dispatchers.IO) {
        val bytes = downloadBytes(url)
        val fileName = coverFileName(name, format)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(bytes, fileName, format)
        } else {
            savePublicDirectory(bytes, fileName, format)
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败 HTTP ${response.code}")
            }
            return response.body?.bytes() ?: throw IOException("下载失败：响应为空")
        }
    }

    /** Q+：IS_PENDING 建条目 → 写流 → 转正；任何异常回滚，不留半张图 */
    private fun saveScoped(bytes: ByteArray, fileName: String, format: CoverFormat) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "$ALBUM_PARENT/$ALBUM_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException(WRITE_FAILED_MESSAGE)
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException(WRITE_FAILED_MESSAGE)
            output.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun savePublicDirectory(bytes: ByteArray, fileName: String, format: CoverFormat) {
        if (!hasWriteExternalPermission(context)) {
            throw IOException(STORAGE_PERMISSION_MESSAGE)
        }
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(pictures, ALBUM_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException(WRITE_FAILED_MESSAGE)
        }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(format.mimeType),
            null,
        )
    }
}
