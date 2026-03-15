package com.ghost.util;

import java.io.*;
import java.nio.file.*;

/**
 * Manages the Windows hosts file to block/unblock websites.
 * Uses hosts file redirects (127.0.0.1) instead of disabling network adapters,
 * so the LAN connection between admin and students stays intact.
 * 
 * SELF-ELEVATING: If not running as admin, automatically launches an elevated
 * PowerShell process to modify the hosts file. No need to run the whole app as
 * admin.
 */
public class HostsFileManager {
    private static final String HOSTS_PATH = "C:\\Windows\\System32\\drivers\\etc\\hosts";
    private static final String BACKUP_PATH = "C:\\Windows\\System32\\drivers\\etc\\hosts.ghost.bak";
    private static final String GHOST_MARKER_START = "# ===== GHOST LAB BLOCKER START =====";
    private static final String GHOST_MARKER_END = "# ===== GHOST LAB BLOCKER END =====";

    private static volatile boolean blocked = false;

    /**
     * Comprehensive list of non-educational/distracting sites to block.
     */
    private static final String[] BLOCKED_DOMAINS = {
            // Social Media
            "facebook.com", "www.facebook.com",
            "instagram.com", "www.instagram.com",
            "twitter.com", "www.twitter.com",
            "x.com", "www.x.com",
            "tiktok.com", "www.tiktok.com",
            "snapchat.com", "www.snapchat.com",
            "reddit.com", "www.reddit.com",
            "pinterest.com", "www.pinterest.com",
            "tumblr.com", "www.tumblr.com",
            "linkedin.com", "www.linkedin.com",

            // Video/Streaming
            "youtube.com", "www.youtube.com",
            "m.youtube.com", "youtu.be",
            "netflix.com", "www.netflix.com",
            "twitch.tv", "www.twitch.tv",
            "hotstar.com", "www.hotstar.com",
            "primevideo.com", "www.primevideo.com",
            "disneyplus.com", "www.disneyplus.com",
            "hulu.com", "www.hulu.com",
            "vimeo.com", "www.vimeo.com",
            "dailymotion.com", "www.dailymotion.com",
            "jiocinema.com", "www.jiocinema.com",
            "sonyliv.com", "www.sonyliv.com",
            "zee5.com", "www.zee5.com",
            "mxplayer.in", "www.mxplayer.in",

            // Gaming
            "store.steampowered.com", "steampowered.com",
            "epicgames.com", "www.epicgames.com",
            "roblox.com", "www.roblox.com",
            "miniclip.com", "www.miniclip.com",
            "poki.com", "www.poki.com",
            "crazygames.com", "www.crazygames.com",
            "y8.com", "www.y8.com",
            "friv.com", "www.friv.com",
            "itch.io", "www.itch.io",
            "chess.com", "www.chess.com",

            // Chat/Messaging
            "discord.com", "www.discord.com",
            "web.whatsapp.com",
            "web.telegram.org",
            "messenger.com", "www.messenger.com",

            // Shopping
            "amazon.in", "www.amazon.in",
            "amazon.com", "www.amazon.com",
            "flipkart.com", "www.flipkart.com",
            "myntra.com", "www.myntra.com",
            "ajio.com", "www.ajio.com",
            "meesho.com", "www.meesho.com",
            "ebay.com", "www.ebay.com",

            // Entertainment / Memes
            "9gag.com", "www.9gag.com",
            "imgur.com", "www.imgur.com",
            "buzzfeed.com", "www.buzzfeed.com",

            // Betting / Gambling
            "dream11.com", "www.dream11.com",
            "bet365.com", "www.bet365.com",

            // Adult content
            "pornhub.com", "www.pornhub.com",
            "xvideos.com", "www.xvideos.com",
            "xnxx.com", "www.xnxx.com",
            "xhamster.com", "www.xhamster.com",

            // Music streaming
            "spotify.com", "www.spotify.com", "open.spotify.com",
            "gaana.com", "www.gaana.com",
            "jiosaavn.com", "www.jiosaavn.com",
            "wynk.in", "www.wynk.in",
            "soundcloud.com", "www.soundcloud.com"
    };

