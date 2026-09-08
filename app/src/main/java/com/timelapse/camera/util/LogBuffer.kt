package com.timelapse.camera.util

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程化日志环形缓冲 + 文件持久化，自动初始化。
 *
 * 设计原则：
 * - 一个进程恰好一个写者文件（跨进程同文件竞争天然消失）
 * - 写路径 log() 不传身份：进程 key 在 initialize() 探测一次并缓存，热路径只读缓存
 * - 读路径 getFormattedLogs() 读本进程内存 buffer（无 IO）
 * - 跨进程读（如 UI 读 watchdog 日志）由调用方直接用 RandomAccessFile 增量读，不经过 LogBuffer
 *
 * 持久化策略：
 * - 每条日志同时写入内存 buffer + 本进程日志文件（append 模式）
 * - 重启时 initialize() 从文件加载 MAX_SIZE 条历史到内存
 * - 文件超过 ~100KB 时自动截断保留最近 MAX_SIZE 条
 *
 * 线程安全：
 * - 内存 + 文件操作共享全局 lock，多协程并发写不交错
 * - SimpleDateFormat 非线程安全，每次局部创建实例使用
 */
object LogBuffer {

    /** 内存 buffer 容量上限（条）。也是跨进程读侧的滚动窗口大小。 */
    const val MAX_SIZE = 500

    /** 进程 key 常量：决定写哪个文件。 */
    const val PROC_MAIN = "main"
    const val PROC_WATCHDOG = "watchdog"

    /** 进程 key → 文件名映射；文件存应用私有目录。 */
    private val FILE_NAMES = mapOf(
        PROC_MAIN to "log_main.txt",
        PROC_WATCHDOG to "log_watchdog.txt"
    )

    /** 单进程状态：内存 buffer + 文件句柄。只有一个实例（本进程）。 */
    private class State(val buffer: MutableList<String> = mutableListOf()) {
        @Volatile var file: File? = null
    }

    private val state = State()
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var processKey: String = PROC_MAIN
    @Volatile private var initialized = false

    /**
     * 全局一次性初始化：由 Application.onCreate() 调用。
     * 探测当前进程 key，打开日志文件，加载 MAX_SIZE 条历史到内存。
     * 幂等：多次调用无副作用。
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            appContext = context.applicationContext
            processKey = detectProcessKeyLocked()
            val file = File(context.applicationContext.filesDir, FILE_NAMES.getValue(processKey))
            state.file = file
            if (file.exists()) {
                runCatching {
                    file.readLines().takeLast(MAX_SIZE).forEach { state.buffer.add(it) }
                }
            }
        }
    }

    /**
     * 写入日志：自动路由到本进程文件。调用方无需传身份。
     * 每条同时写入本进程内存 buffer + 本进程日志文件。
     */
    fun log(level: String, tag: String, message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val line = "[${timeFormat.format(Date())}] $level/$tag: $message"
        synchronized(lock) {
            state.buffer.add(line)
            while (state.buffer.size > MAX_SIZE) state.buffer.removeAt(0)
            appendToFileLocked(line)
        }
    }

    /** 读取本进程内存日志（用于 UI 实时刷新，无 IO）。 */
    fun getFormattedLogs(): String = synchronized(lock) {
        if (state.buffer.isEmpty()) "" else state.buffer.joinToString("\n")
    }

    // ──────────── 进程探测 ────────────

    /**
     * 探测当前进程 key。调用方必须已持有 lock。
     *
     * - API 28+：Application.getProcessName()
     *   返回 "com.timelapse.camera"（main）或 "com.timelapse.camera:watchdog"（watchdog）
     * - API 26/27：Process.myPid() 匹配 ActivityManager.getRunningAppProcesses()
     *   （getRunningAppProcesses 在 API 29+ 对第三方不可用，正好落在 minSdk 26-27 段）
     * - 探测失败一律落 PROC_MAIN：日志宁可错放也不丢（UI/导出都在 main 进程）
     */
    private fun detectProcessKeyLocked(): String {
        val name = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                legacyProcessName()
            }
        }.getOrNull()
        if (name == null) {
            Log.w("LogBuffer", "Process name undetectable, defaulting to $PROC_MAIN")
        }
        return if (name != null && name.endsWith(":watchdog")) PROC_WATCHDOG else PROC_MAIN
    }

    /** API 26/27 兜底：pid 匹配当前进程名；无 Context 时返回 null（落 main）。 */
    private fun legacyProcessName(): String? {
        val ctx = appContext ?: return null
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = Process.myPid()
        @Suppress("DEPRECATION")
        return am.getRunningAppProcesses()
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }

    // ──────────── 文件写入 ────────────

    /** 追加一行到本进程日志文件，超过阈值时截断。调用方必须已持有 lock。 */
    private fun appendToFileLocked(line: String) {
        val file = state.file ?: return
        runCatching {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
            // 截断：文件超过 ~100KB 时重写最近 MAX_SIZE 行（摊还 O(1) 热路径）
            if (file.length() > MAX_SIZE * 200L) {
                file.readLines().takeLast(MAX_SIZE).let { lines ->
                    file.writeText(lines.joinToString("\n", postfix = "\n"))
                }
            }
        }.onFailure { e ->
            val failLine = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] E/LogBuffer: 无法写入日志文件 [${file.absolutePath}]: ${e.message}"
            state.buffer.add(failLine)
            while (state.buffer.size > MAX_SIZE) state.buffer.removeAt(0)
        }
    }
}
