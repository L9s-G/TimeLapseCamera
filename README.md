# 延时相机 (TimeLapseCamera)

> **版本**：v1.0.7 | **最低 API**：26 (Android 8.0) | **目标 API**：34 (Android 14)

旧手机变身延时拍摄设备 —— 长期定期拍照，记录植物生长或城市发展。

## 核心特性

- **底部导航 4 Tab**：状态 / 预览 / 相册 / 设置，功能分区清晰
- **三层保活**：前台服务通知（主）+ START_STICKY（恢复）+ AlarmManager 备份闹钟
- **双进程守护**：主服务 + `:watchdog` 独立进程守护，互监控
- **倒计时通知**：`setChronometerCountDown` 让系统自动渲染倒计时，零额外功耗
- **按需启停摄像头**：每次拍摄重新初始化摄像头，拍完立即释放，间隔期零硬件功耗
- **丰富水印**：时间戳 + 自定义文字 + 电量/存储/温度（可开关）
- **失败回退**：镜头自动切换 + 黑图占位 + 写入不崩溃 + 进程被杀恢复
- **远程配置下发**：通过 URL 动态调整拍摄间隔（URL 格式校验 + 实际抓取验证）
- **模块插拔设计**：相机、存储、配置均可独立替换，适合教学
- **自动化日志体系（LogBuffer）**：双进程日志（main/watchdog），常驻裸 append 流无需 flush，状态页两 Tab 切换查看，SAF 目录一键导出

## UI 架构

底部导航 4 个 Tab，默认进入「状态」页：

```
┌─────────────────────────────┐
│                             │
│        Fragment 内容         │
│  （状态/预览/相册/设置）      │
│                             │
├─────────────────────────────┤
│  📊 状态 | 📷 预览 | 🖼 相册 | ⚙ 设置  │
└─────────────────────────────┘
```

| Tab | 功能 | 典型使用场景 |
|-----|------|-------------|
| **状态** | 运行状态、拍摄统计、电量/存储/温度、开始/停止、两 Tab 日志 | 用户打开 App 第一眼，确认运行正常 |
| **预览** | 实时画面 + 「立即拍一张」试拍 | 安装时构图对齐，验证水印效果 |
| **相册** | 网格浏览历史照片 | 回看记录，检查故障时段 |
| **设置** | 拍摄参数、水印开关、权限状态、远程配置 | 调整参数，检查权限 |

## 架构总览

```
┌──────────────────────────────────────────────────┐
│              TimeLapseApplication                 │
│         LogBuffer.initialize(appContext)          │
│         （全项目唯一的启动级初始化）                │
└──────────────────────────────────────────────────┘
                  │ 主进程 + :watchdog 各自独立执行
                  ▼
┌──────────────────────────────────────────────────┐
│                  MainActivity                      │
│         （底部导航 + 4 个 Fragment）                 │
│  StatusFragment  PreviewFragment  GalleryFragment  │
│  SettingsFragment                                  │
│   │                                                │
│   └─ StatusFragment: 纯读者，两 Tab 切换 + SAF 目录导出  │
└──────────────────┬─────────────────────────────────┘
                   │ startForegroundService(ACTION_START)
                   ▼
┌──────────────────────────────────────────────────┐
│         CaptureService（主进程前台服务）             │
│    通知栏显示倒计时 → 进程不被系统杀死               │
│    PROC_MAIN 日志 (log_main.txt)                   │
│  ┌────────────────────────────────────────────┐  │
│  │            captureLoop (协程循环)             │  │
│  │                                            │  │
│  │  取配置 → 远程间隔 → 拍照 → 水印 → 存盘       │  │
│  │     │                                    │  │
│  │     ├── 更新倒计时通知（系统自动渲染）         │  │
│  │     ├── scheduleNext() 设置备份闹钟          │  │
│  │     └── delay(间隔) → WakeLock 全程持有      │  │
│  └────────────────────────────────────────────┘  │
│                                                  │
│  【互相守护】Watchdog 不存活 → startService 恢复   │
└──────────────────────────────────────────────────┘
        │                                                │
        │ 启动 :watchdog 独立进程                         │ 被杀后重启
        ▼                                                ▼
┌──────────────────────────────────────────────────┐  AlarmManager
│           WatchdogService（守护进程）               │   备份闹钟
│  PROC_WATCHDOG 日志 (log_watchdog.txt)           │◀── CaptureReceiver
│  每 60s 检查主服务存活；onDestroy 设 60s 闹钟交接   │
│  scheduleNext() 设置备份闹钟                       │
└──────────────────────────────────────────────────┘

三层保活:
  ① 前台服务通知（主）── 进程不被系统主动杀死
  ② START_STICKY     ── 被杀后系统尽量恢复
  ③ AlarmManager 备份 ── 每次拍摄后设闹钟，服务被杀则闹钟重启

  AlarmManager ──闹钟到期──▶ CaptureReceiver ──▶ startForegroundService
  开机自启    ──BOOT_COMPLETED──▶ BootReceiver ──▶ startForegroundService

LogBuffer 两个日志文件（均位于 context.filesDir/，应用私有目录）：
  ① PROC_MAIN     → log_main.txt       主服务/相机/存储/UI + AlarmManager 闹钟事件
  ② PROC_WATCHDOG → log_watchdog.txt    守护进程

Application.onCreate() 中 initialize() 一次性完成：探测进程 key → 打开文件
并加载尾部 50KB 历史 → 打开常驻 append 流；之后所有组件直接 log(level, tag, msg)，
无需传进程身份，也无需显式 close（进程被杀由内核自动关 fd）。
```

