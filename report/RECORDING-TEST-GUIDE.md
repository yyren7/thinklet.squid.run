# 录像功能测试指南

## 准备工作

1. **编译并安装应用**
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. **启动日志捕获**
   ```bash
   adb logcat -c  # 清除旧日志
   adb logcat | grep "MainViewModel\|LedController" > recording_test_log.txt
   ```

3. **确保设备准备就绪**
   - 相机权限已授予
   - 存储权限已授予
   - 网络已连接（用于测试网络命令）
   - 电量充足（至少50%）

## 测试场景

### 场景1：基本录像功能

**目的**：验证正常的录像开始和停止流程

**步骤**：
1. 点击UI上的"开始录像"按钮
2. 观察设备LED灯
3. 等待5秒
4. 点击"停止录像"按钮
5. 观察LED灯

**预期结果**：
- ✅ 点击开始后，LED在短暂延迟（<1秒）后开始闪烁
- ✅ LED闪烁频率稳定（每500ms切换一次）
- ✅ 点击停止后，LED立即停止闪烁并熄灭
- ✅ 录像文件已生成且可以正常播放

**日志关键字**：
```
📹 Recording start requested
🔒 Recording operation lock acquired
✅ Recording STARTED successfully
💡 LED blinking started
⏹️ Recording stop requested
💡 LED blinking stopped
```

---

### 场景2：快速连续点击（并发控制测试）

**目的**：验证操作锁是否有效防止并发问题

**步骤**：
1. 快速连续点击"开始录像"按钮5次（尽可能快）
2. 观察LED和日志
3. 等待2秒
4. 点击"停止录像"一次

**预期结果**：
- ✅ LED只闪烁一次（不会出现多次启动/停止）
- ✅ 只生成一个录像文件
- ✅ 日志中有 "⚠️ Recording operation already in progress" 警告
- ✅ 录像文件完整且可播放

**日志检查**：
```bash
grep "⚠️ Recording operation already in progress" recording_test_log.txt
# 应该至少有4条（5次点击，1次成功，4次被拒绝）
```

---

### 场景3：物理按钮测试

**目的**：验证物理相机按钮是否正常工作

**步骤**：
1. 按下设备的物理相机按钮
2. 观察LED
3. 等待5秒
4. 再次按下物理按钮（toggle停止）
5. 观察LED

**预期结果**：
- ✅ 第一次按下：LED开始闪烁
- ✅ 第二次按下：LED停止闪烁
- ✅ 录像文件正常生成

**注意**：物理按钮使用toggle模式，第一次按下开始，第二次按下停止

---

### 场景4：网络命令测试

**目的**：验证网络命令控制是否正常

**准备**：
1. 确保设备已连接到电脑的WebSocket服务器
2. 使用测试脚本发送命令

**测试脚本** (test_recording_commands.py)：
```python
import asyncio
import websockets
import json

async def test_recording():
    uri = "ws://192.168.16.88:8000"  # 修改为你的服务器地址
    
    async with websockets.connect(uri) as websocket:
        # 等待设备连接
        await asyncio.sleep(2)
        
        print("发送开始录像命令...")
        await websocket.send(json.dumps({"command": "startRecording"}))
        await asyncio.sleep(5)
        
        print("发送停止录像命令...")
        await websocket.send(json.dumps({"command": "stopRecording"}))
        
        # 接收设备状态更新
        while True:
            try:
                message = await asyncio.wait_for(websocket.recv(), timeout=1.0)
                status = json.loads(message)
                print(f"设备状态: isRecording={status['status']['isRecording']}")
            except asyncio.TimeoutError:
                break

asyncio.run(test_recording())
```

**预期结果**：
- ✅ 收到startRecording命令后，LED开始闪烁
- ✅ 收到stopRecording命令后，LED停止闪烁
- ✅ 设备状态更新中 `isRecording` 字段正确

---

### 场景5：多入口并发测试（关键测试）

**目的**：验证不同入口同时触发时的冲突处理

**步骤**：
1. 准备好网络命令脚本
2. 同时执行：
   - 按下物理按钮
   - 发送网络命令 `startRecording`
   - 点击UI按钮
3. 观察LED和日志
4. 等待3秒
5. 同时执行停止操作

**预期结果**：
- ✅ LED只闪烁一次（不会出现混乱）
- ✅ 只生成一个录像文件
- ✅ 日志中有多条 "⚠️" 警告，表示重复请求被拒绝
- ✅ 录像文件完整且可播放

**日志分析**：
```bash
# 统计被拒绝的请求数量
grep "⚠️ Recording operation already in progress\|⚠️ Recording is already active" recording_test_log.txt | wc -l
# 应该至少有2条（3个请求，1个成功，2个被拒绝）
```

---

### 场景6：录像失败场景

**目的**：验证错误处理是否正确

**步骤A：权限被拒**
1. 撤销存储权限：`adb shell pm revoke ai.fd.thinklet.app.squid.run android.permission.WRITE_EXTERNAL_STORAGE`
2. 尝试开始录像
3. 观察LED和错误提示

**预期结果**：
- ✅ LED不会启动闪烁
- ✅ 显示错误提示
- ✅ 日志中有 "❌" 错误信息

**步骤B：相机被占用**
1. 打开另一个相机应用
2. 尝试在我们的应用中开始录像
3. 观察行为

**预期结果**：
- ✅ LED不会启动
- ✅ 显示相机不可用的错误

---

### 场景7：压力测试

