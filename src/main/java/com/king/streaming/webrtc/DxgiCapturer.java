package com.king.streaming.webrtc;

import com.king.streaming.api.ScreenCapturer;
import com.king.util.AuditLogger;
import com.king.util.FfmpegResolver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DxgiCapturer — Flicker-Free Screen Capture for Ultra Stream Mode
 *
 * ─────────────────────────────────────────────────────────────────
 * ROOT CAUSE OF CURSOR FLICKER:
 *   The original implementation used -f gdigrab which calls Windows
 *   GDI BitBlt() + GetCursorInfo() every frame.  Even with -draw_mouse 0,
 *   Windows hides and redraws the hardware cursor at the display-driver
 *   level during each GDI capture — causing visible 30/60 Hz flicker.
 *
 * FIX:
 *   Use -f lavfi -i ddagrab (DXGI Desktop Duplication).
 *   ddagrab captures at the GPU/DXGI layer and NEVER calls GetCursorInfo().
 *   The hardware cursor is completely unaffected → zero flicker.
 *
 *   Fallback: gdigrab with draw_mouse=0 for systems without DXGI support.
 * ─────────────────────────────────────────────────────────────────
 */
public class DxgiCapturer implements ScreenCapturer {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    /**
     * Latest raw JPEG bytes from FFmpeg stdout.
     * null = no new frame since last poll (frame-drop semantics).
     */
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>(null);

    private volatile Process ffmpegProc;
    private volatile Thread readerThread;

    // -----------------------------------------------------------------------
    // ScreenCapturer interface
    // -----------------------------------------------------------------------

    @Override
    public void startCapture() {
        if (isCapturing.getAndSet(true))
            return; // already running

        // Try ddagrab (DXGI) first — zero cursor interaction, zero flicker
        if (tryStartDdagrab()) return;

        // Fallback: gdigrab with draw_mouse=0
        tryStartGdigrab();
    }

    /**
     * Attempts to start the FFmpeg ddagrab pipeline.
     * ddagrab uses DXGI Desktop Duplication and does NOT touch the Windows
     * cursor system at all — eliminating cursor flicker entirely.
     *
     * @return true if ddagrab started successfully
     */
    private boolean tryStartDdagrab() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                FfmpegResolver.get(),
                "-loglevel", "quiet",
                "-threads",  "2",
                "-f",        "lavfi",
                "-i",        "ddagrab=output_idx=0:framerate=20:draw_mouse=0",  // 20 FPS — cut GPU load
                "-vf",       "hwdownload,format=bgra,scale=1280:-2,format=yuv420p",  // 1280px wide → ~55% less GPU
                "-f",        "mjpeg",
                "-q:v",      "5",
                "pipe:1"
            );
            ffmpegProc = pb.start();
            drainStderr(ffmpegProc);

            // Wait briefly to confirm FFmpeg started outputting frames
            Thread.sleep(500);
            if (!ffmpegProc.isAlive()) {
                AuditLogger.logSystem("[DxgiCapturer] ddagrab not available — trying gdigrab fallback");
                ffmpegProc = null;
                return false;
            }

            readerThread = new Thread(this::readMjpeg, "DxgiCapturer-ddagrab-Reader");
            readerThread.setDaemon(true);
            readerThread.setPriority(Thread.NORM_PRIORITY);  // was MAX_PRIORITY-1
            readerThread.start();

            AuditLogger.logSystem("[DxgiCapturer] ddagrab MJPEG pipe started (DXGI, cursor-free, 20 FPS)");
            return true;

        } catch (Exception e) {
            AuditLogger.logError("DxgiCapturer.tryStartDdagrab", "ddagrab launch failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fallback: gdigrab with draw_mouse=0.
     * NOTE: gdigrab may still cause slight cursor flicker on some Windows
     * driver versions due to GDI cursor hooks, but draw_mouse=0 minimises it.
     */
    private void tryStartGdigrab() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                FfmpegResolver.get(),
                "-loglevel",   "quiet",
                "-threads",    "2",
                "-f",          "gdigrab",
                "-framerate",  "20",            // 20 FPS fallback
                "-draw_mouse", "0",
                "-i",          "desktop",
                "-vf",         "scale=1280:-2", // cap resolution
                "-f",          "mjpeg",
                "-q:v",        "5",
                "pipe:1"
            );
            ffmpegProc = pb.start();
            drainStderr(ffmpegProc);

            readerThread = new Thread(this::readMjpeg, "DxgiCapturer-gdigrab-Reader");
            readerThread.setDaemon(true);
            readerThread.setPriority(Thread.NORM_PRIORITY);  // was MAX_PRIORITY-1
            readerThread.start();

            AuditLogger.logSystem("[DxgiCapturer] gdigrab MJPEG pipe started (fallback, draw_mouse=0, 20 FPS)");

        } catch (Exception e) {
            isCapturing.set(false);
            AuditLogger.logError("DxgiCapturer.startCapture", "Failed to launch FFmpeg: " + e.getMessage());
        }
    }

    /**
     * Returns the latest captured JPEG frame and clears the reference.
     * Returns null if no new frame has arrived since the last call.
     * Raw bytes are passed directly — NO decode, NO re-encode.
     */
    @Override
    public byte[] getNextFrame() {
        return latestFrame.getAndSet(null);
    }

    @Override
    public void stopCapture() {
        isCapturing.set(false);
        latestFrame.set(null);

        if (ffmpegProc != null) {
            ffmpegProc.destroyForcibly();
            ffmpegProc = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        AuditLogger.logSystem("[DxgiCapturer] FFmpeg pipe stopped");
    }

    @Override
    public boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // -----------------------------------------------------------------------
    // MJPEG frame reader — parses FF D8 / FF D9 boundaries from FFmpeg stdout
    // -----------------------------------------------------------------------

    private void readMjpeg() {
        try (BufferedInputStream bis = new BufferedInputStream(ffmpegProc.getInputStream(), 512_000)) {

            ByteArrayOutputStream frame = new ByteArrayOutputStream(250_000);
            int prev = -1;
            boolean inFrame = false;

            while (isCapturing.get() && !Thread.currentThread().isInterrupted()) {
                int b = bis.read();
                if (b < 0)
                    break; // FFmpeg closed stdout

                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        frame.reset();
                        frame.write(0xFF);
                        frame.write(0xD8);
                        inFrame = true;
                    }
                    prev = b;
                    continue;
                }

                frame.write(b);

                if (prev == 0xFF && b == 0xD9 && frame.size() > 100) {
                    latestFrame.set(frame.toByteArray());
                    frame.reset();
                    inFrame = false;
                }

                prev = b;
            }

        } catch (Exception ignored) {
            // Process destroyed or interrupted — normal shutdown
        } finally {
            isCapturing.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Stderr drainer — prevents FFmpeg from blocking on a full stderr pipe
    // -----------------------------------------------------------------------

    private static void drainStderr(Process p) {
        Thread t = new Thread(() -> {
            try {
                p.getErrorStream().transferTo(OutputStream.nullOutputStream());
            } catch (Exception ignored) {
            }
        }, "DxgiCapturer-FFmpeg-Stderr");
        t.setDaemon(true);
        t.start();
    }
}