## 目录结构

```
├── .github/workflows/
│   └── android.yml              # GitHub Actions: 云端编译 + 发布 APK
├── gradle/wrapper/
│   ├── gradle-wrapper.jar       # Gradle Wrapper 二进制
│   └── gradle-wrapper.properties # 指定 Gradle 版本（8.0）
├── gradlew                      # Unix 构建脚本
├── gradlew.bat                  # Windows 构建脚本
├── app/build.gradle             # AGP 配置 + 依赖声明
├── build.gradle                 # 根构建文件（AGP/Kotlin 插件版本）
│
app/src/main/java/com/timelapse/camera/
├── TimeLapseApplication.kt      # Application 入口：LogBuffer 全局初始化
├── MainActivity.kt              # 主界面：底部导航 + Fragment 切换
│
├── ui/                          # ── UI 层（Fragment）──
│   ├── status/StatusFragment.kt    #   状态页：运行状态 + 统计 + 两 Tab 日志 + SAF 导出
│   ├── preview/PreviewFragment.kt  #   预览页：实时画面 + 试拍
│   ├── gallery/GalleryFragment.kt  #   相册页：网格照片列表
│   └── settings/SettingsFragment.kt #  设置页：参数 + 权限状态
│
├── config/                       # ── 配置模块 ──
│   ├── CaptureConfig.kt          #   拍摄配置 (data class + SharedPreferences 持久化)
│   └── RemoteConfigFetcher.kt    #   远程配置拉取 (URL → 返回秒数 15~3600)
│
├── camera/                       # ── 相机模块 ──
│   ├── ICameraController.kt      #   接口：capture() → CaptureResult
│   ├── CameraXController.kt     #   CameraX 实现 (每次拍完即释放摄像头)
│   ├── CameraEnumerator.kt       #   摄像头枚举（Camera2：ID/方向/像素/焦距）
│   └── CameraMutex.kt            #   进程级相机互斥锁（拍摄与预览排队）
│
├── watermark/                    # ── 水印模块 ──
│   ├── WatermarkOptions.kt       #   水印配置 data class（显示哪些信息）
│   └── WatermarkProcessor.kt     #   Canvas 绘制：左上状态 + 右下时间戳
│
├── storage/                      # ── 存储模块 ──
│   ├── IPhotoStorage.kt           #   接口：save/getLatest/getAll/...
│   ├── LocalPhotoStorage.kt      #   App 私有目录实现 (按年月归档)
│   ├── DcimPhotoStorage.kt       #   DCIM 公共目录实现 (MediaStore 写入)
│   └── PhotoStorageFactory.kt    #   工厂：根据配置选择存储实现
│
├── util/                         # ── 工具类 ──
│   ├── BatteryMonitor.kt         #   电量/存储/温度读取
│   ├── LogBuffer.kt              #   日志：双进程模型（main/watchdog）+ 常驻 append 流 + SAF 导出
│   └── PermissionChecker.kt      #   权限检查 + 跳转系统设置
│
├── scheduler/                    # ── 调度模块（备份）──
│   ├── CaptureScheduler.kt       #   AlarmManager 备份闹钟（服务被杀后重启）
│   ├── CaptureReceiver.kt        #   闹钟接收器 → 重启前台服务
│   └── BootReceiver.kt           #   开机自启 → 启动前台服务
│
├── service/                      # ── 服务模块 ──
│   └── CaptureService.kt        #   持久化前台服务：协程循环 + 倒计时通知
│
└── model/                        # ── 数据模型 ──
    └── CaptureResult.kt         #   拍摄结果 (sealed class: Success / Failure)
```

## 关键设计决策

### 1. 为什么用持久化前台服务而不是纯 AlarmManager？

| 对比项 | 纯 AlarmManager | 持久化前台服务 + 闹钟备份 |
|--------|----------------|------------------------|
| 进程保活 | 无进程常驻，闹钟到期才创建 | 前台通知保持进程存活 |
| 国产 ROM 省电 | 易被杀闹钟 | 通知保活 + 闹钟双保险 |
| 用户可见性 | 无通知，用户不知道是否在运行 | 倒计时通知实时可见 |
| 功耗 | 间隔期零功耗 | 间隔期进程空闲（微安级），可忽略 |
| 可靠性 | 依赖闹钟不被系统拦截 | 三层保活：通知 + STICKY + 闹钟 |

