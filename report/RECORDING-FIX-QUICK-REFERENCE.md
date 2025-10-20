# 录像功能修复 - 快速参考

## 修改文件清单

### 1. MainViewModel.kt
**位置**: `app/src/main/java/ai/fd/thinklet/app/squid/run/MainViewModel.kt`

**修改内容**：

#### 新增变量（第212行）
```kotlin
@Volatile
private var isRecordingOperationInProgress: Boolean = false
```

#### startRecording() 方法重构（第609-644行）
- 添加操作锁检查
- 添加状态双重检查
- 添加详细日志
- 确保线程安全

#### startRecordingInternal() 方法修改（第646-786行）
- **关键修改**：移除了 `ledController.startLedBlinking()` 调用
- LED控制移至 `RecordController.Status.STARTED` 回调中
- 添加详细日志和状态追踪

#### 录像回调增强（第693-714行）
```kotlin
RecordController.Status.STARTED -> {
    _isRecording.value = true
    ledController.startLedBlinking()  // ✅ 移到这里
    // ... 其他逻辑
}
RecordController.Status.STOPPED -> {
    _isRecording.value = false
    ledController.stopLedBlinking()
    // ... 其他逻辑
}
```

#### stopRecording() 方法增强（第792-830行）
- 添加操作锁检查
- 添加防御性LED停止（stream为null时也停止LED）
- 添加详细日志

### 2. LedController.kt
**位置**: `app/src/main/java/ai/fd/thinklet/app/squid/run/LedController.kt`

**修改内容**：

#### stopLedBlinking() 方法增强（第28-42行）
```kotlin
fun stopLedBlinking() {
    if (isBlinking) {
        isBlinking = false
        handler.removeCallbacks(blinkRunnable)
        isLedOn = false
        ledClient.updateCameraLed(false)
        
        // ✅ 新增：延迟确认机制
        handler.postDelayed({
            if (!isBlinking) {
                ledClient.updateCameraLed(false)
            }
        }, 100)
    }
}
```

## 核心修复逻辑

### 问题：LED只亮不暗

**原因**：
1. LED在录像真正开始前就启动
2. 如果录像启动失败，LED会残留
3. 停止时可能有硬件驱动延迟

**解决方案**：
1. LED启动移到 `STARTED` 回调中
2. 添加100ms延迟确认机制
3. 在所有错误路径中都确保LED停止

### 问题：并发导致的竞态条件

**原因**：
1. 没有操作锁保护
2. `ensureCameraReady` 是异步的，存在时间窗口
3. 状态检查不够严格

**解决方案**：
1. 添加 `isRecordingOperationInProgress` 标志
2. 在操作开始时加锁，结束时解锁
3. 多层状态检查（入口检查 + 内部双重检查）

### 问题：录像文件损坏

**原因**：
1. 并发调用导致文件路径冲突
2. 底层API被多次调用
3. 相机资源竞争

**解决方案**：
1. 操作锁确保串行执行
2. 日志追踪帮助诊断
3. 状态检查防止重复调用

## 三个入口点的行为

### 入口点对比

| 入口点 | 触发方式 | 调用路径 | 是否使用toggle | 并发保护 |
|--------|---------|---------|---------------|----------|
| UI按钮 | `buttonRecord.onClick` | `toggleRecording()` → `startRecording()` | ✅ | ✅ |
| 物理按钮 | `KEYCODE_CAMERA` | `toggleRecording()` → `startRecording()` | ✅ | ✅ |
| 网络命令 | WebSocket消息 | 直接 `startRecording()` 或 `stopRecording()` | ❌ | ✅ |

### 统一行为保证

虽然UI/物理按钮使用toggle模式，网络命令使用显式命令，但它们都：
1. ✅ 调用相同的 `startRecording()` / `stopRecording()` 方法
2. ✅ 受相同的操作锁保护
3. ✅ 经过相同的状态检查
4. ✅ 触发相同的回调和LED控制

**关键点**：操作锁在 `startRecording()` 方法中设置，所以无论从哪个入口进入，都会受到保护。

## 状态流转图

