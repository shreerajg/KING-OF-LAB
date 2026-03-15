package com.ghost.util;

public class Config {
    public static final String APP_NAME   = "King of Lab";
    public static final String APP_VER    = "3.0";
    public static final String DB_URL     = "jdbc:sqlite:ghost.db";
    public static final int    SERVER_PORT = 5555;

    // ===== AI CONFIGURATION =====
    // Admin can toggle this at runtime – volatile for cross-thread visibility
    public static volatile boolean aiEnabled = false;
    public static final String OLLAMA_URL  = "http://localhost:11434";
    public static final String AI_MODEL    = "qwen2.5:7b"; // change to any installed Ollama model
    public static final int    AI_TIMEOUT_MS = 60_000;      // 60-second timeout for AI response

    // ===== STREAMING CONFIGURATION =====
    public static final int    FPS_HIGH    = 35;   // low CPU load
    public static final int    FPS_MEDIUM  = 25;   // medium load
    public static final int    FPS_LOW     = 15;   // high load
    public static final int    FPS_AI_MODE = 10;   // while AI is active

    // ===== HEARTBEAT =====
    public static final int    HEARTBEAT_INTERVAL_SEC = 3;
    public static final int    HEARTBEAT_TIMEOUT_SEC  = 12;
}
