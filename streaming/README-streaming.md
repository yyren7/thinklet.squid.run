# 低延迟直播方案 (SRS + HTTP-FLV)

本项目使用 [SRS (Simple Realtime Server)](https://ossrs.io) 配合 [flv.js](https://github.com/bilibili/flv.js) 实现低延迟的直播方案。

## 核心技术

- **推流协议**: `RTMP`
- **流媒体服务器**: `SRS (Simple Realtime Server)`
- **分发/播放协议**: `HTTP-FLV`
- **播放器**: `flv.js`

这个组合可以将直播延迟控制在 **1-3秒**。

## 环境要求

- [Docker](https://www.docker.com/get-started) 和 [Docker Compose](https://docs.docker.com/compose/install/)

## 快速开始

### 1. 获取本机局域网IP地址

你需要知道运行SRS服务器的电脑在局域网中的IP地址。

- **Windows**: 打开命令提示符(CMD)或PowerShell，输入 `ipconfig`，查找你的以太网或Wi-Fi适配器的 "IPv4 地址"。
- **macOS/Linux**: 打开终端，输入 `ifconfig` 或 `ip addr`，查找你的网络接口（如 `en0` 或 `eth0`）的IP地址。

在后续步骤中，我们假设你的IP地址是 `192.168.16.88`。请将其替换为你的实际IP地址。

### 2. 启动SRS服务器

在 `streaming` 目录下，运行以下命令启动SRS服务器：

```bash
docker compose up -d
```

你可以通过以下命令检查服务是否正常运行：

```bash
docker compose ps
```

如果一切正常，你应该能看到 `srs-server` 容器正在运行 (Up)。

### 3. 配置安卓推流端

在安卓App中，你需要将推流地址配置为你的SRS服务器。

- **文件**: `app/src/main/java/ai/fd/thinklet/app/squid/run/DefaultConfig.kt`
- **修改**: 将 `DEFAULT_STREAM_URL` 的值修改为你的SRS服务器的RTMP地址。

**示例**:
```kotlin
// 将 "192.168.16.88" 替换为你的电脑IP
const val DEFAULT_STREAM_URL = "rtmp://192.168.16.88:1935/thinklet.squid.run"
```

- **流密钥 (Stream Key)**: 保持 `DEFAULT_STREAM_KEY` 为 `"test_stream"` 即可。

修改后，重新编译并运行安卓App。点击 "开始直播"，视频流就会被推送到你的SRS服务器。

### 4. 观看直播

为了方便观看，`streaming` 目录下提供了一个简单的HTTP服务器。

**首次使用**:
```bash
# 安装依赖
npm install
```

**启动HTTP服务器**:
```bash
node simple-http-server.js
```

服务器启动后，在与安卓设备**同一个局域网**的任何设备上，打开浏览器并访问：

```
http://192.168.16.88:8000
```
(同样，将 `192.168.16.88` 替换为你的电脑IP)

你应该能看到一个标题为 "SRS 低延迟直播" 的页面，并且视频正在播放。

## 停止服务

要停止SRS服务器，请在 `streaming` 目录下运行：

```bash
docker compose down
```

## WSL 网络配置（重要！）

如果你在 **Windows WSL 2** 环境中运行 Docker，需要进行额外的网络配置才能让局域网中的设备（如手机）连接到 SRS 服务器。

### 一次性设置（只需设置一次）

**以管理员身份打开 PowerShell**，然后执行以下命令：

1. **获取 WSL IP 地址**：
   ```bash
   wsl -- ip addr show eth0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
   ```
   记下输出的 IP 地址（类似 `172.26.136.132`）

2. **设置端口转发**（将 `172.26.136.132` 替换为上一步获取的实际 IP）：
   ```powershell
   netsh interface portproxy add v4tov4 listenport=1935 listenaddress=0.0.0.0 connectport=1935 connectaddress=172.26.136.132
   ```

3. **添加防火墙规则**：
   ```powershell
   netsh advfirewall firewall add rule name="Allow SRS RTMP" dir=in action=allow protocol=TCP localport=1935
   ```

4. **验证设置**：
   ```powershell
   netsh interface portproxy show all
   ```

### 如果 WSL IP 发生变化

WSL 的 IP 地址可能会在重启后发生变化。如果发现连接失败，请：

1. 重新获取 WSL IP 地址
2. 删除旧的端口转发规则：
   ```powershell
   netsh interface portproxy delete v4tov4 listenport=1935 listenaddress=0.0.0.0
   ```
3. 使用新 IP 重新添加端口转发规则

## 日常使用管理

### 启动服务器

每次需要直播时，在 `streaming` 目录下运行：

```bash
docker compose up -d
```

### 停止服务器

不使用时建议停止服务器以节省资源：

```bash
docker compose down
```

### 查看服务器状态

```bash
docker compose ps
```

### 启动观看页面的 HTTP 服务器

```bash
node simple-http-server.js
```

### 重启电脑后需要做什么？

- **端口转发和防火墙规则**：会自动保持，无需重新设置
- **SRS 服务器**：如果 Docker Desktop 设置为开机自启，SRS 会自动启动；否则需要手动执行 `docker compose up -d`
- **HTTP 服务器**：需要手动启动 `node simple-http-server.js`

## SRS 配置说明

SRS的配置文件是 `srs.conf`。当前配置实现了以下功能：
- 监听 `1935` 端口接收RTMP推流。
- 启用 `http_server` 在 `8080` 端口提供HTTP-FLV服务。
- 启用 `http_remux` 将RTMP流自动转封装为HTTP-FLV流。
- **关闭GOP缓存 (`gop_cache off;`)**，这是实现低延迟的关键配置。
- 启用 `http_api` 在 `1985` 端口提供管理API。你可以通过访问 `http://localhost:1985` 来查看SRS的状态。
