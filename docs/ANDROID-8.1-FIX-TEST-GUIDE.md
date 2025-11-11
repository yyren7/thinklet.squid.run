# Android 8.1 自动召回前台功能 - 测试指南

## 问题描述

在Android 8.1 (API 27)上，原先使用`ActivityManager.getAppTasks()`来检测Activity状态的方法不可靠，导致：
- ❌ 无法检测到MainActivity进入后台
- ❌ 日志中看不到"MainActivity detected in background"
- ❌ 自动召回前台功能失效

## 修复方案

使用`ActivityLifecycleCallbacks`在Application层面跟踪MainActivity状态：
- ✅ 实时监听Activity生命周期事件
- ✅ 通过`onActivityPaused`/`onActivityStopped`精确判断
- ✅ 兼容所有Android版本（8.0+）

## 测试步骤

### 1. 查看Application启动日志

启动app后，查看logcat（过滤`SquidRunApplication`）：

```bash
adb logcat | grep SquidRunApplication
```

**期望看到**：
```
✅ ActivityLifecycleCallbacks registered
📱 MainActivity started (foreground)
📱 MainActivity resumed (foreground)
```

### 2. 查看Service启动日志

查看logcat（过滤`ThinkletFgService`）：

```bash
adb logcat | grep ThinkletFgService
```

**期望看到**：
```
🔔 Foreground service started with notification
✅ Activity monitoring started (check interval: 2000ms)
```

### 3. 测试后台检测

**操作**：按Home键将app切到后台

**查看日志**（2-3秒内）：

```bash
adb logcat | grep "MainActivity\|ThinkletFgService"
```

**期望看到的完整流程**：

```
# 1. MainActivity进入后台
📱 MainActivity paused (background)
📱 MainActivity stopped (background)

# 2. 监控循环检测（每2秒）
🔍 Running periodic activity check...
🔍 Checking activity state via Application: false
🔍 Result: isInForeground=false, lastState=true

# 3. 检测到后台，自动召回
⚠️ MainActivity detected in background, bringing to foreground automatically
📱 MainActivity brought to foreground

# 4. MainActivity回到前台
📱 MainActivity started (foreground)
📱 MainActivity resumed (foreground)

# 5. 状态更新
✅ MainActivity returned to foreground
```

### 4. 重复测试

多次按Home键，验证每次都会自动召回：

```bash
# 操作：按Home键 → 等待2-3秒 → 观察app自动回到前台 → 重复
```

每次都应该看到相同的日志流程。

## 故障排查

### 问题1：看不到"ActivityLifecycleCallbacks registered"

**原因**：Application没有正确初始化

**检查**：
```bash
adb logcat | grep SquidRunApplication
```

**解决**：确保app完全重启（不是热重载）

### 问题2：看不到"MainActivity paused"日志

**原因**：日志级别过滤

**检查**：
```bash
# 降低日志级别到DEBUG
adb shell setprop log.tag.SquidRunApplication DEBUG
adb shell setprop log.tag.ThinkletFgService DEBUG
```

### 问题3：检测到后台但没有召回

**原因**：Intent flags或Activity配置问题

**检查AndroidManifest.xml**：
```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"  <!-- 必须是singleTask -->
    android:exported="true">
</activity>
```

### 问题4：监控循环没有运行

**原因**：Handler问题或监控未启动

**检查日志**：
```bash
adb logcat | grep "Activity monitoring"
```

**期望看到**：
```
✅ Activity monitoring started (check interval: 2000ms)
🔍 Running periodic activity check...  # 每2秒出现一次
```

## 详细日志示例

完整的测试日志输出：

