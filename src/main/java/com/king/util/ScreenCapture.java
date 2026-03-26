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
 * King of Lab — ScreenCapture
 *
 * STUDENT CAPTURE: Uses FFmpeg gdigrab with -draw_mouse 0.
 *   This is the ONLY guaranteed way on Windows to exclude the hardware
 *   cursor from the captured bitmap. The cursor is composited by Windows
 *   as a separate overlay layer; -draw_mouse 0 tells gdigrab to skip it.
 *
 * ADMIN SCREEN-SHARE: Uses AWT Robot with the cursor manually drawn on top,
 *   so students can see the admin's pointer during screen sharing.
 *
 * Flickering explained: AWT Robot.createScreenCapture() hides the hardware
 *   cursor momentarily every frame to include it, causing rapid show/hide
 *   which the student sees as flickering. FFmpeg gdigrab avoids this entirely.
 */
public class ScreenCapture {

    private static Rectangle     screenRect;
    private static BufferedImage reusableBuffer;
    private static Graphics2D    reusableGraphics;
    private static BufferedImage prevFrame;

    private static final double DELTA_THRESHOLD = 0.015;

    // Async double-buffer (admin screen-share)
    private static final AtomicReference<String> latestFrame = new AtomicReference<>(null);
    private static volatile boolean asyncRunning = false;

    public static final double QUALITY_LOW    = 0.30;
    public static final double QUALITY_MEDIUM = 0.50;
    public static final double QUALITY_HIGH   = 0.70;
    public static final double QUALITY_ULTRA  = 0.90;

    // -----------------------------------------------------------------------
    // Cursor shape — only used in admin screen share
    // -----------------------------------------------------------------------
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

    // AWT Robot — used ONLY for admin screen-share (cursor needed there)
    private static Robot adminRobot;

    // FFmpeg check
    private static final AtomicBoolean ffmpegChecked = new AtomicBoolean(false);
    private static volatile boolean ffmpegAvailable = false;

    static {
        screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        try {
            adminRobot = new Robot();
            adminRobot.setAutoDelay(0);
        } catch (AWTException e) {
            adminRobot = null;
        }
        checkFfmpeg();
    }

