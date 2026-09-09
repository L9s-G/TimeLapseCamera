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

### 1.2 LogBuffer 双进程模型

```
Application.onCreate()
    └─ LogBuffer.initialize(appContext)    ← 唯一显式调用（幂等）
              │
              ▼
initialize() 一次性完成：
    ├─ 探测当前进程 key（main / :watchdog）
    ├─ 打开对应日志文件，从尾部 50KB 加载历史到内存 ring
    └─ 打开常驻裸 append 流（FileOutputStream，不包 buffer）
              │
              ▼
后续所有组件直接 log(level, tag, msg)，无需传进程身份：
    ├─ 写内存 ring（环形缓冲，本进程 UI 实时读）
    └─ 写常驻 append 流（write() 直接把字节交内核 page cache）

两个日志文件（filesDir/）：
  log_main.txt       主进程所有业务日志（含 AlarmManager 闹钟事件）
  log_watchdog.txt   守护进程日志
```

**关键设计：**
- 一进程一写者文件，跨进程同文件竞争天然消失；进程 key 在 initialize() 探测一次并缓存，热路径只读缓存
- 常驻裸 append 流无 JVM 堆 buffer：write() 直接交内核 page cache，进程被硬杀也丢不了，也就不存在"flush 时机"问题
- 超过 500KB 触发截断到 200KB（close 旧流 → RandomAccessFile("rw") 读回保留窗口 + setLength → 重开 append 流）
- 进程被杀由内核自动关闭 fd，无需显式 close；重启时 initialize() 重新打开
- 跨进程读（UI 读 watchdog 日志）由 StatusFragment 直接用 RandomAccessFile 读文件尾部 50KB，不经 LogBuffer
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
  1. 读取用户配置（CaptureConfig.load）+ 运行时状态（RuntimeState.load）
  2. 若 RuntimeState.isRunning=false → 用户已停止，退出循环
  3. 检测存储位置变更 → 重建 storage 实例
  4. FIFO 清理（见下方状态机）
  5. 远程配置拉取（如有 URL）→ 成功后 RuntimeState.updateRemoteInterval
  6. 拍摄（CameraXController.capture）
  7. 水印处理（WatermarkPipeline.process）
  8. 存盘（storage.save）→ 成功后 RuntimeState.updateCaptureProgress
  9. 更新通知倒计时
  10. 设置备份闹钟（CaptureScheduler.scheduleNext）
  11. delay(间隔) → 协程挂起

FIFO 清理状态机（0.5 段）：
  shouldClean = RuntimeState.isCleaning || 剩余空间 < threshold
  调用 storage.cleanupOldPhotos(safeLine, initialRemainingGb=剩余空间) → CleanupResult
  nowCleaning = deleted>0 && result.remainingGb < safeLine
  状态切换时（nowCleaning != isCleaning）：
    - 打 FIFO开始/结束 日志（📀空间 | ◐阈值 | ⬤安全线 | 📷全目录照片数）
    - RuntimeState.updateCleaning(nowCleaning)  ← 跨轮保持
  防死循环：本轮 0 删除（无文件/权限不足）→ nowCleaning 必为 false，强制释放
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

**FIFO 清理（`IPhotoStorage.cleanupOldPhotos`，默认实现）**：

按「最旧月份优先」删除照片，控制磁盘空间在用户阈值/安全线之内。

- **管线模式**：调用方（`CaptureService`）传入 `initialRemainingGb`（入口值），方法返回 `CleanupResult(deleted, remainingGb, scannedCount)`。方法内部 StatFs **仅 1 次**（出口），入口用传入值判断，零系统调用——避免与调用方重复查剩余空间。
- **批处理**：先收集候选文件（凑够 `maxDeleteCount` 即停），再批量删除，删除过程不穿插 StatFs。
- **跨轮续删**：由 `CaptureService` 的 `isCleaning` 状态机驱动——一轮没删到安全线则下一轮继续，`isCleaning` 跨轮保持，防止「删一点把空间顶回阈值上方后、下轮被守卫吞掉」。
- **防死循环**：本轮 0 删除（无文件 / 权限不足）→ `isCleaning` 强制复位，否则会无限循环。
- **空文件夹不删（tradeoff）**：故意保留空的月份目录——① 不占存储空间，② 其他应用可能向同目录写文件，贸然 `folder.delete()` 有误删风险，③ 保留作为「该月曾拍过照」的记录。
- **紧凑日志**（图标区分全量/本轮）：
  - `FIFO未完成[📀X.XG | 📂N]`：本批未达标（📂=本轮扫描候选数）
  - `删除失败[📀X.XG | 📂N]`：本批 0 删除（N=0 无文件 / N>0 权限不足）
  - `FIFO开始/结束[📀 | ◐阈值 | ⬤安全线 | 📷全目录照片数]`：由调用方在 `isCleaning` 切换轮打印（📷=`getPhotoCount()` 全目录总数，低频）

### 2.6 LogBuffer（双进程日志系统）

`util/LogBuffer.kt`

双进程模型下的日志系统：一个进程恰好一个写者文件，常驻裸 append 流写盘。

