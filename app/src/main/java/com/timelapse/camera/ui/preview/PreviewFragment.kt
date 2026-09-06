package com.timelapse.camera.ui.preview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.timelapse.camera.R
import com.timelapse.camera.camera.CameraMutex
import com.timelapse.camera.camera.CameraXController
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.databinding.FragmentPreviewBinding
import com.timelapse.camera.model.CaptureResult
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.LogBuffer
import com.timelapse.camera.util.WatermarkPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 预览页 Fragment —— 构图对齐 + 试拍验证。
 *
 * 功能：
 * - CameraX Preview 用例实时预览画面
 * - 「立即拍一张」试拍：走与正常拍摄完全相同的管线（CameraXController → 水印 → 存储）
 *   唯一区别：不循环、不等待，文件名格式与正式拍摄对齐（yyyyMMdd_HHmmss.jpg），
 *   试拍额外加毫秒后缀（yyyyMMdd_HHmmss_SSS.jpg）以保证唯一性
 *
 * 功耗设计：
 * - 只在页面可见时绑定预览用例
 * - 切换到其他 Tab 时 onStop() 自动解绑，摄像头完全释放
 *
 * 教学要点：
 * - 试拍代码复用 CaptureService 的拍摄管线，确保验证的是真实流程
 * - 试拍照片用固定文件名，方便用户在相册中快速定位检查
 */
class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(
                requireContext(), R.string.perm_camera_denied, Toast.LENGTH_SHORT
            ).show()
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
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCapture.setOnClickListener {
            takeTestPhoto()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        reloadFromDisk()
    }

    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 异步持锁解绑：onDestroyView 时 viewLifecycleOwner 作用域已死，用 fragment 作用域兜底。
        // 若拍摄服务正在拍照（持锁），此解绑会排队等待，不会打断拍摄
        lifecycleScope.launch {
            CameraMutex.withLock { cameraProvider?.unbindAll() }
            cameraProvider = null
        }
        _binding = null
    }

    // ──────────── 相机权限 ────────────

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ──────────── 相机预览 ────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            viewLifecycleOwner.lifecycleScope.launch {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                val cameraSelector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter { info ->
                            val camera2Info = Camera2CameraInfo.from(info)
                            camera2Info.cameraId == config.cameraId
                        }
                    }
                    .build()

                // 持有相机互斥锁：拍摄服务可能正在拍照（unbindAll 会解绑对方用例）
                try {
                    CameraMutex.withLock {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            viewLifecycleOwner, cameraSelector, preview
                        )
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "启动预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ──────────── 试拍（与 CaptureService 相同的管线）────────────

    /**
     * 试拍流程与 CaptureService.captureLoop() 的单次拍摄完全一致：
     * 1. 释放预览（CameraXController 内部会 unbindAll）
     * 2. CameraXController.capture() → CaptureResult（主线程，CameraX 要求）
     * 3. WatermarkProcessor.apply() 加水印（IO 线程）
     * 4. storage.saveTestPhoto() 保存到用户配置的存储位置（IO 线程）
     * 5. 重新绑定预览（主线程）
     *
     * 线程模型：lifecycleScope 默认 Dispatchers.Main，CameraX 操作在主线程执行；
     * 仅水印处理和文件存储切换到 Dispatchers.IO。
     */
    private fun takeTestPhoto() {
        binding.btnCapture.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // ── 1. 释放预览（主线程，持锁防止打断拍摄服务）──
                LogBuffer.log("I", TAG, "试拍开始，释放预览")
                CameraMutex.withLock { cameraProvider?.unbindAll() }

                val timestamp = System.currentTimeMillis()

                // ── 2. 拍摄（主线程，CameraX 操作必须在主线程）──
                val camera = CameraXController(
                    requireContext(),
                    config.cameraId,
                    config.shotRotation
                )
                val result = camera.capture()
                LogBuffer.log("I", TAG,
                    "拍摄完成: ${if (result is CaptureResult.Success) "成功" else "失败"}")

                // ── 3. 水印处理（统一调用 WatermarkPipeline）──
                LogBuffer.log("I", TAG, "开始水印处理")
                val watermarkedBitmap = WatermarkPipeline.process(
                    config = config,
                    storage = storage,
                    context = requireContext(),
                    result = result
                )

                // ── 4. 保存试拍照片 ──
                LogBuffer.log("I", TAG, "写入存储")
                val savedPath = watermarkedBitmap?.let {
                    storage.saveTestPhoto(it)
                } ?: run {
                    LogBuffer.log("E", TAG, "水印处理失败，无法保存")
                    return@launch
                }
                LogBuffer.log("I", TAG, "保存完成: $savedPath")

                // ── 4. 重新绑定预览 + 显示结果（主线程）──
                // 注意：先 load() 再 recycle() 不是必须顺序——
                // Coil 从文件路径（savedPath）加载，不依赖 bitmap 对象；
                // 但保留此顺序可作为"先消费、后回收"的清晰示范。
                val b = _binding ?: return@launch
                b.btnCapture.isEnabled = true
                startCamera()

                b.cardTestResult.visibility = View.VISIBLE
                b.ivTestResult.load(savedPath)
                // recycle 放在 load() 之后：Bitmap 已 compress 写入磁盘，
                // Coil 从文件路径加载，bitmap 对象此时可安全回收。
                watermarkedBitmap?.recycle()

                b.tvTestResult.text = if (result is CaptureResult.Success) {
                    getString(R.string.preview_test_saved)
                } else {
                    getString(R.string.preview_test_fail)
                }
                LogBuffer.log("I", TAG, "试拍流程全部完成")
            } catch (e: Throwable) {
                LogBuffer.log("E", TAG, "试拍异常: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                val b = _binding
                if (b != null) {
                    b.btnCapture.isEnabled = true
                    b.cardTestResult.visibility = View.VISIBLE
                    b.tvTestResult.text = "试拍失败: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    companion object {
        private const val TAG = "PreviewFragment"
        fun newInstance() = PreviewFragment()
    }
}
