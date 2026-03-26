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
 * STUDENT CAPTURE: Uses a single persistent FFmpeg gdigrab process with
 *   -draw_mouse 0. FFmpeg runs continuously at 30 FPS streaming MJPEG frames
 *   to stdout. A background thread reads frames into an AtomicReference.
 *   The caller always gets the latest frame (frame-drop semantics).
 *
 *   This is the ONLY guaranteed zero-flicker solution on Windows. The OS
 *   cursor is excluded at the driver level — no hide/show / no API calls
 *   that interact with the hardware cursor layer.
 *
 * ADMIN SCREEN-SHARE: AWT Robot + manual cursor draw. Admin cursor visible
 *   to students during screen sharing sessions.
 */
public class ScreenCapture {

    private static final Rectangle screenRect =
            new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

    // -----------------------------------------------------------------------
    // FFmpeg persistent pipe state (student capture)
    // -----------------------------------------------------------------------
    private static volatile Process    ffmpegProcess;
    private static volatile Thread     ffmpegReaderThread;
    private static final AtomicBoolean pipeRunning   = new AtomicBoolean(false);
    private static final AtomicBoolean ffmpegPresent = new AtomicBoolean(false);

    /** Latest JPEG bytes from the FFmpeg pipe. Null = no frame yet. */
    private static final AtomicReference<byte[]> latestStudentJpeg =
            new AtomicReference<>(null);

    // Shared scaled buffer for encoding
    private static BufferedImage reusableBuffer;
    private static Graphics2D    reusableGraphics;

    // Delta detection
    private static BufferedImage prevFrame;
    private static final double  DELTA_THRESHOLD = 0.015;

    // -----------------------------------------------------------------------
    // Admin screen-share state
    // -----------------------------------------------------------------------
    private static final AtomicReference<String> latestAdminFrame =
            new AtomicReference<>(null);
    private static volatile boolean asyncRunning = false;
    private static Robot adminRobot;

    public static final double QUALITY_LOW    = 0.30;
    public static final double QUALITY_MEDIUM = 0.50;
    public static final double QUALITY_HIGH   = 0.70;
    public static final double QUALITY_ULTRA  = 0.90;

    // Cursor pixels — only drawn on admin screen-share frames
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

    static {
        // Start FFmpeg pipe for cursor-free student capture
        ffmpegPresent.set(checkFfmpeg());
        if (ffmpegPresent.get()) {
            startFfmpegPipe();
        }
        try {
            adminRobot = new Robot();
            adminRobot.setAutoDelay(0);
        } catch (AWTException ignored) {}
    }

    // -----------------------------------------------------------------------
    // FFmpeg persistent pipe management
    // -----------------------------------------------------------------------

    private static boolean checkFfmpeg() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ffmpeg", "-version"});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Starts ONE persistent FFmpeg process capturing the desktop at 30 FPS
     * without the mouse cursor. Frames are MJPEG JPEGs streamed to stdout.
     * A daemon thread reads them into latestStudentJpeg (frame-drop semantics).
     */
    private static synchronized void startFfmpegPipe() {
        if (pipeRunning.get()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-loglevel",   "quiet",
                "-f",          "gdigrab",
                "-framerate",  "30",
                "-draw_mouse", "0",          // ← exclude cursor at OS level
                "-i",          "desktop",
                "-f",          "mjpeg",
                "-q:v",        "4",          // JPEG quality 1-31 (lower=better)
                "pipe:1"
            );
            pb.redirectErrorStream(false);
            ffmpegProcess = pb.start();
            pipeRunning.set(true);

            // Discard stderr so it doesn't block
            Thread errDrain = new Thread(() -> {
                try { ffmpegProcess.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                catch (Exception ignored) {}
            }, "FFmpeg-ErrDrain");
            errDrain.setDaemon(true);
            errDrain.start();

            // Background reader: parse MJPEG boundary and extract JPEG frames
            ffmpegReaderThread = new Thread(ScreenCapture::readMjpegFrames, "FFmpeg-FrameReader");
            ffmpegReaderThread.setDaemon(true);
            ffmpegReaderThread.setPriority(Thread.MAX_PRIORITY - 1);
            ffmpegReaderThread.start();

        } catch (Exception e) {
            pipeRunning.set(false);
            ffmpegProcess = null;
        }
    }

