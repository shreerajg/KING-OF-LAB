package com.king.util;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.*;

/**
 * King of Lab — ScreenCapture (FFmpeg-Only Edition)
 *
 * ALL student-facing screen capture now goes through a single, persistent
 * FFmpeg gdigrab MJPEG pipe.  No GDI, no AWT Robot, no decode/re-encode.
 *
 * PRIMARY PIPELINE (student — no cursor):
 *   ffmpeg -f gdigrab -framerate 30 -draw_mouse 0 -i desktop -f mjpeg -q:v 4 pipe:1
 *   → JPEG bytes → AtomicReference<byte[]> → send directly (zero-copy)
 *
 * ADMIN PIPELINE (admin screen-share — cursor visible):
 *   ffmpeg -f gdigrab -framerate 60 -draw_mouse 1 -i desktop -f mjpeg -q:v 3 pipe:1
 *   → JPEG bytes → AtomicReference<byte[]> → Base64 for WebSocket delivery
 *
 * What was REMOVED:
 *   ✗ AWT Robot.createScreenCapture()
 *   ✗ ImageIO.read() / ImageIO.write() in the streaming path
 *   ✗ encodeScaled() / encodeScaledBytes() — decode→scale→re-encode
 *   ✗ reusableBuffer / reusableGraphics shared buffers (race condition source)
 *   ✗ isDeltaSmall() / prevFrame delta detection
 *   ✗ Fallback Robot path (if ffmpeg absent the pipe simply won't start)
 *
 * GUARD:
 *   If the student FFmpeg pipe is running, NO other capture method is invoked.
 *   The studentPipeUp flag is checked before every capture call.
 */
public class ScreenCapture {

    // -----------------------------------------------------------------------
    // Student pipe  (cursor hidden, 30 FPS)
    // -----------------------------------------------------------------------
    private static volatile Process      studentProc;
    private static volatile Thread       studentReader;
    private static final AtomicBoolean   studentPipeUp   = new AtomicBoolean(false);
    private static final AtomicReference<byte[]> latestStudentJpeg =
            new AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // Admin pipe  (cursor visible, 60 FPS — admin screen-share only)
    // -----------------------------------------------------------------------
    private static volatile Process      adminProc;
    private static volatile Thread       adminReader;
    private static final AtomicBoolean   adminPipeUp     = new AtomicBoolean(false);
    private static final AtomicReference<byte[]> latestAdminJpeg  =
            new AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // Admin async state
    // -----------------------------------------------------------------------
    private static final AtomicReference<String> latestAdminFrame =
            new AtomicReference<>(null);
    private static volatile boolean asyncRunning = false;

    // -----------------------------------------------------------------------
    // Static init — start student pipe immediately
    // -----------------------------------------------------------------------
    static {
        startStudentPipe();
    }

