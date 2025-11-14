# 后台资源停止审计报告

## 📋 审计目的

检查当MainActivity进入后台（按Home键）时，哪些功能/资源会被停止或关闭。

## 🔍 审计范围

- MainActivity生命周期方法（onPause, onStop, onDestroy）
- MainViewModel的activityOnPause/activityOnResume
- 各种Manager的停止/清理方法
- SurfaceView的surfaceDestroyed回调

## ✅ 当前状态（2025-11-11）

### 1. MainActivity.onPause() 

**代码位置**：`MainActivity.kt` line 458-465

**当前行为**：
```kotlin
override fun onPause() {
    viewModel.activityOnPause()
    // geofenceManager.stopMonitoring()  // ✅ 已注释，保持运行
    super.onPause()
}
```

**影响的功能**：
- ✅ **BLE扫描**：持续运行（已修复）
- ✅ **地理围栏监控**：持续运行（已修复）

---

### 2. MainViewModel.activityOnPause()

**代码位置**：`MainViewModel.kt` line 585-588

**当前行为**：
```kotlin
fun activityOnPause() {
    // As per user requirement, keep all resources active even when paused
    Log.i("MainViewModel", "Activity pausing, but keeping all resources active")
}
```

**影响的功能**：
- ✅ **相机**：保持活跃
- ✅ **麦克风**：保持活跃
- ✅ **录制**：继续进行（如果正在录制）
- ✅ **直播**：继续推流（如果正在直播）

---

### 3. SurfaceView.surfaceDestroyed()

**代码位置**：`MainActivity.kt` line 678-685

**当前行为**：
```kotlin
override fun surfaceDestroyed(holder: SurfaceHolder) {
    if (localPreviewBinding.preview.visibility == android.view.View.VISIBLE) {
        viewModel.stopPreview()  // ⚠️ 停止预览
    }
}
```

**影响的功能**：
- ⚠️ **预览显示**：会被停止

**说明**：
- 这是正常行为，Surface销毁时必须停止渲染
- 但这**不影响录制和直播**，它们使用独立的Surface
- 回到前台时会自动重建Surface并恢复预览

**触发条件**：
- Activity被系统销毁时（内存不足）
- 不是简单按Home键就会触发

---

### 4. MainActivity.onDestroy()

**代码位置**：`MainActivity.kt` line 407-423

**当前行为**：
```kotlin
override fun onDestroy() {
    Log.i("MainActivity", "🛑 Activity is being destroyed...")
    
    unregisterReceivers()  // 注销广播接收器
    geofenceManager.removeEventListener(geofenceEventListener)  // 移除监听器
    
    // Note: Do not stop foreground service here
    // Service will continue running
    
    super.onDestroy()
}
```

**影响的功能**：
- ✅ 注销广播接收器（正常清理）
- ✅ 移除地理围栏事件监听器
- ✅ **Foreground Service继续运行**
- ✅ **BLE扫描继续**（由Service保护）
- ✅ **录制/直播继续**（由Service保护）

**触发条件**：
- 由于`launchMode="singleTask"`，通常不会被触发
- 只有用户手动"强制停止"或系统杀进程时才触发

---

### 5. Application.onTerminate()

**代码位置**：`SquidRunApplication.kt` line 150-165

**当前行为**：
```kotlin
override fun onTerminate() {
    Log.i("SquidRunApplication", "🛑 Application terminating, stopping all services")
    
    ThinkletForegroundService.stop(applicationContext)
    statusReportingManager.stop()
    networkManager.unregisterCallback()
    ttsManager.shutdown()
    geofenceManager.cleanup()
    beaconScannerManager.cleanup()
    logcatLogger.stop()
    
    super.onTerminate()
}
```

**影响的功能**：
- 🛑 停止所有服务和管理器

**触发条件**：
- ⚠️ **注意**：这个方法在真实设备上**永远不会被调用**
- 只在模拟器测试时可能被调用
- 真实设备上，进程直接被杀死

---

## 📊 功能状态矩阵

| 功能 | 前台运行 | 后台运行（Home键） | Activity销毁 | App终止 |
|-----|---------|-----------------|-------------|---------|
| **BLE扫描** | ✅ 运行 | ✅ 运行 | ✅ 运行 | 🛑 停止 |
| **地理围栏监控** | ✅ 运行 | ✅ 运行 | ✅ 运行 | 🛑 停止 |
| **录制功能** | ✅ 运行 | ✅ 运行 | ✅ 运行 | 🛑 停止 |
| **直播推流** | ✅ 运行 | ✅ 运行 | ✅ 运行 | 🛑 停止 |
| **预览显示** | ✅ 显示 | ⚠️ 隐藏但可快速恢复 | 🛑 停止 | 🛑 停止 |
| **WebSocket连接** | ✅ 连接 | ✅ 连接 | ✅ 连接 | 🛑 断开 |
| **状态报告** | ✅ 发送 | ✅ 发送 | ✅ 发送 | 🛑 停止 |
| **TTS语音** | ✅ 可用 | ✅ 可用 | ✅ 可用 | 🛑 停止 |
| **LogcatLogger** | ✅ 记录 | ✅ 记录 | ✅ 记录 | 🛑 停止 |
| **Foreground Service** | ✅ 运行 | ✅ 运行 | ✅ 运行 | 🛑 停止 |
| **WakeLock** | ✅ 持有 | ✅ 持有 | ✅ 持有 | 🛑 释放 |

