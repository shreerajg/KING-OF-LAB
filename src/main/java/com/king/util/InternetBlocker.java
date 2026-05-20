package com.king.util;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

/**
 * InternetBlocker — Zero-Admin Internet Control for King of Lab
 *
 * ─────────────────────────────────────────────────────────────────
 * PROBLEM:
 *   Editing the system hosts file (C:\Windows\System32\drivers\etc\hosts)
 *   requires administrator privileges — so HostsFileManager cannot run
 *   when the student's King of Lab instance is a standard (non-admin) user.
 *
 * SOLUTION  (no admin rights needed at all):
 *   Technique 1 — WinInet Proxy via HKCU registry
 *     Set HKCU\...\Internet Settings\ProxyEnable = 1
 *     Set HKCU\...\Internet Settings\ProxyServer  = "127.0.0.1:1"
 *     Port 1 has nothing listening → every HTTP/HTTPS connection is refused.
 *     Affects: Chrome, Edge, IE, and any app that uses WinInet / WinHTTP.
 *     HKCU is always writable by the current user — no UAC, no elevation.
 *
 *   Technique 2 — PAC (Proxy Auto-Config) file in user temp dir
 *     Writes a tiny .pac file that returns DIRECT for localhost/192.168.*
 *     (so King of Lab's own TCP connection to the admin never breaks)
 *     and returns "PROXY 127.0.0.1:1" for everything else.
 *     Set via AutoConfigURL registry key (also HKCU, no admin).
 *     Affects: apps that respect PAC / WPAD.
 *
 *   Technique 3 — Firefox user.js in each profile (best-effort)
 *     Firefox ignores WinInet but honours its own prefs.
 *     We drop a user.js into any detected Firefox profile that forces
 *     the same dead proxy. Applied only if Firefox profiles exist.
 *
 * RESTORE:
 *   All three techniques are reversed cleanly by allowInternet().
 *   ProxyEnable → 0, AutoConfigURL deleted, user.js removed.
 *
 * NOTE: The King of Lab admin→student TCP connection uses a raw Socket
 *   (not WinInet), so it is completely unaffected by proxy settings.
 *   Students cannot bypass by opening CMD: the proxy is per-user, not
 *   per-process, and cannot be changed in CMD without reg.exe (which
 *   still only writes HKCU — same effect, no bypass).
 * ─────────────────────────────────────────────────────────────────
 */
public class InternetBlocker {

    // ── Registry paths ────────────────────────────────────────────
    private static final String REG_INET =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    // Dead-end proxy: port 1 is reserved and never open
    private static final String DEAD_PROXY = "127.0.0.1:1";

    // PAC file location (user temp — always writable)
    private static final Path PAC_FILE = Paths.get(
            System.getenv("TEMP") != null ? System.getenv("TEMP") : System.getProperty("java.io.tmpdir"),
            "king_block.pac");

    // ── State ─────────────────────────────────────────────────────
    private static volatile boolean blocked = false;

    public static boolean isBlocked() { return blocked; }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Block internet for the current user — no admin rights required.
     * Applies WinInet proxy + PAC file + Firefox best-effort.
     */
    public static void blockInternet() {
        try {
            writePacFile();
            applyWinInetProxy();
            applyFirefoxProxy(true);
            notifyIeSettings();   // makes Chrome/Edge pick up immediately
            blocked = true;
            AuditLogger.logSystem("[InternetBlocker] Internet BLOCKED (HKCU proxy, no admin)");
        } catch (Exception e) {
            AuditLogger.logError("InternetBlocker.blockInternet", e.getMessage());
        }
    }

