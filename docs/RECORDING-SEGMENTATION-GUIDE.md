# 录制自动分段功能指南

## 功能概述

本功能实现了在不中断录制流的情况下，自动按照文件大小（默认100MB）分割保存视频文件。

## 核心特性

### 1. 自动分段
- 默认每个分段最大 100MB（可配置）
- 在文件达到 95MB（95%）时触发分段切换
- 每5秒检查一次文件大小

### 2. 无缝切换
- LED 保持闪烁，不中断
- 无 TTS 提示，静默切换
- 等待 STOPPED 回调完成后再启动新录制
- 中断时间约 200-500ms（取决于 MediaMuxer 刷新时间）

### 3. 文件命名（统一格式）
```
recording_20251114_040438_part000.mp4   # 初始文件（part000）
recording_20251114_040438_part001.mp4   # 第1个分段
recording_20251114_040438_part002.mp4   # 第2个分段
...
```

### 4. MD5 计算（并行执行）
- 每个分段文件都会自动计算 MD5
- 文件稳定后（3秒内大小不变）开始计算
- **异步并行执行，不阻塞新录制启动**
- 前一个分段计算 MD5 的同时，新分段已开始录制

## 配置选项

### DefaultConfig.kt
```kotlin
// 是否启用自动分段（默认启用）
const val DEFAULT_ENABLE_RECORDING_SEGMENTATION = true

// 分段大小（默认100MB）
const val DEFAULT_SEGMENT_SIZE_MB = 100
const val DEFAULT_SEGMENT_SIZE_BYTES = 104_857_600L // 100MB in bytes
```

### 通过参数配置
```kotlin
// 在 ViewModel 初始化时传入
savedState.put("enableRecordingSegmentation", true)  // 启用/禁用
savedState.put("segmentSizeMB", 200)                 // 设置为200MB
```

## 实现架构

### 1. RecordingSegmentManager
- 监控录制文件大小
- 达到阈值时触发 `onSegmentSwitchNeeded` 回调
- 可配置大小限制和检查频率

### 2. MainViewModel
```kotlin
// 分段切换流程（并行执行 MD5 计算）
handleSegmentSwitch()
  ↓
设置 isSegmentSwitching = true
  ↓
调用 stopRecord()
  ↓
STOPPED 回调触发
  ↓
├─ startNextSegment() (立即执行)
│   ↓
│   启动新录制
│   ↓
│   STARTED 回调：isSegmentSwitching = false
│
└─ viewModelScope.launch(IO) (并行异步)
    ↓
    计算前一个分段的 MD5
    ↓
    保存 .md5 文件
```

### 3. 状态管理
- `isSegmentSwitching`: 标识是否在分段切换中
- `nextSegmentFilePath`: 存储下一个分段的路径
- `currentRecordingFile`: 当前录制文件引用

## 关键代码

### 启动监控
```kotlin
// 在 STARTED 回调中启动
if (isRecordingSegmentationEnabled) {
    recordingSegmentManager.startMonitoring(recordFile, viewModelScope)
}
```

### 分段切换
```kotlin
private fun handleSegmentSwitch(currentFile: File, nextFilePath: String) {
    isSegmentSwitching = true
    nextSegmentFilePath = nextFilePath
    streamSnapshot.stopRecord()  // STOPPED 回调会启动新录制
}
```

### 启动新分段
```kotlin
// 在 STOPPED 回调中
if (isSegmentSwitching) {
    nextSegmentFilePath?.let { nextPath ->
        startNextSegment(nextPath)
        nextSegmentFilePath = null
    }
}
```

## 用户体验

### 正常启停（手动）
| 操作 | LED | TTS | 延迟 | 事件通知 |
|------|-----|-----|------|---------|
| 开始录制 | ✅ 闪烁 | ✅ 提示 | - | ✅ 通知 |
| 停止录制 | ✅ 停止 | ✅ 提示 | - | ✅ 通知 |

