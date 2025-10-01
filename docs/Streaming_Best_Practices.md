# 直播功能配置和最佳实践

本文档提供直播功能的配置说明、性能优化技巧和常见问题解决方案。

## 目录

1. [配置参数详解](#配置参数详解)
2. [性能优化](#性能优化)
3. [错误处理](#错误处理)
4. [常见问题](#常见问题)
5. [测试指南](#测试指南)

---

## 配置参数详解

### 视频配置

#### 分辨率 (videoWidth, videoHeight)

```kotlin
// 1080p - 高质量，高带宽要求
videoWidth = 1920
videoHeight = 1080

// 720p - 平衡质量和性能 (推荐)
videoWidth = 1280
videoHeight = 720

// 480p - 低带宽场景
videoWidth = 640
videoHeight = 480
```

**建议**:
- WiFi环境: 720p或1080p
- 移动网络: 480p或720p
- 弱网环境: 480p

#### 比特率 (videoBitrate)

单位: bps (bits per second)

```kotlin
// 1080p推荐
videoBitrate = 6 * 1024 * 1024  // 6 Mbps

// 720p推荐
videoBitrate = 4 * 1024 * 1024  // 4 Mbps

// 480p推荐
videoBitrate = 2 * 1024 * 1024  // 2 Mbps
```

**码率计算公式** (近似):
```
比特率(Mbps) ≈ 分辨率(百万像素) × 帧率 × 0.07
```

#### 帧率 (videoFps)

```kotlin
// 流畅
videoFps = 30  // 推荐

// 超流畅 (高性能设备)
videoFps = 60

// 省电模式
videoFps = 24
```

**注意**: 帧率越高，CPU/GPU负载越大，耗电量越高

### 音频配置

#### 采样率 (audioSampleRate)

```kotlin
// CD音质
audioSampleRate = 48000  // 推荐

// 普通音质
audioSampleRate = 44100

// 低音质 (语音直播)
audioSampleRate = 16000
```

#### 比特率 (audioBitrate)

```kotlin
// 高音质立体声
audioBitrate = 192 * 1024  // 192 kbps

// 标准音质立体声
audioBitrate = 128 * 1024  // 128 kbps (推荐)

// 单声道语音
audioBitrate = 64 * 1024   // 64 kbps
```

#### 声道 (isStereo)

```kotlin
// 立体声 - 音乐/高质量音频
isStereo = true

// 单声道 - 语音通话/节省带宽
isStereo = false
```

### 网络配置

#### RTMP服务器地址

**格式**: `rtmp://服务器IP:端口/应用名`

```kotlin
// 本地测试 (SRS)
streamUrl = "rtmp://192.168.1.100:1935/live"

// 云服务器
streamUrl = "rtmp://live.example.com:1935/live"
```

#### Stream Key

直播流密钥，用于区分不同的直播流

```kotlin
streamKey = "test_stream"  // 测试
streamKey = "user_${userId}_${timestamp}"  // 动态生成
```

### 预设配置方案

#### 高质量配置

```kotlin
StreamConfig(
    streamUrl = "rtmp://server:1935/live",
    streamKey = "hq_stream",
    videoWidth = 1920,
    videoHeight = 1080,
    videoBitrate = 6 * 1024 * 1024,
    videoFps = 30,
    audioSampleRate = 48000,
    audioBitrate = 192 * 1024,
    isStereo = true,
    enableEchoCanceler = true
)
```

#### 标准配置 (推荐)

```kotlin
StreamConfig(
    streamUrl = "rtmp://server:1935/live",
    streamKey = "std_stream",
    videoWidth = 1280,
    videoHeight = 720,
    videoBitrate = 4 * 1024 * 1024,
    videoFps = 30,
    audioSampleRate = 48000,
    audioBitrate = 128 * 1024,
    isStereo = true,
    enableEchoCanceler = false
)
```

#### 省电配置

```kotlin
StreamConfig(
    streamUrl = "rtmp://server:1935/live",
    streamKey = "eco_stream",
    videoWidth = 640,
    videoHeight = 480,
    videoBitrate = 2 * 1024 * 1024,
    videoFps = 24,
    audioSampleRate = 44100,
    audioBitrate = 96 * 1024,
    isStereo = false,
    enableEchoCanceler = false
)
```

---

## 性能优化

### 1. 降低ImageAnalysis开销

**问题**: ImageAnalysis每帧都会回调，直播时可能不需要那么高的帧率

**解决方案**:

```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(1280, 720))
    .setTargetFrameRate(Range(30, 30)) // 限制帧率
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 丢弃旧帧
    .build()
```

### 2. 使用独立线程处理帧

**问题**: 主线程处理帧数据会卡顿UI

**解决方案**:

```kotlin
private val frameExecutor = Executors.newSingleThreadExecutor()

imageAnalysis.setAnalyzer(frameExecutor) { imageProxy ->
    // 在独立线程处理
}

override fun onDestroy() {
    frameExecutor.shutdown()
}
```

### 3. 减少内存拷贝

**问题**: YUV转NV21涉及大量内存操作

**优化**: 在`yuv420ToNv21()`中检查像素排列

```kotlin
private fun yuv420ToNv21(image: Image): ByteArray {
    val pixelStride = image.planes[1].pixelStride
    val rowStride = image.planes[1].rowStride
    
    // 如果UV平面紧凑排列，直接复制
    if (pixelStride == 2 && rowStride == width) {
        // 快速路径
        vBuffer.get(nv21, ySize, uvSize)
    } else {
        // 慢速路径，逐像素处理
    }
}
```

### 4. 降低分辨率

如果设备性能不足，考虑降低CameraX和编码器的分辨率

```kotlin
// CameraX使用较低分辨率
val imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(960, 540)) // 降低到540p
    .build()

// 编码器也使用相同分辨率
StreamConfig(
    videoWidth = 960,
    videoHeight = 540,
    ...
)
```

### 5. GOP优化

**GOP (Group of Pictures)**: 关键帧间隔

```kotlin
// RootEncoder内部设置 (一般不需要手动设置)
// GOP = FPS (1秒一个关键帧)

// 如果需要更低延迟
// GOP = FPS / 2 (0.5秒一个关键帧)
```

### 6. 电池优化

直播是高耗电操作，以下措施可延长续航:

- 降低分辨率和帧率
- 使用单声道音频
- 降低比特率
- 关闭预览 (仅直播不预览)
- 使用硬件编码 (RootEncoder默认)

---

## 错误处理

### 1. 连接失败自动重连

```kotlin
lifecycleScope.launch {
    streamingManager.connectionStatus.collect { status ->
        when (status) {
            ConnectionStatus.FAILED -> {
                Log.e(TAG, "连接失败，3秒后重试")
                delay(3000)
                streamingManager.start()
            }
            ConnectionStatus.DISCONNECTED -> {
                Log.w(TAG, "意外断开，尝试重连")
                delay(1000)
                streamingManager.start()
            }
            else -> {}
        }
    }
}
```

### 2. 限制重连次数

```kotlin
private var retryCount = 0
private val maxRetries = 3

lifecycleScope.launch {
    streamingManager.connectionStatus.collect { status ->
        when (status) {
            ConnectionStatus.FAILED -> {
                if (retryCount < maxRetries) {
                    retryCount++
                    delay(3000)
                    streamingManager.start()
                } else {
                    showError("连接失败，请检查网络")
                    retryCount = 0
                }
            }
            ConnectionStatus.CONNECTED -> {
                retryCount = 0 // 重置计数
            }
            else -> {}
        }
    }
}
```

### 3. 网络状态监听

```kotlin
class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()
    
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    fun observeNetworkState(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }
            
            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        
        connectivityManager?.registerDefaultNetworkCallback(callback)
        
        awaitClose {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
    }
}

// 使用
lifecycleScope.launch {
    networkMonitor.observeNetworkState().collect { isAvailable ->
        if (!isAvailable && streamingManager.isStreaming.value) {
            streamingManager.stop()
            showError("网络已断开")
        }
    }
}
```

### 4. 权限检查

```kotlin
fun checkPermissionsBeforeStreaming(): Boolean {
    val deniedPermissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    
    if (deniedPermissions.isNotEmpty()) {
        showError("缺少权限: ${deniedPermissions.joinToString()}")
        return false
    }
    
    return true
}
```

---

## 常见问题

### Q1: 直播画面卡顿

**可能原因**:
1. 网络带宽不足
2. 比特率设置过高
3. CPU/GPU性能不足
4. ImageAnalysis处理太慢

**解决方案**:
```kotlin
// 1. 降低比特率
videoBitrate = 2 * 1024 * 1024  // 降到2Mbps

// 2. 降低分辨率
videoWidth = 960
videoHeight = 540

// 3. 降低帧率
videoFps = 24

// 4. 使用STRATEGY_KEEP_ONLY_LATEST
.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
```

### Q2: 直播延迟太高

**SRS服务器配置** (`srs.conf`):

```nginx
vhost __defaultVhost__ {
    # 关闭GOP缓存 (关键)
    gop_cache off;
    
    # 减小队列长度
    queue_length 5;
    
    # 使用HTTP-FLV代替HLS
    http_remux {
        enabled on;
        mount [vhost]/[app]/[stream].flv;
    }
}
```

**客户端配置**:

```kotlin
// 减小GOP
videoFps = 30
// GOP会自动设置为30帧 (1秒)

// 如果RootEncoder支持，可手动设置更小的GOP
// gopSize = 15 (0.5秒)
```

### Q3: 音视频不同步

**原因**: 时间戳来源不一致

**解决方案**: 统一使用`System.nanoTime()`

```kotlin
// 视频帧时间戳
val videoTimestamp = System.nanoTime() / 1000

// 音频帧时间戳
val audioTimestamp = System.nanoTime() / 1000
```

### Q4: 切换前后摄像头导致崩溃

**问题**: 切换相机时直播状态未正确处理

**解决方案**:

```kotlin
fun switchCamera(newSelector: CameraSelector) {
    // 1. 停止直播
    val wasStreaming = streamingManager.isStreaming.value
    if (wasStreaming) {
        streamingManager.stop()
    }
    
    // 2. 重新绑定相机
    cameraProvider.unbindAll()
    setupCamera(lifecycleOwner, cameraProvider, previewView, newSelector)
    
    // 3. 重新开始直播
    if (wasStreaming) {
        lifecycleScope.launch {
            delay(500) // 等待相机稳定
            streamingManager.prepare()
            streamingManager.start()
        }
    }
}
```

### Q5: 内存泄漏

**常见原因**:
1. ImageProxy未关闭
2. Executor未shutdown
3. Flow未cancel

**解决方案**:

```kotlin
// 1. 确保ImageProxy关闭
setAnalyzer(executor) { imageProxy ->
    try {
        processImage(imageProxy)
    } finally {
        imageProxy.close() // 必须
    }
}

// 2. 关闭Executor
override fun onDestroy() {
    frameExecutor.shutdown()
}

// 3. ViewModel自动处理Flow
class ViewModel : AndroidViewModel() {
    override fun onCleared() {
        streamingManager.release()
    }
}
```

### Q6: CameraX绑定三个UseCase失败

**错误信息**: `IllegalArgumentException: Unsupported combination of use cases`

**解决方案**:

```kotlin
// 1. 检查设备级别
@ExperimentalCamera2Interop
fun checkCameraLevel(): String {
    val cameraInfo = cameraProvider.getCameraInfo(cameraSelector)
    val camera2Info = Camera2CameraInfo.from(cameraInfo)
    val level = camera2Info.getCameraCharacteristic(
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
    )
    return when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        else -> "OTHER"
    }
}

// 2. 对于LIMITED设备，降低分辨率或合并UseCase
if (checkCameraLevel() == "LIMITED") {
    // 方案A: 使用较低分辨率
    val preview = Preview.Builder()
        .setTargetResolution(Size(960, 540))
        .build()
    
    // 方案B: 只绑定2个UseCase
    cameraProvider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        preview,
        imageAnalysis  // 移除videoCapture
    )
}
```

---

## 测试指南

### 1. 本地测试 (SRS)

**启动SRS服务器**:

```bash
cd streaming
docker compose up -d
```

**配置应用**:

```kotlin
StreamConfig(
    streamUrl = "rtmp://192.168.1.100:1935/live",
    streamKey = "test",
    ...
)
```

**观看直播**:

浏览器打开: `http://192.168.1.100:8000`

### 2. 网络条件测试

使用Android Studio的Network Profiler模拟弱网:

1. 打开 `View > Tool Windows > Profiler`
2. 选择 `Network`
3. 设置带宽限制:
   - WiFi: 50 Mbps / 10 Mbps
   - 4G: 5 Mbps / 2 Mbps
   - 3G: 1 Mbps / 500 Kbps

### 3. 性能测试

**监控指标**:

```kotlin
class PerformanceMonitor {
    private var frameCount = 0
    private var startTime = System.currentTimeMillis()
    
    fun recordFrame() {
        frameCount++
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed >= 1000) {
            val fps = frameCount * 1000.0 / elapsed
            Log.d(TAG, "实际FPS: $fps")
            frameCount = 0
            startTime = System.currentTimeMillis()
        }
    }
}
```

**CPU使用率**:

```bash
# 使用adb监控CPU
adb shell top -n 1 | grep <package_name>
```

**电池消耗**:

```bash
# 查看电池统计
adb shell dumpsys battery
adb shell dumpsys batterystats
```

### 4. 兼容性测试

测试不同设备级别:

| 设备类型 | Camera Level | UseCase数 | 测试点 |
|---------|-------------|-----------|--------|
| 旗舰机 | LEVEL_3 | 3 | 全功能 |
| 中端机 | FULL | 3 | 全功能 |
| 低端机 | LIMITED | 2 | 降级方案 |
| 老设备 | LEGACY | 1-2 | 最小化功能 |

### 5. 压力测试

**长时间直播**:

```kotlin
// 自动测试：直播2小时
lifecycleScope.launch {
    streamingManager.start()
    delay(2.hours.inWholeMilliseconds)
    streamingManager.stop()
    checkForMemoryLeaks()
}
```

**频繁开关**:

```kotlin
// 测试100次开关
repeat(100) {
    streamingManager.start()
    delay(5000)
    streamingManager.stop()
    delay(1000)
}
```

---

## 最佳实践总结

### ✅ 推荐做法

1. **使用独立线程处理帧数据**
2. **及时关闭ImageProxy**
3. **在ViewModel中管理StreamingManager**
4. **使用StateFlow监听状态变化**
5. **实现自动重连机制**
6. **根据网络状况动态调整码率**
7. **提供UI反馈让用户知道直播状态**

### ❌ 避免的做法

1. **在主线程处理YUV转换**
2. **忘记释放资源 (Executor, StreamingManager)**
3. **硬编码配置参数**
4. **不处理权限拒绝情况**
5. **不检查网络状态就开始直播**
6. **绑定过多UseCase而不检查设备能力**

---

## 调试技巧

### 启用RootEncoder日志

```kotlin
// 在Application或MainActivity中
System.setProperty("pedro.debug", "true")
```

### 查看RTMP连接详情

```kotlin
lifecycleScope.launch {
    streamingManager.connectionStatus.collect { status ->
        Log.d(TAG, "连接状态: $status")
        // 可以在这里添加Toast或Snackbar提示
    }
}
```

### 监控帧率和比特率

```kotlin
// 在ConnectChecker中
override fun onNewBitrate(bitrate: Long) {
    val kbps = bitrate / 1024
    Log.d(TAG, "当前码率: $kbps kbps")
    runOnUiThread {
        binding.bitrateText.text = "$kbps kbps"
    }
}
```

---

## 配置文件示例

### 开发环境配置

`config/dev.properties`:

```properties
stream.url=rtmp://192.168.1.100:1935/live
stream.key=dev_test
video.width=1280
video.height=720
video.bitrate=4096
video.fps=30
```

### 生产环境配置

`config/prod.properties`:

```properties
stream.url=rtmp://live.example.com:1935/live
stream.key=${DYNAMIC_KEY}
video.width=1920
video.height=1080
video.bitrate=6144
video.fps=30
```

### 读取配置

```kotlin
class ConfigReader(context: Context) {
    private val properties = Properties()
    
    init {
        val env = if (BuildConfig.DEBUG) "dev" else "prod"
        context.assets.open("config/$env.properties").use {
            properties.load(it)
        }
    }
    
    fun getStreamConfig(): StreamConfig {
        return StreamConfig(
            streamUrl = properties.getProperty("stream.url"),
            streamKey = properties.getProperty("stream.key"),
            videoWidth = properties.getProperty("video.width").toInt(),
            videoHeight = properties.getProperty("video.height").toInt(),
            videoBitrate = properties.getProperty("video.bitrate").toInt() * 1024,
            videoFps = properties.getProperty("video.fps").toInt()
        )
    }
}
```

