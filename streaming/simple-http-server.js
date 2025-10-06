const http = require('http');
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');
const FileTransferService = require('./file-transfer-service');

const PORT = 8000;
const PUBLIC_DIR = path.join(__dirname);
const DEVICES_FILE = path.join(__dirname, 'devices.json');
const VIDEO_DIR = path.join(__dirname, 'video');

let devices = {}; // { deviceId: { id, lastSeen, isOnline, status: {} } }

// Load devices from file on startup
try {
    if (fs.existsSync(DEVICES_FILE)) {
        const data = fs.readFileSync(DEVICES_FILE);
        devices = JSON.parse(data);
        // Mark all as offline on startup
        Object.values(devices).forEach(device => device.isOnline = false);
    }
} catch (err) {
    console.error('❌ Failed to read devices.json:', err);
}

function saveDevicesToFile() {
    try {
        fs.writeFileSync(DEVICES_FILE, JSON.stringify(devices, null, 4));
    } catch (err)        {
        console.error('❌ Failed to write to devices.json:', err);
    }
}


const MIME_TYPES = {
    '.html': 'text/html',
    '.css': 'text/css',
    '.js': 'application/javascript',
    '.json': 'application/json',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.gif': 'image/gif',
    '.svg': 'image/svg+xml',
    '.wav': 'audio/wav',
    '.mp4': 'video/mp4',
    '.woff': 'application/font-woff',
    '.ttf': 'application/font-ttf',
    '.eot': 'application/vnd.ms-fontobject',
    '.otf': 'application/font-otf',
    '.wasm': 'application/wasm'
};

const server = http.createServer((req, res) => {
    const requestUrl = new URL(req.url, `http://${req.headers.host}`);
    const pathname = requestUrl.pathname;

    // API endpoint to get all devices
    if (pathname === '/devices' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(Object.values(devices)));
        return;
    }

    // API endpoint to get file transfer tasks
    if (pathname === '/file-transfers' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(fileTransferService.getAllTasks()));
        return;
    }

    // API endpoint to delete a device
    if (pathname === '/delete-device' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => {
            body += chunk.toString();
        });
        req.on('end', () => {
            try {
                const { id } = JSON.parse(body);
                if (devices[id]) {
                    delete devices[id];
                    saveDevicesToFile();
                    broadcastToBrowsers({ type: 'deviceRemoved', payload: { id } });
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: true, message: 'deviceDeleted' }));
                } else {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: false, message: 'deviceNotFound' }));
                }
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: 'badRequest' }));
            }
        });
        return;
    }

    // API endpoint to update device name
    if (pathname === '/update-device-name' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => {
            body += chunk.toString();
        });
        req.on('end', () => {
            try {
                const { id, name } = JSON.parse(body);
                if (devices[id]) {
                    devices[id].deviceName = name;
                    saveDevicesToFile();
                    broadcastToBrowsers({ type: 'deviceUpdate', payload: devices[id] });
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: true, message: 'deviceNameUpdated' }));
                } else {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: false, message: 'deviceNotFound' }));
                }
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: 'badRequest' }));
            }
        });
        return;
    }

    // API endpoints for stream control
    if ((pathname === '/start-stream' || pathname === '/stop-stream') && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => {
            body += chunk.toString();
        });
        req.on('end', () => {
            try {
                const { id } = JSON.parse(body);
                const action = pathname === '/start-stream' ? 'startStream' : 'stopStream';
                const command = { command: action };

                let deviceWs = null;
                wss.clients.forEach(client => {
                    if (client.deviceId === id && client.readyState === WebSocket.OPEN) {
                        deviceWs = client;
                    }
                });

                if (deviceWs) {
                    deviceWs.send(JSON.stringify(command));
                    console.log(`🚀 Sent '${action}' command to device ${id}`);
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: true, message: 'commandSentToDevice' }));
                } else {
                    console.log(`⚠️ Device ${id} not connected or not found.`);
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: false, message: 'deviceNotConnected' }));
                }
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: 'badRequest' }));
            }
        });
        return;
    }


    let filePath = path.join(PUBLIC_DIR, req.url === '/' ? 'index.html' : req.url);
    const extname = String(path.extname(filePath)).toLowerCase();
    const contentType = MIME_TYPES[extname] || 'application/octet-stream';

    fs.readFile(filePath, (err, content) => {
        if (err) {
            if (err.code == 'ENOENT') {
                fs.readFile(path.join(PUBLIC_DIR, '404.html'), (error, content404) => {
                    res.writeHead(404, { 'Content-Type': 'text/html' });
                    if (error) {
                        res.end('404 Not Found', 'utf-8');
                    } else {
                        res.end(content404, 'utf-8');
                    }
                });
            } else {
                res.writeHead(500);
                res.end('Sorry, check with the site admin for error: ' + err.code + ' ..\n');
            }
        } else {
            res.writeHead(200, { 'Content-Type': contentType });
            res.end(content, 'utf-8');
        }
    });
});

