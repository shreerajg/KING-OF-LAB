package com.king.util;

import com.king.model.StudentAttendance;
import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks student attendance and generates CSV reports
 */
public class AttendanceTracker {
    private static final Map<String, StudentAttendance> attendanceMap = new ConcurrentHashMap<>();

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
            // Student already connected before, update last seen
            attendance.updateLastSeen();
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
        attendanceDir.mkdirs();

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // Group students by class-division
        Map<String, List<StudentAttendance>> groupedByClass = new HashMap<>();
        for (StudentAttendance attendance : attendanceMap.values()) {
            String key = attendance.getClassDivision();
            groupedByClass.putIfAbsent(key, new ArrayList<>());
            groupedByClass.get(key).add(attendance);
        }

        List<String> generatedFiles = new ArrayList<>();

        // Generate CSV for each class-division
        for (Map.Entry<String, List<StudentAttendance>> entry : groupedByClass.entrySet()) {
            String classDivision = entry.getKey();
            List<StudentAttendance> students = entry.getValue();

            // Sort by roll number
            students.sort(Comparator.comparingInt(StudentAttendance::getRollNumber));

            String filename = "Attendance_" + classDivision + "_" + date + ".csv";
            File csvFile = new File(attendanceDir, filename);

            try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
                // Write CSV header
                writer.println("Roll Number,Username,Class,Division,First Connected,Last Seen");

                // Write student records
                for (StudentAttendance student : students) {
                    writer.printf("%d,%s,%s,%s,%s,%s%n",
                            student.getRollNumber(),
                            student.getUsername(),
                            student.getClassName(),
                            student.getDivision(),
                            student.getFirstConnectedFormatted(),
                            student.getLastSeenFormatted());
                }

                generatedFiles.add(csvFile.getAbsolutePath());
                System.out.println("[Attendance] Generated: " + csvFile.getAbsolutePath() +
                        " (" + students.size() + " students)");

            } catch (IOException e) {
                System.err.println("[Attendance] Failed to write CSV: " + e.getMessage());
            }
        }

        return generatedFiles;
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
