# WSL2 端口转发配置脚本
# 此脚本需要以管理员权限运行

# 获取 WSL2 的 IP 地址
$wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]

Write-Host "WSL2 IP 地址: $wslIp" -ForegroundColor Green

# 需要转发的端口
$ports = @(
    1935,  # RTMP
    8080,  # HTTP-FLV
    1985   # HTTP API
)

# 删除现有的端口转发规则（如果存在）
Write-Host "`n清理现有端口转发规则..." -ForegroundColor Yellow
foreach ($port in $ports) {
    try {
        netsh interface portproxy delete v4tov4 listenport=$port listenaddress=0.0.0.0 2>$null
        Write-Host "  - 已删除端口 $port 的旧规则" -ForegroundColor Gray
    } catch {
        # 忽略错误（规则可能不存在）
    }
}

# 添加新的端口转发规则
Write-Host "`n添加新的端口转发规则..." -ForegroundColor Yellow
foreach ($port in $ports) {
    try {
        netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIp
        Write-Host "  ✓ 端口 $port : 0.0.0.0:$port -> $wslIp:$port" -ForegroundColor Green
    } catch {
        Write-Host "  ✗ 端口 $port 转发设置失败: $_" -ForegroundColor Red
    }
}

# 配置 Windows 防火墙规则
Write-Host "`n配置防火墙规则..." -ForegroundColor Yellow
$ruleName = "WSL2 SRS Streaming Ports"

# 删除现有规则
try {
    Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue 2>$null
    Write-Host "  - 已删除旧防火墙规则" -ForegroundColor Gray
} catch {
    # 忽略错误
}

# 添加新规则
try {
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080,1985 | Out-Null
    Write-Host "  ✓ 防火墙规则已添加" -ForegroundColor Green
} catch {
    Write-Host "  ✗ 防火墙规则添加失败: $_" -ForegroundColor Red
}

# 显示当前配置
Write-Host "`n当前端口转发配置:" -ForegroundColor Cyan
netsh interface portproxy show v4tov4

Write-Host "`n配置完成！" -ForegroundColor Green
Write-Host "现在可以使用以下地址推流:" -ForegroundColor Yellow
Write-Host "  RTMP URL: rtmp://192.168.16.88:1935/thinklet.squid.run" -ForegroundColor White
Write-Host "  Stream Key: test_stream" -ForegroundColor White
Write-Host "`n观看页面:" -ForegroundColor Yellow
Write-Host "  http://192.168.16.88:8080" -ForegroundColor White






