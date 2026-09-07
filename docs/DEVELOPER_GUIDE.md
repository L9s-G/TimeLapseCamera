# Developer Guide

> 本文档是 TimeLapseCamera 的开发指南，覆盖架构、核心模块、线程模型、内存管理、权限体系、错误处理及扩展开发，适合作为教学参考和二次开发手册。

---

## 1. 架构总览

### 1.1 三层保活机制

```
用户点击开始
    │
    ▼
┌─────────────────────────────────────────┐
│         CaptureService（前台服务）         │
│  ① 前台通知（IMPORTANCE_DEFAULT）        │  ← 主保活：进程不被系统主动杀死
│  ② START_STICKY（被杀后系统尽量恢复）      │
│  ③ AlarmManager 备份闹钟                 │  ← 兜底：服务被杀后闹钟重启
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│         WatchdogService（独立进程）        │
│  每 60s 检查主服务进程是否存活             │
│  不存活 + isRunning=true → 触发重启闹钟   │
│  isRunning=false 连续 2 次 → 自行退出     │
└─────────────────────────────────────────┘
```

**设计理由**：国产 ROM（小米 MIUI、华为 EMUI 等）对后台进程管理激进，单一保活机制不可靠，三层叠加才能覆盖绝大多数场景。

### 1.2 LogBuffer 自动初始化 + 三身份

```
Application.onCreate()
    └─ LogBuffer.initialize(appContext)    ← 唯一显式调用，Context 注入
              │
              ▼
组件首次 LogBuffer.log(identity, ...)
    └─ LogBuffer 内部 autoInitIfNeeded：
           ├─ 从 context.filesDir 派生日志文件 log_<identity>.txt
           ├─ 加载该文件历史 MAX_SIZE 条到内存 buffer
           ├─ 创建 State，文件路径绑定
           └─ 再写当前日志（内存 + 文件 append）
              │
              ▼
           后续 log() 直接走落盘路径，零时序风险

三身份文件（filesDir/）：
  log_main.txt       主进程所有业务日志
  log_watchdog.txt   守护进程日志
  log_scheduler.txt  AlarmManager 闹钟事件（两进程共用）
```

**关键设计：**
- 外部完全不需要调用任何 init()，所有组件"只管 log"
- getFormattedLogs() 是纯读操作，不触发初始化（StatusFragment 是纯 Reader）
- 固定私有目录，不跟随照片存储路径，避免存储位置切换造成日志丢失

### 1.3 相机生命周期

```
CaptureService
    │
    ├─ CameraXController（每次拍摄创建，拍完 release）
    │     ├─ ProcessCameraProvider（进程级单例，复用）
    │     └─ CameraMutex（锁：防止与预览竞态）
    │
PreviewFragment
    └─ Preview 用例（页面可见时绑定，onDestroyView 时解绑）
```

**关键约束**：`ProcessCameraProvider` 是进程级单例，拍摄服务与预览页共用，任何一方的 `unbindAll()` 都会解绑对方用例。`CameraMutex` 将冲突从"互相打断"降级为"排队等待"。

### 1.4 Bitmap 责任链

```
CameraXController
    ↓ decodeBytes(inMutable=true)
    → mutable Bitmap（原始照片，~8MB @ 12MP）
    ↓
WatermarkPipeline.process()
    ├─ hasWatermark=true → WatermarkProcessor.apply()（原地绘制，零额外内存）
    ├─ hasWatermark=false → 直接返回（不修改）
    └─ Failure → createErrorBitmap()（新创建，~3.5MB）
    ↓
IPhotoStorage.save() / saveTestPhoto()
    ↓ compress(JPEG, 90) → 磁盘文件
    ↓
调用方 finally { bitmap.recycle() }
```

**内存脚印**：峰值 = 单张图大小（~8MB），全程只有 1 个 Bitmap 对象存在。

---

## 2. 核心模块详解

### 2.1 CaptureService

`service/CaptureService.kt`

前台服务，编排拍摄循环，是整个 App 的核心。

