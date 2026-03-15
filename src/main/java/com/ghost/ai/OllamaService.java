package com.ghost.ai;

import com.ghost.util.AuditLogger;
import com.ghost.util.AdaptiveStreamController;
import com.ghost.util.Config;

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
 * King of Lab — Ollama AI Service with CONVERSATION MEMORY.
 *
 * Uses /api/chat endpoint which supports multi-turn conversation history.
 * Each student session gets its own conversation history so context is preserved.
 *
 * "Yes means yes" — the AI remembers what question it asked you and responds in context.
 */
public class OllamaService {

    private static final String SYSTEM_PROMPT =
            "You are KING AI, an educational programming assistant embedded in a computer lab system called 'King of Lab'. " +
            "Your ONLY role is to GUIDE students using hints, explanations, and step-by-step reasoning. " +
            "YOU MUST NEVER provide complete code solutions, full programs, or copy-paste answers. " +
            "If a student asks for full code, politely decline and instead: " +
            "(1) Explain the concept clearly, (2) Give a logical hint or partial example, (3) Ask a guiding question to help them think. " +
            "MAINTAIN CONVERSATION CONTEXT — always remember what was previously discussed in this session. " +
            "Keep replies concise, encouraging, and educational. Use bullet points for clarity. " +
            "If a question is off-topic (not programming/math/science/academics), say: " +
            "'I can only help with programming and academic questions — ask your instructor for other topics.' " +
            "Address the student warmly and stay positive.";

    /**
     * Per-student conversation history.
     * Key = studentName (username), Value = list of {role, content} messages.
     */
    private static final Map<String, List<ChatMessage>> conversationHistories = new ConcurrentHashMap<>();

    /** Simple message holder. */
    public static class ChatMessage {
        final String role;    // "user" or "assistant" or "system"
        final String content;
        ChatMessage(String role, String content) {
            this.role    = role;
            this.content = content;
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Check if Ollama is running on localhost.
     */
    public static boolean isAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(Config.OLLAMA_URL).openConnection();
            conn.setConnectTimeout(2000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clear conversation history for a student (e.g., on new session).
     */
    public static void clearHistory(String studentName) {
        conversationHistories.remove(studentName);
        AuditLogger.logSystem("Cleared AI conversation history for: " + studentName);
    }

    /**
     * Clear all conversation histories (e.g., when admin ends session).
     */
    public static void clearAllHistories() {
        conversationHistories.clear();
        AuditLogger.logSystem("Cleared all AI conversation histories");
    }

    /**
     * Send a question to Ollama and receive the response asynchronously.
     * Conversation history is maintained per-student across multiple calls.
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
                // Get or create conversation history for this student
                List<ChatMessage> history = conversationHistories
                        .computeIfAbsent(studentName, k -> new ArrayList<>());

                // Add the user's message
                history.add(new ChatMessage("user", question));

                // Call Ollama /api/chat with full history
                String response = callOllamaChat(history);

                // Add assistant's reply to history for context on next turn
                history.add(new ChatMessage("assistant", response));

                // Keep history bounded to last 20 exchanges (40 messages) to avoid huge payloads
                while (history.size() > 40) {
                    history.remove(0);
                }

                if (callback != null) callback.accept(response);

            } catch (Exception e) {
                AuditLogger.logError("OllamaService", e.getMessage());
                if (callback != null)
                    callback.accept("❌ Could not reach KING AI. Make sure Ollama is running.\n" +
                            "• Run: ollama serve\n" +
                            "• Check model is pulled: ollama pull " + Config.AI_MODEL);
            } finally {
                AdaptiveStreamController.setAiProcessing(false);
            }
        }, "KingAI-Worker");
        aiThread.setDaemon(true);
        aiThread.setPriority(Thread.NORM_PRIORITY - 1);
        aiThread.start();
    }

    // -----------------------------------------------------------------------
    // Ollama /api/chat call (supports conversation history)
    // -----------------------------------------------------------------------

    private static String callOllamaChat(List<ChatMessage> history) throws IOException {
        URL url = new URL(Config.OLLAMA_URL + "/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(Config.AI_TIMEOUT_MS);

        // Build messages JSON array manually
        StringBuilder messages = new StringBuilder("[");
        // Always prepend system prompt
        messages.append("{\"role\":\"system\",\"content\":\"").append(escape(SYSTEM_PROMPT)).append("\"}");
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

        // Parse response: {"message":{"role":"assistant","content":"..."}}
        return extractNestedField(raw.toString(), "message", "content");
    }

    // -----------------------------------------------------------------------
    // Minimal JSON parsing helpers
    // -----------------------------------------------------------------------

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Extracts a nested string value: given JSON with {"outerKey":{..., "innerKey":"value"...}}
     * returns the value of innerKey inside outerKey's object.
     */
    private static String extractNestedField(String json, String outerKey, String innerKey) {
        // Find the outer object
        String search = "\"" + outerKey + "\":{";
        int outerStart = json.indexOf(search);
        if (outerStart < 0) return extractJsonField(json, innerKey); // fallback
        // Extract the inner JSON from that point
        String inner = json.substring(outerStart + search.length() - 1);
        return extractJsonField(inner, innerKey);
    }

    private static String extractJsonField(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return "(no response from AI model — is it downloaded?)";
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
}
