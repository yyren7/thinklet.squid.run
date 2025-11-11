# iBeacon 电子围栏功能使用指南

## 概述

本应用已集成基于iBeacon的电子围栏功能，可以通过检测周围的iBeacon设备来判断设备是否在特定区域内，并在进入、离开或停留在围栏区域时触发相应的事件和反馈。

## 功能特性

### 核心功能
- ✅ **自动扫描iBeacon设备**：后台持续扫描周围的iBeacon信号
- ✅ **多围栏支持**：可配置多个围栏区域
- ✅ **距离估算**：根据RSSI信号强度估算与Beacon的距离
- ✅ **事件通知**：
  - 进入围栏（ENTER）
  - 离开围栏（EXIT）
  - 停留在围栏内（DWELL）
- ✅ **多种反馈方式**：
  - UI状态显示
  - Toast消息提示
  - 震动反馈
  - TTS语音播报

### 技术参数
- **扫描模式**：低延迟模式（快速响应）
- **默认围栏半径**：5米（可配置）
- **停留时间阈值**：10秒
- **Beacon超时时间**：30秒

## 架构设计

### 核心组件

#### 1. BeaconScannerManager
负责BLE扫描和iBeacon数据解析

**主要功能：**
- 扫描周围的BLE设备
- 解析iBeacon广播数据（UUID、Major、Minor、RSSI）
- 计算距离
- 管理Beacon生命周期（发现/丢失）

**关键方法：**
```kotlin
startScanning()  // 开始扫描
stopScanning()   // 停止扫描
getDiscoveredBeacons()  // 获取当前发现的所有Beacon
```

#### 2. GeofenceManager
管理围栏区域和事件处理

**主要功能：**
- 管理围栏区域配置
- 监听Beacon扫描结果
- 判断进入/离开/停留状态
- 触发围栏事件通知

**关键方法：**
```kotlin
addGeofenceZone(zone: GeofenceZone)  // 添加围栏
removeGeofenceZone(zoneId: String)   // 移除围栏
startMonitoring()  // 开始监控
stopMonitoring()   // 停止监控
addEventListener(listener: GeofenceEventListener)  // 添加事件监听
```

#### 3. GeofenceZone（数据类）
定义围栏区域

```kotlin
data class GeofenceZone(
    val id: String,              // 围栏唯一ID
    val name: String,            // 围栏名称
    val beaconUuid: String,      // 关联的Beacon UUID
    val beaconMajor: Int? = null,  // Beacon Major（可选）
    val beaconMinor: Int? = null,  // Beacon Minor（可选）
    val radiusMeters: Double = 5.0,  // 围栏半径（米）
    val enabled: Boolean = true  // 是否启用
)
```

## 配置指南

### 1. 添加围栏区域

在 `SquidRunApplication.kt` 中配置围栏：

```kotlin
val geofenceManager: GeofenceManager by lazy {
    GeofenceManager(applicationContext, beaconScannerManager).also {
        // 添加围栏区域
        val zone1 = GeofenceZone(
            id = "office_zone",
            name = "办公室区域",
            beaconUuid = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825",
            beaconMajor = 1,
            beaconMinor = 100,
            radiusMeters = 10.0,  // 10米半径
            enabled = true
        )
        it.addGeofenceZone(zone1)
        
        // 可以添加多个围栏
        val zone2 = GeofenceZone(
            id = "warehouse_zone",
            name = "仓库区域",
            beaconUuid = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825",
            beaconMajor = 1,
            beaconMinor = 200,
            radiusMeters = 15.0,
            enabled = true
        )
        it.addGeofenceZone(zone2)
    }
}
```

### 2. 配置iBeacon设备

确保您的iBeacon设备配置正确：

**必需参数：**
- **UUID**：128位唯一标识符（例如：FDA50693-A4E2-4FB1-AFCF-C6EB07647825）
- **Major**：16位整数（0-65535），通常用于区分不同位置
- **Minor**：16位整数（0-65535），通常用于区分同一位置的不同设备
- **TxPower**：发射功率，用于距离计算（通常在-59dBm左右）

**推荐的iBeacon设备：**
- Estimote Beacons
- Kontakt.io Beacons
- RadBeacon
- 或任何支持iBeacon协议的BLE设备

### 3. 获取iBeacon UUID

如果您还没有iBeacon设备，可以：

1. **使用手机模拟iBeacon**：
   - iOS：使用"Locate Beacon"等应用
   - Android：使用"Beacon Simulator"等应用

2. **在线生成UUID**：
   ```
   使用标准UUID生成器：
   https://www.uuidgenerator.net/
   ```

3. **使用Apple官方UUID**（用于测试）：
   ```
   E2C56DB5-DFFB-48D2-B060-D0F5A71096E0
   ```

