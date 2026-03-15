package com.ghost.database;

public class User {
    private int id;
    private String username;
    private String password; // In a real app, hash this!
    private String role; // "ADMIN" or "STUDENT"
    private String meta; // JSON string for extra data

    // Student-specific fields
    private int rollNumber;
    private String className; // FY, SY, TY
    private String division; // A, B, C, etc.

    public User(int id, String username, String password, String role, String meta) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.role = role;
        this.meta = meta;
        this.rollNumber = 0;
        this.className = "";
        this.division = "";
    }

    public User(int id, String username, String password, String role, String meta,
            int rollNumber, String className, String division) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.role = role;
        this.meta = meta;
        this.rollNumber = rollNumber;
        this.className = className;
        this.division = division;
    }

    public User(String username, String password, String role) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.meta = "{}";
        this.rollNumber = 0;
        this.className = "";
        this.division = "";
    }

    // Getters and Setters
    public int getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getRole() {
        return role;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
    }

    public int getRollNumber() {
        return rollNumber;
    }

    public void setRollNumber(int rollNumber) {
        this.rollNumber = rollNumber;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getDivision() {
        return division;
    }

    public void setDivision(String division) {
        this.division = division;
    }
}
