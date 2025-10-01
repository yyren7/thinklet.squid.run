# WSL2 环境下的 SRS 直播服务器配置指南

## 📋 概述

本文档专门针对在 **Windows + WSL2 + Docker** 环境下运行 SRS 直播服务器的用户。

## 🔍 为什么需要特殊配置？

WSL2 使用虚拟化技术，有自己的虚拟网络接口。这意味着：

- WSL2 内的 Docker 容器有一个内部 IP（如 `172.26.xxx.xxx`）
- Windows 主机有一个局域网 IP（如 `192.168.16.88`）
- **Android 设备只能看到 Windows 主机 IP，无法直接访问 WSL2 内部网络**

因此需要配置**端口转发**，将 Windows 主机端口映射到 WSL2 的 Docker 容器端口。

## 🚀 快速开始

### 前置条件

- ✅ Docker Desktop 已安装并配置为使用 WSL2
- ✅ SRS 服务器已在 WSL2 中运行
- ✅ 管理员权限（用于配置端口转发）

### 一键配置（推荐）

#### 1. 启动 SRS 服务器

在 WSL 终端中：
```bash
cd /mnt/c/Users/J100052060/thinklet.squid.run/streaming
docker compose up -d
```

#### 2. 配置端口转发

在 **管理员权限的 PowerShell** 中：
```powershell
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

#### 3. 验证配置

```powershell
.\test-connection.ps1
```

看到所有 "OK" 表示配置成功！

## 📊 配置详解

### 需要转发的端口

| 端口 | 协议 | 用途 | 必需 |
|------|------|------|------|
| 1935 | RTMP | 推流 | ✅ 是 |
| 8080 | HTTP | FLV 播放 | ✅ 是 |
| 1985 | HTTP | API 管理 | ⚠️ 可选 |

### 端口转发原理

```
Android 设备
    ↓
192.168.16.88:1935 (Windows 主机)
    ↓ (端口转发)
172.26.136.132:1935 (WSL2)
    ↓
Docker 容器 (SRS 服务器)
```

### 手动配置步骤

如果自动脚本失败，可以手动配置：

```powershell
# 1. 获取 WSL2 IP
$wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]
Write-Host "WSL2 IP: $wslIp"

# 2. 删除旧规则（如果存在）
netsh interface portproxy delete v4tov4 listenport=1935 listenaddress=0.0.0.0
netsh interface portproxy delete v4tov4 listenport=8080 listenaddress=0.0.0.0

# 3. 添加新规则
netsh interface portproxy add v4tov4 listenport=1935 listenaddress=0.0.0.0 connectport=1935 connectaddress=$wslIp
netsh interface portproxy add v4tov4 listenport=8080 listenaddress=0.0.0.0 connectport=8080 connectaddress=$wslIp

# 4. 配置防火墙
New-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080

# 5. 验证配置
netsh interface portproxy show v4tov4
```

## 🧪 测试与验证

### 1. 检查端口转发

```powershell
netsh interface portproxy show v4tov4
```

期望输出：
```
Listen on ipv4:             Connect to ipv4:
Address         Port        Address         Port
--------------- ----------  --------------- ----------
0.0.0.0         1935        172.26.136.132  1935
0.0.0.0         8080        172.26.136.132  8080
```

### 2. 测试端口连通性

```powershell
# 测试 RTMP 端口
Test-NetConnection -ComputerName localhost -Port 1935

# 测试 HTTP-FLV 端口
Test-NetConnection -ComputerName localhost -Port 8080
```

### 3. 在 Android 设备上测试

确保 Android 设备和电脑在同一局域网，然后：

```kotlin
// 在 DefaultConfig.kt 中配置
const val DEFAULT_STREAM_URL = "rtmp://192.168.16.88:1935/thinklet.squid.run"
const val DEFAULT_STREAM_KEY = "test_stream"
```

启动推流，应该能成功连接。

## 🔧 常见问题解决

### 问题 1: 端口转发配置失败

**错误**: `The requested operation requires elevation`

**解决**: 必须以管理员身份运行 PowerShell

### 问题 2: 重启后无法连接

**原因**: WSL2 的 IP 地址在重启后可能改变

**解决**: 重新运行配置脚本
```powershell
.\setup-wsl2-port-forwarding.ps1
```

### 问题 3: 防火墙阻止连接

**检查**: 
```powershell
Get-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports"
```

**修复**:
```powershell
New-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080
```

### 问题 4: Android 仍然无法连接

**排查步骤**:

1. 确认 Windows 主机 IP
   ```powershell
   ipconfig | findstr "IPv4"
   ```

2. 确认 Android 和电脑在同一局域网
   - 检查 WiFi SSID 是否相同
   - Ping Windows 主机 IP

3. 检查 SRS 服务器状态
   ```powershell
   wsl -e bash -c "docker compose ps"
   ```

4. 查看 SRS 日志
   ```powershell
   wsl -e bash -c "cd /mnt/c/Users/J100052060/thinklet.squid.run/streaming && docker compose logs --tail 50"
   ```

## 🔄 自动化配置（可选）

### 创建启动任务

如果希望每次开机自动配置端口转发：

1. 打开"任务计划程序"
2. 创建基本任务
3. 触发器：系统启动时
4. 操作：启动程序
   - 程序：`powershell.exe`
   - 参数：`-ExecutionPolicy Bypass -File C:\Users\J100052060\thinklet.squid.run\streaming\setup-wsl2-port-forwarding.ps1`
5. 勾选"使用最高权限运行"

### 快捷方式

创建桌面快捷方式用于快速配置：

1. 右键桌面 → 新建 → 快捷方式
2. 位置：`powershell.exe -ExecutionPolicy Bypass -File "C:\Users\J100052060\thinklet.squid.run\streaming\setup-wsl2-port-forwarding.ps1"`
3. 名称：`配置 SRS 端口转发`
4. 右键快捷方式 → 属性 → 高级 → 勾选"用管理员身份运行"

## 📱 Android 应用配置

确保 `DefaultConfig.kt` 中的配置正确：

```kotlin
object DefaultConfig {
    // RTMP 服务器配置
    const val DEFAULT_STREAM_URL = "rtmp://192.168.16.88:1935/thinklet.squid.run"
    const val DEFAULT_STREAM_KEY = "test_stream"
    
    // 其他配置...
}
```

## 🌐 观看直播

配置完成后，有两种方式观看直播：

### 方式 1: 直接访问 SRS

浏览器访问：`http://192.168.16.88:8080`

### 方式 2: 使用自定义播放页面

1. 启动 HTTP 服务器
   ```bash
   cd streaming
   node simple-http-server.js
   ```

2. 浏览器访问：`http://192.168.16.88:3000`

## 📚 相关文档

- [WSL2-Network-Setup.md](./WSL2-Network-Setup.md) - 详细网络配置说明
- [QUICK-FIX-WSL2.md](./QUICK-FIX-WSL2.md) - 快速故障排除
- [README-streaming.md](./README-streaming.md) - 主要文档

## ✅ 配置检查清单

在推流前，确认以下各项：

- [ ] SRS 服务器在 WSL2 中运行
- [ ] 端口转发已配置（1935, 8080）
- [ ] 防火墙规则已添加
- [ ] Android 和电脑在同一局域网
- [ ] Android 应用配置了正确的 RTMP URL
- [ ] 运行测试脚本，所有测试通过

全部完成后，就可以开始直播了！🎉





