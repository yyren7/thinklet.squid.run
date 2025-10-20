# 网络命令一致性修复

## 问题背景

之前存在三个入口点的行为不一致问题：

| 入口点 | 行为模式 | 状态检查层级 |
|--------|---------|-------------|
| UI按钮 | Toggle | ViewModel层 |
| 物理按钮 | Toggle | ViewModel层 |
| 网络命令 | 显式start/stop | **MainActivity层 + ViewModel层（重复）** |

## 发现的问题

### 问题1：两层检查导致行为不一致

**网络命令的原实现**：
```kotlin
"start" -> {
    if (!viewModel.isRecording.value) {  // ⚠️ MainActivity层检查
        viewModel.startRecording { ... }
    }
}
"stop" -> {
    if (viewModel.isRecording.value) {  // ⚠️ MainActivity层检查
        viewModel.stopRecording()
    }
}
```

**问题**：
1. MainActivity 层的检查是**静默忽略**（没有日志，没有反馈）
2. ViewModel 层的检查会**记录日志并返回结果**
3. 重复请求在 MainActivity 层就被拦截，导致日志不完整

### 问题2：状态快照的时序问题

MainActivity 层使用 `viewModel.isRecording.value`，这是一个**快照值**：
- 可能与 ViewModel 内部状态不同步
- 不受操作锁保护
- 在并发场景下可能产生竞态条件

### 问题3：缺少反馈

原来的 `stopRecording()` 没有回调：
```kotlin
viewModel.stopRecording()  // ⚠️ 无法知道是否成功
vibrator.vibrate(...)       // 总是执行振动
```

## 修复方案

### 修复1：移除MainActivity层的重复检查

**修改前**：
```kotlin
"start" -> {
    if (!viewModel.isRecording.value) {  // ❌ 移除这层检查
        viewModel.startRecording { ... }
    }
}
```

**修改后**：
```kotlin
"start" -> {
    // ✅ 直接调用，由 ViewModel 统一处理
    viewModel.startRecording { isRecordingStarted ->
        if (isRecordingStarted) {
            vibrator.vibrate(createStaccatoVibrationEffect(1))
        } else {
            vibrator.vibrate(createStaccatoVibrationEffect(3))
        }
    }
}
```

### 修复2：为 stopRecording() 添加回调

**MainViewModel.kt 修改**：
```kotlin
// 修改前
fun stopRecording() { ... }

// 修改后
fun stopRecording(onResult: ((Boolean) -> Unit)? = null) {
    // ... 各种检查 ...
    
    if (isRecordingOperationInProgress) {
        onResult?.invoke(false)  // ✅ 返回失败
        return
    }
    
    if (!_isRecording.value) {
        onResult?.invoke(false)  // ✅ 返回失败
        return
    }
    
    try {
        streamSnapshot.stopRecord()
        onResult?.invoke(true)   // ✅ 返回成功
    } catch (e: Exception) {
        onResult?.invoke(false)  // ✅ 返回失败
    }
}
```

### 修复3：网络命令使用回调反馈

**MainActivity.kt 修改**：
```kotlin
"stop" -> {
    viewModel.stopRecording { isStopInitiated ->
        if (isStopInitiated) {
            vibrator.vibrate(createStaccatoVibrationEffect(2))  // 成功振动
        } else {
            vibrator.vibrate(createStaccatoVibrationEffect(3))  // 失败振动
        }
    }
}
```

## 修复后的统一行为

### 三个入口点现在完全统一

| 入口点 | 行为模式 | 状态检查层级 | 反馈机制 |
|--------|---------|-------------|---------|
| UI按钮 | Toggle | ✅ ViewModel层（统一） | ✅ 回调+振动 |
| 物理按钮 | Toggle | ✅ ViewModel层（统一） | ✅ 回调+振动 |
| 网络命令 | 显式start/stop | ✅ ViewModel层（统一） | ✅ 回调+振动 |

**关键改进**：
1. ✅ 所有状态检查都在 ViewModel 层
2. ✅ 所有请求都受操作锁保护
3. ✅ 所有操作都有详细日志
4. ✅ 所有操作都有结果反馈

## 为什么保持显式 start/stop？

虽然 UI/物理按钮使用 toggle，但网络命令保持显式 start/stop 是**有意为之**：

### 优势1：更清晰的远程控制语义

```json
// ✅ 显式命令：清晰明确
{"command": "startRecording"}
{"command": "stopRecording"}

// ❌ Toggle命令：需要知道当前状态
{"command": "toggleRecording"}  // 会开始还是停止？
```

### 优势2：幂等性和错误恢复

```
场景：网络不稳定，命令可能丢失或重复

显式命令：
  PC: startRecording  →  设备未收到
  PC: startRecording  →  设备收到，开始录像 ✅
  （重复命令被ViewModel拒绝，但不影响最终状态）

Toggle命令：
  PC: toggleRecording  →  设备未收到
  PC: toggleRecording  →  设备收到，但行为未知 ❌
  （无法确定最终状态）
```

### 优势3：符合REST API最佳实践

```
POST /api/recording/start   ✅ 显式动作
POST /api/recording/stop    ✅ 显式动作
POST /api/recording/toggle  ❌ 状态不确定
```

## 并发场景验证

### 场景1：网络 + UI 同时开始录像