长期拍摄（1年）可靠性 >> 微量功耗节省，前台服务是更优选择。

### 2. 为什么从 Camera2 迁移到 CameraX？

| 维度 | 手写 Camera2 | CameraX |
|------|------------|---------|
| 代码量 | ~280 行 | ~180 行 |
| 设备兼容性 | 需手动处理各厂商差异 | 官方封装，自动兼容 |
| 预览实现 | 需手写 Surface + CaptureSession | PreviewView 一行搞定 |
| 教学清晰度 | 能看到底层原理，但代码噪音多 | 核心逻辑突出，更容易理解 |

CameraX 底层就是 Camera2，功能完全一致，但代码量减少 40%+，
教学上学生能把注意力放在"延时相机的架构"而不是"Camera2 的繁琐配置"。

#### CameraX 旋转与分辨率的正确用法

通过长期测试，我们厘清了 CameraX 内部的工作机制。正确做法只有四步：

```
1. 选定摄像头（按 cameraId）
2. 查询该摄像头的最高 JPEG 分辨率 + 手机当前旋转角
3. 直接传 rawSize，设置 setTargetRotation(rotation)
4. CameraX 内部根据 rotation 自行计算数据流投影方向，输出正确方向的图片
```

**关键理解：不需要在 Java 层手动对调长宽**

```kotlin
val rawMaxSize = getMaxJpegSize(cameraId)   // 例：3840×2160（16:9 横向传感器）
val currentRotation = getRotation(context)  // 0/90/180/270

ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(rawMaxSize, ResolutionStrategy.FALLBACK_RULE_NONE)
            )
            .setAllowedResolutionMode(
                ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
            )
            .build()
    )
    .setTargetRotation(currentRotation)   // ← 只需告诉 CameraX 当前方向
    .build()
```

CameraX 收到正确的 `targetRotation` 后，会在底层自动完成：
- 数据流方向映射（如竖屏时自行交换宽高）
- EXIF 方向写入
- 最终输出正确方向的照片

**为什么不能手动对调尺寸？** 之前曾尝试在 Java 层根据旋转角对调长宽传入，结果触发了 "No available output size" 错误。原因是 `getOutputSizes(JPEG)` 返回的是 Camera HAL 抽象层给出的已考虑旋转的可用尺寸列表，手动对调会导致 aspect ratio 与实际输出不匹配。

**关于分辨率大小的说明**：系统相机 App 能拍出 4208×3120，是因为它使用厂商私有 API 绕过标准限制。CameraX 通过标准 API 获取的 JPEG 最大输出为 3840×2160（4K），这是硬件抽象层的正常表现。

**ResolutionStrategy 的 fallback 规则**（查官方文档：`ResolutionStrategy`）：

| 常量 | 行为 |
|------|------|
| `FALLBACK_RULE_NONE` | 指定的尺寸不可用时直接报错，不降级 |
| `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` | 先找更高一档，再找更低一档（默认） |
| `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER` | 先找更低一档，找不到才用更高的 |
| `FALLBACK_RULE_CLOSEST_LOWER` | 只找更低一档 |

本项目使用 `FALLBACK_RULE_NONE`：确保不会悄悄降级到次一档分辨率，找不到精确匹配时明确报错，方便排查问题。

### 3. 水印为什么加电量/存储/温度？

延时照片几年后回看时，你想知道的不只是"几点拍的"，还有"当时什么状况"：

- **电量**：照片序列断了，回看水印能知道是没电了还是摄像头坏了
- **存储**：最后几张是黑图，回看水印能知道是磁盘满了还是镜头挂了
- **温度**：夏天放窗边过热导致拍摄失败，水印里有温度一眼就能判断

这些信息写进水印的独特价值是：**照片本身就携带了完整的上下文**，不依赖 App 或日志文件。

### 4. 为什么每次拍摄重新初始化摄像头？

传统相机 App 保持摄像头常开以实现预览和快速拍摄。但延时拍摄间隔可能长达 1 小时，
持续保持摄像头开启会浪费大量功耗。每次拍摄重新初始化的模式：

```
休眠(间隔期) → 唤醒 → 开摄像头(0.5s) → 拍照(0.1s) → 关摄像头 → 存盘 → 休眠
```

拍摄过程仅占整个间隔的极小比例，功耗接近最优。

### 5. 最低 API 26 (Android 8.0) 的理由

- CameraX 稳定支持 API 21+，但 API 26+ 行为更一致
- `getExternalFilesDir()` 无需存储权限，简化权限流程
- 前台服务 + 通知通道 API 成熟
- 覆盖 2017 年后绝大多数旧手机