**历史背景（为什么从三身份改为双进程）**：
- 旧设计有「三身份」（main / watchdog / scheduler），AlarmManager 闹钟事件单独写 `log_scheduler.txt`，由 main 和 :watchdog 两进程**共用同一文件** append
- 问题：跨进程共用一个文件依赖「Linux 短行 append 原子性」这一隐含假设，且 scheduler 日志身份无人显式 init，容易静默丢日志
- 新设计：取消共享文件，改为**双进程模型**——每个进程只写自己那个文件，scheduler（AlarmManager）日志并入 `log_main.txt`（调度器实际在主进程运行）。跨进程同文件竞争从此不存在

**两个日志文件**：

| 进程 key | 文件（filesDir/） | 归属进程 | 记录内容 |
|------|-----------------|---------|---------|
| PROC_MAIN | log_main.txt | 主进程 | CaptureService/相机/存储/UI/水印 + AlarmManager 闹钟事件 |
| PROC_WATCHDOG | log_watchdog.txt | :watchdog | 守护检测、重启闹钟、自行退出 |

**写路径（常驻裸流）**：
- 一条常驻裸 `FileOutputStream`（不包 buffer），每条 log 只 `write()`，不 open/close
- `write()` 直接把字节交给内核 page cache，没有 JVM 堆 buffer，进程被硬杀也丢不了，不存在"flush 时机"问题
- 内存追踪 `fileSize` 仅作触发器，超过 500KB 时截断到 200KB（close 旧流 → `RandomAccessFile("rw")` 以实际文件长度定位保留窗口、读回+setLength → 重开 append 流），并借这次冷路径把触发器对回磁盘真实值

**读路径**：
- 本进程读：`getFormattedLogs()` 读内存 ring（无 IO），带脏标记缓存，稳态零分配零 GC
- 跨进程读（StatusFragment 读 watchdog 日志）：调用方直接用 `RandomAccessFile` 读文件尾部 50KB，不经 LogBuffer

**进程隔离**：
- 全局 `object` 单例，但两进程各自一份 JVM 内存，完全独立
- 一个进程恰好写一个文件，不再有跨进程写竞争

**线程安全**：内存 ring + 文件操作共享全局 `lock`，多协程并发写不交错；`SimpleDateFormat` 每次局部创建实例使用。

### 2.7 配置系统

`config/CaptureConfig.kt` + `config/RuntimeState.kt`

**写入者隔离原则**：用户可编辑配置与运行时状态分离，杜绝全量 save 的丢失更新竞态。

| 类 | 职责 | 写入者 | 读取者 |
|----|------|--------|--------|
| `CaptureConfig` | 用户设置（11 个字段：间隔、摄像头、水印、存储位置/阈值/安全线等） | `SettingsFragment.save()` | 所有 |
| `RuntimeState` | 运行时状态（5 个字段：`isRunning`、`isCleaning`、`captureCount`、`lastCaptureTime`、`lastRemoteInterval`） | Service / Watchdog 局部更新 | Service / Watchdog / UI 显示 |

**设计理由**：`SettingsFragment` 持有 `CaptureConfig` 的快照，用户修改任意设置调 `save()` 全量写回——若 runtime 字段混在其中，`save()` 会把过期的 `isRunning`/`captureCount` 冲回去（丢失更新）。分离后 `CaptureConfig.save()` 不碰 runtime key，彻底消除竞态。

**局部更新方法**（避免全量 save 竞态，每个方法只写 1 个 key）：

| 方法 | 写入 key | 调用方 |
|------|---------|--------|
| `RuntimeState.updateRunning(ctx, isRunning)` | `is_running` | `StatusFragment` 开始/停止按钮 |
| `RuntimeState.updateCleaning(ctx, isCleaning)` | `is_cleaning` | `CaptureService` FIFO 切换轮 |
| `RuntimeState.updateCaptureProgress(ctx, count, time)` | `capture_count` + `last_capture_time` | `CaptureService` 每次拍摄后 |
| `RuntimeState.updateRemoteInterval(ctx, interval)` | `last_remote_interval` | `CaptureService` 远程配置下发后 |

**三级回退**（拍摄间隔）：
```
远程值（URL 返回）→ 上次远程值（RuntimeState.lastRemoteInterval）→ 本地默认值（CaptureConfig.intervalSeconds）
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

- **CaptureService**：`onStartCommand` 启动时 `acquireWakeLock()`；优雅停止由 `onDestroy()` 释放，进程被杀时内核在进程回收时自动释放 held WakeLock
- **WatchdogService**：`onCreate` 时 `acquireWakeLock()`；同理，优雅停止走 `onDestroy()`，进程被杀由内核自动回收
- 全程无超时持有，而非"拍摄瞬间持有"——这是经过实测的教训
- 不依赖任何应用层兜底：PARTIAL_WAKE_LOCK 是内核对象，进程被杀时随进程一起回收，不会残留

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
| `prefsInstance`（SharedPreferences） | `CaptureConfig` / `RuntimeState` 各自静态字段（指向同一文件） | App 进程存活期间 |

> `CaptureConfig` 与 `RuntimeState` 各维护一份 `@Volatile prefsInstance`，但都通过
> `getSharedPreferences("timelapse_config", ...)` 获取——Android 系统内部对该名称池化，
> 两份引用最终指向同一 SharedPreferences 实例，字段 key 不重叠，线程安全。

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
