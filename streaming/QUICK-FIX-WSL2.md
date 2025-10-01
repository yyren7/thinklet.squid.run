# WSL2 RTMP 连接超时快速修复指南

## 问题症状

Android 应用日志显示：
```
RtmpClient: connection error
io.ktor.utils.io.ClosedByteChannelException
Caused by: java.net.SocketTimeoutException
```

RTMP 握手在读取 S0 时超时（约5秒）。

## 根本原因

WSL2 使用 NAT 网络，Android 设备无法直接访问 WSL2 内的 Docker 容器端口。需要配置 Windows 端口转发。

## 一键修复（推荐）

### 步骤 1: 以管理员身份运行 PowerShell

右键点击 PowerShell 图标 → "以管理员身份运行"

### 步骤 2: 运行配置脚本

```powershell
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

### 步骤 3: 验证配置

```powershell
.\test-connection.ps1
```

应该看到所有测试都显示 "OK"。

## 手动修复（备选方案）

如果自动脚本失败，手动执行：

```powershell
# 获取 WSL2 IP
$wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]

# 添加端口转发
netsh interface portproxy add v4tov4 listenport=1935 listenaddress=0.0.0.0 connectport=1935 connectaddress=$wslIp
netsh interface portproxy add v4tov4 listenport=8080 listenaddress=0.0.0.0 connectport=8080 connectaddress=$wslIp

# 添加防火墙规则
New-NetFirewallRule -DisplayName "WSL2 SRS Streaming" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080
```

## 验证连接

### 检查端口转发

```powershell
netsh interface portproxy show v4tov4
```

应该显示：
```
Listen on ipv4:             Connect to ipv4:
Address         Port        Address         Port
--------------- ----------  --------------- ----------
0.0.0.0         1935        172.26.xxx.xxx  1935
0.0.0.0         8080        172.26.xxx.xxx  8080
```

### 测试 RTMP 连接

```powershell
Test-NetConnection -ComputerName 192.168.16.88 -Port 1935
```

## 重启后的注意事项

⚠️ **重要**: WSL2 的 IP 地址可能在重启后改变！

如果重启后无法连接，重新运行配置脚本：
```powershell
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

## 常见问题

### Q: 为什么需要管理员权限？
A: 配置端口转发和防火墙规则需要系统级权限。

### Q: 配置会在重启后失效吗？
A: 端口转发规则会保留，但 WSL2 的 IP 可能改变，需要重新配置。

### Q: 可以设置开机自动配置吗？
A: 可以创建计划任务，在系统启动时自动运行配置脚本。

### Q: 如何撤销配置？
A: 
```powershell
netsh interface portproxy delete v4tov4 listenport=1935 listenaddress=0.0.0.0
netsh interface portproxy delete v4tov4 listenport=8080 listenaddress=0.0.0.0
Remove-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports"
```

## 现在可以开始推流了！

配置完成后，在 Android 设备上：
1. 确保设备和电脑在同一局域网
2. 使用配置：
   - RTMP URL: `rtmp://192.168.16.88:1935/thinklet.squid.run`
   - Stream Key: `test_stream`
3. 点击"开始直播"

观看直播：
- 打开浏览器访问 `http://192.168.16.88:8080`
- 或运行 HTTP 服务器：`node simple-http-server.js`，然后访问 `http://192.168.16.88:3000`





