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
     * FIFO 清理旧照片：剩余空间低于阈值时，按时间从旧到新删除，直到达到安全线。
     *
     * 算法：
     * 1. 检测剩余空间 >= 阈值 → 跳过
     * 2. 列出月份文件夹（字典序 = 时间序，最旧在前）
     * 3. 进入最旧文件夹，列出文件（字典序 = 时间序，最旧在前）
     * 4. 逐个删除，每删一个检测是否达到安全线
     * 5. 单轮最多删 maxDeleteCount 个，防止清理耗时过长影响拍摄节奏
     * 6. 空文件夹自动删除
     *
     * 默认实现基于 File API 删除文件，适用于 LocalPhotoStorage。
     * 对于 DCIM 场景：App 对自己通过 MediaStore 写入的文件，
     * 可以直接用 File API 删除（Android 11+ 允许 App 访问自己创建的文件）。
     * 如果未来需要扩展支持清理系统相册中其他 App 写入的文件，才需要考虑 MediaStore 方案。
     *
     * @param thresholdGb 触发清理的剩余空间阈值（GB）
     * @param safeLineGb 清理目标安全线（GB）
     * @param maxDeleteCount 单轮最多删除的文件数
     * @return 实际删除的文件数
     */
    fun cleanupOldPhotos(
        thresholdGb: Float,
        safeLineGb: Float,
        maxDeleteCount: Int = 20
    ): Int {
        val photoDir = getPhotoDir()
        var remaining = BatteryMonitor.getStorageRemainingGb(photoDir)
        if (remaining >= thresholdGb) return 0

        var deleted = 0
        val monthFolders = photoDir.listFiles()?.filter { it.isDirectory }
            ?.sortedBy { it.name } ?: run {
            LogBuffer.log("W", "Storage", "无照片文件夹可清理")
            return 0
        }

        for (folder in monthFolders) {
            if (deleted >= maxDeleteCount) break
            remaining = BatteryMonitor.getStorageRemainingGb(photoDir)
            if (remaining >= safeLineGb) break

            val files = folder.listFiles()
                ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                ?.sortedBy { it.name } ?: continue

            for (file in files) {
                if (deleted >= maxDeleteCount) break
                remaining = BatteryMonitor.getStorageRemainingGb(photoDir)
                if (remaining >= safeLineGb) break

                if (file.delete()) {
                    deleted++
                } else {
                    LogBuffer.log("W", "Storage", "删除失败: ${file.name}")
                }
            }

            if (folder.listFiles()?.isEmpty() == true) {
                folder.delete()
            }
        }

        if (deleted > 0) {
            LogBuffer.log("I", "Storage", "FIFO 清理: 删除 $deleted 个文件, 剩余 ${BatteryMonitor.getStorageRemainingGb(photoDir)}GB")
            if (BatteryMonitor.getStorageRemainingGb(photoDir) < safeLineGb && deleted >= maxDeleteCount) {
                LogBuffer.log("W", "Storage", "本轮清理未完成，下轮继续")
            }
            // 删除了文件，通知实现失效列表缓存（相册页可能正在分页浏览）
            invalidateListCache()
        }
        if (deleted == 0 && remaining < thresholdGb) {
            LogBuffer.log("W", "Storage", "存储不足但无可删文件")
        }
        return deleted
    }
}
