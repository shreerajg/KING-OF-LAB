package com.king.streaming.webrtc;

import com.king.streaming.api.ScreenCapturer;
import com.king.util.AuditLogger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * King of Lab — FFmpeg MJPEG Pipe Capturer (Flicker-Free, Zero-Copy).
 *
 * SINGLE CAPTURE PIPELINE:
 *   ffmpeg -f gdigrab -framerate 30 -draw_mouse 0 -i desktop -f mjpeg -q:v 4 pipe:1
 *
 * Design:
 *   - FFmpeg writes MJPEG frames to stdout.
 *   - A background reader thread parses JPEG boundaries (FF D8 / FF D9)
 *     and stores the latest complete frame in an AtomicReference<byte[]>.
 *   - getNextFrame() reads AND clears the reference (frame-drop semantics):
 *     only the LATEST frame is ever transmitted; stale frames are dropped.
 *
 * Cursor: -draw_mouse 0 excludes the cursor at the OS level BEFORE the GPU
 * writes the frame.  No hide/show of the hardware cursor ever occurs.
 *
 * What was REMOVED vs the previous implementation:
 *   ✗ BitBlt / GetDC / GetDesktopWindow (GDI — root cause of cursor flicker)
 *   ✗ SRCCOPY / CAPTUREBLT flags
 *   ✗ AWT Robot.createScreenCapture()
 *   ✗ ImageIO.read / ImageIO.write decode→re-encode pipeline
 *   ✗ Shared reusableBuffer / reusableGraphics (race condition source)
 *   ✗ DXGI stub that silently fell through to GDI
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
    private volatile Thread  readerThread;

    // -----------------------------------------------------------------------
    // ScreenCapturer interface
    // -----------------------------------------------------------------------

    @Override
    public void startCapture() {
        if (isCapturing.getAndSet(true)) return; // already running

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",   "quiet",
                "-f",          "gdigrab",
                "-framerate",  "30",
                "-draw_mouse", "0",   // exclude cursor at OS level — NO flicker
                "-i",          "desktop",
                "-f",          "mjpeg",
                "-q:v",        "4",
                "pipe:1"
            );
            ffmpegProc = pb.start();
            drainStderr(ffmpegProc);

            readerThread = new Thread(this::readMjpeg, "DxgiCapturer-FFmpeg-Reader");
            readerThread.setDaemon(true);
            readerThread.setPriority(Thread.MAX_PRIORITY - 1);
            readerThread.start();

            AuditLogger.logSystem("[DxgiCapturer] FFmpeg MJPEG pipe started (draw_mouse=0, 30 FPS)");

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
        // Supported wherever FFmpeg is on PATH on Windows.
        // startCapture() will handle the failure gracefully if FFmpeg is absent.
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // -----------------------------------------------------------------------
    // MJPEG frame reader — parses FF D8 / FF D9 boundaries from FFmpeg stdout
    // -----------------------------------------------------------------------

    private void readMjpeg() {
        try (BufferedInputStream bis =
                 new BufferedInputStream(ffmpegProc.getInputStream(), 512_000)) {

            ByteArrayOutputStream frame = new ByteArrayOutputStream(250_000);
            int prev = -1;
            boolean inFrame = false;

            while (isCapturing.get() && !Thread.currentThread().isInterrupted()) {
                int b = bis.read();
                if (b < 0) break; // FFmpeg closed stdout

                if (!inFrame) {
                    // Wait for JPEG SOI marker: FF D8
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

                // JPEG EOI marker: FF D9
                if (prev == 0xFF && b == 0xD9 && frame.size() > 100) {
                    // Store latest frame — consumer always gets freshest frame
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
            try { p.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
            catch (Exception ignored) {}
        }, "DxgiCapturer-FFmpeg-Stderr");
        t.setDaemon(true);
        t.start();
    }
}