    // -----------------------------------------------------------------------
    // Student pipe management  (draw_mouse=0, 30 FPS, q:v 4)
    // -----------------------------------------------------------------------
    private static synchronized void startStudentPipe() {
        if (studentPipeUp.get()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",   "quiet",
                "-f",          "gdigrab",
                "-framerate",  "30",
                "-draw_mouse", "0",   // exclude cursor at OS level — no flicker
                "-i",          "desktop",
                "-f",          "mjpeg",
                "-q:v",        "4",
                "pipe:1"
            );
            studentProc = pb.start();
            drainStderr(studentProc, "Student");
            studentPipeUp.set(true);

            studentReader = new Thread(
                () -> readMjpeg(studentProc, latestStudentJpeg, studentPipeUp),
                "FFmpeg-Student-Reader");
            studentReader.setDaemon(true);
            studentReader.setPriority(Thread.MAX_PRIORITY - 1);
            studentReader.start();

            System.out.println("[ScreenCapture] Student FFmpeg pipe started (draw_mouse=0, 30 FPS)");

        } catch (Exception e) {
            studentPipeUp.set(false);
            System.err.println("[ScreenCapture] Failed to start student pipe: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Admin pipe management  (draw_mouse=1, 60 FPS, q:v 3)
    // -----------------------------------------------------------------------
    private static synchronized void startAdminPipe() {
        if (adminPipeUp.get()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",   "quiet",
                "-f",          "gdigrab",
                "-framerate",  "60",
                "-draw_mouse", "1",   // include cursor so students see admin pointer
                "-i",          "desktop",
                "-f",          "mjpeg",
                "-q:v",        "3",
                "pipe:1"
            );
            adminProc = pb.start();
            drainStderr(adminProc, "Admin");
            adminPipeUp.set(true);

            adminReader = new Thread(
                () -> readMjpeg(adminProc, latestAdminJpeg, adminPipeUp),
                "FFmpeg-Admin-Reader");
            adminReader.setDaemon(true);
            adminReader.setPriority(Thread.MAX_PRIORITY - 2);
            adminReader.start();

        } catch (Exception e) {
            adminPipeUp.set(false);
            System.err.println("[ScreenCapture] Failed to start admin pipe: " + e.getMessage());
        }
    }

    private static synchronized void stopAdminPipe() {
        adminPipeUp.set(false);
        if (adminProc != null) { adminProc.destroyForcibly(); adminProc = null; }
        if (adminReader != null) { adminReader.interrupt(); adminReader = null; }
        latestAdminJpeg.set(null);
    }

    // -----------------------------------------------------------------------
    // MJPEG frame parser — shared between both pipes
    // Parses FF D8 / FF D9 JPEG boundaries from FFmpeg stdout.
    // -----------------------------------------------------------------------
    private static void readMjpeg(Process proc,
                                   AtomicReference<byte[]> target,
                                   AtomicBoolean running) {
        try (BufferedInputStream bis =
                 new BufferedInputStream(proc.getInputStream(), 512_000)) {

            ByteArrayOutputStream frame = new ByteArrayOutputStream(250_000);
            int prev = -1;
            boolean inFrame = false;

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int b = bis.read();
                if (b < 0) break;

                if (!inFrame) {
                    // Wait for JPEG SOI: FF D8
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

                // JPEG EOI: FF D9
                if (prev == 0xFF && b == 0xD9 && frame.size() > 100) {
                    target.set(frame.toByteArray()); // latest frame wins
                    frame.reset();
                    inFrame = false;
                }

                prev = b;
            }
        } catch (Exception ignored) {
            // Process destroyed or interrupted — normal shutdown
        } finally {
            running.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Stderr drainer — prevents FFmpeg from blocking on stderr
    // -----------------------------------------------------------------------
    private static void drainStderr(Process p, String label) {
        Thread t = new Thread(() -> {
            try { p.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
            catch (Exception ignored) {}
        }, "FFmpeg-" + label + "-Stderr");
        t.setDaemon(true);
        t.start();
    }

    // -----------------------------------------------------------------------
    // Public API — student streaming (zero-copy JPEG passthrough)
    //
    //   GUARD: studentPipeUp checked first.
    //   If FFmpeg is running: return raw JPEG bytes directly, NO processing.
    //   No ImageIO.read, no ImageIO.write, no decode, no re-encode.
    // -----------------------------------------------------------------------

    /**
     * Returns the latest JPEG frame as Base64 for the LEGACY_CPU text stream.
     * Zero-copy: raw bytes from FFmpeg → Base64. No decode/re-encode.
     * Returns null if no new frame has arrived (frame-drop semantics).
     */
    public static String captureForStreaming() {
        if (!studentPipeUp.get()) return null; // pipe not running — no fallback

        byte[] jpeg = latestStudentJpeg.getAndSet(null); // consume latest
        if (jpeg == null) return null; // no new frame since last poll
        return Base64.getEncoder().encodeToString(jpeg); // zero-copy passthrough
    }

    /**
     * Returns raw JPEG bytes for the binary/ultra stream.
     * Zero-copy: raw FFmpeg output, no decode/re-encode.
     * Returns null if no new frame has arrived.
     */
    public static byte[] captureAsBytes() {
        if (!studentPipeUp.get()) return null;
        return latestStudentJpeg.getAndSet(null);
    }

    // -----------------------------------------------------------------------
    // Admin screen-share  (cursor visible via admin pipe)
    // -----------------------------------------------------------------------

    public static void startAsyncCapture() {
        if (asyncRunning) return;
        asyncRunning = true;
        startAdminPipe();

        Thread t = new Thread(() -> {
            while (asyncRunning) {
                try {
                    // Guard: only use admin FFmpeg pipe — no Robot fallback
                    if (adminPipeUp.get()) {
                        byte[] jpeg = latestAdminJpeg.get();
                        if (jpeg != null) {
                            // Zero-copy: raw JPEG → Base64. No ImageIO decode/re-encode.
                            latestAdminFrame.set(Base64.getEncoder().encodeToString(jpeg));
                        }
                    }
                    Thread.sleep(16); // ~60 Hz poll
                } catch (InterruptedException e) { break; }
                catch (Exception ignored) {}
            }
        }, "KingAdminCapture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    public static void stopAsyncCapture() {
        asyncRunning = false;
        latestAdminFrame.set(null);
        stopAdminPipe();
    }

    public static String getLatestFrame() { return latestAdminFrame.get(); }

    // -----------------------------------------------------------------------
    // Utility — Base64 decode for displaying received frames
    // -----------------------------------------------------------------------
    public static BufferedImage decodeBase64(String b64) {
        try { return ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(b64))); }
        catch (Exception e) { return null; }
    }
}
