# WSL2 Port Forwarding Configuration Script
# This script requires administrator privileges to run.

# Get the IP address of WSL2
$wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]

Write-Host "WSL2 IP Address: $wslIp" -ForegroundColor Green

# Ports to be forwarded
$ports = @(
    1935,  # RTMP
    8080,  # HTTP-FLV
    1985   # HTTP API
)

# Delete existing port forwarding rules (if they exist)
Write-Host "`nCleaning up existing port forwarding rules..." -ForegroundColor Yellow
foreach ($port in $ports) {
    try {
        netsh interface portproxy delete v4tov4 listenport=$port listenaddress=0.0.0.0 2>$null
        Write-Host "  - Deleted old rule for port $port" -ForegroundColor Gray
    } catch {
        # Ignore errors (the rule may not exist)
    }
}

# Add new port forwarding rules
Write-Host "`nAdding new port forwarding rules..." -ForegroundColor Yellow
foreach ($port in $ports) {
    try {
        netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIp
        Write-Host "  ✓ Port $port : 0.0.0.0:$port -> $wslIp:$port" -ForegroundColor Green
    } catch {
        Write-Host "  ✗ Port $port forwarding setup failed: $_" -ForegroundColor Red
    }
}

# Configure Windows Firewall rules
Write-Host "`nConfiguring firewall rules..." -ForegroundColor Yellow
$ruleName = "WSL2 SRS Streaming Ports"

# Delete existing rule
try {
    Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue 2>$null
    Write-Host "  - Deleted old firewall rule" -ForegroundColor Gray
} catch {
    # Ignore errors
}

# Add new rule
try {
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080,1985 | Out-Null
    Write-Host "  ✓ Firewall rule added" -ForegroundColor Green
} catch {
    Write-Host "  ✗ Firewall rule addition failed: $_" -ForegroundColor Red
}

# Display current configuration
Write-Host "`nCurrent port forwarding configuration:" -ForegroundColor Cyan
netsh interface portproxy show v4tov4

Write-Host "`nConfiguration complete!" -ForegroundColor Green
Write-Host "You can now stream using the following address:" -ForegroundColor Yellow
Write-Host "  RTMP URL: rtmp://192.168.16.88:1935/thinklet.squid.run" -ForegroundColor White
Write-Host "  Stream Key: test_stream" -ForegroundColor White
Write-Host "`nWatch page:" -ForegroundColor Yellow
Write-Host "  http://192.168.16.88:8080" -ForegroundColor White






