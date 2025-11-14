# iBeacon 硬件配置最佳实践指南

## 概述

本文档提供 iBeacon 硬件的配置建议和故障排查方法，确保围栏监控系统稳定运行。

## 推荐的 Beacon 硬件配置

### 1. 广播间隔（Advertising Interval）

**定义**：Beacon 发送广播包的时间间隔

**推荐配置：**
| 场景 | 间隔 | 响应时间 | 电池寿命 | 推荐 |
|------|------|----------|----------|------|
| 实时定位/导航 | 100ms | 极快 | 6-12个月 | |
| 围栏监控（标准） | 200-300ms | 快速 | 1-2年 | ⭐ |
| 长期部署 | 500-1000ms | 一般 | 2-5年 | |
| 极低功耗模式 | 2000ms+ | 慢 | 5年+ | ❌ 不推荐 |

**Apple 官方规范：**
- 最小值：20ms
- 推荐值：100-200ms  
- 最大值：10240ms

**实际配置建议：**
```
围栏监控应用：设置为 200-300ms
- 足够快速响应（< 1秒检测到）
- 平衡电池寿命（1-2年）
- 避免信号碰撞
```

### 2. 发射功率（Tx Power / Transmission Power）

**定义**：Beacon 的无线信号发射强度

**推荐配置：**
| 场景 | Tx Power | 有效范围 | 电池寿命 | 推荐 |
|------|----------|----------|----------|------|
| 室内小范围定位 | -12dBm | 2-5米 | 3-5年 | |
| 标准围栏监控 | -4dBm ~ 0dBm | 10-20米 | 1-2年 | ⭐ |
| 大范围覆盖 | +4dBm | 50-70米 | 6-12个月 | |
| 最大功率 | +8dBm | 70-100米 | 3-6个月 | ❌ 不推荐 |

**距离与 RSSI 参考值（Tx Power = -4dBm）：**
```
距离    |  RSSI 范围
--------|------------
0.5米   |  -45 ~ -55dBm
1.0米   |  -55 ~ -65dBm
2.0米   |  -60 ~ -70dBm
5.0米   |  -70 ~ -80dBm
10米    |  -80 ~ -90dBm
20米    |  -90 ~ -100dBm
```

**配置建议：**
```
围栏半径 10 米：设置 Tx Power = -4dBm 或 0dBm
围栏半径 5 米：设置 Tx Power = -8dBm
围栏半径 20 米：设置 Tx Power = +4dBm
```

### 3. 广播模式（Broadcasting Mode）

**连续广播（Continuous Broadcasting）** ⭐ **强烈推荐**
```
特点：持续不间断广播
优点：信号稳定，响应迅速
缺点：电池消耗稍高
适用：围栏监控、实时定位
```

**间歇广播（Periodic Broadcasting）** ❌ **不推荐**
```
特点：广播 N 秒，休眠 M 秒
例如：广播 10s，休眠 50s
缺点：容易出现信号丢失，导致误判离开围栏
问题：正如您遇到的情况！
```

**运动触发（Motion-Triggered）** ❌ **不适用**
```
特点：检测到运动时才广播
适用：资产追踪（移动物品）
不适用：固定位置的围栏监控
```

**配置建议：**
```
✅ 使用连续广播模式
❌ 避免任何形式的休眠/间歇模式
✅ 禁用节能模式（如果有）
✅ 禁用运动感应触发
```

### 4. UUID/Major/Minor 配置规划

**标准 iBeacon 格式：**
```
UUID:  128-bit（16字节）唯一标识符
Major: 16-bit（2字节）无符号整数（0-65535）
Minor: 16-bit（2字节）无符号整数（0-65535）
```

**推荐的层级规划：**
```
组织级别（UUID）
  └─ 位置级别（Major）
       └─ 设备级别（Minor）

示例 1：仓库管理
UUID: FDA50693-A4E2-4FB1-AFCF-C6EB07647825（公司统一）
  ├─ Major: 1（北京仓库）
  │    ├─ Minor: 1（入口区域）
  │    ├─ Minor: 2（存储区A）
  │    └─ Minor: 3（装卸区）
  └─ Major: 2（上海仓库）
       ├─ Minor: 1（入口区域）
       └─ Minor: 2（存储区B）

示例 2：办公楼
UUID: E2C56DB5-DFFB-48D2-B060-D0F5A71096E0（公司统一）
  ├─ Major: 1（1楼）
  │    ├─ Minor: 1（大堂）
  │    ├─ Minor: 2（会议室A）
  │    └─ Minor: 3（会议室B）
  └─ Major: 2（2楼）
       ├─ Minor: 1（开放办公区）
       └─ Minor: 2（经理室）
```

