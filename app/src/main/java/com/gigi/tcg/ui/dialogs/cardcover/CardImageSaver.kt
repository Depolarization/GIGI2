// 卡面下载：设计文档 §4.4 —— web 版 download.ts 的 fallback（新标签打开原图）分支整体删除，
// 因为原生侧 OkHttp 直连不受 CORS 约束。失败即失败，由调用方 toast 归因，不做任何回退。
// 落相册两条路径：Q+ 走 MediaStore(RELATIVE_PATH=Pictures/GIGI, IS_PENDING)；
// API 24-28 走公共目录 File + MediaScanner（需 WRITE_EXTERNAL_STORAGE，见 hasWriteExternalPermission）。

package com.gigi.tcg.ui.dialogs.cardcover

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
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

/**
 * 长图导出默认 JPEG 质量（V9-C）。
 * 1600×30524（行动牌 941 行双栏）用 PNG 约 8.3MB 且旧机解码极慢；JPEG q72 约 7.9MB，
 * 真实卡名更短 ⇒ 实测更小（用户样例 6MB / 1.76MB）。再降档（q65）只省 0.7MB 却糊中文笔画。
 */
private const val DEFAULT_EXPORT_JPEG_QUALITY = 72

/**
 * CompressFormat → 文件名后缀 / MIME。
 * 不复用 CoverFormat：那个枚举定义在 CardCoverViewModel（本棒范围外，且只有 Png/Gif 两种卡面格式），
 * 长图要的是 JPEG，在这里做一层私有映射最省事。
 * 只区分 PNG / 其余按 JPEG：WEBP 系列枚举项是 API 30+，在 WhenMappings 静态表里引用会在旧机抛错。
 */
private fun Bitmap.CompressFormat.fileExtension(): String = when (this) {
    Bitmap.CompressFormat.PNG -> "png"
    else -> "jpg"
}

private fun Bitmap.CompressFormat.mime(): String = when (this) {
    Bitmap.CompressFormat.PNG -> "image/png"
    else -> "image/jpeg"
}

fun requiresWriteExternalPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** Sheet 侧据此决定是否申请运行时权限（Q+ 由 MediaStore 代理写入，无需权限） */
fun hasWriteExternalPermission(context: Context): Boolean =
    !requiresWriteExternalPermission() ||
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

/** 下载文件名 {name}.{png|gif}，非法文件名字符替换为下划线 */
internal fun coverFileName(name: String, format: CoverFormat): String = coverFileName(name, format.extension)

/** 长图导出走 CompressFormat（jpg/png），扩展名跟着 format 走 */
internal fun coverFileName(name: String, extension: String): String {
    val cleaned = ILLEGAL_FILE_NAME_CHARS.replace(name, "_")
        .trim()
        .trimEnd('.')
        .take(MAX_BASE_NAME_CHARS)
        .ifBlank { FALLBACK_NAME }
    return "$cleaned.$extension"
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
        persist(bytes, fileName, format.mimeType)
    }

    /**
     * 长图导出（V8 §3.4 / V9-C）：调用方已渲染好 Bitmap，这里只负责编码 + 落盘。
     * 默认 JPEG q72：长图动辄 3 万像素高，PNG 既大（约 8MB）又要旧机做整幅 deflate 重建，解码慢；
     * JPEG 渐进解码快、体积小，文字为主的图在 1600px 宽度下 q72 仍清晰可辨。
     * 不 recycle —— 位图生命周期归调用方（它在 finally 里回收，避免异常路径泄漏）。
     */
    suspend fun saveBitmap(
        bitmap: Bitmap,
        baseName: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_EXPORT_JPEG_QUALITY,
    ) = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(format, quality, out)) {
                throw IOException("图片编码失败")
            }
            out.toByteArray()
        }
        persist(bytes, coverFileName(baseName, format.fileExtension()), format.mime())
    }

    /** 两条落盘路径共用：Q+ 走 MediaStore 代理写入，API 24-28 走公共目录 + MediaScanner */
    private fun persist(bytes: ByteArray, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(bytes, fileName, mimeType)
        } else {
            savePublicDirectory(bytes, fileName, mimeType)
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
    private fun saveScoped(bytes: ByteArray, fileName: String, mimeType: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
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

    private fun savePublicDirectory(bytes: ByteArray, fileName: String, mimeType: String) {
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
            arrayOf(mimeType),
            null,
        )
    }
}
