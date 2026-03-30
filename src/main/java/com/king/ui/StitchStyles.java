package com.king.ui;

/**
 * Centralized inline-style helpers for the Stitch "Command Sentinel" design system.
 *
 * Constraint: keep styling inline (no external CSS file) to preserve the project's
 * current packaging/build approach, while still providing consistent theming.
 */
public final class StitchStyles {
    private StitchStyles() {}

    // Palette (from stitch/DESIGN.md)
    public static final String C_SURFACE              = "#10141a";
    public static final String C_SURFACE_CONTAINER    = "#1c2026";
    public static final String C_SURFACE_HIGH         = "#262a31";
    public static final String C_SURFACE_HIGHEST      = "#31353c";
    public static final String C_SURFACE_LOWEST       = "#0a0e14";
    public static final String C_PRIMARY              = "#00f0ff";
    public static final String C_PRIMARY_GLASS        = "#dbfcff";
    public static final String C_SECONDARY_CONTAINER  = "#7701d0";
    public static final String C_TEXT_MAIN            = "#dfe2eb";
    public static final String C_TEXT_MUTED           = "#849495";
    public static final String C_ON_PRIMARY_FIXED     = "#002022";

    // Opacity helpers
    public static String rgba(String hex, double alpha) {
        // expects #RRGGBB
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
    }

    public static String appRoot() {
        // Prefer Space Grotesk if available; otherwise fallback.
        return "-fx-background-color: " + C_SURFACE + ";" +
               "-fx-font-family: 'Space Grotesk','Segoe UI','Inter',sans-serif;";
    }

    public static String glassPanel(double opacity, int radiusPx) {
        // "Glass & Gradient" rule approximation (JavaFX doesn't support true backdrop-blur).
        // We simulate with translucent surface + subtle inner glow and soft ambient shadow via effects.
        return "-fx-background-color: " + rgba(C_SURFACE_LOWEST, opacity) + ";" +
               "-fx-background-radius: " + radiusPx + "px;" +
               "-fx-border-color: " + rgba(C_PRIMARY_GLASS, 0.10) + ";" +
               "-fx-border-radius: " + radiusPx + "px;" +
               "-fx-border-width: 1px;";
    }

    public static String tonalPanel(String hex, int radiusPx) {
        return "-fx-background-color: " + hex + ";" +
               "-fx-background-radius: " + radiusPx + "px;";
    }

    public static String labelSmMuted() {
        return "-fx-text-fill: " + rgba(C_TEXT_MAIN, 0.45) + ";" +
               "-fx-font-size: 9px;" +
               "-fx-font-weight: 900;" +
               "-fx-letter-spacing: 0.25em;";
    }

    public static String titleMd() {
        return "-fx-text-fill: " + C_TEXT_MAIN + ";" +
               "-fx-font-size: 14px;" +
               "-fx-font-weight: 800;";
    }

    public static String gradientPrimaryCta(int radiusPx) {
        return "-fx-background-color: linear-gradient(to bottom right, " + C_PRIMARY + " 0%, " + C_SECONDARY_CONTAINER + " 100%);" +
               "-fx-text-fill: " + C_ON_PRIMARY_FIXED + ";" +
               "-fx-font-weight: 900;" +
               "-fx-letter-spacing: 0.12em;" +
               "-fx-background-radius: " + radiusPx + "px;" +
               "-fx-cursor: hand;";
    }
}