**配置建议：**
- 同一项目使用相同的 UUID
- 用 Major 区分不同位置/楼层
- 用 Minor 区分同一位置的不同 Beacon
- 记录每个 Beacon 的配置（建立台账）

### 5. 抗干扰配置

**多 Beacon 部署（提高可靠性）：**

```
场景：10米围栏半径监控
建议：部署 2-3 个 Beacon

配置方案 A：冗余部署
- Beacon 1: UUID=XXX, Major=1, Minor=1
- Beacon 2: UUID=XXX, Major=1, Minor=2
- 物理位置：相距 5-8 米
- 系统逻辑：检测到任一 Beacon 即视为"在围栏内"

配置方案 B：区域划分
- Beacon 1: UUID=XXX, Major=1, Minor=1（入口）
- Beacon 2: UUID=XXX, Major=1, Minor=2（中心）
- Beacon 3: UUID=XXX, Major=1, Minor=3（出口）
- 系统逻辑：根据不同 Minor 判断具体位置
```

**避免信号碰撞：**
```
多个 Beacon 同时广播可能导致信号碰撞

解决方案 1：错开广播间隔
- Beacon 1: 200ms 间隔
- Beacon 2: 250ms 间隔
- Beacon 3: 300ms 间隔

解决方案 2：增加物理间距
- 保持 Beacon 之间至少 3-5 米距离
```

## 推荐的 Beacon 硬件品牌

### 企业级产品（推荐）

1. **Estimote Beacons**
   - 配置灵活，支持云端管理
   - 电池寿命：2-3年
   - 价格：$20-30/个
   - 适合：中大型部署

2. **Kontakt.io Beacons**
   - 工业级耐用性
   - 电池寿命：3-5年
   - 价格：$25-35/个
   - 适合：恶劣环境

3. **RadBeacon**
   - 高精度定位
   - 电池寿命：1-2年
   - 价格：$15-25/个
   - 适合：室内定位

### 经济型产品

4. **Generic iBeacon（通用型）**
   - 基本功能完整
   - 电池寿命：1-2年
   - 价格：$5-15/个
   - 适合：测试和小规模部署

### 选购建议

**必须支持的功能：**
- ✅ 可配置广播间隔
- ✅ 可配置发射功率
- ✅ 可配置 UUID/Major/Minor
- ✅ 支持固件更新
- ✅ 提供配置 APP

**可选功能：**
- 🔋 可更换电池（推荐）
- 📱 手机 APP 配置（推荐）
- ☁️ 云端管理平台
- 🌡️ 温湿度传感器
- 📊 电池电量监控

## 当前问题诊断

### 您遇到的问题症状

```
日志分析：
13:32:39: iBeacons=3719
13:32:49: iBeacons=3719  ← 没有新信号（10秒）
13:32:59: iBeacons=3719  ← 没有新信号（20秒）
13:33:09: iBeacons=3719  ← 没有新信号（30秒）
13:33:19: iBeacons=3719  ← 没有新信号（40秒）
13:33:29: 超时断开（60秒超时）
13:33:31: Beacon lost
13:33:49: 重新发现（间隔 70 秒）
```

**问题特征：**
- ⚠️ 长达 70 秒没有收到 Beacon 信号
- ⚠️ 信号恢复后又正常工作
- ⚠️ 距离很近（0.19m）但仍出现中断
- ⚠️ 反复出现"发现 → 丢失 → 重新发现"循环

### 可能的原因

**1. Beacon 配置了间歇广播模式** ⭐ **最可能**
```
症状：每 1 分钟左右出现一次信号中断
原因：Beacon 可能配置为"广播 10s，休眠 50s"模式
解决：检查 Beacon 配置，改为连续广播
```

**2. Beacon 电池电量不足** 
```
症状：信号不稳定，间歇性丢失
原因：低电量导致广播功率下降或间歇工作
解决：更换电池或充电
```

