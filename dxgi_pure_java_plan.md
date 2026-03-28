# Pure-Java True DXGI Capture Pipeline (via FFmpeg ddagrab)

This document outlines how to strictly fulfill the requirements for OBS-style DXGI (Desktop Duplication API) GPU capture **without writing any C++ or JNI code**, keeping the project 100% pure Java.

## The Secret: FFmpeg `ddagrab`

FFmpeg 6.0+ introduced the `ddagrab` video filter. This filter internally uses the exact same C++ DirectX 11 Desktop Duplication API that OBS, Parsec, and Sunshine use. 

Because it's a hardware API, you can specify `draw_mouse=0` to ensure the cursor is **never** grabbed by the GPU compositor. It completely bypasses the Windows cursor layer, guaranteeing absolutely zero flicker.

## The Architecture

Instead of Java taking screenshots and passing them to FFmpeg, Java spawns ONE FFmpeg process that does the entire GPU pipeline natively, and spits out a binary stream of H.264 NAL units to `stdout`. Java just reads the bytes and sends them over TCP.

**GPU Framebuffer → NVENC H.264 Hardware Encoder → stdout → Java `InputStream` → Admin TCP Socket**

### The Magic Command
```bash
ffmpeg -loglevel quiet \
       -f lavfi -i "ddagrab=draw_mouse=0:framerate=60" \
       -c:v h264_nvenc -preset ll -tune zerolatency \
       -b:v 3000k -maxrate 3000k -bufsize 6000k \
       -g 120 \
       -f h264 pipe:1
```

*(Note: If NVENC is unavailable on the student machine, the fallback is `-c:v libx264 -preset ultrafast -tune zerolatency`)*

## Implementation Blueprint

### 1. [ScreenCapturer](file:///c:/Users/shree/OneDrive/Desktop/KING/src/main/java/com/king/streaming/api/ScreenCapturer.java#3-11) Interface update
You don't need [DxgiCapturer](file:///c:/Users/shree/OneDrive/Desktop/KING/src/main/java/com/king/streaming/webrtc/DxgiCapturer.java#57-343) to take screenshots anymore. The entire [DxgiCapturer](file:///c:/Users/shree/OneDrive/Desktop/KING/src/main/java/com/king/streaming/webrtc/DxgiCapturer.java#57-343) and [H264SoftwareEncoder](file:///c:/Users/shree/OneDrive/Desktop/KING/src/main/java/com/king/streaming/webrtc/H264SoftwareEncoder.java#31-227) can be merged into one class: `UltraStreamPipeline.java`.

### 2. `UltraStreamPipeline.java`
```java
public class UltraStreamPipeline {
    private Process ffmpeg;
    private InputStream h264Stream;
    private final AtomicReference<byte[]> latestNalUnit = new AtomicReference<>(null);

    public void start() {
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-loglevel", "quiet",
            "-f", "lavfi", "-i", "ddagrab=draw_mouse=0:framerate=60",
            "-c:v", "h264_nvenc", "-preset", "ll", "-tune", "zerolatency",
            "-b:v", "3M", "-f", "h264", "pipe:1"
        );
        ffmpeg = pb.start();
        h264Stream = ffmpeg.getInputStream();
        
        // Background thread to read H.264 NAL units (byte stream) from FFmpeg
        new Thread(this::readNalUnits).start();
    }

    private void readNalUnits() {
        // Read raw H.264 bytes and find NAL boundaries (0x00 0x00 0x00 0x01)
        // Store the completed NAL payload in latestNalUnit.set(payload)
    }

    // KingClient calls this at 60 FPS:
    public byte[] getNextFrame() {
        return latestNalUnit.getAndSet(null); // Frame-drop semantics
    }
}
```

### 3. KingClient Integration
Currently, `KingClient.startUltraBinaryStream()` polls [DxgiCapturer](file:///c:/Users/shree/OneDrive/Desktop/KING/src/main/java/com/king/streaming/webrtc/DxgiCapturer.java#57-343), gets a JPEG, passes it to [H264SoftwareEncoder](file:///c:/Users/shree/OneDrive/Desktop/KING/src/main/java/com/king/streaming/webrtc/H264SoftwareEncoder.java#31-227), gets H.264 bytes, and writes to TCP.

**New Flow:**
[KingClient](file:///c:/Users/shree/OneDrive/Desktop/KING/src/main/java/com/king/net/KingClient.java#35-521) polls `UltraStreamPipeline.getNextFrame()`, immediately gets an H.264 NAL unit, and writes it directly to the binary socket.

## Why this is the ultimate solution:
1. **Zero Java Overhead:** Java isn't touching any pixels. The GPU copies the screen, NVENC compresses it, and Java just ferries a few kilobytes of network bytes.
2. **Zero Flicker:** `ddagrab` isolates the cursor at the GPU driver level.
3. **No C++ Required:** Avoids all the pain of MSVC, JNA, memory leaks, and compiling DLLs.
4. **Latency:** identical to Parsec/OBS because it's the exact same internal DXGI+NVENC pipeline.
