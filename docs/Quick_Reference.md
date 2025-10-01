# 快速参考卡片 🚀

## 30秒快速入门

```kotlin
// 1. 创建 CameraXSource（RootEncoder 提供）
val cameraXSource = CameraXSource(application)

// 2. 创建音频源
val audioSource = StandardMicrophoneSource()

// 3. 创建 GenericStream
val stream = GenericStream(
    application,
    connectChecker,
    cameraXSource,  // ⚠️ 使用 CameraXSource
    audioSource
)

// 4. 准备编码器
stream.prepareVideo(width = 720, height = 480, bitrate = 4096 * 1024)
stream.prepareAudio(sampleRate = 48000, isStereo = true, bitrate = 128 * 1024)

// 5. 开始直播
stream.startStream("rtmp://192.168.16.88:1935/thinklet.squid.run/test_stream")

// 6. 停止直播
stream.stopStream()

// 7. 释放资源
stream.release()
```

## 核心类速查

| 类名 | 职责 | 关键方法 |
|------|------|---------|
| MainViewModel | 直播状态管理 | maybePrepareStreaming(), startStreaming(), stopStreaming() |
| GenericStream | RTMP 推流 | prepareVideo(), prepareAudio(), startStream(), stopStream() |
| CameraXSource | 视频源（RootEncoder 提供） | 自动处理 CameraX 视频流 |
| StandardMicrophoneSource | 标准音频源 | mute(), unMute() |
| ThinkletMicrophoneSource | Thinklet 音频源 | mute(), unMute() |

## 配置速查

### 预设方案

```kotlin
// 高质量 (1080p, 6Mbps, 30fps)
videoWidth = 1920, videoHeight = 1080, videoBitrate = 6 * 1024 * 1024

// 标准 (720p, 4Mbps, 30fps) ⭐推荐
videoWidth = 1280, videoHeight = 720, videoBitrate = 4 * 1024 * 1024

// 省电 (480p, 2Mbps, 24fps)
videoWidth = 640, videoHeight = 480, videoBitrate = 2 * 1024 * 1024
```

### 常用参数

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| videoFps | 30 | 帧率 |
| audioSampleRate | 48000 | 采样率 |
| audioBitrate | 128 * 1024 | 音频比特率 |
| isStereo | true | 立体声 |

## CameraX绑定速查

```kotlin
// 3个UseCase
cameraProvider.bindToLifecycle(
    lifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview,          // 预览
    videoCapture,     // 录像
    imageAnalysis     // 直播
)
```

## 常见问题速查

| 问题 | 快速解决 |
|------|---------|
| ⚠️ 码率一直是 0 | `videoSource.init()` + `videoSource.start(surfaceTexture)` |
| ⚠️ ImageAnalysis INACTIVE | `cameraProvider.bindToLifecycle(this, ...)` |
| 画面卡顿 | 降低分辨率/比特率/帧率 |
| 延迟高 | SRS配置 `gop_cache off;` |
| UseCase绑定失败 | 降低分辨率或减少UseCase |
| 内存泄漏 | `imageProxy.close()` |
| 音视频不同步 | 使用 `System.nanoTime() / 1000` |

## 状态监听速查

```kotlin
lifecycleScope.launch {
    // 连接状态
    streamingManager.connectionStatus.collect { status ->
        when (status) {
            ConnectionStatus.IDLE -> "空闲"
            ConnectionStatus.CONNECTING -> "连接中"
            ConnectionStatus.CONNECTED -> "已连接"
            ConnectionStatus.FAILED -> "失败"
            ConnectionStatus.DISCONNECTED -> "断开"
        }
    }
    
    // 直播状态
    streamingManager.isStreaming.collect { isStreaming ->
        // 更新UI
    }
}
```

## 权限检查速查

```kotlin
// 必需权限
Manifest.permission.CAMERA
Manifest.permission.RECORD_AUDIO
Manifest.permission.INTERNET
```

## 性能优化速查

```kotlin
// 1. 限制帧率
.setTargetFrameRate(Range(30, 30))

// 2. 丢弃旧帧
.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

// 3. 独立线程
private val executor = Executors.newSingleThreadExecutor()
```

## 错误处理速查

```kotlin
lifecycleScope.launch {
    streamingManager.connectionStatus.collect { status ->
        if (status == ConnectionStatus.FAILED) {
            delay(3000)
            streamingManager.start() // 重连
        }
    }
}
```

## SRS服务器速查

```bash
# 启动
cd streaming && docker compose up -d

# 停止
docker compose down

# 查看日志
docker compose logs -f
```

**配置文件**: `streaming/srs.conf`
```nginx
gop_cache off;  # 关键：降低延迟
queue_length 5;
```

## 依赖速查

```kotlin
// CameraX
implementation("androidx.camera:camera-camera2:1.4.0")
implementation("androidx.camera:camera-lifecycle:1.4.0")
implementation("androidx.camera:camera-video:1.4.0")

// RootEncoder
implementation("com.github.pedroSG94.RootEncoder:library:2.6.4")
implementation("com.github.pedroSG94.RootEncoder:extra-sources:2.6.4")
```

## 调试速查

```kotlin
// 启用日志
System.setProperty("pedro.debug", "true")

// 监控帧率
override fun onNewBitrate(bitrate: Long) {
    Log.d(TAG, "码率: ${bitrate / 1024} kbps")
}
```

## 完整文档链接

- [CameraXSource 集成指南](./CameraXSource_Integration_Guide.md) ⭐ **推荐**
- [原始项目代码](./Original_Project_Code.md)
- [配置和最佳实践](./Streaming_Best_Practices.md)

---

**打印此页面作为快速参考！** 📄

