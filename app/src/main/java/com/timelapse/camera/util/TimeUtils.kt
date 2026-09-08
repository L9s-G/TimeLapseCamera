package com.timelapse.camera.util

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间显示工具：将 elapsedRealtime 值近似换算为可读日历时间。
 *
 * 仅用于日志 / 通知 / UI 等展示场景。
 * 内部调度、delay、闹钟触发等逻辑仍一律使用 elapsedRealtime，不经过本类。
 *
 * 精度说明：
 * 换算依赖"采样瞬间 offset = currentTimeMillis - elapsedRealtime"，
 * NTP 校时或用户改时间会引入漂移，所以结果只是"约等于"，供人类阅读参考。
 * 禁止用于任何判断或调度逻辑。
 */
object TimeUtils {

    /**
     * 将 elapsedRealtime 毫秒值近似换算为日历时间（epoch millis）。
     *
     * @param elapsedRealtimeMillis 来自 SystemClock.elapsedRealtime() 的值
     * @return 近似的 epoch millis（受 NTP / 用户改时间影响，仅供参考）
     */
    fun elapsedRealtimeToWallMillis(elapsedRealtimeMillis: Long): Long {
        val offset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return elapsedRealtimeMillis + offset
    }

    /**
     * 将 epoch millis 格式化为本地 "HH:mm:ss"。
     *
     * 每次调用新建 SimpleDateFormat 实例（该对象非线程安全）。
     */
    fun formatWallTime(epochMillis: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }

    /**
     * 一步到位：elapsedRealtime → 可读 "HH:mm:ss"。
     * 日志场景最常用。
     */
    fun formatElapsedRealtime(elapsedRealtimeMillis: Long): String {
        return formatWallTime(elapsedRealtimeToWallMillis(elapsedRealtimeMillis))
    }
}
