# Foreground Service Enhancement - 自动召回前台功能

## 问题背景

用户反馈了两个问题：

1. **通知点击重启问题**：点击下拉通知栏的Foreground Service气泡时，会重启Activity导致直播录制中断
2. **后台按键控制问题**：当app在后台时，物理按键（CAMERA、VOLUME_UP/DOWN）无法触发相应的功能

## 解决方案

### 1. 通知点击优化 ✅

**修改位置**：`ThinkletForegroundService.kt` - `createNotification()` 方法

**修改内容**：
```kotlin
// 之前（会导致Activity重启）
val intent = Intent(this, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

// 之后（恢复到现有Activity）
val intent = Intent(this, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
}
```

**效果**：
- ✅ 点击通知直接恢复到现有Activity
- ✅ 不会重启Activity
- ✅ 不会中断正在进行的直播和录制
- ✅ 保留所有对话状态和应用状态

### 2. 持续监控自动召回前台功能 ✅

**核心特性**：Foreground Service会**持续监控**MainActivity的状态，一旦发现它在后台，立即自动召回前台，无需等待PC命令。

**新增功能**：

#### 2.1 Activity状态检测

```kotlin
// In SquidRunApplication
companion object {
    @Volatile
    var isMainActivityInForeground = false
        private set
}

// ActivityLifecycleCallbacks tracking
registerActivityLifecycleCallbacks(...)

// In ThinkletForegroundService
private fun isMainActivityInForeground(): Boolean {
    return SquidRunApplication.isMainActivityInForeground
}
```

- **使用Application级别的生命周期跟踪**（兼容Android 8.1+）
- 通过`ActivityLifecycleCallbacks`实时跟踪MainActivity状态
- 可靠检测onPaused/onStopped事件
- 避免使用`ActivityManager.getAppTasks()`（在旧版本Android上不可靠）

#### 2.2 主动召回前台

```kotlin
private fun bringMainActivityToForeground()
```

- 将MainActivity从后台召回到前台
- 使用`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`确保不创建新实例
- 符合Android 10+的后台启动Activity限制（Foreground Service有权限）

#### 2.3 持续监控机制 ⭐ **核心功能**

```kotlin
private fun startActivityMonitoring()
private fun stopActivityMonitoring()
private fun isUserOnHomeScreen()  // 检测是否在桌面
private fun isLauncherPackage()   // 判断Launcher包名
```

**工作原理**：
- Service启动时自动开始监控（`startForegroundService()`中调用）
- 每2秒检查一次MainActivity是否在前台
- 如果检测到Activity进入后台，**进一步检测用户是否在Home screen**
- **只有在Home screen时才召回**，其他情况不召回
- Service停止时自动停止监控

**智能场景识别**：
```kotlin
MainActivity进入后台 → 检测当前前台应用
  ├─ 是Launcher（桌面）→ ✅ 召回 "用户按了Home键"
  ├─ 是com.android.systemui → ❌ 不召回 "通知栏或多任务"
  ├─ 是其他app包名 → ❌ 不召回 "切换到其他app"
  └─ 检测失败 → ❌ 不召回 "保守策略"
```

**关键特性**：
- ✅ **精确判断**：只在Home键回到桌面时召回，不干扰其他操作
- ✅ **场景识别**：
  - ✅ Home键 → 召回
  - ❌ 下拉通知栏 → 不召回
  - ❌ 方块键（多任务） → 不召回
  - ❌ 切换其他app → 不召回
- ✅ **持续监控**：不依赖PC命令，主动检测
- ✅ **智能判断**：只在状态从"前台→后台"变化时检测，避免重复操作
- ✅ **低资源消耗**：2秒检查间隔，使用Handler在主线程执行
- ✅ **自动管理**：Service启动/停止时自动管理监控生命周期
- ✅ **物理按键保证**：确保按Home键后app快速回到前台，物理按键可用

**监控参数**：
```kotlin
private const val ACTIVITY_CHECK_INTERVAL_MS = 2000L // 每2秒检查一次
```

**用户体验**：
- ✅ 用户按Home键将app切到后台 → 约2秒内app自动回到前台
- ❌ 用户下拉通知栏 → 不召回，不干扰用户查看通知
- ❌ 用户按方块键查看多任务 → 不召回，不干扰用户切换
- ❌ 用户切换到微信/浏览器等其他app → 不召回，不干扰使用
- ✅ 确保物理按键（CAMERA、VOLUME_UP/DOWN）在需要时可用

