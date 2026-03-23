package com.king.net;

import com.king.database.User;
import com.king.util.AdaptiveStreamController;
import com.king.util.AuditLogger;
import com.king.util.Config;
import com.king.util.HostsFileManager;
import com.king.util.PerformanceMonitor;
import com.king.util.ScreenCapture;
import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

import com.king.streaming.api.StreamMode;
import com.king.streaming.api.ScreenCapturer;
import com.king.streaming.api.VideoEncoder;
import com.king.streaming.webrtc.WebRtcClient;
import com.king.streaming.webrtc.DxgiCapturer;
import com.king.streaming.webrtc.NvidiaEncoder;

/**
 * King of Lab — KingClient (network connection from student to admin).
 *
 * Features:
 *  - Adaptive FPS via AdaptiveStreamController (CPU-aware, AI-aware)
 *  - Heartbeat sender every Config.HEARTBEAT_INTERVAL_SEC seconds
 *  - Exponential back-off reconnect: 3s → 6s → 12s → 24s → max 30s
 *  - Audit logging for connect / disconnect events
 *  - Delta-frame skip (if ScreenCapture returns null, frame is unchanged)
 */
public class KingClient {

    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;
    private final Gson     gson = new Gson();

    private String  adminIp;
    private final User studentUser;
    private CommandListener listener;

    private ScheduledExecutorService screenScheduler;
    private ScheduledExecutorService heartbeatScheduler;

    private volatile boolean sendingScreens = true;
    private volatile boolean running        = true;

    // WebRTC Upgrade State
    private StreamMode currentMode = StreamMode.LEGACY_CPU;
    private WebRtcClient webRtcClient;
    private ScreenCapturer ultraCapturer;
    private VideoEncoder ultraEncoder;

    // Reconnect back-off state
    private int reconnectDelay = 3; // seconds, doubles each failure up to 30

    public interface CommandListener {
        void onCommand(CommandPacket packet);
    }

    public KingClient(String adminIp, User studentUser) {
        this.adminIp     = adminIp;
        this.studentUser = studentUser;
    }

