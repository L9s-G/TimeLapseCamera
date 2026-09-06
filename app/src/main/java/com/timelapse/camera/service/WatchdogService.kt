package com.timelapse.camera.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.timelapse.camera.R
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.scheduler.CaptureScheduler
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 独立进程的守护服务，用于在主服务被杀后自动重启。
 *
 * 设计原理：
 * - 运行在 :watchdog 进程，与主服务完全隔离（通过 AndroidManifest.xml 中
 *   [WatchdogService] 的 android:process=":watchdog" 声明实现）
 * - 每 60s 检查一次主服务进程是否存活
 * - 不存活时立即重新注册拍摄闹钟以触发服务重启
 * - 持有 WakeLock 防止息屏时守护进程本身被杀（实测国产 OS 深度睡眠后
 *   CPU 无法可靠唤醒，故无超时持有，与主服务策略一致）
 * - 用户停止拍摄（isRunning=false）连续 2 次检查后自行退出，避免常驻耗电
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "timelapse_watchdog"
        private const val NOTIFICATION_ID = 99
        private const val CHECK_INTERVAL_MS = 60_000L // 每 60s 检查一次
        private const val IDLE_EXIT_ROUNDS = 2 // isRunning=false 连续 N 次后自行退出
    }

    // 使用 CoroutineScope 替代 MainScope，避免引入 lifecycle-runtime-ktx 依赖
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var checkJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // 独立进程：LogBuffer 需在本进程内重新初始化，否则守护日志全部静默丢失
        runCatching {
            val config = CaptureConfig.load(applicationContext)
            LogBuffer.init(PhotoStorageFactory.create(applicationContext, config).getPhotoDir(), LogBuffer.ID_WATCHDOG)
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        startCheckLoop()
        LogBuffer.log(LogBuffer.ID_WATCHDOG, "I", TAG, "守护服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * 每 60s 检查一次主服务进程是否存活。
     * 如果主进程已死且配置中 isRunning=true，重新注册拍摄闹钟（5秒后）以触发服务重启。
     * 注意：只在用户主动开始拍摄（isRunning=true）时才触发重启，避免 App 刚启动就自动拍摄。
     */
    private fun startCheckLoop() {
        checkJob = serviceScope.launch(Dispatchers.Default) {
            var idleRounds = 0 // isRunning=false 的连续轮数
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (!isMainServiceRunning()) {
                    // 只有用户在设置中开启了拍摄（isRunning=true），才需要重启
                    val config = CaptureConfig.load(this@WatchdogService)
                    if (config.isRunning) {
                        idleRounds = 0
                        LogBuffer.log(LogBuffer.ID_WATCHDOG, "W", TAG, "主服务进程未运行，Watchdog 触发重启闹钟")
                        // 设一个 5 秒后的闹钟，触发 CaptureReceiver → 重启主服务
                        CaptureScheduler.get(this@WatchdogService).scheduleNext(5)
                    } else {
                        // 用户已停止拍摄：连续 N 轮仍为 false 则自行退出，避免常驻耗电
                        idleRounds++

                        if (idleRounds >= IDLE_EXIT_ROUNDS) {
                            LogBuffer.log(LogBuffer.ID_WATCHDOG, "I", TAG, "连续 $IDLE_EXIT_ROUNDS 轮无拍摄任务，守护服务自行退出")
                            stopSelf()
                            break
                        }
                    }
                } else {
                    idleRounds = 0
                }
            }
        }
    }

    /**
     * 检查主服务（CaptureService）是否在运行中。
     */
    @Suppress("DEPRECATION")
    private fun isMainServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(Int.MAX_VALUE)
        return running.any { it.service.className == CaptureService::class.java.name }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setOngoing(true)
            // PRIORITY_HIGH + VISIBILITY_PUBLIC：锁屏可见，确保系统信任前台服务状态
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle("延时相机守护中")
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "守护服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "延时相机后台守护，请勿关闭" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        releaseWakeLock() // 先释放旧实例，防泄漏
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${applicationContext.packageName}:watchdog"
        ).apply { acquire() } // 无超时：国产 OS 深度睡眠后 CPU 无法可靠唤醒，与主服务策略一致
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        checkJob?.cancel()
        // 【自我保护】只有用户仍在拍摄时才重新注册闹钟唤醒自己
        // isRunning=false 表示用户主动停止，无需恢复
        val config = CaptureConfig.load(applicationContext)
        if (config.isRunning) {
            CaptureScheduler.get(this).scheduleNext(60)
            LogBuffer.log(LogBuffer.ID_WATCHDOG, "I", TAG, "检测拍摄中，注册闹钟60秒后自我唤醒")
        } else {
            LogBuffer.log(LogBuffer.ID_WATCHDOG, "I", TAG, "已停止拍摄，不恢复守护")
        }
        releaseWakeLock()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