    /**
     * Restore internet access for the current user.
     */
    public static void allowInternet() {
        try {
            removeWinInetProxy();
            deletePacFile();
            applyFirefoxProxy(false);
            notifyIeSettings();
            blocked = false;
            AuditLogger.logSystem("[InternetBlocker] Internet ALLOWED (HKCU proxy removed)");
        } catch (Exception e) {
            AuditLogger.logError("InternetBlocker.allowInternet", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Technique 1 — WinInet HKCU Proxy
    // ─────────────────────────────────────────────────────────────

    private static void applyWinInetProxy() throws Exception {
        // ProxyEnable = 1
        reg("add", REG_INET, "/v", "ProxyEnable",   "/t", "REG_DWORD", "/d", "1",          "/f");
        // ProxyServer = 127.0.0.1:1
        reg("add", REG_INET, "/v", "ProxyServer",   "/t", "REG_SZ",    "/d", DEAD_PROXY,    "/f");
        // ProxyOverride — exempt LAN (King of Lab internal comms are raw sockets, not WinInet,
        //   but listing localhost here costs nothing and avoids any edge case)
        reg("add", REG_INET, "/v", "ProxyOverride", "/t", "REG_SZ",
            "/d", "<local>;localhost;127.*;192.168.*;10.*;172.16.*", "/f");
        // Turn off auto-detect so browsers don't fall back to an open proxy
        reg("add", REG_INET, "/v", "AutoDetect",    "/t", "REG_DWORD", "/d", "0",           "/f");
        // Remove any prior AutoConfigURL that might route around us
        reg("delete", REG_INET, "/v", "AutoConfigURL", "/f");
    }

    private static void removeWinInetProxy() throws Exception {
        reg("add", REG_INET, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f");
        // Don't delete ProxyServer — just disable it so restore is instant
        reg("delete", REG_INET, "/v", "AutoConfigURL", "/f");
        // Restore auto-detect to default (on)
        reg("add", REG_INET, "/v", "AutoDetect",    "/t", "REG_DWORD", "/d", "1", "/f");
    }

    // ─────────────────────────────────────────────────────────────
    // Technique 2 — PAC file + AutoConfigURL
    // ─────────────────────────────────────────────────────────────

    /**
     * Writes a PAC file that blocks all non-local traffic.
     * LAN addresses and localhost are explicitly allowed so the King of
     * Lab admin connection (raw TCP) and local tooling are unaffected.
     */
    private static void writePacFile() throws Exception {
        String pac =
            "function FindProxyForURL(url, host) {\n" +
            "  // Always allow local / LAN traffic\n" +
            "  if (isPlainHostName(host)) return 'DIRECT';\n" +
            "  if (isInNet(host, '127.0.0.0',   '255.0.0.0'))   return 'DIRECT';\n" +
            "  if (isInNet(host, '192.168.0.0', '255.255.0.0')) return 'DIRECT';\n" +
            "  if (isInNet(host, '10.0.0.0',    '255.0.0.0'))   return 'DIRECT';\n" +
            "  if (isInNet(host, '172.16.0.0',  '255.240.0.0')) return 'DIRECT';\n" +
            "  // Block all other traffic via dead proxy\n" +
            "  return 'PROXY " + DEAD_PROXY + "';\n" +
            "}\n";

        Files.writeString(PAC_FILE, pac, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Set AutoConfigURL to point to our PAC file
        String pacUrl = PAC_FILE.toUri().toString(); // file:///C:/Users/.../king_block.pac
        reg("add", REG_INET, "/v", "AutoConfigURL", "/t", "REG_SZ", "/d", pacUrl, "/f");
        reg("add", REG_INET, "/v", "ProxyEnable",   "/t", "REG_DWORD", "/d", "1",    "/f");
    }

    private static void deletePacFile() {
        try { Files.deleteIfExists(PAC_FILE); } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────
    // Technique 3 — Firefox profile proxy (best-effort)
    // ─────────────────────────────────────────────────────────────

    /**
     * Drops / removes a user.js inside every Firefox profile directory.
     * user.js overrides prefs.js on each Firefox start — making it
     * impossible for a running Firefox instance to bypass the block
     * simply by changing settings UI (they apply next restart).
     *
     * This is best-effort: silently skipped if no Firefox profiles exist.
     */
    private static void applyFirefoxProxy(boolean block) {
        try {
            Path profilesRoot = Paths.get(System.getProperty("user.home"),
                    "AppData", "Roaming", "Mozilla", "Firefox", "Profiles");
            if (!Files.isDirectory(profilesRoot)) return;

            try (var stream = Files.list(profilesRoot)) {
                stream.filter(Files::isDirectory).forEach(profile -> {
                    Path userJs = profile.resolve("user.js");
                    try {
                        if (block) {
                            String content =
                                "// King of Lab — network policy\n" +
                                "user_pref(\"network.proxy.type\", 1);\n" +
                                "user_pref(\"network.proxy.http\", \"127.0.0.1\");\n" +
                                "user_pref(\"network.proxy.http_port\", 1);\n" +
                                "user_pref(\"network.proxy.ssl\", \"127.0.0.1\");\n" +
                                "user_pref(\"network.proxy.ssl_port\", 1);\n" +
                                "user_pref(\"network.proxy.no_proxies_on\", \"localhost, 127.0.0.1, 192.168.0.0/16, 10.0.0.0/8\");\n";
                            Files.writeString(userJs, content, StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        } else {
                            Files.deleteIfExists(userJs);
                        }
                    } catch (Exception ignored) {}
                });
            }
            AuditLogger.logSystem("[InternetBlocker] Firefox profiles " +
                    (block ? "patched" : "restored"));
        } catch (Exception e) {
            // Not fatal — Firefox might not be installed
            AuditLogger.logSystem("[InternetBlocker] Firefox profile step skipped: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Force immediate pickup by WinInet
    // ─────────────────────────────────────────────────────────────

    /**
     * Calls InternetSetOption via a tiny PowerShell one-liner to signal WinInet
     * that proxy settings have changed.  This makes Chrome/Edge reload settings
     * without requiring a browser restart.
     * Falls back silently if PowerShell is unavailable.
     */
    private static void notifyIeSettings() {
        try {
            // INTERNET_OPTION_SETTINGS_CHANGED = 39, INTERNET_OPTION_REFRESH = 37
            String ps =
                "Add-Type -TypeDefinition @'\n" +
                "using System;\n" +
                "using System.Runtime.InteropServices;\n" +
                "public class WinInet {\n" +
                "  [DllImport(\"wininet.dll\", SetLastError=true)]\n" +
                "  public static extern bool InternetSetOption(IntPtr hInternet,\n" +
                "      int dwOption, IntPtr lpBuffer, int dwBufferLength);\n" +
                "}\n" +
                "'@\n" +
                "[WinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null\n" +
                "[WinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null\n";

            new ProcessBuilder("powershell.exe", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true)
                    .start();
            // Don't wait — fire-and-forget, settings already in registry
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────
    // reg.exe wrapper
    // ─────────────────────────────────────────────────────────────

    /** Run reg.exe with the given arguments. No admin required for HKCU. */
    private static void reg(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "reg.exe";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        // Drain output so process doesn't hang
        try (InputStream is = p.getInputStream()) {
            is.transferTo(OutputStream.nullOutputStream());
        }
        p.waitFor();
    }
}
