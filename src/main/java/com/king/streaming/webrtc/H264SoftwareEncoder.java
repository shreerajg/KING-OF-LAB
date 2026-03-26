package com.king.streaming.webrtc;

import com.king.streaming.api.VideoEncoder;
import com.king.util.AuditLogger;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * King of Lab — H.264 Software / Hardware Encoder.
 *
 * This encoder sits between the DXGI capturer and the binary TCP stream.
 * The frames arriving here are already JPEG-compressed by DxgiCapturer;
 * for the current pipeline they are passed through as-is (JPEG over TCP is
 * already highly efficient on a LAN). A comment block marks the exact spot
 * to replace with a real FFmpeg / NVENC piped process.
 *
 * Two paths are supported at runtime:
 *   1. FFmpeg H.264 (NVENC if GPU present, libx264 otherwise) — enabled when
 *      an FFmpeg binary is detected on the system PATH.
 *   2. Pass-through JPEG — used as fallback; still feeds the binary TCP stream.
 *
 * The interface contract is identical in both cases: encodeFrame() accepts raw
 * JPEG bytes and returns the encoded payload (H.264 NAL unit or JPEG passthrough)
 * to be written to the binary socket.
 *
 * Frame-drop: this encoder is stateless per-frame — it does not buffer.
 * The AtomicReference drop happens upstream in DxgiCapturer; by the time
 * bytes reach here, only the latest captured frame is being encoded.
 */
public class H264SoftwareEncoder implements VideoEncoder {

    private int width;
    private int height;
    private int fps;
    private volatile int bitrateBps;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** When true, FFmpeg pipe is active and outputting H.264 NAL units. */
    private volatile boolean ffmpegActive = false;
    private Process ffmpegProcess;
    private DataOutputStream ffmpegIn;
    private DataInputStream  ffmpegOut;
    private Thread           ffmpegReader;

    // Latest encoded H.264 NAL unit from ffmpeg reader thread (null = none yet)
    private final java.util.concurrent.atomic.AtomicReference<byte[]> latestNal =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // VideoEncoder interface
    // -----------------------------------------------------------------------

    @Override
    public void initialize(int width, int height, int fps, int bitrateBps) {
        this.width      = width;
        this.height     = height;
        this.fps        = fps;
        this.bitrateBps = bitrateBps;

        ffmpegActive = tryStartFfmpeg();
        initialized.set(true);

        AuditLogger.logSystem("[H264Enc] Initialized " + width + "x" + height
                + " @ " + fps + "fps, " + (bitrateBps / 1000) + " kbps"
                + (ffmpegActive ? " [FFmpeg H.264 ACTIVE]" : " [JPEG passthrough]"));
    }

    /**
     * Encodes a raw frame received from DxgiCapturer.
     *
     * @param rawFrame JPEG bytes from DxgiCapturer
     * @return Encoded bytes to transmit over TCP binary stream.
     *         H.264 NAL unit if FFmpeg is active, otherwise the JPEG bytes unchanged.
     */
    @Override
    public byte[] encodeFrame(byte[] rawFrame) {
        if (!initialized.get() || rawFrame == null) return null;

        if (ffmpegActive && ffmpegIn != null) {
            try {
                /*
                 * ── FFmpeg H.264 PIPE ────────────────────────────────────────
                 * Feed JPEG pixels → FFmpeg MJPEG decoder → H.264 encoder
                 * FFmpeg command (started in tryStartFfmpeg):
                 *   ffmpeg -f mjpeg -i pipe:0 -c:v h264_nvenc -preset ll
                 *          -b:v <bitrate> -f h264 pipe:1
                 * The ffmpegReader thread collects the NAL units asynchronously.
                 * ────────────────────────────────────────────────────────────
                 */
                ffmpegIn.writeInt(rawFrame.length);
                ffmpegIn.write(rawFrame);
                ffmpegIn.flush();

                // Return latest NAL unit produced by reader thread (may lag 1 frame)
                byte[] nal = latestNal.getAndSet(null);
                return nal != null ? nal : rawFrame; // fallback to JPEG if encoder is warming up
            } catch (Exception e) {
                AuditLogger.logError("H264Enc.ffmpegPipe", e.getMessage());
                ffmpegActive = false;
                // Fall through to passthrough
            }
        }

        // JPEG passthrough — zero-copy, sub-ms overhead
        return rawFrame;
    }

