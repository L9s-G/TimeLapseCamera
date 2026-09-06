# API Reference

> 本文档是对 TimeLapseCamera 所有公开接口的说明，供教学参考和后续扩展时使用。

---

## 1. CameraXController

`camera/CameraXController.kt`

CameraX 实现的拍摄控制器，每次 `capture()` 完成"绑定→拍照→释放"全流程。

### 构造函数

```kotlin
CameraXController(context: Context, cameraId: String, shotRotation: Int)
```

| 参数 | 说明 |
|------|------|
| `context` | ApplicationContext，用于获取 CameraManager |
| `cameraId` | 精确到具体镜头的 ID（如 `"0"`, `"1"`），来自 `CameraEnumerator` |
| `shotRotation` | 拍摄方向，值为 `Surface.ROTATION_*` 枚举常量（0/1/2/3） |

### 方法

#### `suspend fun capture(): CaptureResult`

执行一次拍摄，返回 `CaptureResult`。

- 内部持 `CameraMutex` 锁，防止与预览竞态
- 拍摄失败时自动切换同方向备用镜头重试一次
- 线程安全：内部用 `withContext(Dispatchers.Main)` 保证 CameraX 操作在主线程

#### `fun release()`

释放 CameraX 资源，调用 `unbindAll()` 并清理 lifecycle 状态。

---

## 2. IPhotoStorage

`storage/IPhotoStorage.kt`

照片存储接口，定义存储层的统一契约。

### 方法

| 方法 | 说明 |
|------|------|
| `suspend fun save(bitmap: Bitmap, timestamp: Long): String` | 保存正式照片，返回文件路径 |
| `suspend fun saveTestPhoto(bitmap: Bitmap): String` | 保存试拍照片（文件名带毫秒后缀），返回文件路径 |
| `fun getPhotoCount(): Int` | 已存储照片数量 |
| `fun getPhotoDir(): File` | 照片根目录 |
| `fun getLatestPhoto(): File?` | 最新一张照片（按文件名排序） |
| `fun getAllPhotos(): List<File>` | 全部照片列表（按时间倒序） |
| `fun getPhotosPaged(offset: Int, limit: Int): List<File>` | 分页查询，用于 RecyclerView 懒加载 |
| `fun invalidateListCache()` | 使照片列表缓存失效（写入/删除后调用） |
| `fun cleanupOldPhotos(thresholdGb, safeLineGb, maxDeleteCount): Int` | FIFO 清理旧照片，返回实际删除数 |

---

## 3. WatermarkPipeline

`util/WatermarkPipeline.kt`

水印处理流水线，封装"判断水印开关 → 构建选项 → 应用水印/生成错误图"流程。

### 方法

#### `suspend fun process(config, storage, context, result): Bitmap?`

处理拍摄结果，返回带水印的 Bitmap 或错误黑图。

| 输入 | 行为 | 返回值 |
|------|------|--------|
| `Success` + 有水印 | 在 `result.bitmap` 上原地绘制（零额外内存） | 原始 bitmap（已被修改） |
| `Success` + 无水印 | 不修改，直接返回 | 原始 bitmap |
| `Failure` | 创建 1280×720 错误黑图 | 新 bitmap（约 3.5MB） |

**调用方负责 recycle 返回值。**

---

## 4. WatermarkProcessor

`watermark/WatermarkProcessor.kt`

直接在 Bitmap 上绘制水印文字的工具类。

### 方法

#### `fun apply(bitmap: Bitmap, timestamp: Long, options: WatermarkOptions): Bitmap`

在 `bitmap` 上绘制水印，返回同一个对象。

- `bitmap` 必须是 `isMutable == true`，否则抛 `IllegalStateException`
- 左上角：电量/存储/温度（根据 `options` 开关）
- 右下角：自定义文字 + 时间戳

#### `fun createErrorBitmap(timestamp: Long): Bitmap`

创建一张 1280×720 纯黑图，绘制时间戳和"拍摄失败"提示。

---

## 5. CaptureConfig / StorageLocation / WatermarkOptions

### StorageLocation（枚举）

```kotlin
enum class StorageLocation { APP_PRIVATE, DCIM, SD_CARD }
```

| 值 | 路径 | 卸载后 | 系统相册可见 |
|----|------|--------|------------|
| `APP_PRIVATE` | `/Android/data/.../Pictures/TimeLapse/` | 删除 | 否 |
| `DCIM` | `/DCIM/TimeLapse/` | 保留 | 是 |
| `SD_CARD` | `/SD卡/Android/data/.../Pictures/TimeLapse/` | 删除 | 否 |

