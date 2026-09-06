package com.timelapse.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.timelapse.camera.MainActivity
import com.timelapse.camera.R
import com.timelapse.camera.camera.CameraXController
import com.timelapse.camera.camera.ICameraController
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.config.RemoteConfigFetcher
import com.timelapse.camera.model.CaptureResult
import com.timelapse.camera.scheduler.CaptureScheduler
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.LogBuffer
import com.timelapse.camera.util.WatermarkPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拍摄前台服务 —— 持久化运行，编排拍摄循环。
 *
 * 架构（前台服务保活 + 协程计时 + 闹钟备份）：
 *   用户点击开始 → startForegroundService(ACTION_START)
 *   → 服务常驻，通知栏显示倒计时
 *   → 协程循环：拍照 → 存盘 → 更新倒计时 → delay(间隔) → 重复
 *   → 用户点击停止或系统杀掉 → 结束
 *
 * 三层保活机制：
 * 1. 前台服务通知（IMPORTANCE_DEFAULT）—— 进程不被系统主动杀死（主要）
 * 2. START_STICKY —— 被杀后系统尽量重启
 * 3. AlarmManager 备份 —— 每次拍摄后设闹钟，服务被杀则闹钟重启
 *
 * WakeLock 策略（实测调整）：
 * 原设计「间隔期深度睡眠节能、拍摄瞬间短暂唤醒」在部分实体设备（国产 OS）上
 * 被证伪：息屏后 CPU 完全无法可靠唤醒，闹钟链路也不可靠，拍摄时序彻底失控。
 * 现改为：运行期全程持有 PARTIAL_WAKE_LOCK（无超时），牺牲间隔期功耗
 * 换取拍摄时序的绝对可靠。锁随服务启动持有，onDestroy 无条件释放。
 *
 * 教学要点：
 * - setChronometerCountDown(true) 让系统自动渲染倒计时，App 无需定时刷新通知
 * - withContext(NonCancellable) 确保 stopForeground/stopSelf 在协程取消后仍执行
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.timelapse.camera.START"
        const val ACTION_STOP = "com.timelapse.camera.STOP"
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "timelapse_capture"
        private const val NOTIFICATION_ID = 1
    }

    /**
     * 服务级协程作用域：随服务 onCreate 创建，onDestroy 取消。
     *
     * 为什么用 SupervisorJob？
     * - 子协程（如 captureLoop）失败不会取消整个 scope 和其他兄弟协程
     * - 将来如果加上传、日志等子协程，互不影响
     *
     * 生命周期边界：onCreate → onDestroy，与服务完全一致。
     */
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val watermarkProcessor = WatermarkProcessor()
    private val remoteConfigFetcher = RemoteConfigFetcher()
    private lateinit var storage: IPhotoStorage
    private var wakeLock: PowerManager.WakeLock? = null
    private var captureJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        storage = PhotoStorageFactory.create(applicationContext, CaptureConfig.load(applicationContext))
        LogBuffer.init(storage.getPhotoDir())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LogBuffer.log("I", TAG, "收到停止指令")
                // 注意：isRunning 已由 UI 层写入，此处不再修改
                CaptureScheduler.get(applicationContext).cancel()
                captureJob?.cancel()
                // 同步停止守护服务：用户主动停止后无需再保活，避免常驻通知和空转耗电
                stopService(Intent(applicationContext, WatchdogService::class.java))
                // 不立即 stopSelf，让 captureLoop 的 finally 块优雅退出
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START 或 null（START_STICKY 恢复）
                val config = CaptureConfig.load(applicationContext)
                // isRunning=false 表示用户已停止，主动清理并退出
                if (!config.isRunning) {
                    LogBuffer.log("I", TAG, "检测到 isRunning=false，主动停止并清理")
                    CaptureScheduler.get(applicationContext).cancel()
                    stopService(Intent(applicationContext, WatchdogService::class.java))
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 区分启动来源：lastCaptureTime==0 说明是闹钟/Watchdog 唤醒后的重启，否则是正常启动
                val restartSource = if (config.lastCaptureTime == 0L) "闹钟重启" else "正常启动"
                LogBuffer.log("I", TAG, "服务启动 [来源=$restartSource]，间隔 ${config.intervalSeconds}s")
                val initialDelay = if (config.lastRemoteInterval > 0)
                    config.lastRemoteInterval else config.intervalSeconds
                // Android 12+ 后台启动前台服务 / Android 14 camera type 缺 CAMERA 权限时
                // startForeground 会抛异常；闹钟重启场景 App 可能正处于后台，必须兜底
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(initialDelay))
                } catch (e: Exception) {
                    LogBuffer.log("E", TAG, "启动前台服务失败: ${e.javaClass.simpleName}: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // 【互相守护】检查并恢复 Watchdog，确保保护链完整
                if (!isWatchdogRunning()) {
                    LogBuffer.log("I", TAG, "Watchdog 未运行，恢复守护")
                    startService(Intent(this, WatchdogService::class.java))
                }

                if (captureJob == null || !captureJob!!.isActive) {
                    LogBuffer.log("I", TAG, "拍摄服务启动，间隔 ${config.intervalSeconds}s")
                    // 开机保活：持有 WakeLock 贯穿整个服务运行期（无超时），防止国产 OS 息屏深度睡眠后
                    // CPU 无法唤醒导致拍摄时序失控（实测教训，见类头注释）
                    acquireWakeLock()
                    captureJob = serviceScope.launch { captureLoop() }
                }
                return START_STICKY
            }
        }
    }

    /**
     * 拍摄循环：拍照 → 水印 → 存盘 → 更新倒计时 → 协程等待 → 重复
     *
     * Bitmap 内存责任链（教学要点）：
     *   CameraXController 创建 → WatermarkProcessor 直接绘制 → IPhotoStorage 存盘
     *   ↑                    ↑                        ↑
     *   1 个 mutable Bitmap 对象在链上传递，全程峰值 = 单张图大小（~8MB）
     *
     * WakeLock 由服务启动时持有，贯穿整个 captureLoop 运行期，不在循环内重复 acquire/release。
     *
     * 被取消时（用户停止 / 服务被杀），finally 块清理并停止服务
     */
    private suspend fun captureLoop() {
        try {
            while (true) {
              try {
                var config = CaptureConfig.load(applicationContext)
                LogBuffer.log("I", TAG, "循环开始, isRunning=${config.isRunning}")
                if (!config.isRunning) break

                // ── 0. 检测存储位置是否变更，变更则重建 storage 实例 ──
                storage = PhotoStorageFactory.create(applicationContext, config)

                // ── 0.5 FIFO 清理：拍摄前检测存储空间，不足则删旧照片 ──
                storage.cleanupOldPhotos(
                    thresholdGb = config.storageThresholdGb,
                    safeLineGb = config.storageSafeLineGb
                )

                // ── 1. 远程配置：拉取下次拍摄延迟 ──
                var nextDelay = config.intervalSeconds
                if (!config.remoteConfigUrl.isNullOrBlank()) {
                    val remoteDelay = remoteConfigFetcher.fetchNextInterval(config.remoteConfigUrl!!)
                    if (remoteDelay != null) {
                        nextDelay = remoteDelay
                        LogBuffer.log("I", TAG, "远程配置: 间隔=${remoteDelay}s")
                        // 局部更新：只写远程间隔的 key，避免全量 save 覆盖用户刚改的其他配置
                        CaptureConfig.updateRemoteInterval(applicationContext, remoteDelay)
                        config = config.copy(lastRemoteInterval = remoteDelay)
                    } else if (config.lastRemoteInterval > 0) {
                        nextDelay = config.lastRemoteInterval
                    }
                }

                // ── 2. 拍摄 ──
                // WakeLock 已由服务启动时持有（防止息屏秒睡），此处只需正常拍摄
                // 拍摄段异常由外层 catch(Throwable) 统一捕获：bitmapToSave 尚未赋值时无需回收
                val timestamp = System.currentTimeMillis()
                LogBuffer.log("I", TAG, "开始拍摄 #${config.captureCount + 1}")
                val camera: ICameraController = CameraXController(
                    applicationContext,
                    config.cameraId,
                    config.shotRotation
                )
                val result = camera.capture()
                LogBuffer.log("I", TAG, "拍摄结果: ${if (result is CaptureResult.Success) "成功" else "失败: ${(result as CaptureResult.Failure).message}"}")

                // 拍摄成功：加水印 + 存盘；拍摄失败：生成黑图占位 + 存盘
                // 统一调用 WatermarkPipeline 处理水印逻辑，确保两条路径行为一致
                LogBuffer.log("I", TAG, "开始水印处理")
                val watermarkedBitmap = WatermarkPipeline.process(
                    config = config,
                    storage = storage,
                    context = applicationContext,
                    result = result
                )

                // 写入磁盘也可能失败（磁盘满、IO 错误等）
                // 失败了就打 Log，不崩溃，等下一轮继续（释放资源是关键）
                // Bitmap 生命周期闭环：无论存盘成功与否，统一回收，杜绝泄漏
                try {
                    runCatching {
                        watermarkedBitmap?.let { storage.save(it, timestamp) }
                    }.onSuccess {
                        if (result is CaptureResult.Success) {
                            // 局部更新：只写拍摄进度的 key，避免全量 save 覆盖用户刚改的其他配置。
                            // lastCaptureTime 在拍摄成功后才更新（UI 据此推算倒计时，拍完起算更准确）
                            val newCount = config.captureCount + 1
                            CaptureConfig.updateCaptureProgress(applicationContext, newCount, timestamp)
                            config = config.copy(captureCount = newCount, lastCaptureTime = timestamp)
                            LogBuffer.log("I", TAG, "拍摄完成 #$newCount")
                        } else {
                            LogBuffer.log("W", TAG, "拍摄失败，已保存黑图占位")
                        }
                    }.onFailure { e ->
                        LogBuffer.log("E", TAG, "写入磁盘失败: ${e.message}")
                    }
                } finally {
                    watermarkedBitmap?.recycle()
                }

                // ── 3. 更新倒计时通知（系统自动渲染，零功耗）──
                updateNotification(nextDelay)

                // ── 4. AlarmManager 备份：服务被杀后闹钟重启 ──
                CaptureScheduler.get(this).scheduleNext(nextDelay)

                // ── 5. 协程等待（主调度，WakeLock 全程持有防息屏秒睡）──
                delay(nextDelay * 1000L)
              } catch (e: CancellationException) {
                  throw e
              } catch (e: Throwable) {
                  LogBuffer.log("E", TAG, "拍摄循环异常: ${e.javaClass.simpleName}: ${e.message}")
                  delay(5000)
              }
            }
        } finally {
            withContext(NonCancellable) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }



    // ────────────────── WakeLock ──────────────────

    /**
     * 无超时持有 PARTIAL_WAKE_LOCK，贯穿整个服务运行期。
     *
     * 为什么不用带超时的 acquire(timeout)？
     * - 早期版本用 acquire(59s)，实测国产 OS 息屏深度睡眠后 CPU 完全无法唤醒，
     *   拍摄间隔 >59s 时时序彻底失控
     * - 无超时锁的释放责任完全在 onDestroy()，务必确保该路径可靠
     */
    private fun acquireWakeLock() {
        releaseWakeLock() // 先释放旧实例，防止闹钟重启路径覆盖引用导致旧锁泄漏
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TimeLapseCamera:Capture"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ────────────────── 通知（含倒计时）──────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    /**
     * 构建带倒计时的通知：
     * setChronometerCountDown(true) + setWhen(未来时间戳) → 系统自动渲染倒计时
     * App 无需定时刷新通知，零额外功耗
     *
     * 点击通知跳转到 MainActivity，这是前台服务通知的标准做法。
     */
    private fun buildNotification(nextDelaySeconds: Int): Notification {
        // 点击通知跳转到主界面
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val nextTime = System.currentTimeMillis() + nextDelaySeconds * 1000L
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_countdown))
            .setOngoing(true)
            // PRIORITY_HIGH 是锁屏显示的前提条件；VISIBILITY_PUBLIC 强制在锁屏上可见
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setWhen(nextTime)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    private fun updateNotification(nextDelaySeconds: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(nextDelaySeconds))
        } catch (e: Exception) {
            LogBuffer.log("E", TAG, "更新通知失败: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun isWatchdogRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(Int.MAX_VALUE)
        return running.any { it.service.className == WatchdogService::class.java.name }
    }

    override fun onDestroy() {
        captureJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
