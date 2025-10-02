# WSL2 Network Configuration Guide

## The Problem

When Docker runs in WSL2, Android devices cannot directly access Docker container ports within WSL2 because WSL2 uses NAT networking. Therefore, Windows port forwarding needs to be configured.

## Quick Configuration (Recommended)

### Method 1: Using the Automated Configuration Script

1.  **Run PowerShell as Administrator**
    -   Right-click the PowerShell icon
    -   Select "Run as administrator"

2.  **Navigate to the project directory and run the script**
    ```powershell
    cd C:\Users\J100052060\thinklet.squid.run\streaming
    .\setup-wsl2-port-forwarding.ps1
    ```

3.  **Verify the configuration**
    ```powershell
    netsh interface portproxy show v4tov4
    ```

### Method 2: Manual Configuration

If the automated script fails, you can manually execute the following commands (requires administrator privileges):

```powershell
# Get WSL2 IP Address
$wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]
Write-Host "WSL2 IP: $wslIp"

# Add port forwarding rules
netsh interface portproxy add v4tov4 listenport=1935 listenaddress=0.0.0.0 connectport=1935 connectaddress=$wslIp
netsh interface portproxy add v4tov4 listenport=8080 listenaddress=0.0.0.0 connectport=8080 connectaddress=$wslIp
netsh interface portproxy add v4tov4 listenport=1985 listenaddress=0.0.0.0 connectport=1985 connectaddress=$wslIp

# Add firewall rule
New-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080,1985
```

## Verifying the Configuration

### 1. Check Port Forwarding

```powershell
netsh interface portproxy show v4tov4
```

You should see output similar to this:
```
Listen on ipv4:             Connect to ipv4:

Address         Port        Address         Port
--------------- ----------  --------------- ----------
0.0.0.0         1935        172.26.xxx.xxx  1935
0.0.0.0         8080        172.26.xxx.xxx  8080
0.0.0.0         1985        172.26.xxx.xxx  1985
```

### 2. Check Firewall Rule

```powershell
Get-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports"
```

### 3. Test SRS Server Connection

On your Android device or another device on the same local network, visit:
```
http://192.168.16.88:8080
```

You should see the default SRS page.

## Cleaning Up the Configuration

If you need to remove the port forwarding rules:

```powershell
# Delete port forwarding
netsh interface portproxy delete v4tov4 listenport=1935 listenaddress=0.0.0.0
netsh interface portproxy delete v4tov4 listenport=8080 listenaddress=0.0.0.0
netsh interface portproxy delete v4tov4 listenport=1985 listenaddress=0.0.0.0

# Delete firewall rule
Remove-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports"
```

## Notes on Rebooting

**Important**: The WSL2 IP address may change after a reboot!

If you cannot connect after restarting your computer, please re-run the configuration script:

```powershell
# Run as administrator
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

## Troubleshooting

### Issue: Android device cannot connect

1.  **Check Windows Host IP**
    ```powershell
    ipconfig | findstr "IPv4"
    ```
    Confirm that `192.168.16.88` is the correct local network IP.

2.  **Check WSL2 IP**
    ```powershell
    wsl -e bash -c "hostname -I"
    ```

3.  **Check SRS Server Status**
    ```bash
    wsl -e bash -c "cd /mnt/c/Users/J100052060/thinklet.squid.run/streaming && docker compose ps"
    ```

4.  **Check Firewall**
    -   Ensure Windows Firewall allows inbound connections.
    -   If using a third-party firewall, add an exception.

5.  **Test Local Connection**
    ```powershell
    Test-NetConnection -ComputerName 192.168.16.88 -Port 1935
    ```

### Issue: Port is already in use

If a port is occupied by another program, find the process using it:

```powershell
netstat -ano | findstr :1935
```

Then terminate the process or change the SRS port.

## Alternative: Using Host Network Mode

If port forwarding is still problematic, you can modify the Docker Compose configuration to use host network mode:

```yaml
# docker-compose.yml
services:
  srs:
    image: ossrs/srs:5
    network_mode: host
    volumes:
      - ./srs.conf:/usr/local/srs/conf/srs.conf
```

**Note**: Host network mode may not be fully supported on Windows WSL2.

## Current Configuration Status

✅ **Port 1935** (RTMP): Configured
✅ **Port 8080** (HTTP-FLV): Configured
⚠️ **Port 1985** (HTTP API): Needs to be added manually (optional)

You can now use the following configuration to stream:
- **RTMP URL**: `rtmp://192.168.16.88:1935/thinklet.squid.run`
- **Stream Key**: `test_stream`
- **Full Address**: `rtmp://192.168.16.88:1935/thinklet.squid.run/test_stream`






