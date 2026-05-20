package com.king.ai;

import com.king.util.AuditLogger;
import com.king.util.AdaptiveStreamController;
import com.king.util.Config;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * King of Lab — AI Service  (Smart Router with Online Fallback Chain)
 *
 * ─────────────────────────────────────────────────────────────────
 * ROUTING STRATEGY
 *
 * ONLINE (internet reachable):
 *   Try each endpoint in order until one succeeds:
 *     1. Pollinations /openai  model=openai    (GPT-4o-mini class, POST)
 *     2. Pollinations /openai  model=mistral   (Mistral-7B class, POST)
 *     3. Pollinations /openai  model=llama     (Llama-3 class, POST)
 *     4. Pollinations GET      text/{prompt}   (simplest, most resilient)
 *
 *   If ALL four fail → surface a friendly error to the student.
 *   Ollama is NEVER started when internet is available.
 *
 * OFFLINE (internet unreachable — checked once per request):
 *   → Auto-install / start Ollama (winget, then "ollama serve")
 *   → Pull model if not present
 *   → POST to localhost:11434/api/chat with compact offline system prompt
 *
 * Per-student conversation history is maintained (rolling 40-message
 * window) and is shared across online/offline backends seamlessly.
 * ─────────────────────────────────────────────────────────────────
 */
public class OllamaService {

    // -----------------------------------------------------------------------
    // System prompts
    // -----------------------------------------------------------------------

    /** Full-featured system prompt for online (cloud) backends. */
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
     * Compact system prompt for offline Ollama path.
     * Kept short to suit small local models (7B parameters) on modest hardware.
     */
    private static final String SYSTEM_PROMPT_OFFLINE =
            "You are KING AI, an offline educational assistant running inside King of Lab. " +
            "The lab has no internet. GUIDE students with hints only — NEVER give full code. " +
            "Be concise and encouraging. Use bullet points. " +
            "Only answer programming, maths, and academic questions. " +
            "If asked anything else say: 'I can only help with academic topics right now.' " +
            "Remember prior messages in this conversation for context.";

    // -----------------------------------------------------------------------
    // Online fallback chain  (tried in order, Ollama never touched when online)
    // -----------------------------------------------------------------------

    /**
     * Each entry describes one attempt in the online fallback chain.
     * The chain is tried top-to-bottom; the first successful response wins.
     */
    private static final OnlineEndpoint[] ONLINE_CHAIN = {
        // 1. Pollinations — OpenAI-compatible POST, model = openai (GPT-4o-mini)
        new OnlineEndpoint("Pollinations/openai",
                           "https://text.pollinations.ai/openai",
                           "openai",
                           EndpointType.POLLINATIONS_POST),

        // 2. Pollinations — OpenAI-compatible POST, model = mistral (Mistral-7B)
        new OnlineEndpoint("Pollinations/mistral",
                           "https://text.pollinations.ai/openai",
                           "mistral",
                           EndpointType.POLLINATIONS_POST),

        // 3. Pollinations — OpenAI-compatible POST, model = llama (Llama-3)
        new OnlineEndpoint("Pollinations/llama",
                           "https://text.pollinations.ai/openai",
                           "llama",
                           EndpointType.POLLINATIONS_POST),

        // 4. Pollinations — Simple GET  (most resilient, no JSON body required)
        //    GET https://text.pollinations.ai/{url-encoded-prompt}?system=...
        new OnlineEndpoint("Pollinations/GET",
                           "https://text.pollinations.ai/",
                           null,
                           EndpointType.POLLINATIONS_GET),
    };

    private enum EndpointType { POLLINATIONS_POST, POLLINATIONS_GET }

    /** Descriptor for one online fallback endpoint. */
    private static class OnlineEndpoint {
        final String name;
        final String url;
        final String model;   // null for GET endpoint
        final EndpointType type;

        OnlineEndpoint(String name, String url, String model, EndpointType type) {
            this.name  = name;
            this.url   = url;
            this.model = model;
            this.type  = type;
        }
    }

    // -----------------------------------------------------------------------
    // Conversation history (per student)
    // -----------------------------------------------------------------------

    private static final Map<String, List<ChatMessage>> conversationHistories =
            new ConcurrentHashMap<>();

    /** Immutable message pair. */
    public static class ChatMessage {
        final String role;      // "user" | "assistant"
        final String content;

        ChatMessage(String role, String content) {
            this.role    = role;
            this.content = content;
        }
    }

