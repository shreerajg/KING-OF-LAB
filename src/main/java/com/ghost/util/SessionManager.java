package com.ghost.util;

import com.google.gson.Gson;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SessionManager {
    private static final String SESSION_FILE = "session.json";
    private static final Gson gson = new Gson();

    public static class SessionData {
        public String u; // username
        public String p; // password (base64)
    }

    public static void saveSession(String username, String password) {
        try (FileWriter writer = new FileWriter(SESSION_FILE)) {
            SessionData data = new SessionData();
            data.u = username;
            data.p = Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
            gson.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static SessionData loadSession() {
        File f = new File(SESSION_FILE);
        if (!f.exists())
            return null;

        try (FileReader reader = new FileReader(f)) {
            SessionData data = gson.fromJson(reader, SessionData.class);
            if (data != null && data.p != null) {
                // Decode password
                byte[] decoded = Base64.getDecoder().decode(data.p);
                data.p = new String(decoded, StandardCharsets.UTF_8);
            }
            return data;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void clearSession() {
        File f = new File(SESSION_FILE);
        if (f.exists()) {
            f.delete();
        }
    }
}
