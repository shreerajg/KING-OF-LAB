package com.king.ai;

import com.king.util.AuditLogger;
import com.king.util.AdaptiveStreamController;
import com.king.util.Config;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * King of Lab — AI Service (Smart Router)
 *
 * ─────────────────────────────────────────────────────────────────
 * ROUTING LOGIC:
 *   ONLINE  → Pollinations AI  (https://text.pollinations.ai/openai)
 *             Free, no API key required. Uses OpenAI-compatible REST.
 *             Pure java.net.HttpURLConnection — zero extra dependencies.
 *
 *   OFFLINE → Ollama local      (http://localhost:11434/api/chat)
 *             Auto-installs/starts Ollama via winget if not present.
 *             Custom "lab-offline" system prompt for contextual guidance.
 *
 * Both paths share per-student conversation history (20-exchange rolling
 * window) so context is never lost when switching between modes.
 * ─────────────────────────────────────────────────────────────────
 */
public class OllamaService {

    // -----------------------------------------------------------------------
    // System prompts
    // -----------------------------------------------------------------------

    /** System prompt used when routing through Pollinations AI (online). */
    private static final String SYSTEM_PROMPT_ONLINE =
            "You are KING AI, an educational programming assistant embedded in a computer lab " +
            "management system called 'King of Lab'. " +
            "Your ONLY role is to GUIDE students using hints, explanations, and step-by-step reasoning. " +
            "YOU MUST NEVER provide complete code solutions, full programs, or copy-paste answers. " +
            "If a student asks for full code, politely decline and instead: " +
            "(1) Explain the concept clearly, (2) Give a logical hint or partial example, " +
            "(3) Ask a guiding question to help them think. " +
            "MAINTAIN CONVERSATION CONTEXT — always remember what was discussed in this session. " +
            "Keep replies concise, encouraging, and educational. Use bullet points for clarity. " +
            "If a question is off-topic (not programming / math / science / academics), say: " +
            "'I can only help with programming and academic questions — ask your instructor for other topics.' " +
            "Address the student warmly and stay positive.";

    /**
     * System prompt used when Pollinations AI is unreachable (offline / no internet).
     * More compact to suit smaller local models and slower inference hardware.
     */
    private static final String SYSTEM_PROMPT_OFFLINE =
            "You are KING AI, an offline educational assistant running inside King of Lab. " +
            "The lab has no internet right now, so you are the student's only AI resource. " +
            "GUIDE students with hints and reasoning — NEVER give complete code answers. " +
            "Be concise and encouraging. Use bullet points. " +
            "Stick to programming, maths, and academic questions only. " +
            "If asked anything else, say: 'I can only help with academic topics right now.' " +
            "Remember prior messages in this conversation for context.";

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    /** Pollinations AI — OpenAI-compatible, free, no key required. */
    private static final String POLLINATIONS_URL = "https://text.pollinations.ai/openai";

    /** Default model name to request from Pollinations. */
    private static final String POLLINATIONS_MODEL = "openai";   // routes to GPT-4o-mini on their backend

    // -----------------------------------------------------------------------
    // Conversation history (per student)
    // -----------------------------------------------------------------------

    /**
     * Per-student conversation history.
     * Key = studentName, Value = list of {role, content} messages (excluding system).
     */
    private static final Map<String, List<ChatMessage>> conversationHistories =
            new ConcurrentHashMap<>();

    /** Simple immutable message holder. */
    public static class ChatMessage {
        final String role;    // "user" | "assistant"
        final String content;

        ChatMessage(String role, String content) {
            this.role    = role;
            this.content = content;
        }
    }

    // -----------------------------------------------------------------------
    // Connectivity helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true if Pollinations AI is reachable (internet is online).
     * Uses a lightweight HEAD-style GET with a short 3-second timeout.
     */
    public static boolean isInternetAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://text.pollinations.ai/").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(3_000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;  // any non-connection-error = internet present
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if Ollama is running on localhost.
     */
    public static boolean isOllamaAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(Config.OLLAMA_URL).openConnection();
            conn.setConnectTimeout(2_000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if the configured Ollama model is pulled and ready.
     */
    public static boolean isModelAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(Config.OLLAMA_URL + "/api/tags").openConnection();
            conn.setConnectTimeout(2_000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                StringBuilder raw = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) raw.append(line);
                }
                return raw.toString().contains("\"name\":\"" + Config.AI_MODEL + "\"") ||
                       raw.toString().contains("\"name\":\"" + Config.AI_MODEL + ":latest\"");
            }
        } catch (Exception ignored) {}
        return false;
    }

    // -----------------------------------------------------------------------
    // History management
    // -----------------------------------------------------------------------

    /** Clear conversation history for one student (e.g., on new session). */
    public static void clearHistory(String studentName) {
        conversationHistories.remove(studentName);
        AuditLogger.logSystem("Cleared AI conversation history for: " + studentName);
    }

    /** Clear all conversation histories (e.g., when admin ends session). */
    public static void clearAllHistories() {
        conversationHistories.clear();
        AuditLogger.logSystem("Cleared all AI conversation histories");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Send a question to the best available AI and receive the response asynchronously.
     *
     * Priority:
     *   1. If internet is available  → Pollinations AI (cloud, GPT-class model)
     *   2. If internet is unavailable → Ollama (local, auto-installed if needed)
     *
     * Callback runs on a background thread — use Platform.runLater() for UI updates.
     */
    public static void askAsync(String studentName, String question, Consumer<String> callback) {
        if (!Config.aiEnabled) {
            if (callback != null)
                callback.accept("⚠️ AI assistance is currently disabled by the administrator.\n" +
                        "Please ask your instructor for help.");
            return;
        }

        AuditLogger.logAiQuery(studentName, question);
        AdaptiveStreamController.setAiProcessing(true);

        Thread aiThread = new Thread(() -> {
            try {
                String response;

                if (isInternetAvailable()) {
                    // ── ONLINE PATH ── Pollinations AI ────────────────────────
                    AuditLogger.logSystem("[KingAI] Online — routing to Pollinations AI");
                    if (callback != null) callback.accept("[SYSTEM]: 🌐 Connected to Pollinations AI...");

                    List<ChatMessage> history = getHistory(studentName);
                    history.add(new ChatMessage("user", question));

                    response = callPollinationsChat(history);

                    history.add(new ChatMessage("assistant", response));
                    trimHistory(history);

                } else {
                    // ── OFFLINE PATH ── Ollama ─────────────────────────────────
                    AuditLogger.logSystem("[KingAI] Offline — routing to Ollama local");
                    if (callback != null) callback.accept("[SYSTEM]: 📡 No internet — switching to offline AI...");

                    if (!isOllamaAvailable()) {
                        ensureOllamaRunning(msg -> {
                            if (callback != null) callback.accept("[SYSTEM]: " + msg);
                        });
                    }

                    List<ChatMessage> history = getHistory(studentName);
                    history.add(new ChatMessage("user", question));

                    response = callOllamaChat(history, SYSTEM_PROMPT_OFFLINE);

                    history.add(new ChatMessage("assistant", response));
                    trimHistory(history);
                }

                if (callback != null) callback.accept(response);

            } catch (Exception e) {
                AuditLogger.logError("OllamaService", e.getMessage());
                if (callback != null)
                    callback.accept("❌ KING AI could not generate a response.\n" +
                            "• Check your network connection.\n" +
                            "• If offline, ensure Ollama is installed: https://ollama.com/download\n" +
                            "• Model: " + Config.AI_MODEL);
            } finally {
                AdaptiveStreamController.setAiProcessing(false);
            }
        }, "KingAI-Worker");

        aiThread.setDaemon(true);
        aiThread.setPriority(Thread.NORM_PRIORITY - 1);
        aiThread.start();
    }

    // -----------------------------------------------------------------------
    // Pollinations AI call (OpenAI-compatible)
    // -----------------------------------------------------------------------

    /**
     * POST to https://text.pollinations.ai/openai with an OpenAI-compatible
     * messages payload. No API key required.
     */
    private static String callPollinationsChat(List<ChatMessage> history) throws IOException {
        URL url = new URL(POLLINATIONS_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept",       "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(Config.AI_TIMEOUT_MS);

        String body = buildOpenAiBody(POLLINATIONS_MODEL, SYSTEM_PROMPT_ONLINE, history);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            // Drain error stream so the connection can be reused
            drainStream(conn.getErrorStream());
            throw new IOException("Pollinations returned HTTP " + status);
        }

        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
        }

        // OpenAI response: {"choices":[{"message":{"role":"assistant","content":"..."}}]}
        return extractOpenAiContent(raw.toString());
    }

    // -----------------------------------------------------------------------
    // Ollama /api/chat call
    // -----------------------------------------------------------------------

    /**
     * POST to Ollama's /api/chat endpoint with the given history and system prompt.
     */
    private static String callOllamaChat(List<ChatMessage> history,
                                         String systemPrompt) throws IOException {
        URL url = new URL(Config.OLLAMA_URL + "/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(Config.AI_TIMEOUT_MS);

        // Build messages JSON: system first, then history
        StringBuilder messages = new StringBuilder("[");
        messages.append("{\"role\":\"system\",\"content\":\"").append(escape(systemPrompt)).append("\"}");
        for (ChatMessage msg : history) {
            messages.append(",{\"role\":\"").append(escape(msg.role))
                    .append("\",\"content\":\"").append(escape(msg.content)).append("\"}");
        }
        messages.append("]");

        String body = "{\"model\":\"" + escape(Config.AI_MODEL) + "\"," +
                "\"messages\":" + messages + "," +
                "\"stream\":false}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("Ollama returned HTTP " + status);
        }

        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
        }

        // Ollama response: {"message":{"role":"assistant","content":"..."}}
        return extractNestedField(raw.toString(), "message", "content");
    }

    // -----------------------------------------------------------------------
    // JSON builders
    // -----------------------------------------------------------------------

    /** Builds an OpenAI-compatible request body (works with Pollinations & other OAI proxies). */
    private static String buildOpenAiBody(String model,
                                          String systemPrompt,
                                          List<ChatMessage> history) {
        StringBuilder messages = new StringBuilder("[");
        // System message first
        messages.append("{\"role\":\"system\",\"content\":\"")
                .append(escape(systemPrompt)).append("\"}");
        for (ChatMessage msg : history) {
            messages.append(",{\"role\":\"").append(escape(msg.role))
                    .append("\",\"content\":\"").append(escape(msg.content)).append("\"}");
        }
        messages.append("]");

        return "{\"model\":\"" + escape(model) + "\"," +
               "\"messages\":" + messages + "," +
               "\"stream\":false}";
    }

    // -----------------------------------------------------------------------
    // Ollama lifecycle management (offline fallback)
    // -----------------------------------------------------------------------

    /**
     * Ensures Ollama is installed and running on this machine.
     *  1. Already running → return immediately.
     *  2. Installed but stopped → launch "ollama serve".
     *  3. Not installed → install via winget, then start.
     *  4. Pull model if not already downloaded.
     */
    public static void ensureOllamaRunning(Consumer<String> statusCallback) {
        if (isOllamaAvailable()) {
            if (!isModelAvailable()) pullModel(statusCallback);
            return;
        }

        notify(statusCallback, "🔍 Ollama not detected — attempting to start...");

        // Step 1: Try starting an existing install
        try {
            new ProcessBuilder("cmd.exe", "/c", "start /b ollama serve")
                    .redirectErrorStream(true).start();
            Thread.sleep(4_000);
        } catch (Exception ignored) {}

        if (isOllamaAvailable()) {
            notify(statusCallback, "✅ Ollama started! Loading model...");
            pullModel(statusCallback);
            return;
        }

        // Step 2: Install via winget
        notify(statusCallback, "📦 Installing Ollama via winget (one-time setup)...");
        try {
            ProcessBuilder installPb = new ProcessBuilder(
                    "cmd.exe", "/c",
                    "winget install Ollama.Ollama -e --accept-package-agreements " +
                    "--accept-source-agreements --silent");
            installPb.redirectErrorStream(true);
            Process p = installPb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    final String l = line.trim();
                    if (!l.isEmpty()) notify(statusCallback, "  " + l);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            notify(statusCallback, "❌ winget install failed: " + e.getMessage() +
                    "\nPlease install Ollama manually from https://ollama.com/download");
            return;
        }

        // Step 3: Start freshly installed Ollama
        notify(statusCallback, "🚀 Starting Ollama service...");
        try {
            new ProcessBuilder("cmd.exe", "/c", "start /b ollama serve")
                    .redirectErrorStream(true).start();
            Thread.sleep(5_000);
        } catch (Exception ignored) {}

        if (!isOllamaAvailable()) {
            notify(statusCallback, "❌ Could not start Ollama after install. Please restart your PC and try again.");
            return;
        }

        notify(statusCallback, "✅ Ollama is running! Pulling AI model...");
        pullModel(statusCallback);
    }

    /** Pulls the configured AI model if not already available. */
    private static void pullModel(Consumer<String> statusCallback) {
        notify(statusCallback, "⬇️ Downloading model: " + Config.AI_MODEL +
                " (first-time only, may take a few minutes)...");
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                    "ollama pull " + Config.AI_MODEL);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    final String l = line.trim();
                    if (!l.isEmpty()) notify(statusCallback, "  " + l);
                }
            }
            p.waitFor();
            notify(statusCallback, "✅ Model ready! Sending your question now...");
        } catch (Exception e) {
            notify(statusCallback, "⚠️ Could not pull model: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    private static List<ChatMessage> getHistory(String studentName) {
        return conversationHistories.computeIfAbsent(studentName, k -> new ArrayList<>());
    }

    /** Keep rolling window to last 20 exchanges (40 messages). */
    private static void trimHistory(List<ChatMessage> history) {
        while (history.size() > 40) history.remove(0);
    }

    private static void notify(Consumer<String> cb, String msg) {
        if (cb != null) cb.accept(msg);
    }

    private static void drainStream(InputStream is) {
        if (is == null) return;
        try { is.transferTo(OutputStream.nullOutputStream()); } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Minimal JSON parsing helpers (no external libraries)
    // -----------------------------------------------------------------------

    /**
     * Extract content from OpenAI-style response:
     *   {"choices":[{"message":{"role":"assistant","content":"..."}},...]}
     */
    private static String extractOpenAiContent(String json) {
        // choices[0].message.content
        String choicesKey = "\"choices\":[";
        int ci = json.indexOf(choicesKey);
        if (ci >= 0) {
            String afterChoices = json.substring(ci + choicesKey.length());
            // Inside the first choice object, find message.content
            String msgKey = "\"message\":{";
            int mi = afterChoices.indexOf(msgKey);
            if (mi >= 0) {
                String insideMsg = afterChoices.substring(mi + msgKey.length() - 1);
                return extractJsonField(insideMsg, "content");
            }
        }
        // Fallback: try direct content field
        return extractJsonField(json, "content");
    }

    /**
     * Extracts a nested string value: {"outerKey":{..., "innerKey":"value"...}}
     */
    private static String extractNestedField(String json, String outerKey, String innerKey) {
        String search = "\"" + outerKey + "\":{";
        int outerStart = json.indexOf(search);
        if (outerStart < 0) return extractJsonField(json, innerKey);
        String inner = json.substring(outerStart + search.length() - 1);
        return extractJsonField(inner, innerKey);
    }

    private static String extractJsonField(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return "(no response from AI — check connection)";
        int valStart = start + searchKey.length();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = valStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(c);    break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** JSON-safe string escaping. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // -----------------------------------------------------------------------
    // Legacy compatibility shim — isAvailable() kept for any callers
    // -----------------------------------------------------------------------

    /** @deprecated Use {@link #isOllamaAvailable()} directly. */
    @Deprecated
    public static boolean isAvailable() {
        return isOllamaAvailable();
    }
}
