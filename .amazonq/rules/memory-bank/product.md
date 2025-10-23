# Product Overview

## Project Purpose
SquidRun is an Android application designed for THINKLET devices that enables live streaming via RTMP(S) protocol. The project provides a complete solution for broadcasting live video and audio from THINKLET wearable devices to any RTMP(S) server, including YouTube Live and other streaming platforms.

## Key Features

### Core Streaming Capabilities
- **RTMP(S) Live Streaming**: Broadcast to any RTMP(S) server with configurable stream URLs and keys
- **Flexible Resolution Support**: Configurable video resolution with long-side and short-side parameters (default 720x480)
- **Audio/Video Configuration**: Customizable bitrates, sample rates, and channel configurations
- **Multi-Microphone Support**: Three microphone modes (Android standard, THINKLET 5-mic, THINKLET 6-mic with reference audio)
- **Echo Cancellation**: Optional echo canceler for improved audio quality
- **Preview Mode**: Optional on-screen streaming preview for debugging

### Device Control
- **Button Controls**: 
  - Button 2: Start/stop streaming
  - Button 3: Toggle audio mute
  - Power button: Stop streaming and exit app
- **Vibration Feedback**: Error notifications and operation confirmations via vibration patterns
- **Orientation Support**: Automatic or forced landscape/portrait orientation based on device model

### Companion Streaming Server
- **Web-Based Viewer**: Node.js/Express server for viewing low-latency streams
- **File Transfer Service**: WebSocket-based file transfer between THINKLET and server
- **Device Management**: Multi-device configuration and connection management
- **Docker Support**: Containerized deployment with SRS (Simple RTMP Server)

## Target Users

### Primary Users
- **Content Creators**: Live streamers using THINKLET wearable devices for hands-free broadcasting
- **Field Professionals**: Workers needing to stream live video from remote locations
- **Event Coverage**: Journalists and documentarians requiring mobile live streaming

### Use Cases
- **Live Event Broadcasting**: Stream events to YouTube Live or other platforms
- **Remote Collaboration**: Share live video feed with remote teams
- **Training and Documentation**: Record and stream training sessions or procedures
- **Testing and Development**: Local RTMP server setup for testing streaming functionality

## Value Proposition
SquidRun transforms THINKLET wearable devices into professional live streaming tools, offering:
- Hands-free operation ideal for mobile scenarios
- Advanced multi-microphone audio capture unique to THINKLET hardware
- Flexible configuration for various streaming quality requirements
- Complete solution including both device app and companion server infrastructure
