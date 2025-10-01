# CameraXSource 集成指南

本文档说明如何在项目中使用 RootEncoder 的 **CameraXSource** 进行 RTMP 直播。

## ⭐ 重要说明

本项目使用 **CameraXSource**（来自 `com.pedro.extrasources` 包），这是 RootEncoder 专门为 CameraX 提供的视频源。与手动实现 `BufferVideoSource` 或自定义视频源不同，CameraXSource 会自动处理所有 CameraX 集成细节。

## 核心优势

✅ **自动化**：无需手动转换 YUV 格式或喂帧  
✅ **简化代码**：不需要 ImageAnalysis 回调处理  
✅ **高性能**：内部优化的帧数据传输  
✅ **稳定性强**：经过 RootEncoder 官方测试和验证  

## 架构概述

```
┌─────────────────────────────────────────────────┐
│              MainActivity                        │
│  - 权限管理                                      │
│  - UI 事件处理                                   │
│  - 生命周期管理                                  │
└──────────────────┬──────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────┐
│              MainViewModel                       │
│  - 直播状态管理 (StateFlow)                     │
│  - GenericStream 封装                           │
│  - 音视频编码参数配置                            │
└──────────────────┬──────────────────────────────┘
                   │
     ┌─────────────┴─────────────┐
     ▼                           ▼
┌───────────────┐      ┌──────────────────┐
│ CameraXSource │      │   AudioSource    │
│ (RootEncoder) │      │  - Standard      │
│               │      │  - Thinklet      │
└───────┬───────┘      └────────┬─────────┘
        │                       │
        └───────────┬───────────┘
                    ▼
        ┌───────────────────────┐
        │   GenericStream       │
        │  - 音视频编码          │
        │  - RTMP 推流           │
        └───────────────────────┘
```

## 核心依赖

### build.gradle.kts

```kotlin
dependencies {
    // CameraX
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-video:1.4.0")
    
    // RootEncoder - RTMP 推流
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.4")
    implementation("com.github.pedroSG94.RootEncoder:extra-sources:2.6.4")  // ⚠️ 提供 CameraXSource
    
    // 协程
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
```

### AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />

<application
    android:usesCleartextTraffic="true"
    ... >
</application>
```

## 完整实现步骤

### 1. 创建 CameraXSource

```kotlin
import com.pedro.extrasources.CameraXSource

// 在 ViewModel 中创建
val cameraXSource = CameraXSource(application)
```

### 2. 创建音频源

```kotlin
// 使用标准麦克风
val audioSource = StandardMicrophoneSource()

// 或使用 Thinklet 多麦克风
val audioSource = ThinkletMicrophoneSource(
    context = application,
    inputChannel = MultiChannelAudioRecord.Channel.CHANNEL_FIVE
)
```

### 3. 创建 GenericStream

```kotlin
import com.pedro.library.generic.GenericStream
import com.pedro.common.ConnectChecker

val stream = GenericStream(
    application,
    connectChecker,  // 连接状态监听器
    cameraXSource,   // ⚠️ 使用 CameraXSource
    audioSource
).apply {
    // 配置 OpenGL 接口
    getGlInterface().autoHandleOrientation = false
    
    // 可选：添加旋转滤镜
    if (needRotation) {
        val rotationFilter = RotationFilterRender().apply {
            rotation = 270
        }
        getGlInterface().addFilter(rotationFilter)
    }
}
```

### 4. 准备编码器

```kotlin
// 准备视频编码器
val videoPrepared = stream.prepareVideo(
    width = 720,
    height = 480,
    bitrate = 4096 * 1024,  // 4 Mbps
    rotation = 0
)

// 准备音频编码器
val audioPrepared = stream.prepareAudio(
    sampleRate = 48000,
    isStereo = true,
    bitrate = 128 * 1024  // 128 kbps
)