**3. Beacon 处于节能模式**
```
症状：自动休眠以延长电池寿命
原因：启用了"Smart Power Saving"等功能
解决：禁用节能模式
```

**4. 环境干扰**
```
症状：特定时间或地点出现信号丢失
原因：WiFi、微波炉、其他 BLE 设备干扰
解决：更换 Beacon 位置，避开干扰源
```

**5. Beacon 固件问题**
```
症状：不规律的信号中断
原因：固件 Bug 或配置损坏
解决：更新固件或重置 Beacon
```

### 诊断步骤

**步骤 1：检查 Beacon 配置**
```
使用 Beacon 配置 APP（厂商提供）检查：
✅ 广播模式：应为"连续广播"
✅ 广播间隔：应为 200-300ms
✅ 发射功率：应为 -4dBm 或 0dBm
✅ 节能模式：应为"关闭"
✅ 电池电量：应 > 20%
```

**步骤 2：使用第三方工具测试**
```
Android APP 推荐：
- Beacon Scanner (Nicholas Briduox)
- nRF Connect (Nordic Semiconductor)
- Locate Beacon

测试方法：
1. 打开 APP 扫描 Beacon
2. 观察信号是否持续出现
3. 记录 RSSI 值的变化
4. 确认是否有长时间信号中断
```

**步骤 3：分析扫描日志**
```bash
# 查看详细扫描日志
adb logcat | grep "BeaconScannerManager"

# 重点关注：
- "📶 iBeacon detected" 的频率（应该很频繁）
- "⚪ Beacon lost" 的原因
- RSSI 值的波动范围
```

**步骤 4：对比测试**
```
方法：使用手机模拟 iBeacon 作为对照组

Android APP：Beacon Simulator
iOS APP：Locate Beacon

配置：
- UUID: E2C56DB5-DFFB-48D2-B060-D0F5A71096E0
- Major: 99
- Minor: 99
- Tx Power: -59dBm

测试：
1. 手机 A 运行 Beacon Simulator
2. 手机 B 运行您的应用
3. 观察是否出现相同的信号中断问题
4. 如果手机模拟的 Beacon 正常 → 硬件 Beacon 有问题
   如果手机模拟的 Beacon 也中断 → 应用或系统问题
```

## 推荐的配置方案

### 标准围栏监控配置（10米半径）

```
Beacon 配置：
├─ UUID: FDA50693-A4E2-4FB1-AFCF-C6EB07647825
├─ Major: 1
├─ Minor: 1
├─ 广播间隔：250ms
├─ 发射功率：0dBm
├─ 广播模式：连续广播
└─ 节能模式：关闭

应用配置（GeofenceZone）：
├─ radiusMeters: 10.0
├─ 超时时间：60秒（内部）
└─ 滞后阈值：1.2倍（12米退出）

预期效果：
├─ 响应时间：< 1秒
├─ 电池寿命：1-2年
├─ 误报率：< 1%
└─ 漏报率：< 0.5%
```

### 高可靠性配置（冗余部署）

```
部署 2 个 Beacon：

Beacon 1:
├─ UUID: FDA50693-A4E2-4FB1-AFCF-C6EB07647825
├─ Major: 1, Minor: 1
├─ 广播间隔：200ms
├─ 发射功率：0dBm
└─ 位置：区域中心

Beacon 2:
├─ UUID: FDA50693-A4E2-4FB1-AFCF-C6EB07647825
├─ Major: 1, Minor: 2
├─ 广播间隔：300ms
├─ 发射功率：0dBm
└─ 位置：距 Beacon 1 约 5-8 米

应用逻辑：
├─ 创建 2 个 GeofenceZone（Minor 不同）
├─ 任一 Beacon 在范围内 → INSIDE
└─ 两个 Beacon 都超时 → OUTSIDE

优点：
├─ 单个 Beacon 故障不影响系统
├─ 信号覆盖更稳定
└─ 误报率大幅降低
```

## 立即行动建议

### 针对您当前的问题

**第一步：检查 Beacon 配置（5分钟）**
```
1. 下载 Beacon 厂商的配置 APP
2. 连接您的 Beacon（UUID: E2C56DB5-DFFB-48D2-B060-D0F5A71096E0）
3. 检查以下设置：
   ✓ 广播模式 → 改为"连续广播"
   ✓ 广播间隔 → 改为 200-300ms
   ✓ 节能模式 → 关闭
   ✓ 电池电量 → 如果 < 20%，更换电池
4. 保存配置并重启 Beacon
```

