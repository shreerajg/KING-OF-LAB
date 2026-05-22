package com.king.database;

import com.king.util.AuditLogger;
import com.king.util.Config;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class DatabaseManager {

    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    // -----------------------------------------------------------------------
    // Password hashing
    // -----------------------------------------------------------------------

    /**
     * Hash a plain-text password with PBKDF2 and a salt.
     * Returns Base64 encoded string.
     */
    public static String hashPassword(String password, String salt) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    /**
     * Legacy SHA-256 hash for migration.
     */
    private static String legacyHash(String password) {
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

    /**
     * Generates a new random salt.
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
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
                        "salt TEXT, " +
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
                "ALTER TABLE users ADD COLUMN salt TEXT",
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
            // --- Migrate old passwords to salted PBKDF2 ---
            migrateToSaltedHashes(conn);
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.migrateSchema", e.getMessage());
        }
    }

    /**
     * Migrates passwords from plain text or legacy SHA-256 to salted PBKDF2.
     */
    private static void migrateToSaltedHashes(Connection conn) throws SQLException {
        String select = "SELECT id, username, password, salt FROM users";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(select)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String pw = rs.getString("password");
                String salt = rs.getString("salt");

                // If salt is missing, it's an old entry (either plain text or SHA-256)
                if (salt == null || salt.isEmpty()) {
                    String newSalt = generateSalt();
                    String newHash;

                    // If it looks like a legacy SHA-256 hash, we can't recover the plain text,
                    // but we can't just hash the hash without breaking login.
                    // Actually, if we want to move to PBKDF2, we MUST have the plain text.
                    // If we only have the SHA-256 hash, we can treat the hash as the password
                    // for PBKDF2, but that's slightly messy.
                    // Better approach: If it's a legacy hash, we re-hash the SHA-256 hash with PBKDF2.
                    // When the user logs in, we first SHA-256 their input, then PBKDF2 it.
                    // FOR SIMPLICITY: Since this is a lab management system, we might just
                    // force a password reset or, if it's plain text (not 64-char hex), hash it directly.

                    if (isLegacyHash(pw)) {
                        // It's a legacy SHA-256 hash.
                        // We'll mark these for re-hashing on NEXT successful login,
                        // or just wrap it. Let's wrap it for now.
                        newHash = hashPassword(pw, newSalt);
                    } else {
                        // It's plain text.
                        newHash = hashPassword(pw, newSalt);
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE users SET password = ?, salt = ? WHERE id = ?")) {
                        ps.setString(1, newHash);
                        ps.setString(2, newSalt);
                        ps.setInt(3, id);
                        ps.executeUpdate();
                        System.out.println("[DB] Migrated password to salted PBKDF2 for user: " + rs.getString("username"));
                    }
                }
            }
        }
    }

    private static boolean isLegacyHash(String pw) {
        return pw != null && pw.matches("[0-9a-f]{64}");
    }

    private static void createDefaultAdmin(Connection conn) throws SQLException {
        String checkSql = "SELECT count(*) FROM users WHERE role = 'ADMIN'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            if (rs.next() && rs.getInt(1) == 0) {
                String salt = generateSalt();
                String hashed = hashPassword("admin123", salt);
                String insertSql =
                        "INSERT INTO users(username, password, salt, role, meta) VALUES(?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, "admin");
                    ps.setString(2, hashed);
                    ps.setString(3, salt);
                    ps.setString(4, "ADMIN");
                    ps.setString(5, "{}");
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
        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(Config.DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password");
                    String salt = rs.getString("salt");

                    if (salt == null || salt.isEmpty()) {
                        // Handle legacy unsalted login attempt
                        String legacyHashed = legacyHash(password);
                        if (legacyHashed.equals(storedHash)) {
                            // Successful login with legacy hash, let's upgrade it now!
                            String newSalt = generateSalt();
                            String newHash = hashPassword(password, newSalt);
                            updateUserPassword(username, newHash, newSalt);
                            return createUserFromResultSet(rs);
                        }
                        // Try plain text legacy
                        if (password.equals(storedHash)) {
                            String newSalt = generateSalt();
                            String newHash = hashPassword(password, newSalt);
                            updateUserPassword(username, newHash, newSalt);
                            return createUserFromResultSet(rs);
                        }
                    } else {
                        // Normal salted PBKDF2 check
                        String challengeHash = hashPassword(password, salt);
                        if (challengeHash.equals(storedHash)) {
                            return createUserFromResultSet(rs);
                        }

                        // Fallback: maybe it was a double-hashed legacy?
                        // (During migration, we might have hashed the legacy SHA-256 hash)
                        String legacyHashed = legacyHash(password);
                        String challengeLegacyHash = hashPassword(legacyHashed, salt);
                        if (challengeLegacyHash.equals(storedHash)) {
                            // Upgrade from double-hash to direct PBKDF2
                            String newSalt = generateSalt();
                            String newHash = hashPassword(password, newSalt);
                            updateUserPassword(username, newHash, newSalt);
                            return createUserFromResultSet(rs);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.login", e.getMessage());
        }
        return null;
    }

    private static void updateUserPassword(String username, String hash, String salt) throws SQLException {
        String sql = "UPDATE users SET password = ?, salt = ? WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(Config.DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.setString(3, username);
            ps.executeUpdate();
            System.out.println("[DB] Upgraded password for user: " + username);
        }
    }

    private static User createUserFromResultSet(ResultSet rs) throws SQLException {
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

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    public static boolean registerStudent(String username, String password, String meta,
                                          int rollNumber, String className, String division) {
        String salt = generateSalt();
        String hashed = hashPassword(password, salt);
        String sql = "INSERT INTO users(username, password, salt, role, meta, roll_number, class_name, division) " +
                     "VALUES(?, ?, ?, 'STUDENT', ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(Config.DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username);
            pstmt.setString(2, hashed);
            pstmt.setString(3, salt);
            pstmt.setString(4, meta);
            pstmt.setInt(5, rollNumber);
            pstmt.setString(6, className);
            pstmt.setString(7, division);
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
        String salt = generateSalt();
        String hashed = hashPassword(newPassword, salt);
        String sql = "UPDATE users SET password = ?, salt = ? WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(Config.DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hashed);
            ps.setString(2, salt);
            ps.setString(3, username);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            AuditLogger.logError("DatabaseManager.changePassword", e.getMessage());
            return false;
        }
    }
}
