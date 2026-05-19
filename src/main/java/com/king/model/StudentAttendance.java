package com.king.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Model class to track student attendance
 */
public class StudentAttendance {
    private String username;
    private int rollNumber;
    private String className;
    private String division;
    private LocalDateTime firstConnected;
    private LocalDateTime lastSeen;
    private LocalDateTime sessionJoinTime;
    private LocalDateTime sessionLeaveTime;
    private long totalDurationSeconds = 0;
    private LocalDateTime lastJoinTime;

    public StudentAttendance(String username, int rollNumber, String className, String division) {
        this.username = username;
        this.rollNumber = rollNumber;
        this.className = className != null ? className.trim().toUpperCase() : "";
        this.division = division != null ? division.trim().toUpperCase() : "";
        LocalDateTime now = LocalDateTime.now();
        this.firstConnected = now;
        this.lastSeen = now;
        this.sessionJoinTime = now;
        this.lastJoinTime = now;
        this.sessionLeaveTime = null;
    }

    public void setFirstConnected(LocalDateTime firstConnected) {
        this.firstConnected = firstConnected;
    }

    public void setLastSeen(LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public void updateLastSeen() {
        this.lastSeen = LocalDateTime.now();
    }

    public void setSessionJoinTime(LocalDateTime sessionJoinTime) {
        this.sessionJoinTime = sessionJoinTime;
        this.lastJoinTime = sessionJoinTime;
    }

    public void setSessionLeaveTime(LocalDateTime sessionLeaveTime) {
        this.sessionLeaveTime = sessionLeaveTime;
        if (lastJoinTime != null && sessionLeaveTime != null) {
            this.totalDurationSeconds += Duration.between(lastJoinTime, sessionLeaveTime).getSeconds();
            this.lastJoinTime = null;
        }
    }

    public void markReconnected() {
        this.lastJoinTime = LocalDateTime.now();
        this.sessionJoinTime = this.lastJoinTime;
        this.sessionLeaveTime = null;
        updateLastSeen();
    }

    public long getTotalDurationSeconds() {
        long currentSessionSeconds = 0;
        if (lastJoinTime != null) {
            currentSessionSeconds = Duration.between(lastJoinTime, LocalDateTime.now()).getSeconds();
        }
        return totalDurationSeconds + currentSessionSeconds;
    }

    public String getTotalDurationFormatted() {
        long totalSeconds = getTotalDurationSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public void setTotalDurationSeconds(long totalDurationSeconds) {
        this.totalDurationSeconds = totalDurationSeconds;
    }

    public LocalDateTime getSessionJoinTime() {
        return sessionJoinTime;
    }

    public LocalDateTime getSessionLeaveTime() {
        return sessionLeaveTime;
    }

    public String getSessionJoinTimeFormatted() {
        return sessionJoinTime != null ? sessionJoinTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "";
    }

    public String getSessionLeaveTimeFormatted() {
        return sessionLeaveTime != null ? sessionLeaveTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "";
    }

    // Getters
    public String getUsername() {
        return username;
    }

    public int getRollNumber() {
        return rollNumber;
    }

    public String getClassName() {
        return className;
    }

    public String getDivision() {
        return division;
    }

    public LocalDateTime getFirstConnected() {
        return firstConnected;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    public String getFirstConnectedFormatted() {
        return firstConnected.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public String getLastSeenFormatted() {
        return lastSeen.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public String getClassDivision() {
        String combined = className + division;
        return combined.isEmpty() ? "UNKNOWN" : combined;
    }
}