    public void updateAdminIp(String newIp) {
        if (!newIp.equals(this.adminIp)) {
            AuditLogger.logSystem("Admin IP changed: " + this.adminIp + " → " + newIp);
            this.adminIp = newIp;
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    public void setListener(CommandListener listener) { this.listener = listener; }
    public void setScreenSending(boolean enabled)     { this.sendingScreens = enabled; }

    // -----------------------------------------------------------------------
    // Connect loop
    // -----------------------------------------------------------------------

    public void connect() {
        new Thread(() -> {
            while (running) {
                try {
                    System.out.println("[King Client] Connecting to " + adminIp + ":" + Config.SERVER_PORT + "...");
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(adminIp, Config.SERVER_PORT), 3000);
                    socket.setKeepAlive(true);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    // Reset back-off on successful connect
                    reconnectDelay = 3;
                    AuditLogger.logSystem("Connected to admin at " + adminIp);

                    if (listener != null)
                        listener.onCommand(new CommandPacket(
                                CommandPacket.Type.NOTIFICATION, "SYSTEM", "CONNECTED"));

                    // Handshake
                    sendHandshake();

                    // Start screen capture and heartbeat
                    startScreenCapture();
                    startHeartbeat();

                    // Read loop
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        handleCommand(inputLine);
                    }

                    if (listener != null)
                        listener.onCommand(new CommandPacket(
                                CommandPacket.Type.NOTIFICATION, "SYSTEM", "⚠️ Admin disconnected"));

                } catch (IOException e) {
                    System.out.println("[King Client] Connection failed: " + e.getMessage()
                            + " — retry in " + reconnectDelay + "s");
                }

                stopScreenCapture();
                stopHeartbeat();
                closeSocket();

                if (running) {
                    try {
                        Thread.sleep(reconnectDelay * 1000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                    // Exponential back-off
                    reconnectDelay = Math.min(reconnectDelay * 2, 30);
                }
            }
        }, "KingClient-Connect").start();
    }

    private void sendHandshake() {
        String studentInfo = String.format(
                "{\"username\":\"%s\",\"roll\":%d,\"class\":\"%s\",\"division\":\"%s\"}",
                studentUser.getUsername(),
                studentUser.getRollNumber(),
                studentUser.getClassName(),
                studentUser.getDivision());
        CommandPacket verify = new CommandPacket(
                CommandPacket.Type.CONNECT, studentUser.getUsername(), studentInfo);
        if (out != null) out.println(gson.toJson(verify));
    }

    // -----------------------------------------------------------------------
    // Adaptive Screen Capture
    // -----------------------------------------------------------------------

    private void startScreenCapture() {
        if (currentMode == StreamMode.ULTRA_WEBRTC) {
            startUltraBinaryStream();
            return;
        }

        if (screenScheduler != null && !screenScheduler.isShutdown()) return;

        screenScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KingClient-ScreenCapture");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        // Adaptive scheduling: reschedules itself with the correct interval each frame
        scheduleNextFrame();
    }

    private void scheduleNextFrame() {
        if (screenScheduler == null || screenScheduler.isShutdown()) return;
        long intervalMs = AdaptiveStreamController.getIntervalMs();
        screenScheduler.schedule(this::captureAndSend, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void captureAndSend() {
        if (sendingScreens && out != null) {
            try {
                String base64 = ScreenCapture.captureForStreaming();
                if (base64 != null) {  // null means delta check: frame unchanged
                    CommandPacket pkt = new CommandPacket(
                            CommandPacket.Type.SCREEN_DATA,
                            studentUser.getUsername(),
                            base64);
                    if (out != null) out.println(gson.toJson(pkt));
                    PerformanceMonitor.recordFrame();
                }
            } catch (Exception e) {
                AuditLogger.logError("ScreenCapture", e.getMessage());
            }
        }
        // Reschedule with the (potentially updated) adaptive interval
        scheduleNextFrame();
    }

    private void stopScreenCapture() {
        stopUltraBinaryStream();
        if (screenScheduler != null) {
            screenScheduler.shutdownNow();
            screenScheduler = null;
        }
    }

    // -----------------------------------------------------------------------
    // Ultra Low Latency Pipeline (Binary TCP Bypass)
    // -----------------------------------------------------------------------

    private Socket binarySocket;
    private DataOutputStream bOut;

    private void startUltraBinaryStream() {
        new Thread(() -> {
            try {
                System.out.println("[Ultra-Stream] Connecting binary socket to " + adminIp + ":5556");
                binarySocket = new Socket();
                binarySocket.connect(new InetSocketAddress(adminIp, Config.SERVER_PORT + 1), 5000);
                bOut = new DataOutputStream(new BufferedOutputStream(binarySocket.getOutputStream()));
                
                while (currentMode == StreamMode.ULTRA_WEBRTC && running && !binarySocket.isClosed()) {
                    byte[] jpeg = ScreenCapture.captureAsBytes(1.0, 0.70f);
                    if (jpeg != null) {
                        synchronized (bOut) {
                            byte[] nameBytes = studentUser.getUsername().getBytes();
                            bOut.writeInt(nameBytes.length);
                            bOut.write(nameBytes);
                            bOut.writeInt(jpeg.length);
                            bOut.write(jpeg);
                            bOut.flush();
                        }
                        PerformanceMonitor.recordFrame();
                    }
                    Thread.sleep(1000 / Config.FPS_HIGH);
                }
            } catch (Exception e) {
                AuditLogger.logError("UltraStream", e.getMessage());
                currentMode = StreamMode.LEGACY_CPU;
                startScreenCapture();
            } finally {
                stopUltraBinaryStream();
            }
        }, "KingClient-UltraStream").start();
    }

    private void stopUltraBinaryStream() {
        try {
            if (binarySocket != null) binarySocket.close();
            binarySocket = null;
            bOut = null;
        } catch (IOException ignored) {}
    }

    // -----------------------------------------------------------------------
    // Heartbeat
    // -----------------------------------------------------------------------

    private void startHeartbeat() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) return;
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KingClient-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (out != null) {
                out.println(gson.toJson(new CommandPacket(
                        CommandPacket.Type.HEARTBEAT, studentUser.getUsername(), "")));
            }
        }, Config.HEARTBEAT_INTERVAL_SEC, Config.HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    // -----------------------------------------------------------------------
    // Command handling
    // -----------------------------------------------------------------------

    private void handleCommand(String json) {
        try {
            CommandPacket packet = gson.fromJson(json, CommandPacket.class);

            if (listener != null) listener.onCommand(packet);

            switch (packet.getType()) {
                case LOCK:
                    executeDirectCommand("rundll32.exe user32.dll,LockWorkStation");
                    break;
                case UNLOCK:
                    // Overlay is cleared by UI listener
                    break;
                case SHUTDOWN:
                    Runtime.getRuntime().exec(new String[]{"shutdown", "/s", "/t", "5"});
                    break;
                case RESTART:
                    Runtime.getRuntime().exec(new String[]{"shutdown", "/r", "/t", "5"});
                    break;
                case OPEN_URL:
                    Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", packet.getPayload()});
                    break;
                case INTERNET:
                    if ("DISABLE".equals(packet.getPayload())) {
                        HostsFileManager.blockSites();
                    } else {
                        HostsFileManager.restoreHostsFile();
                    }
                    break;
                case SHELL:
                    String cmd = packet.getPayload();
                    if (cmd != null && !cmd.isEmpty()) executeShellWithOutput(cmd);
                    break;
                case HEARTBEAT_ACK:
                    // Server is alive — no action needed
                    break;
                case AI_TOGGLE:
                    Config.aiEnabled = "ENABLE".equals(packet.getPayload());
                    AuditLogger.logSystem("AI " + (Config.aiEnabled ? "ENABLED" : "DISABLED") + " by admin");
                    break;
                case STREAM_MODE:
                    StreamMode newMode = StreamMode.valueOf(packet.getPayload());
                    if (currentMode != newMode) {
                        AuditLogger.logSystem("Switching Stream Mode to: " + newMode);
                        stopScreenCapture();
                        currentMode = newMode;
                        startScreenCapture();
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            AuditLogger.logError("KingClient.handleCommand", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Outbound messaging
    // -----------------------------------------------------------------------

    public void sendMessage(CommandPacket packet) {
        if (out != null) out.println(gson.toJson(packet));
    }

    public void disconnect() {
        running = false;
        stopScreenCapture();
        stopHeartbeat();
        closeSocket();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        out    = null;
        in     = null;
    }

    // -----------------------------------------------------------------------
    // Shell execution helpers
    // -----------------------------------------------------------------------

    private void executeDirectCommand(String command) {
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
                pb.redirectErrorStream(true);
                pb.start().waitFor();
            } catch (Exception e) {
                AuditLogger.logError("DirectCmd", command + ": " + e.getMessage());
            }
        });
    }

    private void executeShellWithOutput(String command) {
        String clientName = studentUser.getUsername();
        String cmdLower   = command.trim().toLowerCase();

        // Shortcut commands
        if (cmdLower.equals("/shutdown") || cmdLower.startsWith("/shutdown ")) {
            String args = command.trim().substring("/shutdown".length()).trim();
            String sc   = "shutdown " + (args.isEmpty() ? "/s /t 0" : args);
            executeDirectCommand(sc);
            sendShellOutput(clientName, command, "Executing: " + sc);
            AuditLogger.logCommand("admin", clientName, command);
            return;
        } else if (cmdLower.equals("/restart") || cmdLower.startsWith("/restart ")) {
            executeDirectCommand("shutdown /r /t 0");
            sendShellOutput(clientName, command, "Executing: shutdown /r /t 0");
            AuditLogger.logCommand("admin", clientName, command);
            return;
        } else if (cmdLower.equals("/lock")) {
            executeDirectCommand("rundll32.exe user32.dll,LockWorkStation");
            sendShellOutput(clientName, command, "Workstation locked");
            return;
        } else if (cmdLower.equals("/logoff")) {
            executeDirectCommand("shutdown /l");
            sendShellOutput(clientName, command, "Logging off");
            return;
        }

        // Strip leading '/'
        String cleanCmd = command.trim().startsWith("/") ? command.trim().substring(1) : command.trim();
        AuditLogger.logCommand("admin", clientName, cleanCmd);

        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cleanCmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                StringBuilder outp = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) outp.append(line).append("\n");
                }
                p.waitFor();
                sendShellOutput(clientName, command, outp.toString());
            } catch (Exception e) {
                sendShellOutput(clientName, command, "Error: " + e.getMessage());
            }
        });
    }

    private void sendShellOutput(String clientName, String command, String output) {
        if (out != null) {
            String response = clientName + " > " + command + "\n" + output;
            out.println(gson.toJson(new CommandPacket(
                    CommandPacket.Type.SHELL_OUTPUT, clientName, response)));
        }
    }
}
