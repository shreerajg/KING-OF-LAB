package com.king.ui;

/**
 * Centralized inline-style helpers for the Stitch "Command Sentinel" design system.
 * Expanded to support multiple themes: Cyberpunk (Default), Midnight, Sakura, and Light.
 */
public final class StitchStyles {
    private StitchStyles() {}

    public enum Theme {
        CYBERPUNK, // Dark Blue/Cyan/Purple
        MIDNIGHT,  // Pure Black/Gray/Electric Blue
        SAKURA,    // Dark/Soft Pink/Rose
        LIGHT      // Clean White/Soft Gray/Blue
    }

    private static Theme currentTheme = Theme.CYBERPUNK;

    // Palette Variables (Dynamic)
    public static String C_SURFACE;
    public static String C_SURFACE_CONTAINER;
    public static String C_SURFACE_HIGH;
    public static String C_SURFACE_HIGHEST;
    public static String C_SURFACE_LOWEST;
    public static String C_PRIMARY;
    public static String C_PRIMARY_GLASS;
    public static String C_SECONDARY_CONTAINER;
    public static String C_TEXT_MAIN;
    public static String C_TEXT_MUTED;
    public static String C_ON_PRIMARY_FIXED;

    static {
        applyTheme(Theme.CYBERPUNK);
    }

    public static void applyTheme(Theme theme) {
        currentTheme = theme;
        switch (theme) {
            case MIDNIGHT:
                C_SURFACE              = "#050505";
                C_SURFACE_CONTAINER    = "#0f0f0f";
                C_SURFACE_HIGH         = "#1a1a1a";
                C_SURFACE_HIGHEST      = "#252525";
                C_SURFACE_LOWEST       = "#000000";
                C_PRIMARY              = "#0077ff"; // Electric Blue
                C_PRIMARY_GLASS        = "#e0f0ff";
                C_SECONDARY_CONTAINER  = "#333333";
                C_TEXT_MAIN            = "#eeeeee";
                C_TEXT_MUTED           = "#777777";
                C_ON_PRIMARY_FIXED     = "#ffffff";
                break;
            case SAKURA:
                C_SURFACE              = "#1a0f14"; // Deep Rose Dark
                C_SURFACE_CONTAINER    = "#26151c";
                C_SURFACE_HIGH         = "#331b25";
                C_SURFACE_HIGHEST      = "#40222e";
                C_SURFACE_LOWEST       = "#120a0e";
                C_PRIMARY              = "#ff8fab"; // Sakura Pink
                C_PRIMARY_GLASS        = "#ffe5ec";
                C_SECONDARY_CONTAINER  = "#fb6f92";
                C_TEXT_MAIN            = "#ffc2d1";
                C_TEXT_MUTED           = "#8c6b74";
                C_ON_PRIMARY_FIXED     = "#590d22";
                break;
            case LIGHT:
                C_SURFACE              = "#f8f9fa";
                C_SURFACE_CONTAINER    = "#ffffff";
                C_SURFACE_HIGH         = "#e9ecef";
                C_SURFACE_HIGHEST      = "#dee2e6";
                C_SURFACE_LOWEST       = "#f1f3f5";
                C_PRIMARY              = "#0056b3"; // Deep Blue
                C_PRIMARY_GLASS        = "#e7f1ff";
                C_SECONDARY_CONTAINER  = "#6c757d";
                C_TEXT_MAIN            = "#212529";
                C_TEXT_MUTED           = "#6c757d";
                C_ON_PRIMARY_FIXED     = "#ffffff";
                break;
            case CYBERPUNK:
            default:
                C_SURFACE              = "#10141a";
                C_SURFACE_CONTAINER    = "#1c2026";
                C_SURFACE_HIGH         = "#262a31";
                C_SURFACE_HIGHEST      = "#31353c";
                C_SURFACE_LOWEST       = "#0a0e14";
                C_PRIMARY              = "#00f0ff";
                C_PRIMARY_GLASS        = "#dbfcff";
                C_SECONDARY_CONTAINER  = "#7701d0";
                C_TEXT_MAIN            = "#dfe2eb";
                C_TEXT_MUTED           = "#849495";
                C_ON_PRIMARY_FIXED     = "#002022";
                break;
        }
    }

    public static Theme getCurrentTheme() {
        return currentTheme;
    }

    // Opacity helpers
    public static String rgba(String hex, double alpha) {
        // expects #RRGGBB
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
    }

    public static String appRoot() {
        return "-fx-background-color: " + C_SURFACE + ";" +
               "-fx-font-family: 'Space Grotesk','Segoe UI','Inter',sans-serif;";
    }

    public static String glassPanel(double opacity, int radiusPx) {
        return "-fx-background-color: " + rgba(C_SURFACE_LOWEST, opacity) + ";" +
               "-fx-background-radius: " + radiusPx + "px;" +
               "-fx-border-color: " + rgba(C_PRIMARY_GLASS, 0.10) + ";" +
               "-fx-border-radius: " + radiusPx + "px;" +
               "-fx-border-width: 1px;";
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

