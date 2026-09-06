package com.timelapse.camera.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import java.io.File

/**
 * 电池与存储状态读取工具。
 *
 * 所有读取都是即时的、同步的，不需要权限（API 26+ 直接可用）。
 * 设计为 object 单例，因为这些都是系统级只读信息，不需要维护状态。
 */
object BatteryMonitor {

    /** 读取当前电量百分比（0-100） */
    fun getBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * 读取电池温度（摄氏度）。
     *
     * Android 没有 BATTERY_PROPERTY_TEMPERATURE 常量，温度只能通过
     * ACTION_BATTERY_CHANGED 这个 sticky broadcast intent 读取。
     * 返回值单位为 0.1°C，需除以 10。
     *
     * 注意：传入 null receiver 是获取 sticky broadcast 的标准写法——
     * 系统会返回最后一次已发出的广播内容，不需要也不应该手动 unregisterReceiver，
     * 否则会在下次电池事件时抛出 IllegalStateException。
     */
    fun getBatteryTemperature(context: Context): Float {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return raw / 10f
    }

    /**
     * 读取指定存储目录所在分区的剩余空间（GB）。
     *
     * 设计要点：
     * - 接收 storageDir 参数而非 Context，避免工具类对存储路径做假设
     * - 调用方传入实际的照片存储目录，确保读取的分区与写入一致
     * - StatFs 读取的是整个分区的可用空间，不是单个目录的配额
     */
    fun getStorageRemainingGb(storageDir: File): Float {
        val statFs = StatFs(storageDir.absolutePath)
        val availableBytes = statFs.availableBytes
        return availableBytes / (1024f * 1024f * 1024f)
    }
}
