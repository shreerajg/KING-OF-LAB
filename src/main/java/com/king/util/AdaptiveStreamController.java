package com.king.util;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

/**
 * Adapts the screen-capture interval based on live CPU load, AI activity,
 * and whether an admin is actively receiving the stream.
 *
 * FPS tiers:
 *  - No admin / streaming off → 0 (pipe is idle / lazy-stopped)
 *  - AI active               → FPS_AI_MODE  (10 fps)
 *  - CPU > 80%               → FPS_LOW      (15 fps)
 *  - CPU 60–80%              → FPS_MEDIUM   (25 fps)
 *  - CPU < 60%               → FPS_HIGH     (35 fps)
 *  Hard ceiling              → 20 fps (GPU protection — avoids 94% GPU)
 */
public class AdaptiveStreamController {

    private static final OperatingSystemMXBean osMXBean;
    private static volatile boolean aiProcessing   = false;

    /**
     * Set to true only while an admin is actively connected and receiving frames.
     * When false, the lazy-start guard in ScreenCapture will avoid spinning up FFmpeg.
     */
    private static volatile boolean streamingActive = false;

    /** Absolute FPS ceiling — guards against GPU saturation. */
    private static final int GPU_SAFE_FPS_CEILING = 20;

    static {
        OperatingSystemMXBean bean;
        try {
            bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Exception e) {
            bean = null;
        }
        osMXBean = bean;
    }

    /** Call when AI inference starts/stops so FPS can throttle accordingly. */
    public static void setAiProcessing(boolean running) {
        aiProcessing = running;
    }

    /** Returns true if AI is actively processing a request. */
    public static boolean isAiProcessing() {
        return aiProcessing;
    }

    /**
     * Set to true when admin connects and starts receiving frames,
     * false when admin disconnects.  Used by ScreenCapture lazy-start
     * to avoid running FFmpeg when nobody is watching.
     */
    public static void setStreamingActive(boolean active) {
        streamingActive = active;
    }

    public static boolean isStreamingActive() {
        return streamingActive;
    }

    /**
     * Returns the current target FPS based on CPU load and AI state,
     * clamped to GPU_SAFE_FPS_CEILING.
     */
    public static int getTargetFps() {
        if (aiProcessing) return Config.FPS_AI_MODE;
        double cpu = getCpuLoad();
        int fps;
        if (cpu > 0.80)      fps = Config.FPS_LOW;    // 15
        else if (cpu > 0.60) fps = Config.FPS_MEDIUM;  // 25 — clamped below
        else                 fps = Config.FPS_HIGH;    // 35 — clamped below
        // Hard GPU ceiling: never exceed 20 FPS regardless of CPU readings
        return Math.min(fps, GPU_SAFE_FPS_CEILING);
    }

    /**
     * Returns the current capture/send interval in milliseconds.
     */
    public static long getIntervalMs() {
        int fps = getTargetFps();
        return 1000L / fps;
    }

    private static double getCpuLoad() {
        if (osMXBean == null) return 0.0;
        double load = osMXBean.getCpuLoad();
        return load < 0 ? 0.0 : load;
    }
}