    private static void checkFfmpeg() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ffmpeg", "-version"});
            p.waitFor();
            ffmpegAvailable = (p.exitValue() == 0);
        } catch (Exception e) {
            ffmpegAvailable = false;
        }
        ffmpegChecked.set(true);
    }

    // -----------------------------------------------------------------------
    // STUDENT CAPTURE — cursor excluded via FFmpeg gdigrab -draw_mouse 0
    // -----------------------------------------------------------------------

    /**
     * Captures one JPEG frame WITHOUT the mouse cursor.
     * Uses FFmpeg gdigrab -draw_mouse 0 on Windows to guarantee cursor exclusion.
     * Falls back to AWT Robot (cursor may be present) only if FFmpeg is missing.
     */
    private static BufferedImage captureStudentFrame() {
        if (ffmpegAvailable) {
            return captureViaFfmpegGdigrab();
        }
        // Last-resort fallback — no flicker workaround possible without ffmpeg
        if (adminRobot != null) {
            return adminRobot.createScreenCapture(screenRect);
        }
        return new BufferedImage(screenRect.width, screenRect.height, BufferedImage.TYPE_INT_RGB);
    }

    /**
     * Captures one frame using FFmpeg gdigrab with -draw_mouse 0.
     * FFmpeg grabs the desktop DC directly without hiding the cursor via the
     * OS API, so there is zero flickering on the student's screen.
     */
    private static BufferedImage captureViaFfmpegGdigrab() {
        try {
            // We capture exactly 1 frame via stdout as MJPEG/rawvideo
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",  "quiet",
                "-f",         "gdigrab",
                "-draw_mouse","0",           // <-- THE KEY FLAG: exclude cursor
                "-i",         "desktop",
                "-vframes",   "1",
                "-f",         "image2",
                "-vcodec",    "mjpeg",
                "-q:v",       "3",
                "pipe:1"
            );
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            byte[] jpegBytes;
            try (InputStream is = proc.getInputStream()) {
                jpegBytes = is.readAllBytes();
            }
            proc.waitFor();

            if (jpegBytes == null || jpegBytes.length == 0) return null;

            return ImageIO.read(new ByteArrayInputStream(jpegBytes));

        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Public API — student facing (no cursor)
    // -----------------------------------------------------------------------

    public static String captureAsBase64(double resolutionScale, float jpegQuality) {
        try {
            BufferedImage capture = captureStudentFrame();
            if (capture == null) return null;

            int newW = (int)(capture.getWidth()  * resolutionScale);
            int newH = (int)(capture.getHeight() * resolutionScale);
            ensureBuffer(newW, newH, false);

            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);
            return encodeJpeg(reusableBuffer, jpegQuality);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] captureAsBytes(double resolutionScale, float jpegQuality) {
        try {
            BufferedImage capture = captureStudentFrame();
            if (capture == null) return null;

            int newW = (int)(capture.getWidth()  * resolutionScale);
            int newH = (int)(capture.getHeight() * resolutionScale);
            ensureBuffer(newW, newH, false);

            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);
            return encodeJpegBytes(reusableBuffer, jpegQuality);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Streaming capture with delta detection.
     * Returns null if frame is nearly identical to previous (bandwidth optimization).
     */
    public static String captureForStreaming() {
        try {
            BufferedImage capture = captureStudentFrame();
            if (capture == null) return null;

            if (prevFrame != null && isDeltaSmall(prevFrame, capture)) {
                return null;
            }

            // Store quarter-res copy for next delta check
            int qW = capture.getWidth()  / 4;
            int qH = capture.getHeight() / 4;
            prevFrame = new BufferedImage(qW, qH, BufferedImage.TYPE_INT_RGB);
            prevFrame.createGraphics().drawImage(capture, 0, 0, qW, qH, null);

            int newW = capture.getWidth();
            int newH = capture.getHeight();
            ensureBuffer(newW, newH, false);
            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);

            return encodeJpeg(reusableBuffer, 0.72f);
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Async capture — ADMIN screen share (cursor included)
    // -----------------------------------------------------------------------

    public static void startAsyncCapture() {
        if (asyncRunning) return;
        asyncRunning = true;

        Thread t = new Thread(() -> {
            while (asyncRunning) {
                try {
                    String frame = captureAsBase64WithCursor(1.0, 0.8f);
                    if (frame != null) latestFrame.set(frame);
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        }, "KingAdminCapture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    public static void stopAsyncCapture() {
        asyncRunning = false;
        latestFrame.set(null);
    }

    public static String getLatestFrame() { return latestFrame.get(); }

    // -----------------------------------------------------------------------
    // ADMIN Screen Share — cursor included (AWT Robot + manual draw)
    // -----------------------------------------------------------------------

    /**
     * Used ONLY for admin screen-share so students can see the admin's cursor.
     */
    public static String captureAsBase64WithCursor(double resolutionScale, float jpegQuality) {
        try {
            if (adminRobot == null) return null;

            BufferedImage capture = adminRobot.createScreenCapture(screenRect);
            int newW = (int)(capture.getWidth()  * resolutionScale);
            int newH = (int)(capture.getHeight() * resolutionScale);
            ensureBuffer(newW, newH, true);

            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);
            drawMouseCursor(reusableGraphics, resolutionScale, resolutionScale);

            return encodeJpeg(reusableBuffer, jpegQuality);
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Convenience overloads
    // -----------------------------------------------------------------------
    public static String captureAsBase64(double scale) { return captureAsBase64(scale, 0.85f); }
    public static String captureHighQuality()           { return captureAsBase64(1.0, 0.95f); }

    // -----------------------------------------------------------------------
    // Delta detection
    // -----------------------------------------------------------------------
    private static boolean isDeltaSmall(BufferedImage prev, BufferedImage current) {
        int qW = prev.getWidth();
        int qH = prev.getHeight();
        BufferedImage curQ = new BufferedImage(qW, qH, BufferedImage.TYPE_INT_RGB);
        curQ.createGraphics().drawImage(current, 0, 0, qW, qH, null);
        int total   = qW * qH;
        int changed = 0;
        int thresh  = (int)(total * DELTA_THRESHOLD);
        for (int y = 0; y < qH; y++) {
            for (int x = 0; x < qW; x++) {
                if (prev.getRGB(x, y) != curQ.getRGB(x, y)) {
                    if (++changed > thresh) return false;
                }
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void ensureBuffer(int w, int h, boolean resetPrevFrame) {
        if (reusableBuffer == null || reusableBuffer.getWidth() != w || reusableBuffer.getHeight() != h) {
            if (reusableGraphics != null) reusableGraphics.dispose();
            reusableBuffer   = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            reusableGraphics = reusableBuffer.createGraphics();
            reusableGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            reusableGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            if (resetPrevFrame) prevFrame = null;
        }
    }

    private static void drawMouseCursor(Graphics2D g, double scaleX, double scaleY) {
        try {
            Point p = MouseInfo.getPointerInfo().getLocation();
            int mx = (int)(p.x * scaleX);
            int my = (int)(p.y * scaleY);
            for (int row = 0; row < CURSOR_SHAPE.length; row++) {
                for (int col = 0; col < CURSOR_SHAPE[row].length; col++) {
                    int val = CURSOR_SHAPE[row][col];
                    if (val == 1)      { g.setColor(Color.BLACK); g.fillRect(mx+col, my+row, 1, 1); }
                    else if (val == 2) { g.setColor(Color.WHITE); g.fillRect(mx+col, my+row, 1, 1); }
                }
            }
        } catch (Exception ignored) {}
    }

    private static String encodeJpeg(BufferedImage img, float quality) throws Exception {
        return Base64.getEncoder().encodeToString(encodeJpegBytes(img, quality));
    }

    private static byte[] encodeJpegBytes(BufferedImage img, float quality) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(50_000);
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

    public static BufferedImage decodeBase64(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }
}
