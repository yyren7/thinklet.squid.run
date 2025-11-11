# TTS Async Initialization Test Guide

## Problem Statement

Previously, TTS initialization took approximately 10 seconds and blocked the main thread during:
- Model file copying from assets to cache
- TTS model loading
- AudioTrack initialization

This caused any voice announcement triggered during this period to freeze the application for 10+ seconds.

## Solution

The TTS initialization has been refactored to run asynchronously in a background thread:
- Constructor returns immediately
- Message queue is started immediately
- TTS initialization runs in background
- Any `speak()` calls before initialization are queued and played automatically once ready

## Key Changes

### 1. Async Initialization
```kotlin
init {
    // Start queue processor immediately
    startQueueProcessor()
    
    // Initialize TTS asynchronously in background thread
    Log.i(TAG, "🚀 Starting async TTS initialization...")
    Thread {
        try {
            initializeTTS()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize TTS in background", e)
            _ttsReady.value = false
        }
    }.start()
}
```

### 2. Non-blocking speak() method
```kotlin
fun speak(message: String, queueMode: Int = QUEUE_FLUSH) {
    // This method returns immediately, even if TTS is not ready
    // Messages are queued and played automatically once TTS is initialized
}
```

### 3. Performance Monitoring
Added detailed timing logs to identify bottlenecks:
- Step 1: File copying duration
- Step 2: Model loading duration  
- Step 3: AudioTrack initialization duration
- Total initialization time

## Test Scenarios

### Scenario 1: Early Voice Announcement
**Test Steps:**
1. Clean install the app (to trigger first-time file copying)
2. Launch the app
3. Immediately trigger a geofence enter/exit event
4. Observe behavior

**Expected Result:**
- App should remain responsive
- Voice announcement should be queued
- Once TTS is ready, queued message plays automatically
- Log should show: `⏳ TTS message queued (waiting for initialization): ...`

**Example Log Output:**
```
🚀 Starting async TTS initialization...
📋 Queue processor started
⏳ TTS message queued (waiting for initialization): 已进入办公区域 (queue size: 1)
📦 Step 1/3: Copying model files to cache...
✅ Model files ready (took 3500ms)
⚙️ Step 2/3: Loading TTS model...
✅ TTS model loaded (took 6200ms)
🔊 Step 3/3: Initializing audio track...
✅ Audio track initialized (took 150ms)
✅ 🎉 Sherpa-ONNX TTS fully initialized in 9850ms (sample rate: 22050 Hz)
📊 Breakdown: Copy=3500ms, Model=6200ms, Audio=150ms
📢 TTS ready! Processing 1 queued message(s)...
⏳ Waiting for TTS initialization before playing: 已进入办公区域
🔊 Generating speech: 已进入办公区域
🎵 Playing audio (12345 samples)
✅ Speech completed: 已进入办公区域
```

### Scenario 2: Multiple Early Announcements
**Test Steps:**
1. Clean install the app
2. Launch the app
3. Trigger 3 voice announcements quickly

**Expected Result:**
- All 3 messages should be queued
- App remains responsive
- Once TTS ready, messages play sequentially
- Log shows queue size growing then being processed

### Scenario 3: Normal Operation (TTS Already Ready)
**Test Steps:**
1. App already running with TTS initialized
2. Trigger voice announcement

**Expected Result:**
- Immediate playback (no queuing delay)
- Log shows: `📝 TTS message queued: ... (queue size: 1)`

### Scenario 4: Queue Overflow Protection
**Test Steps:**
1. Clean install
2. Trigger 15+ voice announcements rapidly during initialization

**Expected Result:**
- Queue size capped at 10 messages
- Oldest messages dropped
- Log shows: `⚠️ TTS queue is full (10/10), dropping oldest message`

## Verification Points

### 1. Check Constructor Time
Add timing around TTS manager creation:
```kotlin
val startTime = System.currentTimeMillis()
val ttsManager = SherpaOnnxTTSManager(applicationContext)
val duration = System.currentTimeMillis() - startTime
Log.i(TAG, "TTS manager created in ${duration}ms") // Should be < 50ms
```

### 2. Check speak() Call Time
```kotlin
val startTime = System.currentTimeMillis()
ttsManager.speak("test message")
val duration = System.currentTimeMillis() - startTime
Log.i(TAG, "speak() returned in ${duration}ms") // Should be < 5ms
```

### 3. Monitor Queue Size
```kotlin
Log.i(TAG, "TTS ready: ${ttsManager.isReady()}")
Log.i(TAG, "Queue size: ${ttsManager.getQueueSize()}")
```

## Performance Baseline

### Before Fix
- Constructor: ~10 seconds (blocking)
- speak() during init: ~10+ seconds (blocking)
- User experience: Frozen app

### After Fix
- Constructor: < 50ms (non-blocking)
- speak() during init: < 5ms (queued)
- User experience: Smooth and responsive

## Troubleshooting

### Issue: Messages not playing after initialization
**Check:**
- Queue processor started? Look for: `📋 Queue processor started`
- TTS initialized successfully? Look for: `✅ 🎉 Sherpa-ONNX TTS fully initialized`

### Issue: Still experiencing delays
**Check:**
- Are you calling from UI thread? Should be safe now
- Is lazy initialization being triggered at right time?
- Check log for actual initialization timing

### Issue: Voice quality problems
**Check:**
- Same as before, not related to async initialization
- Model files copied correctly?
- AudioTrack sample rate matches model?

## Additional APIs

### Check if TTS is Ready
```kotlin
if (ttsManager.isReady()) {
    // TTS ready, immediate playback
} else {
    // TTS initializing, will be queued
}
```

### Check Queue Size
```kotlin
val queueSize = ttsManager.getQueueSize()
Log.i(TAG, "Messages waiting: $queueSize")
```

### Monitor Ready State
```kotlin
lifecycleScope.launch {
    ttsManager.ttsReady.collectLatest { isReady ->
        Log.i(TAG, "TTS ready state changed: $isReady")
        if (isReady) {
            // Safe to make announcements now
        }
    }
}
```

## Notes

1. **Thread Safety**: The queue mechanism is thread-safe using `LinkedBlockingQueue`
2. **Memory Usage**: Queue is capped at 10 messages to prevent memory issues
3. **Graceful Degradation**: If initialization fails, messages are still queued but won't play (error logged)
4. **Performance**: First launch slower due to file copying, subsequent launches faster (files cached)

## Related Files

- `SherpaOnnxTTSManager.kt` - Main implementation
- `MainActivity.kt` - Usage example (geofence events)
- `SquidRunApplication.kt` - TTS manager initialization

## Summary

The async initialization ensures:
- ✅ Non-blocking app startup
- ✅ Responsive UI during TTS initialization
- ✅ Automatic message queuing and playback
- ✅ Detailed performance monitoring
- ✅ Graceful handling of early voice announcements


















