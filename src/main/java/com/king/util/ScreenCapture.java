package com.king.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;

/**
 * King of Lab — upgraded ScreenCapture.
 *
 * New features vs. King of Lab:
 *  - Delta-frame detection: if the frame has < DELTA_THRESHOLD% of pixels changed,
 *    captureForStreaming() returns null (skip send — saves bandwidth).
 *  - Cursor flicker fix: capture interval aligned to 16 ms boundary.
 *  - configurable quality via AdaptiveStreamController FPS tier.
 */
public class ScreenCapture {

    private static Robot         robot;
    private static Rectangle     screenRect;
    private static BufferedImage reusableBuffer;
    private static Graphics2D    reusableGraphics;
    private static BufferedImage prevFrame; // for delta detection

    // Delta threshold: skip frame if fewer than 1.5% of pixels differ
    private static final double DELTA_THRESHOLD = 0.015;

    // Async double-buffer
    private static final java.util.concurrent.atomic.AtomicReference<String>
            latestFrame = new java.util.concurrent.atomic.AtomicReference<>(null);
    private static volatile boolean asyncRunning = false;

    public static final double QUALITY_LOW    = 0.30;
    public static final double QUALITY_MEDIUM = 0.50;
    public static final double QUALITY_HIGH   = 0.70;
    public static final double QUALITY_ULTRA  = 0.90;

    // -----------------------------------------------------------------------
    // Cursor shape (drawn manually since AWT Robot doesn't capture it)
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

