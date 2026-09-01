# API 核查清单

> 项目级知识库。规划阶段一次性核查所有主要 API，新增功能时增量更新。
> 每条记录必须包含：用途、核查结论（含坑点/注意事项）、官方文档链接。

---

## 1. 相机 CameraX (androidx.camera:* 1.4.1)

| API | 用途 | 核查结论 | 文档 |
|-----|------|---------|------|
| `ProcessCameraProvider.getInstance(context)` | 获取相机提供者单例 | 返回 `ListenableFuture<ProcessCameraProvider>`（Guava），**不是** Play Services 的 `Task<T>`。协程 `await()` 扩展需用 `kotlinx-coroutines-guava`，**不能用** `kotlinx-coroutines-play-services` | [CameraX 概览](https://developer.android.com/training/camerax) |
| `ResolutionSelector` + `ResolutionStrategy` | 设置拍照分辨率 | `setTargetResolution()` 已废弃，部分设备上行为异常（如返回 1920x1920）。正确做法：`ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(maxSize, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))`，再 `ImageCapture.Builder().setResolutionSelector(selector)` | [配置选项](https://developer.android.com/media/camera/camerax/configuration) |
| `ImageCapture.takePicture(executor, callback)` | 拍照 | 回调在指定 executor 上执行。主线程安全。`OnImageCapturedCallback.onCaptureSuccess(image)` 返回 `ImageProxy`，用完必须 `close()` | [ImageCapture](https://developer.android.com/reference/androidx/camera/core/ImageCapture) |
| `ProcessCameraProvider.bindToLifecycle(owner, selector, ...useCases)` | 绑定用例 | 第一个参数是 `LifecycleOwner` 接口，**不是** `Lifecycle`。`LifecycleRegistry` 继承自 `Lifecycle`，不能直接传入。需要构造一个匿名 `LifecycleOwner` 返回 lifecycle | [ProcessCameraProvider](https://developer.android.com/reference/androidx/camera/lifecycle/ProcessCameraProvider) |
| `provider.unbindAll()` | 解绑所有用例 | 必须在主线程调用。Service 中使用需切 `Dispatchers.Main` | 同上 |
| `ImageProxy.imageInfo.rotationDegrees` | 照片旋转角度 | CameraX 返回的 JPEG 可能需要旋转校正。`rotationDegrees` 是传感器方向到显示方向的角度差 | [ImageInfo](https://developer.android.com/reference/androidx/camera/core/ImageInfo) |
| `CameraManager.cameraIdList` + `CameraCharacteristics` | 枚举所有摄像头 | 不需要相机权限。可读取传感器尺寸、焦距、支持的输出分辨率等。用于排查"为什么拍出来不是主摄分辨率" | [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics) |

---

## 2. 存储

| API | 用途 | 核查结论 | 文档 |
|-----|------|---------|------|
| `MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL_PRIMARY)` | DCIM 目录写入入口 | API 29+ Scoped Storage 必须用 MediaStore。用 `RELATIVE_PATH` 指定子目录（如 `DCIM/TimeLapse/202608`），`IS_PENDING=1` 写入中，写完改 `IS_PENDING=0` | [MediaStore](https://developer.android.com/reference/android/provider/MediaStore) |
| `ContentResolver.insert(uri, values)` | 创建 MediaStore 条目 | 返回 `Uri?`，可能为 null（权限不足/空间满），必须判空 | [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) |
| `Environment.getExternalStoragePublicDirectory(DIRECTORY_DCIM)` | 获取 DCIM 路径 | API 29+ 直接访问需 Scoped Storage 适配。App 自己创建的文件可用 File API 读取 | [Environment](https://developer.android.com/reference/android/os/Environment) |
| `StatFs` | 读取分区可用空间 | `availableBytes` 返回整个分区的可用字节数，不是单个目录配额 | [StatFs](https://developer.android.com/reference/android/os/StatFs) |

---

## 3. 系统服务

| API | 用途 | 核查结论 | 文档 |
|-----|------|---------|------|
| `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` | 读取电量百分比 | API 21+，返回 0-100。需要 `BATTERY_SERVICE` 系统服务 | [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager) |
| 电池温度 | 读取电池温度 | **没有** `BATTERY_PROPERTY_TEMPERATURE` 常量。正确做法：注册 `ACTION_BATTERY_CHANGED` sticky broadcast（`registerReceiver(null, IntentFilter(...))`），从 `EXTRA_TEMPERATURE` 读取，单位 0.1°C，除以 10 得摄氏度 | [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager) |
| `AlarmManager.setExactAndAllowWhileIdle()` | 精确定时唤醒 | Doze 模式下也能唤醒。配合 `PendingIntent.getBroadcast()` 使用。API 31+ 需要 `SCHEDULE_EXACT_ALARM` 权限 | [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager) |
| `PendingIntent.FLAG_IMMUTABLE` / `FLAG_UPDATE_CURRENT` | PendingIntent flag | Android 12+ 创建 `PendingIntent` 必须指定可变性。AlarmManager 触发的广播用 `FLAG_IMMUTABLE`，需要更新 extra 时用 `FLAG_UPDATE_CURRENT \| FLAG_IMMUTABLE` | [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent) |

---

## 4. 协程

| API | 用途 | 核查结论 | 文档 |
|-----|------|---------|------|
| `kotlinx-coroutines-guava` 的 `ListenableFuture.await()` | Guava Future 转挂起函数 | CameraX / AndroidX 架构组件的异步操作返回 `ListenableFuture`，用此扩展。**不要**和 `kotlinx-coroutines-play-services`（Firebase/Play Services `Task<T>` 用）搞混 | [kotlinx-coroutines-guava](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-guava/) |
| `suspendCancellableCoroutine` | 回调 API 转挂起函数 | 比 `suspendCoroutine` 多了取消支持。需要在 `cont.invokeOnCancellation` 中清理资源 | [suspendCancellableCoroutine](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html) |
| `CancellationException` | 协程取消 | 协程取消时抛出，**不应被普通 catch 吞掉**。`catch (e: Exception)` 会漏掉它（它是 `RuntimeException` 子类，会被 Exception 捕获）。正确做法：先 `catch (e: CancellationException) { throw e }`，再 `catch (e: Exception)` | [协程取消](https://kotlinlang.org/docs/cancellation-and-timeouts.html) |

---

## 5. 生命周期

| API | 用途 | 核查结论 | 文档 |
|-----|------|---------|------|
| `LifecycleRegistry` | 手动管理生命周期 | 继承自 `Lifecycle`，**不是** `LifecycleOwner`。Service / 非 lifecycle 组件使用时，需自己实现 `LifecycleOwner` 接口返回该 registry | [LifecycleRegistry](https://developer.android.com/reference/androidx/lifecycle/LifecycleRegistry) |
| `startForegroundService()` + `startForeground()` | 前台服务 | Android 8.0+ 必须用 `startForegroundService()` 启动，5 秒内调用 `startForeground(id, notification)`，否则 ANR | [前台服务](https://developer.android.com/guide/components/foreground-services) |
| `START_STICKY` | Service 被杀后自动重启 | `onStartCommand` 返回 `START_STICKY`，系统会在资源充足时重启服务。重启后 intent 为 null | [Service](https://developer.android.com/reference/android/app/Service) |

---

## 维护规则

1. **新增功能前**：列出涉及的主要 API → 查官方文档 → 填入此表 → 再写代码
2. **踩坑后**：把坑点补充到对应 API 的"核查结论"中
3. **版本升级**：依赖库升级时，重新核查受影响的 API
4. **审查阶段**：对照本表检查代码，确保用法与结论一致
