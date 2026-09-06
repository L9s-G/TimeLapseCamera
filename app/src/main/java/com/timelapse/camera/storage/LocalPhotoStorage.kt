package com.timelapse.camera.storage

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地照片存储实现（App 私有目录方式）。
 *
 * 目录结构：
 *   {app外部文件目录}/Pictures/TimeLapse/
 *     ├── 2026-03/
 *     │   ├── 20260301_080000.jpg
 *     │   └── ...
 *     └── 2026-04/
 *
 * 教学要点：
 * - 用 getExternalFilesDir() 存储，无需申请存储权限（API 26+ 友好）
 * - 按年月分目录，方便后续 FIFO 清理或导出
 * - 保存操作在 IO 线程执行，避免阻塞主线程
 * - save() 是 Bitmap 内存责任链的终点：compress 后立即 recycle
 *
 * 复用设计：SD 卡存储也用本类，只需传入 SD 卡的私有目录作为 baseDir。
 * 两者都是 App 私有目录，权限和写入方式完全一样，无需 MediaStore。
 */
class LocalPhotoStorage(
    context: Context,
    private val customBaseDir: File? = null
) : IPhotoStorage {

    companion object {
        private const val TAG = "LocalPhotoStorage"
    }

    private val baseDir = (customBaseDir ?: File(
        // getExternalFilesDir 理论上可返回 null（存储卷被移除），兜底到内部 filesDir
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir,
        "TimeLapse"
    )).also { if (!it.exists()) it.mkdirs() }

    override suspend fun save(bitmap: Bitmap, timestamp: Long): String = withContext(Dispatchers.IO) {
        val monthDir = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            .format(Date(timestamp))
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date(timestamp)) + ".jpg"

        val dir = File(baseDir, monthDir).apply { mkdirs() }
        val file = File(dir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        // 不在 save() 内 recycle，由调用方（CaptureService）统一管理 Bitmap 生命周期
        invalidateListCache()
        file.absolutePath
    }

    override suspend fun saveTestPhoto(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        // 文件名格式与正式照片对齐（yyyyMMdd_HHmmss_SSS），保证相册字典序 = 时间序
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date(System.currentTimeMillis())) + ".jpg"
        val file = File(baseDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        // 不在 save() 内 recycle，由调用方（CaptureService）统一管理 Bitmap 生命周期
        invalidateListCache()
        file.absolutePath
    }

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
