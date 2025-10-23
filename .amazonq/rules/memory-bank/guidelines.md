# Development Guidelines

## Code Quality Standards

### Kotlin Code Formatting
- **Package Structure**: Use reverse domain notation (ai.fd.thinklet.app.squid.run)
- **Imports**: Group imports logically - Android SDK, third-party libraries, then internal packages
- **Line Length**: Keep lines reasonable, break long parameter lists across multiple lines
- **Indentation**: Use 4 spaces for indentation
- **Braces**: Opening brace on same line, closing brace on new line

### JavaScript Code Formatting
- **Semicolons**: Use semicolons consistently (present in file-transfer-service.js)
- **String Quotes**: Use single quotes for strings in JavaScript
- **Indentation**: Use 4 spaces for indentation
- **Comments**: Use JSDoc-style comments for class and function documentation

### Naming Conventions

#### Kotlin
- **Classes**: PascalCase (MainViewModel, StatusReportingManager, FileTransferServer)
- **Functions**: camelCase (maybeStartStreaming, updateServerIp, startRecording)
- **Properties**: camelCase (isStreaming, deviceId, streamUrl)
- **Private Properties**: camelCase with underscore prefix for backing fields (_isStreaming, _serverIp)
- **Constants**: UPPER_SNAKE_CASE (DEFAULT_LONG_SIDE, MAX_RECONNECT_DELAY, TAG)
- **Enum Values**: UPPER_SNAKE_CASE (LANDSCAPE, PORTRAIT, CONNECTING, CONNECTED)

#### JavaScript
- **Classes**: PascalCase (FileTransferService)
- **Functions**: camelCase (scanDeviceFiles, addDownloadTask, calculateMD5)
- **Variables**: camelCase (deviceGrid, flvPlayers, fileTransfers)
- **Constants**: UPPER_SNAKE_CASE (STREAM_BASE_URL, MAX_BUFFER_DELAY, BROADCAST_INTERVAL_MS)

### Documentation Standards
- **File Headers**: Include purpose and feature descriptions for complex files
- **Function Comments**: Document complex logic, especially async operations and state management
- **Inline Comments**: Use for clarifying non-obvious code, especially in critical sections
- **Emoji Logging**: Use emojis in log messages for visual categorization (✅ success, ❌ error, 🔄 retry, 📁 file operations)

## Architectural Patterns

### Android MVVM Architecture
- **ViewModel**: Contains business logic, manages state with StateFlow/MutableStateFlow
- **Activity**: Handles UI, lifecycle, and user interactions
- **Manager Classes**: Specialized components (CameraManager, AudioManager, StreamingManager)
- **Separation of Concerns**: ViewModels never reference Activities directly

### State Management Patterns

#### Kotlin Coroutines and Flow
```kotlin
// StateFlow for observable state
private val _isStreaming: MutableStateFlow<Boolean> = MutableStateFlow(false)
val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

// Derived state using map and stateIn
val streamUrl: StateFlow<String> = _serverIp.map { ip ->
    "rtmp://$ip:1935/thinklet.squid.run"
}.stateIn(viewModelScope, SharingStarted.Eagerly, "")

// Combining multiple flows
val isReadyForStreaming: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
    streamUrl,
    streamKey
) { url, key ->
    !url.isNullOrBlank() && !key.isNullOrBlank() && isAllPermissionGranted()
}.stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = false)
```

#### Lifecycle-Aware Collection
```kotlin
lifecycleScope.launch {
    viewModel.isStreaming
        .flowWithLifecycle(lifecycle)
        .collect { isStreaming ->
            binding.streaming.text = isStreaming.toString()
            statusReportingManager.updateStreamingStatus(isStreaming)
        }
}
```

### Concurrency Control Patterns

#### Volatile Flags for Thread Safety
```kotlin
@Volatile
private var isPreparing: Boolean = false

@Volatile
private var isRecordingOperationInProgress: Boolean = false
```

#### Operation Locking Pattern
```kotlin
fun startRecording(onResult: ((Boolean) -> Unit)? = null) {
    // Check if another operation is in progress
    if (isRecordingOperationInProgress) {
        Log.w(TAG, "⚠️ Recording operation already in progress, ignoring request")
        onResult?.invoke(false)
        return
    }
    
    // Set operation lock
    isRecordingOperationInProgress = true
    
    // Perform operation
    ensureCameraReady {
        val result = startRecordingInternal()
        // Release lock after completion
        isRecordingOperationInProgress = false
        onResult?.invoke(result)
    }
}
```

