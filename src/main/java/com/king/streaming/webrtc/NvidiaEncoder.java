package com.king.streaming.webrtc;

import com.king.streaming.api.VideoEncoder;
import com.king.util.AuditLogger;
import com.king.util.FfmpegResolver;

/**
 * King of Lab — NVIDIA NVENC Hardware Encoder.
 *
 * This class requires NVENC support on the GPU.  It auto-detects availability
 * via FFmpeg's encoder list at runtime.  If NVENC is not present the pipeline
 * falls back to H264SoftwareEncoder which handles libx264 / AMF / QSV.
 *
 * In the current implementation the encoding is delegated to H264SoftwareEncoder
 * (which itself uses an FFmpeg pipe with -c:v h264_nvenc when available).
 * This class is kept as a named entry point so existing code that instantiates
 * NvidiaEncoder continues to compile and work — it simply delegates internally.
 */
public class NvidiaEncoder implements VideoEncoder {

    private final H264SoftwareEncoder delegate = new H264SoftwareEncoder();
    private boolean nvencDetected = false;

    @Override
    public void initialize(int width, int height, int fps, int bitrateBps) {
        nvencDetected = probeNvenc();
        AuditLogger.logSystem("[NvidiaEncoder] NVENC available: " + nvencDetected
                + " — delegating to H264SoftwareEncoder");
        delegate.initialize(width, height, fps, bitrateBps);
    }

    @Override
    public byte[] encodeFrame(byte[] rawFrame) {
        return delegate.encodeFrame(rawFrame);
    }

    @Override
    public void setBitrate(int bitrateBps) {
        delegate.setBitrate(bitrateBps);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public boolean isSupported() {
        return nvencDetected;
    }

    public boolean isNvencActive() { return nvencDetected; }

    // -----------------------------------------------------------------------
    // Detection helper
    // -----------------------------------------------------------------------

    private static boolean probeNvenc() {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{FfmpegResolver.get(), "-encoders", "-hide_banner"});
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out).contains("h264_nvenc");
        } catch (Exception e) {
            return false;
        }
    }
}