```
T0: PC发送 startRecording 命令
T1: 用户点击 UI 按钮
    ↓
T2: 网络命令到达 startRecording()
    检查 isRecordingOperationInProgress = false ✅
    设置 isRecordingOperationInProgress = true
    ↓
T3: UI点击到达 toggleRecording() → startRecording()
    检查 isRecordingOperationInProgress = true ❌
    Log: "⚠️ Recording operation already in progress"
    振动3次（失败反馈）
    ↓
T4: 网络命令完成录像启动
    释放 isRecordingOperationInProgress = false
    振动1次（成功反馈）
```

**结果**：✅ 只有一个录像会话，用户得到明确反馈

### 场景2：快速连续的网络命令

```
T0: PC发送多个 startRecording 命令（网络延迟/重试）
    ↓
T1: 第一个命令：获取操作锁，开始录像
T2: 第二个命令：检测到操作锁，被拒绝
T3: 第三个命令：检测到操作锁，被拒绝
    ↓
日志：
  "📹 Recording start requested..."
  "⚠️ Recording operation already in progress"
  "⚠️ Recording operation already in progress"
```

**结果**：✅ 重复命令被安全拒绝，有完整日志

### 场景3：stop 命令在未录像时

```
PC发送 stopRecording 命令，但设备未在录像
    ↓
stopRecording() 被调用
    ↓
检查 _isRecording.value = false
    ↓
Log: "⚠️ Recording is not active, ignoring stop request"
onResult?.invoke(false)
    ↓
振动3次（失败反馈）
```

**结果**：✅ 无效命令被忽略，但有日志和反馈

## 日志追踪

### 正常流程日志

**网络命令 start**：
```
I StatusReportingManager: Start recording command received
I MainActivity: 📹 Recording start requested. Current state: isRecording=false, operationInProgress=false
D MainActivity: 🔒 Recording operation lock acquired
D MainActivity: 🎬 startRecordingInternal: Initiating recording
I MainActivity: ✅ Recording STARTED successfully
D MainActivity: 💡 LED blinking started
D MainActivity: 🔓 Recording operation lock released, result=true
```

**网络命令 stop**：
```
I StatusReportingManager: Stop recording command received
I MainActivity: ⏹️ Recording stop requested. Current state: isRecording=true, operationInProgress=false
I MainActivity: 🛑 Stopping recording...
I MainActivity: ⏹️ Recording STOPPED
D MainActivity: 💡 LED blinking stopped
```

### 重复命令日志

```
I StatusReportingManager: Start recording command received (x3)
I MainActivity: 📹 Recording start requested. Current state: isRecording=false, operationInProgress=false
D MainActivity: 🔒 Recording operation lock acquired
I MainActivity: 📹 Recording start requested. Current state: isRecording=false, operationInProgress=true
W MainActivity: ⚠️ Recording operation already in progress, ignoring request
I MainActivity: 📹 Recording start requested. Current state: isRecording=true, operationInProgress=false
W MainActivity: ⚠️ Recording is already active, ignoring request
```

## 向后兼容性

### ✅ 完全兼容

1. **网络协议不变**：仍然使用 `startRecording` / `stopRecording` 命令
2. **API不变**：`startRecording()` 和 `stopRecording()` 的调用方式保持兼容
3. **行为改进**：只是增强了并发保护和错误处理

### 升级路径

对于现有的客户端（PC端控制软件）：
- ✅ 无需修改代码
- ✅ 自动获得更好的并发保护
- ✅ 更详细的日志便于调试

## 测试验证

### 测试1：网络命令单独测试

```bash
# 发送开始录像命令
echo '{"command":"startRecording"}' | websocat ws://192.168.16.88:8000

# 发送停止录像命令
echo '{"command":"stopRecording"}' | websocat ws://192.168.16.88:8000
```

**预期**：
- 开始时振动1次，LED闪烁
- 停止时振动2次，LED停止

### 测试2：重复命令测试

```bash
# 快速发送多个开始命令
for i in {1..5}; do
  echo '{"command":"startRecording"}' | websocat ws://192.168.16.88:8000 &
done
```

**预期**：
- 只有一个录像会话
- 日志中有4条 "⚠️ operation already in progress"
- 只有一个成功振动

### 测试3：混合入口测试

```bash
# 发送网络命令的同时按物理按钮
echo '{"command":"startRecording"}' | websocat ws://192.168.16.88:8000 &
# 立即按物理相机按钮
```

**预期**：
- 只有一个录像会话
- 后到达的请求被拒绝
- 完整的日志追踪

## 总结

### ✅ 问题已解决

1. **统一的状态检查**：所有入口点都在 ViewModel 层检查
2. **完整的反馈机制**：所有操作都有回调和振动反馈
3. **一致的日志记录**：所有请求都被记录
4. **保持了语义清晰**：网络命令继续使用显式 start/stop

### 设计原则

1. **单一职责**：MainActivity 负责 UI 交互，ViewModel 负责状态管理
2. **统一处理**：所有并发控制和状态检查在一个地方
3. **清晰的反馈**：每个操作都有明确的结果
4. **完整的日志**：所有路径都可追踪

### 文件修改清单

- `MainViewModel.kt`: 为 `stopRecording()` 添加回调参数
- `MainActivity.kt`: 移除 `recordingControlReceiver` 中的重复检查，使用回调

