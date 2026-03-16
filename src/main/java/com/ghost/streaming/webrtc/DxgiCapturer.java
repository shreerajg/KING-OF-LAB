package com.ghost.streaming.webrtc;

import com.ghost.streaming.api.ScreenCapturer;

public class DxgiCapturer implements ScreenCapturer {

    private boolean isCapturing;

    @Override
    public void startCapture() {
        System.out.println("Starting DXGI Desktop Duplication Hook...");
        isCapturing = true;
    }

    @Override
    public byte[] getNextFrame() {
        if (!isCapturing) return null;
        // In a real implementation this copies native memory via JNA
        return new byte[1920 * 1080 * 4]; // Dummy frame
    }

    @Override
    public void stopCapture() {
        System.out.println("Stopping DXGI Capture...");
        isCapturing = false;
    }

    @Override
    public boolean isSupported() {
        // Will check for Windows and DXGI support via native call
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win");
    }
}
