# Test SRS Server Connection
# This script verifies port forwarding and server configuration

Write-Host "=== SRS Server Connection Test ===" -ForegroundColor Cyan
Write-Host ""

# 1. Check Windows Host IP
Write-Host "1. Checking Windows Host IP..." -ForegroundColor Yellow
$windowsIp = (ipconfig | Select-String "IPv4" | Select-Object -First 1).ToString()
Write-Host "   $windowsIp" -ForegroundColor White

# 2. Check WSL2 IP
Write-Host ""
Write-Host "2. Checking WSL2 IP..." -ForegroundColor Yellow
try {
    $wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]
    Write-Host "   WSL2 IP: $wslIp" -ForegroundColor Green
} catch {
    Write-Host "   X Cannot get WSL2 IP: $_" -ForegroundColor Red
}

# 3. Check Port Forwarding Configuration
Write-Host ""
Write-Host "3. Checking Port Forwarding..." -ForegroundColor Yellow
$portProxy = netsh interface portproxy show v4tov4
if ($portProxy -match "1935") {
    Write-Host "   OK Port 1935 (RTMP) configured" -ForegroundColor Green
} else {
    Write-Host "   X Port 1935 (RTMP) not configured" -ForegroundColor Red
}
if ($portProxy -match "8080") {
    Write-Host "   OK Port 8080 (HTTP-FLV) configured" -ForegroundColor Green
} else {
    Write-Host "   X Port 8080 (HTTP-FLV) not configured" -ForegroundColor Red
}

# 4. Check SRS Server Status
Write-Host ""
Write-Host "4. Checking SRS Server Status..." -ForegroundColor Yellow
try {
    $srsStatus = wsl -e bash -c "cd /mnt/c/Users/J100052060/thinklet.squid.run/streaming && docker compose ps"
    if ($srsStatus -match "Up") {
        Write-Host "   OK SRS server is running" -ForegroundColor Green
    } else {
        Write-Host "   X SRS server is not running" -ForegroundColor Red
    }
} catch {
    Write-Host "   ! Cannot check SRS status" -ForegroundColor Yellow
}

# 5. Test HTTP-FLV Port
Write-Host ""
Write-Host "5. Testing HTTP-FLV Port..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8080" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "   OK HTTP-FLV (8080) accessible" -ForegroundColor Green
} catch {
    Write-Host "   X HTTP-FLV (8080) not accessible" -ForegroundColor Red
}

# 6. Test RTMP Port
Write-Host ""
Write-Host "6. Testing RTMP Port..." -ForegroundColor Yellow
$tcpClient = New-Object System.Net.Sockets.TcpClient
try {
    $tcpClient.Connect("localhost", 1935)
    if ($tcpClient.Connected) {
        Write-Host "   OK RTMP (1935) port connectable" -ForegroundColor Green
        $tcpClient.Close()
    }
} catch {
    Write-Host "   X RTMP (1935) port not connectable" -ForegroundColor Red
} finally {
    $tcpClient.Dispose()
}

# Summary
Write-Host ""
Write-Host "=== Test Complete ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Streaming Configuration:" -ForegroundColor Yellow
Write-Host "  RTMP URL: rtmp://192.168.16.88:1935/thinklet.squid.run" -ForegroundColor White
Write-Host "  Stream Key: test_stream" -ForegroundColor White
Write-Host "  Full Address: rtmp://192.168.16.88:1935/thinklet.squid.run/test_stream" -ForegroundColor White
Write-Host ""
Write-Host "If all tests passed, you can start streaming from Android device now!" -ForegroundColor Green