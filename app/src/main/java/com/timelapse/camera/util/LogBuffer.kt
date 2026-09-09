package com.timelapse.camera.util

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 进程化日志环形缓冲 + 文件持久化，自动初始化。
 *
 * 设计原则：
 * - 一个进程恰好一个写者文件（跨进程同文件竞争天然消失）
 * - 写路径 log() 不传身份：进程 key 在 initialize() 探测一次并缓存，热路径只读缓存
 * - 读路径 getFormattedLogs() 读本进程内存 ring（无 IO）
 * - 跨进程读（UI 读 watchdog 日志）由调用方直接用 RandomAccessFile 读尾部 50KB，不经过 LogBuffer
 *
 * 持久化策略：
 * - 常驻一条裸 append 流（FileOutputStream，不包 buffer），每条 log 只 write，不 open/close。
 *   裸流的 write() 直接把字节交给内核 page cache，进程被硬杀也丢不了；
 *   没有 JVM 堆里的 buffer，也就不存在"flush 时机"问题。
 * - 内存追踪 fileSize（仅作触发器），超过 FILE_MAX（500KB）时截断到 FILE_RETAIN（200KB）
 * - 截断操作：close 旧流 → RandomAccessFile("rw") 以实际文件长度定位保留窗口 读+写回+setLength → reopen append 流
 * - 重启时 initialize() 从文件尾部 50KB 读历史填入 ring
 *
 * 线程安全：
 * - 内存 ring + 文件操作共享全局 lock，多协程并发写不交错
 * - SimpleDateFormat 非线程安全，每次局部创建实例使用
 */
object LogBuffer {

    /** 内存 ring 容量上限（条）。UI 实时窗口。 */
    const val MAX_SIZE = 500

    /** 进程 key 常量：决定写哪个文件。 */
    const val PROC_MAIN = "main"
    const val PROC_WATCHDOG = "watchdog"

    /** 进程 key → 文件名映射；文件存应用私有目录。 */
    private val FILE_NAMES = mapOf(
        PROC_MAIN to "log_main.txt",
        PROC_WATCHDOG to "log_watchdog.txt"
    )

    // ──── IO 参数 ────

    /** 初始化 / 跨进程读侧的尾部读取窗口：50KB。 */
    private const val READ_WINDOW = 50 * 1024L

    /** 文件达到此大小时触发截断：500KB。 */
    private const val FILE_MAX = 500 * 1024L

    /** 截断后保留量：200KB。 */
    private const val FILE_RETAIN = 200 * 1024L

    // ──── 单进程状态（一个进程恰好一个写者） ────

    private val ring = ArrayDeque<String>(MAX_SIZE)

    /** 常驻裸 append 流（不包 buffer），每条 write 直接交内核。 */
    @Volatile private var appendStream: FileOutputStream? = null
    @Volatile private var logFile: File? = null

    /** 内存追踪当前文件大小（字节），不 stat 磁盘。 */
    private var fileSize: Long = 0L

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var processKey: String = PROC_MAIN
    @Volatile private var initialized = false

    /**
     * 全局一次性初始化：由 Application.onCreate() 调用。
     * 1. 探测当前进程 key
     * 2. 打开日志文件，从尾部 50KB 加载历史到 ring
     * 3. 打开常驻 append 流
     * 幂等：多次调用无副作用。
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            appContext = context.applicationContext
            processKey = detectProcessKeyLocked()
            val file = File(context.applicationContext.filesDir, FILE_NAMES.getValue(processKey))
            logFile = file
            loadHistoryLocked(file)
            openStreamLocked(file)
        }
    }

    /**
     * 写入日志：自动路由到本进程文件。调用方无需传身份。
     * 每条同时写入内存 ring + 本进程日志文件（常驻裸 append 流）。
     *
     * 裸 FileOutputStream 的 write() 直接把字节交内核 page cache，
     * 没有 JVM 堆 buffer，进程被硬杀也丢不了——无需 flush 时机。
     */
    fun log(level: String, tag: String, message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val line = "[${timeFormat.format(Date())}] $level/$tag: $message"
        val bytes = (line + "\n").toByteArray()
        synchronized(lock) {
            // ring：O(1) addLast / removeFirst
            ring.addLast(line)
            while (ring.size > MAX_SIZE) ring.removeFirst()

            // 常驻 append 流写入
            writeToFileLocked(bytes)
        }
    }

    /** 读取本进程内存日志（用于 UI 实时刷新，无 IO）。 */
    fun getFormattedLogs(): String = synchronized(lock) {
        if (ring.isEmpty()) "" else ring.joinToString("\n")
    }

    // ──────────── 进程探测 ────────────

