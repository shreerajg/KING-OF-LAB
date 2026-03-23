package com.king.util;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

/**
 * Reads system-level CPU and memory metrics for the admin performance dashboard.
 * Uses com.sun.management.OperatingSystemMXBean for process CPU load.
 */
public class PerformanceMonitor {

    private static final OperatingSystemMXBean osMXBean;
    private static final Runtime runtime = Runtime.getRuntime();

    // Rolling FPS counter
    private static volatile long lastFrameTime = System.currentTimeMillis();
    private static volatile int  currentFps    = 0;
    private static volatile int  frameCount    = 0;

    static {
        OperatingSystemMXBean bean;
        try {
            bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Exception e) {
            bean = null;
        }
        osMXBean = bean;
    }

    /**
     * Returns CPU usage of the whole system as a percentage (0–100).
     * May return -1 if not available.
     */
    public static int getCpuPercent() {
        if (osMXBean == null) return -1;
        double load = osMXBean.getCpuLoad();
        if (load < 0) return -1;
        return (int) Math.round(load * 100);
    }

    /**
     * Returns JVM heap used in MB.
     */
    public static int getUsedHeapMB() {
        long used = runtime.totalMemory() - runtime.freeMemory();
        return (int) (used / (1024 * 1024));
    }

    /**
     * Returns max JVM heap in MB.
     */
    public static int getMaxHeapMB() {
        return (int) (runtime.maxMemory() / (1024 * 1024));
    }

    /**
     * Call this every time a screen frame is sent/received to track FPS.
     */
    public static void recordFrame() {
        frameCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - lastFrameTime;
        if (elapsed >= 1000) {
            currentFps    = (int) (frameCount * 1000L / elapsed);
            frameCount    = 0;
            lastFrameTime = now;
        }
    }

    /** Returns the measured FPS over the last second. */
    public static int getFps() {
        return currentFps;
    }

    /** Returns a formatted summary string for the status bar. */
    public static String getSummary(int clientCount) {
        int cpu = getCpuPercent();
        String cpuStr = cpu < 0 ? "N/A" : cpu + "%";
        return String.format("CPU: %s  |  RAM: %dMB / %dMB  |  Clients: %d  |  FPS: %d",
                cpuStr, getUsedHeapMB(), getMaxHeapMB(), clientCount, getFps());
    }
}