#### 2.4 智能命令转发

```kotlin
private fun forwardCommandToActivity(originalIntent: Intent)
```

**工作流程**：
1. 接收到PC端的控制命令（streaming-control或recording-control）
2. 检测MainActivity是否在前台（虽然监控会自动召回，但这里仍做检查）
3. 转发命令到MainActivity执行

**日志输出**：
```
⚠️ MainActivity detected in background, bringing to foreground automatically
📱 MainActivity brought to foreground
✅ Activity monitoring started (check interval: 2000ms)
```

## 技术说明

### 为什么Service不能直接监听物理按键？

Android的物理按键事件（`KeyEvent`）只能在前台Activity中捕获：
- ❌ Service无法接收`onKeyDown`/`onKeyUp`事件
- ❌ 系统级按键（Home、Recent Apps）即使在Activity中也无法拦截
- ❌ 使用Accessibility Service需要用户手动授权且有隐私问题

### 为什么持续监控自动召回是最佳方案？

1. **合法性**：Foreground Service在Android 10+拥有从后台启动Activity的权限
2. **主动性**：不依赖PC命令，主动检测并召回，确保app始终在前台
3. **用户体验**：用户按Home键后，app在2秒内自动回到前台，物理按键始终可用
4. **功能完整性**：保留了所有现有的按键处理逻辑，无需重复开发
5. **维护性**：不需要引入额外的权限或复杂的后台监听机制
6. **资源友好**：2秒检查间隔，Handler机制轻量高效

## 使用场景

### 场景1：Home键自动召回 ⭐ **核心场景**

**用户操作**：按Home键将app切到后台

**系统行为**：
1. MainActivity进入后台（onPaused）
2. Foreground Service检测到状态变化
3. 检测当前前台应用是Launcher（桌面）
4. **自动召回**MainActivity到前台（约2秒内）
5. 物理按键可以正常工作

**日志输出**：
```
📱 MainActivity paused (background)
🔍 Running periodic activity check...
🔍 Checking activity state via Application: false
🔍 Top package: com.android.launcher3
🔍 Is launcher: true
🏠 User on home screen detected, bringing MainActivity to foreground automatically
📱 MainActivity brought to foreground
📱 MainActivity resumed (foreground)
```

**效果**：
- ✅ 用户按Home键后，app会在2秒内自动回到前台
- ✅ 确保物理按键（CAMERA、VOLUME）可用
- ✅ 无需用户手动操作，完全自动化

### 场景1.1：下拉通知栏 - 不召回 ✅

**用户操作**：下拉通知栏查看通知

**系统行为**：
1. MainActivity进入后台（onPaused）
2. Foreground Service检测到状态变化
3. 检测当前前台应用是`com.android.systemui`（系统UI）
4. **不召回**，保持在后台

**日志输出**：
```
📱 MainActivity paused (background)
🔍 Running periodic activity check...
🔍 Checking activity state via Application: false
🔍 Top package: com.android.systemui
🔍 Is launcher: false
📱 MainActivity in background but user NOT on home screen - no action
   (User may be in: notification drawer, recent apps, or another app)
```

**效果**：
- ✅ 用户可以正常查看通知
- ✅ app不会自动弹出，不干扰用户

### 场景1.2：方块键（多任务） - 不召回 ✅

**用户操作**：按方块键查看最近应用

**系统行为**：
1. MainActivity进入后台（onPaused）
2. Foreground Service检测到状态变化
3. 检测当前前台应用是`com.android.systemui`或null
4. **不召回**，保持在后台

**日志输出**：
```
📱 MainActivity paused (background)
🔍 Top package: com.android.systemui (or null)
🔍 Is launcher: false
📱 MainActivity in background but user NOT on home screen - no action
```

**效果**：
- ✅ 用户可以正常查看多任务界面
- ✅ 可以切换到其他app

### 场景1.3：切换到其他app - 不召回 ✅

**用户操作**：切换到微信、浏览器等其他app

**系统行为**：
1. MainActivity进入后台（onPaused）
2. Foreground Service检测到状态变化
3. 检测当前前台应用是其他app（如`com.tencent.mm`）
4. **不召回**，保持在后台

