let currentLang = localStorage.getItem('language') || 'en';

// Update page language
function updateLanguage(lang) {
    currentLang = lang;
    localStorage.setItem('language', lang);
    document.documentElement.lang = lang;
    document.title = i18n[lang].title.replace('⚡ ', '') + ' - Thinklet Dashboard';
    
    // Update all elements with data-i18n attribute
    document.querySelectorAll('[data-i18n]').forEach(element => {
        const key = element.getAttribute('data-i18n');
        if (i18n[lang][key]) {
            element.textContent = i18n[lang][key];
        }
    });

    // Update language button states
    document.querySelectorAll('.language-btn').forEach(btn => {
        btn.classList.toggle('active', btn.getAttribute('data-lang') === lang);
    });
}

// Get translated text
function t(key) {
    return i18n[currentLang][key] || key;
}

document.addEventListener('DOMContentLoaded', () => {
    const deviceGrid = document.getElementById('device-grid');
    const cardTemplate = document.getElementById('device-card-template');
    const STREAM_BASE_URL = `http://${window.location.hostname}:8080/thinklet.squid.run`;
    const connectionStatus = document.getElementById('connection-status');
    const connectionText = document.getElementById('connection-text');

    const flvPlayers = {};
    let devices = [];
    let fileTransfers = {}; // File transfer task map
    const recordingTimers = {}; // Recording duration timers (interval IDs)

    // 低延迟监控：追赶最新直播内容
    function setupLowLatencyMonitor(player, videoElement, deviceId) {
        const MAX_BUFFER_DELAY = 3; // 最大允许延迟3秒
        const CHECK_INTERVAL = 1000; // 每秒检查一次
        
        const intervalId = setInterval(() => {
            if (!flvPlayers[deviceId]) {
                clearInterval(intervalId);
                return;
            }
            
            try {
                const buffered = videoElement.buffered;
                if (buffered.length > 0) {
                    const currentTime = videoElement.currentTime;
                    const bufferedEnd = buffered.end(buffered.length - 1);
                    const delay = bufferedEnd - currentTime;
                    
                    // 如果延迟超过阈值，跳转到最新位置
                    if (delay > MAX_BUFFER_DELAY) {
                        console.log(`Device ${deviceId}: 延迟过大 (${delay.toFixed(2)}s)，跳转到最新位置`);
                        videoElement.currentTime = bufferedEnd - 0.5; // 跳到最新位置，留0.5秒缓冲
                    }
                }
            } catch (e) {
                console.warn(`Device ${deviceId}: 延迟检查失败`, e);
            }
        }, CHECK_INTERVAL);
        
        // 保存定时器ID以便清理
        player._latencyMonitorInterval = intervalId;
    }

    // 页面可见性变化处理
    function handleVisibilityChange() {
        if (!document.hidden) {
            console.log('页面重新可见，刷新所有直播流到最新位置');
            Object.entries(flvPlayers).forEach(([deviceId, player]) => {
                try {
                    const videoElement = document.querySelector(`#device-${deviceId} video`);
                    if (videoElement && videoElement.buffered.length > 0) {
                        const bufferedEnd = videoElement.buffered.end(videoElement.buffered.length - 1);
                        const currentTime = videoElement.currentTime;
                        const delay = bufferedEnd - currentTime;
                        
                        if (delay > 1) {
                            console.log(`Device ${deviceId}: 跳转到最新位置 (延迟: ${delay.toFixed(2)}s)`);
                            videoElement.currentTime = bufferedEnd - 0.5;
                        }
                        
                        // 确保视频继续播放
                        if (videoElement.paused) {
                            videoElement.play().catch(e => console.warn(`Failed to resume ${deviceId}:`, e));
                        }
                    }
                } catch (e) {
                    console.warn(`Failed to sync device ${deviceId}:`, e);
                }
            });
        }
    }

    // 监听页面可见性变化
    document.addEventListener('visibilitychange', handleVisibilityChange);

    // Initialize language
    updateLanguage(currentLang);

    // Language switch event
    document.querySelectorAll('.language-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            updateLanguage(btn.getAttribute('data-lang'));
            // Re-render all device cards
            devices.forEach(createOrUpdateCard);
        });
    });

    // Update statistics
    function updateStats() {
        const onlineDevices = devices.filter(d => d.isOnline).length;
        const totalDevices = devices.length;
        
        document.getElementById('online-count').textContent = onlineDevices;
        document.getElementById('total-count').textContent = totalDevices;

        // Update empty state
        const emptyState = deviceGrid.querySelector('.empty-state');
        if (totalDevices > 0 && emptyState) {
            emptyState.remove();
        } else if (totalDevices === 0 && !emptyState) {
            deviceGrid.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">📱</div>
                    <div class="empty-state-text" data-i18n="noDevices">${t('noDevices')}</div>
                </div>
            `;
        }
    }

    // Format time
    function formatTime(timestamp) {
        const date = new Date(timestamp);
        const now = new Date();
        const diff = now - date;
        
        if (diff < 60000) return t('justNow');
        if (diff < 3600000) return `${Math.floor(diff / 60000)} ${t('minutesAgo')}`;
        if (diff < 86400000) return `${Math.floor(diff / 3600000)} ${t('hoursAgo')}`;
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
    }

    // Format duration (milliseconds to HH:MM:SS)
    function formatDuration(ms) {
        const totalSeconds = Math.floor(ms / 1000);
        const hours = Math.floor(totalSeconds / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
        } else {
            return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
        }
    }

    // Create or update card
    function createOrUpdateCard(device) {
        let card = deviceGrid.querySelector(`#device-${device.id}`);
        if (!card) {
            card = cardTemplate.content.cloneNode(true).firstElementChild;
            card.id = `device-${device.id}`;
            deviceGrid.appendChild(card);
            card.querySelector('.delete-btn').addEventListener('click', () => deleteDevice(device.id));
            card.querySelector('.start-stream-btn').addEventListener('click', () => controlStream(device.id, 'start'));
            card.querySelector('.stop-stream-btn').addEventListener('click', () => controlStream(device.id, 'stop'));
        }

        // Update device info
        const streamKey = device.status?.streamKey || device.id;
        card.querySelector('.device-name').textContent = `${t('deviceName')}: ${streamKey}`;
        card.querySelector('.device-id').textContent = `${t('deviceId')}${device.id}`;
        
        // Update status
        const statusBadge = card.querySelector('.status-badge');
        const statusText = statusBadge.querySelector('.status-text');
        statusText.textContent = device.isOnline ? t('online') : t('offline');
        card.classList.toggle('online', device.isOnline);
        card.classList.toggle('offline', !device.isOnline);
        statusBadge.classList.toggle('online', device.isOnline);
        statusBadge.classList.toggle('offline', !device.isOnline);

        // Update timestamp
        card.querySelector('.lastSeen').textContent = formatTime(device.lastSeen);

        // Update status details only if device is online
        const status = device.status || {};
        const batteryEl = card.querySelector('.battery');
        const wifiEl = card.querySelector('.wifiSignalStrength');
        const recordingEl = card.querySelector('.isRecording');
        const streamingEl = card.querySelector('.isStreaming');

        if (device.isOnline) {
            // Battery status with modern indicator
            const level = status.batteryLevel;
            const isCharging = status.isCharging;
            let levelClass = 'low';
            if (level > 60) levelClass = 'high';
            else if (level > 20) levelClass = 'medium';
            
            const chargingClass = isCharging ? 'battery-charging' : '';
            const chargingIcon = isCharging ? `<span class="charging-icon"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M11.9,2.6L4.2,14.5h5.7v6.9l7.7-11.9h-5.7V2.6z"/></svg></span>` : '';

            batteryEl.innerHTML = `
                <div class="battery-container ${chargingClass}">
                    <div class="battery-icon">
                        ${chargingIcon}
                        <div class="battery-level-bar ${levelClass}" style="width: ${level}%;"></div>
                    </div>
                    <span class="battery-text">${level}%</span>
                </div>
            `;

            wifiEl.textContent = status.wifiSignalStrength !== undefined ? `${status.wifiSignalStrength} dBm` : t('notAvailable');
            
            // Recording status with modern indicator
            // Use server-side timestamp for accurate duration calculation
            if (status.isRecording && device.recordingStartTime) {
                // Clear any existing timer
                if (recordingTimers[device.id]) {
                    clearInterval(recordingTimers[device.id]);
                }

                const updateRecordingTime = () => {
                    // Calculate elapsed time from server-side start time
                    const elapsedTime = Date.now() - device.recordingStartTime;
                    const duration = formatDuration(elapsedTime);
                    
                    const recordingElement = card.querySelector('.status-recording span:last-child');
                    if (recordingElement) {
                        recordingElement.textContent = `${t('recording')} ${duration}`;
                    }
                };
                
                // Initial update
                const elapsedTime = Date.now() - device.recordingStartTime;
                recordingEl.innerHTML = `
                    <span class="status-indicator status-recording">
                        <span class="status-led"></span>
                        <span>${t('recording')} ${formatDuration(elapsedTime)}</span>
                    </span>
                `;

                // Start timer to update every second
                recordingTimers[device.id] = setInterval(updateRecordingTime, 1000);
            } else {
                // Recording stopped or not started
                if (recordingTimers[device.id]) {
                    clearInterval(recordingTimers[device.id]);
                    delete recordingTimers[device.id];
                }

                recordingEl.innerHTML = `
                    <span class="status-indicator status-prepared">
                        <span class="status-led"></span>
                        <span>${t('notRecording')}</span>
                    </span>
                `;
            }
            
            // Streaming status with modern indicator
            if (status.isStreaming) {
                streamingEl.innerHTML = `
                    <span class="status-indicator status-streaming">
                        <span class="status-led"></span>
                        <span>${t('streaming')}</span>
                    </span>
                `;
            } else if (status.isStreamingReady) {
                streamingEl.innerHTML = `
                    <span class="status-indicator status-prepared">
                        <span class="status-led"></span>
                        <span>${t('prepared')}</span>
                    </span>
                `;
            } else {
                streamingEl.innerHTML = `
                    <span class="status-indicator status-not-prepared">
                        <span class="status-led"></span>
                        <span>${t('notPrepared')}</span>
                    </span>
                `;
            }
        } else {
            batteryEl.innerHTML = `
                <div class="battery-container">
                    <div class="battery-icon"></div>
                    <span class="battery-text">-</span>
                </div>
            `;
            wifiEl.textContent = '-';
            recordingEl.innerHTML = `
                <span class="status-indicator status-offline">
                    <span class="status-led"></span>
                    <span>${t('offline')}</span>
                </span>
            `;
            streamingEl.innerHTML = `
                <span class="status-indicator status-offline">
                    <span class="status-led"></span>
                    <span>${t('offline')}</span>
                </span>
            `;
        }

        // Handle stream control buttons visibility
        const startBtn = card.querySelector('.start-stream-btn');
        const stopBtn = card.querySelector('.stop-stream-btn');

        if (device.isOnline && status.isStreamingReady) {
            if (status.isStreaming) {
                startBtn.style.display = 'none';
                stopBtn.style.display = 'inline-block';
            } else {
                startBtn.style.display = 'inline-block';
                stopBtn.style.display = 'none';
            }
        } else {
            startBtn.style.display = 'none';
            stopBtn.style.display = 'none';
        }

        // Update file transfer section for this device
        updateDeviceTransfers(card, device.id);

        // Handle video stream
        const videoElement = card.querySelector('video');
        const placeholder = card.querySelector('.video-placeholder');
        const streamUrl = `${STREAM_BASE_URL}/${streamKey}.flv`;

        if (device.isOnline && device.status && device.status.isStreaming) {
            placeholder.style.display = 'none';
            videoElement.style.display = 'block';
            
            if (flvjs.isSupported() && !flvPlayers[device.id]) {
                console.log(`Creating player for: ${device.id}`);
                const flvPlayer = flvjs.createPlayer({
                    type: 'flv',
                    isLive: true,
                    url: streamUrl
                }, {
                    // 低延迟配置
                    enableWorker: false,
                    enableStashBuffer: false,  // 禁用缓存缓冲区
                    stashInitialSize: 128,     // 减小初始缓存大小
                    isLive: true,
                    lazyLoad: false,
                    lazyLoadMaxDuration: 0.2,
                    seekType: 'range',
                    autoCleanupSourceBuffer: true,
                    autoCleanupMaxBackwardDuration: 3,  // 只保留3秒历史数据
                    autoCleanupMinBackwardDuration: 2,
                });
                flvPlayer.attachMediaElement(videoElement);
                flvPlayer.load();
                flvPlayer.play().catch(e => console.error(`Failed to play ${device.id}:`, e));
                
                // 设置低延迟追赶机制
                setupLowLatencyMonitor(flvPlayer, videoElement, device.id);
                
                flvPlayers[device.id] = flvPlayer;
            }
        } else {
            placeholder.style.display = 'flex';
            videoElement.style.display = 'none';
            placeholder.textContent = device.isOnline ? t('waitingStream') : t('deviceOffline');
            
            if (flvPlayers[device.id]) {
                console.log(`Destroying player for: ${device.id}`);
                const player = flvPlayers[device.id];
                
                // 清理延迟监控定时器
                if (player._latencyMonitorInterval) {
                    clearInterval(player._latencyMonitorInterval);
                }
                
                player.destroy();
                delete flvPlayers[device.id];
            }
        }

        // Update device list
        const index = devices.findIndex(d => d.id === device.id);
        if (index >= 0) {
            devices[index] = device;
        } else {
            devices.push(device);
        }
        
        updateStats();
    }

    // Delete device
    async function deleteDevice(id) {
        if (!confirm(`${t('confirmDelete')} ${id}? ${t('irreversible')}`)) return;

        try {
            const response = await fetch('/delete-device', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id })
            });
            const result = await response.json();
            if (!result.success) {
                alert(`${t('deleteFailed')}: ${t(result.message)}`);
            }
        } catch (error) {
            console.error('Error deleting device:', error);
            alert(t('deleteError'));
        }
    }
    
    // Control stream
    async function controlStream(id, action) {
        const endpoint = action === 'start' ? '/start-stream' : '/stop-stream';

        try {
            const response = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id })
            });
            const result = await response.json();
            if (!result.success) {
                alert(`${t(action + 'StreamErrorFail')}: ${t(result.message)}`);
            }
        } catch (error) {
            console.error(`Error ${action}ing stream:`, error);
            alert(t(action + 'StreamErrorAlert'));
        }
    }

    // Initialize dashboard
    async function initializeDashboard() {
        try {
            const response = await fetch('/devices');
            const fetchedDevices = await response.json();
            devices = fetchedDevices.sort((a, b) => new Date(b.lastSeen) - new Date(a.lastSeen));
            devices.forEach(createOrUpdateCard);
        } catch (error) {
            console.error('Failed to initialize dashboard:', error);
        }
    }

    // WebSocket Connection
    function connectWebSocket() {
        const wsUrl = `ws://${window.location.hostname}:8000`;
        const ws = new WebSocket(wsUrl);

        ws.onopen = () => {
            console.log('WebSocket connected');
            connectionStatus.classList.add('show', 'connected');
            connectionStatus.classList.remove('disconnected');
            connectionText.textContent = t('connected');

            // Identify this client as a browser
            ws.send(JSON.stringify({ type: 'identify', client: 'browser' }));
        };

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                console.log('WebSocket message received:', data);

                if (data.type === 'deviceUpdate') {
                    createOrUpdateCard(data.payload);
                } else if (data.type === 'deviceRemoved') {
                    const card = deviceGrid.querySelector(`#device-${data.payload.id}`);
                    if (card) card.remove();
                    if (flvPlayers[data.payload.id]) {
                        const player = flvPlayers[data.payload.id];
                        
                        // 清理延迟监控定时器
                        if (player._latencyMonitorInterval) {
                            clearInterval(player._latencyMonitorInterval);
                        }
                        
                        player.destroy();
                        delete flvPlayers[data.payload.id];
                    }
                    devices = devices.filter(d => d.id !== data.payload.id);
                    updateStats();
                } else if (data.type === 'fileTransferProgress') {
                    updateFileTransferProgress(data.payload);
                } else if (data.type === 'fileTransferInitialState') {
                    // Handle initial state of all transfers
                    data.payload.forEach(updateFileTransferProgress);
                }
            } catch (error) {
                console.error('Failed to parse WebSocket message:', error);
            }
        };

        ws.onclose = () => {
            console.log('WebSocket disconnected. Reconnecting in 5 seconds...');
            connectionStatus.classList.add('show', 'disconnected');
            connectionStatus.classList.remove('connected');
            connectionText.textContent = t('disconnected');
            setTimeout(connectWebSocket, 5000);
        };

        ws.onerror = (error) => {
            console.error('WebSocket error:', error);
            ws.close();
        };
    }

    // Update file transfer progress
    function updateFileTransferProgress(transfer) {
        // If the status is not 'failed', clear any previous errors.
        // This handles the case where a transfer reconnects and resumes successfully.
        if (transfer.status !== 'failed') {
            transfer.error = null;
        }

        fileTransfers[transfer.id] = transfer;
        
        // Update the device card's transfer section
        const card = deviceGrid.querySelector(`#device-${transfer.deviceId}`);
        if (card) {
            updateDeviceTransfers(card, transfer.deviceId);
        }
    }

    // Update device-specific file transfers
    function updateDeviceTransfers(card, deviceId) {
        const transferSection = card.querySelector('.file-transfer-section');
        const transferList = transferSection.querySelector('.file-transfer-list');
        const transferBadge = transferSection.querySelector('.transfer-badge');
        
        // Get transfers for this specific device
        const deviceTransfers = Object.values(fileTransfers).filter(t => t.deviceId === deviceId);
        
        // Update badge count
        transferBadge.textContent = deviceTransfers.length;
        
        if (deviceTransfers.length === 0) {
            transferList.innerHTML = `<div class="empty-transfer-state">${t('noActiveTransfers')}</div>`;
            transferSection.classList.remove('has-transfers');
            return;
        }
        
        transferSection.classList.add('has-transfers');
        
        transferList.innerHTML = deviceTransfers.map(transfer => {
            const statusEmoji = {
                'downloading': '⬇️',
                'verifying': '🔐',
                'completed': '✅',
                'failed': '❌',
                'queued': '⏳',
                'retrying': '🔄',
                'paused': '⏸️'
            }[transfer.status] || '⚙️';
            
            const sizeText = `${(transfer.downloadedBytes / 1024 / 1024).toFixed(2)} / ${(transfer.totalBytes / 1024 / 1024).toFixed(2)} MB`;
            
            // Add a hint for paused status
            const pausedHint = transfer.status === 'paused' 
                ? `<div class="transfer-hint">${t('resumeOnOnline')}</div>` 
                : '';
            
            return `
                <div class="transfer-item ${transfer.status}">
                    <div class="transfer-item-header">
                        <span class="transfer-status-badge">${statusEmoji} ${t('status' + transfer.status.charAt(0).toUpperCase() + transfer.status.slice(1))}</span>
                    </div>
                    <div class="transfer-filename">${transfer.fileName}</div>
                    <div class="transfer-progress-bar">
                        <div class="transfer-progress-fill" style="width: ${transfer.progress}%"></div>
                    </div>
                    <div class="transfer-details">
                        <span>${sizeText}</span>
                        <span>${transfer.progress}%</span>
                    </div>
                    ${transfer.error ? `<div class="transfer-error">${t('errorLabel')}: ${transfer.error}</div>` : ''}
                    ${pausedHint}
                </div>
            `;
        }).join('');
        
        // Auto-remove completed tasks after 2 minutes
        deviceTransfers.forEach(transfer => {
            if (transfer.status === 'completed' && !transfer.cleanupScheduled) {
                transfer.cleanupScheduled = true;
                setTimeout(() => {
                    delete fileTransfers[transfer.id];
                    updateDeviceTransfers(card, deviceId);
                }, 120000);
            }
        });
    }

    initializeDashboard();
    connectWebSocket();
});