### WatermarkOptions（data class）

```kotlin
data class WatermarkOptions(
    val customText: String? = null,
    val showBattery: Boolean = false,
    val showStorage: Boolean = false,
    val showTemperature: Boolean = false,
    val batteryPercent: Int = 0,
    val storageRemainingGb: Float = 0f,
    val temperatureCelsius: Float = 0f
)
```

所有布尔字段默认 `false`，调用方按需开启。

### CaptureConfig（data class）

完整字段列表见 `config/CaptureConfig.kt` 类头注释。

关键方法：
- `save(context: Context)` — 全量持久化
- `updateCaptureProgress(context, count, timestamp)` — 局部更新，避免竞态
- `updateRemoteInterval(context, interval)` — 局部更新远程间隔

---

## 6. LogBuffer

`util/LogBuffer.kt`

内存环形缓冲 + 文件持久化的日志工具。

### 方法

| 方法 | 说明 |
|------|------|
| `fun init(logFileDir: File)` | 初始化日志文件路径，加载历史日志（支持目录变更） |
| `fun log(level: String, tag: String, message: String)` | 写入一条日志（同时写内存和文件） |
| `fun getFormattedLogs(): String` | 获取格式化后的日志文本（最多 500 条） |
| `fun clear()` | 清空内存缓冲和日志文件 |

**线程安全**：内部用 `synchronized(logs)` 保证多线程并发写入不交错。`SimpleDateFormat` 在 `log()` 中每次局部创建，避免线程安全问题。

---

## 7. BatteryMonitor

`util/BatteryMonitor.kt`

读取电池和存储状态的工具类（`object` 单例）。

### 方法

| 方法 | 说明 |
|------|------|
| `fun getBatteryPercent(context: Context): Int` | 当前电量百分比（0-100） |
| `fun getBatteryTemperature(context: Context): Float` | 电池温度（°C），通过 sticky broadcast 读取 |
| `fun getStorageRemainingGb(storageDir: File): Float` | 指定目录所在分区的剩余空间（GB） |

---

## 8. CaptureScheduler

`scheduler/CaptureScheduler.kt`

基于 AlarmManager 的拍摄调度器，作为前台服务的备份机制。

### 方法

| 方法 | 说明 |
|------|------|
| `fun scheduleNext(delaySeconds: Int)` | 安排下次拍摄（精确闹钟优先，失败降级为非精确） |
| `fun cancel()` | 取消已安排的闹钟 |

**线程安全**：单例由 `synchronized` 保护，`scheduleNext`/`cancel` 在任意线程调用安全。

---

## 9. CameraMutex

`camera/CameraMutex.kt`

进程级相机互斥锁，串行化所有 CameraX 绑定/解绑操作。

### 方法

```kotlin
suspend fun <T> withLock(block: suspend () -> T): T
```

所有涉及 `ProcessCameraProvider.bindToLifecycle()` 或 `unbindAll()` 的代码路径都必须通过此方法执行。

---

## 10. 其他公共类

### CameraEnumerator

`camera/CameraEnumerator.kt`

枚举设备所有摄像头，输出详细信息（ID/方向/像素/焦距）。

- `enumerate(context): List<CameraInfo>` — 返回所有摄像头列表
- `findBestBackCamera(context): CameraInfo?` — 找像素最高的后置摄像头

### RemoteConfigFetcher

`config/RemoteConfigFetcher.kt`

从 URL 拉取下次拍摄延迟（15-3600 秒整数）。

- `fetchNextInterval(url): Int?` — 成功返回秒数，失败返回 `null`

### PermissionChecker

`util/PermissionChecker.kt`

权限检查 + 跳转系统设置工具。

| 方法 | 说明 |
|------|------|
| `hasCameraPermission(context): Boolean` | 相机权限状态 |
| `hasNotificationPermission(context): Boolean` | 通知权限状态 |
| `canScheduleExactAlarms(context): Boolean` | 精确闹钟权限状态 |
| `isIgnoringBatteryOptimizations(context): Boolean` | 电池优化忽略状态 |
| `appDetailsIntent(context): Intent` | 跳转应用详情页 |
| `batteryOptimizationIntent(): Intent` | 跳转电池优化设置页 |
