package com.king.model;

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

    public StudentAttendance(String username, int rollNumber, String className, String division) {
        this.username = username;
        this.rollNumber = rollNumber;
        this.className = className != null ? className.trim().toUpperCase() : "";
        this.division = division != null ? division.trim().toUpperCase() : "";
        LocalDateTime now = LocalDateTime.now();
        this.firstConnected = now;
        this.lastSeen = now;
        this.sessionJoinTime = now;
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
    }

    public void setSessionLeaveTime(LocalDateTime sessionLeaveTime) {
        this.sessionLeaveTime = sessionLeaveTime;
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
