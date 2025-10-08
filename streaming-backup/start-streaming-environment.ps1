# =============================================================================
# One-Click Streaming Environment Setup
#
# This script prepares the entire environment for streaming:
# 1. Self-elevates to Administrator privileges.
# 2. Configures WSL2 port forwarding and Windows Firewall.
# 3. Starts the required Docker and Node.js services.
# 4. Verifies that all services are running and accessible.
#
# Run this single script to start everything.
# =============================================================================

# 1. CHECK ADMINISTRATOR PRIVILEGES
# -----------------------------------------------------------------------------
# Verify that the script is running with administrator privileges.
if (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "===============================================" -ForegroundColor Red
    Write-Host "ERROR: Administrator privileges required!" -ForegroundColor Red
    Write-Host "===============================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please run this script as Administrator:" -ForegroundColor Yellow
    Write-Host "1. Right-click on 'start-streaming-environment.ps1'" -ForegroundColor White
    Write-Host "2. Select 'Run with PowerShell' or 'Run as Administrator'" -ForegroundColor White
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

# Set script directory as current location
$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
Set-Location $scriptDir

Write-Host "=== Starting Streaming Environment Setup ===" -ForegroundColor Cyan
Write-Host "Running with Administrator privileges: OK" -ForegroundColor Green
Write-Host ""

# 2. CONFIGURATION & NETWORK SETUP
# -----------------------------------------------------------------------------
Write-Host "--> Step 1: Configuring Network (Port Forwarding & Firewall)" -ForegroundColor Yellow

# Get WSL2 IP Address
try {
    $wslIp = (wsl -e bash -c "hostname -I").Trim().Split()[0]
    if (-not $wslIp) { throw "WSL IP is empty." }
    Write-Host "    - WSL2 IP Address found: $wslIp" -ForegroundColor Green
}
catch {
    Write-Host "    - FATAL: Could not determine WSL2 IP address. Is WSL running?" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Define ports to forward (to WSL2 for SRS/Docker services)
# Note: Port 8000 is NOT included because Node.js WebSocket server runs directly on Windows
$ports = @{
    "1935" = "RTMP (Video Ingest)";
    "8080" = "HTTP-FLV (Video Playback)";
    "1985" = "SRS API";
}
# Convert port keys to an array of integers for firewall rule
# Add port 8000 separately for firewall (Node.js runs on Windows, needs firewall access)
$portArray = @($ports.Keys | ForEach-Object { [int]$_ })
$portArray += 8000  # WebSocket Server (runs directly on Windows)
$portNumbers = $portArray -join ","

# *** NEW STEP: Attempt to fix potential registry permission issues for PortProxy ***
Write-Host "    - Applying potential fix for netsh registry permissions..."
try {
    $key = "HKLM:\SYSTEM\CurrentControlSet\Services\PortProxy"
    if (-not (Test-Path $key)) {
        New-Item -Path $key -Force | Out-Null
    }
    # Grant "Everyone" full control. This is a broad permission setting for troubleshooting.
    $acl = Get-Acl $key
    $rule = New-Object System.Security.AccessControl.RegistryAccessRule("Everyone", "FullControl", "Allow")
    $acl.SetAccessRule($rule)
    Set-Acl -Path $key -AclObject $acl
    Write-Host "      ✓ Registry permissions for PortProxy set." -ForegroundColor Green
} catch {
    Write-Host "      ! WARNING: Could not set registry permissions. This might not be critical. Error: $($_.Exception.Message)" -ForegroundColor Yellow
}
# *** END OF NEW STEP ***

# Clear existing port proxy rules
Write-Host "    - Cleaning up old port proxy rules..."
foreach ($port in $ports.Keys) {
    netsh interface portproxy delete v4tov4 listenport=$port listenaddress=0.0.0.0 2>$null
}

# Create new port proxy rules
Write-Host "    - Creating new port proxy rules..."
$allProxiesAdded = $true
foreach ($port in $ports.Keys) {
    $portName = $ports[$port]
    
    # Execute netsh command and capture output
    $output = netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIp 2>&1
    $exitCode = $LASTEXITCODE
    
    # Check if the rule was actually created
    $rule = netsh interface portproxy show v4tov4 | Select-String "$port"
    
    if ($rule -and $exitCode -eq 0) {
        Write-Host "      ✓ Port $port ($portName) forwarded to $wslIp" -ForegroundColor Green
    } else {
        Write-Host "      ✗ FAILED to forward port $port ($portName)!" -ForegroundColor Red
        Write-Host "        Exit Code: $exitCode" -ForegroundColor Yellow
        Write-Host "        Error Output: $output" -ForegroundColor Yellow
        $allProxiesAdded = $false
    }
}

if (-not $allProxiesAdded) {
    Write-Host "    - FATAL: Not all port forwarding rules could be created." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Configure Windows Firewall
Write-Host "    - Configuring Windows Firewall..."
$ruleName = "Thinklet Streaming Environment"
Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $portArray
$firewallRule = Get-NetFirewallRule -DisplayName $ruleName
if ($firewallRule) {
    Write-Host "      ✓ Firewall rule '$ruleName' created for ports $portNumbers." -ForegroundColor Green
} else {
    Write-Host "      ✗ FAILED to create firewall rule!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# 3. STARTING SERVICES
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "--> Step 2: Starting Services (Docker & Node.js)" -ForegroundColor Yellow

# Check if Docker is accessible in WSL
Write-Host "    - Checking Docker availability in WSL..."
try {
    $dockerCheck = wsl -e bash -c "docker --version 2>&1"
    if ($dockerCheck -match "Docker version") {
        Write-Host "      ✓ Docker is accessible in WSL: $dockerCheck" -ForegroundColor Green
    } else {
        throw "Docker is not accessible in WSL. Please ensure Docker Desktop is running."
    }
} catch {
    Write-Host "      ✗ FAILED: Docker is not available in WSL." -ForegroundColor Red
    Write-Host "      ! Please start Docker Desktop and try again." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# Start Docker Containers (SRS Server)
Write-Host "    - Starting SRS Docker container..."
try {
    # Convert Windows path to WSL path dynamically
    $wslPath = wsl -e bash -c "wslpath '$($scriptDir.Replace('\', '\\'))'"
    
    # Check if container already exists and handle conflicts
    Write-Host "      ! Checking for existing containers..." -ForegroundColor Cyan
    $existingContainer = wsl -e bash -c "docker ps -a --filter 'name=srs-server' --format '{{.Names}}'"
    if ($existingContainer -match "srs-server") {
        Write-Host "      ! Found existing srs-server container, removing it..." -ForegroundColor Yellow
        wsl -e bash -c "docker stop srs-server 2>/dev/null; docker rm srs-server 2>/dev/null"
        Write-Host "      ✓ Old container removed." -ForegroundColor Green
    }
    
    # Start or restart the container (docker-compose.yml is now in config/)
    Write-Host "      ! Starting SRS container..." -ForegroundColor Cyan
    wsl -e bash -c "cd '$wslPath/config' && docker compose up -d"
    
    # Verify container is running
    $srsStatus = wsl -e bash -c "cd '$wslPath/config' && docker compose ps"
    if ($srsStatus -match "Up") {
        Write-Host "      ✓ SRS Docker container is running." -ForegroundColor Green
    } else {
        throw "SRS container not in 'Up' state."
    }
    
    # Health check: Wait for SRS API to be ready (up to 30 seconds)
    Write-Host "      ! Checking SRS service health via API..." -ForegroundColor Cyan
    $srsReady = $false
    $maxAttempts = 30
    
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:1985/api/v1/versions" -TimeoutSec 2 -ErrorAction Stop
            if ($response.StatusCode -eq 200) {
                Write-Host "      ✓ SRS service is healthy and ready!" -ForegroundColor Green
                $srsReady = $true
                break
            }
        } catch {
            # SRS not ready yet, wait and retry
            Start-Sleep -Seconds 1
            if ($attempt % 5 -eq 0) {
                Write-Host "      ! Still waiting for SRS to initialize... ($attempt/$maxAttempts)" -ForegroundColor Yellow
            }
        }
    }
    
    if (-not $srsReady) {
        throw "SRS API health check failed after $maxAttempts seconds. Service may not be fully initialized."
    }
} catch {
    Write-Host "      ✗ FAILED to start or verify SRS service." -ForegroundColor Red
    Write-Host "      Error: $_" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# Start Node.js Server
Write-Host "    - Starting Node.js WebSocket server..."
try {
    # Forcefully stop any process using port 8000
    Write-Host "      ! Checking for existing processes on port 8000..." -ForegroundColor Cyan
    $port8000Connection = Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue
    if ($port8000Connection) {
        $existingProcess = Get-Process -Id $port8000Connection.OwningProcess -ErrorAction SilentlyContinue
        if ($existingProcess) {
            Write-Host "      ! Port 8000 is in use by $($existingProcess.ProcessName) (PID: $($existingProcess.Id)). Forcefully stopping it..." -ForegroundColor Yellow
            if ($existingProcess.Id -eq 0) {
                throw "Port 8000 is occupied by a system process (PID 0) and cannot be stopped automatically. Please check for conflicting services (like other web servers or system services) and try again."
            }
            Stop-Process -Id $existingProcess.Id -Force
            Write-Host "      ✓ Old process stopped." -ForegroundColor Green
        }
    }
    
    Write-Host "      ! Starting new Node.js server..." -ForegroundColor Cyan
    
    # Use node directly to run simple-http-server.js
    $nodeExe = Get-Command node -ErrorAction SilentlyContinue
    if (-not $nodeExe) {
        throw "Node.js is not installed or not in PATH. Please install Node.js first."
    }
    
    $serverScript = Join-Path $scriptDir "src\simple-http-server.js"
    if (-not (Test-Path $serverScript)) {
        throw "Server script not found: $serverScript"
    }
    
    # Start the Node.js server in a hidden window (working directory is still streaming root)
    Start-Process -FilePath "node.exe" -ArgumentList "`"$serverScript`"" -WorkingDirectory $scriptDir -WindowStyle Hidden
    
    Write-Host "      ✓ Node.js server started in the background." -ForegroundColor Green
    Write-Host "      ! Waiting for Node.js server to initialize (5 seconds)..." -ForegroundColor Cyan
    Start-Sleep -Seconds 5
} catch {
    Write-Host "      ✗ FAILED to start Node.js server." -ForegroundColor Red
    Write-Host $_
    Read-Host "Press Enter to exit"
    exit 1
}

# 4. VERIFYING CONNECTIVITY
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "--> Step 3: Verifying Service Connectivity" -ForegroundColor Yellow
Write-Host "    - Waiting for services to initialize..."

# Test all ports including 8000 (Node.js WebSocket Server)
$testPorts = @{
    "1935" = "RTMP (Video Ingest)";
    "8080" = "HTTP-FLV (Video Playback)";
    "1985" = "SRS API";
    "8000" = "WebSocket Server (Node.js)";
}

$allPortsConnected = $true
foreach ($port in $testPorts.Keys) {
    $portName = $testPorts[$port]
    $portConnected = $false
    # Wait up to 10 seconds for the port to become available
    foreach ($attempt in 1..10) {
        try {
            $socket = New-Object System.Net.Sockets.TcpClient
            $socket.Connect("localhost", $port)
            if ($socket.Connected) {
                Write-Host "      ✓ Port $port ($portName) is active and connectable." -ForegroundColor Green
                $socket.Close()
                $portConnected = $true
                break # Exit the attempt loop
            }
        } catch {
            # Port not ready yet, wait and try again
            Start-Sleep -Seconds 1
        } finally {
            if ($socket) { $socket.Dispose() }
        }
    }

    if (-not $portConnected) {
        Write-Host "      ✗ TIMEOUT: Port $port ($portName) is not connectable after 10 seconds." -ForegroundColor Red
        $allPortsConnected = $false
    }
}

# 5. FINAL SUMMARY
# -----------------------------------------------------------------------------
Write-Host ""
if ($allPortsConnected) {
    # Get Windows IP address (try multiple methods for reliability)
    $windowsIp = $null
    try {
        $windowsIp = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like '192.168.*' -or $_.IPAddress -like '10.*' -or $_.IPAddress -like '172.*' } | Select-Object -First 1).IPAddress
    } catch {}
    
    if (-not $windowsIp) {
        $windowsIp = "192.168.16.88"  # Fallback to known IP
    }
    
    Write-Host "================= SUCCESS =================" -ForegroundColor Green
    Write-Host "All services are running and accessible."
    Write-Host "You can now connect from your devices."
    Write-Host ""
    Write-Host "  - Windows Host IP: $windowsIp"
    Write-Host "  - WebSocket Server: ws://$windowsIp:8000"
    Write-Host "  - RTMP Ingest: rtmp://$windowsIp:1935/thinklet.squid.run/STREAM_KEY"
    Write-Host "  - Web Interface / Player: http://$windowsIp:8080"
    Write-Host "===========================================" -ForegroundColor Green
} else {
    Write-Host "================== FAILED =================" -ForegroundColor Red
    Write-Host "One or more services failed to start or are not accessible."
    Write-Host "Please review the errors above to diagnose the issue."
    Write-Host "===========================================" -ForegroundColor Red
}

Write-Host ""
Read-Host "Press Enter to exit"