```
初始状态
    ↓
[startRecording() 被调用]
    ↓
检查 isRecordingOperationInProgress? 
    ↓ NO
检查 _isRecording.value?
    ↓ NO
设置 isRecordingOperationInProgress = true
    ↓
ensureCameraReady (异步)
    ↓
startRecordingInternal()
    ↓
streamSnapshot.startRecord() [API调用]
    ↓
释放 isRecordingOperationInProgress = false
    ↓
[等待回调...]
    ↓
onStatusChange(STARTED) 回调
    ↓
设置 _isRecording.value = true
    ↓
启动 LED 闪烁
    ↓
录像进行中
    ↓
[stopRecording() 被调用]
    ↓
检查 isRecordingOperationInProgress?
    ↓ NO
检查 _isRecording.value?
    ↓ YES
streamSnapshot.stopRecord() [API调用]
    ↓
[等待回调...]
    ↓
onStatusChange(STOPPED) 回调
    ↓
设置 _isRecording.value = false
    ↓
停止 LED 闪烁
    ↓
100ms后再次确认LED关闭
    ↓
回到初始状态
```

## 关键日志标记

| Emoji | 含义 | 示例 |
|-------|------|------|
| 📹 | 录像请求 | `📹 Recording start requested` |
| 🔒 | 操作锁获取 | `🔒 Recording operation lock acquired` |
| 🔓 | 操作锁释放 | `🔓 Recording operation lock released` |
| ✅ | 成功状态 | `✅ Recording STARTED successfully` |
| ⏹️ | 停止操作 | `⏹️ Recording stop requested` |
| 💡 | LED状态 | `💡 LED blinking started` |
| ⚠️ | 警告信息 | `⚠️ Recording operation already in progress` |
| ❌ | 错误信息 | `❌ Recording failed: Stream not ready` |
| 🎬 | 内部操作 | `🎬 startRecordingInternal: Initiating recording` |
| 🛑 | 停止中 | `🛑 Stopping recording...` |

## 诊断命令

```bash
# 查看所有录像相关日志
adb logcat | grep "MainViewModel.*Recording"

# 查看操作锁日志
adb logcat | grep "🔒\|🔓"

# 查看LED状态日志
adb logcat | grep "💡"

# 查看并发冲突
adb logcat | grep "⚠️.*operation already in progress"

# 查看错误
adb logcat | grep "❌.*Recording"

# 统计被拒绝的录像请求
adb logcat | grep "⚠️.*Recording.*already" | wc -l
```

## 验证清单

修复后，以下问题应该全部解决：

- [ ] LED只亮不暗的问题
- [ ] 快速连续点击导致的文件损坏
- [ ] 多入口并发触发导致的状态混乱
- [ ] 录像失败后LED残留的问题
- [ ] 时间窗口导致的重复调用

## 性能影响

修改对性能的影响：
- ✅ **几乎无影响**：只添加了简单的布尔标志检查
- ✅ **日志开销**：可忽略（日志是异步的）
- ✅ **LED延迟确认**：100ms延迟不会影响用户体验

## 兼容性

- ✅ 保持了原有的API接口
- ✅ 不影响现有的三个入口点
- ✅ 向后兼容所有网络命令
- ✅ 不影响其他功能（推流、预览等）

## 回滚方案

如果需要回滚修改：

1. 恢复 `MainViewModel.kt` 中的 LED 调用位置
2. 移除 `isRecordingOperationInProgress` 标志
3. 恢复 `LedController.kt` 的原始实现

```bash
git diff HEAD~1 app/src/main/java/ai/fd/thinklet/app/squid/run/
```

## 下一步行动

1. ✅ 代码已修改并编译通过
2. ⏳ 执行测试指南中的所有场景
3. ⏳ 收集实际使用中的日志
4. ⏳ 根据测试结果优化
5. ⏳ 编写单元测试

## 联系与支持

如有问题或发现新的边缘情况，请：
1. 收集完整日志（至少包含问题前后30秒）
2. 记录复现步骤
3. 注明设备型号和Android版本
4. 提供录像文件状态（是否生成、是否损坏）

