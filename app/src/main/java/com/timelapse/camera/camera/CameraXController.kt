package com.timelapse.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.timelapse.camera.model.CaptureResult
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * CameraX 实现的拍摄控制器。
 *
 * 为什么从 Camera2 迁移到 CameraX？
 * - CameraX 是 Google 官方推荐的现代相机库，底层封装了 Camera2
 * - 代码量比手写 Camera2 少 60%+，更适合教学
 * - 自动处理设备兼容性，不用手动处理各种厂商差异
 * - 预览（Preview）和拍照（ImageCapture）是独立用例，可按需组合
 *
 * 功耗策略：每次 capture() 内部完成 "绑定用例 → 拍照 → 解绑释放" 全流程，
 * 拍完后摄像头完全释放，间隔期间零硬件功耗。
 *
 * 教学要点：
 * - ProcessCameraProvider 是单例，需要 Future 异步获取
 * - ImageCapture.takePicture() 是回调 API，用 suspendCancellableCoroutine 转挂起函数
 * - CameraX 内部有自己的线程池，不需要手动管理 HandlerThread
 * - LifecycleRegistry 手动管理生命周期（Service 不是 LifecycleOwner，需要自己造一个）
 * - 按 cameraId 选摄像头，而非粗粒度的 facing（多镜头设备精确到具体镜头）
 */
