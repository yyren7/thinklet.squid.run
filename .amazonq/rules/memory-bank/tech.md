# Technology Stack

## Programming Languages

### Android Application
- **Kotlin 2.1.0**: Primary language for Android app development
- **Java 17**: Target JVM version for Kotlin compilation

### Streaming Server
- **JavaScript (Node.js)**: Server-side runtime for streaming server
- **HTML5/CSS3**: Web viewer interface

## Build Systems and Tools

### Android Build
- **Gradle 8.7.3**: Build automation tool
- **Android Gradle Plugin 8.7.3**: Android-specific build configuration
- **Kotlin Gradle Plugin 2.1.0**: Kotlin compilation support

### Node.js Build
- **npm**: Package manager for Node.js dependencies
- **Node.js**: Runtime environment (version not specified, recommend LTS)

## Key Dependencies

### Android Application

#### Core Android Libraries
- `androidx.core:core-ktx:1.15.0` - Kotlin extensions for Android
- `androidx.appcompat:appcompat:1.7.0` - Backward compatibility
- `androidx.activity:activity:1.9.3` - Activity components
- `androidx.fragment:fragment-ktx:1.8.5` - Fragment management
- `androidx.constraintlayout:constraintlayout:2.2.0` - Layout system
- `com.google.android.material:material:1.12.0` - Material Design components

#### Camera and Video
- `androidx.camera:camera-camera2:1.4.0` - CameraX Camera2 implementation (底层依赖)
- `androidx.camera:camera-video:1.4.0` - Video capture capabilities
- `androidx.camera:camera-lifecycle:1.4.0` - Lifecycle integration
- `androidx.camera:camera-extensions:1.4.0` - Camera extensions
- Note: RootEncoder's Camera2Source class wraps CameraX for streaming

#### Streaming
- `com.github.pedroSG94.RootEncoder:library:2.6.4` - RTMP streaming library
- `com.github.pedroSG94.RootEncoder:common:2.6.4` - Common utilities

#### THINKLET SDK
- `thinklet-sdk-maintenance` - Device maintenance utilities
- `thinklet-sdk-audio` - Multi-microphone audio capture
- `thinklet-sdk-led` - LED control

#### Networking and Utilities
- `com.squareup.okhttp3:okhttp:4.12.0` - HTTP client
- `com.google.code.gson:gson:2.10.1` - JSON serialization
- `org.nanohttpd:nanohttpd:2.3.1` - Embedded HTTP server

#### Testing
- `junit:junit:4.13.2` - Unit testing framework
- `androidx.test.ext:junit:1.2.1` - Android JUnit extensions
- `androidx.test.espresso:espresso-core:3.6.1` - UI testing

### Streaming Server

#### Core Dependencies
- `express:^4.18.2` - Web application framework
- `ws:^8.13.0` - WebSocket implementation
- `axios:^1.4.0` - HTTP client
- `default-gateway:^7.2.2` - Network gateway detection

#### Client-Side Libraries
- `flv.js` - FLV video player for web browsers

## Development Commands

### Android Application

#### Build Commands
```bash
# Build release APK
./gradlew :app:assembleRelease

# Build debug APK
./gradlew :app:assembleDebug

# Clean build
./gradlew clean

# Run tests
./gradlew test
```

#### Installation and Launch
```bash
# Install APK to device
adb install app/build/outputs/apk/release/app-release.apk

# Launch with parameters
adb shell am start \
    -n ai.fd.thinklet.app.squid.run/.MainActivity \
    -a android.intent.action.MAIN \
    -e streamUrl "rtmp://example.com/live" \
    -e streamKey "stream_key" \
    --ei longSide 720 \
    --ei shortSide 480
```

### Streaming Server

#### Server Commands
```bash
# Install dependencies
npm install

# Start server
npm start

# Alternative start
node src/simple-http-server.js
```

#### Docker Commands
```bash
# Start with Docker Compose
docker-compose -f config/docker-compose.yml up

# Stop containers
docker-compose -f config/docker-compose.yml down
```

## Configuration Files

### Android Configuration
- `build.gradle.kts` - Module build configuration
- `gradle.properties` - Gradle properties
- `local.properties` - Local SDK paths and GitHub credentials
- `proguard-rules.pro` - Code obfuscation rules
- `AndroidManifest.xml` - App manifest and permissions

### Server Configuration
- `package.json` - Node.js dependencies and scripts
- `config/devices.json` - Device connection configurations
- `config/srs.conf` - SRS RTMP server configuration
- `config/docker-compose.yml` - Docker container setup

## SDK and API Levels

### Android
- **Minimum SDK**: 27 (Android 8.1 Oreo)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 35

### THINKLET SDK
- Versions managed in `gradle/thinklet.versions.toml`
- Requires GitHub Packages authentication
- Credentials stored in `local.properties`

## External Services

### GitHub Packages
- Repository: `https://maven.pkg.github.com/FairyDevicesRD/thinklet.app.sdk`
- Authentication: Username and token in `local.properties`
- Required for THINKLET SDK dependencies

### JitPack
- Repository: `https://jitpack.io`
- Used for RootEncoder library

## Development Environment

### Required Tools
- **Android Studio**: IDE for Android development (recommended)
- **JDK 17**: Java Development Kit
- **Android SDK**: Android development tools
- **Node.js**: JavaScript runtime for streaming server
- **ADB**: Android Debug Bridge for device communication
- **Git**: Version control

### Optional Tools
- **Docker**: Container runtime for SRS server
- **MediaMTX**: Local RTMP server for testing
- **ffplay**: Video player for stream verification