    static {
        try {
            robot = new Robot();
            robot.setAutoDelay(0);
            screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Core capture
    // -----------------------------------------------------------------------

    public static String captureAsBase64(double resolutionScale, float jpegQuality) {
        try {
            // Align capture to 16 ms boundary to reduce cursor flicker
            long now     = System.currentTimeMillis();
            long aligned = (now / 16) * 16;
            long wait    = aligned + 16 - now;
            if (wait > 0 && wait < 16) Thread.sleep(wait);

            BufferedImage capture = robot.createScreenCapture(screenRect);

            int newW = (int) (capture.getWidth()  * resolutionScale);
            int newH = (int) (capture.getHeight() * resolutionScale);

            if (reusableBuffer == null
                    || reusableBuffer.getWidth()  != newW
                    || reusableBuffer.getHeight() != newH) {
                if (reusableGraphics != null) reusableGraphics.dispose();
                reusableBuffer   = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                reusableGraphics = reusableBuffer.createGraphics();
                reusableGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                reusableGraphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED);
                prevFrame = null; // reset delta baseline on size change
            }

            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);
            // Cursor deliberately NOT drawn for student capture

            return encodeJpeg(reusableBuffer, jpegQuality);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] captureAsBytes(double resolutionScale, float jpegQuality) {
        try {
            BufferedImage capture = robot.createScreenCapture(screenRect);
            int newW = (int) (capture.getWidth()  * resolutionScale);
            int newH = (int) (capture.getHeight() * resolutionScale);

            if (reusableBuffer == null || reusableBuffer.getWidth() != newW || reusableBuffer.getHeight() != newH) {
                if (reusableGraphics != null) reusableGraphics.dispose();
                reusableBuffer   = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                reusableGraphics = reusableBuffer.createGraphics();
                reusableGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }

            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);
            // Cursor deliberately NOT drawn for student capture

            ByteArrayOutputStream baos = new ByteArrayOutputStream(50_000);
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(jpegQuality);
                ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(reusableBuffer, null, null), param);
                writer.dispose();
                ios.close();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Streaming entry point (with delta detection)
    // -----------------------------------------------------------------------

    /**
     * Captures the screen for streaming.
     * Returns null if the frame changed less than DELTA_THRESHOLD — caller must skip sending.
     */
    public static String captureForStreaming() {
        try {
            long now     = System.currentTimeMillis();
            long aligned = (now / 16) * 16;
            long wait    = aligned + 16 - now;
            if (wait > 0 && wait < 16) Thread.sleep(wait);

            BufferedImage capture = robot.createScreenCapture(screenRect);

            // Delta check at quarter resolution for speed
            if (prevFrame != null && isDeltaSmall(prevFrame, capture)) {
                return null; // frame effectively unchanged
            }

            // Store a quarter-res copy of this frame for next delta check
            int qW = capture.getWidth()  / 4;
            int qH = capture.getHeight() / 4;
            prevFrame = new BufferedImage(qW, qH, BufferedImage.TYPE_INT_RGB);
            prevFrame.createGraphics().drawImage(capture, 0, 0, qW, qH, null);

            // Scale to full stream resolution and encode
            int newW = capture.getWidth();
            int newH = capture.getHeight();

            if (reusableBuffer == null
                    || reusableBuffer.getWidth()  != newW
                    || reusableBuffer.getHeight() != newH) {
                if (reusableGraphics != null) reusableGraphics.dispose();
                reusableBuffer   = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                reusableGraphics = reusableBuffer.createGraphics();
                reusableGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                reusableGraphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED);
            }
            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);
            // Cursor deliberately NOT drawn for student capture

            return encodeJpeg(reusableBuffer, 0.72f);

        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Delta detection
    // -----------------------------------------------------------------------

    /**
     * Compare two images at quarter resolution.
     * Returns true if proportion of changed pixels < DELTA_THRESHOLD.
     */
    private static boolean isDeltaSmall(BufferedImage prev, BufferedImage current) {
        int qW = prev.getWidth();
        int qH = prev.getHeight();

        // Down-sample current to quarter res for comparison
        BufferedImage curQ = new BufferedImage(qW, qH, BufferedImage.TYPE_INT_RGB);
        curQ.createGraphics().drawImage(current, 0, 0, qW, qH, null);

        int totalPixels   = qW * qH;
        int changedPixels = 0;
        int threshold     = (int) (totalPixels * DELTA_THRESHOLD);

        for (int y = 0; y < qH; y++) {
            for (int x = 0; x < qW; x++) {
                if (prev.getRGB(x, y) != curQ.getRGB(x, y)) {
                    changedPixels++;
                    if (changedPixels > threshold) return false; // early exit — frame changed enough
                }
            }
        }
        return true; // very few pixels changed — skip this frame
    }

    // -----------------------------------------------------------------------
    // Async capture (used by admin screen-share)
    // -----------------------------------------------------------------------

    public static void startAsyncCapture() {
        if (asyncRunning) return;
        asyncRunning = true;

        Thread t = new Thread(() -> {
            while (asyncRunning) {
                try {
                    String frame = captureAsBase64WithCursor(1.0, 0.8f);
                    if (frame != null) latestFrame.set(frame);
                    Thread.sleep(16); // ~60 Hz max capture rate for admin share
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
    // Helpers
    // -----------------------------------------------------------------------

    private static void drawMouseCursor(Graphics2D g, double scaleX, double scaleY) {
        try {
            Point p  = MouseInfo.getPointerInfo().getLocation();
            int mx   = (int) (p.x * scaleX);
            int my   = (int) (p.y * scaleY);
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
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    public static String captureAsBase64(double scale) { return captureAsBase64(scale, 0.85f); }
    public static String captureHighQuality()           { return captureAsBase64(1.0,  0.95f); }

    // Used exclusively by Admin Screen Share
    public static String captureAsBase64WithCursor(double resolutionScale, float jpegQuality) {
        try {
            long now     = System.currentTimeMillis();
            long aligned = (now / 16) * 16;
            long wait    = aligned + 16 - now;
            if (wait > 0 && wait < 16) Thread.sleep(wait);

            BufferedImage capture = robot.createScreenCapture(screenRect);

            int newW = (int) (capture.getWidth()  * resolutionScale);
            int newH = (int) (capture.getHeight() * resolutionScale);

            if (reusableBuffer == null
                    || reusableBuffer.getWidth()  != newW
                    || reusableBuffer.getHeight() != newH) {
                if (reusableGraphics != null) reusableGraphics.dispose();
                reusableBuffer   = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                reusableGraphics = reusableBuffer.createGraphics();
                reusableGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                reusableGraphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED);
                prevFrame = null;
            }

            reusableGraphics.drawImage(capture, 0, 0, newW, newH, null);
            drawMouseCursor(reusableGraphics, resolutionScale, resolutionScale);

            return encodeJpeg(reusableBuffer, jpegQuality);

        } catch (Exception e) {
            return null;
        }
    }

    public static BufferedImage decodeBase64(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }
}