### Resource Management Patterns

#### Lazy Initialization
```kotlin
private val ledController = LedController(application)
private val angle: Angle by lazy(LazyThreadSafetyMode.NONE, ::Angle)

val ttsManager: TTSManager by lazy {
    (application as SquidRunApplication).ttsManager
}
```

#### Lifecycle-Bound Resources
```kotlin
// Release resources when no longer needed
private fun checkAndReleaseCamera() {
    val shouldRelease = !_isStreaming.value && !_isRecording.value && !_isPreviewActive.value
    if (shouldRelease) {
        Log.i(TAG, "All features are off, releasing camera resources to save power")
        releaseCamera()
    }
}

// Background thread for heavy cleanup
private fun releaseCamera() {
    val localStream = stream ?: return
    stream = null
    _isPrepared.value = false
    
    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (localStream.isRecording) {
                localStream.stopRecord()
                delay(200)  // Wait for file system to release locks
            }
            localStream.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release camera resources", e)
        }
    }
}
```

### Error Handling Patterns

#### Defensive Programming
```kotlin
// Double-check state before operations
if (_isRecording.value) {
    Log.w(TAG, "⚠️ Recording is already in progress (double-check)")
    return true
}

// Null safety with early returns
val streamSnapshot = stream ?: run {
    Log.w(TAG, "⚠️ Cannot stop recording: stream is null")
    _isRecording.value = false
    onResult?.invoke(false)
    return
}
```

#### Retry Logic with Exponential Backoff
```kotlin
// Network retry pattern
if (task.retryCount < 3) {
    task.retryCount++
    console.log(`🔄 Retrying download (${task.retryCount}/3): ${fileInfo.name}`)
    task.status = 'retrying'
    
    const delay = isNetworkError ? 10000 * task.retryCount : 5000 * task.retryCount
    setTimeout(() => {
        this.downloadFile(task)
    }, delay)
} else {
    // Pause task after max retries
    task.status = 'paused'
    this.pausedTasks.set(task.id, task)
}
```

#### Graceful Degradation
```kotlin
try {
    fileTransferServer.startServer()
    fileServerEnabled = true
} catch (e: Exception) {
    Log.e(TAG, "❌ Failed to start file transfer server", e)
    fileServerEnabled = false
    // Continue without file transfer capability
}
```

## Common Code Idioms

### Kotlin Idioms

#### Safe Calls and Elvis Operator
```kotlin
val deviceName = device.deviceName ?: device.id
val currentStreamKey = streamKey.value ?: return false
```

#### Scope Functions
```kotlin
// let for null checks
device.status?.let { status ->
    binding.battery.text = "${status.batteryLevel}%"
}

// apply for object configuration
val request = Request.Builder().url(wsUrl).build()

// also for side effects
stream = localStream.also {
    _isPrepared.value = true
}
```

#### When Expressions
```kotlin
when (status) {
    RecordController.Status.STARTED -> {
        _isRecording.value = true
        ledController.startLedBlinking()
    }
    RecordController.Status.STOPPED -> {
        _isRecording.value = false
        ledController.stopLedBlinking()
    }
    else -> { /* Handle other cases */ }
}
```

### JavaScript Idioms

#### Async/Await Pattern
```javascript
async function fetchServerIp() {
    try {
        const response = await fetch('/server-ip');
        const data = await response.json();
        serverIpElement.textContent = data.ip;
    } catch (error) {
        console.error('Failed to fetch server IP:', error);
        serverIpElement.textContent = 'Error';
    }
}
```

#### Promise Handling
```javascript
await new Promise((resolve, reject) => {
    writer.on('finish', resolve);
    writer.on('error', reject);
    response.data.on('error', reject);
});
```

#### Map and Filter for Collections
```javascript
const deviceTransfers = Object.values(fileTransfers).filter(t => t.deviceId === deviceId);
const tasks = deviceTransfers.map(transfer => ({
    id: transfer.id,
    fileName: transfer.fileInfo.name,
    status: transfer.status
}));
```

## Frequently Used Annotations

### Kotlin Annotations
- `@MainThread` - Indicates function must be called on main thread
- `@Volatile` - Ensures visibility across threads
- `@SuppressLint("HardwareIds")` - Suppress lint warnings for device ID access
- `@Deprecated("use getDeviceStatus()")` - Mark deprecated methods

