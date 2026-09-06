package com.timelapse.camera.util

import android.content.Context
import android.graphics.Bitmap
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.model.CaptureResult
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.watermark.WatermarkOptions
import com.timelapse.camera.watermark.WatermarkProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 水印处理流水线 —— 封装 "判断水印开关 → 构建选项 → 应用水印/生成错误图" 流程
 *
 * 设计边界：
 * - 负责：纯数据转换逻辑（无线程/生命周期依赖）
 * - 不负责：拍照（由 camera.capture() 完成）、保存（调用方选择 save/saveTestPhoto）、回收（调用方负责）
 *
 * 消除 PreviewFragment 和 CaptureService 之间的水印逻辑重复，确保两条路径行为一致。
 */
object WatermarkPipeline {

    private val watermarkProcessor = WatermarkProcessor()

    /**
     * 处理拍摄结果：判断是否需要水印，返回带水印的 Bitmap 或错误黑图。
     *
     * 内存设计（教学要点）：
     * - Success 且有水印：调用 WatermarkProcessor.apply() 在 result.bitmap 上**原地绘制**，
     *   不创建副本，零额外内存开销。result.bitmap 即返回对象，调用方 recycle 即可。
     * - Success 且无水印：直接返回 result.bitmap，不调用 apply()。
     * - Failure：调用 createErrorBitmap() 创建新的 1280x720 错误图（约 3.5MB），与原始 bitmap 无关。
     *
     * 调用方负责 recycle 返回值（无论 Success 还是 Failure 路径）。
     *
     * @param config 拍摄配置
     * @param storage 存储服务（用于查询剩余空间）
     * @param context Android Context，用于读取电量/温度
     * @param result 拍摄结果
     * @return 处理后的 Bitmap；失败时返回错误黑图
     */
    suspend fun process(
        config: CaptureConfig,
        storage: IPhotoStorage,
        context: Context,
        result: CaptureResult
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            val hasWatermark = !config.watermarkText.isNullOrBlank() ||
                    config.watermarkShowBattery ||
                    config.watermarkShowStorage ||
                    config.watermarkShowTemperature

            when (result) {
                is CaptureResult.Success -> {
                    if (hasWatermark) {
                        LogBuffer.log("I", TAG, "开始水印处理")
                        val options = buildWatermarkOptions(config, storage, context)
                        watermarkProcessor.apply(result.bitmap, result.timestamp, options)
                    } else {
                        LogBuffer.log("I", TAG, "水印全关，跳过处理")
                        result.bitmap
                    }
                }
                is CaptureResult.Failure -> {
                    LogBuffer.log("E", TAG, "拍摄失败: ${result.message}")
                    watermarkProcessor.createErrorBitmap(System.currentTimeMillis())
                }
            }
        }
    }

    private fun buildWatermarkOptions(
        config: CaptureConfig,
        storage: IPhotoStorage,
        context: Context
    ): WatermarkOptions = WatermarkOptions(
        customText = config.watermarkText,
        showBattery = config.watermarkShowBattery,
        showStorage = config.watermarkShowStorage,
        showTemperature = config.watermarkShowTemperature,
        batteryPercent = BatteryMonitor.getBatteryPercent(context),
        storageRemainingGb = BatteryMonitor.getStorageRemainingGb(storage.getPhotoDir()),
        temperatureCelsius = BatteryMonitor.getBatteryTemperature(context)
    )

    private const val TAG = "WatermarkPipeline"
}
