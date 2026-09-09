package com.timelapse.camera.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timelapse.camera.config.RuntimeState
import com.timelapse.camera.service.WatchdogService

/**
 * 开机自启接收器 —— 手机重启后恢复拍摄服务。
 *
 * 设计说明：
 * - Android 14 (API 34)+ 禁止在 BOOT_COMPLETED 广播中直接启动 camera 类型前台服务，
 *   会抛 ForegroundServiceStartNotAllowedException
 * - 因此只启动 WatchdogService（specialUse 类型，不在豁免黑名单中），由它负责后续的
 *   拍摄服务恢复流程：检查 isRunning → 设闹钟唤醒 CaptureReceiver → 拉起 CaptureService
 * - 若闹钟路径也失败（Android 12+ 后台 FGS 限制），Watchdog 的 60s 检查循环会持续尝试，
 *   最终依赖用户下次前台操作手动恢复
 *
 * 教学要点：
 * - BOOT_COMPLETED 广播有严格的后台服务启动限制，只能启动不受 while-in-use 约束的 FGS 类型
 * - 多进程守护架构（WatchdogService）天然解决了这个限制：独立进程 + specialUse 豁免
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val runtime = RuntimeState.load(context)
        if (!runtime.isRunning) return

        // 只启动 WatchdogService：它检测主服务状态后自行决定是否需要闹钟唤醒 CaptureService
        context.startService(Intent(context, WatchdogService::class.java))
    }
}