## 失败回退机制

长期运行的 App，失败处理比成功路径更重要。以下是各个环节的回退策略：

### 总览

```
远程配置失败 → 三级回退：远程值 → 上次远程值 → 本地配置值
      ↓
拍摄失败  → 自动切换备用镜头重试一次 → 还失败 → 生成黑图占位
      ↓
写入失败  → 打 Log + 释放资源 → 等下一轮重试（绝不崩溃）
      ↓
进程被杀  → START_STICKY 恢复 + AlarmManager 备份闹钟重启
```

### 1. 远程配置失败（三级回退）

| 层级 | 触发条件 | 行为 |
|------|---------|------|
| 第一级 | URL 返回有效整数（15-3600） | 使用该值，并更新 lastRemoteInterval |
| 第二级 | URL 失败/超时/返回非数字/返回值超出 15-3600，但有上次有效值 | 回退到 lastRemoteInterval |
| 第三级 | URL 失败且无上次记录 | 回退到本地配置的 intervalSeconds |

### 2. 拍摄失败（镜头切换 + 黑图占位）

**镜头切换救场**：主摄像头失败时，自动切换到另一个镜头重试一次。
- 成功了就用备用镜头的照片
- 失败了继续走黑图占位逻辑
- 下一轮仍然从主镜头开始（每次重新走完整流程，行为可预测）

**黑图占位**：两个镜头都失败时，生成一张 1280×720 的纯黑图，右下角绘制时间戳 + "拍摄失败"文字。

为什么要做黑图？
- 用户翻照片序列时，能区分"App 正常唤醒了但摄像头坏了" vs "App 根本没跑"
- 纯黑图 + 明确文字，比单纯打 Log 更直观
- 尺寸较小（720p），不浪费磁盘空间

### 3. 写入失败（释放资源 + 等下一轮）

磁盘满、IO 错误、SD 卡拔出…… 写入可能失败。处理原则：
- **绝不崩溃**：用 `runCatching` 包裹，失败只打 Log
- **释放资源**：写入失败时手动 recycle Bitmap，避免内存泄漏
- **继续循环**：下一轮到了再试一次（也许用户清理了空间）

### 4. 进程被杀（三层保活）

详见前文"三层保活机制"章节。

## 权限与保活设置

设置页的「权限与保活」分组展示 4 项可检测的权限状态：

| 权限 | 检测方式 | 点击跳转 |
|------|---------|---------|
| 相机权限 | `checkSelfPermission(CAMERA)` | 应用详情页 |
| 通知权限 | `NotificationManager.areNotificationsEnabled()` | 通知设置页 |
| 精确闹钟 | `AlarmManager.canScheduleExactAlarms()` | 精确闹钟设置页 |
| 忽略电池优化 | `PowerManager.isIgnoringBatteryOptimizations()` | 电池优化设置页 |

还有一个「系统设置」入口统一跳应用详情页，用于设置自启动、后台活动等
国产 ROM 特有的选项（这些没有标准 API 检测，只做跳转入口，不显示假状态）。

## 深度专题：Bitmap 内存优化

这是本项目最值得深入的性能优化案例。以 1920×1080 ARGB_8888 计算：
单像素 4 bytes × 1920 × 1080 ≈ **8.3 MB / 张**

### 优化前：峰值 3 张图（~25MB）

```
decodeImage()                     WatermarkProcessor.apply()
     │                                   │
     ▼                                   ▼
[raw Bitmap] ──旋转──▶ [rotated Bitmap] ──copy──▶ [watermarked Bitmap]
     │                    │                              │
     │  raw.recycle()      │  输入被 copy 后 recycle       │
                            ▼                              ▼
                     （中间短暂 2 张）                LocalPhotoStorage.save()
                                                       compress + recycle
```

**问题根源**：WatermarkProcessor 为了"不修改输入"做了一次全尺寸 copy，
但输入 bitmap 是刚从相机出来的、没有其他引用的临时对象，copy 完全没有必要。

### 优化后：峰值 1 张图（~8MB）

```
CameraXController
  inMutable = true  ← 关键：解码为可变 Bitmap
     │
     ▼
[mutable Bitmap] ──旋转──▶ [mutable Bitmap] （同对象或新对象，旧的立即回收）
     │
     ├── WatermarkProcessor.apply() → Canvas 直接绘制，零额外内存
     │
     └── LocalPhotoStorage.save() → compress + recycle（责任链终点）
```

**两处改动**：
1. `BitmapFactory.Options.inMutable = true` → 解码出可变 Bitmap
2. `WatermarkProcessor` 移除 `bitmap.copy()` → 直接在输入上绘制

### 什么时候才需要 copy？

- 原始 Bitmap 还有其他引用（如缓存池复用）
- 需要保留原始图像（如先显示原图再加水印）
- 函数是公共 API，调用方可能依赖输入不变