**第二步：测试信号稳定性（10分钟）**
```bash
# 清除日志并重新测试
adb logcat -c
adb logcat | grep "BeaconScannerManager"

# 观察 3-5 分钟
# 正常情况应该看到：
# - 每 1-2 秒有一条"扫描到设备"日志
# - iBeacons 计数持续增长
# - 没有"Beacon lost"消息
```

**第三步：调整应用超时参数（可选）**
```kotlin
// 如果 Beacon 广播间隔较长（如 1000ms）
// 可以增加应用的超时时间

// BeaconScannerManager.kt
private const val BEACON_TIMEOUT_MS = 120000L  // 从 60s 改为 120s

// GeofenceManager.kt
private const val TIMEOUT_INSIDE_MS = 120000L  // 从 60s 改为 120s
```

**第四步：考虑增加冗余 Beacon（长期方案）**
```
如果单个 Beacon 仍不稳定：
1. 购买第二个 Beacon
2. 配置不同的 Minor 值（如 Minor: 2）
3. 放置在相距 5-8 米的位置
4. 在应用中添加第二个围栏区域
```

## 常见问题（FAQ）

**Q1：Beacon 的电池能用多久？**
```
取决于配置：
- 广播间隔 100ms，功率 +4dBm：6-12个月
- 广播间隔 300ms，功率 0dBm：1-2年（推荐）
- 广播间隔 1000ms，功率 -8dBm：3-5年

建议：定期检查电池电量，< 20% 时更换
```

**Q2：为什么 RSSI 值波动很大？**
```
正常现象，BLE 信号受多种因素影响：
- 人体遮挡（水分吸收 2.4GHz 信号）
- 墙壁、金属障碍物
- WiFi、蓝牙设备干扰
- 设备方向（天线指向性）

解决方法：
1. 应用使用 Kalman 滤波平滑距离（已实现）
2. 设置合理的围栏半径和滞后阈值
3. 多 Beacon 部署提高稳定性
```

**Q3：如何测试 Beacon 是否正常工作？**
```
方法 1：使用 nRF Connect APP
1. 打开 APP，点击"SCAN"
2. 查找您的 Beacon（按 UUID 搜索）
3. 观察信号是否持续出现（每秒更新）
4. 点击 Beacon 查看详细信息（UUID/Major/Minor/RSSI）

方法 2：使用 Beacon Scanner APP
1. 打开 APP 自动扫描
2. 找到您的 Beacon
3. 查看"Last Seen"时间（应该是"刚刚"）
4. 观察 RSSI 值变化
```

**Q4：可以用手机模拟 Beacon 吗？**
```
可以，用于测试：

Android：
- APP: Beacon Simulator (免费)
- 支持 iBeacon 和 Eddystone 格式

iOS：
- APP: Locate Beacon
- 支持 iBeacon 格式

限制：
- 手机进入休眠后可能停止广播
- 不适合长期部署
- 适合开发测试阶段
```

**Q5：围栏半径应该设置多大？**
```
建议：
- 小房间（3-5米）：radiusMeters = 3.0
- 中型房间（5-10米）：radiusMeters = 5.0 ~ 8.0（推荐）
- 大型区域（10-20米）：radiusMeters = 10.0 ~ 15.0
- 户外（20米+）：radiusMeters = 15.0 ~ 30.0

原则：
- 半径 > 实际需要的 1.2 倍（考虑信号波动）
- 退出阈值 = 半径 × 1.2（滞后防抖）
- 测试验证，根据实际情况调整
```

## 相关文档

- [iBeacon 电子围栏功能使用指南](IBEACON-GEOFENCE-GUIDE.md)
- [iBeacon 实现总结](IBEACON-IMPLEMENTATION-SUMMARY.md)
- [iBeacon 快速开始](IBEACON-QUICK-START.md)

## 技术支持

如需进一步帮助：
1. 提供完整的日志：`adb logcat > beacon_log.txt`
2. 提供 Beacon 型号和当前配置截图
3. 描述具体的使用场景和需求

---

**文档版本**: 1.0  
**最后更新**: 2025-11-13  
**作者**: Thinklet Development Team