### Android Lifecycle Annotations
- `override fun onCreate(savedInstanceState: Bundle?)` - Activity creation
- `override fun onResume()` - Activity becomes visible
- `override fun onPause()` - Activity loses focus
- `override fun onDestroy()` - Activity cleanup

## Internal API Usage Patterns

### THINKLET SDK Usage
```kotlin
// Multi-channel audio recording
import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord

val microphoneSource = ThinkletMicrophoneSource(
    context, 
    MultiChannelAudioRecord.Channel.CHANNEL_FIVE
)

// Device angle/orientation
import ai.fd.thinklet.sdk.maintenance.camera.Angle

private val angle: Angle by lazy(LazyThreadSafetyMode.NONE, ::Angle)
angle.setLandscape()
val rotation = angle.current()

// LED control
import ai.fd.thinklet.sdk.led.LedController

ledController.startLedBlinking()
ledController.stopLedBlinking()

// Power control
import ai.fd.thinklet.sdk.maintenance.power.PowerController

PowerController().shutdown(this, wait = 1000)
```

### RootEncoder (RTMP Streaming)
```kotlin
import com.pedro.library.generic.GenericStream
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.audio.AudioSource

val stream = GenericStream(
    application,
    connectionChecker,
    Camera2Source(application),
    audioSource
)

// Prepare video and audio
stream.prepareVideo(width, height, fps, bitrate, rotation)
stream.prepareAudio(sampleRate, isStereo, bitrate, echoCanceler)

// Start streaming
stream.startStream("rtmp://server:1935/app/key")

// Recording
stream.startRecord(path, null, recordListener)
stream.stopRecord()
```

### Camera Integration
```kotlin
// Camera2Source is from RootEncoder, not androidx.camera
import com.pedro.encoder.input.sources.video.Camera2Source

// RootEncoder's Camera2Source wraps CameraX internally
val cameraSource = Camera2Source(application)
val stream = GenericStream(application, connectionChecker, cameraSource, audioSource)
// Camera lifecycle is managed by RootEncoder's GenericStream
```

### WebSocket Communication (OkHttp)
```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

val client = OkHttpClient.Builder()
    .pingInterval(10, TimeUnit.SECONDS)
    .build()

val request = Request.Builder().url(wsUrl).build()
client.newWebSocket(request, object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        // Connection established
    }
    override fun onMessage(webSocket: WebSocket, text: String) {
        // Handle incoming message
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // Handle connection failure
    }
})
```

### Node.js Express Server
```javascript
const express = require('express');
const app = express();

app.use(express.json());
app.use(express.static('public'));

app.get('/devices', (req, res) => {
    res.json(devices);
});

app.post('/start-stream', (req, res) => {
    const { id } = req.body;
    // Handle stream start
    res.json({ success: true });
});

app.listen(8000, () => {
    console.log('Server running on port 8000');
});
```

### WebSocket Broadcasting (ws library)
```javascript
const WebSocket = require('ws');
const wss = new WebSocket.Server({ port: 8000 });

function broadcast(data) {
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(JSON.stringify(data));
        }
    });
}

wss.on('connection', (ws) => {
    ws.on('message', (message) => {
        const data = JSON.parse(message);
        // Handle message
    });
});
```

## Best Practices Summary

### Android Development
1. **Use StateFlow for observable state** - Prefer StateFlow over LiveData for new code
2. **Lifecycle awareness** - Always use flowWithLifecycle for collecting flows in Activities
3. **Background operations** - Use Dispatchers.IO for file/network operations
4. **Resource cleanup** - Release camera/audio resources when not in use
5. **Concurrency control** - Use @Volatile flags and operation locks for thread safety
6. **Defensive programming** - Double-check state before critical operations
7. **Logging with context** - Use emoji prefixes and descriptive messages

### JavaScript Development
1. **Async/await** - Prefer async/await over raw promises
2. **Error handling** - Always wrap async operations in try-catch
3. **State management** - Use Maps for keyed collections, Arrays for ordered lists
4. **Event-driven** - Use WebSocket for real-time updates
5. **Throttling** - Limit update frequency for performance (e.g., progress updates)
6. **Graceful degradation** - Handle offline/error states gracefully

### Cross-Platform Communication
1. **WebSocket protocol** - Use JSON messages with type and payload structure
2. **Status reporting** - Send periodic heartbeats with device state
3. **Command pattern** - Use command objects for remote control
4. **Retry logic** - Implement exponential backoff for network operations
5. **State synchronization** - Keep client and server state in sync