**目的**：验证系统在高频操作下的稳定性

**步骤**：
```python
# pressure_test.py
import asyncio
import websockets
import json

async def pressure_test():
    uri = "ws://192.168.16.88:8000"
    
    async with websockets.connect(uri) as websocket:
        for i in range(100):
            print(f"测试轮次 {i+1}/100")
            
            # 快速发送多次开始命令
            for _ in range(5):
                await websocket.send(json.dumps({"command": "startRecording"}))
                await asyncio.sleep(0.05)
            
            await asyncio.sleep(2)
            
            # 发送停止命令
            await websocket.send(json.dumps({"command": "stopRecording"}))
            await asyncio.sleep(1)

asyncio.run(pressure_test())
```

**预期结果**：
- ✅ 应用不会崩溃
- ✅ 生成100个完整的录像文件
- ✅ LED状态始终正常
- ✅ 没有文件损坏

---

### 场景8：LED状态确认测试

**目的**：专门测试LED"只亮不暗"的问题是否已修复

**步骤**：
1. 开始录像
2. 用手机拍摄LED灯（慢动作模式）
3. 观察LED是否严格按照 500ms 亮/暗切换
4. 停止录像
5. 确认LED完全熄灭

**预期结果**：
- ✅ LED闪烁频率稳定（500ms亮，500ms暗）
- ✅ 停止后LED完全熄灭（不会停在亮的状态）
- ✅ 100ms延迟确认机制生效（日志中可以看到两次关闭LED的调用）

**日志确认**：
```bash
# 查看LED停止日志
grep "💡 LED blinking stopped" recording_test_log.txt
# 应该能看到停止命令后，100ms后还有一次确认关闭
```

---

## 日志分析

### 正常流程日志模板

```
I MainViewModel: 📹 Recording start requested. Current state: isRecording=false, operationInProgress=false
D MainViewModel: 🔒 Recording operation lock acquired
D MainViewModel: 🎬 startRecordingInternal: Initiating recording
I MainViewModel: 📹 Recording API call successful, waiting for STARTED callback
D MainViewModel: 📹 Recording status changed: STARTED
I MainViewModel: ✅ Recording STARTED successfully
D MainViewModel: 💡 LED blinking started
D MainViewModel: 🔓 Recording operation lock released, result=true

[... 录像进行中 ...]

I MainViewModel: ⏹️ Recording stop requested. Current state: isRecording=true, operationInProgress=false
I MainViewModel: 🛑 Stopping recording...
D MainViewModel: 📹 Recording status changed: STOPPED
I MainViewModel: ⏹️ Recording STOPPED
D MainViewModel: 💡 LED blinking stopped
```

### 并发拒绝日志模板

```
I MainViewModel: 📹 Recording start requested. Current state: isRecording=false, operationInProgress=true
W MainViewModel: ⚠️ Recording operation already in progress, ignoring request
```

### 错误日志模板

```
I MainViewModel: 📹 Recording start requested. Current state: isRecording=false, operationInProgress=false
D MainViewModel: 🔒 Recording operation lock acquired
D MainViewModel: 🎬 startRecordingInternal: Initiating recording
E MainViewModel: ❌ Recording failed: Stream not ready
D MainViewModel: 🔓 Recording operation lock released, result=false
```

---

## 测试检查清单

- [ ] 场景1：基本录像功能
- [ ] 场景2：快速连续点击
- [ ] 场景3：物理按钮测试
- [ ] 场景4：网络命令测试
- [ ] 场景5：多入口并发测试
- [ ] 场景6：录像失败场景
- [ ] 场景7：压力测试（100轮）
- [ ] 场景8：LED状态确认

## 问题报告模板

如果发现问题，请按以下格式报告：

```markdown
### 问题描述
[简要描述问题]

### 复现步骤
1. ...
2. ...
3. ...

### 预期结果
[应该发生什么]

### 实际结果
[实际发生了什么]

### 日志片段
```
[粘贴相关日志]
```

### 环境信息
- 设备型号：
- Android版本：
- 应用版本：
- 测试时间：
```

---

## 验收标准

修复被认为成功，当且仅当：

1. ✅ **所有8个测试场景通过**
2. ✅ **压力测试（100轮）无崩溃，无文件损坏**
3. ✅ **LED"只亮不暗"问题不再出现**
4. ✅ **多入口并发测试无状态混乱**
5. ✅ **日志中所有操作都有明确的开始和结束标记**

---

## 常见问题排查

### Q: LED还是会偶尔"只亮不暗"
**A**: 检查以下几点：
1. 查看日志确认 `stopLedBlinking()` 是否被调用
2. 确认100ms延迟确认是否执行
3. 检查是否有其他地方也在控制LED

### Q: 录像文件损坏
**A**: 检查：
1. 是否有多个录像会话同时运行（日志中搜索"⚠️"）
2. 存储空间是否充足
3. MD5计算是否完成（等待2秒的延迟是否足够）

### Q: 并发测试时应用崩溃
**A**: 
1. 查看崩溃日志 `adb logcat | grep "FATAL"`
2. 检查是否有资源泄漏
3. 验证操作锁是否正确释放

---

## 后续优化建议

1. **添加单元测试**：为录像逻辑编写单元测试
2. **性能监控**：添加录像性能指标（帧率、比特率等）
3. **用户通知**：在并发请求被拒绝时，给用户明确的UI反馈
4. **自动化测试**：将上述场景编写为自动化测试脚本

