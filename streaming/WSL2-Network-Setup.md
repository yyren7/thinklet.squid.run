# WSL2 网络配置指南

## 问题说明

当 Docker 运行在 WSL2 中时，由于 WSL2 使用 NAT 网络，Android 设备无法直接访问 WSL2 内的 Docker 容器端口。因此需要配置 Windows 端口转发。

## 快速配置（推荐）

### 方法 1: 使用自动配置脚本

1. **以管理员身份运行 PowerShell**
   - 右键点击 PowerShell 图标
   - 选择 "以管理员身份运行"

2. **切换到项目目录并运行脚本**
   ```powershell
   cd C:\Users\J100052060\thinklet.squid.run\streaming
   .\setup-wsl2-port-forwarding.ps1
   ```

3. **验证配置**
   ```powershell
   netsh interface portproxy show v4tov4
   ```

### 方法 2: 手动配置

如果自动脚本失败，可以手动执行以下命令（需要管理员权限）：

```powershell
# 获取 WSL2 IP 地址
$wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]
Write-Host "WSL2 IP: $wslIp"

# 添加端口转发规则
netsh interface portproxy add v4tov4 listenport=1935 listenaddress=0.0.0.0 connectport=1935 connectaddress=$wslIp
netsh interface portproxy add v4tov4 listenport=8080 listenaddress=0.0.0.0 connectport=8080 connectaddress=$wslIp
netsh interface portproxy add v4tov4 listenport=1985 listenaddress=0.0.0.0 connectport=1985 connectaddress=$wslIp

# 添加防火墙规则
New-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080,1985
```

## 验证配置

### 1. 检查端口转发

```powershell
netsh interface portproxy show v4tov4
```

应该看到类似输出：
```
Listen on ipv4:             Connect to ipv4:

Address         Port        Address         Port
--------------- ----------  --------------- ----------
0.0.0.0         1935        172.26.xxx.xxx  1935
0.0.0.0         8080        172.26.xxx.xxx  8080
0.0.0.0         1985        172.26.xxx.xxx  1985
```

### 2. 检查防火墙规则

```powershell
Get-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports"
```

### 3. 测试 SRS 服务器连接

在 Android 设备或同一局域网的其他设备上访问：
```
http://192.168.16.88:8080
```

应该能看到 SRS 的默认页面。

## 清理配置

如果需要删除端口转发规则：

```powershell
# 删除端口转发
netsh interface portproxy delete v4tov4 listenport=1935 listenaddress=0.0.0.0
netsh interface portproxy delete v4tov4 listenport=8080 listenaddress=0.0.0.0
netsh interface portproxy delete v4tov4 listenport=1985 listenaddress=0.0.0.0

# 删除防火墙规则
Remove-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports"
```

## 重启后的注意事项

**重要**: WSL2 的 IP 地址可能在重启后改变！

如果重启电脑后无法连接，请重新运行配置脚本：

```powershell
# 以管理员身份运行
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

## 故障排查

### 问题：Android 设备无法连接

1. **检查 Windows 主机 IP**
   ```powershell
   ipconfig | findstr "IPv4"
   ```
   确认 `192.168.16.88` 是正确的局域网 IP

2. **检查 WSL2 IP**
   ```powershell
   wsl -e bash -c "hostname -I"
   ```

3. **检查 SRS 服务器状态**
   ```bash
   wsl -e bash -c "cd /mnt/c/Users/J100052060/thinklet.squid.run/streaming && docker compose ps"
   ```

4. **检查防火墙**
   - 确保 Windows 防火墙允许入站连接
   - 如果使用第三方防火墙，请添加例外

5. **测试本地连接**
   ```powershell
   Test-NetConnection -ComputerName 192.168.16.88 -Port 1935
   ```

### 问题：端口已被占用

如果端口被其他程序占用，查找占用进程：

```powershell
netstat -ano | findstr :1935
```

然后终止该进程或更改 SRS 端口。

## 替代方案：使用 Host 网络模式

如果端口转发仍然有问题，可以修改 Docker Compose 配置使用 Host 网络模式：

```yaml
# docker-compose.yml
services:
  srs:
    image: ossrs/srs:5
    network_mode: host
    volumes:
      - ./srs.conf:/usr/local/srs/conf/srs.conf
```

**注意**: Host 网络模式在 Windows WSL2 上可能不完全支持。

## 当前配置状态

✅ **端口 1935** (RTMP): 已配置  
✅ **端口 8080** (HTTP-FLV): 已配置  
⚠️ **端口 1985** (HTTP API): 需要手动添加（可选）

现在可以使用以下配置推流：
- **RTMP URL**: `rtmp://192.168.16.88:1935/thinklet.squid.run`
- **Stream Key**: `test_stream`
- **完整地址**: `rtmp://192.168.16.88:1935/thinklet.squid.run/test_stream`






