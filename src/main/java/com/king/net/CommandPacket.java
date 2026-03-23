package com.king.net;

public class CommandPacket {
    public enum Type {
        // Core existing types
        CONNECT, DISCONNECT, LOCK, UNLOCK, SHUTDOWN, RESTART, MSG, SCREEN_DATA,
        INTERNET, SHELL, SHELL_OUTPUT, FILE_DATA, ADMIN_SCREEN, NOTIFICATION,
        SCREENSHOT_ALL, OPEN_URL,

        // Phase 1 additions
        HEARTBEAT, HEARTBEAT_ACK,
        AI_TOGGLE,     // "ENABLE" | "DISABLE"
        FOCUS_REQUEST,
        PERF_STATS,
        STREAM_MODE,   // "LEGACY_CPU" | "ULTRA_WEBRTC"

        // Phase 2 — New features
        RAISE_HAND,    // Student → Server: raise/lower hand (payload: "UP" | "DOWN")
        HAND_STATUS,   // Server → Admin: forward hand-raise events
        TIMER_START,   // Admin → All: start countdown (payload: seconds as string)
        TIMER_STOP,    // Admin → All: stop/clear countdown
        TIMER_TICK,    // Server → All: current remaining seconds
        POLL_QUESTION, // Admin → All: poll question (payload: "question|optA|optB|optC")
        POLL_ANSWER,   // Student → Server: (payload: "optionIndex")
        POLL_RESULTS,  // Server → Admin: aggregated results
        SESSION_START, // Admin → All: lab session started (payload: session name)
        SESSION_END,   // Admin → All: lab session ended
        FOCUS_MODE,    // Admin → All: enable full-screen focus overlay ("ON" | "OFF")
        ALERT_STUDENT, // Admin → specific: flash alert on student screen
        AI_CLEAR_HISTORY // Admin → specific student or "ALL": clear AI context
    }

    private Type   type;
    private String sender;
    private String payload;
    private long   timestamp;

    public CommandPacket(Type type, String sender, String payload) {
        this.type      = type;
        this.sender    = sender;
        this.payload   = payload;
        this.timestamp = System.currentTimeMillis();
    }

    public Type   getType()      { return type;      }
    public String getSender()    { return sender;    }
    public String getPayload()   { return payload;   }
    public long   getTimestamp() { return timestamp; }
}