const wss = new WebSocket.Server({ server });

function broadcastToBrowsers(data) {
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN && client.clientType === 'browser') {
            client.send(JSON.stringify(data));
        }
    });
}

// This function is kept for potential future use where broadcasting to all clients is needed.
// For now, most messages are targeted to browsers.
function broadcast(data) {
    const message = JSON.stringify(data);
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(message);
        }
    });
}

// Initialize file transfer service
const fileTransferService = new FileTransferService(
    path.join(__dirname, 'video'),
    (data) => {
        // data already contains the complete {type, payload} structure, send directly
        wss.clients.forEach(client => {
            if (client.readyState === WebSocket.OPEN && client.clientType === 'browser') {
                client.send(JSON.stringify(data));
            }
        });
    }
);

// Scan device files periodically (every 2 minutes)
// This is a backup mechanism; primary scanning happens when devices come online
setInterval(async () => {
    const onlineDevices = Object.values(devices).filter(d => d.isOnline && d.status?.fileServerEnabled);
    
    if (onlineDevices.length === 0) {
        // No online devices with file server enabled, skip scanning
        return;
    }
    
    console.log(`🔍 Periodic file scan: checking ${onlineDevices.length} online devices...`);
    
    for (const device of onlineDevices) {
        // Ensure device has IP address
        if (!device.ip) {
            // Try to get IP from WebSocket connection
            wss.clients.forEach(client => {
                if (client.deviceId === device.id && client.readyState === WebSocket.OPEN) {
                    const ip = client._socket?.remoteAddress?.replace('::ffff:', '');
                    if (ip) {
                        device.ip = ip;
                        console.log(`📍 Updated IP for device ${device.id}: ${ip}`);
                    }
                }
            });
        }
        
        // Only scan if we have IP address
        if (device.ip) {
            try {
                const files = await fileTransferService.scanDeviceFiles(device);
                let addedCount = 0;
                for (const file of files) {
                    const added = await fileTransferService.addDownloadTask(file);
                    if (added) addedCount++;
                }
                if (addedCount > 0) {
                    console.log(`📥 Periodic scan: added ${addedCount} new download tasks for device ${device.id}`);
                }
            } catch (error) {
                console.error(`❌ Periodic scan failed for device ${device.id}:`, error.message);
            }
        } else {
            console.log(`⚠️ Device ${device.id} is online but IP address is not available, skipping scan`);
        }
    }
}, 10000); // Scan every 10s

