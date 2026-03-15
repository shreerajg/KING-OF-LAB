package com.ghost.util;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Thread-safe audit logger for King of Lab.
 * Writes timestamped entries to king_audit.log in the working directory.
 *
 * Log events: LOGIN, CONNECT, DISCONNECT, AI_QUERY, COMMAND, ERROR, SYSTEM
 */
public class AuditLogger {

    private static final String LOG_FILE = "king_audit.log";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter writer;

    static {
        try {
            File f = new File(LOG_FILE);
            writer = new PrintWriter(new BufferedWriter(new FileWriter(f, true)));
            log("SYSTEM", "King of Lab v" + Config.APP_VER + " started");
        } catch (IOException e) {
            System.err.println("[AuditLogger] Cannot open log file: " + e.getMessage());
        }
    }

    /** Log a generic event with a category label. */
    public static synchronized void log(String category, String message) {
        if (writer == null) return;
        String line = "[" + LocalDateTime.now().format(FMT) + "] [" + category + "] " + message;
        writer.println(line);
        writer.flush();
        System.out.println(line); // also echo to console
    }

    public static void logLogin(String username, boolean success) {
        log("LOGIN", username + (success ? " logged in successfully" : " LOGIN FAILED"));
    }

    public static void logConnect(String clientName) {
        log("CONNECT", "Student connected: " + clientName);
    }

    public static void logDisconnect(String clientName) {
        log("DISCONNECT", "Student disconnected: " + clientName);
    }

    public static void logAiQuery(String studentName, String query) {
        // Truncate long queries for brevity
        String q = query.length() > 80 ? query.substring(0, 77) + "..." : query;
        log("AI_QUERY", studentName + " asked: " + q);
    }

    public static void logCommand(String admin, String target, String command) {
        log("COMMAND", admin + " → " + target + ": " + command);
    }

    public static void logError(String context, String error) {
        log("ERROR", "[" + context + "] " + error);
    }

    public static void logSystem(String message) {
        log("SYSTEM", message);
    }

    public static void close() {
        if (writer != null) {
            log("SYSTEM", "King of Lab shutdown");
            writer.close();
        }
    }
}