    // -----------------------------------------------------------------------
    // Connectivity detection
    // -----------------------------------------------------------------------

    /**
     * Returns true if the internet is reachable (Pollinations host responds).
     * Short 3-second timeout so we don't stall the UI.
     */
    public static boolean isInternetAvailable() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://text.pollinations.ai/").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(3_000);
            c.setReadTimeout(3_000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true if Ollama is running on localhost. */
    public static boolean isOllamaAvailable() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(Config.OLLAMA_URL).openConnection();
            c.setConnectTimeout(2_000);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true if the configured Ollama model is already pulled. */
    public static boolean isModelAvailable() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(Config.OLLAMA_URL + "/api/tags").openConnection();
            c.setConnectTimeout(2_000);
            c.setRequestMethod("GET");
            if (c.getResponseCode() == 200) {
                StringBuilder raw = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) raw.append(line);
                }
                String s = raw.toString();
                return s.contains("\"name\":\"" + Config.AI_MODEL + "\"") ||
                       s.contains("\"name\":\"" + Config.AI_MODEL + ":latest\"");
            }
        } catch (Exception ignored) {}
        return false;
    }

    // -----------------------------------------------------------------------
    // History management
    // -----------------------------------------------------------------------

    public static void clearHistory(String studentName) {
        conversationHistories.remove(studentName);
        AuditLogger.logSystem("Cleared AI conversation history for: " + studentName);
    }

    public static void clearAllHistories() {
        conversationHistories.clear();
        AuditLogger.logSystem("Cleared all AI conversation histories");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Send a question to the best available AI backend asynchronously.
     *
     * Decision tree (evaluated once per call):
     *   internet reachable?
     *     YES → walk ONLINE_CHAIN (4 fallback steps, Pollinations only)
     *     NO  → start Ollama if needed → local inference
     *
     * Callback runs on a background thread — use Platform.runLater() for UI.
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
                List<ChatMessage> history = getHistory(studentName);
                history.add(new ChatMessage("user", question));

                String response;

                if (isInternetAvailable()) {
                    // ── ONLINE PATH — try each endpoint until one works ──────
                    response = tryOnlineChain(history, callback);
                } else {
                    // ── OFFLINE PATH — Ollama only ───────────────────────────
                    notify(callback, "[SYSTEM]: 📡 No internet — switching to offline KING AI...");
                    response = runOllamaOffline(history, callback);
                }

                history.add(new ChatMessage("assistant", response));
                trimHistory(history);

                if (callback != null) callback.accept(response);

            } catch (Exception e) {
                AuditLogger.logError("OllamaService", e.getMessage());
                if (callback != null)
                    callback.accept("❌ KING AI could not generate a response.\n" +
                            "• Check your network connection.\n" +
                            "• If offline, ensure Ollama is running: http://localhost:11434");
            } finally {
                AdaptiveStreamController.setAiProcessing(false);
            }
        }, "KingAI-Worker");

        aiThread.setDaemon(true);
        aiThread.setPriority(Thread.NORM_PRIORITY - 1);
        aiThread.start();
    }

    // -----------------------------------------------------------------------
    // Online fallback chain executor
    // -----------------------------------------------------------------------

    /**
     * Walks ONLINE_CHAIN trying each endpoint in turn.
     * Returns the first successful response.
     * If all endpoints fail, returns a user-friendly error message.
     * Ollama is NEVER started from this path.
     */
    private static String tryOnlineChain(List<ChatMessage> history,
                                         Consumer<String> callback) {
        for (int i = 0; i < ONLINE_CHAIN.length; i++) {
            OnlineEndpoint ep = ONLINE_CHAIN[i];
            try {
                notify(callback, "[SYSTEM]: 🌐 Connecting to " + ep.name + "...");
                String response;
                if (ep.type == EndpointType.POLLINATIONS_POST) {
                    response = callPollinationsPost(ep.url, ep.model, history);
                } else {
                    response = callPollinationsGet(history);
                }
                AuditLogger.logSystem("[KingAI] Online response from: " + ep.name);
                return response;

            } catch (Exception ex) {
                AuditLogger.logError("KingAI[" + ep.name + "]",
                        "Attempt " + (i + 1) + " failed: " + ex.getMessage());

                boolean isLast = (i == ONLINE_CHAIN.length - 1);
                if (!isLast) {
                    notify(callback, "[SYSTEM]: ⚠️ " + ep.name + " unavailable — trying next...");
                }
            }
        }

        // All online endpoints exhausted
        AuditLogger.logError("KingAI", "All online endpoints failed");
        return "⚠️ All online AI services are currently unavailable.\n" +
               "• Check your internet connection.\n" +
               "• The Pollinations service may be temporarily down.\n" +
               "• Your administrator can enable offline mode if Ollama is installed.";
    }

    // -----------------------------------------------------------------------
    // Offline Ollama executor
    // -----------------------------------------------------------------------

    /**
     * Ensures Ollama is running, then sends the request.
     * This is only called when isInternetAvailable() returns false.
     */
    private static String runOllamaOffline(List<ChatMessage> history,
                                           Consumer<String> callback) throws IOException {
        if (!isOllamaAvailable()) {
            ensureOllamaRunning(msg -> notify(callback, "[SYSTEM]: " + msg));
        }

        if (!isOllamaAvailable()) {
            throw new IOException("Ollama could not be started on this machine.");
        }

        return callOllamaChat(history);
    }

    // -----------------------------------------------------------------------
    // Pollinations POST  (OpenAI-compatible)
    // -----------------------------------------------------------------------

    private static String callPollinationsPost(String endpointUrl,
                                               String model,
                                               List<ChatMessage> history) throws IOException {
        URL url = new URL(endpointUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept",       "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(Config.AI_TIMEOUT_MS);

        // Build OpenAI-compatible body
        StringBuilder messages = new StringBuilder("[");
        messages.append("{\"role\":\"system\",\"content\":\"")
                .append(escape(SYSTEM_PROMPT_ONLINE)).append("\"}");
        for (ChatMessage msg : history) {
            messages.append(",{\"role\":\"").append(escape(msg.role))
                    .append("\",\"content\":\"").append(escape(msg.content)).append("\"}");
        }
        messages.append("]");

        String body = "{\"model\":\"" + escape(model) + "\"," +
                      "\"messages\":" + messages + "," +
                      "\"stream\":false}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            drainStream(conn.getErrorStream());
            throw new IOException("HTTP " + status);
        }

        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
        }

        // OpenAI response: {"choices":[{"message":{"content":"..."}}]}
        return extractOpenAiContent(raw.toString());
    }

    // -----------------------------------------------------------------------
    // Pollinations GET  (simplest, most resilient fallback)
    // -----------------------------------------------------------------------

    /**
     * GET https://text.pollinations.ai/{url-encoded-prompt}?system={system-prompt}
     * Returns plain text. No JSON body needed — most resilient to service issues.
     */
    private static String callPollinationsGet(List<ChatMessage> history) throws IOException {
        // Build a compact context string: last user message + brief prior context
        StringBuilder context = new StringBuilder();
        int start = Math.max(0, history.size() - 5);  // last 5 messages for context
        for (int i = start; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            context.append(m.role.toUpperCase()).append(": ").append(m.content).append("\n");
        }
        String prompt = context.toString().trim();
        if (prompt.isEmpty() && !history.isEmpty()) {
            prompt = history.get(history.size() - 1).content;
        }

        String encodedPrompt = URLEncoder.encode(prompt,      StandardCharsets.UTF_8.name());
        String encodedSystem = URLEncoder.encode(SYSTEM_PROMPT_ONLINE, StandardCharsets.UTF_8.name());

        String urlStr = "https://text.pollinations.ai/" + encodedPrompt +
                        "?model=openai&system=" + encodedSystem;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(Config.AI_TIMEOUT_MS);

        int status = conn.getResponseCode();
        if (status != 200) {
            drainStream(conn.getErrorStream());
            throw new IOException("HTTP " + status);
        }

        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) raw.append(line).append("\n");
        }

        String result = raw.toString().trim();
        if (result.isEmpty()) throw new IOException("Empty response from Pollinations GET");
        return result;
    }

    // -----------------------------------------------------------------------
    // Ollama /api/chat  (offline only)
    // -----------------------------------------------------------------------

    private static String callOllamaChat(List<ChatMessage> history) throws IOException {
        URL url = new URL(Config.OLLAMA_URL + "/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(Config.AI_TIMEOUT_MS);

        StringBuilder messages = new StringBuilder("[");
        messages.append("{\"role\":\"system\",\"content\":\"")
                .append(escape(SYSTEM_PROMPT_OFFLINE)).append("\"}");
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
        if (status != 200) throw new IOException("Ollama returned HTTP " + status);

        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
        }

        // Ollama: {"message":{"role":"assistant","content":"..."}}
        return extractNestedField(raw.toString(), "message", "content");
    }

    // -----------------------------------------------------------------------
    // Ollama lifecycle (offline fallback only)
    // -----------------------------------------------------------------------

    /**
     * Ensures Ollama is installed and running — ONLY called when internet is off.
     *  1. Running → pull model if needed → return.
     *  2. Installed but stopped → "ollama serve" → wait → pull if needed.
     *  3. Not installed → winget install → start → pull.
     */
    public static void ensureOllamaRunning(Consumer<String> statusCallback) {
        if (isOllamaAvailable()) {
            if (!isModelAvailable()) pullModel(statusCallback);
            return;
        }

        notify(statusCallback, "🔍 Ollama not detected — attempting to start...");

        // Try starting an existing install first
        try {
            new ProcessBuilder("cmd.exe", "/c", "start /b ollama serve")
                    .redirectErrorStream(true).start();
            Thread.sleep(4_000);
        } catch (Exception ignored) {}

        if (isOllamaAvailable()) {
            notify(statusCallback, "✅ Ollama started!");
            pullModel(statusCallback);
            return;
        }

        // Not installed — try winget
        notify(statusCallback, "📦 Installing Ollama via winget (one-time setup)...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd.exe", "/c",
                    "winget install Ollama.Ollama -e --accept-package-agreements " +
                    "--accept-source-agreements --silent");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String l = line.trim();
                    if (!l.isEmpty()) notify(statusCallback, "  " + l);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            notify(statusCallback,
                    "❌ winget install failed: " + e.getMessage() +
                    "\nInstall manually: https://ollama.com/download");
            return;
        }

        notify(statusCallback, "🚀 Starting Ollama...");
        try {
            new ProcessBuilder("cmd.exe", "/c", "start /b ollama serve")
                    .redirectErrorStream(true).start();
            Thread.sleep(5_000);
        } catch (Exception ignored) {}

        if (!isOllamaAvailable()) {
            notify(statusCallback,
                    "❌ Could not start Ollama after install. Please restart your PC.");
            return;
        }

        notify(statusCallback, "✅ Ollama is running! Pulling AI model...");
        pullModel(statusCallback);
    }

    private static void pullModel(Consumer<String> statusCallback) {
        notify(statusCallback, "⬇️ Downloading " + Config.AI_MODEL +
                " (first-time only)...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd.exe", "/c", "ollama pull " + Config.AI_MODEL);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String l = line.trim();
                    if (!l.isEmpty()) notify(statusCallback, "  " + l);
                }
            }
            p.waitFor();
            notify(statusCallback, "✅ Model ready!");
        } catch (Exception e) {
            notify(statusCallback, "⚠️ Could not pull model: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private static List<ChatMessage> getHistory(String studentName) {
        return conversationHistories.computeIfAbsent(studentName, k -> new ArrayList<>());
    }

    /** Rolling window — keep last 40 messages (20 exchanges). */
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
    // Minimal JSON parsing (stdlib only — no external deps)
    // -----------------------------------------------------------------------

    /** Parse OpenAI-style: {"choices":[{"message":{"content":"..."}}]} */
    private static String extractOpenAiContent(String json) {
        String choicesKey = "\"choices\":[";
        int ci = json.indexOf(choicesKey);
        if (ci >= 0) {
            String afterChoices = json.substring(ci + choicesKey.length());
            String msgKey = "\"message\":{";
            int mi = afterChoices.indexOf(msgKey);
            if (mi >= 0) {
                String insideMsg = afterChoices.substring(mi + msgKey.length() - 1);
                return extractJsonField(insideMsg, "content");
            }
        }
        return extractJsonField(json, "content");
    }

    /** Parse nested: {"outerKey":{"innerKey":"value"}} */
    private static String extractNestedField(String json, String outerKey, String innerKey) {
        String search = "\"" + outerKey + "\":{";
        int start = json.indexOf(search);
        if (start < 0) return extractJsonField(json, innerKey);
        return extractJsonField(json.substring(start + search.length() - 1), innerKey);
    }

    private static String extractJsonField(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return "(no response — check AI service)";
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

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // -----------------------------------------------------------------------
    // Legacy compatibility shims
    // -----------------------------------------------------------------------

    /** @deprecated Use {@link #isOllamaAvailable()} */
    @Deprecated
    public static boolean isAvailable() { return isOllamaAvailable(); }
}
