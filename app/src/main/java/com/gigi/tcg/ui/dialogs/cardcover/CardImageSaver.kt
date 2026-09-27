// 卡面下载：设计文档 §4.4 —— web 版 download.ts 的 fallback（新标签打开原图）分支整体删除，
// 因为原生侧 OkHttp 直连不受 CORS 约束。失败即失败，由调用方 toast 归因，不做任何回退。
// 落相册两条路径：Q+ 走 MediaStore(RELATIVE_PATH=相册相对路径（可带类型子目录）, IS_PENDING)；
// API 24-28 走公共目录 File + MediaScanner（需 WRITE_EXTERNAL_STORAGE，见 hasWriteExternalPermission），
// 该路径没有 MediaStore uri 可返回，故 saveBitmap 在旧机上返回 null。

package com.gigi.tcg.ui.dialogs.cardcover

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gigi.tcg.R
import com.gigi.tcg.i18n.LocaleStrings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 图床 act-upload.mihoyo.com 无 CORS 限制，直连即可；与 MihoyoClient 分离（不进 Cookie 注入链） */
private const val DOWNLOAD_CONNECT_TIMEOUT_S = 10L
private const val DOWNLOAD_READ_TIMEOUT_S = 30L

/** 相册子目录名：应用自有命名（非系统目录，父目录一律走 Environment 常量），不入 xml、三语不翻译 */
const val GIGI_ALBUM_NAME: String = "GIGI"

/**
 * 导出落盘的类型子目录名（角色牌 / 行动牌 / 最近对局）：与 GIGI_ALBUM_NAME 同一惯例 ——
 * 应用自有命名（非系统目录），不入 xml、三语不翻译。
 */
const val EXPORT_DIR_CHAR: String = "角色牌"
const val EXPORT_DIR_ACTION: String = "行动牌"
const val EXPORT_DIR_RECORDS: String = "最近对局"

/** 纯算术：相册相对路径 = 父目录 + 子目录。parent 由调用方传系统常量，便于 JVM 单测 */
internal fun buildAlbumRelativePath(systemPicturesDir: String, albumName: String): String =
    "$systemPicturesDir/$albumName"

/** 三参重载：相册根下再挂一层类型子目录，如 "Pictures/GIGI/行动牌" */
internal fun buildAlbumRelativePath(systemPicturesDir: String, albumName: String, subDir: String): String =
    "$systemPicturesDir/$albumName/$subDir"

/**
 * 导出文件名用的日期后缀：yyyy-MM-dd（本地时区，如 2026-09-27）。
 * SimpleDateFormat 非线程安全，故每次调用新建（同 domain/Format.kt 惯例）；导出跑在 Default/IO 线程池。
 *
 * @param nowMillis 「当前时刻」，默认取系统时间；显式注入供单测固定日期。
 */
internal fun exportDateText(nowMillis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))

/**
 * 相册相对路径：父目录走系统 API（Environment.DIRECTORY_PICTURES，不写字面量），子目录是应用自有命名。
 * Q+ 的 RELATIVE_PATH、<Q 的 File 目录、导出 Toast 文案三处必须同源。
 * 本函数返回值固定为相册根（Pictures/GIGI）；带类型子目录的落盘走三参 buildAlbumRelativePath。
 */
fun albumRelativePath(): String = buildAlbumRelativePath(Environment.DIRECTORY_PICTURES, GIGI_ALBUM_NAME)

/** 落盘相对路径：subDir 为 null 时与 albumRelativePath() 逐字相同；非 null 时追加类型子目录 */
private fun relativePathFor(subDir: String?): String =
    if (subDir == null) {
        albumRelativePath()
    } else {
        buildAlbumRelativePath(Environment.DIRECTORY_PICTURES, GIGI_ALBUM_NAME, subDir)
    }

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
        .ifBlank { LocaleStrings.get(R.string.cover_title_fallback) }
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
     *
     * @param subDir 类型子目录（EXPORT_DIR_* 常量），null 时落相册根 Pictures/GIGI，行为与旧版一致
     * @return Q+ 为 MediaStore 条目 uri（二维码临时图靠它删）；API 24-28 没有 uri，恒返回 null
     */
    suspend fun saveBitmap(
        bitmap: Bitmap,
        baseName: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_EXPORT_JPEG_QUALITY,
        subDir: String? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(format, quality, out)) {
                throw IOException(LocaleStrings.get(R.string.error_image_encode))
            }
            out.toByteArray()
        }
        persist(bytes, coverFileName(baseName, format.fileExtension()), format.mime(), subDir)
    }

    /** 两条落盘路径共用：Q+ 走 MediaStore 代理写入，API 24-28 走公共目录 + MediaScanner */
    private fun persist(bytes: ByteArray, fileName: String, mimeType: String, subDir: String? = null): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(bytes, fileName, mimeType, subDir)
        } else {
            savePublicDirectory(bytes, fileName, mimeType, subDir)
        }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(LocaleStrings.get(R.string.error_download_failed_http, response.code))
            }
            return response.body?.bytes() ?: throw IOException(LocaleStrings.get(R.string.error_download_empty))
        }
    }

    /**
     * Q+：IS_PENDING 建条目 → 写流 → 转正；任何异常回滚，不留半张图。
     * 返回条目 uri —— 二维码临时图落盘后要靠它 delete（API 24-28 分支无此能力，只能返回 null）。
     */
    private fun saveScoped(bytes: ByteArray, fileName: String, mimeType: String, subDir: String?): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePathFor(subDir))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException(LocaleStrings.get(R.string.error_album_write_failed))
        try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException(LocaleStrings.get(R.string.error_album_write_failed))
            output.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /**
     * API 24-28：File + MediaScanner 落盘，没有可返回的 MediaStore uri（scanFile 只是事后异步建条目），
     * 故恒返回 null；类型子目录靠 mkdirs 的多层创建能力。
     */
    private fun savePublicDirectory(bytes: ByteArray, fileName: String, mimeType: String, subDir: String?): Uri? {
        if (!hasWriteExternalPermission(context)) {
            throw IOException(LocaleStrings.get(R.string.error_storage_permission))
        }
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = if (subDir == null) File(pictures, GIGI_ALBUM_NAME) else File(pictures, "$GIGI_ALBUM_NAME/$subDir")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException(LocaleStrings.get(R.string.error_album_write_failed))
        }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return null
    }
}
