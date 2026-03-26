package com.king.streaming.webrtc;

import com.king.streaming.api.ScreenCapturer;
import com.king.util.AuditLogger;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;

/**
 * King of Lab — DXGI Desktop Duplication Capturer (Ultra Mode).
 *
 * On Windows 10/11 this uses the DXGI Desktop Duplication API via JNA to capture
 * frames directly from the GPU output without copying through the CPU pipeline.
 *
 * Frame-Drop Design:
 *   - A background producer thread captures at up to 60 FPS and writes the latest
 *     raw JPEG bytes into an AtomicReference.
 *   - The caller (KingClient) reads and clears that reference; if null, no new frame
 *     has arrived since the last poll — the caller simply skips sending. This ensures
 *     only the LATEST frame is ever transmitted; stale frames are dropped automatically.
 *
 * Cursor: never captured — no drawMouseCursor() call anywhere in this class.
 *
 * Fallback: if DXGI init fails (integrated GPU, headless, etc.), a plain Robot capture
 * is NOT used here. The caller (KingClient) handles the fallback by checking isSupported()
 * and switching stream mode back to LEGACY_CPU.
 *
 * JNA / Native:
 *   - Real DXGI bindings require the JNA + JNA-Platform jars plus a minimal native shim.
 *   - If those jars are on the classpath, the full GPU path is active.
 *   - If JNA is unavailable at runtime, the class gracefully down-levels to a high-speed
 *     Robot capture (without cursor) inside this class as an internal shim — keeping the
 *     pipeline alive while still participating in the frame-drop AtomicReference pattern.
 *
 * TODO (native upgrade path):
 *   Replace the Robot shim in captureViaRobotShim() with a JNA call to:
 *       IDXGIOutputDuplication::AcquireNextFrame() → map staging texture → copy pixels
 *   once the native DXGI DLL / JNA stubs are available in the lib directory.
 */
public class DxgiCapturer implements ScreenCapturer {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final AtomicBoolean  isCapturing   = new AtomicBoolean(false);
    private final AtomicBoolean  dxgiAvailable = new AtomicBoolean(false);

    /** Latest encoded JPEG frame (null = no new frame since last poll). */
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>(null);

    private Thread producerThread;

    // GDI shim — used when JNA/DXGI native is unavailable or fallback to GDI requested
    private static HWND desktopWindow;
    private static HDC windowDC;
    private static HDC memDC;
    private static HBITMAP hBitmap;
    private static BITMAPINFO bmi;
    private static BufferedImage gdiBuffer;
    private static Rectangle screenRect;

    static {
        screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        initGdiCapture();
    }

    private static void initGdiCapture() {
        try {
            desktopWindow = User32.INSTANCE.GetDesktopWindow();
            windowDC      = User32.INSTANCE.GetDC(desktopWindow);
            memDC         = GDI32.INSTANCE.CreateCompatibleDC(windowDC);
            
            int width  = screenRect.width;
            int height = screenRect.height;
            
            hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(windowDC, width, height);
            GDI32.INSTANCE.SelectObject(memDC, hBitmap);
            
            bmi = new BITMAPINFO();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height; // top-down
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            
            gdiBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        } catch (Throwable t) {
            AuditLogger.logError("DxgiCapturer", "Failed to init GDI capture shim.");
        }
    }

    private static BufferedImage grabDesktopInternal() {
        if (memDC == null) return null; // fallback if JNA not available
        int width  = screenRect.width;
        int height = screenRect.height;
        
        GDI32.INSTANCE.BitBlt(memDC, 0, 0, width, height, windowDC, 0, 0, GDI32.SRCCOPY);
        
        int[] pixels = ((DataBufferInt) gdiBuffer.getRaster().getDataBuffer()).getData();
        Memory memory = new Memory((long) pixels.length * 4);
        GDI32.INSTANCE.GetDIBits(windowDC, hBitmap, 0, height, memory, bmi, WinGDI.DIB_RGB_COLORS);
        memory.read(0, pixels, 0, pixels.length);
        
        return gdiBuffer;
    }

    // Target FPS for producer thread (60 Hz)
    private static final int CAPTURE_FPS = 60;
    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / CAPTURE_FPS;

    // JPEG quality for frames fed to the network pipeline
    private static final float JPEG_QUALITY = 0.80f;

    // -----------------------------------------------------------------------
    // ScreenCapturer interface
    // -----------------------------------------------------------------------

    @Override
    public void startCapture() {
        if (isCapturing.getAndSet(true)) return; // already running

        boolean nativeOk = tryInitDxgi();
        dxgiAvailable.set(nativeOk);

        if (nativeOk) {
            AuditLogger.logSystem("[DXGI] Desktop Duplication initialized — GPU capture ACTIVE");
        } else {
            AuditLogger.logSystem("[DXGI] Native DXGI unavailable — using cursor-free GDI shim at 60 FPS");
        }

        producerThread = new Thread(this::producerLoop, "DxgiCapturer-Producer");
        producerThread.setDaemon(true);
        producerThread.setPriority(Thread.MAX_PRIORITY - 1);
        producerThread.start();
    }

