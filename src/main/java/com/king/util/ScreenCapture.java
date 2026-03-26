package com.king.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;

/**
 * King of Lab — ScreenCapture (Flicker-Free Edition)
 *
 * ALL screen capture — both student (cursor hidden) and admin screen-share
 * (cursor visible) — now goes through persistent FFmpeg gdigrab processes.
 *
 * Why FFmpeg and not AWT Robot / GDI BitBlt / GetDIBits:
 *   – AWT Robot, GetDIBits, and even BitBlt against the screen DC all interact
 *     with the Windows hardware-cursor layer.  On any display configuration
 *     they can briefly hide/show the cursor, causing visible flicker for the
 *     physical user sitting at the machine.
 *   – FFmpeg gdigrab uses its own DWM-composited capture path.  With
 *     -draw_mouse 0 the cursor overlay is skipped entirely before the GPU
 *     writes the frame; no hide/show of the cursor ever occurs.
 *   – OBS Studio uses the same approach (DXGI Desktop Duplication with
 *     cursor flag).  gdigrab with -draw_mouse 0 achieves the same visible
 *     result from Java without a native DLL.
 *
 * Pipes:
 *   STUDENT pipe  – gdigrab -draw_mouse 0 @ 30 FPS → latestStudentJpeg
 *   ADMIN pipe    – gdigrab -draw_mouse 1 @ 60 FPS → latestAdminJpeg
 *                   (cursor included so students see admin pointer)
 *
 * AWT Robot is kept ONLY as a last-resort fallback when FFmpeg is absent from
 * the system PATH.  Log line "[ScreenCapture] FFmpeg NOT found" signals this.
 */
public class ScreenCapture {

    private static final Rectangle SCREEN = new Rectangle(
            Toolkit.getDefaultToolkit().getScreenSize());

    // -----------------------------------------------------------------------
    // Student pipe  (cursor hidden)
    // -----------------------------------------------------------------------
    private static volatile Process studentProc;
    private static volatile Thread  studentReader;
    private static final AtomicBoolean studentPipeUp = new AtomicBoolean(false);
    private static final AtomicReference<byte[]> latestStudentJpeg =
            new AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // Admin pipe  (cursor visible — used for admin screen-share)
    // -----------------------------------------------------------------------
    private static volatile Process adminProc;
    private static volatile Thread  adminReader;
    private static final AtomicBoolean adminPipeUp = new AtomicBoolean(false);
    private static final AtomicReference<byte[]> latestAdminJpeg =
            new AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------
    private static volatile boolean ffmpegAvailable = false;

    private static BufferedImage reusableBuffer;
    private static Graphics2D    reusableGraphics;
    private static BufferedImage prevFrame;
    private static final double  DELTA_THRESHOLD = 0.015;

    // Async screen-share state
    private static final AtomicReference<String> latestAdminFrame =
            new AtomicReference<>(null);
    private static volatile boolean asyncRunning = false;

    // Fallback AWT Robot (only when ffmpeg absent)
    private static Robot fallbackRobot;

    // Cursor pixels — used only when FFmpeg is absent and admin shares screen
    private static final int[][] CURSOR_SHAPE = {
        {1,0,0,0,0,0,0,0,0,0,0,0},
        {1,1,0,0,0,0,0,0,0,0,0,0},
        {1,2,1,0,0,0,0,0,0,0,0,0},
        {1,2,2,1,0,0,0,0,0,0,0,0},
        {1,2,2,2,1,0,0,0,0,0,0,0},
        {1,2,2,2,2,1,0,0,0,0,0,0},
        {1,2,2,2,2,2,1,0,0,0,0,0},
        {1,2,2,2,2,2,2,1,0,0,0,0},
        {1,2,2,2,2,2,2,2,1,0,0,0},
        {1,2,2,2,2,2,2,2,2,1,0,0},
        {1,2,2,2,2,2,2,2,2,2,1,0},
        {1,2,2,2,2,2,2,2,2,2,2,1},
        {1,2,2,2,2,2,2,1,1,1,1,1},
        {1,2,2,2,1,2,2,1,0,0,0,0},
        {1,2,2,1,0,1,2,2,1,0,0,0},
        {1,2,1,0,0,1,2,2,1,0,0,0},
        {1,1,0,0,0,0,1,2,2,1,0,0},
        {1,0,0,0,0,0,1,2,2,1,0,0},
        {0,0,0,0,0,0,0,1,1,0,0,0},
    };