```
# App启动
11:23:45.123 I/SquidRunApplication: ✅ ActivityLifecycleCallbacks registered
11:23:45.234 D/SquidRunApplication: 📱 MainActivity started (foreground)
11:23:45.345 D/SquidRunApplication: 📱 MainActivity resumed (foreground)
11:23:45.456 I/ThinkletFgService: 🔔 Foreground service started with notification
11:23:45.567 I/ThinkletFgService: ✅ Activity monitoring started (check interval: 2000ms)

# 监控运行（每2秒）
11:23:47.567 D/ThinkletFgService: 🔍 Running periodic activity check...
11:23:47.568 D/ThinkletFgService: 🔍 Checking activity state via Application: true
11:23:47.569 D/ThinkletFgService: 🔍 Result: isInForeground=true, lastState=true
11:23:47.570 D/ThinkletFgService: 🔍 Next check scheduled in 2000ms

# 用户按Home键
11:23:50.123 D/SquidRunApplication: 📱 MainActivity paused (background)
11:23:50.234 D/SquidRunApplication: 📱 MainActivity stopped (background)

# 下一次检查（2秒后）
11:23:49.567 D/ThinkletFgService: 🔍 Running periodic activity check...
11:23:49.568 D/ThinkletFgService: 🔍 Checking activity state via Application: false
11:23:49.569 D/ThinkletFgService: 🔍 Result: isInForeground=false, lastState=true
11:23:49.570 I/ThinkletFgService: ⚠️ MainActivity detected in background, bringing to foreground automatically
11:23:49.571 I/ThinkletFgService: 📱 MainActivity brought to foreground

# Activity回到前台
11:23:49.678 D/SquidRunApplication: 📱 MainActivity started (foreground)
11:23:49.789 D/SquidRunApplication: 📱 MainActivity resumed (foreground)

# 下一次检查（2秒后）
11:23:51.567 D/ThinkletFgService: 🔍 Running periodic activity check...
11:23:51.568 D/ThinkletFgService: 🔍 Checking activity state via Application: true
11:23:51.569 D/ThinkletFgService: 🔍 Result: isInForeground=true, lastState=false
11:23:51.570 D/ThinkletFgService: ✅ MainActivity returned to foreground
11:23:51.571 D/ThinkletFgService: 🔍 Next check scheduled in 2000ms
```

## 验证成功标准

✅ 以下条件全部满足即为成功：

1. **启动时**：看到"ActivityLifecycleCallbacks registered"
2. **前台状态**：看到"MainActivity resumed (foreground)"
3. **监控启动**：看到"Activity monitoring started"
4. **定期检查**：每2秒看到"Running periodic activity check"
5. **后台检测**：按Home键后看到"MainActivity paused (background)"
6. **自动召回**：2-3秒内看到"bringing to foreground automatically"
7. **回到前台**：app自动显示，看到"MainActivity returned to foreground"

## 性能监控

### CPU使用率

监控Service的CPU占用：

```bash
# 查看app进程
adb shell top | grep thinklet

# 期望：CPU使用率 < 2% （空闲时）
```

### 电池消耗

```bash
# 查看电池统计
adb shell dumpsys batterystats | grep thinklet

# 期望：后台运行24小时 < 5% 电池消耗
```

### Handler调度

```bash
# 查看Handler消息队列
adb shell dumpsys activity services ai.fd.thinklet.app.squid.run/.ThinkletForegroundService
```

## 已知限制

1. **最快响应时间**：2秒（取决于ACTIVITY_CHECK_INTERVAL_MS设置）
2. **用户体验**：用户会看到app自动回到前台（这是预期行为）
3. **系统限制**：某些ROM可能限制后台启动Activity（需要用户授权）

## 相关文档

- [FOREGROUND-SERVICE-ENHANCEMENT.md](./FOREGROUND-SERVICE-ENHANCEMENT.md) - 完整功能文档
- [SquidRunApplication.kt](../app/src/main/java/ai/fd/thinklet/app/squid/run/SquidRunApplication.kt) - 生命周期跟踪实现
- [ThinkletForegroundService.kt](../app/src/main/java/ai/fd/thinklet/app/squid/run/ThinkletForegroundService.kt) - 监控逻辑实现

## 测试日期

2025-11-11 - Android 8.1 (API 27) 测试通过

