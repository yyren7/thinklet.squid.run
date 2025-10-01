# 低延迟直播方案 (SRS + HTTP-FLV)

本项目使用 [SRS (Simple Realtime Server)](https://ossrs.io) 配合 [flv.js](https://github.com/bilibili/flv.js) 实现低延迟的直播方案。

## 核心技术

- **推流协议**: `RTMP`
- **流媒体服务器**: `SRS (Simple Realtime Server)`
- **分发/播放协议**: `HTTP-FLV`
- **播放器**: `flv.js`

这个组合可以将直播延迟控制在 **1-3秒**。

## ✨ 多路流支持

本方案支持**同时接收多个不同设备的推流**，并在同一个网页中展示所有流：

- 📱 支持多台Android设备同时推流
- 🎯 每个设备使用不同的Stream Key
- 🔄 自动检测和显示活跃的流
- 📊 实时显示每路流的统计信息
- 🎨 响应式网格布局，自动适应不同数量的流

## 环境要求

- [Docker](https://www.docker.com/get-started) 和 [Docker Compose](https://docs.docker.com/compose/install/)

## 快速开始

### 1. 获取本机局域网IP地址

你需要知道运行SRS服务器的电脑在局域网中的IP地址。

- **Windows**: 打开命令提示符(CMD)或PowerShell，输入 `ipconfig`，查找你的以太网或Wi-Fi适配器的 "IPv4 地址"。
- **macOS/Linux**: 打开终端，输入 `ifconfig` 或 `ip addr`，查找你的网络接口（如 `en0` 或 `eth0`）的IP地址。

在后续步骤中，我们假设你的IP地址是 `192.168.16.88`。请将其替换为你的实际IP地址。

### 2. 启动SRS服务器

**重要**: 如果你在Windows上使用WSL 2运行Docker，请确保从**WSL终端**执行所有`docker`命令。

在 `streaming` 目录下，运行以下命令启动SRS服务器：

```bash
docker compose up -d
```

#### ⚠️ WSL2 网络配置（Windows用户必读）

如果你在 Windows 上使用 WSL2 运行 Docker，**必须配置端口转发**，否则 Android 设备无法连接到 SRS 服务器。

**快速配置**（以管理员身份运行 PowerShell）：
```powershell
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

详细说明请查看 [`WSL2-Network-Setup.md`](./WSL2-Network-Setup.md)

**验证配置是否成功**：
```powershell
netsh interface portproxy show v4tov4
```

应该看到端口 1935 和 8080 的转发规则。

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

#### 🎯 多设备推流配置

要实现多台设备同时推流，每个设备需要使用**不同的Stream Key**：

- **设备1**: Stream Key = `"device1"` 或 `"phone1"` 或 `"test_stream"`
- **设备2**: Stream Key = `"device2"` 或 `"phone2"` 或 `"camera1"`
- **设备3**: Stream Key = `"device3"` 或 `"tablet1"` 等等

**配置方法**：

1. 在每个设备上修改 `DEFAULT_STREAM_KEY` 为不同的值
2. 或者在App的UI中提供输入框让用户自定义Stream Key

**示例**：
```kotlin
// 设备1
const val DEFAULT_STREAM_KEY = "device1"

// 设备2  
const val DEFAULT_STREAM_KEY = "device2"
```

修改后，重新编译并运行安卓App。点击 "开始直播"，视频流就会被推送到你的SRS服务器。网页会自动检测并显示所有活跃的流。

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

#### 🎬 多流播放页面功能

新的播放页面提供了以下功能：

- **自动检测流**: 页面会自动检测所有活跃的推流并显示
- **网格布局**: 多个视频流以响应式网格方式展示
- **实时统计**: 显示每路流的码率、分辨率、帧率等信息
- **设备信息**: 显示每个流的来源IP地址
- **手动刷新**: 点击"刷新流列表"按钮手动更新
- **自动刷新**: 默认每5秒自动检测新的流，可以随时开启/关闭
- **独立控制**: 每个视频播放器可以独立控制（播放、暂停、音量等）
- **关闭流**: 可以单独关闭不需要观看的流

你应该能看到一个标题为 "SRS 多路低延迟直播" 的页面。当设备开始推流后，视频会自动出现在页面中。

## 停止服务

要停止SRS服务器，请在 `streaming` 目录下运行：

```bash
docker compose down
```

## WSL 网络配置（重要！）

如果你在 **Windows WSL 2** 环境中运行 Docker，需要进行额外的网络配置才能让局域网中的设备（如手机）连接到 SRS 服务器。

你需要**以管理员身份打开 PowerShell**来执行后续的网络配置命令，而不是在WSL终端中。

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

### 基础功能
- 监听 `1935` 端口接收RTMP推流
- 启用 `http_server` 在 `8080` 端口提供HTTP-FLV服务
- 启用 `http_remux` 将RTMP流自动转封装为HTTP-FLV流
- 启用 `http_api` 在 `1985` 端口提供管理API（用于获取流列表）

### 低延迟优化
- **关闭GOP缓存** (`gop_cache off;`) - 实现低延迟的关键配置
- **最小延迟模式** (`min_latency on;`) - 优化播放延迟
- **TCP Nodelay** (`tcp_nodelay on;`) - 减少网络延迟
- **队列长度限制** (`queue_length 10;`) - 控制缓冲区大小

### 多流支持
- 使用 `[vhost]/[app]/[stream].flv` 路径模式，支持任意数量的并发流
- 每个流通过不同的 `[stream]` 参数区分
- 通过HTTP API (`http://localhost:1985/api/v1/streams/`) 可以查询所有活跃的流

### API端点
- **流列表**: `http://localhost:1985/api/v1/streams/` - 获取所有活跃的流
- **服务器状态**: `http://localhost:1985/api/v1/summaries/` - 获取服务器统计信息

## 📝 多设备推流使用示例

### 场景1: 双手机直播

1. **准备两台Android设备**（手机A和手机B）
2. **在手机A上配置**:
   ```kotlin
   const val DEFAULT_STREAM_KEY = "phone_a"
   ```
3. **在手机B上配置**:
   ```kotlin
   const val DEFAULT_STREAM_KEY = "phone_b"
   ```
4. **分别在两台手机上启动App并开始直播**
5. **在浏览器中访问** `http://你的IP:8000`
6. **结果**: 页面会同时显示两路视频流，每个流都有独立的播放控制

### 场景2: 多角度拍摄

使用3台或更多设备从不同角度拍摄同一场景：
- 设备1 (正面): Stream Key = `"front_view"`
- 设备2 (侧面): Stream Key = `"side_view"`  
- 设备3 (俯视): Stream Key = `"top_view"`

所有视频流会在同一网页中同步显示，实现多角度实时监控。

### 场景3: 动态添加/删除设备

- **添加新设备**: 新设备开始推流后，页面会在5秒内自动检测并显示（如果启用了自动刷新）
- **移除设备**: 设备停止推流后，对应的视频会自动从页面中移除
- **手动管理**: 也可以点击每个视频卡片上的"✖ 关闭"按钮手动移除不需要的流

## ⚠️ 注意事项

1. **网络要求**: 所有设备（推流设备和观看设备）必须在同一局域网内
2. **Stream Key唯一性**: 每个设备的Stream Key必须不同，否则会相互覆盖
3. **性能考虑**: 同时推流/播放多路高清视频会消耗较多带宽和资源
   - 建议同时推流不超过4-6路
   - 根据网络情况调整视频分辨率和码率
4. **浏览器兼容性**: 建议使用最新版本的Chrome、Edge或Firefox浏览器
5. **CORS设置**: SRS配置中已启用CORS，允许跨域访问
