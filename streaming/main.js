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
        card.querySelector('.device-id').textContent = `ID: ${device.id}`;
        
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

            wifiEl.textContent = status.wifiSignalStrength !== undefined ? `${status.wifiSignalStrength} dBm` : 'N/A';
            
            // Recording status with modern indicator
            if (status.isRecording) {
                const duration = formatDuration(status.recordingDurationMs || 0);
                recordingEl.innerHTML = `
                    <span class="status-indicator status-recording">
                        <span class="status-led"></span>
                        <span>${t('recording')} ${duration}</span>
                    </span>
                `;
            } else {
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
                    });
                    flvPlayer.attachMediaElement(videoElement);
                    flvPlayer.load();
                flvPlayer.play().catch(e => console.error(`Failed to play ${device.id}:`, e));
                    flvPlayers[device.id] = flvPlayer;
            }
        } else {
            placeholder.style.display = 'flex';
            videoElement.style.display = 'none';
            placeholder.textContent = device.isOnline ? t('waitingStream') : t('deviceOffline');
            
            if (flvPlayers[device.id]) {
                console.log(`Destroying player for: ${device.id}`);
                flvPlayers[device.id].destroy();
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
        if (!confirm(`${t('confirmDelete')} ${id}？${t('irreversible')}`)) return;

        try {
            const response = await fetch('/delete-device', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id })
            });
            const result = await response.json();
            if (!result.success) {
                alert(`${t('deleteFailed')}: ${result.message}`);
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
                alert(`${t(action + 'StreamErrorFail')}: ${result.message}`);
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
        const ws = new WebSocket(`ws://${window.location.host}`);

        ws.onopen = () => {
            console.log('WebSocket connected');
            connectionStatus.classList.add('show', 'connected');
            connectionStatus.classList.remove('disconnected');
            connectionText.textContent = t('connected');
            setTimeout(() => {
                connectionStatus.classList.remove('show');
            }, 3000);
        };

        ws.onmessage = (event) => {
            const data = JSON.parse(event.data);
            console.log('WebSocket message received:', data);

            if (data.type === 'deviceUpdate') {
                createOrUpdateCard(data.payload);
            } else if (data.type === 'deviceRemoved') {
                const card = deviceGrid.querySelector(`#device-${data.payload.id}`);
                if (card) card.remove();
                if (flvPlayers[data.payload.id]) {
                    flvPlayers[data.payload.id].destroy();
                    delete flvPlayers[data.payload.id];
                }
                devices = devices.filter(d => d.id !== data.payload.id);
                updateStats();
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

    initializeDashboard();
    connectWebSocket();
});