本项目的流水线是单向的（相机→水印→存盘→回收），copy 是纯粹的浪费。

### 延伸讨论：GC 压力视角

除了"峰值内存"，另一个值得关注的维度是**分配速率**——单位时间产生多少需要 GC 回收的临时对象。

单次拍摄产生的临时对象：
- `ByteArray`（JPEG 原始数据，~2MB）
- `BitmapFactory` 内部临时缓冲区
- `compress()` 过程中的临时 ByteBuffer
- 每轮创建的 `CameraXController` 等对象

这些都是短生命周期对象，理论上全部在 Young Gen 回收，不触发 Full GC。但在**两个极端场景**下需要留意：

1. **短间隔拍摄**（如 15 秒）：每分钟分配几十 MB，旧手机（2GB 内存）Young Gen 小，可能更频繁地 GC。不过延时相机的典型场景是小时级间隔，实际影响可以忽略。

2. **系统内存压力大时**：GC 阈值降低，本来能在 Young Gen 回收的对象可能被提早晋升到 Old Gen，增加 Full GC 概率。但这是系统级问题，App 层面能做的有限。

**结论**：当前设计在典型使用场景下（分钟/小时级间隔）GC 压力可以忽略。只有当你要做秒级延时视频时，才需要考虑 `inBitmap` 复用、对象池等进一步优化。

## 深度专题：Android 息屏保活与摄像头调用

Android 的后台保活和相机调用是生态中最复杂的两个坑。本项目在长期测试中踩了多个真实 bug，以下记录关键问题和解决思路，供教学参考。

### 问题一：息屏后无法正常拍照（CPU 秒睡）

**现象**：手机锁屏后，App 停止拍摄，日志和照片均无新增；每隔数小时偶尔恢复一张，时机不确定。解锁后打开 App，循环恢复正常。

**根因分析**：

```
锁屏 → Android 进入 Doze 模式 → CPU 周期性休眠
    ↓
前台服务通知（IMPORTANCE_LOW）→ 优先级太低，被系统忽略
    ↓
协程 delay() 挂起 → CPU 休眠期间 delay 不推进
    ↓
拍摄时机错过 → 闹钟备份（AlarmManager）触发但启动太慢 → 部分周期被跳过
```

我们最初的设计是**每次拍摄瞬间持有 WakeLock**（~3 秒），间隔期不持锁，认为"前台通知就够了"。但实测发现：
- 国产 ROM（小米 MIUI、华为 EMUI）在息屏后**不尊重前台通知**，仍可能杀进程
- `IMPORTANCE_LOW` 的通知在锁屏上**几乎不可见**，系统也更容易降权它
- `delay()` 挂起期间没有 WakeLock，CPU 秒睡，闹钟启动时间隔已经过了

**解决方案**：

```kotlin
// 服务启动时持有 WakeLock，贯穿整个运行期
override fun onStartCommand(...) {
    if (captureJob == null || !captureJob!!.isActive) {
        acquireWakeLock()  // ← 全程持有，not 每轮重新 acquire/release
        captureJob = serviceScope.launch { captureLoop() }
    }
}

override fun onDestroy() {
    releaseWakeLock()   // ← 优雅停止时释放；进程被杀时内核自动回收
    ...
}
```

**代价与权衡**：
| | 间歇 WakeLock（旧方案） | 全程 WakeLock（新方案） |
|--|--|--|
| 息屏拍摄稳定性 | ❌ 漏拍严重 | ✅ 稳定 |
| 间隔期 CPU 功耗 | ~1μA（深度睡眠） | ~50-100mA（轻度唤醒） |
| 1小时间隔额外耗电 | 0 | ~3-5mAh（约 0.5%） |

对小时级延时拍摄，全程 WakeLock 的额外功耗可忽略不计，换取稳定性是完全值得的。

### 问题二：CameraX 的旋转与分辨率陷阱

**现象**：拍出的照片比系统相机裁切严重，分辨率明显下降。

**根因**：`getOutputSizes(JPEG)` 返回的是传感器的**自然方向**尺寸，不是最终输出方向的尺寸：

```
后置摄像头传感器自然方向 = 横向
  getOutputSizes → [4208×3120, 4208×3120, ...]  ← 宽 > 高

但用户竖屏持机拍摄：
  targetRotation = ROTATION_0（横向）
  → CameraX 输出 4208×3120 的水平图
  → 部分国产 ROM 的 CameraX 实现：
     ① 不写入正确的 EXIF 方向信息
     ② 或直接按错误的方向输出
  → 结果：照片被强制裁切成错误的宽高比
```

**正确做法**：把 `getOutputSizes(JPEG)` 返回的最大尺寸**原样**传给 `ResolutionStrategy`，不要在 Java 层根据旋转角对调长宽：