    /**
     * 探测当前进程 key。调用方必须已持有 lock。
     *
     * - API 28+：Application.getProcessName()
     * - API 26/27：Process.myPid() 匹配 ActivityManager.getRunningAppProcesses()
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

    // ──────────── 文件 IO ────────────

    /**
     * 从文件尾部 50KB 加载历史到 ring，并初始化 fileSize。
     * 仅初始化时调用一次（initialize 路径）。恢复 / 截断路径**不**调用它
     * （避免写失败后重读磁盘，把尚未落盘的内存行丢掉）。
     * 调用方必须已持有 lock。
     */
    private fun loadHistoryLocked(file: File) {
        if (file.exists() && file.length() > 0) {
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val len = raf.length()
                    val start = if (len > READ_WINDOW) len - READ_WINDOW else 0
                    raf.seek(start)
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = raf.read(buf)
                        if (n <= 0) break
                        baos.write(buf, 0, n)
                    }
                    val content = baos.toString("UTF-8")
                    // 如果 seek 不是从 0 开始，第一行是半截（跨了 50KB 边界），丢弃
                    val lines = if (start > 0) {
                        content.substringAfter('\n').split('\n')
                    } else {
                        content.split('\n')
                    }
                    // 取最后 MAX_SIZE 行填入 ring
                    ring.clear()
                    val tail = if (lines.size > MAX_SIZE) lines.drop(lines.size - MAX_SIZE) else lines
                    tail.filter { it.isNotBlank() }.forEach { ring.addLast(it) }
                }
            }
            fileSize = file.length()
        } else {
            fileSize = 0L
        }
    }

    /**
     * 打开（或重新打开）常驻裸 append 流。幂等，可被恢复 / 截断路径复用。
     * 不动 ring、不动 fileSize。调用方必须已持有 lock。
     */
    private fun openStreamLocked(file: File) {
        runCatching {
            if (!file.exists()) file.createNewFile()
            appendStream = FileOutputStream(file, true)
        }.onFailure { e ->
            Log.w("LogBuffer", "Failed to open append stream: ${e.message}")
            appendStream = null
        }
    }

    /**
     * 写入字节到本进程日志文件（常驻 append 流）。调用方必须已持有 lock。
     * 写失败：close + null，下条 log() 尝试 reopen（只重开流，不重读历史）。
     * 超过 FILE_MAX 触发截断。
     */
    private fun writeToFileLocked(bytes: ByteArray) {
        val file = logFile ?: return

        // 惰性创建 / 恢复
        if (appendStream == null) {
            openStreamLocked(file)
        }
        val stream = appendStream ?: return  // reopen 也失败，只写 ring，不崩

        runCatching {
            stream.write(bytes)
            fileSize += bytes.size
        }.onFailure { e ->
            // 写失败：close + null，下条重试
            Log.w("LogBuffer", "Write failed: ${e.message}")
            runCatching { stream.close() }
            appendStream = null
            return
        }

        // 截断检查（热路径 O(1) 整数比较）
        if (fileSize > FILE_MAX) {
            truncateLocked(file)
        }
    }

    /**
     * 截断文件到 FILE_RETAIN（200KB）：保留尾部 200KB。
     * 1. close 当前 append 流（裸流无 buffer，无需 flush）
     * 2. RandomAccessFile("rw") 以 **raf.length()（权威值）** 定位保留窗口，
     *    读 200KB → seek(0) → 写回 → setLength，并顺手把触发器 fileSize 对回真实值
     * 3. reopen append 流
     * 调用方必须已持有 lock。
     *
     * 说明：fileSize 平时只作"触发截断"的热路径整数比较（避免每条 stat）；
     * 真正操作文件时以磁盘实际长度为准——写失败（close+null）那条路会让 fileSize
     * 脱钩甚至领先真实盘长，若拿它定位会 seek 到 EOF 之后。这里用权威值定位，
     * 并借截断这次冷路径把 fileSize 拉回真实值，自愈脱钩。
     */
    private fun truncateLocked(file: File) {
        // 1. close 旧流（裸流无 buffer，无需 flush）
        runCatching {
            appendStream?.close()
        }
        appendStream = null

        // 2. 截断：保留尾部 FILE_RETAIN（以 raf.length() 为权威）
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val trueLen = raf.length()
                val keepLen = minOf(FILE_RETAIN, trueLen)
                val keepFrom = trueLen - keepLen
                raf.seek(keepFrom)
                val tail = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var remaining = keepLen.toInt()
                while (remaining > 0) {
                    val n = raf.read(buf, 0, remaining)
                    if (n <= 0) break
                    tail.write(buf, 0, n)
                    remaining -= n
                }
                raf.seek(0)
                raf.write(tail.toByteArray())
                raf.setLength(keepLen)
                // 对回真实保留长度，让触发器与磁盘一致（正常路径 keepLen==fileSize，零回归）
                fileSize = keepLen
            }
        }.onFailure { e ->
            Log.w("LogBuffer", "Truncate failed: ${e.message}")
            // 截断失败：以磁盘实际长度重新对回触发器，避免后续又拿陈旧值定位
            runCatching { fileSize = file.length() }
        }

        // 3. reopen append 流（不重新读文件、不重读历史，ring 不变）
        openStreamLocked(file)
    }
}
