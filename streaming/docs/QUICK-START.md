# Quick Start Guide for Thinklet Streaming

## 🚀 Starting the Streaming Environment

We provide two easy ways to start the streaming environment:

### Option 1: Auto-Elevated Start (Recommended) ⭐

**File**: `Start-Streaming-Auto.bat`

**How to use**:
1. **Double-click** the file
2. Click **"Yes"** when Windows asks for administrator permissions
3. Wait for all services to start
4. Done! The streaming environment is ready

**Advantages**:
- Simplest method - just double-click
- Automatically requests admin privileges
- No need to right-click

### Option 2: Manual Administrator Start

**File**: `Start-Streaming.bat`

**How to use**:
1. **Right-click** the file
2. Select **"Run as administrator"**
3. Wait for all services to start
4. Done! The streaming environment is ready

**Advantages**:
- More control over when to elevate
- Some users prefer explicit admin elevation

## 📋 What These Scripts Do

Both scripts will automatically:
1. ✅ Detect WSL2 IP address
2. ✅ Configure network port forwarding (ports 1935, 8080, 1985)
3. ✅ Set up Windows Firewall rules
4. ✅ Start SRS Docker container (for video streaming)
5. ✅ Start Node.js WebSocket server (for device management)
6. ✅ Verify all services are running correctly

## 🌐 After Starting

Once the services are running, you can:

1. **Open the web dashboard** in your browser:
   ```
   http://localhost:8000
   ```
   or from another device on the same network:
   ```
   http://YOUR-PC-IP:8000
   ```

2. **Configure your Android app** with:
   - RTMP URL: `rtmp://YOUR-PC-IP:1935/thinklet.squid.run`
   - Stream Key: `device1` (or any unique name)

3. **Start streaming** from the Android app

## ⚠️ Requirements

Before running these scripts, make sure:
- ✅ **Rancher Desktop** (推荐) 或 **Docker Desktop** 已安装并正在运行
- ✅ **WSL2** 已启用并配置（需要安装 Ubuntu 或其他 Linux 发行版）
- ✅ **Rancher Desktop 的 WSL 集成已启用**：
  - 打开 Rancher Desktop → Preferences → WSL
  - 勾选你的 WSL 发行版（如 `Ubuntu`）
  - ❌ 不要勾选 `rancher-desktop` 本身
- ✅ **Node.js** 已安装（v16 或更高版本）
- ✅ **npm 依赖**已安装（首次使用需在 `streaming` 目录运行 `npm install`）

> **重要提醒**：不要在 WSL 内部手动安装 Docker。请使用 Rancher Desktop 提供的 Docker 环境，并确保已启用 WSL 集成。验证方法：在 Ubuntu 终端运行 `docker --version`，应看到 `Docker version xx.x.x-rd` 输出（注意 `-rd` 后缀）。

## 🛑 Stopping the Services

To stop all services:
1. Close the command window (or press Ctrl+C)
2. To stop Docker containers, run in WSL terminal:
   ```bash
   docker compose down
   ```

## 💡 Troubleshooting

**如果看到 "Docker is not available"**:
- 先启动 Rancher Desktop，然后重新运行脚本

**如果看到 "The rancher-desktop WSL distribution is not meant to be used..."**:
- 这说明你在错误的 WSL 发行版中运行了命令
- 解决方案：使用 `Start-Streaming-Auto.bat` 脚本启动（推荐）
- 或确保在 Rancher Desktop 中启用了 Ubuntu 的 WSL 集成

**如果看到端口冲突**:
- 可能有其他服务占用了相同端口
- 脚本会显示哪个进程正在使用该端口

**如果 Android 设备无法连接**:
- 检查 PC 和 Android 是否在同一网络
- 验证 Windows 防火墙规则是否已创建
- 检查 Android 应用中的 IP 地址配置

**如果重启电脑后无法连接**:
- WSL IP 地址可能已更改
- 重新运行 `Start-Streaming-Auto.bat` 脚本即可

**如果看到 "container name is already in use" 错误**:
- 这是正常的，脚本会自动清理旧容器
- 如果脚本没有自动清理，可以手动运行：
  ```bash
  wsl -e bash -c "docker stop srs-server && docker rm srs-server"
  ```
- 然后重新运行启动脚本

## 📖 更多详细信息

查看以下文档获取更多信息：
- **[README-streaming.md](./README-streaming.md)** - 完整的技术文档
- **[ANDROID-CONNECTION-GUIDE.md](./ANDROID-CONNECTION-GUIDE.md)** - Android 配置详解
- **[FILE-TRANSFER-README.md](./FILE-TRANSFER-README.md)** - 文件传输功能






