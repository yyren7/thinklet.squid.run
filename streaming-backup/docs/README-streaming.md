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

### 必备环境

- **Windows 10/11** 操作系统
- **WSL 2** (Windows Subsystem for Linux 2)
  - 确保已安装 Ubuntu 或其他 Linux 发行版
  - 使用 `wsl -l -v` 检查版本必须为 `2`
- **Rancher Desktop** 或 **Docker Desktop** (推荐 Rancher Desktop)
  - ⚠️ **重要**：不要在 WSL 内部手动安装 Docker
  - ✅ 必须在 Rancher Desktop 设置中启用 WSL 集成（见下方配置步骤）
- **Node.js** (v16 或更高版本)

### Rancher Desktop 配置步骤

1. **安装 Rancher Desktop**
   - 下载地址：https://rancherdesktop.io/
   
2. **配置 WSL 集成**（关键步骤）
   - 打开 Rancher Desktop
   - 进入 **Preferences → WSL**
   - ✅ 勾选你的 WSL 发行版（如 `Ubuntu`）
   - ❌ 不要勾选 `rancher-desktop` 或 `rancher-desktop-data`
   - 保存设置并等待 Rancher Desktop 重启

3. **验证配置**
   - 打开 Ubuntu WSL 终端
   - 运行 `docker --version`
   - 应该看到类似 `Docker version 28.3.3-rd` 的输出（注意 `-rd` 后缀）

> **为什么不在 WSL 内安装 Docker？**  
> Rancher Desktop 提供了统一的 Docker 环境管理，避免了环境冲突和网络配置问题。所有通过 WSL 运行的容器都会显示在 Rancher Desktop 的图形界面中，方便管理。

## Quick Start

### 1. Get Your Local Network IP Address

You need to know the IP address of the computer running the SRS server on your local network.

- **Windows**: Open Command Prompt (CMD) or PowerShell, type `ipconfig`, and find the "IPv4 Address" for your Ethernet or Wi-Fi adapter.
- **macOS/Linux**: Open a terminal, type `ifconfig` or `ip addr`, and find the IP address for your network interface (e.g., `en0` or `eth0`).

In the following steps, we will assume your IP address is `192.168.16.88`. Please replace it with your actual IP address.

### 2. 一键启动所有服务（推荐方式）⭐

我们提供了**完全自动化**的启动脚本，无需手动配置任何网络或防火墙设置。

#### 使用自动化启动脚本

1. **确保 Rancher Desktop 正在运行**

2. **在文件资源管理器中**，进入 `streaming` 文件夹

3. **双击 `Start-Streaming-Auto.bat` 文件**

4. 当 Windows 弹出 UAC 权限请求时，点击**"是"**

5. 脚本会自动完成以下所有操作：
   - ✅ 检测 WSL2 IP 地址
   - ✅ 配置网络端口转发（1935, 8080, 1985, 8000）
   - ✅ 设置 Windows 防火墙规则
   - ✅ 启动 SRS Docker 容器
   - ✅ 启动 Node.js WebSocket 服务器
   - ✅ 验证所有服务是否正常运行

6. 看到绿色的 **"SUCCESS"** 消息后，所有服务就已就绪！

> 💡 **提示**：详细的启动说明请参见 [QUICK-START.md](./QUICK-START.md)

#### 手动启动方式（仅用于调试）

如果你需要手动控制服务启动过程：

```bash
# 在 WSL 终端中
cd /mnt/c/Users/<你的用户名>/thinklet.squid.run/streaming
docker compose up -d
```

然后在 Windows PowerShell 中手动运行网络配置和 Node.js 服务器（不推荐，建议使用自动化脚本）。

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

## 故障排查

### 问题：Rancher Desktop 提示 "The rancher-desktop WSL distribution is not meant to be used..."

**原因**：你可能在错误的 WSL 发行版中运行命令。

**解决方案**：
1. 使用 `Start-Streaming-Auto.bat` 脚本启动（推荐）
2. 或者确保在 Rancher Desktop 的 WSL 集成设置中勾选了你的 Ubuntu 发行版

### 问题：Docker 命令找不到

**解决方案**：
1. 确认 Rancher Desktop 正在运行
2. 检查 WSL 集成配置（Preferences → WSL → 勾选 Ubuntu）
3. 重启 WSL：`wsl --shutdown`（在 Windows PowerShell 中运行）
4. 在 Ubuntu 终端中验证：`docker --version` 应显示 `-rd` 后缀

### 问题：Android 设备无法连接

**解决方案**：
1. 确保使用了 `Start-Streaming-Auto.bat` 启动服务
2. 检查防火墙规则是否已创建：
   ```powershell
   Get-NetFirewallRule -DisplayName "Thinklet Streaming Environment"
   ```
3. 检查端口转发是否已配置：
   ```powershell
   netsh interface portproxy show v4tov4
   ```

### 问题：重启电脑后无法连接

**原因**：WSL IP 地址可能已更改。

**解决方案**：
- 重新运行 `Start-Streaming-Auto.bat` 脚本，它会自动更新所有配置

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
