package com.timelapse.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.timelapse.camera.util.LogBuffer

/**
 * 枚举设备所有摄像头，输出详细信息到日志。
 *
 * 为什么不用 CameraX 枚举？
 * - CameraX 的 CameraSelector 只有 BACK/FRONT 两个粗粒度选项
 * - 多镜头手机（主摄+超广角+长焦+微距）需要底层 Camera2 API 才能看到全部
 * - 教学价值：展示 CameraX 之下的真实硬件世界
 *
 * 输出信息：
 * - cameraId：摄像头 ID（"0", "1", "2"…）
 * - facing：后置/前置/外接
 * - sensorSize：传感器像素尺寸（宽x高）
 * - focalLengths：焦距数组（mm），区分主摄/超广角/长焦
 *
 * 教学要点：
 * - CameraManager.getCameraIdList() 不需要相机权限
 * - getCameraCharacteristics() 也不需要权限
 * - 只有打开摄像头（openCamera）才需要 CAMERA 运行时权限
 */
object CameraEnumerator {

    private const val TAG = "CameraEnumerator"

    data class CameraInfo(
        val cameraId: String,
        val facing: Int,
        val facingName: String,
        val sensorSize: Size?,
        val focalLengths: FloatArray?
    ) {
        val megapixels: String
            get() = sensorSize?.let {
                "%.1fMP".format((it.width * it.height) / 1_000_000f)
            } ?: "未知"

        val focalLengthText: String
            get() = focalLengths?.joinToString(", ") { "%.2fmm".format(it) } ?: "未知"
    }

    /**
     * 枚举所有摄像头并打印到 LogBuffer。
     * @return 摄像头信息列表
     */
    fun enumerate(context: Context): List<CameraInfo> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList

        val result = mutableListOf<CameraInfo>()

        for (id in cameraIds) {
            runCatching {
                val chars = cameraManager.getCameraCharacteristics(id)

                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_BACK
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                    else -> "未知($facing)"
                }

                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

                val info = CameraInfo(id, facing, facingName, sensorSize, focalLengths)
                result.add(info)
                LogBuffer.log("I", TAG, "[$id] $facingName | ${info.megapixels} | 传感器: ${sensorSize?.let { "${it.width}x${it.height}" } ?: "未知"} | 焦距: ${info.focalLengthText}")
            }.onFailure { e ->
                LogBuffer.log("E", TAG, "读取摄像头 $id 失败: ${e.message}")
            }
        }

        return result
    }

    /**
     * 找到后置摄像头中像素最高的那颗（通常是主摄）。
     * 用于默认选择，避免 CameraX 选到超广角。
     */
    fun findBestBackCamera(context: Context): CameraInfo? {
        val cameras = enumerate(context)
        return cameras
            .filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .maxByOrNull { (it.sensorSize?.width ?: 0) * (it.sensorSize?.height ?: 0) }
    }
}