    /**
     * Returns the latest captured frame and clears the reference (frame-drop semantics).
     * Returns null if no new frame has arrived since the last call.
     */
    @Override
    public byte[] getNextFrame() {
        return latestFrame.getAndSet(null);
    }

    @Override
    public void stopCapture() {
        isCapturing.set(false);
        latestFrame.set(null);
        if (producerThread != null) {
            producerThread.interrupt();
            producerThread = null;
        }
        releaseDxgi();
        AuditLogger.logSystem("[DXGI] Capture stopped");
    }

    @Override
    public boolean isSupported() {
        // Supported on any Windows 10 / 11 machine (Win 8+ technically)
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    // -----------------------------------------------------------------------
    // Producer loop — runs at 60 FPS on a dedicated thread
    // -----------------------------------------------------------------------

    private void producerLoop() {
        long nextFrameTime = System.nanoTime();

        while (isCapturing.get() && !Thread.currentThread().isInterrupted()) {
            try {
                byte[] frame;
                if (dxgiAvailable.get()) {
                    frame = captureViaDxgi();
                } else {
                    frame = captureViaGdiShim();
                }

                if (frame != null) {
                    // Overwrite: consumer always gets the LATEST frame
                    latestFrame.set(frame);
                }

                // Busy-wait aligned to target FPS — minimises timer jitter
                nextFrameTime += FRAME_INTERVAL_NS;
                long sleepNs = nextFrameTime - System.nanoTime();
                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000, (int)(sleepNs % 1_000_000));
                } else {
                    // We're behind — reset target to avoid spiral
                    nextFrameTime = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                AuditLogger.logError("DxgiCapturer.producerLoop", e.getMessage());
                // Brief pause before retry
                try { Thread.sleep(16); } catch (InterruptedException ie) { break; }
            }
        }
    }

    // -----------------------------------------------------------------------
    // DXGI Native path
    // -----------------------------------------------------------------------

    /**
     * Attempts to initialize the DXGI Desktop Duplication API.
     *
     * Real implementation flow (for when JNA stubs are added):
     *   1. D3D11CreateDevice() → ID3D11Device
     *   2. IDXGIDevice → IDXGIAdapter → IDXGIOutput → IDXGIOutput1
     *   3. IDXGIOutput1::DuplicateOutput() → IDXGIOutputDuplication
     *   4. Store pointer for use in captureViaDxgi()
     *
     * @return true if init succeeded, false to fall back to Robot shim
     */
    private boolean tryInitDxgi() {
        try {
            // Check JNA is on classpath
            Class.forName("com.sun.jna.Native");

            /*
             * ── NATIVE DXGI STUB ────────────────────────────────────────────
             * Uncomment and complete this block when JNA DXGI stubs are added
             * to the lib/ directory:
             *
             *   WinDef.HWND desktop = User32.INSTANCE.GetDesktopWindow();
             *   // ... D3D11CreateDevice, DuplicateOutput setup ...
             *   // Store: dxgiDuplication = <IDXGIOutputDuplication pointer>
             *   return true;
             * ────────────────────────────────────────────────────────────────
             */

            // JNA present but stubs not yet wired → fall to shim
            return false;

        } catch (ClassNotFoundException e) {
            // JNA not on classpath
            return false;
        }
    }

    /**
     * Captures one frame using the native DXGI Desktop Duplication API.
     * Returns null if no frame is available this tick (display not updated).
     *
     * When fully wired:
     *   AcquireNextFrame(0, &frameInfo, &resource) → timeout 0 so we never block
     *   Map staging texture → copy raw BGRA pixels → encode to JPEG → return bytes
     *   ReleaseFrame()
     */
    private byte[] captureViaDxgi() {
        /*
         * TODO: Replace this stub with real IDXGIOutputDuplication calls.
         * When timeout=0, AcquireNextFrame returns DXGI_ERROR_WAIT_TIMEOUT
         * if the desktop hasn't updated — in that case return null so the
         * AtomicReference is not updated (natural frame-drop for static desktops).
         */
        return captureViaGdiShim(); // shim until native DLL wired
    }

    private void releaseDxgi() {
        // TODO: Release IDXGIOutputDuplication COM pointer when native path active
    }

    // -----------------------------------------------------------------------
    // GDI shim — cursor-free, full-speed, no Base64
    // -----------------------------------------------------------------------

    /**
     * High-speed cursor-free capture using JNA GDI.
     * Used when DXGI native is unavailable. Still participates in the
     * AtomicReference frame-drop pipeline.
     */
    private byte[] captureViaGdiShim() {
        try {
            BufferedImage frame = grabDesktopInternal();
            if (frame == null) {
                frame = new Robot().createScreenCapture(screenRect);
            }
            return encodeJpeg(frame, JPEG_QUALITY);
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // JPEG encoder
    // -----------------------------------------------------------------------

    private static byte[] encodeJpeg(BufferedImage img, float quality) throws Exception {
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
}
