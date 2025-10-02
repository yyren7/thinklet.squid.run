# Quick Fix Guide for WSL2 RTMP Connection Timeout

## Symptoms

Android app logs show:
```
RtmpClient: connection error
io.ktor.utils.io.ClosedByteChannelException
Caused by: java.net.SocketTimeoutException
```

The RTMP handshake times out when reading S0 (approximately 5 seconds).

## Root Cause

WSL2 uses a NAT network, which prevents Android devices from directly accessing Docker container ports within WSL2. Windows port forwarding needs to be configured.

## One-Click Fix (Recommended)

### Step 1: Run PowerShell as Administrator

Right-click the PowerShell icon → "Run as administrator"

### Step 2: Run the Configuration Script

```powershell
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

### Step 3: Verify the Configuration

```powershell
.\test-connection.ps1
```

You should see all tests display "OK".

## Manual Fix (Alternative)

If the automatic script fails, perform the following steps manually:

```powershell
# Get WSL2 IP
$wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]

# Add port forwarding
netsh interface portproxy add v4tov4 listenport=1935 listenaddress=0.0.0.0 connectport=1935 connectaddress=$wslIp
netsh interface portproxy add v4tov4 listenport=8080 listenaddress=0.0.0.0 connectport=8080 connectaddress=$wslIp

# Add firewall rule
New-NetFirewallRule -DisplayName "WSL2 SRS Streaming" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935,8080
```

## Verify Connection

### Check Port Forwarding

```powershell
netsh interface portproxy show v4tov4
```

It should display:
```
Listen on ipv4:             Connect to ipv4:
Address         Port        Address         Port
--------------- ----------  --------------- ----------
0.0.0.0         1935        172.26.xxx.xxx  1935
0.0.0.0         8080        172.26.xxx.xxx  8080
```

### Test RTMP Connection

```powershell
Test-NetConnection -ComputerName 192.168.16.88 -Port 1935
```

## Notes on Restarting

⚠️ **Important**: The WSL2 IP address may change after a restart!

If you cannot connect after a restart, re-run the configuration script:
```powershell
cd C:\Users\J100052060\thinklet.squid.run\streaming
.\setup-wsl2-port-forwarding.ps1
```

## FAQ

### Q: Why are administrator privileges required?
A: Configuring port forwarding and firewall rules requires system-level permissions.

### Q: Will the configuration be lost after a restart?
A: The port forwarding rules will be retained, but the WSL2 IP may change, requiring reconfiguration.

### Q: Can I set it to configure automatically on startup?
A: You can create a scheduled task to run the configuration script automatically at system startup.

### Q: How do I undo the configuration?
A: 
```powershell
netsh interface portproxy delete v4tov4 listenport=1935 listenaddress=0.0.0.0
netsh interface portproxy delete v4tov4 listenport=8080 listenaddress=0.0.0.0
Remove-NetFirewallRule -DisplayName "WSL2 SRS Streaming Ports"
```

## You can now start streaming!

After configuration, on your Android device:
1. Ensure the device and computer are on the same local network
2. Use the following configuration:
   - RTMP URL: `rtmp://192.168.16.88:1935/thinklet.squid.run`
   - Stream Key: `test_stream`
3. Click "Start Live"

To watch the live stream:
- Open a browser and go to `http://192.168.16.88:8080`
- Or run the HTTP server: `node simple-http-server.js`, then visit `http://192.168.16.88:3000`