```kotlin
// rawMaxSize = getOutputSizes(JPEG) 中的最大项，直接使用，不对调宽高
ResolutionStrategy(rawMaxSize, ResolutionStrategy.FALLBACK_RULE_NONE)
```

**为什么不能手动对调尺寸？** `getOutputSizes()` 返回的是 Camera HAL 抽象层给出的
已考虑旋转的可用尺寸列表，手动对调会导致 aspect ratio 与实际输出不匹配，
触发 "No available output size" 错误（本项目早期实测踩过这个坑）。

方向修正交给 EXIF：CameraX 把方向信息写入 JPEG 元数据，系统相册显示时自动
旋转，App 层无需干预。

> 勘误注记：本节早期版本曾记载"按旋转角对调长宽"的做法并提及
> `getAdjustedSizeAndRotation()` 方法——那是已被证伪的错误方案，相关代码
> 早已删除，此处按现行实现改写。

### 问题三：通知在锁屏不可见

**现象**：拍摄正常运行时，锁屏上看不到任何通知，解锁后才能看到。

**根因**：`IMPORTANCE_LOW` + `PRIORITY_LOW` 的通知优先级太低，系统在锁屏上会隐藏它们。

**解决方案**：通知 Channel 改为 `IMPORTANCE_DEFAULT`，并在锁屏上可见：

```kotlin
NotificationChannel(CHANNEL_ID, ..., NotificationManager.IMPORTANCE_DEFAULT)
```

> 注意：如果锁屏通知仍然不可见，需要在手机设置里单独检查：
> **设置 → 应用 → 延时相机 → 通知管理 → 锁屏通知**（各厂商 ROM 路径不同）。

---

## 深度专题：Android 生命周期与初始化时机

这是 Android 开发中最容易踩坑的地方，也是本项目在开发过程中真实经历的 bug。

### 问题根源：UI 生命周期 ≠ 业务生命周期

开发者（尤其是初学者）容易把三件事搞混：

1. **UI 可见性**（Fragment `onResume`/`onPause`）—— 用户是否能看到界面
2. **业务执行**（Service `captureLoop`）—— 拍摄是否在进行
3. **数据初始化**（属性赋值时机）—— config/storage 何时可用

这三者是独立的，但开发者经常把它们绑在一起。

### 我们踩过的真实 bug

本项目经历了三个阶段，恰好展示了这个问题的演变：

#### 阶段 1：`by lazy`（碰巧能工作）

```kotlin
// 原始代码
private val storage by lazy { LocalPhotoStorage(applicationContext) }
```

`by lazy` 延迟到首次访问，碰巧 `onResume` 才访问，此时 context 已可用。
**问题**：这个"碰巧"是隐式假设，代码里完全看不出来。一旦重构改变了访问时机，bug 立即暴露。

#### 阶段 2：重构改成 `lateinit`（暴露 ordering bug）

```kotlin
// 重构后
private lateinit var storage: IPhotoStorage

override fun onViewCreated(...) {
    storage = PhotoStorageFactory.create(...)  // ← 这里消费 config，但 config 还没赋值！
    // → UninitializedPropertyAccessException 崩溃
}

override fun onResume() {
    config = CaptureConfig.load(...)  // ← 太晚了，onViewCreated 已经崩了
}
```

`by lazy` 的延迟保护消失了，`lateinit` 需要开发者手动保证初始化时机，但没做到。

#### 阶段 3：`onCreate()` 初始化 + 单一数据源（正确模式）

```kotlin
private lateinit var config: CaptureConfig
private lateinit var storage: IPhotoStorage

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    config = CaptureConfig.load(requireContext())       // ← 最早安全位置
    storage = PhotoStorageFactory.create(requireContext(), config)
}

override fun onResume() {
    super.onResume()
    reloadFromDisk()  // ← 刷新，不是初始化
}

private fun reloadFromDisk() {
    config = CaptureConfig.load(requireContext())       // ← 读一次
    storage = PhotoStorageFactory.create(requireContext(), config)  // ← 从同一份 config 派生
}
```

### 三条规则

#### 规则 1：分清"需要什么"→ 决定"在哪里初始化"

| 依赖什么 | 初始化位置 | 例子 |
|---------|-----------|------|
| 只需要 Context（`onAttach` 之后可用） | `onCreate()` | config、storage、camera |
| 需要视图（`onCreateView` 之后可用） | `onViewCreated()` | binding、adapter、点击监听 |

**原则：在最早安全的位置初始化，不要等到"刚好要用"才赋值。**

#### 规则 2：UI 是观察者，不是控制器

```
❌ 错误心智模型：  Fragment → 触发拍摄 → Service 执行
✅ 正确心智模型：  Fragment → 发指令(Intent) → Service 自己决定何时执行
                  Fragment → 读 config → 显示状态
```