**日志输出**：
```
📱 MainActivity paused (background)
🔍 Top package: com.tencent.mm
🔍 Is launcher: false
📱 MainActivity in background but user NOT on home screen - no action
```

**效果**：
- ✅ 用户可以正常使用其他app
- ✅ app不会自动弹出干扰

### 场景2：PC端远程控制

**用户操作**：在PC前端页面点击"开始录制"按钮

**系统行为**：
1. PC发送WebSocket命令到Android
2. `StatusReportingManager`接收命令，发送本地广播
3. `ThinkletForegroundService`的`recordingControlReceiver`接收广播
4. 转发命令到MainActivity（此时Activity应该已经在前台，因为持续监控）
5. MainActivity执行`toggleRecording()`开始录制

### 场景3：通知点击恢复

**用户操作**：下拉通知栏，点击"Thinklet Running"通知

**系统行为**：
1. 触发PendingIntent
2. 使用优化后的Intent flags
3. 直接恢复到现有MainActivity（不重启）
4. 保持录制/直播状态不中断

### 场景4：物理按键操作（app在后台）

**用户操作**：app在后台时，按下CAMERA按键

**系统行为**：
1. 用户按下CAMERA按键
2. 由于app在后台，按键事件无法被捕获
3. **但是**：持续监控会在2秒内检测到app在后台
4. 监控自动召回MainActivity到前台
5. 如果用户再次按下CAMERA按键，此时app已在前台，可以正常响应

**注意**：物理按键仍然需要app在前台才能工作，但持续监控确保了app会快速回到前台。

## 物理按键功能映射

当前MainActivity支持的物理按键功能：

| 按键 | 功能 | 代码位置 |
|-----|------|---------|
| **CAMERA** | 切换录制开关（带防抖） | `MainActivity.onKeyUp()` line 518-525 |
| **VOLUME_UP** | 播报电量和网络状态（TTS） | `handleVolumeUpKeyPress()` line 570-577 |
| **VOLUME_DOWN** | 预留（当前无功能） | `handleVolumeDownKeyUp()` line 579-581 |
| **POWER（长按2秒）** | 关机（播报→发送offline→启用自启动→关机） | `handlePowerKeyPress()` line 542-568 |

## Android版本兼容性

- ✅ **Android 8.0+ (API 26+)**：Foreground Service基础功能
- ✅ **Android 8.1 (API 27)**：使用ActivityLifecycleCallbacks进行状态跟踪（已测试）
- ✅ **Android 10+ (API 29+)**：后台启动Activity限制的例外（Foreground Service）
- ✅ **所有Android版本**：不依赖`ActivityManager.getAppTasks()`（在旧版本上不可靠）

### 重要：Android 8.1兼容性修复

在Android 8.1 (API 27)上，`ActivityManager.getAppTasks()`可能返回空列表或需要特殊权限，导致无法检测Activity状态。

**解决方案**：
- 使用`Application.registerActivityLifecycleCallbacks()`
- 在Application层面跟踪MainActivity的生命周期
- 通过`onActivityPaused`/`onActivityStopped`精确判断后台状态
- 更可靠、更轻量、兼容所有Android版本

## 安全性和权限

### 已有权限（AndroidManifest.xml）
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### MainActivity配置
```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"  <!-- 确保只有一个实例 -->
    android:exported="true">
</activity>
```

## 测试建议

### 测试场景1：Home键自动召回 ⭐ **重点测试**
1. 启动app，确保Foreground Service正在运行
2. **按Home键**将app切到后台
3. **等待2-3秒**
4. **验证**：app自动回到前台（无需任何操作）
5. **查看日志**：
   ```
   🔍 Top package: com.android.launcher3
   🔍 Is launcher: true
   🏠 User on home screen detected, bringing MainActivity to foreground automatically
   ```
6. **重复测试**：多次按Home键，验证每次都会自动召回

### 测试场景1.1：下拉通知栏 - 不应召回 ✅
1. 启动app
2. **下拉通知栏**
3. **等待2-3秒**
4. **验证**：app保持在后台，不会自动弹出
5. **查看日志**：
   ```
   🔍 Top package: com.android.systemui
   🔍 Is launcher: false
   📱 MainActivity in background but user NOT on home screen - no action
   ```