## 权限说明

应用需要以下权限才能正常工作：

### Android 12+ (API 31+)
- `BLUETOOTH_SCAN` - 扫描蓝牙设备
- `BLUETOOTH_CONNECT` - 连接蓝牙设备
- `ACCESS_FINE_LOCATION` - 精确位置（BLE扫描需要）
- `ACCESS_COARSE_LOCATION` - 粗略位置

### Android 11 及以下
- `BLUETOOTH` - 蓝牙基础功能
- `BLUETOOTH_ADMIN` - 蓝牙管理
- `ACCESS_FINE_LOCATION` - 精确位置
- `ACCESS_COARSE_LOCATION` - 粗略位置

**注意**：在Android系统中，BLE扫描被视为位置相关功能，因此需要位置权限。

## 使用方法

### 1. 启动时自动运行

应用启动后，围栏功能会自动初始化：
- 权限检查和请求
- Activity进入Resume状态时自动开始监控
- Activity进入Pause状态时自动停止监控（节省电量）

### 2. 监控围栏状态

在MainActivity中可以实时查看围栏状态：

```
App Status 部分：
- Geofence: INSIDE/OUTSIDE
```

状态颜色：
- 🟢 绿色 = INSIDE（在围栏内）
- ⚫ 灰色 = OUTSIDE（在围栏外）

### 3. 事件反馈

当触发围栏事件时，会收到以下反馈：

#### 进入围栏（ENTER）
- 📱 Toast消息："进入围栏: [围栏名称]"
- 🔊 TTS语音："已进入[围栏名称]区域"
- 📳 震动：1次短震

#### 离开围栏（EXIT）
- 📱 Toast消息："离开围栏: [围栏名称]"
- 🔊 TTS语音："已离开[围栏名称]区域"
- 📳 震动：2次短震

#### 停留在围栏内（DWELL）
- 📱 Toast消息："在[围栏名称]区域停留中"
- （10秒后触发一次）

## 高级功能

### 1. 动态管理围栏

可以在运行时动态添加或移除围栏：

```kotlin
// 获取GeofenceManager实例
val geofenceManager = (application as SquidRunApplication).geofenceManager

// 添加新围栏
val newZone = GeofenceZone(
    id = "new_zone",
    name = "新区域",
    beaconUuid = "YOUR-UUID-HERE",
    beaconMajor = 1,
    beaconMinor = 1,
    radiusMeters = 8.0
)
geofenceManager.addGeofenceZone(newZone)

// 移除围栏
geofenceManager.removeGeofenceZone("new_zone")
```

### 2. 自定义事件监听

可以添加自定义的事件监听器来处理围栏事件：

```kotlin
geofenceManager.addEventListener(object : GeofenceEventListener {
    override fun onGeofenceEnter(event: GeofenceEvent) {
        // 进入围栏时的自定义处理
        Log.i("MyApp", "进入: ${event.zone.name}")
        // 例如：自动开始录像
        // viewModel.startRecording()
    }
    
    override fun onGeofenceExit(event: GeofenceEvent) {
        // 离开围栏时的自定义处理
        Log.i("MyApp", "离开: ${event.zone.name}")
        // 例如：自动停止录像
        // viewModel.stopRecording()
    }
    
    override fun onGeofenceDwell(event: GeofenceEvent) {
        // 停留时的自定义处理
        Log.i("MyApp", "停留: ${event.zone.name}")
    }
})
```

### 3. 获取当前状态

```kotlin
// 获取所有围栏区域
val zones = geofenceManager.getAllGeofenceZones()

// 获取特定围栏的状态
val state = geofenceManager.getGeofenceState("zone_id")
// 返回: INSIDE, OUTSIDE, 或 UNKNOWN

// 检查是否在任意围栏内
val isInside = geofenceManager.isInsideAnyGeofence.value

// 获取当前活动的围栏列表
val activeZones = geofenceManager.activeGeofences.value
```

### 4. 获取发现的Beacon

```kotlin
val beaconScanner = (application as SquidRunApplication).beaconScannerManager
val beacons = beaconScanner.getDiscoveredBeacons()

beacons.forEach { beacon ->
    Log.d("Beacon", """
        UUID: ${beacon.uuid}
        Major: ${beacon.major}
        Minor: ${beacon.minor}
        Distance: ${beacon.distance}m
        RSSI: ${beacon.rssi}dBm
    """)
}
```

## 故障排查

### 1. 无法扫描到Beacon

**可能原因：**
- 蓝牙未开启
- 权限未授予（特别是位置权限）
- Beacon设备未开启或电量耗尽
- Beacon距离过远