- `StatusFragment` 的 `onResume()` 只做**显示刷新**（reload config + updateUI），不做业务决策
- `SettingsFragment` 只写 config 和发 `startForegroundService` 指令，不直接控制拍摄
- `CaptureService` 的 `captureLoop` **完全独立于 Fragment 生命周期**——Fragment 可以不存在，Service 照常拍

#### 规则 3：单一数据源

```kotlin
❌ 两次独立读取，config 和 storage 可能不一致：
   storage = create(CaptureConfig.load(...))   // 第 1 次读
   config = CaptureConfig.load(...)            // 第 2 次读

✅ 一次读取，config 和 storage 同源：
   config = CaptureConfig.load(...)           // 读一次
   storage = create(config)                    // 从同一份 config 派生
```

`reloadFromDisk()` 方法封装了这个模式，保证 `config` 和 `storage` 永远来自同一次磁盘读取。

### Fragment 的双重生命周期

Fragment 比 Service 更复杂，因为它有**两套生命周期**：

```
Fragment 生命周期:  onCreate → ... → onDestroy
View 生命周期:      onCreateView → onViewCreated → ... → onDestroyView
```

| 属性类别 | 依赖 | 初始化 | 清理 |
|---------|------|--------|------|
| 不需要 View | Context | `onCreate()` | `onDestroy()`（或不需要） |
| 需要 View | binding | `onViewCreated()` | `onDestroyView()` 置 null |

本项目的实践：
- `config`、`storage` → `onCreate()` 初始化（只需要 Context）
- `_binding` → `onCreateView()` 初始化，`onDestroyView()` 置 null（需要 View）
- `onResume()` → 只调 `reloadFromDisk()` 刷新数据，不做初始化

### 时序保证

```
onCreate()         ← 所有 lateinit 在此完成（context 可用）
  ↓
onCreateView()     ← 创建视图
  ↓
onViewCreated()    ← 只做 view 绑定和监听设置
  ↓
onResume()         ← 刷新数据（reloadFromDisk），但属性已在 onCreate 就绪
  ↓
...（协程、刷新循环等都可以安全访问所有属性）
```

**核心保证**：无论哪个生命周期回调或协程访问 `config` / `storage`，都不会遇到 `UninitializedPropertyAccessException`。`onResume()` 的重新赋值是"刷新"而非"初始化"——即使 `onResume` 不执行，属性也已经有值了。

## 构建与安装

### 用 Android Studio（推荐）

1. 打开 Android Studio → File → Open → 选择项目目录
2. 等待 Gradle 同步完成
3. 连接手机（开启 USB 调试）→ 点击 Run

### 用命令行

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 安装到已连接的设备
./gradlew installDebug
```

### 用 GitHub Actions 云端编译（无需本地环境）

项目配置了 GitHub Actions workflow（`.github/workflows/android.yml`），push 到 `main` 分支后自动编译 Debug APK。

**适用场景**：学生在瘦客户端（Chromebook、iPad）上开发，本地没有 Android Studio 和 SDK。

**工作流设计**：

| 触发条件 | 执行内容 | 产物 |
|---------|---------|------|
| push 到 main/master | 编译 Debug APK | 30 天临时 artifact |
| push tag `v*` | 编译 + 创建 GitHub Release | 永久 Release 附带 APK |

**关键技术决策**：

1. **用 Gradle Wrapper 而非强制安装 Gradle** — `./gradlew` 由 `gradle-wrapper.properties` 控制版本，CI 和本地一致
2. **setup-gradle@v5 而非 v6** — v6 把缓存组件改为闭源专有许可，v5 是最后一个 MIT 许可版本
3. **不指定 `gradle-version`** — 让项目自己的 Wrapper 决定，避免与 AGP 版本不匹配

**CI 调试注意事项**：

1. **失败时先看详细日志**：进入 Actions 页面 → 点失败的 run → 展开失败的 step → 读完整错误
2. **区分错误类型**：环境问题（改 workflow）vs 编译问题（改代码）vs SDK 问题（对齐 compileSdk）
3. **编译错误一次修完**：读全部错误，不要修一个 push 一次
4. **找根因不做表面修复**：类型不匹配 → 查继承层次；API 不存在 → 查官方文档
5. **warning 也要修**：Node.js 废弃警告未来会变成 error

> 本项目 CI 配置过程中踩过的坑（7 轮失败 → 成功）已记录在 SKILL.md 第 10 节，可作为教学案例。

**下载已编译的 APK**：

到 [Releases 页面](https://github.com/gzdanny/TimeLapseCamera/releases) 下载最新版本的 APK。

## 使用指南

1. 打开 App，默认进入「状态」页
2. 切到「预览」Tab，对准景物，点「立即拍一张」验证构图和水印效果
3. 切到「设置」Tab，调整拍摄间隔、摄像头方向、水印开关等
4. 在「权限与保活」分组检查各项权限，点击未授权的项跳转授予
5. 回到「状态」页，点击「开始拍摄」
6. 手机可以锁屏放置，App 会自动在设定间隔唤醒拍照
7. 随时切到「相册」Tab 查看历史照片

### 远程配置协议

如填写了远程配置 URL，每次拍摄前会 GET 该 URL，期望返回一个 15-3600 的整数作为下次拍摄延迟（秒）。

- URL 返回有效整数 → 使用该值作为下次间隔
- URL 返回无效或请求失败 → 回退到上次有效值，再回退到本地配置

示例服务器响应：`300`（表示下次 5 分钟后拍摄）

### 照片存储位置

支持三种预设存储位置，在设置页选择：

| 位置 | 路径 | 权限 | 卸载后 | 系统相册可见 |
|------|------|------|--------|------------|
| App 私有目录 | `/Android/data/.../Pictures/TimeLapse/` | 无 | 照片删除 | 否 |
| 系统相册 (DCIM) | `/DCIM/TimeLapse/` | API 26-28 需权限 | 照片保留 | 是 |
| SD 卡 | `/SD卡/Android/data/.../Pictures/TimeLapse/` | 无 | 照片删除 | 否 |

App 私有目录和 SD 卡通过 `File.mkdir()` 按年月归档；DCIM 通过 `MediaStore.RELATIVE_PATH` 指定子目录，由系统管理。

```
TimeLapse/
  ├── 2026-03/
  │   ├── 20260301_080000.jpg
  │   └── 20260301_090000.jpg
  └── 2026-04/
      └── ...
