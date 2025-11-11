# iBeacon 电子围栏 - 快速开始

## 5分钟快速配置

### 1️⃣ 准备iBeacon设备

您需要至少一个iBeacon设备。如果还没有，可以使用手机临时模拟：

**iOS设备**：
- 下载"Locate Beacon"应用
- 创建一个新的Beacon
- 记录UUID、Major、Minor

**Android设备**：
- 下载"Beacon Simulator"应用
- 创建一个新的Beacon
- 记录UUID、Major、Minor

**推荐测试参数**：
```
UUID: FDA50693-A4E2-4FB1-AFCF-C6EB07647825
Major: 1
Minor: 100
```

### 2️⃣ 配置围栏区域

编辑 `app/src/main/java/ai/fd/thinklet/app/squid/run/SquidRunApplication.kt`：

找到这部分代码（约第71-91行）：

```kotlin
val geofenceManager: GeofenceManager by lazy {
    GeofenceManager(applicationContext, beaconScannerManager).also {
        Log.i("GeofenceManager", "📍 Initializing geofence zones...")
        
        // 取消注释并修改以下代码：
        val exampleZone = GeofenceZone(
            id = "zone_1",
            name = "测试区域",  // 修改为您的区域名称
            beaconUuid = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825",  // 使用您的UUID
            beaconMajor = 1,      // 使用您的Major值
            beaconMinor = 100,    // 使用您的Minor值
            radiusMeters = 10.0,  // 围栏半径（米）
            enabled = true
        )
        it.addGeofenceZone(exampleZone)
        
        Log.i("GeofenceManager", "✅ GeofenceManager initialized")
    }
}
```

### 3️⃣ 编译并安装应用

```bash
# 清理并重新编译
./gradlew clean
./gradlew assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4️⃣ 测试

1. **启动应用**
   - 应用会自动请求蓝牙和位置权限
   - 授予所有权限

2. **查看状态**
   - 在"App Status"区域查看"Geofence"状态
   - 初始应该显示"OUTSIDE"（灰色）

3. **进入围栏**
   - 打开iBeacon设备（或启动模拟器）
   - 将设备靠近Beacon（< 5米）
   - 等待2-3秒

4. **预期结果**
   - 📱 状态变为"INSIDE"（绿色）
   - 📳 设备震动1次
   - 🔊 TTS播报："已进入测试区域"
   - 💬 Toast提示："进入围栏: 测试区域"

5. **离开围栏**
   - 远离Beacon（> 10米）或关闭Beacon
   - 等待3-5秒

6. **预期结果**
   - 📱 状态变为"OUTSIDE"（灰色）
   - 📳 设备震动2次
   - 🔊 TTS播报："已离开测试区域"
   - 💬 Toast提示："离开围栏: 测试区域"

## 常见问题

### ❓ 无法扫描到Beacon

**检查清单**：
- ✅ 蓝牙已开启
- ✅ 位置权限已授予（必需！）
- ✅ Beacon设备已开启
- ✅ 距离在10米以内

### ❓ 状态一直是OUTSIDE

1. 检查UUID/Major/Minor是否正确匹配
2. 查看日志：
   ```bash
   adb logcat | grep "BeaconScannerManager"
   ```
3. 增大围栏半径（改为15.0或20.0）

### ❓ 如何查看扫描到的Beacon

查看日志中的"🔵 New beacon discovered"消息：
```bash
adb logcat | grep "New beacon discovered"
```

输出示例：
```
New beacon discovered: UUID=FDA50693-A4E2-4FB1-AFCF-C6EB07647825, Major=1, Minor=100, Distance=3.45m
```

## 进阶配置

### 添加多个围栏

```kotlin
// 围栏1 - 办公室
val officeZone = GeofenceZone(
    id = "office",
    name = "办公室",
    beaconUuid = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825",
    beaconMajor = 1,
    beaconMinor = 100,
    radiusMeters = 10.0,
    enabled = true
)
it.addGeofenceZone(officeZone)

// 围栏2 - 仓库
val warehouseZone = GeofenceZone(
    id = "warehouse",
    name = "仓库",
    beaconUuid = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825",
    beaconMajor = 1,
    beaconMinor = 200,
    radiusMeters = 15.0,
    enabled = true
)
it.addGeofenceZone(warehouseZone)
```

### 仅匹配UUID（忽略Major/Minor）

```kotlin
val broadZone = GeofenceZone(
    id = "broad_zone",
    name = "整个区域",
    beaconUuid = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825",
    beaconMajor = null,  // 不限制Major
    beaconMinor = null,  // 不限制Minor
    radiusMeters = 20.0,
    enabled = true
)
it.addGeofenceZone(broadZone)
```

## 实用命令

```bash
# 查看所有围栏相关日志
adb logcat | grep -E "Geofence|Beacon"

# 查看进入/离开事件
adb logcat | grep -E "进入围栏|离开围栏"

# 清除日志并重新开始
adb logcat -c
adb logcat | grep "GeofenceManager"

# 重启应用
adb shell am force-stop ai.fd.thinklet.app.squid.run
adb shell am start -n ai.fd.thinklet.app.squid.run/.MainActivity
```

## 下一步

- 📖 阅读[完整文档](./IBEACON-GEOFENCE-GUIDE.md)
- 🔧 根据实际需求调整围栏配置
- 📱 部署到生产环境

---

**需要帮助？** 查看详细日志或联系开发团队。






