**解决方法：**
1. 检查蓝牙是否开启：设置 → 蓝牙
2. 检查权限：设置 → 应用 → Thinklet → 权限
3. 确认Beacon正常工作（使用其他iBeacon扫描应用测试）
4. 减小与Beacon的距离（< 10米）

### 2. 围栏状态始终显示OUTSIDE

**可能原因：**
- UUID/Major/Minor配置不匹配
- 围栏半径设置过小
- Beacon信号太弱

**解决方法：**
1. 查看日志确认是否扫描到Beacon：
   ```
   adb logcat | grep "BeaconScannerManager"
   ```
2. 验证配置的UUID/Major/Minor与Beacon设备一致
3. 增大围栏半径（例如从5米改为10米）
4. 确认Beacon的TxPower设置正确

### 3. 频繁触发进入/离开事件

**可能原因：**
- Beacon信号不稳定
- 围栏半径设置不当
- 处于围栏边界附近

**解决方法：**
1. 增大围栏半径
2. 调整Beacon位置避免障碍物干扰
3. 增加多个Beacon提高稳定性

### 4. 查看详细日志

```bash
# 查看围栏相关日志
adb logcat | grep -E "GeofenceManager|BeaconScannerManager"

# 查看所有应用日志
adb logcat | grep "ai.fd.thinklet"
```

## 性能优化

### 电量优化
- Activity暂停时自动停止扫描
- 使用合理的扫描间隔（默认10秒）
- Beacon超时自动清理（30秒）

### 准确性优化
- 使用低延迟扫描模式提高响应速度
- 基于RSSI的距离估算算法
- 支持多Beacon配置提高覆盖范围

## 实际应用场景

### 1. 仓库管理
- 在仓库各区域部署iBeacon
- 设备进入特定区域时自动开始录像
- 离开区域时自动停止录像

### 2. 安全监控
- 在重要区域部署iBeacon
- 检测设备是否在授权区域内
- 未授权离开时触发告警

### 3. 考勤打卡
- 在办公室部署iBeacon
- 检测设备进入/离开办公区域
- 自动记录考勤时间

### 4. 位置追踪
- 在大型场所部署多个iBeacon
- 实时追踪设备位置
- 绘制移动轨迹

## 测试建议

### 1. 基础功能测试
1. 启动应用，授予所有权限
2. 确认"Geofence: OUTSIDE"显示
3. 靠近iBeacon设备（< 5米）
4. 观察状态变化为"INSIDE"
5. 检查Toast、震动、TTS反馈
6. 远离iBeacon设备（> 10米）
7. 确认状态变回"OUTSIDE"

### 2. 多围栏测试
1. 配置2个以上围栏（不同Major/Minor）
2. 分别测试进入每个围栏
3. 验证围栏名称正确显示
4. 测试从一个围栏切换到另一个

### 3. 边界测试
1. 在围栏边界附近来回走动
2. 观察状态切换的稳定性
3. 调整围栏半径优化体验

### 4. 长时间测试
1. 在围栏内停留10秒以上
2. 验证DWELL事件触发
3. 测试Beacon超时机制（离开30秒后）

## API参考

### BeaconData
```kotlin
data class BeaconData(
    val uuid: String,        // Beacon UUID
    val major: Int,          // Major值
    val minor: Int,          // Minor值
    val rssi: Int,           // 信号强度
    val distance: Double,    // 估算距离（米）
    val timestamp: Long      // 时间戳
)
```

### GeofenceEvent
```kotlin
data class GeofenceEvent(
    val type: GeofenceEventType,  // ENTER, EXIT, DWELL
    val zone: GeofenceZone,       // 围栏信息
    val beacon: BeaconData,       // Beacon信息
    val timestamp: Long           // 时间戳
)
```

### GeofenceState
```kotlin
enum class GeofenceState {
    INSIDE,   // 在围栏内
    OUTSIDE,  // 在围栏外
    UNKNOWN   // 未知状态
}
```

## 更新日志

### v1.1.0 (2025-11-06)
- ✨ 新增iBeacon电子围栏功能
- ✨ 支持多围栏配置
- ✨ 添加进入/离开/停留事件
- ✨ 集成震动和TTS反馈
- ✨ UI状态实时显示

## 技术支持

如有问题或建议，请查看：
- 项目日志：`adb logcat`
- 源代码：`app/src/main/java/ai/fd/thinklet/app/squid/run/`
- 相关文件：
  - `BeaconScannerManager.kt` - Beacon扫描器
  - `GeofenceManager.kt` - 围栏管理器
  - `MainActivity.kt` - UI和事件处理
  - `SquidRunApplication.kt` - 初始化配置

---

**文档版本**: 1.0  
**最后更新**: 2025-11-06  
**作者**: Thinklet Development Team






















