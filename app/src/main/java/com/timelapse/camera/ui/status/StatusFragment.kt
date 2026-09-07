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
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.timelapse.camera.BuildConfig
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

/**
 * 状态页 Fragment —— 默认首页。
 *
 * 核心信息：
 * - 运行状态 + 统计（拍摄数量、电量、存储、温度）
 * - 开始/停止按钮
 * - 三 Tab 日志：主日志 / 调度日志 / 守护日志（实时滚动更新，不自动滚底）
 * - 一键导出当前 Tab 的日志文件
 *
 * 设计要点：
 * - 进入页面时加载一次完整状态，后续每秒刷新全部信息
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
                binding.tvLog.text = LogBuffer.getFormattedLogs(currentLogIdentity)
                    .ifEmpty { getString(R.string.status_log_empty) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
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

    private fun exportCurrentLog() {
        val ctx = requireContext()
        val identity = currentLogIdentity
        val exported = LogBuffer.exportLogs(identity)
        if (exported == null) {
            Toast.makeText(ctx, R.string.status_log_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            ctx,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            exported
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.status_log_export_title)))
    }

    // ──────────── 主页状态刷新 ────────────

    private fun startStatusRefresh() {
        stopStatusRefresh()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(1000)
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
                val now = System.currentTimeMillis()

                val interval = if (curConfig.lastRemoteInterval > 0)
                    curConfig.lastRemoteInterval else curConfig.intervalSeconds
                val remaining = if (curConfig.lastCaptureTime > 0) {
                    (curConfig.lastCaptureTime + interval * 1000L - now).coerceAtLeast(0)
                } else 0L
                b.tvCountdown.text = if (remaining > 0) formatDuration(remaining) else "--:--:--"

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
        val now = System.currentTimeMillis()
        context.startForegroundService(Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
        })
        config = config.copy(isRunning = true, lastCaptureTime = now)
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

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    companion object {
        fun newInstance() = StatusFragment()
    }
}