---

## 🎯 按Home键回到桌面的影响（核心场景）

### ✅ 保持运行的功能

1. **BLE扫描和地理围栏**（已修复）
   - 持续扫描iBeacon信号
   - 实时检测进出地理围栏
   - 触发TTS语音提示

2. **录制和直播**
   - 录制继续进行
   - 直播继续推流
   - 不会中断

3. **相机和麦克风**
   - 资源保持占用
   - 数据继续采集

4. **网络连接**
   - WebSocket保持连接
   - 状态持续上报

5. **Foreground Service**
   - 持续运行
   - 2秒检测一次，自动召回前台

### ⚠️ 受影响的功能

1. **预览显示**
   - 不再显示画面（正常）
   - 回到前台时自动恢复

### 🛑 不受影响的功能

无。所有核心功能都在后台持续运行。

---

## 🔒 保护机制

### 1. Foreground Service保护

- **WakeLock**：防止CPU休眠
- **前台通知**：告知用户app在运行
- **高优先级**：系统不会轻易杀死

### 2. singleTask启动模式

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"
    ...>
```

- 只有一个Activity实例
- 按Home键后Activity不会被销毁
- 资源保持活跃

### 3. 资源保持策略

```kotlin
fun activityOnPause() {
    // Keep all resources active even when paused
}
```

- 明确声明保持所有资源活跃
- 适合单一用途设备

---

## 📱 实际测试结果

### 测试1：按Home键 → 等待 → 自动返回

```
操作序列：
1. app在前台，BLE扫描运行
2. 按Home键 → 回到桌面
3. 等待2-3秒
4. app自动返回前台

结果：
✅ BLE扫描持续运行（看到持续的iBeacon检测日志）
✅ 地理围栏持续监控
✅ 录制/直播不中断
✅ WebSocket保持连接
✅ 自动召回成功
```

### 测试2：按Home键 → 停留在桌面

```
操作序列：
1. app在前台
2. 按Home键 → 回到桌面
3. 不让app自动返回（测试后台运行）

预期结果：
✅ BLE扫描持续运行
✅ 检测到iBeacon进出事件
✅ TTS语音提示正常
✅ 状态持续上报
```

### 测试3：切换到其他app

```
操作序列：
1. app在前台
2. 切换到微信/浏览器等其他app
3. 使用其他app

结果：
✅ app保持在后台
✅ 不会自动弹出（智能识别）
✅ BLE扫描持续运行
✅ 所有后台功能正常
```

---

## 🚨 潜在风险

### 1. 电池消耗

**影响**：持续运行BLE扫描和相机会消耗电量

**缓解措施**：
- 已有Foreground Service通知，用户知晓
- 设备通常插电使用
- 可配置扫描间隔

### 2. 内存压力

**影响**：保持所有资源可能占用较多内存

**缓解措施**：
- 单一用途设备，内存充足
- Foreground Service优先级高
- 系统不会轻易回收

### 3. 后台限制（Android 10+）

**影响**：某些厂商ROM可能限制后台Activity启动

**缓解措施**：
- Foreground Service有白名单权限
- 已测试Android 8.1可用
- 需要在不同设备上验证

---

## ✅ 推荐配置

### 当前配置（最佳）

```kotlin
// MainActivity.onPause() - 不停止任何功能
override fun onPause() {
    viewModel.activityOnPause()  // 保持所有资源活跃
    // geofenceManager.stopMonitoring()  // 注释掉，保持运行
    super.onPause()
}

// MainViewModel.activityOnPause() - 明确保持活跃
fun activityOnPause() {
    Log.i("MainViewModel", "Activity pausing, but keeping all resources active")
}

// Foreground Service - 持续监控和保护
private fun startActivityMonitoring() {
    // 每2秒检测，Home键时自动召回
}
```

---

## 📚 相关文档

- [FOREGROUND-SERVICE-ENHANCEMENT.md](./FOREGROUND-SERVICE-ENHANCEMENT.md) - Foreground Service实现
- [HOME-BUTTON-DETECTION-ANALYSIS.md](./HOME-BUTTON-DETECTION-ANALYSIS.md) - Home键检测方案
- [HOME-BUTTON-TEST-GUIDE.md](./HOME-BUTTON-TEST-GUIDE.md) - 测试指南

---

## 📝 审计结论

### ✅ 良好状态

1. **所有核心功能在后台持续运行**
2. **BLE和地理围栏已修复，不再停止**
3. **Foreground Service有效保护**
4. **智能Home键检测和召回**

### 🎯 无需额外修改

当前配置已经最优化，所有功能在后台都能正常运行。

### 💡 建议

1. **监控电池消耗**：在实际使用中监控，必要时优化
2. **测试不同设备**：在不同厂商ROM上验证
3. **添加配置选项**（可选）：允许用户选择是否启用自动召回

---

## 📅 审计日期

2025-11-11 - 初始审计和修复完成







