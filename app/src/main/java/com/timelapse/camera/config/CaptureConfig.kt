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
 * 拍摄配置 —— 所有可调参数集中在此，通过 SharedPreferences 持久化。
 *
 * 教学要点：
 * - 用 data class + copy() 实现不可变配置，避免运行期被意外修改
 * - 每个字段都有默认值，首次运行不会崩
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
    /** 拍摄是否正在运行 */
    val isRunning: Boolean = false,
    /** 已拍摄张数 */
    val captureCount: Int = 0,
    /** 上次有效远程间隔（远程失败时回退使用） */
    val lastRemoteInterval: Int = 0,
    /** 上次成功拍摄的时间戳，服务重启时用于判断启动来源 */
    val lastCaptureTime: Long = 0,
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
    val storageSafeLineGb: Float = 2.0f,
    /** FIFO 清理是否进行中（跨轮保持，防止阈值反弹后丢失续删任务） */
    val isCleaning: Boolean = false
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
            putBoolean(KEY_IS_RUNNING, isRunning)
            putInt(KEY_CAPTURE_COUNT, captureCount)
            putInt(KEY_LAST_REMOTE_INTERVAL, lastRemoteInterval)
            putLong(KEY_LAST_CAPTURE_TIME, lastCaptureTime)
            putString(KEY_STORAGE_LOCATION, storageLocation.name)
            putInt(KEY_SHOT_ROTATION, shotRotation)
            putFloat(KEY_STORAGE_THRESHOLD, storageThresholdGb)
            putFloat(KEY_STORAGE_SAFE_LINE, storageSafeLineGb)
            putBoolean(KEY_IS_CLEANING, isCleaning)
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
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_CAPTURE_COUNT = "capture_count"
        private const val KEY_LAST_REMOTE_INTERVAL = "last_remote_interval"
        private const val KEY_LAST_CAPTURE_TIME = "last_capture_time"
        private const val KEY_STORAGE_LOCATION = "storage_location"
        private const val KEY_SHOT_ROTATION = "shot_rotation"
        private const val KEY_STORAGE_THRESHOLD = "storage_threshold_gb"
        private const val KEY_STORAGE_SAFE_LINE = "storage_safe_line_gb"
        private const val KEY_IS_CLEANING = "is_cleaning"

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
                isRunning = prefs.getBoolean(KEY_IS_RUNNING, false),
                captureCount = prefs.getInt(KEY_CAPTURE_COUNT, 0),
                lastRemoteInterval = prefs.getInt(KEY_LAST_REMOTE_INTERVAL, 0),
                lastCaptureTime = prefs.getLong(KEY_LAST_CAPTURE_TIME, 0),
                storageLocation = StorageLocation.fromName(prefs.getString(KEY_STORAGE_LOCATION, null)),
                shotRotation = prefs.getInt(KEY_SHOT_ROTATION, Surface.ROTATION_90),
                storageThresholdGb = prefs.getFloat(KEY_STORAGE_THRESHOLD, 1.0f),
                storageSafeLineGb = prefs.getFloat(KEY_STORAGE_SAFE_LINE, 2.0f),
                isCleaning = prefs.getBoolean(KEY_IS_CLEANING, false)
            )
        }

        /**
         * 局部更新拍摄进度（captureCount + lastCaptureTime）。
         *
         * 为什么不用 save() 全量保存？
         * - captureLoop 拍摄耗时数秒，期间用户可能在设置页修改配置
         * - 全量 save 会用拍摄开始时读到的旧配置覆盖用户刚保存的新值（丢失更新竞态）
         * - 局部更新只写自己的 key，与设置页的写入互不干扰
         */
        fun updateCaptureProgress(context: Context, captureCount: Int, lastCaptureTime: Long) {
            prefs(context).edit()
                .putInt(KEY_CAPTURE_COUNT, captureCount)
                .putLong(KEY_LAST_CAPTURE_TIME, lastCaptureTime)
                .apply()
        }

        /**
         * 局部更新远程配置下发的间隔值（避免全量 save 的丢失更新竞态）。
         */
        fun updateRemoteInterval(context: Context, remoteInterval: Int) {
            prefs(context).edit()
                .putInt(KEY_LAST_REMOTE_INTERVAL, remoteInterval)
                .apply()
        }

        /**
         * 局部更新 FIFO 清理状态（避免全量 save 的丢失更新竞态）。
         * 跨轮保持：防止阈值反弹后丢失续删任务。
         */
        fun updateCleaningState(context: Context, isCleaning: Boolean) {
            prefs(context).edit()
                .putBoolean(KEY_IS_CLEANING, isCleaning)
                .apply()
        }
    }
}
