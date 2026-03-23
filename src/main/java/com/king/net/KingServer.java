package com.king.net;

import com.king.util.AuditLogger;
import com.king.util.Config;
import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * King of Lab — upgraded KingServer.
 *
 * Features:
 *  - Heartbeat watchdog: marks clients offline after Config.HEARTBEAT_TIMEOUT_SEC
 *  - Audit logging on connect / disconnect / command
 *  - Thread-safe client map (ConcurrentHashMap)
 *  - Sends HEARTBEAT_ACK response to clients
 */
public class KingServer {

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    // Use ConcurrentHashMap for thread-safe access without synchronisation on every read
    private final ConcurrentHashMap<String, ClientHandler> clientsByName = new ConcurrentHashMap<>();
    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "KingServer-Worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "KingServer-Watchdog");
        t.setDaemon(true);
        return t;
    });

    private final Gson gson = new Gson();
    private ScreenUpdateListener screenListener;
    private ClientStatusListener statusListener;
    private ExtendedListener extendedListener;

    // -----------------------------------------------------------------------
    // Listener interfaces
    // -----------------------------------------------------------------------

    public interface ScreenUpdateListener {
        void onScreenUpdate(String clientName, String base64Image);
        default void onShellOutput(String clientName, String output) {}
    }

    public interface ClientStatusListener {
        void onClientConnected(String clientName);
        void onClientDisconnected(String clientName);
    }

    /**
     * Extended listener for Phase 2 packet types: RAISE_HAND, POLL_ANSWER.
     */
    public interface ExtendedListener {
        void onExtendedPacket(CommandPacket.Type type, String sender, String payload);
    }

    public void setScreenListener(ScreenUpdateListener l)    { this.screenListener    = l; }
    public void setStatusListener(ClientStatusListener l)     { this.statusListener    = l; }
    public void setExtendedListener(ExtendedListener l)       { this.extendedListener  = l; }

    // -----------------------------------------------------------------------
    // Start / Stop
    // -----------------------------------------------------------------------

    public void start() {
        startBinaryStreamServer();
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(Config.SERVER_PORT);
                running = true;
                AuditLogger.logSystem("King Server listening on port " + Config.SERVER_PORT);

                // Heartbeat watchdog — checks every 5 s
                scheduler.scheduleAtFixedRate(this::checkHeartbeats,
                        Config.HEARTBEAT_TIMEOUT_SEC,
                        Config.HEARTBEAT_TIMEOUT_SEC / 2,
                        TimeUnit.SECONDS);

                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        ClientHandler handler = new ClientHandler(clientSocket);
                        clients.add(handler);
                        pool.execute(handler);
                    } catch (IOException e) {
                        if (running) AuditLogger.logError("KingServer.accept", e.getMessage());
                    }
                }
            } catch (IOException e) {
                AuditLogger.logError("KingServer.start", e.getMessage());
            }
        }, "KingServer-Accept").start();
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Heartbeat watchdog
    // -----------------------------------------------------------------------

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        long timeoutMs = Config.HEARTBEAT_TIMEOUT_SEC * 1000L;
        List<ClientHandler> toRemove = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (c.isRegistered() && (now - c.lastHeartbeat) > timeoutMs) {
                    AuditLogger.logSystem("Heartbeat timeout for: " + c.clientName);
                    toRemove.add(c);
                }
            }
        }
        for (ClientHandler c : toRemove) {
            c.close();
        }
    }

    // -----------------------------------------------------------------------
    // Broadcast / send helpers
    // -----------------------------------------------------------------------

    public void broadcast(CommandPacket packet) {
        String json = gson.toJson(packet);
        synchronized (clients) {
            for (ClientHandler c : clients) {
                c.send(json);
            }
        }
    }

    public void broadcastExcept(CommandPacket packet, ClientHandler sender) {
        String json = gson.toJson(packet);
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (c != sender) c.send(json);
            }
        }
    }

    public void sendToClient(String clientName, CommandPacket packet) {
        ClientHandler handler = clientsByName.get(clientName);
        if (handler != null) handler.send(gson.toJson(packet));
    }

    public List<String> getConnectedClients() { return new ArrayList<>(clientsByName.keySet()); }
    public int getClientCount()               { return clientsByName.size(); }

    private void startBinaryStreamServer() {
        new Thread(() -> {
            int port = Config.SERVER_PORT + 1;
            try (ServerSocket binarySocket = new ServerSocket(port)) {
                AuditLogger.logSystem("Ultra-Binary Stream Server listening on port " + port);
                while (running) {
                    Socket client = binarySocket.accept();
                    pool.execute(() -> handleBinaryStream(client));
                }
            } catch (IOException e) {
                AuditLogger.logError("BinaryStreamServer", e.getMessage());
            }
        }, "KingServer-BinaryStream").start();
    }

    private void handleBinaryStream(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            while (running && !socket.isClosed()) {
                // Header: [Name Length (int)][Name (UTF)][Payload Length (int)][Payload (bytes)]
                int nameLen = dis.readInt();
                byte[] nameBytes = new byte[nameLen];
                dis.readFully(nameBytes);
                String studentName = new String(nameBytes);
                
                int payloadLen = dis.readInt();
                byte[] payload = new byte[payloadLen];
                dis.readFully(payload);
                
                // Directly convert JPEG bytes to Image and notify listener
                Image image = new Image(new ByteArrayInputStream(payload));
                // We use a custom update path to bypass Base64 decoding in the UI
                Platform.runLater(() -> {
                    if (screenListener instanceof BinaryScreenListener) {
                        ((BinaryScreenListener) screenListener).onBinaryUpdate(studentName, image);
                    }
                });
            }
        } catch (EOFException ignored) {
        } catch (Exception e) {
            AuditLogger.logError("BinaryStream", e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public interface BinaryScreenListener extends ScreenUpdateListener {
        void onBinaryUpdate(String clientName, javafx.scene.image.Image image);
    }

    // -----------------------------------------------------------------------
    // ClientHandler (inner class)
    // -----------------------------------------------------------------------

    public class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        String clientName = "Unknown";
        volatile long lastHeartbeat = System.currentTimeMillis();
        private volatile boolean registered = false;

        public ClientHandler(Socket socket) { this.socket = socket; }

        boolean isRegistered() { return registered; }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(0); // no read timeout — heartbeat watchdog handles it
                out = new PrintWriter(socket.getOutputStream(), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    try {
                        CommandPacket packet = gson.fromJson(inputLine, CommandPacket.class);
                        handlePacket(packet);
                    } catch (Exception e) {
                        // Ignore parse errors from malformed packets
                    }
                }
            } catch (IOException e) {
                // Client disconnected
            } finally {
                cleanup();
            }
        }

        private void handlePacket(CommandPacket packet) {
            switch (packet.getType()) {
                case CONNECT:
                    clientName = packet.getSender();
                    clientsByName.put(clientName, this);
                    registered = true;
                    lastHeartbeat = System.currentTimeMillis();

                    // Parse student info (roll, class, division)
                    try {
                        com.google.gson.JsonObject info = new Gson().fromJson(
                                packet.getPayload(), com.google.gson.JsonObject.class);
                        int roll = info.has("roll") ? info.get("roll").getAsInt() : 0;
                        String cls  = info.has("class") ? info.get("class").getAsString() : "";
                        String div  = info.has("division") ? info.get("division").getAsString() : "";
                        com.king.util.AttendanceTracker.recordConnection(clientName, roll, cls, div);
                        AuditLogger.logConnect(clientName + " (Roll:" + roll + " " + cls + div + ")");
                    } catch (Exception e) {
                        AuditLogger.logConnect(clientName);
                    }

                    if (statusListener != null) statusListener.onClientConnected(clientName);
                    break;

                case HEARTBEAT:
                    lastHeartbeat = System.currentTimeMillis();
                    send(gson.toJson(new CommandPacket(CommandPacket.Type.HEARTBEAT_ACK, "SERVER", "")));
                    break;

                case SCREEN_DATA:
                    if (screenListener != null)
                        screenListener.onScreenUpdate(clientName, packet.getPayload());
                    break;

                case SHELL_OUTPUT:
                    if (screenListener != null)
                        screenListener.onShellOutput(clientName, packet.getPayload());
                    break;

                case MSG:
                    // Forward peer chat to all other students
                    broadcastExcept(packet, this);
                    break;

                case RAISE_HAND:
                case POLL_ANSWER:
                    // Forward to admin via extended listener
                    if (extendedListener != null)
                        extendedListener.onExtendedPacket(packet.getType(), clientName, packet.getPayload());
                    break;

                default:
                    break;
            }
        }

        public void send(String msg) {
            if (out != null) out.println(msg);
        }

        public String getClientName() { return clientName; }

        void close() {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }

        private void cleanup() {
            clients.remove(this);
            clientsByName.remove(clientName);
            AuditLogger.logDisconnect(clientName);
            if (statusListener != null && registered) {
                statusListener.onClientDisconnected(clientName);
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
