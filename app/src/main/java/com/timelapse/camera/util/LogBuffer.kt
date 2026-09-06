package com.timelapse.camera.util

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存日志环形缓冲 —— 服务端写，UI 端读，崩溃后可恢复。
 *
 * 为什么不用 logcat？
 * - logcat 需要 READ_LOGS 权限（Android 4.1+ 普通应用无法获取）
 * - 教学项目要让用户在 UI 上直接看到运行日志，方便 debug
 *
 * 持久化设计：
 * - 每条日志同时写入内存缓冲和文件（append 模式）
 * - 崩溃后重启，init() 从文件加载历史日志到内存
 * - 内存缓冲最多保留 500 条，文件超过 ~100KB 自动截断（保留最近条目）
 *
 * 身份化路由（多进程支持）：
 * - 每个身份对应独立的内存缓冲和日志文件
 * - FILE_NAMES 表维护身份与文件名的映射关系，新增日志进程只需加一行
 * - 同身份同路径重复 init 直接跳过（保留原语义）
 *
 * 线程安全：
 * - 内存缓冲和文件追加共用锁，多协程并发写不会交错
 * - SimpleDateFormat 非线程安全，log() 中每次局部创建实例使用
 */
object LogBuffer {

    private const val MAX_SIZE = 500

    /** 日志身份常量：每个写日志的进程/角色一个，保持短标识符以便文件命名简洁 */
    const val ID_MAIN = "main"
    const val ID_WATCHDOG = "watchdog"

    /**
     * 身份 → 日志文件名 关系表。
     * 日志文件与照片同目录（学生可从文件管理器直接定位提交）。
     * 日后新增日志进程：加一行映射 + 该进程启动处 init(dir, ID_XXX)。
     */
    private val FILE_NAMES = mapOf(
        ID_MAIN to "timelapse_log.txt",
        ID_WATCHDOG to "timelapse_log_watchdog.txt"
    )

    /** 每身份独立状态：内存缓冲 + 文件路径 + 截断计数 */
    private class State(val buffer: MutableList<String>) {
        @Volatile var file: File? = null
    }

    private val states = mutableMapOf<String, State>()
    private val lock = Any()  // 单锁覆盖所有身份，保证各文件内顺序一致

    /**
     * 初始化指定身份的日志文件路径，并从文件加载历史日志。
     *
     * 应在各进程启动处调用（CaptureService.onCreate、WatchdogService.onCreate、
     * StatusFragment.onCreate、CaptureReceiver.onReceive）。
     *
     * 支持目录变更：存储位置切换后传入新目录，会切换到新日志文件
     * （旧文件保留，内存缓冲改为加载新目录下的历史日志）。
     *
     * @param logFileDir 日志文件所在目录（通常传入 storage.getPhotoDir()，与照片同目录）
     * @param identity   日志身份标识（ID_MAIN / ID_WATCHDOG / 自定义）
     */
    fun init(logFileDir: File, identity: String) {
        synchronized(lock) {
            val fileName = FILE_NAMES[identity]
                ?: run {
                    // 防御路径：未知身份回落 main 并告警一次（不应在正常运行中出现）
                    Log.w("LogBuffer", "Unknown identity '$identity', falling back to main")
                    return init(logFileDir, ID_MAIN)
                }
            val newFile = File(logFileDir, fileName)
            val state = states.getOrPut(identity) { State(mutableListOf()) }

            // 同身份同路径重复 init 直接跳过（避免覆盖内存中的实时数据）
            if (state.file != null && state.file!!.absolutePath == newFile.absolutePath) return

            // 首次初始化，或目录变更：加载新目录下的历史日志
            state.buffer.clear()
            if (newFile.exists()) {
                runCatching {
                    newFile.readLines().takeLast(MAX_SIZE).forEach { state.buffer.add(it) }
                }
            }
            state.file = newFile
        }
    }

    /**
     * 按身份写入日志，同时更新对应身份的内存缓冲和文件。
     *
     * @param identity 日志身份标识（ID_MAIN / ID_WATCHDOG）
     * @param level    日志级别（I/W/E/V/D）
     * @param tag      业务标签（如 "CaptureService"、"CameraXController"）
     * @param message  消息内容
     */
    fun log(identity: String, level: String, tag: String, message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val line = "[${timeFormat.format(Date())}] $level/$tag: $message"
        synchronized(lock) {
            val state = states[identity]
            if (state == null) {
                // 防御路径：init 前调用 log，仅写内存（与旧行为一致）
                Log.w("LogBuffer", "Identity '$identity' not initialized, log buffered only")
                return
            }
            state.buffer.add(line)
            while (state.buffer.size > MAX_SIZE) state.buffer.removeAt(0)
            appendToFileLocked(state, line)
        }
    }

    /**
     * 读取指定身份的格式化日志字符串。
     * 用于 StatusFragment 展示（每秒刷新）。
     */
    fun getFormattedLogs(identity: String): String = synchronized(lock) {
        states[identity]?.buffer?.let { buf ->
            if (buf.isEmpty()) "" else buf.joinToString("\n")
        } ?: ""
    }

    /**
     * 追加一行到指定身份的日志文件。调用方必须已持有 lock。
     */
    private fun appendToFileLocked(state: State, line: String) {
        val file = state.file ?: return
        runCatching {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
            // 200 = 每行平均字节数的估计值（时间戳+级别+标签+消息 ≈ 100-300 字节），
            // 故截断阈值 ≈ 500 行 × 200B = 100KB
            if (file.length() > MAX_SIZE * 200L) {
                file.readLines().takeLast(MAX_SIZE).let { lines ->
                    file.writeText(lines.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}