if (videoPrepared && audioPrepared) {
    Log.d(TAG, "编码器准备成功")
} else {
    Log.e(TAG, "编码器准备失败")
}
```

### 5. 开始直播

```kotlin
val rtmpUrl = "rtmp://192.168.16.88:1935/thinklet.squid.run/test_stream"
stream.startStream(rtmpUrl)
```

### 6. 停止直播

```kotlin
stream.stopStream()
```

### 7. 释放资源

```kotlin
stream.release()
```

## MainViewModel 完整示例

```kotlin
class MainViewModel(
    private val application: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isPrepared = MutableStateFlow(false)
    val isPrepared: StateFlow<Boolean> = _isPrepared.asStateFlow()

    private var stream: GenericStream? = null

    fun maybePrepareStreaming(lifecycleOwner: LifecycleOwner) {
        if (_isPrepared.value) return

        viewModelScope.launch {
            try {
                // 创建 CameraXSource
                val cameraXSource = CameraXSource(application)
                
                // 创建音频源
                val audioSource = StandardMicrophoneSource()
                
                // 创建 GenericStream
                val localStream = GenericStream(
                    application,
                    createConnectChecker(),
                    cameraXSource,
                    audioSource
                ).apply {
                    getGlInterface().autoHandleOrientation = false
                }
                
                // 准备编码器
                val videoPrepared = localStream.prepareVideo(
                    width = 720,
                    height = 480,
                    bitrate = 4096 * 1024,
                    rotation = 0
                )
                
                val audioPrepared = localStream.prepareAudio(
                    sampleRate = 48000,
                    isStereo = true,
                    bitrate = 128 * 1024
                )
                
                if (videoPrepared && audioPrepared) {
                    stream = localStream
                    _isPrepared.value = true
                    Log.d(TAG, "直播准备成功")
                } else {
                    Log.e(TAG, "编码器准备失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "准备失败", e)
                _isPrepared.value = false
            }
        }
    }

    fun startStreaming(rtmpUrl: String) {
        val streamInstance = stream ?: return
        if (_isStreaming.value) return
        
        streamInstance.startStream(rtmpUrl)
    }

    fun stopStreaming() {
        stream?.stopStream()
        _isStreaming.value = false
        _connectionStatus.value = ConnectionStatus.IDLE
    }

    private fun createConnectChecker() = object : ConnectChecker {
        override fun onAuthError() {
            _connectionStatus.value = ConnectionStatus.FAILED
        }

        override fun onAuthSuccess() {
            Log.d(TAG, "RTMP 认证成功")
        }

        override fun onConnectionFailed(reason: String) {
            _isStreaming.value = false
            _connectionStatus.value = ConnectionStatus.FAILED
        }

        override fun onConnectionStarted(url: String) {
            _connectionStatus.value = ConnectionStatus.CONNECTING
        }

        override fun onConnectionSuccess() {
            _isStreaming.value = true
            _connectionStatus.value = ConnectionStatus.CONNECTED
        }

        override fun onDisconnect() {
            _isStreaming.value = false
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }

        override fun onNewBitrate(bitrate: Long) {
            Log.d(TAG, "当前码率: ${bitrate / 1024} kbps")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stream?.release()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}

enum class ConnectionStatus {
    IDLE, CONNECTING, CONNECTED, FAILED, DISCONNECTED
}
```

## 与其他方案对比

### ❌ 错误方案：手动实现 BufferVideoSource

```kotlin
// 错误示例 - 过于复杂
val videoSource = BufferVideoSource(Format.NV21, bitrate)
videoSource.init(width, height, fps, rotation)
videoSource.start(surfaceTexture)

// 需要手动设置 ImageAnalysis
imageAnalysis.setAnalyzer(executor) { imageProxy ->
    imageProxy.image?.let { image ->
        val nv21 = yuv420ToNv21(image)  // 手动转换格式
        videoSource.setBuffer(nv21)     // 手动喂帧
    }
    imageProxy.close()
}
```

**问题**：
- 需要手动转换 YUV420 到 NV21
- 需要手动管理 ImageAnalysis
- 需要手动喂入每一帧
- 容易出现内存泄漏和性能问题

### ✅ 正确方案：使用 CameraXSource

```kotlin
// 正确示例 - 简单直接
val cameraXSource = CameraXSource(application)

val stream = GenericStream(
    application,
    connectChecker,
    cameraXSource,  // ✅ 就这么简单！
    audioSource
)
```

**优势**：
- 无需手动转换格式
- 无需手动喂帧
- CameraXSource 自动处理所有细节
- 代码更简洁、更可靠

## 配置参数

### 视频参数

```kotlin
stream.prepareVideo(
    width = 1280,          // 视频宽度
    height = 720,          // 视频高度
    bitrate = 4096 * 1024, // 比特率（4 Mbps）
    fps = 30,              // 帧率
    rotation = 0           // 旋转角度
)
```

**推荐配置**：

| 场景 | 分辨率 | 比特率 | 帧率 |
|------|--------|--------|------|
| 高质量 | 1920x1080 | 6 Mbps | 30 |
| 标准 | 1280x720 | 4 Mbps | 30 |
| 省电 | 640x480 | 2 Mbps | 24 |

### 音频参数

```kotlin
stream.prepareAudio(
    sampleRate = 48000,    // 采样率（Hz）
    isStereo = true,       // 是否立体声
    bitrate = 128 * 1024,  // 比特率（128 kbps）
    echoCanceler = false   // 是否启用回声消除
)
```

**推荐配置**：

| 场景 | 采样率 | 比特率 | 声道 |
|------|--------|--------|------|
| 高质量 | 48000 | 192 kbps | 立体声 |
| 标准 | 48000 | 128 kbps | 立体声 |
| 语音 | 16000 | 64 kbps | 单声道 |

## 常见问题

### Q1: CameraXSource 与 Camera2Source 的区别？

**A**: 
- **Camera2Source**: 使用 Camera2 API，需要手动管理相机
- **CameraXSource**: 使用 CameraX，自动处理相机生命周期

本项目使用 CameraXSource，因为它更简单、更现代。

### Q2: 是否需要手动设置 ImageAnalysis？

**A**: **不需要**。CameraXSource 内部已经处理了所有视频帧的采集和传输，你不需要自己创建 ImageAnalysis UseCase。

### Q3: 如何切换前后摄像头？

**A**: CameraXSource 内部管理相机，暂不支持运行时切换。如需切换，需要重新创建 CameraXSource 并重新准备 stream。

### Q4: 是否可以同时使用 Preview、VideoCapture 和 CameraXSource？

**A**: 可以，但需要注意：
- CameraXSource 内部会创建自己的相机 session
- 不要在外部再次绑定相机到同一个 CameraProvider
- 如果需要预览，使用 `stream.startPreview(surface, width, height)`

### Q5: 性能如何优化？

**A**: 
1. 降低视频分辨率和比特率
2. 使用硬件编码器（RootEncoder 默认启用）
3. 适当降低帧率（24-30 fps）
4. 关闭不必要的滤镜效果

## 最佳实践

### ✅ 推荐做法

1. **使用 ViewModel 管理 GenericStream**
   ```kotlin
   class MainViewModel : AndroidViewModel() {
       private var stream: GenericStream? = null
       
       override fun onCleared() {
           stream?.release()
       }
   }
   ```

2. **使用 StateFlow 监听状态变化**
   ```kotlin
   val isStreaming = MutableStateFlow(false)
   
   lifecycleScope.launch {
       isStreaming.collect { streaming ->
           updateUI(streaming)
       }
   }
   ```

3. **在 ViewModelScope 中初始化**
   ```kotlin
   fun prepare() {
       viewModelScope.launch {
           // 初始化代码
       }
   }
   ```

### ❌ 避免的做法

1. **不要在 Activity 中直接持有 GenericStream**  
   → 会导致内存泄漏

2. **不要忘记调用 release()**  
   → 会导致资源未释放

3. **不要在主线程进行耗时操作**  
   → 使用协程或后台线程

## 调试技巧

### 启用 RootEncoder 日志

```kotlin
// 在 Application 或 MainActivity 中
System.setProperty("pedro.debug", "true")
```

### 监控连接状态

```kotlin
override fun onConnectionStarted(url: String) {
    Log.d(TAG, "开始连接: $url")
}

override fun onConnectionSuccess() {
    Log.d(TAG, "连接成功")
}

override fun onConnectionFailed(reason: String) {
    Log.e(TAG, "连接失败: $reason")
}
```

### 监控码率

```kotlin
override fun onNewBitrate(bitrate: Long) {
    val kbps = bitrate / 1024
    Log.d(TAG, "当前码率: $kbps kbps")
}
```

## 相关文档

- [原始项目代码](./Original_Project_Code.md) - 完整的源代码实现
- [配置和最佳实践](./Streaming_Best_Practices.md) - 性能优化和配置指南
- [RootEncoder 官方文档](https://github.com/pedroSG94/RootEncoder)
- [CameraX 官方文档](https://developer.android.com/training/camerax)

## 总结

使用 CameraXSource 的关键点：

1. ✅ 添加 `extra-sources` 依赖
2. ✅ 创建 `CameraXSource(application)`
3. ✅ 传入 GenericStream 构造器
4. ✅ 准备视频和音频编码器
5. ✅ 开始直播

**无需**：
- ❌ 手动实现视频源
- ❌ 手动转换 YUV 格式
- ❌ 手动喂入视频帧
- ❌ 手动管理 ImageAnalysis

CameraXSource 让 RTMP 直播变得简单、可靠、高效！

