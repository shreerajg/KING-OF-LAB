package com.ghost.net;

public class CommandPacket {
    public enum Type {
        // Existing types
        CONNECT, DISCONNECT, LOCK, UNLOCK, SHUTDOWN, RESTART, MSG, SCREEN_DATA,
        INTERNET, SHELL, SHELL_OUTPUT, FILE_DATA, ADMIN_SCREEN, NOTIFICATION,
        SCREENSHOT_ALL, OPEN_URL,

        // New types for King of Lab upgrade
        HEARTBEAT,      // Client → Server ping to prove liveness
        HEARTBEAT_ACK,  // Server → Client acknowledgement
        AI_TOGGLE,      // Admin → All: enable/disable AI (payload: "ENABLE" | "DISABLE")
        FOCUS_REQUEST,  // Admin → Client: request student focus mode
        PERF_STATS      // Client → Server: optional performance stats payload
    }

    private Type   type;
    private String sender;    // Username or "ADMIN" / "SYSTEM"
    private String payload;   // JSON or raw string
    private long   timestamp;

    public CommandPacket(Type type, String sender, String payload) {
        this.type      = type;
        this.sender    = sender;
        this.payload   = payload;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters
    public Type   getType()      { return type;      }
    public String getSender()    { return sender;    }
    public String getPayload()   { return payload;   }
    public long   getTimestamp() { return timestamp; }
}
