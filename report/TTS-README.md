# TTS (Text-to-Speech) 实现说明

## ⚠️ 重要：开机延迟特殊处理

**Android 8.1 + Pico TTS 存在系统级bug**：如果在开机后立即初始化TTS，会导致 `libttspico.so` native代码崩溃（SIGSEGV），影响整个系统的TTS服务。

### 解决方案

**智能检测开机场景，延迟初始化TTS**

```kotlin
class TTSManager(context: Context) {
    init {
        // 检测系统启动时间
        val uptimeSeconds = SystemClock.elapsedRealtime() / 1000
        
        if (uptimeSeconds < 90) {
            // ⚠️ 开机场景：延迟3秒初始化（异步，不阻塞主线程）
            handler.postDelayed({ initializeTTS() }, 3000)
        } else {
            // ✅ 正常场景：立即初始化
            initializeTTS()
        }
    }
}
```

### 关键参数

| 参数 | 值 | 说明 |
|-----|---|------|
| **uptime阈值** | 90秒 | 系统运行时间 < 90秒视为开机场景 |
| **延迟时间** | 3秒 | 开机场景下延迟3秒初始化TTS |
| **正常启动** | 0秒 | 非开机场景立即初始化，无延迟 |

### 为什么需要这个延迟？

1. **系统启动顺序**：开机后各系统服务按顺序启动
2. **TTS服务依赖**：Pico TTS需要文件系统、音频服务等资源完全就绪
3. **竞态条件**：App开机自启动时（~20秒），TTS服务可能尚未完全初始化
4. **崩溃后果**：一旦崩溃，整个系统TTS不可用，必须重启系统

## 崩溃现象

### 错误日志
```
Fatal signal 11 (SIGSEGV), code 1, fault addr 0x52f00c3c in tid 5611 (SynthThread)
pid: 5597, tid: 5611, name: SynthThread  >>> com.svox.pico <<<
backtrace:
    #00 pc 00024b04  /system/lib/libttspico.so (picobase_get_next_utf8char+9)
```

### 触发条件
- ✅ **开机自启动**场景
- ✅ **立即初始化TTS**
- ✅ **Pico TTS服务未就绪**
- ❌ **结果：系统TTS崩溃**

### 影响范围
- ❌ **系统级崩溃** - 不仅app无法使用TTS，整个系统的TTS都崩溃
- ❌ **持续到重启** - 无法通过代码恢复，只能重启系统
- ❌ **无法预防（除了延迟）** - 这是Pico TTS底层bug，应用层无法修复

## 设计架构

### 1. 智能场景检测
```kotlin
private fun isRecentBoot(): Boolean {
    val uptimeSeconds = android.os.SystemClock.elapsedRealtime() / 1000
    Log.d(TAG, "System uptime: ${uptimeSeconds}s")
    return uptimeSeconds < 90  // 90秒内视为开机场景
}
```

### 2. 异步初始化（不阻塞主线程）
```kotlin
if (isBootScenario) {
    // 使用Handler.postDelayed，异步延迟
    handler.postDelayed({
        initializeTTS()
    }, 3000)
} else {
    initializeTTS()
}
```

### 3. StateFlow状态管理
```kotlin
private val _ttsReady = MutableStateFlow(false)
val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

// Activity中异步等待
lifecycleScope.launch {
    ttsManager.ttsReady.first { it }  // 协程挂起，不阻塞线程
    ttsManager.speak("ready")
}
```

### 4. 优雅降级
```kotlin
fun speak(message: String) {
    if (!_ttsReady.value) {
        Log.w(TAG, "⚠️ TTS not ready, skipping: $message")
        return  // 立即返回，不阻塞
    }
    tts?.speak(message, ...)
}
```

## 日志示例

### 开机场景
```
D/TTSManager: System uptime: 25s
I/TTSManager: ⏱️ Boot scenario detected (uptime < 90s), delaying TTS initialization by 3 seconds...
... 3 seconds later ...
I/TTSManager: ✅ Boot delay complete, initializing TTS...
D/TTSManager: Initializing TextToSpeech...
I/TTSManager: ✅ TTS initialized successfully
```

### 正常启动
```
D/TTSManager: System uptime: 3600s
D/TTSManager: Normal startup, initializing TTS immediately
D/TTSManager: Initializing TextToSpeech...
I/TTSManager: ✅ TTS initialized successfully
```

### 如果崩溃（不应该发生）
```
E/libc: Fatal signal 11 (SIGSEGV) in libttspico.so
I/ActivityManager: Process com.svox.pico has died
W/TTSManager: ⚠️ TTS not ready, skipping speech: application prepared
```

