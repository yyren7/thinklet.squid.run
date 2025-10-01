# 直播功能文档索引

本目录包含将RTMP直播功能集成到CameraX应用的完整文档。

## 文档列表

### 1. [CameraXSource 集成指南](./CameraXSource_Integration_Guide.md) 🎯 ⭐

**适合**: 想要了解本项目如何使用 CameraXSource 进行 RTMP 直播的开发者

**内容**:
- CameraXSource 核心优势
- 架构概述和设计思路
- 核心依赖和权限配置
- 完整实现步骤
- MainViewModel 完整示例
- 与其他方案对比
- 配置参数详解
- 常见问题解答

**阅读时间**: 约20分钟

---

### 2. [原始项目代码](./Original_Project_Code.md) 💻

**适合**: 需要查看实际项目代码的开发者

**内容**:
- MainActivity.kt - 完整的 Activity 实现
- MainViewModel.kt - 使用 CameraXSource 的 ViewModel
- StandardMicrophoneSource.kt - 标准音频源
- ThinkletMicrophoneSource.kt - Thinklet 音频源
- ConfigHelper.kt - 配置助手类
- ConfigManager.kt - 配置管理器
- DefaultConfig.kt - 默认配置
- PermissionHelper.kt - 权限管理

**特点**: 所有代码都与实际项目一致

**阅读时间**: 约30分钟

---

### 3. [配置和最佳实践](./Streaming_Best_Practices.md) ⚙️

**适合**: 需要优化性能和解决问题的开发者

**内容**:
- 视频参数配置（分辨率、比特率、帧率）
- 音频参数配置（采样率、比特率、声道）
- 预设配置方案（高质量/标准/省电）
- 性能优化技巧（6个方面）
- 错误处理和自动重连
- 常见问题解答（6个问题）
- 测试指南
- 调试技巧

**阅读时间**: 约30分钟

---

## 推荐阅读顺序

### 第一次接触项目

1. 先阅读 [CameraXSource集成指南](./CameraXSource_Integration_Guide.md) 了解整体架构
2. 浏览 [原始项目代码](./Original_Project_Code.md) 了解实现细节
3. 参考 [配置和最佳实践](./Streaming_Best_Practices.md) 进行配置

### 已经熟悉项目

- 需要代码 → [原始项目代码](./Original_Project_Code.md)
- 遇到问题 → [配置和最佳实践](./Streaming_Best_Practices.md) 的"常见问题"章节
- 性能优化 → [配置和最佳实践](./Streaming_Best_Practices.md) 的"性能优化"章节

## 快速查找

### 按功能查找

| 需求 | 文档 | 章节 |
|------|------|------|
| 了解 CameraXSource 集成 | CameraXSource 集成指南 | 完整实现步骤 |
| 查看 MainViewModel 代码 | 原始项目代码 | MainViewModel |
| 配置视频参数 | 配置和最佳实践 | 配置参数详解 > 视频配置 |
| 降低延迟 | 配置和最佳实践 | 常见问题 > Q2 |
| 优化性能 | 配置和最佳实践 | 性能优化 |

### 按问题查找

| 问题 | 解决方案位置 |
|------|------------|
| 画面卡顿 | 配置和最佳实践 > 常见问题 > Q1 |
| 延迟太高 | 配置和最佳实践 > 常见问题 > Q2 |
| 音视频不同步 | 配置和最佳实践 > 常见问题 > Q3 |
| 切换摄像头崩溃 | 配置和最佳实践 > 常见问题 > Q4 |
| 内存泄漏 | 配置和最佳实践 > 常见问题 > Q5 |
| UseCase绑定失败 | 配置和最佳实践 > 常见问题 > Q6 |

## 相关资源

### 外部文档

- [RootEncoder GitHub](https://github.com/pedroSG94/RootEncoder)
- [CameraX官方文档](https://developer.android.com/training/camerax)
- [SRS官方网站](https://ossrs.io)

### 项目文档

- [SRS服务器配置](../streaming/README-streaming.md)
- [项目主README](../README_CN.md)

## 贡献文档

如果发现文档中的错误或有改进建议，欢迎：

1. 提交Issue
2. 提交Pull Request
3. 联系维护者

## 文档更新记录

| 日期 | 版本 | 更新内容 |
|------|------|---------|
| 2025-09-30 | 1.0 | 初始版本，包含三个核心文档 |

---

**提示**: 所有文档都使用Markdown格式，支持目录跳转和代码高亮。建议使用支持Markdown预览的编辑器阅读。

