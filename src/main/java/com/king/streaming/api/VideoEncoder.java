package com.king.streaming.api;

public interface VideoEncoder {
    void initialize(int width, int height, int fps, int bitratebps);
    // Encode a raw frame into NAL units (H264)
    byte[] encodeFrame(byte[] rawFrame);
    void setBitrate(int bitratebps);
    void shutdown();
    boolean isSupported();
}
