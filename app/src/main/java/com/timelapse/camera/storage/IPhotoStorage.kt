package com.timelapse.camera.storage

import android.graphics.Bitmap
import com.timelapse.camera.util.BatteryMonitor
import com.timelapse.camera.util.LogBuffer
import java.io.File

/**
 * 照片存储接口 —— 存储策略抽象。
 *
 * 设计意图（模块插拔）：
 * - LocalPhotoStorage: 本地存储实现（App 私有目录 / SD 卡）
 * - DcimPhotoStorage: DCIM 公共目录存储实现
 * - cleanupOldPhotos(): FIFO 清理，默认实现基于 File API
 *
 * 调用方（CaptureService）只依赖此接口，不关心底层存储细节。
 */
interface IPhotoStorage {

    /**
     * 保存照片到存储。
     * @param bitmap 带水印的照片
     * @param timestamp 拍摄时间戳，用于命名和归档
     * @return 保存后的文件路径
     */
    suspend fun save(bitmap: Bitmap, timestamp: Long): String

    /**
     * 保存试拍照片（文件名带毫秒时间戳，格式 yyyyMMdd_HHmmss_SSS.jpg，每次不覆盖）。
     * @param bitmap 带水印的试拍照片
     * @return 保存后的文件路径
     */
    suspend fun saveTestPhoto(bitmap: Bitmap): String

    /** 已存储照片数量 */
    fun getPhotoCount(): Int

    /** 照片根目录（便于 FIFO 清理或导出） */
    fun getPhotoDir(): File

    /** 获取最近一张照片（按文件名排序，最新的在前），没有则返回 null */
    fun getLatestPhoto(): File?

    /** 获取所有照片列表（按时间倒序） */
    fun getAllPhotos(): List<File>

    /**
     * 分页查询照片（按时间倒序），用于懒加载。
     * @param offset 跳过前 offset 条（分页游标）
     * @param limit  每批最多返回条数
     * @return 本页照片列表（可能少于 limit，表示已到最后）
     */
    fun getPhotosPaged(offset: Int, limit: Int): List<File>

    /**
     * 使照片列表缓存失效。写操作（save/saveTestPhoto）后由实现自行调用；
     * FIFO 清理删除文件后由 cleanupOldPhotos 默认实现调用。
     * 默认空实现：不使用缓存的存储类无需关心。
     */
    fun invalidateListCache() {}

    /**
     * FIFO 清理旧照片：按时间从旧到新删除，单轮最多删 maxDeleteCount 个。
     *
     * 管线模式：调用方传入 initialRemainingGb（入口防御），方法返回 CleanupResult（出口空间值）。
     * 方法内部 StatFs 仅 1 次（出口），入口用传入值判断，零系统调用。
     *
     * 算法：
     * 1. initialRemainingGb >= safeLine → 跳过（调用方已保证进入时 < safeLine，此层为参数合法性防御）
     * 2. 从最旧月份目录开始收集候选文件，凑够 maxDeleteCount 即停收集
     * 3. 批量删除（不穿插 StatFs）
     * 4. 出口 1 次 StatFs，返回 CleanupResult
     *
     * 空文件夹策略（tradeoff）：
     * - 故意不删除空的月份目录。原因：
     *   a) 空文件夹不占存储空间，清理收益为零
     *   b) 其他应用（系统相册、备份工具等）可能向同一目录写入文件，
     *      本 App 无法区分"我刚删完导致目录变空"和"目录本来就被其他应用写入过"，
     *      贸然 folder.delete() 有误删风险
     *   c) 保留月份目录作为"该月曾拍过照"的记录，对用户有信息价值
     *
     * 日志格式（紧凑）：
     * - FIFO未完成[📀X.XG | 📂N]   本批未达标，下轮续删（N=本轮扫描候选数）
     * - 删除失败[📀X.XG | 📂N]    本批 0 删除（N=0 无文件 / N>0 权限不足）
     * - "FIFO开始 / FIFO结束"由调用方在 isCleaning 切换轮打印（用 📷=全目录总数）
     *
     * @param safeLineGb 清理目标安全线（GB）
     * @param maxDeleteCount 单轮最多删除的文件数（批大小）
     * @param initialRemainingGb 调用方传入的当前可用空间（GB），用于入口防御，避免方法内多查一次 StatFs
     * @return CleanupResult（deleted / remainingGb / scannedCount）
     */
    fun cleanupOldPhotos(
        safeLineGb: Float,
        maxDeleteCount: Int = 20,
        initialRemainingGb: Float
    ): CleanupResult {
        if (initialRemainingGb >= safeLineGb) {
            return CleanupResult(0, initialRemainingGb, 0)
        }

        val photoDir = getPhotoDir()

        // ① 收集候选文件（从最旧月开始，凑够 maxDeleteCount 即停）
        val monthFolders = photoDir.listFiles()?.filter { it.isDirectory }
            ?.sortedBy { it.name } ?: return CleanupResult(0, initialRemainingGb, 0)

        val allFiles = mutableListOf<File>()
        var scannedCount = 0
        for (folder in monthFolders) {
            if (allFiles.size >= maxDeleteCount) break
            val files = folder.listFiles()
                ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                ?.sortedBy { it.name } ?: continue
            scannedCount += files.size
            allFiles += files
        }

        // ② 批量删除（不穿插 StatFs）
        var deleted = 0
        for (file in allFiles.take(maxDeleteCount)) {
            if (file.delete()) deleted++
        }

        // ③ 出口：唯一 1 次 StatFs + 日志
        val finalRemaining = BatteryMonitor.getStorageRemainingGb(photoDir)
        if (deleted > 0) {
            invalidateListCache()
            if (finalRemaining < safeLineGb) {
                LogBuffer.log("W", "Storage",
                    "FIFO未完成[📀${String.format("%.1f", finalRemaining)}G | 📂$scannedCount]")
            }
        } else {
            LogBuffer.log("W", "Storage",
                "删除失败[📀${String.format("%.1f", finalRemaining)}G | 📂$scannedCount]")
        }
        return CleanupResult(deleted, finalRemaining, scannedCount)
    }

    /**
     * FIFO 清理结果。
     * @param deleted 本批实际删除的文件数
     * @param remainingGb 删除后的可用空间（GB），供调用方判定 isCleaning 切换
     * @param scannedCount 本轮扫描到的候选文件数（仅统计实际 listFiles 过的目录）
     */
    data class CleanupResult(
        val deleted: Int,
        val remainingGb: Float,
        val scannedCount: Int
    )
}
