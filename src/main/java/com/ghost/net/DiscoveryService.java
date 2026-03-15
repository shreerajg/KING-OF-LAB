package com.ghost.net;

import com.ghost.util.Config;
import java.net.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP-based server discovery service.
 * Admin broadcasts its presence on ALL network interfaces.
 * Students listen continuously and auto-connect, even if IP changes.
 */
public class DiscoveryService {
    private static final int DISCOVERY_PORT = 5556;
    private static final String BROADCAST_MESSAGE = "GHOST_SERVER";
    private static final int BROADCAST_INTERVAL_MS = 2000;

    private DatagramSocket socket;
    private AtomicBoolean running = new AtomicBoolean(false);
    private DiscoveryListener listener;

    public interface DiscoveryListener {
        void onServerFound(String serverIp, int port);
    }

    public void setListener(DiscoveryListener listener) {
        this.listener = listener;
    }

    /**
     * Start broadcasting server presence (called by Admin).
     * Broadcasts on ALL active network interfaces for maximum compatibility
     * (works with Ethernet, Wi-Fi, and mobile hotspot simultaneously).
     */
    public void startBroadcasting() {
        running.set(true);
        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);

                String localIp = getLocalIp();
                System.out.println("Discovery: Broadcasting on port " + DISCOVERY_PORT + " (IP: " + localIp + ")");

                while (running.get()) {
                    try {
                        // Get current local IP (may change with hotspot/network switches)
                        localIp = getLocalIp();
                        String message = BROADCAST_MESSAGE + ":" + Config.SERVER_PORT + ":" + localIp;
                        byte[] data = message.getBytes();

                        // Broadcast to 255.255.255.255 (global broadcast)
                        DatagramPacket packet = new DatagramPacket(
                                data, data.length,
                                InetAddress.getByName("255.255.255.255"),
                                DISCOVERY_PORT);
                        socket.send(packet);

                        // Broadcast to ALL subnet broadcast addresses (covers all interfaces)
                        java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces.hasMoreElements()) {
                            NetworkInterface iface = interfaces.nextElement();
                            if (iface.isLoopback() || !iface.isUp())
                                continue;

                            for (InterfaceAddress ifAddr : iface.getInterfaceAddresses()) {
                                InetAddress broadcast = ifAddr.getBroadcast();
                                if (broadcast != null) {
                                    packet = new DatagramPacket(data, data.length, broadcast, DISCOVERY_PORT);
                                    socket.send(packet);
                                }
                            }
                        }

                        // Also broadcast to localhost for same-machine testing
                        packet = new DatagramPacket(data, data.length,
                                InetAddress.getByName("127.0.0.1"), DISCOVERY_PORT);
                        socket.send(packet);

                        Thread.sleep(BROADCAST_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        break;
                    } catch (IOException e) {
                        System.err.println("Discovery broadcast send error: " + e.getMessage());
                        try {
                            Thread.sleep(BROADCAST_INTERVAL_MS);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Discovery broadcast error: " + e.getMessage());
            } finally {
                if (socket != null)
                    socket.close();
            }
        }, "DiscoveryBroadcaster").start();
    }

    /**
     * Start listening for server broadcasts (called by Student).
     * Runs CONTINUOUSLY - keeps listening even after finding the server
     * so it can detect IP changes when admin switches networks.
     */
    public void startListening() {
        running.set(true);

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            socket.setBroadcast(true);
            socket.setSoTimeout(5000); // 5 second timeout per attempt
        } catch (IOException e) {
            System.err.println("Discovery: Cannot bind to port " + DISCOVERY_PORT + " - trying localhost fallback");
            // Port in use (likely by Admin on same machine), use localhost
            if (listener != null) {
                listener.onServerFound("127.0.0.1", Config.SERVER_PORT);
            }
            return;
        }

        new Thread(() -> {
            byte[] buffer = new byte[256];
            String lastFoundIp = null;

            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength());
                    if (message.startsWith(BROADCAST_MESSAGE)) {
                        String[] parts = message.split(":");
                        if (parts.length >= 3) {
                            int port = Integer.parseInt(parts[1]);
                            String serverIp = parts[2];

                            // On same machine, prefer localhost
                            if (serverIp.equals(getLocalIp())) {
                                serverIp = "127.0.0.1";
                            }

                            // Only log when IP changes or first discovery
                            if (!serverIp.equals(lastFoundIp)) {
                                System.out.println("Discovery: Found Admin at " + serverIp + ":" + port);
                                lastFoundIp = serverIp;
                            }

                            if (listener != null) {
                                listener.onServerFound(serverIp, port);
                            }

                            // Keep listening but sleep a bit to avoid spamming
                            Thread.sleep(BROADCAST_INTERVAL_MS);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // Normal timeout - keep listening silently
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("Discovery error: " + e.getMessage());
                    }
                    break;
                }
            }
        }, "DiscoveryListener").start();
    }

    public void stop() {
        running.set(false);
        if (socket != null) {
            socket.close();
        }
    }

    /**
     * Get local IP address (not loopback).
     * Scans all active network interfaces.
     */
    public static String getLocalIp() {
        try {
            // Try to find a non-loopback address
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }
}