**生命周期**：
- `onCreate()`：创建 `serviceScope`、初始化存储、创建通知频道
- `onStartCommand()`：处理 START/STOP 指令，启动 `captureLoop`
- `onDestroy()`：取消协程、释放 WakeLock、停止前台服务

**拍摄循环（captureLoop）**：
```
每轮：
  1. 读取配置（CaptureConfig.load）
  2. 检测存储位置变更 → 重建 storage 实例
  3. FIFO 清理（存储空间不足时）
  4. 远程配置拉取（如有 URL）
  5. 拍摄（CameraXController.capture）
  6. 水印处理（WatermarkPipeline.process）
  7. 存盘（storage.save）
  8. 更新通知倒计时
  9. 设置备份闹钟（CaptureScheduler.scheduleNext）
  10. delay(间隔) → 协程挂起
```

**线程模型**：
- `serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`
- `captureLoop` 在 Default 线程运行
- CameraX 操作通过 `withContext(Dispatchers.Main)` 切换到主线程
- 水印和存盘在 IO 线程执行

### 2.2 WatchdogService

`service/WatchdogService.kt`

独立进程（`android:process=":watchdog"`）的守护服务。

**职责**：
- 每 60 秒检查主服务进程是否存活
- 主服务死亡且 `isRunning=true` 时触发重启闹钟
- `isRunning=false` 连续 2 轮 → 自行退出

**WakeLock 策略**：与主服务一致，全程持有无超时 `PARTIAL_WAKE_LOCK`。

**自我保护**：`onDestroy` 时若 `isRunning=true`，注册 60 秒后自我唤醒闹钟。

### 2.3 CameraXController

`camera/CameraXController.kt`

每次拍摄创建新实例，拍完 `release()` 释放摄像头。

**关键设计**：
- 冷启动等待 300ms（传感器/ISP 稳定时间，覆盖 200-500ms 范围）
- 通过底层 Camera2 API 查询最高 JPEG 分辨率，传给 CameraX ResolutionSelector
- 按 `cameraId` 精确选择摄像头（非粗粒度的 BACK/FRONT）
- 拍摄失败时自动切换同方向备用镜头重试一次

**不手动对调宽高**：`getOutputSizes(JPEG)` 返回的已是 HAL 考虑旋转后的可用尺寸，手动对调会触发 "No available output size" 错误。

### 2.4 WatermarkPipeline

`util/WatermarkPipeline.kt`

封装水印处理流程，消除 PreviewFragment 和 CaptureService 之间的代码重复。

**设计边界**：
- 负责：纯数据转换逻辑（无线程/生命周期依赖）
- 不负责：拍照（由 `camera.capture()` 完成）、保存（调用方选择 `save`/`saveTestPhoto`）、回收（调用方负责）

**副作用说明**：`Success` 且有水印时，`apply()` 会原地修改 `result.bitmap`（在原始 Bitmap 上绘制），这是为了零额外内存分配的设计选择。

### 2.5 存储层

| 类 | 路径 | 实现方式 | 适用场景 |
|----|------|---------|---------|
| `LocalPhotoStorage` | `storage/` | File API，`getExternalFilesDir()` | App 私有目录、SD 卡 |
| `DcimPhotoStorage` | `storage/` | MediaStore（API 29+）/ File API（API 26-28） | DCIM 公共目录 |
| `PhotoStorageFactory` | `storage/` | 根据配置创建对应实现 | 工厂模式入口 |

**缓存策略**：`sortedCache`（`@Volatile`）缓存排序结果，写入/删除后失效，避免相册页每次分页都做 O(N log N) 全量扫描。

### 2.6 LogBuffer（工具类）

`util/LogBuffer.kt`

内存环形缓冲 + 文件持久化，零显式 init。

**设计意图（解决了什么 bug）**：
- 旧设计要求每个 Writer 在合适时机 init(dir, identity)，但双进程架构下 Watchdog 进程调用 CaptureScheduler 时它的日志身份（ID_SCHEDULER）没人显式 init，导致调度日志静默丢失
- StatusFragment 作为纯读者也被要求 init，职责混乱
- 新设计：LogBuffer.initialize(appContext) 在 Application.onCreate() 只做一次，后续所有组件只调 log()，首次调用自动完成身份初始化