class CameraXController(
    private val context: Context,
    private val cameraId: String,
    /**
     * 用户设定的拍摄方向，值为 Surface.ROTATION_* 枚举常量（0/1/2/3），
     * 直接传给 setTargetRotation，与 CameraX 值域一致，无转换。
     * 注意：不是角度值（90° 是 ROTATION_90 = 1，而非 90）。
     */
    private val shotRotation: Int
) : ICameraController {

    companion object {
        private const val TAG = "CameraXController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleRegistry: LifecycleRegistry? = null
    private var lifecycleOwner: LifecycleOwner? = null

    override suspend fun capture(): CaptureResult = CameraMutex.withLock {
        LogBuffer.log("I", TAG, "开始拍摄, cameraId=$cameraId")
        val result = captureWithCameraId(cameraId)
        if (result is CaptureResult.Success) return@withLock result

        // 失败了尝试同方向其他摄像头作为备用
        val fallbackId = findFallbackCameraId(cameraId)
        if (fallbackId != null) {
            LogBuffer.log("W", TAG, "主镜头失败，切换备用镜头 cameraId=$fallbackId")
            val fallbackResult = captureWithCameraId(fallbackId)
            if (fallbackResult is CaptureResult.Success) return@withLock fallbackResult

            val firstMsg = (result as? CaptureResult.Failure)?.message ?: "未知错误"
            val secondMsg = (fallbackResult as? CaptureResult.Failure)?.message ?: "未知错误"
            return@withLock CaptureResult.Failure(
                "主镜头失败: $firstMsg；备用镜头失败: $secondMsg"
            )
        }

        result
    }

    /**
     * 使用指定 ID 的摄像头执行一次完整拍摄。
     * 内部完成 "初始化 CameraX → 绑定 ImageCapture → 拍照 → 释放" 全流程。
     *
     * 线程安全：CameraX 的 unbindAll/bindToLifecycle 必须在主线程执行。
     * 无论调用者在哪个线程，内部用 withContext(Dispatchers.Main) 保证。
     */
    private suspend fun captureWithCameraId(id: String): CaptureResult {
        return try {
            val bitmap = withContext(Dispatchers.Main) {
                val provider = ProcessCameraProvider.getInstance(context).await()
                cameraProvider = provider

                val owner = object : LifecycleOwner {
                    override val lifecycle: Lifecycle get() = lifecycleRegistry!!
                }
                lifecycleRegistry = LifecycleRegistry(owner).apply { currentState = Lifecycle.State.RESUMED }
                lifecycleOwner = owner

                // 1. 获取该镜头物理传感器的最高 JPEG 输出尺寸（通过底层 Camera2 API）
                val targetSize = getMaxJpegSize(id)

                // 2. 直接使用最高分辨率，不手动对调长宽：CameraX 的 setTargetRotation 会内部处理旋转映射
                //    之前曾尝试手动对调，结果触发了 "No available output size" 错误
                //    正确做法：传 rawSize + setTargetRotation(rotation)，CameraX 自行计算
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_NONE)
                    )
                    .setAllowedResolutionMode(
                        ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    )
                    .build()

                // 3. 组装 ImageCapture
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(resolutionSelector)
                    .setJpegQuality(90)
                    .setTargetRotation(shotRotation)  // ← 关键：使用用户设定的方向
                    .build()
                imageCapture = capture

                // 4. 按 cameraId 精确选择摄像头（CameraFilter 是 CameraX 精确选择摄像头的正确方式）
                val cameraSelector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter { info ->
                            val camera2Info = Camera2CameraInfo.from(info)
                            camera2Info.cameraId == id
                        }.ifEmpty { cameraInfos }
                    }
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner!!, cameraSelector, capture)

                // 5. 冷启动等待：摄像头刚绑定后传感器/ISP 需要几毫秒稳定，
                //    不加此延时在部分低端机上会出现首张黑屏或模糊。
                //    300ms 为实测经验值，覆盖大多数设备（低-中端机约 200-500ms）；
                //    极端设备可调为此值乘以系数，或未来扩展为配置项。
                kotlinx.coroutines.delay(300L)

                takePictureAndDecode()
            }

            CaptureResult.Success(bitmap, System.currentTimeMillis())
        } catch (e: Throwable) {
            LogBuffer.log("E", TAG, "拍摄失败: ${e.javaClass.simpleName}: ${e.message}")
            CaptureResult.Failure("拍摄失败: ${e.message}", e as? Exception ?: RuntimeException(e))
        } finally {
            withContext(Dispatchers.Main) { release() }
        }
    }

    /**
     * 执行拍照并解码为 mutable Bitmap。
     * ImageCapture.takePicture 是回调 API，转为挂起函数。
     *
     * 线程模型：回调内只在主线程做字节拷贝（内存 memcpy，微秒级）并立即
     * close ImageProxy；JPEG 解码（12-48MB，百毫秒级）切到 IO 线程，
     * 避免试拍等 UI 场景下阻塞主线程造成卡顿。
     *
     * 不做像素旋转：JPEG 的 EXIF 方向信息由 CameraX 写入，系统相册会自动旋转显示。
     * 这样节省内存（不需要创建第二个 Bitmap），也保证方向一致性。
     */
    private suspend fun takePictureAndDecode(): Bitmap {
        val bytes = suspendCancellableCoroutine { cont ->
            LogBuffer.log("I", TAG, "takePicture 调用")
            imageCapture?.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        LogBuffer.log("I", TAG, "onCaptureSuccess 回调")
                        // 主线程只做字节拷贝，解码放 IO 线程
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        image.close()
                        if (cont.isActive) cont.resume(data)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        LogBuffer.log("E", TAG, "onError 回调: ${exception.message}")
                        if (cont.isActive) cont.cancel(exception)
                    }
                }
            ) ?: run {
                LogBuffer.log("E", TAG, "imageCapture 为 null，无法拍照")
                cont.cancel(IllegalStateException("imageCapture 未初始化"))
            }
        }
        return withContext(Dispatchers.IO) { decodeBytes(data = bytes) }
    }

    /**
     * 将 JPEG 字节流解码为 mutable Bitmap。
     * inMutable=true 保证后续水印模块可直接在 Bitmap 上绘制，零额外内存。
     */
    private fun decodeBytes(data: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply {
            inMutable = true
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
            ?: throw RuntimeException("JPEG 解码失败")
    }

    /**
     * 获取指定摄像头支持的最高 JPEG 拍照分辨率。
     *
     * 为什么不用 CameraX 自带的？
     * - CameraX 默认不选传感器最高分辨率
     * - 需要从底层 Camera2 API 查询真实能力，再回设给 CameraX
     *
     * 注意：返回值直接原样传给 ResolutionStrategy，禁止按旋转角手动对调宽高——
     * getOutputSizes(JPEG) 返回的已是 HAL 考虑旋转后的可用尺寸，手动对调会因
     * aspect ratio 不匹配触发 "No available output size" 错误（早期实测踩坑）。
     * 方向修正是 EXIF 的职责，相册读取时自动旋转。
     */
    private fun getMaxJpegSize(cameraId: String): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = configs?.getOutputSizes(ImageFormat.JPEG)
            if (!sizes.isNullOrEmpty()) {
                sizes.maxByOrNull { it.width * it.height }!!
            } else {
                Size(4032, 3024)
            }
        }.getOrElse {
            LogBuffer.log("W", TAG, "getMaxJpegSize 失败: ${it.message}，使用兜底分辨率 4032x3024")
            Size(4032, 3024)
        }
    }

    /**
     * 找同方向的备用摄像头（主摄像头失败时切换）。
     * 返回 null 表示没有备用。
     */
    private fun findFallbackCameraId(primaryId: String): String? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val primaryFacing = runCatching {
            cameraManager.getCameraCharacteristics(primaryId)
                .get(CameraCharacteristics.LENS_FACING)
        }.getOrNull() ?: return null

        return cameraManager.cameraIdList.firstOrNull { id ->
            id != primaryId && runCatching {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == primaryFacing
            }.getOrDefault(false)
        }
    }

    override fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        lifecycleRegistry?.currentState = Lifecycle.State.DESTROYED
        lifecycleRegistry = null
        lifecycleOwner = null
    }
}
