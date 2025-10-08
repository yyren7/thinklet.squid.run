# Android Device Connection Guide

## 📋 环境要求

在开始配置 Android 设备之前，请确保：
- ✅ **Rancher Desktop** (推荐) 或 **Docker Desktop** 已安装并运行
- ✅ **WSL2** 已配置，并且 Rancher Desktop 已启用 Ubuntu 的 WSL 集成
- ✅ 已使用 `Start-Streaming-Auto.bat` 启动所有服务

> **验证方法**：在 Ubuntu WSL 终端运行 `docker --version`，应看到 `Docker version xx.x.x-rd`（注意 `-rd` 后缀）

## 🔍 服务状态检查

如果已运行 `Start-Streaming-Auto.bat`，你的 PC 流媒体环境应该是：
- ✅ 端口转发：已激活（端口 1935, 8080, 1985, 8000）
- ✅ 防火墙规则：已启用
- ✅ SRS Docker 容器：正在运行
- ✅ WebSocket 服务器：正在端口 8000 运行
- ✅ 所有端口都在监听并可访问

**你的 PC IP 地址示例**: `192.168.16.88`（请替换为你的实际 IP）

## 📱 Android App Configuration

The connection error (`SocketTimeoutException`) means your Android device cannot reach the RTMP server. Follow these steps:

### Step 1: Verify Network Connection

**Both devices MUST be on the same Wi-Fi network**:
- PC is connected to: **Wi-Fi 2** (IP: 192.168.16.88)
- Android device must connect to: **The same Wi-Fi network**

### Step 2: Configure RTMP URL in Android App

In your Android app configuration file:

**File**: `app/src/main/java/ai/fd/thinklet/app/squid/run/DefaultConfig.kt`

**Correct Configuration**:
```kotlin
const val DEFAULT_STREAM_URL = "rtmp://192.168.16.88:1935/thinklet.squid.run"
const val DEFAULT_STREAM_KEY = "device1"  // or any unique name
```

**Important**:
- Use `192.168.16.88` (your PC's IP address)
- Port `1935` (RTMP port)
- Application name: `thinklet.squid.run`

### Step 3: Test Connection from Android

Before starting the stream, you can test if the Android device can reach your PC:

1. **Install a terminal app** on Android (like Termux)
2. **Run ping test**:
   ```bash
   ping 192.168.16.88
   ```
3. **Expected result**: You should see responses like:
   ```
   64 bytes from 192.168.16.88: icmp_seq=1 ttl=64 time=5.2 ms
   ```

If ping fails, the devices are not on the same network!

### Step 4: Rebuild and Install Android App

After changing the configuration:
1. **Rebuild** the Android app
2. **Uninstall** the old version from your device
3. **Install** the new version
4. **Start streaming**

## 🌐 View the Stream

Once the Android device successfully connects and starts streaming:

**Web Dashboard**:
```
http://192.168.16.88:8000
```

Open this URL in any browser on your local network to view:
- Device online status
- Live video stream
- File transfer progress

## 🔥 Troubleshooting

### Problem: "Connection timeout" error

**Possible causes**:
1. ❌ Android device is on a different network
   - **Solution**: Connect to the same Wi-Fi as your PC
   
2. ❌ Wrong IP address in Android app
   - **Solution**: Use `192.168.16.88` (not localhost, not 127.0.0.1)
   
3. ❌ Firewall blocking connection
   - **Solution**: Already configured (Thinklet Streaming Environment rule is active)
   
4. ❌ PC IP address changed
   - **Solution**: Check IP with `ipconfig` and update Android app config

### Problem: Stream connects but video doesn't play

**Possible causes**:
1. ❌ Wrong stream key
   - **Solution**: Each device needs a unique stream key
   
2. ❌ Port 8080 not accessible
   - **Solution**: Already configured (port forwarding active)

### Problem: Web dashboard shows "Waiting for stream"

This is **normal** when:
- Device is online but hasn't started streaming yet
- Click "Start Stream" button on the web dashboard
- Or start streaming from the Android app

## 📊 Verify Server Status

You can verify the server is working by visiting:

**SRS API** (check active streams):
```
http://192.168.16.88:1985/api/v1/streams/
```

**Expected response** when no streams active:
```json
{"code":0,"server":"vid-xxxx","streams":[]}
```

**Expected response** when streaming:
```json
{
  "code": 0,
  "server": "vid-xxxx",
  "streams": [
    {
      "id": "...",
      "name": "device1",
      "vhost": "thinklet.squid.run",
      ...
    }
  ]
}
```

## 🔄 If Your PC IP Changes

If your PC gets a new IP address (after reboot or network change):

1. **Find new IP**:
   ```powershell
   ipconfig
   ```
   Look for "Wi-Fi 2" adapter IPv4 Address

2. **Update Android app** configuration with new IP

3. **Rebuild and reinstall** the app

4. **Restart streaming environment**:
   - Double-click `Start-Streaming-Auto.bat`
   - This will update port forwarding with the new WSL IP

## 📞 Need Help?

If you're still experiencing issues:

1. **Check PC firewall**: Make sure "Thinklet Streaming Environment" rule is enabled
2. **Restart services**: Run `Start-Streaming-Auto.bat` again
3. **Check logs**: Look at Android app logs for detailed error messages
4. **Network restrictions**: Some corporate or public Wi-Fi networks block local communication





