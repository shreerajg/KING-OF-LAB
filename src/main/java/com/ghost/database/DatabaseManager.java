package com.ghost.database;

import com.ghost.util.AuditLogger;
import com.ghost.util.Config;
import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {

    // -----------------------------------------------------------------------
    // Password hashing
    // -----------------------------------------------------------------------

    /**
     * Hash a plain-text password with SHA-256.
     * Returns 64-char hex string.
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // -----------------------------------------------------------------------
    // Initialisation
    // -----------------------------------------------------------------------

    public static void init() {
        try (Connection conn = DriverManager.getConnection(Config.DB_URL)) {
            if (conn != null) {
                String sql = "CREATE TABLE IF NOT EXISTS users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "username TEXT UNIQUE NOT NULL, " +
                        "password TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "meta TEXT, " +
                        "roll_number INTEGER DEFAULT 0, " +
                        "class_name TEXT DEFAULT '', " +
                        "division TEXT DEFAULT ''" +
                        ");";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }

                migrateSchema(conn);
                createDefaultAdmin(conn);
                AuditLogger.logSystem("Database initialised");
            }
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.init", e.getMessage());
        }
    }

    private static void migrateSchema(Connection conn) {
        String[] alterStatements = {
                "ALTER TABLE users ADD COLUMN roll_number INTEGER DEFAULT 0",
                "ALTER TABLE users ADD COLUMN class_name TEXT DEFAULT ''",
                "ALTER TABLE users ADD COLUMN division TEXT DEFAULT ''"
        };
        try (Statement stmt = conn.createStatement()) {
            for (String sql : alterStatements) {
                try {
                    stmt.execute(sql);
                } catch (SQLException e) {
                    // Column already exists — expected on subsequent launches
                }
            }
            // --- Migrate plain-text admin password to SHA-256 on upgrade ---
            migratePasswords(conn);
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.migrateSchema", e.getMessage());
        }
    }

    /**
     * Detect and re-hash any password that isn't already a 64-char lowercase hex string
     * (i.e., was stored as plain text by the old Ghost system).
     */
    private static void migratePasswords(Connection conn) throws SQLException {
        String select = "SELECT id, password FROM users";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(select)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String pw = rs.getString("password");
                if (pw != null && !isHashed(pw)) {
                    String hashed = hashPassword(pw);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE users SET password = ? WHERE id = ?")) {
                        ps.setString(1, hashed);
                        ps.setInt(2, id);
                        ps.executeUpdate();
                        System.out.println("[DB] Migrated password for user id=" + id);
                    }
                }
            }
        }
    }

    /** A hashed password is exactly 64 lowercase hex characters. */
    private static boolean isHashed(String pw) {
        return pw != null && pw.matches("[0-9a-f]{64}");
    }

    private static void createDefaultAdmin(Connection conn) throws SQLException {
        String checkSql = "SELECT count(*) FROM users WHERE role = 'ADMIN'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            if (rs.next() && rs.getInt(1) == 0) {
                String hashed = hashPassword("admin123");
                String insertSql =
                        "INSERT INTO users(username, password, role, meta) VALUES(?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, "admin");
                    ps.setString(2, hashed);
                    ps.setString(3, "ADMIN");
                    ps.setString(4, "{}");
                    ps.executeUpdate();
                    System.out.println("[DB] Default admin created (password: admin123).");
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    public static User login(String username, String password) {
        String hashed = hashPassword(password);
        String sql = "SELECT * FROM users WHERE username = ? AND password = ?";
        try (Connection conn = DriverManager.getConnection(Config.DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username);
            pstmt.setString(2, hashed);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new User(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("password"),
                            rs.getString("role"),
                            rs.getString("meta"),
                            rs.getInt("roll_number"),
                            rs.getString("class_name"),
                            rs.getString("division"));
                }
            }
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.login", e.getMessage());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    public static boolean registerStudent(String username, String password, String meta,
                                          int rollNumber, String className, String division) {
        String hashed = hashPassword(password);
        String sql = "INSERT INTO users(username, password, role, meta, roll_number, class_name, division) " +
                     "VALUES(?, ?, 'STUDENT', ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(Config.DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username);
            pstmt.setString(2, hashed);
            pstmt.setString(3, meta);
            pstmt.setInt(4, rollNumber);
            pstmt.setString(5, className);
            pstmt.setString(6, division);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.registerStudent", e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Password change (admin utility)
    // -----------------------------------------------------------------------

    public static boolean changePassword(String username, String newPassword) {
        String hashed = hashPassword(newPassword);
        String sql = "UPDATE users SET password = ? WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(Config.DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hashed);
            ps.setString(2, username);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.changePassword", e.getMessage());
            return false;
        }
    }
}
