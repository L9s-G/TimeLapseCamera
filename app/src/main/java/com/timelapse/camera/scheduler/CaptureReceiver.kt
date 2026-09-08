package com.timelapse.camera.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.timelapse.camera.service.CaptureService
import com.timelapse.camera.util.LogBuffer

/**
 * 闹钟备份接收器 —— AlarmManager 备份闹钟到期时触发，重启拍摄服务。
 *
 * 使用场景：持久化前台服务被系统杀死后，AlarmManager 备份闹钟到期，
 * 通过此接收器重启服务。如果服务仍在运行，onStartCommand 中的
 * captureJob 活跃检查会跳过重复启动。
 *
 * 教学要点：
 * - BroadcastReceiver 生命周期极短，不能执行耗时操作
 * - 此接收器只负责"转发"：启动前台服务，由服务完成实际工作
 * - 日志初始化由 CaptureService.onCreate() 统一负责，此处不做任何 I/O
 */
class CaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CaptureScheduler.ACTION_TRIGGER_CAPTURE) return

        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Android 12+ 后台 FGS 启动限制：setExactAndAllowWhileIdle 触发的广播
            // 不在豁免列表内，App 处于后台时 startForegroundService 会抛
            // ForegroundServiceStartNotAllowedException。兜底：5 秒后再试一次
            // 最终依赖 WatchdogService 的 60s 检查周期恢复
            LogBuffer.log(LogBuffer.ID_SCHEDULER, "W", "CaptureReceiver",
                "后台启动 FGS 失败: ${e.javaClass.simpleName}: ${e.message}")
            CaptureScheduler.get(context).scheduleNext(5)
        }
    }
}