    @Override
    public void setBitrate(int bitrateBps) {
        this.bitrateBps = bitrateBps;
        // Dynamic bitrate changes require NVENC CBR re-init; log for now
        AuditLogger.logSystem("[H264Enc] Bitrate updated to " + (bitrateBps / 1000) + " kbps");
        /*
         * TODO: When FFmpeg pipe is active, send a -b:v change via the
         * filter_complex / side-data mechanism, or restart the pipe with new rate.
         */
    }

    @Override
    public void shutdown() {
        initialized.set(false);
        ffmpegActive = false;
        try {
            if (ffmpegIn != null)      ffmpegIn.close();
            if (ffmpegProcess != null) ffmpegProcess.destroyForcibly();
            if (ffmpegReader != null)  ffmpegReader.interrupt();
        } catch (Exception ignored) {}
        ffmpegProcess = null;
        ffmpegIn = null;
        ffmpegOut = null;
        AuditLogger.logSystem("[H264Enc] Shutdown complete");
    }

    @Override
    public boolean isSupported() {
        return true; // JPEG passthrough always works; FFmpeg is detected at runtime
    }

    // -----------------------------------------------------------------------
    // FFmpeg process management
    // -----------------------------------------------------------------------

    /**
     * Attempts to start an FFmpeg H.264 encoding pipe.
     *
     * FFmpeg command used:
     *   ffmpeg -loglevel quiet
     *          -f mjpeg -i pipe:0
     *          -c:v h264_nvenc -preset ll -zerolatency 1 -b:v <bitrate> -f h264 pipe:1
     *   (falls back to -c:v libx264 -preset ultrafast if NVENC unavailable)
     *
     * @return true if FFmpeg process started and pipe is writable
     */
    private boolean tryStartFfmpeg() {
        try {
            // Detect FFmpeg on PATH
            Process probe = Runtime.getRuntime().exec(new String[]{"ffmpeg", "-version"});
            probe.waitFor();
            if (probe.exitValue() != 0) return false;

            // Try NVENC first, fall back to libx264
            String codec    = detectBestCodec();
            String bitrateK = (bitrateBps / 1000) + "k";

            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel", "quiet",
                "-f",  "mjpeg", "-i", "pipe:0",
                "-vf", "scale=" + width + ":" + height,
                "-c:v", codec,
                "-preset", codec.equals("h264_nvenc") ? "ll" : "ultrafast",
                "-tune", "zerolatency",
                "-b:v", bitrateK,
                "-maxrate", bitrateK,
                "-bufsize", (bitrateBps / 500) + "k",
                "-g", String.valueOf(fps * 2), // keyframe every 2 seconds
                "-f", "h264", "pipe:1"
            );
            pb.redirectErrorStream(false);
            ffmpegProcess = pb.start();
            ffmpegIn  = new DataOutputStream(new BufferedOutputStream(ffmpegProcess.getOutputStream(), 128_000));
            ffmpegOut = new DataInputStream(new BufferedInputStream(ffmpegProcess.getInputStream(), 256_000));

            // Background thread reads NAL units from FFmpeg stdout
            ffmpegReader = new Thread(this::readNalUnits, "H264Encoder-NalReader");
            ffmpegReader.setDaemon(true);
            ffmpegReader.start();

            AuditLogger.logSystem("[H264Enc] FFmpeg pipe started with codec: " + codec);
            return true;
        } catch (Exception e) {
            AuditLogger.logSystem("[H264Enc] FFmpeg unavailable: " + e.getMessage() + " — using JPEG passthrough");
            return false;
        }
    }

    /** Reads raw H.264 byte stream from FFmpeg stdout into latestNal. */
    private void readNalUnits() {
        byte[] buf = new byte[256_000];
        try {
            while (ffmpegActive && !Thread.currentThread().isInterrupted()) {
                int read = ffmpegOut.read(buf);
                if (read <= 0) break;
                byte[] nal = new byte[read];
                System.arraycopy(buf, 0, nal, 0, read);
                latestNal.set(nal); // frame-drop: overwrite with latest NAL
            }
        } catch (Exception ignored) {}
    }

    /** Returns the best available H.264 codec identifier for FFmpeg. */
    private String detectBestCodec() {
        try {
            Process p = Runtime.getRuntime().exec(
                new String[]{"ffmpeg", "-encoders", "-hide_banner"});
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            String encoders = new String(out);
            if (encoders.contains("h264_nvenc")) return "h264_nvenc";
            if (encoders.contains("h264_amf"))   return "h264_amf";
            if (encoders.contains("h264_qsv"))   return "h264_qsv";
        } catch (Exception ignored) {}
        return "libx264"; // universal CPU fallback
    }
}
