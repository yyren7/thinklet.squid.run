# Home键智能召回测试指南

## 功能说明

**新特性**：系统现在只在用户按Home键回到桌面时才自动召回MainActivity，其他场景不会召回。

**设计目标**：
- ✅ 按Home键 → 自动召回（确保物理按键可用）
- ❌ 下拉通知栏 → 不召回（不干扰用户查看通知）
- ❌ 方块键（多任务） → 不召回（不干扰用户切换）
- ❌ 切换其他app → 不召回（不干扰用户使用其他app）

## 快速测试

### ✅ 测试1：按Home键（应该召回）

```bash
# 1. 启动app
# 2. 按Home键
# 3. 等待2-3秒
# 4. 验证：app自动回到前台

# 查看日志
adb logcat | grep "ThinkletFgService"
```

**期望日志**：
```
🔍 Top package: com.android.launcher3
🔍 Is launcher: true
🏠 User on home screen detected, bringing MainActivity to foreground automatically
```

**结果**：✅ app应该自动回到前台

---

### ❌ 测试2：下拉通知栏（不应召回）

```bash
# 1. 启动app
# 2. 下拉通知栏
# 3. 等待2-3秒
# 4. 验证：app保持在后台
```

**期望日志**：
```
🔍 Top package: com.android.systemui
🔍 Is launcher: false
📱 MainActivity in background but user NOT on home screen - no action
```

**结果**：❌ app应该保持在后台，不弹出

---

### ❌ 测试3：方块键（不应召回）

```bash
# 1. 启动app
# 2. 按方块键（多任务/Recent Apps）
# 3. 等待2-3秒
# 4. 验证：app保持在后台
```

**期望日志**：
```
🔍 Top package: com.android.systemui (或null)
🔍 Is launcher: false
📱 MainActivity in background but user NOT on home screen - no action
```

**结果**：❌ app应该保持在后台，不弹出

---

### ❌ 测试4：切换到其他app（不应召回）

```bash
# 1. 启动app
# 2. 切换到微信/浏览器/任意其他app
# 3. 等待2-3秒
# 4. 验证：app保持在后台
```

**期望日志**：
```
🔍 Top package: com.tencent.mm (或其他app包名)
🔍 Is launcher: false
📱 MainActivity in background but user NOT on home screen - no action
```

**结果**：❌ app应该保持在后台，不弹出

---

## 测试矩阵

| 测试场景 | 用户操作 | 期望行为 | Top Package | Is Launcher | 召回？ |
|---------|---------|---------|-------------|-------------|-------|
| 1 | 按Home键 | 自动召回 | `com.android.launcher3` | true | ✅ 是 |
| 2 | 下拉通知栏 | 保持后台 | `com.android.systemui` | false | ❌ 否 |
| 3 | 按方块键 | 保持后台 | `com.android.systemui` | false | ❌ 否 |
| 4 | 切换微信 | 保持后台 | `com.tencent.mm` | false | ❌ 否 |
| 5 | 切换浏览器 | 保持后台 | `com.android.chrome` | false | ❌ 否 |
| 6 | 锁屏 | 保持后台 | (varies) | false | ❌ 否 |

## 完整日志示例

### 场景：按Home键 → 自动召回

```
11:30:00.123 D/SquidRunApplication: 📱 MainActivity paused (background)
11:30:00.234 D/ThinkletFgService: 🔍 Running periodic activity check...
11:30:00.235 D/ThinkletFgService: 🔍 Checking activity state via Application: false
11:30:00.236 D/ThinkletFgService: 🔍 Result: isInForeground=false, lastState=true
11:30:00.237 D/ThinkletFgService: 🔍 Top activity: com.android.launcher3.Launcher
11:30:00.238 D/ThinkletFgService: 🔍 Top package: com.android.launcher3
11:30:00.239 D/ThinkletFgService: 🔍 Is launcher: true
11:30:00.240 I/ThinkletFgService: 🏠 User on home screen detected, bringing MainActivity to foreground automatically
11:30:00.241 I/ThinkletFgService: 📱 MainActivity brought to foreground
11:30:00.345 D/SquidRunApplication: 📱 MainActivity started (foreground)
11:30:00.456 D/SquidRunApplication: 📱 MainActivity resumed (foreground)
```

