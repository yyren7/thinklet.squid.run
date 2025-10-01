const http = require('http');
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const PORT = 8000;
const PUBLIC_DIR = path.join(__dirname);
const DEVICES_FILE = path.join(__dirname, 'devices.json');

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
    console.error('❌ 读取 devices.json 文件失败:', err);
}

function saveDevicesToFile() {
    try {
        fs.writeFileSync(DEVICES_FILE, JSON.stringify(devices, null, 4));
    } catch (err)        {
        console.error('❌ 写入 devices.json 文件失败:', err);
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
                    broadcast({ type: 'deviceRemoved', payload: { id } });
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: true, message: `设备 ${id} 已删除` }));
                } else {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: false, message: '未找到设备' }));
                }
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: '错误的请求' }));
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

function broadcast(data) {
    const message = JSON.stringify(data);
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(message);
        }
    });
}

wss.on('connection', ws => {
    console.log('✅ WebSocket 客户端已连接');
    let deviceId = null;

    ws.on('message', message => {
        try {
            const data = JSON.parse(message);
            // Assuming the first message from a device contains its ID and status
            if (data.id && data.status) {
                deviceId = data.id;
                ws.deviceId = deviceId; // Attach deviceId to the ws connection for later use

                console.log(`📥 收到来自设备 ${deviceId} 的状态更新`);

                devices[deviceId] = {
                    ...(devices[deviceId] || {}),
                    id: deviceId,
                    lastSeen: new Date().toISOString(),
                    isOnline: true,
                    status: data.status
                };
                
                saveDevicesToFile();
                broadcast({ type: 'deviceUpdate', payload: devices[deviceId] });
            }
        } catch (e) {
            console.error('解析消息失败:', e);
            // Not a JSON message, might be the old "hello" or from web client
            console.log('📥 收到非JSON格式消息:', message.toString());
        }
    });

    ws.on('close', () => {
        console.log('🔌 WebSocket 客户端已断开');
        if (ws.deviceId && devices[ws.deviceId]) {
            devices[ws.deviceId].isOnline = false;
            saveDevicesToFile();
            broadcast({ type: 'deviceUpdate', payload: devices[ws.deviceId] });
            console.log(` marcado 设备 ${ws.deviceId} 为离线`);
        }
    });
});

server.listen(PORT, () => {
    console.log(`✅ HTTP服务器已启动，正在监听 ${PORT} 端口`);
    console.log(`🔗 请在浏览器中打开 http://localhost:${PORT}`);
});



