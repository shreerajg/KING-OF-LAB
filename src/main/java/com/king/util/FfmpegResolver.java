package com.king.util;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * King of Lab — FFmpeg Binary Resolver
 *
 * Resolves the absolute path to the FFmpeg executable that this application
 * should use.  Priority order:
 *
 *   1. Bundled binary  — &lt;app-root&gt;/ffmpeg/ffmpeg.exe  (Windows)
 *                         &lt;app-root&gt;/ffmpeg/ffmpeg       (Linux / macOS)
 *   2. System PATH     — "ffmpeg" (last resort; may not be present on all machines)
 *
 * The "app root" is resolved at class-load time relative to the JVM working
 * directory (System.getProperty("user.dir")).  When you launch the application
 * via scripts/run.bat the working directory is the project root, so the
 * bundled binary lives at  ./ffmpeg/ffmpeg.exe  as distributed.
 *
 * DEPLOYMENT NOTE:
 *   The ./ffmpeg/ directory (containing ffmpeg.exe on Windows, or the ffmpeg
 *   binary on Linux/macOS) MUST be distributed alongside the application.
 *   Do NOT remove it from the deployment package.  See DEPLOYMENT.md for
 *   full instructions.
 */
public final class FfmpegResolver {

    /** Relative sub-directory name that holds the bundled binary. */
    private static final String FFMPEG_DIR = "ffmpeg";

    /** Resolved path — computed once at class-load time. */
    private static final String RESOLVED_PATH = resolve();

    private FfmpegResolver() { /* utility class */ }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns the FFmpeg executable path to use for {@link ProcessBuilder} /
     * {@link Runtime#exec} calls.
     *
     * <ul>
     *   <li>If the bundled binary exists and is executable, its absolute
     *       path is returned (e.g. {@code C:\App\ffmpeg\ffmpeg.exe}).</li>
     *   <li>Otherwise {@code "ffmpeg"} is returned so the OS searches PATH.</li>
     * </ul>
     *
     * @return non-null, non-empty executable token/path
     */
    public static String get() {
        return RESOLVED_PATH;
    }

    /**
     * Logs the resolved path on first call — useful during startup diagnostics.
     */
    public static void logResolved() {
        boolean bundled = !RESOLVED_PATH.equals("ffmpeg");
        System.out.println("[FfmpegResolver] Using FFmpeg: " + RESOLVED_PATH
                + (bundled ? " (bundled)" : " (system PATH)"));
    }

    // -----------------------------------------------------------------------
    // Resolution logic
    // -----------------------------------------------------------------------

    private static String resolve() {
        // Determine OS-specific binary name
        String os        = System.getProperty("os.name", "").toLowerCase();
        String exeName   = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";

        // Build candidate path:  <working-dir>/ffmpeg/<exeName>
        String workDir   = System.getProperty("user.dir", ".");
        Path   candidate = Paths.get(workDir, FFMPEG_DIR, exeName);
        File   file      = candidate.toFile();

        if (file.exists() && file.canExecute()) {
            return file.getAbsolutePath();
        }

        // Warn but don't throw — system PATH may still work
        System.err.println("[FfmpegResolver] Bundled FFmpeg not found at: "
                + candidate.toAbsolutePath()
                + " — falling back to system PATH. "
                + "Ensure the ffmpeg/ directory is present in the app root.");
        return "ffmpeg";
    }
}