### 测试场景1.2：方块键（多任务） - 不应召回 ✅
1. 启动app
2. **按方块键**查看最近应用
3. **等待2-3秒**
4. **验证**：app保持在后台，不会自动弹出
5. **查看日志**：
   ```
   🔍 Top package: com.android.systemui (or null)
   🔍 Is launcher: false
   📱 MainActivity in background but user NOT on home screen - no action
   ```

### 测试场景1.3：切换到其他app - 不应召回 ✅
1. 启动app
2. **切换到其他app**（如微信、浏览器）
3. **等待2-3秒**
4. **验证**：app保持在后台，不会自动弹出
5. **查看日志**：
   ```
   🔍 Top package: com.tencent.mm (或其他app包名)
   🔍 Is launcher: false
   📱 MainActivity in background but user NOT on home screen - no action
   ```

### 测试场景2：通知点击恢复
1. 开始录制或直播
2. 按Home键将app切到后台
3. 下拉通知栏，点击"Thinklet Running"通知
4. 验证：app回到前台，录制/直播继续，无重启

### 测试场景3：PC端远程控制
1. 将app切到后台（按Home键）
2. 在PC前端点击"开始录制"
3. 验证：app自动回到前台，录制开始
4. 注意：由于持续监控，app可能已经在前台了

### 测试场景4：前台状态下的正常控制
1. app保持在前台
2. 在PC前端点击"开始直播"
3. 验证：直播正常开始，app不会有不必要的Activity切换

### 测试场景5：监控生命周期
1. 启动app，查看日志：应该看到"Activity monitoring started"
2. 停止Foreground Service（如果支持）
3. 查看日志：应该看到"Activity monitoring stopped"

## 日志关键字

用于调试和监控：

```
# Application启动和生命周期跟踪
✅ ActivityLifecycleCallbacks registered
📱 MainActivity started (foreground)
📱 MainActivity resumed (foreground)
📱 MainActivity paused (background)
📱 MainActivity stopped (background)

# Service启动和监控
🔔 Foreground service started with notification
✅ Activity monitoring started (check interval: 2000ms)

# 监控循环（每2秒）
🔍 Running periodic activity check...
🔍 Checking activity state via Application: true/false
🔍 Result: isInForeground=false, lastState=true

# 持续监控检测到后台（Home键场景）
🔍 Top package: com.android.launcher3
🔍 Is launcher: true
🏠 User on home screen detected, bringing MainActivity to foreground automatically
📱 MainActivity brought to foreground

# 后台但不在Home screen（其他场景）
🔍 Top package: com.android.systemui (or other app package)
🔍 Is launcher: false
📱 MainActivity in background but user NOT on home screen - no action
🔍 Next check scheduled in 2000ms

# 状态变化
✅ MainActivity returned to foreground
🔍 MainActivity still in background (already detected)

# 监控停止
🛑 Activity monitoring stopped

# 命令转发（现在较少出现，因为监控会自动召回）
📤 Command forwarded to MainActivity: recording-control, action: start (was in foreground: false)

# MainActivity接收
📥 Handling command from service: recording-control, action: start
🔄 onNewIntent called
```

## 未来优化方向

### 1. 可配置的监控间隔
- 添加配置选项：用户可自定义检查间隔（1-5秒）
- 平衡响应速度和资源消耗

### 2. 智能召回策略（可选）
- 添加开关：用户可选择是否启用自动召回
- 某些场景可能不需要强制前台（如仅状态报告）

### 3. 召回动画优化
- 使用`ActivityOptions`自定义召回动画
- 提供更流畅的用户体验

### 4. 电池优化考虑
- 监控电池状态，低电量时延长检查间隔
- 添加省电模式配置

## 相关文件

- `app/src/main/java/ai/fd/thinklet/app/squid/run/ThinkletForegroundService.kt` - 前台服务和监控逻辑
- `app/src/main/java/ai/fd/thinklet/app/squid/run/SquidRunApplication.kt` - Application生命周期跟踪
- `app/src/main/java/ai/fd/thinklet/app/squid/run/MainActivity.kt` - 主Activity
- `app/src/main/AndroidManifest.xml` - 权限和配置

## 更新日期

- **2025-11-11** - 初始版本（命令触发召回）
- **2025-11-11** - 更新版本（持续监控自动召回）
- **2025-11-11** - Android 8.1兼容性修复（使用ActivityLifecycleCallbacks）
- **2025-11-11** - 智能场景识别（方案1：只在Home键时召回，不干扰其他操作）