    // -----------------------------------------------------------------------
    // Static init
    // -----------------------------------------------------------------------
    static {
        ffmpegAvailable = probeFfmpeg();
        if (ffmpegAvailable) {
            startStudentPipe();
            // Admin pipe is started lazily when admin screen-share begins
        } else {
            System.err.println("[ScreenCapture] FFmpeg NOT found — falling back to AWT Robot (cursor may flicker)");
            try { fallbackRobot = new Robot(); fallbackRobot.setAutoDelay(0); }
            catch (AWTException ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // FFmpeg probe
    // -----------------------------------------------------------------------
    private static boolean probeFfmpeg() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ffmpeg", "-version"});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    // -----------------------------------------------------------------------
    // Student pipe management  (draw_mouse=0, 30 FPS)
    // -----------------------------------------------------------------------
    private static synchronized void startStudentPipe() {
        if (studentPipeUp.get()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",   "quiet",
                "-f",          "gdigrab",
                "-framerate",  "30",
                "-draw_mouse", "0",   // exclude cursor at OS level
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
        } catch (Exception e) {
            studentPipeUp.set(false);
            System.err.println("[ScreenCapture] Failed to start student pipe: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Admin pipe management  (draw_mouse=1, 60 FPS)
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
        }
    }

    private static synchronized void stopAdminPipe() {
        adminPipeUp.set(false);
        if (adminProc != null) { adminProc.destroyForcibly(); adminProc = null; }
        if (adminReader != null) { adminReader.interrupt(); adminReader = null; }
        latestAdminJpeg.set(null);
    }

    // -----------------------------------------------------------------------
    // MJPEG frame parser (shared between both pipes)
    // -----------------------------------------------------------------------
    private static void readMjpeg(Process proc,
                                   AtomicReference<byte[]> target,
                                   AtomicBoolean running) {
        try (BufferedInputStream bis =
                 new BufferedInputStream(proc.getInputStream(), 512_000)) {

            ByteArrayOutputStream frame = new ByteArrayOutputStream(250_000);
            int prev = -1;

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int b = bis.read();
                if (b < 0) break;

                // Start of JPEG  (FF D8)
                if (prev == 0xFF && b == 0xD8) {
                    frame.reset();
                    frame.write(0xFF);
                    frame.write(0xD8);
                    prev = b;
                    continue;
                }

                frame.write(b);

                // End of JPEG  (FF D9)
                if (prev == 0xFF && b == 0xD9 && frame.size() > 100) {
                    target.set(frame.toByteArray()); // frame-drop: latest wins
                    frame.reset();
                }

                prev = b;
            }
        } catch (Exception ignored) {
        } finally {
            running.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Stderr drainer so FFmpeg never blocks on stderr
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
    // Public API — student-facing (no cursor)
    // -----------------------------------------------------------------------

    public static String captureAsBase64(double resolutionScale, float jpegQuality) {
        try {
            byte[] jpeg = latestStudentJpeg.get();
            if (jpeg == null) return null;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) return null;
            return encodeScaled(img, resolutionScale, jpegQuality);
        } catch (Exception e) { return null; }
    }

    public static byte[] captureAsBytes(double resolutionScale, float jpegQuality) {
        try {
            byte[] jpeg = latestStudentJpeg.get();
            if (jpeg == null) return null;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) return null;
            return encodeScaledBytes(img, resolutionScale, jpegQuality);
        } catch (Exception e) { return null; }
    }

    /**
     * Streaming capture with delta detection.
     * Returns null when frame is nearly identical to previous (bandwidth save).
     */
    public static String captureForStreaming() {
        try {
            byte[] jpeg = latestStudentJpeg.getAndSet(null); // consume frame
            if (jpeg == null) return null;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) return null;

            if (prevFrame != null && isDeltaSmall(prevFrame, img)) return null;

            int qW = img.getWidth() / 4, qH = img.getHeight() / 4;
            prevFrame = new BufferedImage(qW, qH, BufferedImage.TYPE_INT_RGB);
            prevFrame.createGraphics().drawImage(img, 0, 0, qW, qH, null);

            return encodeScaled(img, 1.0, 0.72f);
        } catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    // Admin screen-share  (cursor visible via admin pipe)
    // -----------------------------------------------------------------------

    public static void startAsyncCapture() {
        if (asyncRunning) return;
        asyncRunning = true;
        if (ffmpegAvailable) startAdminPipe();

        Thread t = new Thread(() -> {
            while (asyncRunning) {
                try {
                    String frame = captureAsBase64WithCursor(1.0, 0.8f);
                    if (frame != null) latestAdminFrame.set(frame);
                    Thread.sleep(16);
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
        if (ffmpegAvailable) stopAdminPipe();
    }

    public static String getLatestFrame() { return latestAdminFrame.get(); }

    /** Admin screen-share frame WITH cursor — uses admin FFmpeg pipe (draw_mouse=1). */
    public static String captureAsBase64WithCursor(double scale, float quality) {
        try {
            if (ffmpegAvailable) {
                byte[] jpeg = latestAdminJpeg.get();
                if (jpeg == null) return null;
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                if (img == null) return null;
                return encodeScaled(img, scale, quality);
            }
            // Fallback (ffmpeg absent): AWT Robot + manual cursor draw
            if (fallbackRobot == null) return null;
            BufferedImage capture = fallbackRobot.createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            int w = (int)(capture.getWidth() * scale);
            int h = (int)(capture.getHeight() * scale);
            ensureBuffer(w, h);
            reusableGraphics.drawImage(capture, 0, 0, w, h, null);
            drawMouseCursor(reusableGraphics, scale, scale);
            return encodeJpeg(reusableBuffer, quality);
        } catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    // Convenience overloads
    // -----------------------------------------------------------------------
    public static String captureAsBase64(double scale) { return captureAsBase64(scale, 0.85f); }
    public static String captureHighQuality()           { return captureAsBase64(1.0, 0.95f); }

    // -----------------------------------------------------------------------
    // Encode helpers
    // -----------------------------------------------------------------------
    private static String encodeScaled(BufferedImage img, double scale, float quality) throws Exception {
        return Base64.getEncoder().encodeToString(encodeScaledBytes(img, scale, quality));
    }

    private static byte[] encodeScaledBytes(BufferedImage img, double scale, float quality) throws Exception {
        int w = (int)(img.getWidth() * scale);
        int h = (int)(img.getHeight() * scale);
        ensureBuffer(w, h);
        reusableGraphics.drawImage(img, 0, 0, w, h, null);
        return encodeJpegBytes(reusableBuffer, quality);
    }

    private static void ensureBuffer(int w, int h) {
        if (reusableBuffer == null || reusableBuffer.getWidth() != w || reusableBuffer.getHeight() != h) {
            if (reusableGraphics != null) reusableGraphics.dispose();
            reusableBuffer   = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            reusableGraphics = reusableBuffer.createGraphics();
            reusableGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            reusableGraphics.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_SPEED);
        }
    }

    private static String encodeJpeg(BufferedImage img, float quality) throws Exception {
        return Base64.getEncoder().encodeToString(encodeJpegBytes(img, quality));
    }

    private static byte[] encodeJpegBytes(BufferedImage img, float quality) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(80_000);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (writers.hasNext()) {
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
            writer.dispose();
            ios.close();
        } else {
            ImageIO.write(img, "jpg", baos);
        }
        return baos.toByteArray();
    }

    // -----------------------------------------------------------------------
    // Delta detection
    // -----------------------------------------------------------------------
    private static boolean isDeltaSmall(BufferedImage prev, BufferedImage cur) {
        int qW = prev.getWidth(), qH = prev.getHeight();
        BufferedImage q = new BufferedImage(qW, qH, BufferedImage.TYPE_INT_RGB);
        q.createGraphics().drawImage(cur, 0, 0, qW, qH, null);
        int total = qW * qH, changed = 0, thresh = (int)(total * DELTA_THRESHOLD);
        for (int y = 0; y < qH; y++)
            for (int x = 0; x < qW; x++)
                if (prev.getRGB(x, y) != q.getRGB(x, y) && ++changed > thresh) return false;
        return true;
    }

    // -----------------------------------------------------------------------
    // Cursor drawing (fallback only — not used when FFmpeg is available)
    // -----------------------------------------------------------------------
    private static void drawMouseCursor(Graphics2D g, double sx, double sy) {
        try {
            Point p = MouseInfo.getPointerInfo().getLocation();
            int mx = (int)(p.x * sx), my = (int)(p.y * sy);
            for (int row = 0; row < CURSOR_SHAPE.length; row++)
                for (int col = 0; col < CURSOR_SHAPE[row].length; col++) {
                    int v = CURSOR_SHAPE[row][col];
                    if      (v == 1) { g.setColor(Color.BLACK); g.fillRect(mx+col, my+row, 1, 1); }
                    else if (v == 2) { g.setColor(Color.WHITE); g.fillRect(mx+col, my+row, 1, 1); }
                }
        } catch (Exception ignored) {}
    }

    public static BufferedImage decodeBase64(String b64) {
        try { return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64))); }
        catch (Exception e) { return null; }
    }
}
