package com.timelapse.camera.config

import android.content.Context
import android.content.SharedPreferences
import android.view.Surface

/**
 * 存储位置枚举。
 *
 * 教学要点：用枚举而非字符串，避免拼写错误导致运行时崩溃。
 * - APP_PRIVATE: App 私有目录，无需权限，卸载后照片删除
 * - DCIM: 系统相册公共目录，卸载后照片保留，系统相册可见
 * - SD_CARD: SD 卡 App 私有目录，无需权限，卸载后照片删除
 */
enum class StorageLocation {
    APP_PRIVATE,
    DCIM,
    SD_CARD;

    companion object {
        fun fromName(name: String?): StorageLocation =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: APP_PRIVATE
    }
}

/**
 * 用户可编辑拍摄配置 —— 所有可调参数集中在此，通过 SharedPreferences 持久化。
 *
 * 写入者：仅 SettingsFragment（用户修改设置时调用 save()）。
 * 运行时状态（isRunning / isCleaning / captureCount 等）已分离至 RuntimeState。
 *
 * 教学要点：
 * - 用 data class + copy() 实现不可变配置，避免运行期被意外修改
 * - 每个字段都有默认值，首次运行不会崩
 * - save() 只写用户设置字段，不碰 runtime key，杜绝竞态
 */
data class CaptureConfig(
    /** 拍摄间隔（秒），远程配置可动态覆盖此值 */
    val intervalSeconds: Int = 30,
    /** 选中的摄像头 ID，精确到具体镜头（"0", "1", "2"…），而非粗粒度的前/后 */
    val cameraId: String = "0",
    /** 自定义水印文字，null 表示仅显示时间戳 */
    val watermarkText: String? = null,
    /** 水印是否显示电量 */
    val watermarkShowBattery: Boolean = true,
    /** 水印是否显示剩余存储 */
    val watermarkShowStorage: Boolean = true,
    /** 水印是否显示电池温度 */
    val watermarkShowTemperature: Boolean = true,
    /** 远程配置 URL，返回 15-3600 整数作为下次拍摄延迟，null 表示不使用远程配置 */
    val remoteConfigUrl: String? = null,
    /** 照片存储位置 */
    val storageLocation: StorageLocation = StorageLocation.APP_PRIVATE,
    /**
     * 拍摄方向：内部直接使用 Surface.ROTATION_* 枚举常量（0/1/2/3），
     * 与 setTargetRotation() 的值域一致，无需任何转换。
     * 度数只存在于设置页的显示文案（人类阅读），代码中不出现。
     */
    val shotRotation: Int = Surface.ROTATION_90,
    /** FIFO 清理阈值（GB）：剩余空间低于此值时触发清理 */
    val storageThresholdGb: Float = 1.0f,
    /** FIFO 清理安全线（GB）：清理到此值停止 */
    val storageSafeLineGb: Float = 2.0f
) {
    fun save(context: Context) {
        prefs(context).edit().apply {
            putInt(KEY_INTERVAL, intervalSeconds)
            putString(KEY_CAMERA_ID, cameraId)
            putString(KEY_WATERMARK, watermarkText)
            putBoolean(KEY_WATERMARK_BATTERY, watermarkShowBattery)
            putBoolean(KEY_WATERMARK_STORAGE, watermarkShowStorage)
            putBoolean(KEY_WATERMARK_TEMP, watermarkShowTemperature)
            putString(KEY_REMOTE_URL, remoteConfigUrl)
            putString(KEY_STORAGE_LOCATION, storageLocation.name)
            putInt(KEY_SHOT_ROTATION, shotRotation)
            putFloat(KEY_STORAGE_THRESHOLD, storageThresholdGb)
            putFloat(KEY_STORAGE_SAFE_LINE, storageSafeLineGb)
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "timelapse_config"
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_WATERMARK = "watermark_text"
        private const val KEY_WATERMARK_BATTERY = "watermark_battery"
        private const val KEY_WATERMARK_STORAGE = "watermark_storage"
        private const val KEY_WATERMARK_TEMP = "watermark_temperature"
        private const val KEY_REMOTE_URL = "remote_config_url"
        private const val KEY_STORAGE_LOCATION = "storage_location"
        private const val KEY_SHOT_ROTATION = "shot_rotation"
        private const val KEY_STORAGE_THRESHOLD = "storage_threshold_gb"
        private const val KEY_STORAGE_SAFE_LINE = "storage_safe_line_gb"

        @Volatile private var prefsInstance: SharedPreferences? = null

        private fun prefs(context: Context): SharedPreferences =
            prefsInstance ?: synchronized(this) {
                prefsInstance ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .also { prefsInstance = it }
            }

        fun load(context: Context): CaptureConfig {
            val prefs = prefs(context)
            return CaptureConfig(
                intervalSeconds = prefs.getInt(KEY_INTERVAL, 30),
                cameraId = prefs.getString(KEY_CAMERA_ID, "0") ?: "0",
                watermarkText = prefs.getString(KEY_WATERMARK, null),
                watermarkShowBattery = prefs.getBoolean(KEY_WATERMARK_BATTERY, true),
                watermarkShowStorage = prefs.getBoolean(KEY_WATERMARK_STORAGE, true),
                watermarkShowTemperature = prefs.getBoolean(KEY_WATERMARK_TEMP, true),
                remoteConfigUrl = prefs.getString(KEY_REMOTE_URL, null),
                storageLocation = StorageLocation.fromName(prefs.getString(KEY_STORAGE_LOCATION, null)),
                shotRotation = prefs.getInt(KEY_SHOT_ROTATION, Surface.ROTATION_90),
                storageThresholdGb = prefs.getFloat(KEY_STORAGE_THRESHOLD, 1.0f),
                storageSafeLineGb = prefs.getFloat(KEY_STORAGE_SAFE_LINE, 2.0f)
            )
        }
    }
}