```

**存储策略的教学要点**：
- App 私有目录：`getExternalFilesDir()` 无需权限，File API 直接操作，最简单
- DCIM 公共目录：API 29+ 必须用 `MediaStore` API（Scoped Storage），API 26-28 用 File API + `WRITE_EXTERNAL_STORAGE`
- SD 卡：`getExternalFilesDirs()` 返回数组，`[0]` 是内部存储，`[1]+` 是 SD 卡，复用 App 私有目录逻辑
- 工厂模式：`PhotoStorageFactory` 根据配置创建不同实现，调用方只依赖 `IPhotoStorage` 接口

### 日志文件位置（LogBuffer）

为了避免与存储位置耦合、消除"未 init 先写"的时序 bug，日志文件固定保存在**应用私有目录** `context.filesDir/` 下，一个进程恰好写一个文件：

| 进程 | 文件名 | 记录内容 |
|------|--------|---------|
| PROC_MAIN | `log_main.txt` | 主进程所有业务：CaptureService、相机、存储、水印、UI、AlarmManager 闹钟事件等 |
| PROC_WATCHDOG | `log_watchdog.txt` | watchdog 独立进程的守护检测、重启决策、自我退出 |

> 日志不跟随照片存储路径的理由：照片可能放 SD 卡或 DCIM，日志的访问模式完全不同——需要保证稳定可写、私有、不被媒体扫描器打扰。直接放在 `filesDir` 是最简单可靠的。

**初始化机制**：仅在 `TimeLapseApplication.onCreate()` 调一次 `LogBuffer.initialize(appContext)`，一次性完成：探测进程 key → 打开文件并加载尾部 50KB 历史 → 打开常驻 append 流。之后任何组件直接 `log(level, tag, msg)` 即可，无需传进程身份，也无需显式 close（进程被杀由内核自动关 fd）。StatusFragment 作为纯读者，**不管理 LogBuffer 生命周期**，只在 UI 上做两 Tab 切换展示和导出。

**导出**：状态页「导出」按钮通过 SAF 目录选择器（`OpenDocumentTree`）让用户选择保存位置，直接读 `filesDir/` 下的日志文件（绕开 LogBuffer）写入所选目录（带时间戳文件名），可以存云盘或转发分享。

## 扩展方向

| 方向 | 实现方式 |
|------|---------|
| 自动清理旧照片 | 新增 `FifoPhotoStorage` 实现 `IPhotoStorage` |
| 云端上传 | 新增 `CloudPhotoStorage` 实现 `IPhotoStorage` |
| 兼容更老设备 | 新增 `Camera1Controller` 实现 `ICameraController` |
| 位置水印 | `WatermarkOptions` 增加 GPS 参数 |
| 延时视频连播 | GalleryFragment 增加连播功能 |
| 远程监控 | 新增 HTTP API，展示拍摄状态和最近照片 |

## 技术栈

- **语言**：Kotlin
- **最低 API**：26 (Android 8.0)
- **目标 API**：34 (Android 14)
- **异步**：Kotlin Coroutines
- **相机**：CameraX (Preview + ImageCapture)
- **UI**：ViewBinding + Material Components + Fragment
- **图片加载**：Coil
- **保活**：前台服务 + START_STICKY + AlarmManager 备份
- **通知**：setChronometerCountDown（系统自动倒计时）
