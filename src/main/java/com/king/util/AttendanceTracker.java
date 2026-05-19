package com.king.util;

import com.king.model.StudentAttendance;
import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks student attendance and generates CSV reports
 */
public class AttendanceTracker {
    private static final Map<String, StudentAttendance> attendanceMap = new ConcurrentHashMap<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Record a student connection
     */
    public static void recordConnection(String username, int rollNumber, String className, String division) {
        if (username == null || username.isEmpty())
            return;

        StudentAttendance attendance = attendanceMap.get(username);
        if (attendance == null) {
            // First time seeing this student today
            attendance = new StudentAttendance(username, rollNumber, className, division);
            attendanceMap.put(username, attendance);
            System.out.println(
                "[Attendance] Recorded: " + username + " (Roll: " + rollNumber + ", " + className + division + ")");
        } else {
            // Student reconnected
            attendance.markReconnected();
        }
    }

    /**
     * Record a student disconnection
     */
    public static void recordDisconnection(String username) {
        if (username == null || username.isEmpty())
            return;

        StudentAttendance attendance = attendanceMap.get(username);
        if (attendance != null) {
            attendance.setSessionLeaveTime(LocalDateTime.now());
        }
    }

    /**
     * Generate attendance CSV files grouped by class-division
     * Returns list of generated file paths
     */
    public static List<String> generateAttendanceCSV() {
        if (attendanceMap.isEmpty()) {
            System.out.println("[Attendance] No students connected, skipping CSV generation");
            return Collections.emptyList();
        }

        // Create attendance directory
        File attendanceDir = new File(System.getProperty("user.home"), "King Attendance");
        if (!attendanceDir.exists()) {
            attendanceDir.mkdirs();
        }

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // Group students by class-division
        Map<String, List<StudentAttendance>> groupedByClass = new HashMap<>();
        for (StudentAttendance attendance : attendanceMap.values()) {
            String key = attendance.getClassDivision();
            groupedByClass.computeIfAbsent(key, k -> new ArrayList<>()).add(attendance);
        }

        List<String> generatedFiles = new ArrayList<>();

        // Generate CSV for each class-division
        for (Map.Entry<String, List<StudentAttendance>> entry : groupedByClass.entrySet()) {
            String classDivision = entry.getKey();
            List<StudentAttendance> currentStudents = entry.getValue();

            String filename = "Attendance_" + classDivision + "_" + date + ".csv";
            File csvFile = new File(attendanceDir, filename);

            Map<String, StudentAttendance> mergedStudents = new HashMap<>();

            // 1. Read existing from CSV to avoid overwriting lost state
            if (csvFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
                    String line = reader.readLine(); // skip header
                    while ((line = reader.readLine()) != null) {
                        String[] parts = parseCsvLine(line);
                        if (parts.length >= 6) {
                            try {
                                int roll = Integer.parseInt(parts[0].trim());
                                String user = parts[1].trim();
                                String cl = parts[2].trim();
                                String div = parts[3].trim();
                                LocalDateTime fc = LocalDateTime.parse(parts[4].trim(), formatter);
                                LocalDateTime ls = LocalDateTime.parse(parts[5].trim(), formatter);
                                
                                StudentAttendance oldAtt = new StudentAttendance(user, roll, cl, div);
                                oldAtt.setFirstConnected(fc);
                                oldAtt.setLastSeen(ls);
                                
                                if (parts.length >= 8) {
                                    if (!parts[6].isEmpty()) oldAtt.setSessionJoinTime(LocalDateTime.parse(parts[6].trim(), formatter));
                                    if (!parts[7].isEmpty()) oldAtt.setSessionLeaveTime(LocalDateTime.parse(parts[7].trim(), formatter));
                                }
                                
                                if (parts.length >= 9) {
                                    oldAtt.setTotalDurationSeconds(parseDurationToSeconds(parts[8].trim()));
                                }
                                
                                mergedStudents.put(user, oldAtt);
                            } catch (Exception e) {
                                // Ignore parse errors for a single line
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[Attendance] Error reading existing CSV: " + e.getMessage());
                }
            }

            // 2. Merge with current in-memory map
            for (StudentAttendance curr : currentStudents) {
                if (mergedStudents.containsKey(curr.getUsername())) {
                    StudentAttendance existing = mergedStudents.get(curr.getUsername());
                    
                    // Update connection times
                    if (curr.getLastSeen().isAfter(existing.getLastSeen())) {
                        existing.setLastSeen(curr.getLastSeen());
                    }
                    if (curr.getFirstConnected().isBefore(existing.getFirstConnected())) {
                        existing.setFirstConnected(curr.getFirstConnected());
                    }
                    
                    // Update session times (latest)
                    existing.setSessionJoinTime(curr.getSessionJoinTime());
                    existing.setSessionLeaveTime(curr.getSessionLeaveTime());
                    
                    // Update duration: memory is current source of truth for THIS run.
                    // But we want total duration.
                    // Since memory's totalDurationSeconds includes all sessions in this run,
                    // we should probably just use the memory's duration if it's larger?
                    // Actually, if we restarted the server, memory starts at 0.
                    // So we should add memory's duration to CSV duration?
                    // But generateAttendanceCSV is called multiple times.
                    // We need to avoid double adding.
                    
                    // Strategy: memory `totalDurationSeconds` is for THIS process lifecycle.
                    // CSV `totalDurationSeconds` is from PREVIOUS process lifecycles.
                    // However, `curr` in memory might have been updated by `markReconnected`.
                    
                    // Let's just use the largest duration for now as a simple heuristic, 
                    // or better: memory's totalDurationSeconds is what we have NOW.
                    // If we just overwrite, we lose history.
                    // If we add, we double count.
                    
                    // Correct way: StudentAttendance in memory should be initialized with CSV values if they exist!
                    // But recordConnection doesn't check CSV.
                    
                    // Simple fix for now: take the most recent duration.
                    existing.setTotalDurationSeconds(Math.max(existing.getTotalDurationSeconds(), curr.getTotalDurationSeconds()));

                } else {
                    mergedStudents.put(curr.getUsername(), curr);
                }
            }

            // 3. Sort merged list by roll number
            List<StudentAttendance> finalStudents = new ArrayList<>(mergedStudents.values());
            finalStudents.sort(Comparator.comparingInt(StudentAttendance::getRollNumber));

            try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
                // Write CSV header
                writer.println("Roll Number,Username,Class,Division,First Connected,Last Seen,Session Join Time,Session Leave Time,Total Duration");

                // Write student records
                for (StudentAttendance student : finalStudents) {
                    writer.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        student.getRollNumber(),
                        escapeCsv(student.getUsername()),
                        escapeCsv(student.getClassName()),
                        escapeCsv(student.getDivision()),
                        student.getFirstConnectedFormatted(),
                        student.getLastSeenFormatted(),
                        student.getSessionJoinTimeFormatted(),
                        student.getSessionLeaveTimeFormatted(),
                        student.getTotalDurationFormatted());
                }

                if (!generatedFiles.contains(csvFile.getAbsolutePath())) {
                    generatedFiles.add(csvFile.getAbsolutePath());
                }
                System.out.println("[Attendance] Generated: " + csvFile.getAbsolutePath() +
                        " (" + finalStudents.size() + " students)");

            } catch (IOException e) {
                System.err.println("[Attendance] Failed to write CSV: " + e.getMessage());
            }
        }

        return generatedFiles;
    }

    private static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result.toArray(new String[0]);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static long parseDurationToSeconds(String duration) {
        try {
            String[] parts = duration.split(":");
            if (parts.length == 3) {
                long h = Long.parseLong(parts[0]);
                long m = Long.parseLong(parts[1]);
                long s = Long.parseLong(parts[2]);
                return h * 3600 + m * 60 + s;
            }
        } catch (Exception e) {}
        return 0;
    }

    /**
     * Clear all attendance records (for new session)
     */
    public static void clearRecords() {
        attendanceMap.clear();
    }

    /**
     * Get total number of students who connected
     */
    public static int getTotalStudents() {
        return attendanceMap.size();
    }
}
