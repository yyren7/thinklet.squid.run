# 蓝牙扫描功耗分析与优化建议

## 当前实现分析

### 1. 扫描模式（BeaconScannerManager）

**当前配置：**
```kotlin
// BeaconScannerManager.kt Line 539
.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
```

**功耗分析：**
| 扫描模式 | 扫描窗口/间隔 | 功耗 | 响应延迟 | 适用场景 |
|---------|--------------|------|----------|---------|
| **LOW_LATENCY** ⚠️ | ~11.25ms/~11.25ms | **高（100%占空比）** | < 100ms | 实时导航 |
| BALANCED | ~11.25ms/~300ms | 中等（~4%占空比） | 100-500ms | 一般应用 ⭐ |
| LOW_POWER | ~1.5ms/~4500ms | 低（~0.03%占空比） | 1-5s | 后台监控 |
| OPPORTUNISTIC | 随其他扫描 | 极低（共享） | 不确定 | 非关键功能 |

**⚠️ 问题：LOW_LATENCY 模式功耗最高，几乎持续扫描**

**建议：改为 BALANCED 模式**
- 功耗降低约 96%
- 响应延迟仍可接受（< 500ms）
- 适合围栏监控场景

### 2. 定期任务分析

#### BeaconScannerManager 的定期任务

**任务 1：Beacon 超时检查**
```kotlin
// Line 670-676
scheduleBeaconTimeoutCheck()
  └─ 每 5 秒执行一次
  └─ 检查所有已发现的 Beacon 是否超时（60秒）
```

**任务 2：扫描统计输出**
```kotlin
// Line 520-527
scheduleScanStatistics()
  └─ 每 10 秒输出一次统计日志
  └─ 仅用于调试
```

**功耗影响：**
- ✅ 超时检查（5秒）：合理，必需功能
- ⚠️ 统计输出（10秒）：**可以禁用或改为更长间隔**

#### GeofenceManager 的定期任务

**任务 3：围栏状态检查**
```kotlin
// Line 530-541
startPeriodicCheck()
  └─ 每 3 秒执行一次（协程）
  └─ 从 BeaconScannerManager 同步最新数据
  └─ 检查围栏状态（进入/离开/超时）
```

**功耗影响：**
- ⚠️ **每 3 秒主动拉取数据 → 存在冗余**
- BeaconScannerManager 已经通过监听器通知新发现的 Beacon
- 只需要检查超时，不需要每次都同步数据

### 3. 数据处理开销

**Kalman 滤波处理**
```kotlin
// Line 636-637
val filter = getDistanceFilter(beaconKey)
val filteredDistance = filter.filter(beaconData.distance)
```

**功耗影响：**
- ✅ 每个 Beacon 只在收到新数据时处理
- ✅ 计算量小，可以忽略
- 已优化：更新时不通知监听器（避免 ANR）

### 4. 监听器通知机制

**当前策略：**
```kotlin
// Line 649-658
if (existingBeacon == null) {
    // 首次发现：通知监听器
    listeners.forEach { it.onBeaconDiscovered(filteredBeaconData) }
} else {
    // 更新：不通知监听器（避免过度回调）
    // GeofenceManager 通过定期检查获取最新数据
}
```

**功耗影响：**
- ✅ 首次发现才通知 → 避免频繁回调
- ⚠️ GeofenceManager 每 3 秒主动拉取 → **这里有冗余！**

## 功耗优化建议

### 优先级 1：修改扫描模式（推荐）⭐

**修改前：**
```kotlin
.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // 高功耗
```

**修改后：**
```kotlin
.setScanMode(ScanSettings.SCAN_MODE_BALANCED)     // 中等功耗 ⭐
```

**效果：**
- 功耗降低约 **96%**
- 响应延迟：< 500ms（完全可接受）
- 不影响围栏监控功能

**更激进的选项（如果对延迟要求不高）：**
```kotlin
.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)    // 低功耗
```
- 功耗降低约 **99.97%**
- 响应延迟：1-5 秒
- 适合非实时场景

---

### 优先级 2：优化定期任务（推荐）⭐

#### 2.1 禁用调试统计日志

**当前问题：**
```kotlin
// Line 520-527
scheduleScanStatistics()  // 每 10 秒输出统计
```

**建议修改：**
```kotlin
// 方案 A：完全禁用（生产环境）
// scheduleScanStatistics()  // 注释掉

// 方案 B：降低频率（开发环境）
scheduleScanStatistics(60000)  // 改为每 60 秒
```

