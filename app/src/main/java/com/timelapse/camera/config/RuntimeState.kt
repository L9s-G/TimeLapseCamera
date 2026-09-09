package com.timelapse.camera.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 运行时状态 —— 服务/Watchdog/UI 内部自维护，与用户可编辑配置（CaptureConfig）分离。
 *
 * 设计原则：
 * - 与 CaptureConfig 共用同一 SharedPreferences 文件（timelapse_config），字段不重叠
 * - 写入者隔离：用户设置页只写 CaptureConfig，运行时状态只写 RuntimeState 的局部更新方法
 * - 每个 update* 方法只写自己的 key，避免全量 save 的丢失更新竞态
 *
 * 字段：
 * - isRunning: 拍摄是否正在运行（StatusFragment 切换按钮 / Service / Watchdog 读）
 * - isCleaning: FIFO 清理是否进行中（CaptureService 跨轮保持）
 * - captureCount: 已拍摄张数（CaptureService 每次拍摄后局部更新）
 * - lastCaptureTime: 上次成功拍摄时间戳（服务重启判定 + 闹钟对齐）
 * - lastRemoteInterval: 上次有效远程间隔（远程失败时回退使用）
 */
data class RuntimeState(
    val isRunning: Boolean = false,
    val isCleaning: Boolean = false,
    val captureCount: Int = 0,
    val lastCaptureTime: Long = 0,
    val lastRemoteInterval: Int = 0
) {
    companion object {
        private const val PREFS_NAME = "timelapse_config"

        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_IS_CLEANING = "is_cleaning"
        private const val KEY_CAPTURE_COUNT = "capture_count"
        private const val KEY_LAST_CAPTURE_TIME = "last_capture_time"
        private const val KEY_LAST_REMOTE_INTERVAL = "last_remote_interval"

        @Volatile private var prefsInstance: SharedPreferences? = null

        private fun prefs(context: Context): SharedPreferences =
            prefsInstance ?: synchronized(this) {
                prefsInstance ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .also { prefsInstance = it }
            }

        fun load(context: Context): RuntimeState {
            val prefs = prefs(context)
            return RuntimeState(
                isRunning = prefs.getBoolean(KEY_IS_RUNNING, false),
                isCleaning = prefs.getBoolean(KEY_IS_CLEANING, false),
                captureCount = prefs.getInt(KEY_CAPTURE_COUNT, 0),
                lastCaptureTime = prefs.getLong(KEY_LAST_CAPTURE_TIME, 0),
                lastRemoteInterval = prefs.getInt(KEY_LAST_REMOTE_INTERVAL, 0)
            )
        }

        /** 局部更新拍摄运行状态（StatusFragment 开始/停止按钮） */
        fun updateRunning(context: Context, isRunning: Boolean) {
            prefs(context).edit()
                .putBoolean(KEY_IS_RUNNING, isRunning)
                .apply()
        }

        /** 局部更新 FIFO 清理状态（CaptureService 跨轮保持） */
        fun updateCleaning(context: Context, isCleaning: Boolean) {
            prefs(context).edit()
                .putBoolean(KEY_IS_CLEANING, isCleaning)
                .apply()
        }

        /** 局部更新拍摄进度（captureCount + lastCaptureTime） */
        fun updateCaptureProgress(context: Context, captureCount: Int, lastCaptureTime: Long) {
            prefs(context).edit()
                .putInt(KEY_CAPTURE_COUNT, captureCount)
                .putLong(KEY_LAST_CAPTURE_TIME, lastCaptureTime)
                .apply()
        }

        /** 局部更新远程配置下发的间隔值 */
        fun updateRemoteInterval(context: Context, remoteInterval: Int) {
            prefs(context).edit()
                .putInt(KEY_LAST_REMOTE_INTERVAL, remoteInterval)
                .apply()
        }
    }
}