**三个身份**：

| 常量 | 文件（filesDir/） | 归属进程 | 记录内容 |
|------|-----------------|---------|---------|
| ID_MAIN | log_main.txt | 主进程 | CaptureService/相机/存储/UI/水印 等 |
| ID_WATCHDOG | log_watchdog.txt | :watchdog | 守护检测、重启闹钟、自行退出 |
| ID_SCHEDULER | log_scheduler.txt | 两进程共用 | AlarmManager 闹钟事件（安排、取消、权限降级） |

**线程/进程隔离**：
- 全局单例 object，但两进程各自一份 JVM 内存，完全独立
- 两进程共用 ID_SCHEDULER 文件 —— 分别 append，Linux 下 append 模式对短行写入是原子的，不会交错

### 2.7 配置系统

`config/CaptureConfig.kt`

- 所有字段有默认值，首次运行不崩
- `data class` + `copy()` 实现不可变配置
- SharedPreferences 单例（`@Volatile` + `synchronized`）避免重复创建
- 局部更新方法（`updateCaptureProgress`、`updateRemoteInterval`）避免竞态丢失更新

**三级回退**：
```
远程值（URL 返回）→ 上次远程值（lastRemoteInterval）→ 本地默认值（intervalSeconds）
```

---

## 3. 线程模型

### 3.1 线程使用规范

| 操作 | 线程 | 原因 |
|------|------|------|
| CameraX bind/unbind | 主线程 | CameraX 强制要求 |
| `ProcessCameraProvider.getInstance()` | 主线程（Future 回调） | CameraX 内部限制 |
| `watermarkProcessor.apply()` | IO 线程 | Bitmap 操作，可能耗时 |
| `storage.save()` | IO 线程 | 文件 IO |
| `BatteryMonitor.getBatteryTemperature()` | 任意线程 | 同步读取，无阻塞 |
| `RemoteConfigFetcher.fetchNextInterval()` | IO 线程 | 网络请求 |

### 3.2 CameraX 必须在主线程

`CameraXController.captureWithCameraId()` 内部用 `withContext(Dispatchers.Main)` 包裹所有 CameraX 操作：

```kotlin
val bitmap = withContext(Dispatchers.Main) {
    // 所有 CameraX 操作在这里
    val provider = ProcessCameraProvider.getInstance(context).await()
    provider.bindToLifecycle(...)
    takePictureAndDecode()
}
```

### 3.3 WakeLock 持有时机

- **CaptureService**：`onStartCommand` 启动时 `acquireWakeLock()`，`onDestroy` 释放
- **WatchdogService**：`onCreate` 时 `acquireWakeLock()`，`onDestroy` 释放
- 全程无超时持有，而非"拍摄瞬间持有"——这是经过实测的教训

---

## 4. 权限体系

### 4.1 权限分类

| 权限 | 类型 | 检测方式 | 跳转目标 |
|------|------|---------|---------|
| CAMERA | 运行时 | `checkSelfPermission` | 应用详情页 |
| POST_NOTIFICATIONS (API 33+) | 运行时 | `NotificationManager.areNotificationsEnabled()` | 通知设置页 |
| SCHEDULE_EXACT_ALARM (API 31+) | 声明 + 运行时 | `AlarmManager.canScheduleExactAlarms()` | 精确闹钟设置页 |
| IGNORE_BATTERY_OPTIMIZATIONS | 声明即可 | `PowerManager.isIgnoringBatteryOptimizations()` | 电池优化设置页 |

### 4.2 静态声明 vs 运行时申请

- **静态声明**（`AndroidManifest.xml`）：所有权限都需要，告知系统 App 需要什么能力
- **运行时申请**：仅 CAMERA 和 POST_NOTIFICATIONS，用户授权后才可用

### 4.3 国产 ROM 兼容

