# SRT协议配置指南

## 概述

SquidRun现在支持两种推流协议：
- **RTMP** - 传统流媒体协议（默认）
- **SRT** - 安全可靠传输协议（推荐用于弱网环境）

## SRT优势

### 为什么选择SRT？

1. **弱网优化** - 自动重传丢失的数据包，30%丢包率仍可正常传输
2. **低延迟** - 可配置延迟（200ms-8s），适合实时场景
3. **加密传输** - 支持AES加密，保障数据安全
4. **工业级稳定** - 广泛用于卫星传输、无人机直播等场景

### RTMP vs SRT对比

| 特性 | RTMP | SRT |
|------|------|-----|
| 丢包恢复 | ❌ 无 | ✅ 自动重传 |
| 延迟 | 3-5秒 | 0.2-2秒可调 |
| 弱网表现 | 差 | 优秀 |
| 加密 | 需RTMPS | 内置AES |
| 配置复杂度 | 简单 | 简单 |

## 配置方法

### 通过代码调用

在MainActivity或其他地方调用：

```kotlin
// 切换到SRT
viewModel.updateStreamProtocol("srt")

// 切换到RTMP
viewModel.updateStreamProtocol("rtmp")
```

配置会自动保存到SharedPreferences，重启后保持。

## 服务端配置

### MediaMTX配置（已完成）

`streaming/config/mediamtx.yml` 已添加SRT支持：

```yaml
# RTMP端口（保持兼容）
rtmpAddress: :1935

# SRT端口（新增）
srtAddress: :8890

# WebRTC输出（不变）
webrtcAddress: :8889
```

### 端口说明

| 协议 | 端口 | 用途 |
|------|------|------|
| RTMP | 1935 | Android推流（传统） |
| SRT | 8890 | Android推流（弱网优化） |
| WebRTC | 8889 | Web浏览器播放 |

## 使用示例

### RTMP推流（默认）

```bash
# 启动应用
adb shell am start \
    -n ai.fd.thinklet.app.squid.run/.MainActivity \
    -a android.intent.action.MAIN

# 推流URL自动生成为：
# rtmp://192.168.1.100:1935/thinklet.squid.run/{deviceId}
```

### SRT推流（弱网环境）

```kotlin
// 在代码中切换到SRT
viewModel.updateStreamProtocol("srt")

// 推流URL自动生成为：
// srt://192.168.1.100:8890?streamid=publish:thinklet.squid.run/{deviceId}
// 注意：MediaMTX要求streamid格式为 action:pathname，action为publish或read
```

## Web端播放

**无需任何改动！** Web端继续使用WebRTC/WHEP播放，完全透明。

MediaMTX会自动将RTMP或SRT输入转换为WebRTC输出。

```
Android (RTMP/SRT) → MediaMTX → WebRTC → Web浏览器
```

## 故障排查

### 问题1：SRT推流失败

**检查项：**
1. MediaMTX是否启动？
2. 端口8890是否被占用？
3. 防火墙是否允许8890端口？

```bash
# 检查MediaMTX状态
netstat -an | findstr 8890

# 测试SRT连接
ffmpeg -re -i test.mp4 -c copy -f mpegts "srt://192.168.1.100:8890?streamid=publish:test"
```

### 问题2：切换协议后无法推流

**解决方法：**
1. 停止当前推流
2. 切换协议
3. 重新开始推流

### 问题3：Web端无法播放

**检查项：**
1. MediaMTX的WebRTC端口(8889)是否正常
2. 浏览器控制台是否有错误
3. 尝试刷新页面

## 性能建议

### 网络环境选择

| 网络质量 | 推荐协议 |
|----------|----------|
| 优秀 | RTMP |
| 良好 | RTMP/SRT |
| 一般 | SRT |
| 较差 | SRT |
| 很差 | SRT |

### 资源占用

- **RTMP** - CPU占用较低，适合稳定网络
- **SRT** - CPU占用略高（+5-10%），但弱网下更稳定

## 总结

✅ **向后兼容** - RTMP继续工作，无需修改现有配置  
✅ **弱网优化** - SRT自动处理丢包和重传  
✅ **零前端改动** - Web端完全透明  
✅ **灵活切换** - 根据网络环境选择最佳协议  

推荐在工业弱网环境下使用SRT协议以获得最佳稳定性。