### 自动分段切换
| 操作 | LED | TTS | 延迟 | 事件通知 |
|------|-----|-----|------|---------|
| 分段切换 | ✅ 保持闪烁 | ❌ 无 | ~200ms | ✅ 静默日志 |

## 日志追踪

### 关键日志
```
# 文件大小监控
📊 Starting segment monitoring for: recording_xxx.mp4
📏 Current file size: 950MB / 1024MB
🔄 File size limit reached, triggering segment switch

# 分段切换
⏸️ Stopping current segment...
▶️ Starting next segment after STOPPED callback
✅ Next segment STARTED successfully
📊 Segment switch completed successfully (segment #1)

# MD5 计算
Starting MD5 calculation for segment: xxx_part001.mp4
MD5 file created successfully for segment
```

### 调试命令
```bash
# 查看分段切换日志
adb logcat | grep "🔄\|📊\|▶️"

# 查看文件大小监控
adb logcat | grep "📏\|RecordSegmentManager"

# 查看录制状态
adb logcat | grep "Recording.*started\|Recording.*STOPPED"
```

## 已知限制

### 1. 不是完全无缝
- 有 200-500ms 的中断时间
- 约丢失 5-12 帧（24fps）
- 对大多数应用场景可接受

### 2. 文件系统延迟
- MediaMuxer 需要时间刷新缓冲区
- 必须等待 STOPPED 回调完成

### 3. 触发阈值
- 在 95% 时触发（95MB for 100MB limit）
- 实际文件可能略超限制（因为检查间隔为5秒，录制比特率高时可能超出）

## 与 CameraX 方案对比

| 特性 | 当前方案 (RootEncoder) | CameraX 方案 |
|------|----------------------|-------------|
| 无缝切换 | ❌ 有 200-500ms 中断 | ✅ 完全无缝 |
| 流媒体 | ✅ 支持 RTMP/SRT | ❌ 不支持 |
| 实现难度 | ✅ 已实现 | ⚠️ 需重构架构 |
| 帧丢失 | ⚠️ 5-12 帧/小时 | ✅ 0 帧 |

## 测试场景

### 场景1：基本分段测试
1. 开始录制
2. 等待文件达到 1GB
3. 观察是否自动切换到 part001
4. 检查文件是否完整

### 场景2：多分段连续测试
1. 开始录制
2. 持续录制 300MB 数据
3. 应生成：
   - recording_xxx_part000.mp4 (100MB)
   - recording_xxx_part001.mp4 (100MB)
   - recording_xxx_part002.mp4 (100MB)

### 场景3：停止录制
1. 在分段切换过程中停止录制
2. 确认所有文件都正常保存
3. 确认所有文件都有 MD5

### 场景4：禁用分段功能
1. 设置 `enableRecordingSegmentation = false`
2. 录制超过 100MB
3. 应只生成一个文件（无分段）

## 故障排查

### 问题：分段未触发
**检查**：
- `isRecordingSegmentationEnabled` 是否为 true
- 日志中是否有 "📊 Segment monitoring started"
- 文件是否真的达到 95MB（对于100MB限制）

### 问题：文件未生成
**检查**：
- 日志中是否有 "▶️ Starting next segment"
- 是否有错误日志 "❌ Failed to start next segment"
- 存储空间是否充足

### 问题：MD5 未计算
**检查**：
- 日志中是否有 "Starting MD5 calculation for segment"
- 文件是否存在
- 文件大小是否稳定

## 版本历史

### v1.1 (2025-11-14)
- ✅ MD5 计算改为并行执行（不阻塞新录制）
- ✅ 初始文件命名改为 part000（统一格式）
- ✅ 默认分段大小改为 100MB（方便测试）

### v1.0 (2025-11-14)
- ✅ 初始实现
- ✅ 文件大小监控
- ✅ 自动分段切换
- ✅ MD5 计算
- ✅ 配置选项

## 相关文件

- `RecordingSegmentManager.kt` - 分段管理器
- `MainViewModel.kt` - 集成逻辑
- `DefaultConfig.kt` - 配置选项