    /**
     * Check if we have write access to the hosts file.
     */
    private static boolean canWriteHostsFile() {
        try {
            Path hostsPath = Paths.get(HOSTS_PATH);
            // Try to open for writing to test access
            return Files.isWritable(hostsPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build the block entries string for the hosts file.
     */
    private static String buildBlockEntries() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(GHOST_MARKER_START).append("\n");
        sb.append("# Blocked by Ghost Lab Management - DO NOT EDIT\n");
        for (String domain : BLOCKED_DOMAINS) {
            sb.append("127.0.0.1 ").append(domain).append("\n");
        }
        sb.append(GHOST_MARKER_END).append("\n");
        return sb.toString();
    }

    /**
     * Block all distracting sites by modifying the hosts file.
     * Automatically elevates to admin if needed.
     */
    public static synchronized boolean blockSites() {
        try {
            Path hostsPath = Paths.get(HOSTS_PATH);

            // Check if already blocked
            String currentContent = new String(Files.readAllBytes(hostsPath));
            if (currentContent.contains(GHOST_MARKER_START)) {
                System.out.println("[HostsManager] Sites already blocked");
                blocked = true;
                return true;
            }

            if (canWriteHostsFile()) {
                // Direct write (app has admin rights)
                return blockSitesDirect();
            } else {
                // Self-elevate via PowerShell
                System.out.println("[HostsManager] Elevating to modify hosts file...");
                return blockSitesElevated();
            }
        } catch (IOException e) {
            System.err.println("[HostsManager] Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Direct write (when running as admin)
     */
    private static boolean blockSitesDirect() {
        try {
            Path hostsPath = Paths.get(HOSTS_PATH);
            Path backupPath = Paths.get(BACKUP_PATH);

            // Backup original
            if (!Files.exists(backupPath)) {
                Files.copy(hostsPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Append block entries
            Files.write(hostsPath, buildBlockEntries().getBytes(), StandardOpenOption.APPEND);

            flushDns();
            blocked = true;
            System.out.println("[HostsManager] Blocked " + BLOCKED_DOMAINS.length + " domains");
            return true;
        } catch (IOException e) {
            System.err.println("[HostsManager] Direct write error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Elevated write (when NOT running as admin)
     * Creates a temp script, runs it with admin elevation via PowerShell.
     */
    private static boolean blockSitesElevated() {
        try {
            // Create a temp PowerShell script with all the blocking logic
            Path tempScript = Files.createTempFile("ghost_block_", ".ps1");
            StringBuilder ps = new StringBuilder();

            // Backup original hosts file
            ps.append("$hostsPath = '").append(HOSTS_PATH).append("'\n");
            ps.append("$backupPath = '").append(BACKUP_PATH).append("'\n");
            ps.append("if (-not (Test-Path $backupPath)) { Copy-Item $hostsPath $backupPath }\n");

            // Append block entries
            ps.append("$entries = @\"\n");
            ps.append(buildBlockEntries());
            ps.append("\"@\n");
            ps.append("Add-Content -Path $hostsPath -Value $entries -Encoding ASCII\n");

            // Flush DNS
            ps.append("ipconfig /flushdns | Out-Null\n");

            Files.write(tempScript, ps.toString().getBytes());

            // Run the script elevated
            String[] cmd = {
                    "powershell.exe", "-NoProfile", "-Command",
                    "Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','" +
                            tempScript.toAbsolutePath().toString() +
                            "' -Verb RunAs -WindowStyle Hidden -Wait"
            };
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();

            // Cleanup temp script
            Files.deleteIfExists(tempScript);

            // Verify it worked
            String content = new String(Files.readAllBytes(Paths.get(HOSTS_PATH)));
            if (content.contains(GHOST_MARKER_START)) {
                blocked = true;
                System.out.println("[HostsManager] Blocked " + BLOCKED_DOMAINS.length + " domains (elevated)");
                return true;
            } else {
                System.err.println("[HostsManager] Elevation may have been denied by user");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[HostsManager] Elevated block error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Restore the original hosts file by removing Ghost's block entries.
     * Automatically elevates to admin if needed.
     */
    public static synchronized boolean restoreHostsFile() {
        try {
            // Quick check: anything to restore?
            String content = new String(Files.readAllBytes(Paths.get(HOSTS_PATH)));
            if (!content.contains(GHOST_MARKER_START) && !Files.exists(Paths.get(BACKUP_PATH))) {
                blocked = false;
                return true; // Nothing to restore
            }

            if (canWriteHostsFile()) {
                return restoreDirect();
            } else {
                System.out.println("[HostsManager] Elevating to restore hosts file...");
                return restoreElevated();
            }
        } catch (IOException e) {
            System.err.println("[HostsManager] Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Direct restore (when running as admin)
     */
    private static boolean restoreDirect() {
        try {
            Path hostsPath = Paths.get(HOSTS_PATH);
            Path backupPath = Paths.get(BACKUP_PATH);

            if (Files.exists(backupPath)) {
                Files.copy(backupPath, hostsPath, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(backupPath);
                System.out.println("[HostsManager] Restored from backup");
            } else {
                // Remove markers manually
                String content = new String(Files.readAllBytes(hostsPath));
                if (content.contains(GHOST_MARKER_START)) {
                    int startIdx = content.indexOf(GHOST_MARKER_START);
                    int endIdx = content.indexOf(GHOST_MARKER_END);
                    if (endIdx > startIdx) {
                        String cleaned = content.substring(0, startIdx)
                                + content.substring(endIdx + GHOST_MARKER_END.length());
                        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
                        Files.write(hostsPath, cleaned.getBytes());
                    }
                }
            }

            flushDns();
            blocked = false;
            return true;
        } catch (IOException e) {
            System.err.println("[HostsManager] Direct restore error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Elevated restore (when NOT running as admin)
     */
    private static boolean restoreElevated() {
        try {
            Path tempScript = Files.createTempFile("ghost_restore_", ".ps1");
            StringBuilder ps = new StringBuilder();

            ps.append("$hostsPath = '").append(HOSTS_PATH).append("'\n");
            ps.append("$backupPath = '").append(BACKUP_PATH).append("'\n");
            ps.append("if (Test-Path $backupPath) {\n");
            ps.append("    Copy-Item $backupPath $hostsPath -Force\n");
            ps.append("    Remove-Item $backupPath -Force\n");
            ps.append("} else {\n");
            ps.append("    $content = Get-Content $hostsPath -Raw\n");
            ps.append("    $startMarker = '").append(GHOST_MARKER_START).append("'\n");
            ps.append("    $endMarker = '").append(GHOST_MARKER_END).append("'\n");
            ps.append("    if ($content -match [regex]::Escape($startMarker)) {\n");
            ps.append("        $startIdx = $content.IndexOf($startMarker)\n");
            ps.append("        $endIdx = $content.IndexOf($endMarker)\n");
            ps.append("        if ($endIdx -gt $startIdx) {\n");
            ps.append(
                    "            $cleaned = $content.Substring(0, $startIdx) + $content.Substring($endIdx + $endMarker.Length)\n");
            ps.append("            Set-Content -Path $hostsPath -Value $cleaned -NoNewline\n");
            ps.append("        }\n");
            ps.append("    }\n");
            ps.append("}\n");
            ps.append("ipconfig /flushdns | Out-Null\n");

            Files.write(tempScript, ps.toString().getBytes());

            String[] cmd = {
                    "powershell.exe", "-NoProfile", "-Command",
                    "Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','" +
                            tempScript.toAbsolutePath().toString() +
                            "' -Verb RunAs -WindowStyle Hidden -Wait"
            };
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();

            Files.deleteIfExists(tempScript);

            // Verify
            String content = new String(Files.readAllBytes(Paths.get(HOSTS_PATH)));
            if (!content.contains(GHOST_MARKER_START)) {
                blocked = false;
                System.out.println("[HostsManager] Hosts file restored (elevated)");
                return true;
            } else {
                System.err.println("[HostsManager] Restore elevation may have been denied");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[HostsManager] Elevated restore error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Flush DNS cache so hosts file changes take effect immediately.
     */
    private static void flushDns() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "ipconfig /flushdns");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
        } catch (Exception e) {
            System.err.println("[HostsManager] Failed to flush DNS: " + e.getMessage());
        }
    }

    public static boolean isBlocked() {
        return blocked;
    }

    /**
     * Check if the hosts file has leftover Ghost entries (e.g. from a crash).
     */
    public static boolean hasLeftoverBlocks() {
        try {
            String content = new String(Files.readAllBytes(Paths.get(HOSTS_PATH)));
            return content.contains(GHOST_MARKER_START);
        } catch (IOException e) {
            return false;
        }
    }
}