自启动、后台活动等国产 ROM 特有权限无标准 API 检测，设置页只做跳转入口，不显示假状态。

---

## 5. 内存管理

### 5.1 Bitmap 生命周期闭环

```
创建：CameraXController.decodeBytes(inMutable=true)
  ↓
传递：WatermarkPipeline.process()（原地修改或返回新对象）
  ↓
消费：storage.save() 的 compress() 读取像素数据
  ↓
回收：调用方 finally { bitmap.recycle() }
```

**关键保证**：
- 每个 Bitmap 只被 recycle 一次（无双重回收风险）
- 失败路径也走 `finally { recycle() }`（无泄漏）
- 整条管线峰值 = 1 个 Bitmap（零副本设计）

### 5.2 缓存策略

| 缓存 | 位置 | 失效条件 |
|------|------|---------|
| `sortedCache`（照片列表） | `LocalPhotoStorage` / `DcimPhotoStorage` | 写入或删除照片后 `invalidateListCache()` |
| `prefsInstance`（SharedPreferences） | `CaptureConfig` 静态字段 | App 进程存活期间 |

### 5.3 Coil 图片加载

`GalleryFragment` 使用 Coil 加载缩略图：
- 自动按 ImageView 尺寸（120dp）采样，不加载全尺寸 Bitmap
- LRU 缓存管理，浏览深度对内存峰值影响有限
- 不调用 `recycle()`，由 Coil 内部管理生命周期

---

## 6. 错误处理

### 6.1 CaptureResult sealed class

```kotlin
sealed class CaptureResult {
    data class Success(val bitmap: Bitmap, val timestamp: Long) : CaptureResult()
    data class Failure(val message: String, val cause: Throwable? = null) : CaptureResult()
}
```

调用方用 `when (result)` 穷举分支，编译器强制处理所有情况。

### 6.2 回退层次

```
远程配置失败：远程值 → 上次远程值 → 本地默认值
拍摄失败：    主镜头 → 备用镜头 → 黑图占位
写入失败：    打 Log + 释放资源 → 等下一轮重试（不崩溃）
进程被杀：    START_STICKY 恢复 + AlarmManager 备份闹钟重启
```

### 6.3 协程异常传播

- `serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`：子协程失败不取消整个 scope
- `captureLoop` 外层 `catch (e: Throwable)` 兜底，`delay(5000)` 后重试
- `finally { withContext(NonCancellable) { stopForeground/stopSelf() } }` 确保停止时清理不被打断

---

## 7. 扩展开发

### 7.1 新增存储实现

1. 实现 `IPhotoStorage` 接口
2. 在 `PhotoStorageFactory.create()` 中添加分支
3. （可选）在 `StorageLocation` 枚举中添加新值

### 7.2 新增摄像头支持

1. `CameraEnumerator.enumerate()` 自动发现所有镜头，无需修改
2. 若需支持 Camera1，实现 `ICameraController` 接口并注册到工厂

### 7.3 修改水印

1. 修改 `WatermarkProcessor.apply()` 的绘制逻辑
2. 如需新增水印字段，扩展 `WatermarkOptions` data class
3. 在 `SettingsFragment` 中添加对应开关

### 7.4 修改通知样式

1. 修改 `CaptureService.buildNotification()` 中的 `NotificationCompat.Builder` 链
2. 通知频道 ID（`CHANNEL_ID`）和图标（`R.drawable.ic_camera`）需保持一致

---

## 8. 技术栈速查

| 组件 | 库 | 版本/说明 |
|------|-----|---------|
| 语言 | Kotlin | 1.9+ |
| 相机 | CameraX | `camera-camera2`, `camera-lifecycle`, `camera-view` |
| 异步 | Kotlin Coroutines | `coroutines-android`, `coroutines-guava` |
| UI | ViewBinding | AGP 自动生成 |
| 图片加载 | Coil | `coil-compose` |
| 构建 | AGP | 8.0+ |
| 最低 API | 26 (Android 8.0) | |
| 目标 API | 34 (Android 14) | |
