package com.timelapse.camera.ui.status

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.timelapse.camera.R
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.databinding.FragmentStatusBinding
import com.timelapse.camera.service.CaptureService
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.BatteryMonitor
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 状态页 Fragment —— 默认首页。
 *
 * 核心信息：
 * - 运行状态 + 统计（拍摄数量、电量、存储、温度）
 * - 开始/停止按钮
 * - 两 Tab 日志：
 *   - 主日志：读本进程 LogBuffer 内存 buffer（实时，3s 循环刷新，零磁盘 IO）
 *   - 守护日志：跨进程，RandomAccessFile 增量读 filesDir/log_watchdog.txt
 *     - 手动刷新：切 Tab / 重点击 Tab 时才 seek + read；不在前台时零系统调用
 *     - 文件不存在时显示"暂无日志"占位（便于发现 bug）
 * - 一键导出：SAF 目录选择器，直接读 filesDir 下两个日志文件写入用户指定目录（绕开 LogBuffer）
 *
 * 设计要点：
 * - StatusFragment 是纯读者，**不管理 LogBuffer 生命周期**（由 TimeLapseApplication.onCreate() 完成）
 * - 跨进程 watchdog 日志由 RAF 直接增量读取，不经 LogBuffer（LogBuffer 只管本进程写路径）
 * - 3s 自动刷新只针对主日志 Tab；watchdog Tab 靠手动切/重点击刷新
 */
