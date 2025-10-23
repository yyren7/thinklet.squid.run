# Project Structure

## Directory Organization

### Root Level
```
thinklet.squid.run/
├── app/                    # Android application module
├── streaming/              # Node.js streaming server
├── gradle/                 # Gradle configuration and dependencies
├── report/                 # Development reports and documentation
├── build.gradle.kts        # Root Gradle build configuration
├── settings.gradle.kts     # Gradle project settings
└── README.md              # Main project documentation
```

### Android Application (`app/`)
```
app/
├── src/
│   ├── main/
│   │   ├── java/ai/fd/thinklet/app/squid/run/
│   │   │   ├── MainActivity.kt              # Main activity and lifecycle
│   │   │   ├── MainViewModel.kt             # Business logic and state
│   │   │   ├── StatusReportingManager.kt    # Status reporting to server
│   │   │   ├── StreamingManager.kt          # RTMP streaming coordination
│   │   │   ├── CameraManager.kt             # Camera resource management
│   │   │   ├── AudioManager.kt              # Audio capture and processing
│   │   │   ├── ButtonManager.kt             # Hardware button handling
│   │   │   └── ...
│   │   ├── res/                             # Android resources
│   │   └── AndroidManifest.xml              # App manifest
│   ├── androidTest/                         # Instrumented tests
│   └── test/                                # Unit tests
├── build.gradle.kts                         # App module build config
└── proguard-rules.pro                       # ProGuard configuration
```

### Streaming Server (`streaming/`)
```
streaming/
├── src/
│   ├── simple-http-server.js        # Express HTTP server
│   ├── file-transfer-service.js     # WebSocket file transfer
│   └── video/                       # Video processing utilities
├── public/
│   ├── index.html                   # Web viewer interface
│   ├── main.js                      # Client-side JavaScript
│   ├── style.css                    # Styling
│   ├── i18n.js                      # Internationalization
│   └── flv.min.js                   # FLV video player
├── config/
│   ├── devices.json                 # Device configurations
│   ├── docker-compose.yml           # Docker setup
│   └── srs.conf                     # SRS server config
├── docs/                            # Server documentation
└── package.json                     # Node.js dependencies
```

### Gradle Configuration (`gradle/`)
```
gradle/
├── libs.versions.toml               # Android dependency versions
├── thinklet.versions.toml           # THINKLET SDK versions
└── wrapper/                         # Gradle wrapper files
```

## Core Components and Relationships

### Android Application Architecture

**MainActivity** (Entry Point)
- Manages app lifecycle and permissions
- Initializes ViewBinding and ViewModel
- Handles Intent extras for configuration
- Coordinates with ButtonManager for hardware input

**MainViewModel** (Business Logic)
- Manages streaming state (idle, starting, streaming, stopping)
- Coordinates StreamingManager, CameraManager, AudioManager
- Handles mute state and error conditions
- Provides LiveData for UI observation

**StreamingManager** (RTMP Coordination)
- Wraps RootEncoder library for RTMP streaming
- Manages video encoder and audio encoder configuration
- Handles connection to RTMP server
- Reports streaming status and errors

**CameraManager** (Camera Resources)
- Manages CameraX lifecycle
- Configures video capture with specified resolution
- Provides Surface for video encoding
- Handles camera permissions and availability

**AudioManager** (Audio Capture)
- Supports three microphone modes (Android, THINKLET 5-mic, 6-mic)
- Configures audio capture parameters (sample rate, bitrate, channels)
- Manages echo cancellation
- Provides audio data to StreamingManager

**ButtonManager** (Hardware Input)
- Listens for THINKLET button events
- Triggers streaming start/stop, mute toggle
- Provides vibration feedback
- Handles power button for app exit

**StatusReportingManager** (Server Communication)
- Reports device status to streaming server via HTTP
- Sends periodic heartbeats
- Communicates streaming state changes
- Uses OkHttp for network requests

### Streaming Server Architecture

**simple-http-server.js** (Main Server)
- Express.js HTTP server
- Serves web viewer interface
- Provides REST API for device status
- Manages WebSocket connections

**file-transfer-service.js** (File Transfer)
- WebSocket-based file transfer protocol
- Handles file uploads from THINKLET devices
- Manages transfer progress and errors
- Supports multiple concurrent transfers

**Web Viewer** (public/)
- HTML5 video player using flv.js
- Real-time stream viewing interface
- Device connection management UI
- Multi-language support (i18n.js)

## Architectural Patterns

### Android Patterns
- **MVVM Architecture**: MainActivity (View) + MainViewModel (ViewModel) + Manager classes (Model)
- **Lifecycle Awareness**: ViewModels and lifecycle-aware components
- **Dependency Injection**: Manual DI via constructor parameters
- **Observer Pattern**: LiveData for state observation
- **Manager Pattern**: Specialized managers for camera, audio, streaming, buttons

### Server Patterns
- **Express.js MVC**: Routes, controllers, and services separation
- **WebSocket Protocol**: Real-time bidirectional communication
- **RESTful API**: HTTP endpoints for device status and control
- **Event-Driven**: Node.js event loop for async operations

### Integration Patterns
- **Intent Extras**: Configuration passed via Android Intent
- **HTTP Polling**: Device status reporting to server
- **WebSocket Streaming**: File transfer between device and server
- **RTMP Protocol**: Standard streaming protocol for video/audio
