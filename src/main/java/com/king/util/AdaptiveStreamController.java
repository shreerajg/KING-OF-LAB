package com.king.util;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

/**
 * Adapts the screen-capture interval based on live CPU load and AI activity.
 *
 * Mutual-exclusivity rules:
 *  - AI active       → FPS capped at Config.FPS_AI_MODE  (10 fps)
 *  - CPU > 70%       → FPS_LOW                           (15 fps)
 *  - CPU 40–70%      → FPS_MEDIUM                        (25 fps)
 *  - CPU < 40%       → FPS_HIGH                          (35 fps)
 */
public class AdaptiveStreamController {

    private static final OperatingSystemMXBean osMXBean;
    private static volatile boolean aiProcessing = false;

    static {
        OperatingSystemMXBean bean;
        try {
            bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Exception e) {
            bean = null;
        }
        osMXBean = bean;
    }

    /** Call this when AI inference starts/stops so FPS can throttle accordingly. */
    public static void setAiProcessing(boolean running) {
        aiProcessing = running;
    }

    /** Returns true if AI is actively processing a request. */
    public static boolean isAiProcessing() {
        return aiProcessing;
    }

    /**
     * Returns the current target FPS based on CPU load and AI state.
     */
    public static int getTargetFps() {
        if (aiProcessing) return Config.FPS_AI_MODE;
        double cpu = getCpuLoad();
        if (cpu > 0.70) return Config.FPS_LOW;
        if (cpu > 0.40) return Config.FPS_MEDIUM;
        return Config.FPS_HIGH;
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
