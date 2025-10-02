# Low-Latency Live Streaming Solution (SRS + HTTP-FLV)

This project uses [SRS (Simple Realtime Server)](https://ossrs.io) in combination with [flv.js](https://github.com/bilibili/flv.js) to implement a low-latency live streaming solution.

## Core Technologies

- **Push Protocol**: `RTMP`
- **Streaming Media Server**: `SRS (Simple Realtime Server)`
- **Distribution/Playback Protocol**: `HTTP-FLV`
- **Player**: `flv.js`

This combination can control live streaming latency to within **1-3 seconds**.

## ✨ Multi-Stream Support

This solution supports **receiving streams from multiple different devices simultaneously** and displaying all streams on the same webpage:

- 📱 Supports multiple Android devices streaming at the same time
- 🎯 Each device uses a different Stream Key
- 🔄 Automatically detects and displays active streams
- 📊 Real-time display of statistics for each stream
- 🎨 Responsive grid layout that automatically adapts to the number of streams

## Requirements

- [Docker](https://www.docker.com/get-started) and [Docker Compose](https://docs.docker.com/compose/install/)

## Quick Start

### 1. Get Your Local Network IP Address

You need to know the IP address of the computer running the SRS server on your local network.

- **Windows**: Open Command Prompt (CMD) or PowerShell, type `ipconfig`, and find the "IPv4 Address" for your Ethernet or Wi-Fi adapter.
- **macOS/Linux**: Open a terminal, type `ifconfig` or `ip addr`, and find the IP address for your network interface (e.g., `en0` or `eth0`).

In the following steps, we will assume your IP address is `192.168.16.88`. Please replace it with your actual IP address.

### 2. Start the SRS Server

**Important**: If you are using WSL 2 on Windows to run Docker, make sure to execute all `docker` commands from the **WSL terminal**.

In the `streaming` directory, run the following command to start the SRS server:

```bash
docker compose up -d
```

#### ⚠️ WSL2 Network Configuration (Required for Windows Users)

If you are running Docker on Windows with WSL2, you **must configure port forwarding**, otherwise, Android devices will not be able to connect to the SRS server.

**Quick Setup** (run PowerShell as an administrator):
```powershell
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

For detailed instructions, please see [`WSL2-Network-Setup.md`](./WSL2-Network-Setup.md)

**Verify the configuration was successful**:
```powershell
netsh interface portproxy show v4tov4
```

You should see forwarding rules for ports 1935 and 8080.

You can check if the service is running correctly with the following command:

```bash
docker compose ps
```

If everything is normal, you should see the `srs-server` container running (Up).

### 3. Configure the Android Streaming Client

In the Android App, you need to configure the streaming address to your SRS server.

- **File**: `app/src/main/java/ai/fd/thinklet/app/squid/run/DefaultConfig.kt`
- **Modification**: Change the value of `DEFAULT_STREAM_URL` to the RTMP address of your SRS server.

**Example**:
```kotlin
// Replace "192.168.16.88" with your computer's IP
const val DEFAULT_STREAM_URL = "rtmp://192.168.16.88:1935/thinklet.squid.run"
```

#### 🎯 Multi-Device Streaming Configuration

To have multiple devices streaming simultaneously, each device needs to use a **different Stream Key**:

- **Device 1**: Stream Key = `"device1"` or `"phone1"` or `"test_stream"`
- **Device 2**: Stream Key = `"device2"` or `"phone2"` or `"camera1"`
- **Device 3**: Stream Key = `"device3"` or `"tablet1"`, etc.

**Configuration Method**:

1. On each device, change `DEFAULT_STREAM_KEY` to a different value
2. Or, provide an input field in the App's UI to let users customize the Stream Key

**Example**:
```kotlin
// Device 1
const val DEFAULT_STREAM_KEY = "device1"

// Device 2  
const val DEFAULT_STREAM_KEY = "device2"
```

After modification, recompile and run the Android App. Click "Start Streaming", and the video stream will be pushed to your SRS server. The webpage will automatically detect and display all active streams.

### 4. Watch the Live Stream

For convenience, a simple HTTP server is provided in the `streaming` directory.

**First-time use**:
```bash
# Install dependencies
npm install
```

**Start the HTTP server**:
```bash
node simple-http-server.js
```

After the server starts, on any device on the **same local network** as the Android device, open a browser and visit:

```
http://192.168.16.88:8000
```
(Again, replace `192.168.16.88` with your computer's IP)

#### 🎬 Multi-Stream Playback Page Features

The new playback page provides the following features:

- **Auto-detect streams**: The page automatically detects and displays all active streams
- **Grid layout**: Multiple video streams are displayed in a responsive grid
- **Real-time stats**: Displays bitrate, resolution, frame rate, etc. for each stream
- **Device info**: Shows the source IP address for each stream
- **Manual refresh**: Click the "Refresh Stream List" button to manually update
- **Auto-refresh**: By default, automatically checks for new streams every 5 seconds (can be toggled)
- **Independent controls**: Each video player can be controlled independently (play, pause, volume, etc.)
- **Close stream**: You can individually close streams you don't want to watch

You should see a page titled "SRS Multi-Stream Low-Latency Streaming". When a device starts streaming, the video will automatically appear on the page.

## Stopping the Service

To stop the SRS server, run the following command in the `streaming` directory:

```bash
docker compose down
```

## WSL Network Configuration (Important!)

If you are running Docker in a **Windows WSL 2** environment, you need to perform additional network configuration to allow devices on the local network (like your phone) to connect to the SRS server.

You need to **open PowerShell as an administrator** to execute the following network configuration commands, not in the WSL terminal.

### One-Time Setup (only needs to be done once)

**Open PowerShell as an administrator**, then execute the following commands:

1. **Get WSL IP Address**:
   ```bash
   wsl -- ip addr show eth0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
   ```
   Note down the output IP address (e.g., `172.26.136.132`)

2. **Set up Port Forwarding** (replace `172.26.136.132` with the actual IP from the previous step):
   ```powershell
   netsh interface portproxy add v4tov4 listenport=1935 listenaddress=0.0.0.0 connectport=1935 connectaddress=172.26.136.132
   ```

3. **Add Firewall Rule**:
   ```powershell
   netsh advfirewall firewall add rule name="Allow SRS RTMP" dir=in action=allow protocol=TCP localport=1935
   ```

4. **Verify Setup**:
   ```powershell
   netsh interface portproxy show all
   ```

### If the WSL IP Changes

The WSL IP address may change after a reboot. If you find the connection failing, please:

1. Get the new WSL IP address
2. Delete the old port forwarding rule:
   ```powershell
   netsh interface portproxy delete v4tov4 listenport=1935 listenaddress=0.0.0.0
   ```
3. Re-add the port forwarding rule with the new IP

## Daily Usage Management

### Starting the Server

Whenever you need to stream, run the following in the `streaming` directory:

```bash
docker compose up -d
```

### Stopping the Server

It's recommended to stop the server when not in use to save resources:

```bash
docker compose down
```

### Checking Server Status

```bash
docker compose ps
```

### Starting the HTTP Server for Viewing

```bash
node simple-http-server.js
```

### What to do after a reboot?

- **Port forwarding and firewall rules**: These are persistent and do not need to be reset.
- **SRS Server**: If Docker Desktop is set to start on boot, SRS will start automatically; otherwise, you need to manually run `docker compose up -d`.
- **HTTP Server**: You need to manually start it with `node simple-http-server.js`.

## SRS Configuration Details

The SRS configuration file is `srs.conf`. The current configuration implements the following:

### Basic Functionality
- Listens on port `1935` to receive RTMP streams
- Enables `http_server` on port `8080` to provide HTTP-FLV service
- Enables `http_remux` to automatically remux RTMP streams to HTTP-FLV
- Enables `http_api` on port `1985` to provide a management API (for getting the stream list)

### Low-Latency Optimizations
- **Disable GOP cache** (`gop_cache off;`) - a key configuration for low latency
- **Minimum latency mode** (`min_latency on;`) - optimizes playback latency
- **TCP Nodelay** (`tcp_nodelay on;`) - reduces network latency
- **Queue length limit** (`queue_length 10;`) - controls buffer size

### Multi-Stream Support
- Uses the `[vhost]/[app]/[stream].flv` path pattern, supporting any number of concurrent streams
- Each stream is distinguished by a different `[stream]` parameter
- All active streams can be queried via the HTTP API (`http://localhost:1985/api/v1/streams/`)

### API Endpoints
- **Stream List**: `http://localhost:1985/api/v1/streams/` - get all active streams
- **Server Status**: `http://localhost:1985/api/v1/summaries/` - get server statistics

## 📝 Multi-Device Streaming Usage Example

### Scenario 1: Dual Phone Live Stream

1. **Prepare two Android devices** (Phone A and Phone B)
2. **Configure on Phone A**:
   ```kotlin
   const val DEFAULT_STREAM_KEY = "phone_a"
   ```
3. **Configure on Phone B**:
   ```kotlin
   const val DEFAULT_STREAM_KEY = "phone_b"
   ```
4. **Start the App and begin streaming on both phones**
5. **Visit** `http://your-ip:8000` in a browser
6. **Result**: The page will display both video streams simultaneously, each with its own playback controls.

### Scenario 2: Multi-Angle Shooting

Use 3 or more devices to shoot the same scene from different angles:
- Device 1 (Front): Stream Key = `"front_view"`
- Device 2 (Side): Stream Key = `"side_view"`  
- Device 3 (Top): Stream Key = `"top_view"`

All video streams will be displayed synchronously on the same webpage, achieving multi-angle real-time monitoring.

### Scenario 3: Dynamically Add/Remove Devices

- **Add a new device**: After a new device starts streaming, the page will automatically detect and display it within 5 seconds (if auto-refresh is enabled).
- **Remove a device**: After a device stops streaming, the corresponding video will be automatically removed from the page.
- **Manual management**: You can also click the "✖ Close" button on each video card to manually remove unwanted streams.

## ⚠️ Important Notes

1. **Network Requirements**: All devices (streaming and viewing) must be on the same local network.
2. **Stream Key Uniqueness**: Each device must have a different Stream Key, otherwise they will overwrite each other.
3. **Performance Considerations**: Streaming/playing multiple high-definition videos simultaneously will consume a lot of bandwidth and resources.
   - It's recommended to stream no more than 4-6 channels at the same time.
   - Adjust video resolution and bitrate according to your network conditions.
4. **Browser Compatibility**: It is recommended to use the latest version of Chrome, Edge, or Firefox.
5. **CORS Settings**: CORS is enabled in the SRS configuration, allowing cross-domain access.