    /**
     * Reads MJPEG frames from the FFmpeg stdout pipe.
     * MJPEG frames start with FFD8 (JPEG SOI) and end with FFD9 (JPEG EOI).
     * We find these markers to extract each complete JPEG frame.
     */
    private static void readMjpegFrames() {
        try (BufferedInputStream bis =
                 new BufferedInputStream(ffmpegProcess.getInputStream(), 512_000)) {

            ByteArrayOutputStream frame = new ByteArrayOutputStream(200_000);
            int prev = -1;

            while (pipeRunning.get() && !Thread.currentThread().isInterrupted()) {
                int b = bis.read();
                if (b < 0) break;

                frame.write(b);

                // Detect JPEG SOI (FF D8) — start of new frame
                if (prev == 0xFF && b == 0xD8) {
                    frame.reset();
                    frame.write(0xFF);
                    frame.write(0xD8);
                }

                // Detect JPEG EOI (FF D9) — end of frame
                if (prev == 0xFF && b == 0xD9 && frame.size() > 10) {
                    byte[] jpeg = frame.toByteArray();
                    // Frame-drop: overwrite with latest; consumer gets newest
                    latestStudentJpeg.set(jpeg);
                    frame.reset();
                }

                prev = b;
            }
        } catch (Exception ignored) {
        } finally {
            pipeRunning.set(false);
        }
    }

    public static synchronized void stopFfmpegPipe() {
        pipeRunning.set(false);
        if (ffmpegProcess != null) {
            ffmpegProcess.destroyForcibly();
            ffmpegProcess = null;
        }
        if (ffmpegReaderThread != null) {
            ffmpegReaderThread.interrupt();
            ffmpegReaderThread = null;
        }
        latestStudentJpeg.set(null);
    }

    // -----------------------------------------------------------------------
    // Student capture helpers — consume from the pipe
    // -----------------------------------------------------------------------

    private static BufferedImage getLatestStudentFrame() {
        byte[] jpeg = latestStudentJpeg.getAndSet(null);
        if (jpeg == null) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (Exception e) {
            return null;
        }
    }

    /** Fallback when FFmpeg is unavailable — AWT Robot but NO cursor draw. */
    private static BufferedImage captureRobotNoCursor() {
        if (adminRobot == null) return new BufferedImage(screenRect.width, screenRect.height, BufferedImage.TYPE_INT_RGB);
        return adminRobot.createScreenCapture(screenRect);
    }

    // -----------------------------------------------------------------------
    // Public API — student-facing (cursor excluded)
    // -----------------------------------------------------------------------

    public static String captureAsBase64(double resolutionScale, float jpegQuality) {
        try {
            BufferedImage capture = ffmpegPresent.get() ? getLatestStudentFrame() : captureRobotNoCursor();
            if (capture == null) return null;
            return encodeScaled(capture, resolutionScale, jpegQuality);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] captureAsBytes(double resolutionScale, float jpegQuality) {
        try {
            BufferedImage capture = ffmpegPresent.get() ? getLatestStudentFrame() : captureRobotNoCursor();
            if (capture == null) return null;
            return encodeScaledBytes(capture, resolutionScale, jpegQuality);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Streaming capture with delta detection.
     * Returns null if frame is nearly identical (bandwidth optimization).
     */
    public static String captureForStreaming() {
        try {
            BufferedImage capture = ffmpegPresent.get() ? getLatestStudentFrame() : captureRobotNoCursor();
            if (capture == null) return null;

            if (prevFrame != null && isDeltaSmall(prevFrame, capture)) return null;

            int qW = capture.getWidth()  / 4;
            int qH = capture.getHeight() / 4;
            prevFrame = new BufferedImage(qW, qH, BufferedImage.TYPE_INT_RGB);
            prevFrame.createGraphics().drawImage(capture, 0, 0, qW, qH, null);

            return encodeScaled(capture, 1.0, 0.72f);
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Admin screen-share (cursor included)
    // -----------------------------------------------------------------------

    public static void startAsyncCapture() {
        if (asyncRunning) return;
        asyncRunning = true;
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
    }

    public static String getLatestFrame() { return latestAdminFrame.get(); }

    /** Admin screen-share: AWT Robot + cursor drawn on top. */
    public static String captureAsBase64WithCursor(double scale, float quality) {
        try {
            if (adminRobot == null) return null;
            BufferedImage capture = adminRobot.createScreenCapture(screenRect);
            int w = (int)(capture.getWidth()  * scale);
            int h = (int)(capture.getHeight() * scale);
            ensureBuffer(w, h);
            reusableGraphics.drawImage(capture, 0, 0, w, h, null);
            drawMouseCursor(reusableGraphics, scale, scale);
            return encodeJpeg(reusableBuffer, quality);
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
    // Encode helpers
    // -----------------------------------------------------------------------

    private static String encodeScaled(BufferedImage img, double scale, float quality) throws Exception {
        return Base64.getEncoder().encodeToString(encodeScaledBytes(img, scale, quality));
    }

    private static byte[] encodeScaledBytes(BufferedImage img, double scale, float quality) throws Exception {
        int w = (int)(img.getWidth()  * scale);
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
    // Cursor drawing (admin screen-share only)
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