**效果：**
- 减少 CPU 唤醒次数
- 减少日志 I/O 开销
- 功耗降低约 1-2%

#### 2.2 优化 GeofenceManager 的数据同步

**当前问题：**
```kotlin
// Line 533-537
kotlinx.coroutines.delay(3000)  // 每 3 秒
if (isMonitoring) {
    updateBeaconsFromScanner()  // 主动拉取最新数据 ← 冗余！
}
checkGeofenceStates()  // 检查超时
```

**冗余分析：**
- BeaconScannerManager 已经通过 `onBeaconDiscovered` 通知新 Beacon
- `updateBeaconsFromScanner()` 每 3 秒主动拉取 → **重复处理相同数据**
- 只有超时检查才真正需要定期执行

**建议修改：**
```kotlin
kotlinx.coroutines.delay(10000)  // 改为每 10 秒（只需检查超时）
if (isMonitoring) {
    // 移除 updateBeaconsFromScanner()，依赖监听器通知
}
checkGeofenceStates()  // 只检查超时，不主动拉取
```

**效果：**
- CPU 唤醒频率从每 3 秒降至每 10 秒
- 功耗降低约 5-10%

---

### 优先级 3：智能扫描策略（可选）

**方案 A：屏幕状态感知**

```kotlin
// 根据屏幕状态动态调整扫描模式
class BeaconScannerManager(private val context: Context) {
    
    private var isScreenOn = true
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    updateScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    updateScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                }
            }
        }
    }
    
    private fun updateScanMode(mode: Int) {
        if (isScanning) {
            stopScanning()
            startScanning(mode)
        }
    }
}
```

**效果：**
- 屏幕开启：快速响应（LOW_LATENCY）
- 屏幕关闭：省电模式（LOW_POWER）
- 功耗降低约 50-80%（取决于使用习惯）

**方案 B：围栏状态感知**

```kotlin
// 在围栏内使用低功耗模式，在围栏外使用高灵敏度模式
private fun adjustScanModeByGeofenceState() {
    val isInside = geofenceManager.isInsideAnyGeofence.value
    val mode = if (isInside) {
        ScanSettings.SCAN_MODE_LOW_POWER    // 已经在围栏内，降低频率
    } else {
        ScanSettings.SCAN_MODE_BALANCED      // 在围栏外，保持灵敏
    }
    updateScanMode(mode)
}
```

**效果：**
- 在围栏内：功耗降低 99%
- 离开围栏时仍能快速检测
- 适合长时间停留场景

---

### 优先级 4：后台优化（可选）

**方案：Activity 生命周期感知**

**当前实现：**
```kotlin
// MainActivity.kt
override fun onResume() {
    geofenceManager.startMonitoring()  // 前台启动
}

override fun onPause() {
    geofenceManager.stopMonitoring()   // 后台停止 ✅ 已优化
}
```

**效果：**
- ✅ 已经实现：后台自动停止扫描
- 只在用户使用时才扫描
- 符合 Android 省电最佳实践

**可选增强：**
```kotlin
// 允许配置后台扫描策略
class GeofenceManager {
    enum class BackgroundScanPolicy {
        STOP,           // 完全停止（当前策略）✅
        LOW_POWER,      // 低功耗模式
        KEEP_SCANNING   // 保持扫描（用于特殊需求）
    }
    
    var backgroundPolicy = BackgroundScanPolicy.STOP
}
```

---

## 功耗对比估算

### 当前配置 vs. 优化后配置

| 组件 | 当前配置 | 优化后配置 | 功耗降低 |
|------|---------|-----------|---------|
| **扫描模式** | LOW_LATENCY | BALANCED | **-96%** ⭐ |
| **GeofenceManager 检查** | 每 3 秒 | 每 10 秒 | -70% |
| **统计日志** | 每 10 秒 | 禁用 | -100% |
| **后台扫描** | 已停止 ✅ | 已停止 ✅ | 0% |
| **综合功耗** | 基准 100% | 约 **4-5%** | **-95%** 🎉 |

### 电池续航影响估算

**假设场景：** 8 小时工作日，持续扫描

| 配置 | 蓝牙扫描功耗 | 对总续航影响 | 8 小时耗电 |
|------|-------------|-------------|-----------|
| **当前（LOW_LATENCY）** | ~50 mA | 减少续航约 15-20% | ~400 mAh |
| **优化后（BALANCED）** | ~2-5 mA | 减少续航约 1-2% | ~16-40 mAh |
| **改善幅度** | **-90%** | **+13-18%** | **-360 mAh** |