### 场景：下拉通知栏 → 不召回

```
11:31:00.123 D/SquidRunApplication: 📱 MainActivity paused (background)
11:31:02.567 D/ThinkletFgService: 🔍 Running periodic activity check...
11:31:02.568 D/ThinkletFgService: 🔍 Checking activity state via Application: false
11:31:02.569 D/ThinkletFgService: 🔍 Result: isInForeground=false, lastState=true
11:31:02.570 D/ThinkletFgService: 🔍 Top activity: com.android.systemui.statusbar.phone.StatusBar
11:31:02.571 D/ThinkletFgService: 🔍 Top package: com.android.systemui
11:31:02.572 D/ThinkletFgService: 🔍 Is launcher: false
11:31:02.573 D/ThinkletFgService: 📱 MainActivity in background but user NOT on home screen - no action
11:31:02.574 D/ThinkletFgService:    (User may be in: notification drawer, recent apps, or another app)
```

## 常见问题排查

### Q1: 按Home键后没有自动召回

**可能原因**：
1. Launcher包名不在识别列表中
2. getRunningTasks权限问题

**排查步骤**：
```bash
# 查看日志中的Top package
adb logcat | grep "Top package"

# 如果显示的Launcher包名不在列表中，需要添加
```

**解决方法**：
找到你的Launcher包名，添加到`isLauncherPackage()`方法的列表中。

### Q2: 下拉通知栏时仍然召回

**可能原因**：
系统UI包名识别错误

**排查步骤**：
```bash
# 查看日志
adb logcat | grep "Top package"
```

查看是否正确识别为`com.android.systemui`。

### Q3: 切换其他app时仍然召回

**可能原因**：
其他app的包名被误识别为Launcher

**排查步骤**：
```bash
# 查看日志中的Is launcher判断
adb logcat | grep "Is launcher"
```

应该显示`false`。

## 支持的Launcher列表

当前代码支持以下厂商的Launcher：

- ✅ AOSP / Google (com.android.launcher3)
- ✅ Pixel (com.google.android.apps.nexuslauncher)
- ✅ 华为 (com.huawei.android.launcher)
- ✅ 三星 (com.sec.android.app.launcher)
- ✅ 小米 (com.miui.home)
- ✅ OPPO (com.oppo.launcher)
- ✅ Vivo (com.bbk.launcher2)
- ✅ OnePlus (net.oneplus.launcher)
- ✅ Sony (com.sonyericsson.home)
- ✅ Lenovo (com.lenovo.launcher)
- ✅ LG (com.lge.launcher2)
- ✅ Motorola (com.motorola.launcher3)
- ✅ ASUS (com.asus.launcher)
- ✅ Meizu (com.meizu.flyme.launcher)

如果你的设备Launcher不在列表中，请：
1. 查看日志获取包名
2. 添加到代码中的`launcherPackages` Set

## 性能指标

- **检测延迟**：2秒（ACTIVITY_CHECK_INTERVAL_MS）
- **CPU占用**：< 1%（空闲时）
- **内存占用**：可忽略（Handler机制）
- **电池影响**：极小（每2秒一次轻量级检查）

## 相关文档

- [FOREGROUND-SERVICE-ENHANCEMENT.md](./FOREGROUND-SERVICE-ENHANCEMENT.md) - 完整功能文档
- [HOME-BUTTON-DETECTION-ANALYSIS.md](./HOME-BUTTON-DETECTION-ANALYSIS.md) - 方案分析
- [ANDROID-8.1-FIX-TEST-GUIDE.md](./ANDROID-8.1-FIX-TEST-GUIDE.md) - Android 8.1兼容性测试

## 测试日期

2025-11-11 - 方案1实现和测试


