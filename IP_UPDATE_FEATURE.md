# IP 动态更新功能说明

## 功能概述

现在当您在主页面更新 Server IP 并点击保存后，应用会自动应用新的 IP 地址到所有相关服务，无需手动重启应用。

## 实现细节

### 1. 统一 IP 管理
- 所有使用 PC 端 IP 的功能都从 `MainViewModel.serverIp` 获取 IP 地址
- IP 地址通过 SharedPreferences 持久化保存

### 2. 自动重连机制

当您保存新 IP 后，以下服务会自动重新连接：

#### a. 推流服务 (Streaming)
- 如果正在推流：
  1. 自动停止当前推流
  2. 等待 1 秒让连接完全关闭
  3. 使用新 IP 重新开始推流
  4. 显示成功/失败提示

#### b. 状态报告服务 (StatusReportingManager)
- 自动断开与旧 PC 的 WebSocket 连接
- 使用新 IP 重新建立 WebSocket 连接
- 继续发送设备状态报告到新的 PC

#### c. 文件传输服务 (FileTransferServer)
- 文件传输服务监听本地端口 8889，不需要 PC 的 IP
- 继续正常工作，无需重启

### 3. 录制功能
- 录制功能不依赖 PC 端 IP，将视频保存到本地
- IP 更新不影响正在进行的录制

## 使用流程

1. **编辑 IP 地址**
   - 在主页面找到 "Server IP" 输入框
   - 输入新的 IP 地址（格式：192.168.x.x）
   - 保存按钮会自动启用

2. **保存 IP**
   - 点击 "Save Config" 按钮
   - 应用会验证 IP 格式
   - 如果格式正确，会保存并自动重连所有服务

3. **自动重连**
   - 如果正在推流：推流会自动停止并重新连接
   - StatusReportingManager 会自动重连到新 PC
   - 您会收到 Toast 提示：
     - "Configuration saved successfully"
     - "Server IP updated to: [新IP]"
     - 推流重连成功："Streaming reconnected with new IP"

## 技术实现

### MainActivity.kt
- 添加了 `serverIp` StateFlow 监听器
- 实现了 `handleServerIpChanged()` 方法处理 IP 变更
- 当检测到 IP 变化时（非初始值），自动触发重连流程

### MainViewModel.kt
- `serverIp` StateFlow：存储当前 IP
- `streamUrl` StateFlow：从 `serverIp` 自动派生
- 当 `serverIp` 变化时，`streamUrl` 自动更新

### StatusReportingManager.kt
- `updateStreamUrl()` 方法：接收新的 streamUrl
- `reconnect()` 方法：停止旧连接，启动新连接
- 从 streamUrl 中提取 hostname 用于 WebSocket 连接

## 日志标记

在 logcat 中可以看到以下日志来追踪 IP 更新过程：

```
I/MainActivity: 🔄 Server IP changed to: [新IP], reconnecting services...
I/MainActivity: 📡 Stopping streaming to reconnect with new IP...
I/MainActivity: 📡 Restarting streaming with new IP...
I/MainActivity: ✅ IP change handling completed
D/StatusReportingManager: Stream URL updated to: [新URL]
D/StatusReportingManager: 🛑 Stopping StatusReportingManager...
D/StatusReportingManager: StatusReportingManager started.
D/StatusReportingManager: 🔗 Connecting to WebSocket: [新地址]
D/StatusReportingManager: ✅ WebSocket connection opened
```

## 注意事项

1. **IP 格式验证**：只接受标准 IPv4 格式（例：192.168.1.100）
2. **推流中断**：如果正在推流，更新 IP 会导致短暂中断（约 1-2 秒）
3. **网络连接**：确保设备和新 PC 在同一网络中
4. **首次加载**：应用启动时加载的 IP 不会触发重连（只有用户主动更改才会触发）

## 测试建议

1. **测试场景 1：未推流时更新 IP**
   - 启动应用
   - 更改 IP 并保存
   - 检查 StatusReportingManager 是否连接到新 PC

2. **测试场景 2：推流中更新 IP**
   - 启动推流
   - 更改 IP 并保存
   - 检查推流是否自动断开并重连到新 PC

3. **测试场景 3：录制中更新 IP**
   - 开始录制
   - 更改 IP 并保存
   - 检查录制是否继续正常（不受影响）

## 故障排查

如果 IP 更新后无法连接：
1. 检查新 IP 是否正确
2. 检查 PC 端服务器是否运行
3. 检查设备和 PC 是否在同一网络
4. 查看 logcat 日志了解详细错误信息

