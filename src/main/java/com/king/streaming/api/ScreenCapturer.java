package com.king.streaming.api;

public interface ScreenCapturer {
    void startCapture();
    // In a real implementation this might return a native pointer or a byte array 
    // representing the frame buffer to avoid memory copies.
    byte[] getNextFrame();
    void stopCapture();
    boolean isSupported();
}