## 使用方式

### 1. Activity中等待TTS就绪
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 异步等待TTS初始化完成
        lifecycleScope.launch {
            viewModel.ttsManager.ttsReady.first { it }
            viewModel.ttsManager.speakApplicationPrepared()
        }
    }
}
```

### 2. 直接调用（自动跳过未就绪情况）
```kotlin
// TTS未就绪时自动跳过，不会阻塞或崩溃
ttsManager.speakRecordingStarted()
```

### 3. 便捷方法
```kotlin
ttsManager.speakPowerDown()
ttsManager.speakRecordingStarted()
ttsManager.speakRecordingFinished()
ttsManager.speakApplicationPrepared()
ttsManager.speakBatteryAndNetworkStatus()
```

## 性能对比

| 场景 | 改进前 | 改进后 |
|------|-------|-------|
| **开机启动** |
| 主线程阻塞 | 1秒 | 0秒 ✅ |
| TTS初始化 | 2秒延迟 | 3秒延迟 |
| 崩溃风险 | 高 ❌ | 无 ✅ |
| **正常启动** |
| 主线程阻塞 | 1秒 | 0秒 ✅ |
| TTS初始化 | 2秒延迟 | 0秒 ✅ |
| 总提升 | - | **快2秒** ✅ |

## 测试验证

### 开机测试
```bash
# 1. 重启设备
adb reboot

# 2. 观察日志
adb logcat | grep -E "TTSManager|uptime"

# 3. 预期结果
# - 检测到开机场景 (uptime < 90s)
# - 延迟3秒后初始化
# - TTS成功初始化
# - 无Pico TTS崩溃
```

### 正常启动测试
```bash
# 1. 杀掉app
adb shell am force-stop ai.fd.thinklet.app.squid.run

# 2. 重启app
adb shell am start -n ai.fd.thinklet.app.squid.run/.MainActivity

# 3. 预期结果
# - 检测到正常场景 (uptime > 90s)
# - 立即初始化TTS
# - 无延迟，快速启动
```

## 参数调优

### 如果仍然崩溃
```kotlin
// 增加延迟时间
handler.postDelayed({ initializeTTS() }, 5000)  // 5秒

// 或增加uptime阈值
return uptimeSeconds < 120  // 120秒
```

### 如果需要更快启动（风险自负）
```kotlin
// 减少延迟时间
handler.postDelayed({ initializeTTS() }, 2000)  // 2秒

// 或减少uptime阈值
return uptimeSeconds < 60  // 60秒
```

## 关键代码位置

| 文件 | 关键内容 |
|-----|---------|
| `TTSManager.kt` | TTS管理器，包含开机检测和延迟初始化逻辑 |
| `MainActivity.kt` | 异步等待TTS就绪后播报 |
| `MainViewModel.kt` | 录制状态变化时调用TTS播报 |

## 常见问题

### Q: 为什么不能用Thread.sleep()？
A: `Thread.sleep()` 会阻塞主线程，导致应用启动卡顿。我们使用 `Handler.postDelayed()` 异步延迟，不影响UI响应。

### Q: 为什么不尝试恢复崩溃？
A: Pico TTS底层崩溃后，重试也无效，只能重启系统。因此我们采用**预防策略**而非恢复策略。

### Q: 90秒阈值是否合适？
A: 经过测试，90秒可以覆盖绝大多数开机场景。如果设备启动特别慢，可以适当增加到120秒。

### Q: 3秒延迟是否足够？
A: 经过验证，3秒延迟在大多数设备上足够让Pico TTS服务稳定。如果仍有问题，可增加到5秒。

### Q: 正常启动为什么没有延迟？
A: 通过检测系统uptime，我们只在开机场景延迟。正常启动（uptime > 90s）时TTS服务已稳定，可以立即初始化。

## 总结

### ✅ 解决方案核心
**智能检测开机 + 异步延迟 + 优雅降级**

### ✅ 关键特性
- 智能场景检测（uptime < 90s）
- 异步延迟初始化（不阻塞主线程）
- StateFlow状态管理（现代异步设计）
- 优雅降级（TTS未就绪时跳过）

### ✅ 性能提升
- 开机场景：无崩溃 ✅
- 正常启动：快2秒 ✅
- 用户体验：流畅启动 ✅

### ⚠️ 核心原则
**在开机场景下，必须延迟3秒后再初始化TTS，否则会导致系统级Pico TTS服务崩溃**

---

**文档版本**: 1.0  
**最后更新**: 2025年10月17日  
**验证状态**: ✅ 已在实际设备测试通过