*注：实际功耗受设备型号、Beacon 数量、环境干扰等因素影响*

---

## 实施建议

### 阶段 1：立即优化（无风险）⭐

**修改 1：扫描模式改为 BALANCED**
```kotlin
// BeaconScannerManager.kt Line 539
.setScanMode(ScanSettings.SCAN_MODE_BALANCED)  // 从 LOW_LATENCY 改为 BALANCED
```

**修改 2：禁用调试统计（生产环境）**
```kotlin
// BeaconScannerManager.kt Line 382
// scheduleScanStatistics()  // 注释掉或删除
```

**预期效果：**
- 功耗降低 **95%**
- 响应延迟增加 < 500ms（几乎感觉不到）
- 不影响功能

**测试验证：**
```bash
# 测试前后功耗对比
adb shell dumpsys batterystats --reset
# 使用应用 30 分钟
adb shell dumpsys batterystats | grep -A 20 "Bluetooth"
```

### 阶段 2：进一步优化（需测试）

**修改 3：降低 GeofenceManager 检查频率**
```kotlin
// GeofenceManager.kt Line 533
kotlinx.coroutines.delay(10000)  // 从 3000 改为 10000
// 注释掉 updateBeaconsFromScanner()
```

**测试重点：**
- 验证围栏进入/离开检测仍然灵敏
- 验证超时检测正常工作
- 观察日志，确认无异常

### 阶段 3：高级优化（可选）

**修改 4：屏幕状态感知扫描**
- 实现 BroadcastReceiver 监听屏幕开关
- 动态调整扫描模式

**修改 5：围栏状态感知扫描**
- 在围栏内降低扫描频率
- 离开围栏时恢复灵敏度

---

## 功耗测试方法

### 方法 1：Android Profiler（推荐）

```
1. Android Studio → View → Tool Windows → Profiler
2. 选择设备和应用
3. 点击 Energy Profiler
4. 观察 BLE 相关的能耗
5. 对比优化前后差异
```

### 方法 2：Battery Historian（详细分析）

```bash
# 1. 重置电池统计
adb shell dumpsys batterystats --reset

# 2. 拔掉 USB（使用电池）
# 3. 使用应用 30-60 分钟
# 4. 重新连接 USB

# 5. 导出电池统计
adb bugreport > bugreport.zip

# 6. 上传到 Battery Historian 分析
# https://bathist.ef.lc/
```

### 方法 3：简单对比测试

```bash
# 优化前
adb shell dumpsys battery | grep level
# 使用 1 小时
adb shell dumpsys battery | grep level
# 计算耗电百分比

# 优化后（相同使用模式）
# 对比耗电差异
```

---

## 总结与建议

### 当前问题

1. ⚠️ **扫描模式过于激进**：LOW_LATENCY 持续扫描，功耗极高
2. ⚠️ **定期任务有冗余**：GeofenceManager 每 3 秒主动拉取数据，重复处理
3. ⚠️ **调试日志未关闭**：每 10 秒输出统计，不必要的 CPU 唤醒
4. ✅ **后台已优化**：Activity 暂停时停止扫描

### 推荐方案

**保守方案（立即实施）：**
```
1. 扫描模式：LOW_LATENCY → BALANCED
2. 禁用统计日志

预期效果：功耗降低 95%，几乎无副作用
```

**激进方案（需测试）：**
```
1. 扫描模式：LOW_LATENCY → LOW_POWER
2. GeofenceManager 检查间隔：3秒 → 10秒
3. 屏幕状态感知扫描

预期效果：功耗降低 99%，响应延迟增加 1-5 秒
```

### 风险评估

| 优化项 | 风险 | 影响 |
|--------|------|------|
| BALANCED 扫描模式 | 低 | 延迟 +0.3s |
| 禁用统计日志 | 无 | 无 |
| GeofenceManager 降频 | 中 | 超时检测延迟 |
| 屏幕状态感知 | 低 | 屏幕关闭时响应慢 |

### 下一步行动

1. **立即修改**：扫描模式改为 BALANCED
2. **测试验证**：使用 1 小时，观察功能和电量
3. **评估效果**：对比优化前后的功耗数据
4. **迭代优化**：根据实际需求调整参数

---

**文档版本**: 1.0  
**最后更新**: 2025-11-13  
**作者**: Thinklet Development Team





