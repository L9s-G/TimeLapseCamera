package com.timelapse.camera.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DCIM 公共目录存储实现。
 *
 * 写入策略（随 Android 版本演进）：
 * - API 29+（Android 10+）：Scoped Storage 要求，用 MediaStore API 写入
 *   • 先 insert() 得到 Uri，设置 IS_PENDING=1
 *   • openOutputStream 写入图片
 *   • update() 设置 IS_PENDING=0（部分设备需要 WRITE_EXTERNAL_STORAGE，失败时降级忽略）
 * - API 26-28（Android 8-9）：直接 File API + WRITE_EXTERNAL_STORAGE 权限
 *
 * 读取方式：全部用 File API（baseDir 是我们自己创建的用户可见目录）。
 *
 * 权限要求：
 * - API 29+：MediaStore 写入不需要额外权限（系统处理）
 * - API 26-28：需要 WRITE_EXTERNAL_STORAGE 权限
 *
 * 优势：卸载 App 后照片保留，系统相册可见。
 */
class DcimPhotoStorage(private val context: Context) : IPhotoStorage {

    companion object {
        private const val TAG = "DcimPhotoStorage"
        private const val SUB_DIR = "TimeLapse"
    }

    /** DCIM/TimeLapse 目录的 File 引用，用于读取操作 */
    private val baseDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        SUB_DIR
    )

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    override suspend fun save(bitmap: Bitmap, timestamp: Long): String = withContext(Dispatchers.IO) {
        val monthDir = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(timestamp))
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date(timestamp)) + ".jpg"

        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(bitmap, fileName, monthDir)
        } else {
            saveViaFileApi(bitmap, fileName, monthDir)
        }
        invalidateListCache()
        path
    }

    override suspend fun saveTestPhoto(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        // 文件名格式与正式照片对齐（yyyyMMdd_HHmmss_SSS），保证相册字典序 = 时间序
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date(System.currentTimeMillis())) + ".jpg"
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveTestViaMediaStore(bitmap, fileName)
        } else {
            saveTestViaFileApi(bitmap, fileName)
        }
        invalidateListCache()
        path
    }

    // ──────────── API 29+：MediaStore 写入 ────────────

    /**
     * 通过 MediaStore 写入正式拍摄照片到 DCIM/TimeLapse/{yyyy-MM}/。
     *
     * 流程：insert(IS_PENDING=1) → compress → update(IS_PENDING=0) → 返回路径
     *
     * IS_PENDING=0 这一步部分 Android 11+ 设备需要 WRITE_EXTERNAL_STORAGE 权限，
     * 如果缺少权限只影响相册索引更新，不影响图片本身已写入。
     * 捕获异常后降级返回，避免整个拍摄流程中断。
     */
    private fun saveViaMediaStore(
        bitmap: Bitmap, fileName: String, monthDir: String
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$SUB_DIR/$monthDir")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore insert 失败")

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            } ?: throw IOException("打开输出流失败")
        } catch (e: Exception) {
            // 写入失败：删除孤儿记录，避免 IS_PENDING=1 的空条目遗留在相册（Android 10+ 保留至多 7 天）
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { LogBuffer.log("W", TAG, "孤儿记录清理失败: ${it.message}") }
            throw e
        }
        // bitmap 由 CaptureService 统一 recycle，此处不回收

        // IS_PENDING=0：通知媒体扫描器此文件可被系统相册索引
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        runCatching {
            context.contentResolver.update(uri, values, null, null)
        }.onFailure { e ->
            LogBuffer.log("W", TAG, "IS_PENDING 更新失败（相册可能延迟显示）: ${e.message}")
        }

        // 返回路径：优先取 DATA 列，失败则返回 Uri 字符串（调用方只需识别是成功路径）
        return getFilePathFromUri(uri) ?: uri.toString()
    }

    /**
     * 通过 MediaStore 写入试拍照片（文件名带毫秒时间戳，每次唯一，不复用旧 Uri）。
     */
    private fun saveTestViaMediaStore(bitmap: Bitmap, fileName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$SUB_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore insert 失败")

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            } ?: throw IOException("打开输出流失败")
        } catch (e: Exception) {
            // 写入失败：删除孤儿记录，避免 IS_PENDING=1 的空条目遗留在相册
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { LogBuffer.log("W", TAG, "孤儿记录清理失败（试拍）: ${it.message}") }
            throw e
        }
        // bitmap 由 CaptureService 统一 recycle

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        runCatching {
            context.contentResolver.update(uri, values, null, null)
        }.onFailure { e ->
            LogBuffer.log("W", TAG, "IS_PENDING 更新失败（试拍）: ${e.message}")
        }

        return getFilePathFromUri(uri) ?: uri.toString()
    }

    // ──────────── API 26-28：File API 写入 ────────────

    /**
     * API 26-28：直接用 File API 写入 DCIM，需要 WRITE_EXTERNAL_STORAGE 权限。
     */
    private fun saveViaFileApi(bitmap: Bitmap, fileName: String, monthDir: String): String {
        val dir = File(baseDir, monthDir).apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        // bitmap 由 CaptureService 统一 recycle
        return file.absolutePath
    }

    private fun saveTestViaFileApi(bitmap: Bitmap, fileName: String): String {
        val file = File(baseDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        // bitmap 由 CaptureService 统一 recycle
        return file.absolutePath
    }

    // ──────────── 辅助方法 ────────────

    /**
     * 从 MediaStore Uri 查询本地文件路径。
     * DATA 列在 Android 10+ 标记为废弃，但对自己创建的文件仍有效。
     */
    private fun getFilePathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    // ──────────── 读取操作：全部用 File API ────────────
    // Android 11+ 允许 App 用 File 路径访问自己通过 MediaStore 创建的文件。
    // Android 10 以下本来就支持直接访问 DCIM。

    override fun getPhotoCount(): Int =
        baseDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .count()

    override fun getPhotoDir(): File = baseDir

    override fun getLatestPhoto(): File? = allPhotosSortedDesc().firstOrNull()

    override fun getAllPhotos(): List<File> = allPhotosSortedDesc()

    override fun getPhotosPaged(offset: Int, limit: Int): List<File> =
        allPhotosSortedDesc().drop(offset).take(limit)

    /**
     * 排序结果缓存：全目录 walk + sort 每次是 O(N log N)，
     * 相册页每次分页都调用会随照片数量线性劣化（数万张时明显卡顿）。
     * 缓存一份排序结果，写入/删除后失效。
     */
    @Volatile private var sortedCache: List<File>? = null

    override fun invalidateListCache() {
        sortedCache = null
    }

    private fun allPhotosSortedDesc(): List<File> =
        sortedCache ?: baseDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .sortedByDescending { it.name }
            .toList()
            .also { sortedCache = it }
}
