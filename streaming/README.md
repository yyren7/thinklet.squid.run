# Thinklet Streaming 文档导航

欢迎使用 Thinklet 流媒体系统！本文档将帮助你快速找到所需的信息。

## 🚀 快速开始

如果你是第一次使用，请按以下顺序阅读：

1. **[QUICK-START.md](./docs/QUICK-START.md)** ⭐ 最推荐
   - 3 步即可启动流媒体环境
   - 包含环境要求清单
   - 使用 `Start-Streaming-Auto.bat` 一键启动

2. **[README-streaming.md](./docs/README-streaming.md)**
   - 详细的技术文档
   - 完整的功能说明
   - 高级配置选项

## 📚 文档索引

### 基础配置

- **[QUICK-START.md](./docs/QUICK-START.md)**  
  快速启动指南，推荐所有用户首先阅读

- **[README-streaming.md](./docs/README-streaming.md)**  
  完整的流媒体系统文档，包括：
  - Rancher Desktop 配置步骤
  - 多设备流媒体支持
  - SRS 服务器配置详解
  - 故障排查指南

### 设备配置

- **[ANDROID-CONNECTION-GUIDE.md](./docs/ANDROID-CONNECTION-GUIDE.md)**  
  Android 设备连接配置指南，包括：
  - RTMP URL 配置
  - 网络诊断步骤
  - 常见连接问题解决方案

### 文件传输

- **[FILE-TRANSFER-README.md](./docs/FILE-TRANSFER-README.md)**  
  录像文件自动传输功能使用指南，包括：
  - 极低功耗的文件传输方案
  - 自动下载和 MD5 校验
  - Web 界面操作说明

- **[file-transfer-design.md](./docs/file-transfer-design.md)**  
  文件传输功能的技术设计文档（开发者参考）

## 📂 文件夹结构

```
streaming/
├── 📄 README.md                      # 主导航文档（本文档）
├── 📄 package.json                   # Node.js 项目配置
├── 📄 package-lock.json              # 依赖锁定文件
│
├── 🚀 Start-Streaming-Auto.bat       # 一键启动脚本 ⭐
├── 🔧 start-streaming-environment.ps1 # PowerShell 核心脚本
│
├── 📂 docs/                          # 文档目录
│   ├── QUICK-START.md               # 快速开始指南 ⭐
│   ├── README-streaming.md          # 详细技术文档
│   ├── ANDROID-CONNECTION-GUIDE.md  # Android 配置指南
│   ├── FILE-TRANSFER-README.md      # 文件传输功能文档
│   └── file-transfer-design.md      # 技术设计文档
│
├── 📂 src/                           # 源代码目录
│   ├── simple-http-server.js        # HTTP/WebSocket 服务器
│   ├── file-transfer-service.js     # 文件传输服务
│   ├── i18n.js                      # 国际化
│   └── main.js                      # 前端主脚本
│
├── 📂 public/                        # Web 资源目录
│   ├── index.html                   # Web 界面
│   └── style.css                    # 样式表
│
├── 📂 config/                        # 配置文件目录
│   ├── docker-compose.yml           # Docker Compose 配置
│   ├── srs.conf                     # SRS 服务器配置
│   └── devices.json                 # 设备信息存储（自动生成）
│
├── 📂 video/                         # 录像文件存储目录
│   └── {streamKey}_{deviceId}/      # 按设备分组的录像
│
└── 📂 node_modules/                  # Node.js 依赖（自动生成）
```

## 🔧 核心脚本

### Windows 启动脚本

- **`Start-Streaming-Auto.bat`** ⭐ 推荐使用
  - 自动请求管理员权限
  - 一键启动所有服务
  - 自动配置网络和防火墙

- **`start-streaming-environment.ps1`**
  - PowerShell 核心脚本
  - 由 `.bat` 文件自动调用
  - 处理所有网络配置和服务启动

## ⚙️ 环境要求

### 必备软件

1. **Rancher Desktop** (推荐) 或 **Docker Desktop**
   - 下载地址：https://rancherdesktop.io/
   - ⚠️ 重要：不要在 WSL 内手动安装 Docker

2. **WSL 2** (Windows Subsystem for Linux 2)
   - 需要安装 Ubuntu 或其他 Linux 发行版
   - 使用 `wsl -l -v` 检查版本

3. **Node.js** (v16 或更高版本)
   - 下载地址：https://nodejs.org/

### Rancher Desktop 配置（关键步骤）

1. 打开 Rancher Desktop
2. 进入 **Preferences → WSL**
3. ✅ 勾选你的 WSL 发行版（如 `Ubuntu`）
4. ❌ 不要勾选 `rancher-desktop` 或 `rancher-desktop-data`
5. 保存并等待重启

### 验证配置

在 Ubuntu WSL 终端中运行：
```bash
docker --version
```

应该看到：
```
Docker version 28.3.3-rd, build 309deef
```

注意 `-rd` 后缀，表示使用的是 Rancher Desktop 提供的 Docker。

## 🎯 使用流程

### 1. 首次安装

```bash
# 在 streaming 目录下
npm install
```

### 2. 启动服务

双击 `Start-Streaming-Auto.bat`，点击"是"授予管理员权限。

### 3. 配置 Android 应用

在 `app/src/main/java/ai/fd/thinklet/app/squid/run/DefaultConfig.kt` 中配置：

```kotlin
const val DEFAULT_STREAM_URL = "rtmp://YOUR-PC-IP:1935/thinklet.squid.run"
const val DEFAULT_STREAM_KEY = "device1"
```

### 4. 开始流媒体

在 Android 应用中点击"开始推流"，然后在浏览器中访问：
```
http://YOUR-PC-IP:8000
```

## 🔍 故障排查快速参考

| 问题 | 解决方案 |
|------|---------|
| Docker 命令找不到 | 检查 Rancher Desktop WSL 集成配置 |
| rancher-desktop 错误提示 | 使用 `Start-Streaming-Auto.bat` 启动 |
| Android 无法连接 | 确保 PC 和手机在同一 Wi-Fi 网络 |
| 重启后无法连接 | 重新运行 `Start-Streaming-Auto.bat` |

详细的故障排查步骤请查看各文档的"Troubleshooting"章节。

## 📊 功能特性

### 流媒体功能
- 🎥 低延迟直播（1-3秒）
- 📱 多设备同时推流
- 🎬 实时视频预览
- 📊 流状态监控

### 文件传输功能
- 🔋 极低功耗（安卓端 CPU < 5%）
- 🔄 断点续传
- 🔐 MD5 完整性校验
- 📊 实时进度显示
- ⚙️ 自动重试机制

## 🆘 获取帮助

如果遇到问题：

1. 查看 [QUICK-START.md](./docs/QUICK-START.md) 的故障排查部分
2. 查看 [README-streaming.md](./docs/README-streaming.md) 的详细故障排查
3. 检查终端和浏览器控制台的日志输出
4. 确认所有环境要求都已满足

## 📝 文档更新日志

### 2025-10-06
- ✅ 重组文件夹结构（docs/, src/, public/, config/）
- ✅ 删除过时的手动配置文档
- ✅ 强调 Rancher Desktop 作为标准 Docker 环境
- ✅ 更新所有文档以反映自动化启动脚本
- ✅ 添加详细的环境配置验证步骤
- ✅ 更新所有路径引用和依赖关系

---

**开始使用**：直接阅读 [QUICK-START.md](./docs/QUICK-START.md) 即可！🚀

