package com.ghost.ai;

import com.ghost.util.AuditLogger;
import com.ghost.util.AdaptiveStreamController;
import com.ghost.util.Config;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Pure-Java Ollama AI service for King of Lab.
 *
 * Calls the local Ollama REST API: POST http://localhost:11434/api/generate
 * Uses a strict educational system prompt that enforces hint-only responses.
 * Runs requests asynchronously — never blocks the UI thread.
 */
public class OllamaService {

    private static final String SYSTEM_PROMPT =
            "You are KING AI, an educational programming assistant for a computer lab. " +
            "Your ONLY role is to GUIDE students using hints, explanations, and step-by-step reasoning. " +
            "YOU MUST NEVER provide complete code solutions, full programs, or copy-paste answers. " +
            "If a student asks for full code, politely decline and instead: " +
            "(1) Explain the concept, (2) Give a logical hint, (3) Ask guiding questions. " +
            "Keep replies short, encouraging, and educational. " +
            "If a question is off-topic (not programming/math/science), say: " +
            "'I can only help with programming and technical questions. Ask your instructor for other topics.'";

    /**
     * Check if Ollama is running on localhost.
     * Returns true if the service is reachable.
     */
    public static boolean isAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(Config.OLLAMA_URL).openConnection();
            conn.setConnectTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send a question to Ollama and receive the response asynchronously.
     * The callback is called on a background thread — caller must marshal to UI thread if needed.
     *
     * @param studentName  used for audit logging
     * @param question     the student's question
     * @param callback     called with the AI response string (or error message)
     */
    public static void askAsync(String studentName, String question, Consumer<String> callback) {
        if (!Config.aiEnabled) {
            if (callback != null) {
                callback.accept("⚠️ AI assistance is currently disabled by the administrator.");
            }
            return;
        }

        // Log the query
        AuditLogger.logAiQuery(studentName, question);

        // Signal to throttle streaming while AI runs
        AdaptiveStreamController.setAiProcessing(true);

        Thread aiThread = new Thread(() -> {
            try {
                String response = callOllama(question);
                if (callback != null) callback.accept(response);
            } catch (Exception e) {
                AuditLogger.logError("OllamaService", e.getMessage());
                if (callback != null) {
                    callback.accept("❌ Could not reach KING AI. Make sure Ollama is running.\n" +
                            "Run: ollama serve   (and ensure model '" + Config.AI_MODEL + "' is pulled)");
                }
            } finally {
                AdaptiveStreamController.setAiProcessing(false);
            }
        }, "KingAI-Worker");
        aiThread.setDaemon(true);
        aiThread.setPriority(Thread.NORM_PRIORITY - 1);
        aiThread.start();
    }

    /**
     * Synchronous call to Ollama /api/generate endpoint.
     * Uses streaming=false for simplicity (waits for full response).
     */
    private static String callOllama(String userQuestion) throws IOException {
        URL url = new URL(Config.OLLAMA_URL + "/api/generate");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(Config.AI_TIMEOUT_MS);

        // Build JSON body — manual to avoid adding a new dependency
        String body = "{"
                + "\"model\": \"" + escape(Config.AI_MODEL) + "\","
                + "\"system\": \"" + escape(SYSTEM_PROMPT) + "\","
                + "\"prompt\": \"" + escape(userQuestion) + "\","
                + "\"stream\": false"
                + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("Ollama returned HTTP " + status);
        }

        // Parse the "response" field from Ollama JSON manually
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line);
            }
        }

        return extractJsonField(raw.toString(), "response");
    }

    // -------------------------------------------------------------------------
    // Minimal JSON helpers (avoid adding Gson dependency to this class)
    // -------------------------------------------------------------------------

    /** Escape a string for embedding in a JSON literal. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Extracts the value of a top-level string field from a flat JSON object.
     * Simple implementation — sufficient for Ollama's response format.
     */
    private static String extractJsonField(String json, String key) {
        // Look for "key":"..." pattern
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            // Try without quotes (number/bool field — shouldn't hit for "response")
            searchKey = "\"" + key + "\":";
            start = json.indexOf(searchKey);
            if (start < 0) return "(no response from model)";
            int valStart = start + searchKey.length();
            int end = json.indexOf(",", valStart);
            if (end < 0) end = json.indexOf("}", valStart);
            return end > valStart ? json.substring(valStart, end).trim() : "(parse error)";
        }
        int valStart = start + searchKey.length();
        // Walk forward to find the unescaped closing quote
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = valStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default:  sb.append(c);    break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break; // end of string
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
