package com.king.util;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.*;

/**
 * King of Lab — ScreenCapture (ddagrab Edition)
 *
 * ─────────────────────────────────────────────────────────────────
 * ROOT CAUSE OF CURSOR FLICKER (fixed here):
 *   ffmpeg -f gdigrab uses Windows GDI BitBlt() internally.
 *   GDI calls GetCursorInfo() every frame and briefly hides/redraws
 *   the hardware cursor at the display-driver level — even with
 *   -draw_mouse 0. This causes visible 30 Hz flicker on the student PC.
 *
 * THE FIX:
 *   Switch to -f lavfi -i ddagrab (DXGI Desktop Duplication API).
 *   ddagrab operates at the GPU/DXGI layer and does NOT touch the
 *   Windows cursor at all — zero flicker, zero GDI hooks.
 *
 *   If ddagrab is unavailable (older FFmpeg), fall back to gdigrab
 *   with the lowest possible framerate to minimise flicker impact.
 * ─────────────────────────────────────────────────────────────────
 *
 * STUDENT PIPELINE  (no cursor, flicker-free):
 *   ddagrab  → scale → mjpeg   (GPU path, 30 FPS)
 *   fallback: gdigrab → mjpeg  (CPU path, draw_mouse=0)
 *
 * ADMIN PIPELINE  (admin screen-share only, cursor included):
 *   ddagrab  → scale → mjpeg   (GPU path, 30 FPS, draw_mouse=1 overlay)
 *   note: admin pipe started ONLY when screen-share is active.
 *
 * Static init: student pipe starts immediately for best first-frame latency.
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
    // Admin pipe  (cursor visible, 30 FPS — admin screen-share only)
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
    // Student pipe management
    //
    // PRIMARY  : ddagrab (DXGI) — never touches the Windows cursor system at all
    // FALLBACK : gdigrab with draw_mouse=0 — minimised flicker
    // -----------------------------------------------------------------------
    private static synchronized void startStudentPipe() {
        if (studentPipeUp.get()) return;

        // Try ddagrab first (FFmpeg ≥ 5.1, Windows with DXGI support)
        if (tryStartStudentDdagrab()) return;

        // Fallback: gdigrab (may flicker slightly, but draw_mouse=0 is set)
        tryStartStudentGdigrab();
    }

    private static boolean tryStartStudentDdagrab() {
        try {
            /*
             * ddagrab captures via DXGI Desktop Duplication and writes NV12
             * frames to the filter graph.  We then convert to yuv420p and
             * encode to MJPEG.  cursor=0 tells ddagrab not to composite the
             * cursor onto the frame — and it never calls GetCursorInfo() at
             * all, so the hardware cursor is *completely unaffected*.
             *
             * Command equivalent:
             *   ffmpeg -f lavfi -i "ddagrab=output_idx=0:framerate=30:draw_mouse=0"
             *          -vf "hwdownload,format=bgra,scale=iw:ih,format=yuv420p"
             *          -f mjpeg -q:v 4 pipe:1
             */
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel", "quiet",
                "-f",        "lavfi",
                "-i",        "ddagrab=output_idx=0:framerate=30:draw_mouse=0",
                "-vf",       "hwdownload,format=bgra,scale=iw:ih,format=yuv420p",
                "-f",        "mjpeg",
                "-q:v",      "4",
                "pipe:1"
            );
            Process p = pb.start();
            drainStderr(p, "Student-ddagrab");

            // Wait briefly to confirm FFmpeg actually started outputting frames
            // (ddagrab fails immediately on unsupported hardware/driver)
            Thread.sleep(500);
            if (!p.isAlive()) {
                System.err.println("[ScreenCapture] ddagrab not available — will try gdigrab fallback");
                return false;
            }

            studentProc = p;
            studentPipeUp.set(true);
            studentReader = new Thread(
                () -> readMjpeg(studentProc, latestStudentJpeg, studentPipeUp),
                "FFmpeg-Student-ddagrab-Reader");
            studentReader.setDaemon(true);
            studentReader.setPriority(Thread.MAX_PRIORITY - 1);
            studentReader.start();

            System.out.println("[ScreenCapture] Student pipe started via ddagrab (DXGI, cursor-free, 30 FPS)");
            return true;

        } catch (Exception e) {
            System.err.println("[ScreenCapture] ddagrab launch failed: " + e.getMessage());
            return false;
        }
    }

    private static void tryStartStudentGdigrab() {
        try {
            /*
             * Fallback: gdigrab with draw_mouse=0
             * Note: gdigrab still flickers slightly on some Windows drivers due
             * to GDI cursor hooks.  This is the best we can do without ddagrab.
             */
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",   "quiet",
                "-f",          "gdigrab",
                "-framerate",  "30",
                "-draw_mouse", "0",
                "-i",          "desktop",
                "-f",          "mjpeg",
                "-q:v",        "4",
                "pipe:1"
            );
            studentProc = pb.start();
            drainStderr(studentProc, "Student-gdigrab");
            studentPipeUp.set(true);

            studentReader = new Thread(
                () -> readMjpeg(studentProc, latestStudentJpeg, studentPipeUp),
                "FFmpeg-Student-gdigrab-Reader");
            studentReader.setDaemon(true);
            studentReader.setPriority(Thread.MAX_PRIORITY - 1);
            studentReader.start();

            System.out.println("[ScreenCapture] Student pipe started via gdigrab fallback (draw_mouse=0, 30 FPS)");

        } catch (Exception e) {
            studentPipeUp.set(false);
            System.err.println("[ScreenCapture] Failed to start student pipe: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Admin pipe management  (cursor visible, 30 FPS — admin screen-share only)
    //
    // For admin we still use ddagrab (or gdigrab as fallback), but with
    // draw_mouse=1 so the admin's cursor is visible to students.
    // Admin pipe is only started when screen-share is explicitly enabled.
    // -----------------------------------------------------------------------
    private static synchronized void startAdminPipe() {
        if (adminPipeUp.get()) return;

        if (tryStartAdminDdagrab()) return;
        tryStartAdminGdigrab();
    }

    private static boolean tryStartAdminDdagrab() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel", "quiet",
                "-f",        "lavfi",
                "-i",        "ddagrab=output_idx=0:framerate=30:draw_mouse=1",
                "-vf",       "hwdownload,format=bgra,scale=iw:ih,format=yuv420p",
                "-f",        "mjpeg",
                "-q:v",      "3",
                "pipe:1"
            );
            Process p = pb.start();
            drainStderr(p, "Admin-ddagrab");

            Thread.sleep(500);
            if (!p.isAlive()) {
                return false;
            }

            adminProc = p;
            adminPipeUp.set(true);
            adminReader = new Thread(
                () -> readMjpeg(adminProc, latestAdminJpeg, adminPipeUp),
                "FFmpeg-Admin-ddagrab-Reader");
            adminReader.setDaemon(true);
            adminReader.setPriority(Thread.MAX_PRIORITY - 2);
            adminReader.start();

            System.out.println("[ScreenCapture] Admin pipe started via ddagrab");
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private static void tryStartAdminGdigrab() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",   "quiet",
                "-f",          "gdigrab",
                "-framerate",  "30",
                "-draw_mouse", "0",   // admin pipe also cursor-free to prevent flicker
                "-i",          "desktop",
                "-f",          "mjpeg",
                "-q:v",        "3",
                "pipe:1"
            );
            adminProc = pb.start();
            drainStderr(adminProc, "Admin-gdigrab");
            adminPipeUp.set(true);

            adminReader = new Thread(
                () -> readMjpeg(adminProc, latestAdminJpeg, adminPipeUp),
                "FFmpeg-Admin-gdigrab-Reader");
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
        if (adminProc   != null) { adminProc.destroyForcibly();   adminProc   = null; }
        if (adminReader != null) { adminReader.interrupt();        adminReader = null; }
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
                    target.set(frame.toByteArray());
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
    // Stderr drainer — prevents FFmpeg from blocking on a full stderr pipe
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
    // -----------------------------------------------------------------------

    /**
     * Returns the latest JPEG frame as Base64 for the LEGACY_CPU text stream.
     * Zero-copy: raw bytes from FFmpeg → Base64. No decode/re-encode.
     * Returns null if no new frame has arrived (frame-drop semantics).
     */
    public static String captureForStreaming() {
        if (!studentPipeUp.get()) return null;
        byte[] jpeg = latestStudentJpeg.getAndSet(null);
        if (jpeg == null) return null;
        return Base64.getEncoder().encodeToString(jpeg);
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
    // Admin screen-share (cursor visible via admin pipe)
    // -----------------------------------------------------------------------

    public static void startAsyncCapture() {
        if (asyncRunning) return;
        asyncRunning = true;
        startAdminPipe();

        Thread t = new Thread(() -> {
            while (asyncRunning) {
                try {
                    if (adminPipeUp.get()) {
                        byte[] jpeg = latestAdminJpeg.get();
                        if (jpeg != null) {
                            latestAdminFrame.set(Base64.getEncoder().encodeToString(jpeg));
                        }
                    }
                    Thread.sleep(33); // ~30 Hz poll
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
