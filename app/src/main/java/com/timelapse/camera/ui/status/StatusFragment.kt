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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 状态页 Fragment —— 默认首页。
 *
 * 核心信息：
 * - 运行状态 + 统计（拍摄数量、电量、存储、温度）
 * - 开始/停止按钮
 * - 三 Tab 日志：主日志 / 调度日志 / 守护日志（切 Tab 时 refillBuffer 从磁盘刷新跨进程日志）
 * - 一键导出：SAF 目录选择器，把 main/scheduler/watchdog 三个日志文件写入用户指定目录
 *
 * 设计要点：
 * - 进入页面时加载一次完整状态，后续每3秒刷新全部信息
 * - StatusFragment 是纯读者，**不管理 LogBuffer 生命周期**：
 *   - 不再调用 LogBuffer.init()
 *   - 只读 getFormattedLogs()
 *   - LogBuffer 的初始化由 TimeLapseApplication + 各身份首次 log() 自行完成
 * - 用 lifecycleScope，页面不可见时自动停止刷新
 */
class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage

    private var refreshJob: Job? = null

    /** 当前选中的日志身份：主/调度/守护，切换 Tab 时同步更新。 */
    private var currentLogIdentity = LogBuffer.ID_MAIN

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
        _binding = null
    }

    private fun setupLogTabs() {
        val tabLayout = binding.tabLog
        val identities = listOf(
            LogBuffer.ID_MAIN to getString(R.string.tab_log_main),
            LogBuffer.ID_SCHEDULER to getString(R.string.tab_log_scheduler),
            LogBuffer.ID_WATCHDOG to getString(R.string.tab_log_watchdog)
        )
        identities.forEach { (_, title) ->
            tabLayout.addTab(tabLayout.newTab().setText(title))
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentLogIdentity = identities[tab.position].first
                // 切 Tab 时从磁盘刷新，保证跨进程身份的日志可见
                LogBuffer.refillBuffer(currentLogIdentity)
                binding.tvLog.text = LogBuffer.getFormattedLogs(currentLogIdentity)
                    .ifEmpty { getString(R.string.status_log_empty) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                // 重新选中同一 Tab 也刷新一次，方便用户手动拉取最新日志
                LogBuffer.refillBuffer(currentLogIdentity)
                binding.tvLog.text = LogBuffer.getFormattedLogs(currentLogIdentity)
                    .ifEmpty { getString(R.string.status_log_empty) }
            }
        })
    }

    /**
     * 从磁盘重新加载 config，并基于同一份 config 创建 storage。
     * 不再调用 LogBuffer.init() —— 本组件是纯读者。
     */
    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    /** 点击导出按钮：弹出 SAF 目录选择器，把三个身份的日志文件写入所选目录 */
    private fun exportCurrentLog() {
        exportTreeLauncher.launch(null)
    }

    /**
     * 将 main / scheduler / watchdog 三个身份的日志磁盘文件
     * 写入用户通过 OpenDocumentTree 选择的目录。
     *
     * 文件名格式：log_<identity>_yyyyMMdd_HHmmss.txt（带时间戳，每次导出不覆盖历史）
     */
    private fun exportLogsToTree(treeUri: Uri) {
        val ctx = requireContext()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val targetRoot = DocumentFile.fromTreeUri(ctx, treeUri) ?: run {
            Toast.makeText(ctx, R.string.status_log_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val identities = listOf(LogBuffer.ID_MAIN, LogBuffer.ID_SCHEDULER, LogBuffer.ID_WATCHDOG)
        var exported = 0
        for (identity in identities) {
            val sourceFile = LogBuffer.getFile(identity) ?: continue
            if (!sourceFile.exists() || sourceFile.length() == 0L) continue
            val docName = "log_${identity}_$ts.txt"
            // 同一次导出的 3 个文件名唯一（ts 相同、identity 不同），无需查找已存在文件；
            // 目录里可能有历史导出残留，但本组新文件不会冲突。直接 createFile。
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
                val identity = currentLogIdentity
                val snapshot = withContext(Dispatchers.IO) {
                    val curConfig = CaptureConfig.load(requireContext())
                    val curStorage = PhotoStorageFactory.create(requireContext(), curConfig)
                    RefreshSnapshot(
                        config = curConfig,
                        battery = BatteryMonitor.getBatteryPercent(requireContext()),
                        storageGb = BatteryMonitor.getStorageRemainingGb(curStorage.getPhotoDir()),
                        temp = BatteryMonitor.getBatteryTemperature(requireContext()),
                        logs = LogBuffer.getFormattedLogs(identity)
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

                b.tvLog.text = snapshot.logs.ifEmpty { getString(R.string.status_log_empty) }
            }
        }
    }

    private data class RefreshSnapshot(
        val config: CaptureConfig,
        val battery: Int,
        val storageGb: Float,
        val temp: Float,
        val logs: String
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

        binding.tvLog.text = LogBuffer.getFormattedLogs(currentLogIdentity)
            .ifEmpty { getString(R.string.status_log_empty) }
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
