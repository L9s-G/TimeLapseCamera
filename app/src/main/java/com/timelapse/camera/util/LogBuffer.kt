package com.timelapse.camera.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存日志环形缓冲 + 文件持久化，自动初始化，零启动成本。
 *
 * 为什么不用 logcat？
 * - logcat 需要 READ_LOGS 权限（Android 4.1+ 普通应用无法获取）
 * - 教学项目要让用户在 UI 上直接看到运行日志，方便 debug
 *
 * 架构设计（职责自管）：
 * - TimeLapseApplication.onCreate() 调用一次 initialize(appContext) —— 这是唯一的启动代码
 * - 其余任何组件（Service/Fragment/Scheduler/Storage）**只管 log()**，不需要任何 init
 * - 首次 log(identity) 时自动从 appContext.filesDir 派生日志路径、加载历史、创建文件
 * - 外部不存在调用 init() 的入口，消除"未 init 先 log"的时序 bug
 *
 * 持久化策略：
 * - 每条日志同时写入内存 buffer + 文件（append 模式）
 * - 崩溃后重启，首次 log(identity) 从文件加载 MAX_SIZE 条历史到内存
 * - 内存缓冲最多 500 条，文件超过 ~100KB 时自动截断保留最近条目
 *
 * 身份化路由（多进程支持）：
 * - 每个身份对应独立的内存状态和日志文件，文件名统一 log_<identity>.txt
 * - ID_MAIN      主进程：CaptureService/UI/存储/相机
 * - ID_WATCHDOG  watchdog 独立进程：守护与保活
 * - ID_SCHEDULER 调度器：AlarmManager 闹钟事件，被两个进程共用
 *
 * 线程安全：
 * - 内存 + 文件操作共享全局 lock，多协程并发写不交错
 * - SimpleDateFormat 非线程安全，每次局部创建实例使用
 *
 * 导出：
 * - exportLogs(identity) 将日志复制到公共缓存区，返回可分享/读取的临时文件
 */
object LogBuffer {

    private const val MAX_SIZE = 500

    /** 日志身份常量：每个角色一个，保持短标识符便于文件命名简洁。 */
    const val ID_MAIN = "main"
    const val ID_WATCHDOG = "watchdog"
    const val ID_SCHEDULER = "scheduler"

    /** 身份 → 文件名映射；文件名统一 log_<identity>.txt，存应用私有目录。 */
    private val FILE_NAMES = mapOf(
        ID_MAIN to "log_main.txt",
        ID_WATCHDOG to "log_watchdog.txt",
        ID_SCHEDULER to "log_scheduler.txt"
    )

    /** 单身份状态：内存缓冲 + 文件句柄。 */
    private class State(val buffer: MutableList<String>) {
        @Volatile var file: File? = null
    }

    private val states = mutableMapOf<String, State>()
    private val lock = Any()

    @Volatile private var appContext: Context? = null

    /**
     * 全局一次性初始化：由 Application.onCreate() 调用，提供 Context。
     * 幂等：多次调用无副作用。
     */
    fun initialize(context: Context) {
        if (appContext == null) {
            synchronized(lock) {
                if (appContext == null) {
                    appContext = context.applicationContext
                }
            }
        }
    }

    /**
     * 按身份写入日志，同时写入对应身份的内存 buffer + 文件。
     *
     * 若该身份尚未初始化：
     *   - 从 appContext.filesDir 自动推导日志文件路径
     *   - 加载历史日志到内存 buffer
     *   - 创建新的 State 后再写当前日志
     *   对调用方完全透明，不存在时序问题。
     */
    fun log(identity: String, level: String, tag: String, message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val line = "[${timeFormat.format(Date())}] $level/$tag: $message"
        synchronized(lock) {
            autoInitIfNeededLocked(identity)
            val state = states[identity]
                ?: run {
                    // 理论上不会走到这里（autoInit 保证 state 已创建），留兜底
                    Log.w("LogBuffer", "Identity '$identity' state unexpectedly null after autoInit")
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
     *
     * - 未初始化身份：返回空串（不触发 init，保持纯读取语义）
     * - 已初始化：返回最多 MAX_SIZE 条，按 \n 拼接
     */
    fun getFormattedLogs(identity: String): String = synchronized(lock) {
        states[identity]?.buffer?.let { buf ->
            if (buf.isEmpty()) "" else buf.joinToString("\n")
        } ?: ""
    }

    /**
     * 将指定身份的日志文件复制到外部缓存目录，返回可分享/读取的临时文件。
     * 未初始化的身份返回 null。
     */
    fun exportLogs(identity: String): File? = synchronized(lock) {
        val state = states[identity] ?: return null
        val file = state.file ?: return null
        val ctx = appContext ?: return null
        val outDir = File(ctx.externalCacheDir ?: ctx.cacheDir, "exports")
        outDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outFile = File(outDir, "log_${identity}_$ts.txt")
        runCatching {
            file.copyTo(outFile, overwrite = true)
        }.getOrNull()
    }

    /**
     * 内部自动初始化：创建 State + 设文件路径 + 从历史文件加载 MAX_SIZE 条。
     * 调用方必须已持有 lock。
     */
    private fun autoInitIfNeededLocked(identity: String) {
        if (states.containsKey(identity) && states[identity]!!.file != null) return

        val ctx = appContext
        if (ctx == null) {
            // Context 都没有（理论上不会发生，Application.onCreate 先执行）：
            // 建个空 state 收内存 buffer，至少 UI 上能看到内存日志，后续文件补写
            if (!states.containsKey(identity)) states[identity] = State(mutableListOf())
            Log.w("LogBuffer", "appContext is null, identity '$identity' will work in memory only")
            return
        }

        val fileName = FILE_NAMES[identity]
            ?: run {
                Log.w("LogBuffer", "Unknown identity '$identity', falling back to $ID_MAIN")
                FILE_NAMES.getValue(ID_MAIN)
            }
        val newFile = File(ctx.filesDir, fileName)
        val state = states.getOrPut(identity) { State(mutableListOf()) }

        if (state.file != null && state.file == newFile) return  // 已初始化且路径一致

        // 首次 init：加载历史日志
        state.buffer.clear()
        if (newFile.exists()) {
            runCatching {
                newFile.readLines().takeLast(MAX_SIZE).forEach { state.buffer.add(it) }
            }
        }
        state.file = newFile
    }

    /**
     * 追加一行到 state 的日志文件，超过阈值时截断保留最近条目。
     * 调用方必须已持有 lock。
     */
    private fun appendToFileLocked(state: State, line: String) {
        val file = state.file ?: return
        runCatching {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
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
