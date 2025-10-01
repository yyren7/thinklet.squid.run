# Android RTMP直播应用 - Thinklet.Squid.Run

基于CameraX和RootEncoder实现的Android RTMP直播应用。

## 项目特点

- ✅ CameraX集成 - 现代化相机API
- ✅ RTMP推流 - 低延迟直播
- ✅ 多UseCase支持 - 预览、录像、直播同时进行
- ✅ 模块化设计 - 易于集成到其他项目
- ✅ Kotlin协程 - StateFlow状态管理

## 文档导航 📚

### 🎯 [CameraX集成指南](./docs/CameraX_Streaming_Integration.md)

将直播功能集成到CameraX应用：
- 架构设计和集成策略
- CameraX三UseCase绑定
- UseCase限制解决方案

### 💻 [完整源代码](./docs/Streaming_Source_Code.md)

核心类的完整实现，可直接复制使用：
- StreamingManager
- CameraXVideoSource
- StandardMicrophoneSource
- ThinkletMicrophoneSource

### ⚙️ [配置和最佳实践](./docs/Streaming_Best_Practices.md)

参数配置、性能优化、问题解决：
- 视频/音频参数详解
- 预设配置方案
- 性能优化技巧
- 常见问题解答

### 🎥 [SRS服务器配置](./streaming/README-streaming.md)

流媒体服务器部署：
- Docker快速部署
- 低延迟配置
- WSL网络设置

## 快速开始

### 1. 启动RTMP服务器

```bash
cd streaming
docker compose up -d
```

### 2. 配置推流地址

修改 `DefaultConfig.kt`:

```kotlin
const val DEFAULT_STREAM_URL = "rtmp://192.168.1.100:1935/live"
const val DEFAULT_STREAM_KEY = "test_stream"
```

### 3. 编译运行

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

- Kotlin 2.1.0
- CameraX 1.4.0
- RootEncoder 2.6.4
- Coroutines + Flow
- MVVM架构

## 迁移到CameraX应用

1. 阅读 [CameraX集成指南](./docs/CameraX_Streaming_Integration.md)
2. 复制 [源代码](./docs/Streaming_Source_Code.md) 中的核心类
3. 参考 [最佳实践](./docs/Streaming_Best_Practices.md) 进行配置

## 项目结构

```
├── app/src/main/java/ai/fd/thinklet/app/squid/run/
│   ├── MainActivity.kt
│   ├── MainViewModel.kt
│   ├── StandardMicrophoneSource.kt
│   ├── ThinkletMicrophoneSource.kt
│   └── ...
├── docs/                    # 📚 完整文档
├── streaming/               # SRS服务器配置
└── README.md
```

## 许可证

MIT License

