/**
 * File Transfer Service
 * Responsible for downloading recording files from Android devices.
 *
 * Features:
 * - Resumable downloads
 * - MD5 integrity check
 * - Automatic retries
 * - Real-time progress updates
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const axios = require('axios');

class FileTransferService {
    constructor(videoBasePath, wsBroadcast) {
        this.videoBasePath = videoBasePath || path.join(__dirname, '..', 'video');
        this.wsBroadcast = wsBroadcast; // WebSocket broadcast function
        this.activeDownloads = new Map(); // Active downloads
        this.downloadQueue = []; // Download queue
        this.pausedTasks = new Map(); // Paused tasks (retry limit reached)
        this.completedTasks = new Map(); // Store completed or failed tasks
        this.maxConcurrentDownloads = 2;
        this.deleteAfterTransfer = false; // Control whether to delete files on the Android client after successful transfer
        
        // Ensure video directory exists
        this.ensureDirectoryExists(this.videoBasePath);
        
        console.log('📁 File transfer service initialized, save path:', this.videoBasePath);
    }

    /**
     * Scan for new files on a device.
     * @param {Object} device - Device information
     */
    async scanDeviceFiles(device) {
        // Check if device is online
        if (!device.isOnline) {
            console.log(`⚠️ Device ${device.id} is offline, skipping scan`);
            return [];
        }

        if (!device.status?.fileServerEnabled) {
            console.log(`⚠️ File server for device ${device.id} is not enabled`);
            return [];
        }

        const deviceIp = await this.getDeviceIp(device);
        if (!deviceIp) {
            console.log(`⚠️ Failed to get IP address for device ${device.id}`);
            return [];
        }

        const fileServerPort = device.status.fileServerPort || 8889;
        const fileListUrl = `http://${deviceIp}:${fileServerPort}/files`;

        try {
            const response = await axios.get(fileListUrl, { timeout: 5000 });
            const files = response.data;
            console.log(`📋 Device ${device.id} has ${files.length} files`);
            return files.map(file => ({
                ...file,
                deviceId: device.id,
                streamKey: device.status.streamKey || device.id,
                deviceIp,
                fileServerPort
            }));
        } catch (error) {
            console.error(`❌ Failed to get file list for device ${device.id}:`, error.message);
            return [];
        }
    }

    /**
     * Get the device IP address (from WebSocket connection info).
     */
    async getDeviceIp(device) {
        // This needs to get the IP from the device status.
        // Simple implementation: assume it's extracted from streamUrl.
        // In practice, it should be obtained from the WebSocket connection info.
        return device.ip || null;
    }

    /**
     * Add a download task to the queue.
     * @returns {boolean} Whether the task was successfully added.
     */
    async addDownloadTask(fileInfo) {
        const taskId = `${fileInfo.deviceId}_${fileInfo.name}`;
        
        // Check if already downloading
        if (this.activeDownloads.has(taskId)) {
            // console.log(`⚠️ Task ${taskId} is already in active downloads`);
            return false;
        }

        // Check if already in the waiting queue
        if (this.downloadQueue.some(task => task.id === taskId)) {
            // console.log(`⚠️ Task ${taskId} is already in the waiting queue`);
            return false;
        }

        // Check if the file already exists and is complete
        const targetPath = this.getTargetFilePath(fileInfo);
        if (fs.existsSync(targetPath)) {
            console.log(`✅ File ${fileInfo.name} already exists locally, skipping download.`);
            // If the file is complete, remove it from paused tasks (if it exists)
            this.pausedTasks.delete(taskId);
            return false;
        }

        // Check for paused tasks (paused after retry failures)
        if (this.pausedTasks.has(taskId)) {
            const pausedTask = this.pausedTasks.get(taskId);
            console.log(`🔄 Resuming paused task: ${taskId} (previously failed: ${pausedTask.error})`);
            
            // Reset task status, prepare to re-download
            pausedTask.status = 'pending';
            pausedTask.retryCount = 0; // Reset retry count
            pausedTask.error = null;
            pausedTask.fileInfo = fileInfo; // Update file info (IP address might have changed)
            
            // Move to download queue
            this.downloadQueue.push(pausedTask);
            this.pausedTasks.delete(taskId);
            this.processQueue();
            return true;
        }

        const task = {
            id: taskId,
            fileInfo,
            targetPath,
            status: 'pending',
            progress: 0,
            downloadedBytes: 0,
            totalBytes: fileInfo.size,
            error: null,
            retryCount: 0
        };

        this.downloadQueue.push(task);
        console.log(`➕ Added download task: ${task.id}`);
        
        this.processQueue();
        return true;
    }

    /**
     * Process the download queue.
     */
    async processQueue() {
        while (this.activeDownloads.size < this.maxConcurrentDownloads && this.downloadQueue.length > 0) {
            const task = this.downloadQueue.shift();
            this.activeDownloads.set(task.id, task);
            this.downloadFile(task);
        }
    }

    /**
     * Download a file (with resume support).
     */
    async downloadFile(task) {
        const { fileInfo, targetPath } = task;
        const downloadUrl = `http://${fileInfo.deviceIp}:${fileInfo.fileServerPort}/download/${fileInfo.name}`;
        
        task.status = 'downloading';
        this.broadcastProgress(task);

        try {
            // Check downloaded size (for resume support)
            const downloadedSize = fs.existsSync(targetPath) ? fs.statSync(targetPath).size : 0;
            
            if (downloadedSize > 0 && downloadedSize < fileInfo.size) {
                console.log(`🔄 Resuming file ${fileInfo.name}, downloaded ${downloadedSize} bytes`);
            }

            const headers = {};
            if (downloadedSize > 0) {
                headers['Range'] = `bytes=${downloadedSize}-`;
            }

            const response = await axios({
                method: 'get',
                url: downloadUrl,
                headers,
                responseType: 'stream',
                timeout: 30000
            });

            // Ensure target directory exists
            this.ensureDirectoryExists(path.dirname(targetPath));

            // Write to file
            const writer = fs.createWriteStream(targetPath, { flags: downloadedSize > 0 ? 'a' : 'w' });
            
            task.downloadedBytes = downloadedSize;

            // Use time-based throttling for smoother progress updates
            let lastBroadcastTime = 0;
            const BROADCAST_INTERVAL_MS = 250; // Update every 250ms for a smoother UI

            response.data.on('data', (chunk) => {
                task.downloadedBytes += chunk.length;
                task.progress = Math.floor((task.downloadedBytes / task.totalBytes) * 100);
                
                // Broadcast progress based on a time interval
                const now = Date.now();
                if (now - lastBroadcastTime > BROADCAST_INTERVAL_MS) {
                    this.broadcastProgress(task);
                    lastBroadcastTime = now;
                }
            });

            response.data.pipe(writer);

            await new Promise((resolve, reject) => {
                writer.on('finish', () => {
                    // Ensure the final 100% progress is broadcast before verification
                    task.progress = 100;
                    this.broadcastProgress(task);
                    resolve();
                });
                writer.on('error', reject);
                response.data.on('error', reject);
            });

            console.log(`✅ File download completed: ${fileInfo.name}`);
            
            // MD5 checksum
            await this.verifyAndCleanup(task);

        } catch (error) {
            console.error(`❌ File download failed: ${fileInfo.name}`, error.message);
            task.error = error.message;
            task.status = 'failed';
            
            // Check if error is due to network/connection issues
            const isNetworkError = error.code === 'ECONNREFUSED' || 
                                  error.code === 'ETIMEDOUT' || 
                                  error.code === 'ENOTFOUND' ||
                                  error.message.includes('timeout') ||
                                  error.message.includes('connect');
            
            // Retry logic
            if (task.retryCount < 3) {
                task.retryCount++;
                console.log(`🔄 Retrying download (${task.retryCount}/3): ${fileInfo.name}`);
                task.status = 'retrying';
                this.broadcastProgress(task);
                
                // Use longer delay for network errors
                const delay = isNetworkError ? 10000 * task.retryCount : 5000 * task.retryCount;
                
                setTimeout(() => {
                    this.downloadFile(task);
                }, delay);
            } else {
                console.log(`⏸️ Task ${task.id} has reached the retry limit, pausing task (will resume automatically when device is online)`);
                task.status = 'paused';
                this.broadcastProgress(task);
                
                // Move the task to the paused list, preserving downloaded progress
                this.pausedTasks.set(task.id, task);
                this.activeDownloads.delete(task.id);
                
                // Clean up duplicate tasks in the queue (possibly added by periodic scans)
                this.downloadQueue = this.downloadQueue.filter(t => t.id !== task.id);
                
                this.processQueue();
            }
        }
    }

    /**
     * Verify the file and clean up on the Android client.
     */
    async verifyAndCleanup(task) {
        const { fileInfo, targetPath } = task;
        
        task.status = 'verifying';
        this.broadcastProgress(task);

        try {
            // Validate file size
            const actualSize = fs.statSync(targetPath).size;
            if (actualSize !== fileInfo.size) {
                throw new Error(`File size mismatch: expected ${fileInfo.size}, got ${actualSize}`);
            }

            // Calculate and validate MD5 (if provided by Android client)
            const localMd5 = await this.calculateMD5(targetPath);
            console.log(`🔐 Local file MD5: ${localMd5}`);
            
            if (fileInfo.md5 && fileInfo.md5.length > 0) {
                console.log(`🔐 Android client MD5: ${fileInfo.md5}`);
                if (localMd5 !== fileInfo.md5) {
                    throw new Error(`MD5 checksum failed: local=${localMd5}, android=${fileInfo.md5}`);
                }
                console.log(`✅ MD5 checksum passed`);
            } else {
                console.log(`⚠️ Android client did not provide MD5, validating file size only`);
            }

            console.log(`✅ File verification passed: ${fileInfo.name}`);
            
            if (this.deleteAfterTransfer) {
                // Delete file on Android client
                await this.deleteRemoteFile(fileInfo);
            } else {
                console.log('📂 Skipping remote file deletion as per configuration.');
            }

            task.status = 'completed';
            task.progress = 100;
            task.md5 = localMd5;
            this.broadcastProgress(task);
            this.completedTasks.set(task.id, task); // Keep completed task in history

        } catch (error) {
            console.error(`❌ File verification failed: ${fileInfo.name}`, error.message);
            task.error = error.message;
            task.status = 'verification_failed';
            this.broadcastProgress(task);
            this.completedTasks.set(task.id, task); // Keep failed task in history
            
            // Delete corrupted file
            if (fs.existsSync(targetPath)) {
                fs.unlinkSync(targetPath);
                console.log(`🗑️ Deleted corrupted file: ${targetPath}`);
            }
        } finally {
            this.activeDownloads.delete(task.id);
            this.processQueue();
        }
    }

    /**
     * Delete the file on the Android client.
     */
    async deleteRemoteFile(fileInfo) {
        const deleteUrl = `http://${fileInfo.deviceIp}:${fileInfo.fileServerPort}/delete/${fileInfo.name}`;
        
        try {
            await axios.delete(deleteUrl, { timeout: 5000 });
            console.log(`🗑️ Deleted file on Android client: ${fileInfo.name}`);
        } catch (error) {
            console.error(`⚠️ Failed to delete file on Android client: ${fileInfo.name}`, error.message);
            // Do not throw an error, as the file has been successfully downloaded to the PC.
        }
    }

    /**
     * Calculate the MD5 hash of a file.
     */
    calculateMD5(filePath) {
        return new Promise((resolve, reject) => {
            const hash = crypto.createHash('md5');
            const stream = fs.createReadStream(filePath);
            
            stream.on('data', (data) => hash.update(data));
            stream.on('end', () => resolve(hash.digest('hex')));
            stream.on('error', reject);
        });
    }

    /**
     * Check if a file is complete.
     */
    async isFileComplete(filePath, expectedSize) {
        if (!fs.existsSync(filePath)) {
            return false;
        }
        
        const actualSize = fs.statSync(filePath).size;
        return actualSize === expectedSize;
    }

    /**
     * Get the target file path.
     * Format: video/{streamKey}_{deviceId}/{filename}
     */
    getTargetFilePath(fileInfo) {
        const deviceFolder = `${fileInfo.streamKey}_${fileInfo.deviceId}`;
        return path.join(this.videoBasePath, deviceFolder, fileInfo.name);
    }

    /**
     * Ensure a directory exists.
     */
    ensureDirectoryExists(dirPath) {
        if (!fs.existsSync(dirPath)) {
            fs.mkdirSync(dirPath, { recursive: true });
            console.log(`📁 Created directory: ${dirPath}`);
        }
    }

    /**
     * Broadcast download progress.
     */
    broadcastProgress(task) {
        if (this.wsBroadcast) {
            this.wsBroadcast({
                type: 'fileTransferProgress',
                payload: {
                    id: task.id,
                    fileName: task.fileInfo.name,
                    deviceId: task.fileInfo.deviceId,
                    streamKey: task.fileInfo.streamKey,
                    status: task.status,
                    progress: task.progress,
                    downloadedBytes: task.downloadedBytes,
                    totalBytes: task.totalBytes,
                    error: task.error,
                    retryCount: task.retryCount
                }
            });
        }
    }

    /**
     * Resume paused tasks for a specific device.
     * Called when a device comes back online.
     */
    resumePausedTasksForDevice(deviceId) {
        const pausedTasks = Array.from(this.pausedTasks.values()).filter(
            task => task.fileInfo.deviceId === deviceId
        );
        
        if (pausedTasks.length === 0) {
            return 0;
        }
        
        console.log(`🔄 Found ${pausedTasks.length} paused tasks for device ${deviceId}, resuming...`);
        
        let resumedCount = 0;
        for (const task of pausedTasks) {
            // Reset task status
            task.status = 'pending';
            task.retryCount = 0;
            task.error = null;
            
            // Move to download queue
            this.downloadQueue.push(task);
            this.pausedTasks.delete(task.id);
            resumedCount++;
            
            console.log(`✅ Resumed task: ${task.id}`);
        }
        
        // Process the queue
        this.processQueue();
        
        return resumedCount;
    }

    /**
     * Update device IP for paused tasks (when device IP changes after reconnection).
     */
    updateDeviceIpForPausedTasks(deviceId, newIp, fileServerPort) {
        const pausedTasks = Array.from(this.pausedTasks.values()).filter(
            task => task.fileInfo.deviceId === deviceId
        );
        
        for (const task of pausedTasks) {
            task.fileInfo.deviceIp = newIp;
            task.fileInfo.fileServerPort = fileServerPort || 8889;
            console.log(`🔄 Updated IP for paused task ${task.id}: ${newIp}:${fileServerPort}`);
        }
    }

    /**
     * Get the status of all download tasks.
     */
    getAllTasks() {
        const tasks = [];
        
        // Active tasks
        for (const task of this.activeDownloads.values()) {
            tasks.push({
                id: task.id,
                fileName: task.fileInfo.name,
                deviceId: task.fileInfo.deviceId,
                streamKey: task.fileInfo.streamKey,
                status: task.status,
                progress: task.progress,
                downloadedBytes: task.downloadedBytes,
                totalBytes: task.totalBytes,
                error: task.error
            });
        }
        
        // Queued tasks
        for (const task of this.downloadQueue) {
            tasks.push({
                id: task.id,
                fileName: task.fileInfo.name,
                deviceId: task.fileInfo.deviceId,
                streamKey: task.fileInfo.streamKey,
                status: 'queued',
                progress: 0,
                downloadedBytes: 0,
                totalBytes: task.totalBytes,
                error: null
            });
        }
        
        // Paused tasks
        for (const task of this.pausedTasks.values()) {
            tasks.push({
                id: task.id,
                fileName: task.fileInfo.name,
                deviceId: task.fileInfo.deviceId,
                streamKey: task.fileInfo.streamKey,
                status: 'paused',
                progress: task.progress,
                downloadedBytes: task.downloadedBytes,
                totalBytes: task.totalBytes,
                error: task.error
            });
        }
        
        // Completed and Failed tasks
        for (const task of this.completedTasks.values()) {
            tasks.push({
                id: task.id,
                fileName: task.fileInfo.name,
                deviceId: task.fileInfo.deviceId,
                streamKey: task.fileInfo.streamKey,
                status: task.status,
                progress: task.progress,
                downloadedBytes: task.downloadedBytes,
                totalBytes: task.totalBytes,
                error: task.error
            });
        }
        
        return tasks;
    }
}

module.exports = FileTransferService;