wss.on('connection', ws => {
    console.log('✅ WebSocket client connected');
    let deviceId = null;
    ws.clientType = 'unknown'; // Default client type

    ws.on('message', async message => {
        try {
            const data = JSON.parse(message);

            // Handle client type identification
            if (data.type === 'identify') {
                if (data.client === 'browser') {
                    ws.clientType = 'browser';
                    console.log('🖥️ WebSocket client identified as browser');

                    // Send all current transfer statuses to the newly connected browser
                    const allTasks = fileTransferService.getAllTasks();
                    if (allTasks.length > 0) {
                        ws.send(JSON.stringify({
                            type: 'fileTransferInitialState',
                            payload: allTasks
                        }));
                    }
                }
                return; // No further processing for identify messages
            }
            
            // Assuming the first message from a device contains its ID and status
            if (data.id && data.status) {
                deviceId = data.id;
                ws.deviceId = deviceId; // Attach deviceId to the ws connection for later use

                console.log(`📥 Received status update from device ${deviceId}`);

                // Save device IP address (for file transfer)
                const ip = ws._socket?.remoteAddress?.replace('::ffff:', '');
                
                // Check if device was offline before (to detect online transition)
                const wasOffline = !devices[deviceId] || !devices[deviceId].isOnline;
                
                // Track recording start time for accurate duration calculation
                const previousDevice = devices[deviceId];
                const wasRecording = previousDevice?.status?.isRecording || false;
                const isNowRecording = data.status?.isRecording || false;
                
                let recordingStartTime = previousDevice?.recordingStartTime;
                
                // Recording just started - record the timestamp
                if (!wasRecording && isNowRecording) {
                    recordingStartTime = Date.now();
                    console.log(`🔴 Device ${deviceId} started recording at ${new Date(recordingStartTime).toISOString()}`);
                }
                // Recording stopped - clear the timestamp
                else if (wasRecording && !isNowRecording) {
                    recordingStartTime = null;
                    console.log(`⏹️ Device ${deviceId} stopped recording`);
                }

                devices[deviceId] = {
                    ...(devices[deviceId] || {}),
                    id: deviceId,
                    lastSeen: new Date().toISOString(),
                    isOnline: true,
                    status: data.status,
                    ip: ip || devices[deviceId]?.ip,
                    recordingStartTime: recordingStartTime,
                    // Preserve existing deviceName if it's not in the payload
                    deviceName: devices[deviceId]?.deviceName || null
                };
                
                saveDevicesToFile();
                broadcastToBrowsers({ type: 'deviceUpdate', payload: devices[deviceId] });
                
                // If device just came online, trigger file operations
                if (wasOffline && devices[deviceId].status?.fileServerEnabled) {
                    console.log(`🟢 Device ${deviceId} came online, triggering file operations...`);
                    
                    // Update IP for paused tasks
                    if (ip) {
                        fileTransferService.updateDeviceIpForPausedTasks(
                            deviceId, 
                            ip, 
                            data.status.fileServerPort || 8889
                        );
                    }
                    
                    // Resume paused tasks
                    const resumedCount = fileTransferService.resumePausedTasksForDevice(deviceId);
                    if (resumedCount > 0) {
                        console.log(`✅ Resumed ${resumedCount} paused tasks for device ${deviceId}`);
                    }
                    
                    // Scan for new files
                    if (ip) {
                        const files = await fileTransferService.scanDeviceFiles(devices[deviceId]);
                        let addedCount = 0;
                        for (const file of files) {
                            const added = await fileTransferService.addDownloadTask(file);
                            if (added) addedCount++;
                        }
                        if (addedCount > 0) {
                            console.log(`📥 Added ${addedCount} new download tasks for device ${deviceId}`);
                        }
                    }
                }
            } else if (data.command === 'getDevices') {
                // This is a request from a web client to get the initial list of devices
                ws.send(JSON.stringify({ type: 'deviceList', payload: Object.values(devices) }));
            }
        } catch (e) {
            console.error('Failed to parse message:', e);
            // Not a JSON message, might be the old "hello" or from web client
            console.log('📥 Received non-JSON message:', message.toString());
        }
    });

    ws.on('close', () => {
        console.log('🔌 WebSocket client disconnected');
        if (ws.deviceId && devices[ws.deviceId]) {
            devices[ws.deviceId].isOnline = false;
            saveDevicesToFile();
            broadcastToBrowsers({ type: 'deviceUpdate', payload: devices[ws.deviceId] });
            console.log(`Device ${ws.deviceId} marked as offline`);
        }
    });
});

server.listen(PORT, () => {
    console.log(`✅ HTTP server started on port ${PORT}`);
    console.log(`🔗 Open http://localhost:${PORT} in your browser`);
});