class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage

    private var refreshJob: Job? = null

    /** 当前选中的日志 Tab 对应的进程 key：主/守护。 */
    private var currentLogProcess = LogBuffer.PROC_MAIN

    // ──── watchdog 跨进程日志：RAF 增量读 ────

    /**
     * watchdog 日志的只读文件流。
     * onViewCreated 时尝试 open（文件可能还不存在）；onDestroyView 时 close。
     * 不在前台时保持 open 但零系统调用，只有切 tab 时 seek + read。
     */
    private var watchdogRaf: RandomAccessFile? = null

    /** 上次读到文件末尾的字节偏移；用于增量 seek。 */
    private var watchdogLastPos: Long = 0L

    /** watchdog 日志的 UI 显示 buffer（最多 LogBuffer.MAX_SIZE 条），独立于 LogBuffer。 */
    private val watchdogLines = mutableListOf<String>()

    /** 开始拍摄前必须持有相机权限（Android 14 camera type FGS 强制要求） */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCapture()
        } else {
            Toast.makeText(requireContext(), R.string.perm_camera_denied, Toast.LENGTH_SHORT).show()
        }
    }

    /** 导出日志：SAF 目录选择器，返回用户选中的 tree Uri */
    private val exportTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            exportLogsToTree(treeUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnToggle.setOnClickListener { toggleCapture() }
        binding.btnExportLog.setOnClickListener { exportCurrentLog() }
        setupLogTabs()
        // 尝试打开 watchdog 日志流；文件可能尚未被 watchdog 进程创建，失败时暂存 null，
        // 用户点击 watchdog Tab 时再重试
        openWatchdogRaf()
    }

    override fun onResume() {
        super.onResume()
        reloadFromDisk()
        updateUI()
        startStatusRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopStatusRefresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 关闭 watchdog 日志流（RAF 在 view 销毁时释放，再切回时重新 open）
        watchdogRaf?.close()
        watchdogRaf = null
        _binding = null
    }

    // ──────────── Tab 设置 ────────────

    private fun setupLogTabs() {
        val tabLayout = binding.tabLog
        val processes = listOf(
            LogBuffer.PROC_MAIN to getString(R.string.tab_log_main),
            LogBuffer.PROC_WATCHDOG to getString(R.string.tab_log_watchdog)
        )
        processes.forEach { (_, title) ->
            tabLayout.addTab(tabLayout.newTab().setText(title))
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentLogProcess = processes[tab.position].first
                if (currentLogProcess == LogBuffer.PROC_WATCHDOG) {
                    tryRefreshWatchdogLog()
                } else {
                    binding.tvLog.text = LogBuffer.getFormattedLogs()
                        .ifEmpty { getString(R.string.status_log_empty) }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                // 重点击当前 Tab：watchdog 手动刷新，main 立即重读（补 3s 循环的即时响应）
                if (currentLogProcess == LogBuffer.PROC_WATCHDOG) {
                    tryRefreshWatchdogLog()
                } else {
                    binding.tvLog.text = LogBuffer.getFormattedLogs()
                        .ifEmpty { getString(R.string.status_log_empty) }
                }
            }
        })
    }

    // ──────────── watchdog 跨进程日志：RAF 增量读 ────────────

    /**
     * 打开 watchdog 日志的只读 RAF。
     * 文件不存在时静默跳过（watchdog 进程尚未启动），切 tab 时再试。
     */
    private fun openWatchdogRaf() {
        val file = File(requireContext().filesDir, "log_watchdog.txt")
        if (!file.exists()) return
        watchdogRaf?.close()
        watchdogRaf = runCatching { RandomAccessFile(file, "r") }.getOrNull()
        watchdogLastPos = 0L
        watchdogLines.clear()
    }

    /**
     * 增量读取 watchdog 日志，追加到 UI 显示 buffer。
     *
     * - RAF 为 null（文件尚未存在）：显示"暂无日志"占位
     * - 文件被 LogBuffer 截断（watchdogLastPos > fileLength）：重置到文件头
     * - 读到新内容：追加到 watchdogLines，截取最近 MAX_SIZE 条显示
     * - 无新内容且 buffer 为空：显示"暂无日志"占位
     */
    private fun tryRefreshWatchdogLog() {
        val raf = watchdogRaf ?: run {
            // RAF 尚未打开，尝试打开（文件可能刚被 watchdog 进程创建）
            openWatchdogRaf()
            val r = watchdogRaf
            if (r == null) {
                binding.tvLog.text = getString(R.string.status_log_empty)
                return
            }
            refreshWatchdogFromRaf(r)
            return
        }
        refreshWatchdogFromRaf(raf)
    }

    /** 从已打开的 RAF 增量读，更新 watchdogLines 和 tvLog。调用方必须确保 RAF 非 null。 */
    private fun refreshWatchdogFromRaf(raf: RandomAccessFile) {
        val fileLen = raf.length()
        if (watchdogLastPos > fileLen) {
            // 文件被 LogBuffer 截断重写，旧偏移失效
            watchdogLastPos = 0L
            watchdogLines.clear()
        }
        raf.seek(watchdogLastPos)
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        while (true) {
            val n = raf.read(buf)
            if (n <= 0) break
            sb.append(String(buf, 0, n, Charsets.UTF_8))
        }
        watchdogLastPos = raf.filePointer

        val newContent = sb.toString()
        if (newContent.isNotEmpty()) {
            val newLines = newContent.lineSequence().filter { it.isNotBlank() }.toList()
            watchdogLines.addAll(newLines)
            // 保持与 LogBuffer 相同的 MAX_SIZE 上限
            while (watchdogLines.size > LogBuffer.MAX_SIZE) watchdogLines.removeAt(0)
        }

        binding.tvLog.text = if (watchdogLines.isEmpty()) {
            getString(R.string.status_log_empty)
        } else {
            watchdogLines.joinToString("\n")
        }
    }

    // ──────────── config / storage 重载 ────────────

    /**
     * 从磁盘重新加载 config，并基于同一份 config 创建 storage。
     * 本组件是纯读者，不管理 LogBuffer 生命周期。
     */
    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    // ──────────── 导出日志（直接读文件，绕开 LogBuffer）────────────

    /** 点击导出按钮：弹出 SAF 目录选择器，把两个进程的日志文件写入所选目录 */
    private fun exportCurrentLog() {
        exportTreeLauncher.launch(null)
    }

    /**
     * 将 main / watchdog 两个日志磁盘文件
     * 写入用户通过 OpenDocumentTree 选择的目录。
     *
     * 直接读 filesDir 下的文件，不经过 LogBuffer。
     * 文件名格式：log_<process>_yyyyMMdd_HHmmss.txt（带时间戳，每次导出不覆盖历史）
     */
    private fun exportLogsToTree(treeUri: Uri) {
        val ctx = requireContext()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val targetRoot = DocumentFile.fromTreeUri(ctx, treeUri) ?: run {
            Toast.makeText(ctx, R.string.status_log_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val logFiles = listOf(
            LogBuffer.PROC_MAIN to "log_main.txt",
            LogBuffer.PROC_WATCHDOG to "log_watchdog.txt",
        )
        var exported = 0
        for ((process, fileName) in logFiles) {
            val sourceFile = File(ctx.filesDir, fileName)
            if (!sourceFile.exists() || sourceFile.length() == 0L) continue
            val docName = "log_${process}_${ts}.txt"
            val targetDoc: DocumentFile? = targetRoot.createFile("text/plain", docName)
            if (targetDoc == null) continue
            val ok = runCatching {
                val out = ctx.contentResolver.openOutputStream(targetDoc.uri) ?: return@runCatching false
                out.use { outputStream ->
                    sourceFile.inputStream().use { input -> input.copyTo(outputStream) }
                }
                true
            }.getOrDefault(false)
            if (ok) exported++
        }
        if (exported > 0) {
            Toast.makeText(ctx, getString(R.string.status_log_export_success, exported), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, R.string.status_log_export_empty, Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────── 主页状态刷新 ────────────

    private fun startStatusRefresh() {
        stopStatusRefresh()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(3000)
                val snapshot = withContext(Dispatchers.IO) {
                    val curConfig = CaptureConfig.load(requireContext())
                    val curStorage = PhotoStorageFactory.create(requireContext(), curConfig)
                    RefreshSnapshot(
                        config = curConfig,
                        battery = BatteryMonitor.getBatteryPercent(requireContext()),
                        storageGb = BatteryMonitor.getStorageRemainingGb(curStorage.getPhotoDir()),
                        temp = BatteryMonitor.getBatteryTemperature(requireContext())
                    )
                }
                val b = _binding ?: return@launch
                val curConfig = snapshot.config

                val isRunning = curConfig.isRunning
                b.tvStatus.text = if (isRunning) getString(R.string.status_running)
                else getString(R.string.status_stopped)
                b.tvStatus.setTextColor(
                    if (isRunning) ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                    else ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                )
                b.btnToggle.text = if (isRunning) getString(R.string.btn_stop)
                else getString(R.string.btn_start)

                b.tvCaptureCount.text = getString(R.string.status_count_format, curConfig.captureCount)
                b.tvBattery.text = getString(R.string.status_battery_format, snapshot.battery)
                b.tvStorage.text = getString(R.string.status_storage_format, snapshot.storageGb)
                b.tvTemperature.text = getString(R.string.status_temperature_format, snapshot.temp)

                // 3s 循环只自动刷新主日志 Tab；watchdog Tab 靠手动切/重点击刷新
                if (currentLogProcess == LogBuffer.PROC_MAIN) {
                    b.tvLog.text = LogBuffer.getFormattedLogs()
                        .ifEmpty { getString(R.string.status_log_empty) }
                }
                // watchdog tab: 不在前台时零系统调用，用户主动切 tab 才 seek + read
            }
        }
    }

    private data class RefreshSnapshot(
        val config: CaptureConfig,
        val battery: Int,
        val storageGb: Float,
        val temp: Float
    )

    private fun stopStatusRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    // ──────────── UI 更新（进入页面时加载一次）────────────

    private fun updateUI() {
        val isRunning = config.isRunning

        binding.tvStatus.text = if (isRunning) getString(R.string.status_running)
        else getString(R.string.status_stopped)
        binding.tvStatus.setTextColor(
            if (isRunning) ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
            else ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
        )

        binding.btnToggle.text = if (isRunning) getString(R.string.btn_stop)
        else getString(R.string.btn_start)

        binding.tvCaptureCount.text = getString(R.string.status_count_format, config.captureCount)

        val battery = BatteryMonitor.getBatteryPercent(requireContext())
        val storageGb = BatteryMonitor.getStorageRemainingGb(storage.getPhotoDir())
        val temp = BatteryMonitor.getBatteryTemperature(requireContext())
        binding.tvBattery.text = getString(R.string.status_battery_format, battery)
        binding.tvStorage.text = getString(R.string.status_storage_format, storageGb)
        binding.tvTemperature.text = getString(R.string.status_temperature_format, temp)

        if (currentLogProcess == LogBuffer.PROC_WATCHDOG) {
            tryRefreshWatchdogLog()
        } else {
            binding.tvLog.text = LogBuffer.getFormattedLogs()
                .ifEmpty { getString(R.string.status_log_empty) }
        }
    }

    // ──────────── 开始/停止 ────────────

    private fun toggleCapture() {
        if (config.isRunning) {
            stopCapture()
        } else if (hasCameraPermission()) {
            startCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        val context = requireContext()
        context.startForegroundService(Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
        })
        config = config.copy(isRunning = true)
        config.save(context)
        updateUI()
    }

    private fun stopCapture() {
        val context = requireContext()
        context.startService(Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        })
        config = config.copy(isRunning = false)
        config.save(context)
        updateUI()
    }

    companion object {
        fun newInstance() = StatusFragment()
    }
}
