package com.timelapse.camera.ui.status

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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

/**
 * 状态页 Fragment —— 默认首页。
 *
 * 核心信息：
 * - 运行状态 + 统计（拍摄数量、电量、存储、温度）
 * - 开始/停止按钮
 * - 运行日志（实时滚动更新，不自动滚底）
 *
 * 设计要点：
 * - 页面进入时加载一次完整状态，后续每秒刷新全部信息（倒计时、统计、日志）
 * - 倒计时基于 lastCaptureTime 推算，每轮从磁盘读取最新 config
 * - 用 lifecycleScope，页面不可见时自动停止刷新
 * - 刷新循环的磁盘/系统读取全部在 IO 线程，主线程只做 setText
 */
class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage

    private var refreshJob: Job? = null

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
        LogBuffer.init(storage.getPhotoDir(), LogBuffer.ID_MAIN)
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

    /**
     * 从磁盘重新加载 config，并基于同一份 config 创建 storage。
     */
    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    // ──────────── 主页状态刷新 ────────────

    private fun startStatusRefresh() {
        stopStatusRefresh()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(1000)
                // 每秒从磁盘重新加载 config，保证所有动态数据都最新。
                // 采集（磁盘/StatFs/广播）在 IO 线程，主线程只做 setText
                val snapshot = withContext(Dispatchers.IO) {
                    val curConfig = CaptureConfig.load(requireContext())
                    val curStorage = PhotoStorageFactory.create(requireContext(), curConfig)
                    RefreshSnapshot(
                        config = curConfig,
                        battery = BatteryMonitor.getBatteryPercent(requireContext()),
                        storageGb = BatteryMonitor.getStorageRemainingGb(curStorage.getPhotoDir()),
                        temp = BatteryMonitor.getBatteryTemperature(requireContext()),
                        logs = LogBuffer.getFormattedLogs(LogBuffer.ID_MAIN)
                    )
                }
                val b = _binding ?: return@launch
                val curConfig = snapshot.config
                val now = System.currentTimeMillis()

                // 倒计时：lastCaptureTime > 0 说明循环正在运行。
                // 间隔优先用远程下发的 lastRemoteInterval，回退本地 intervalSeconds
                val interval = if (curConfig.lastRemoteInterval > 0)
                    curConfig.lastRemoteInterval else curConfig.intervalSeconds
                val remaining = if (curConfig.lastCaptureTime > 0) {
                    (curConfig.lastCaptureTime + interval * 1000L - now).coerceAtLeast(0)
                } else 0L
                b.tvCountdown.text = if (remaining > 0) formatDuration(remaining) else "--:--:--"

                // 运行状态
                val isRunning = curConfig.isRunning
                b.tvStatus.text = if (isRunning) getString(R.string.status_running)
                else getString(R.string.status_stopped)
                b.tvStatus.setTextColor(
                    if (isRunning) ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                    else ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                )
                b.btnToggle.text = if (isRunning) getString(R.string.btn_stop)
                else getString(R.string.btn_start)

                // 统计信息
                b.tvCaptureCount.text = getString(R.string.status_count_format, curConfig.captureCount)
                b.tvBattery.text = getString(R.string.status_battery_format, snapshot.battery)
                b.tvStorage.text = getString(R.string.status_storage_format, snapshot.storageGb)
                b.tvTemperature.text = getString(R.string.status_temperature_format, snapshot.temp)

                // 日志（不自动滚底，用户可自由滚动查阅）
                b.tvLog.text = snapshot.logs.ifEmpty { getString(R.string.status_log_empty) }
            }
        }
    }

    /** 每秒刷新的数据快照（IO 线程采集，主线程消费） */
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

        // 运行状态
        binding.tvStatus.text = if (isRunning) getString(R.string.status_running)
        else getString(R.string.status_stopped)
        binding.tvStatus.setTextColor(
            if (isRunning) ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
            else ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
        )

        // 按钮
        binding.btnToggle.text = if (isRunning) getString(R.string.btn_stop)
        else getString(R.string.btn_start)

        // 计数
        binding.tvCaptureCount.text = getString(R.string.status_count_format, config.captureCount)

        // 电量/存储/温度
        val battery = BatteryMonitor.getBatteryPercent(requireContext())
        val storageGb = BatteryMonitor.getStorageRemainingGb(storage.getPhotoDir())
        val temp = BatteryMonitor.getBatteryTemperature(requireContext())
        binding.tvBattery.text = getString(R.string.status_battery_format, battery)
        binding.tvStorage.text = getString(R.string.status_storage_format, storageGb)
        binding.tvTemperature.text = getString(R.string.status_temperature_format, temp)

        // 日志
        binding.tvLog.text = LogBuffer.getFormattedLogs(LogBuffer.ID_MAIN)
            .ifEmpty { getString(R.string.status_log_empty) }
    }

    // ──────────── 开始/停止 ────────────

    private fun toggleCapture() {
        if (config.isRunning) {
            stopCapture()
        } else if (hasCameraPermission()) {
            startCapture()
        } else {
            // Android 14 上 camera type FGS 启动强制要求 CAMERA 权限，未授权直接开始会崩溃
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
