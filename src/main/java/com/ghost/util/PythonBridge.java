package com.ghost.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class PythonBridge {
    private static final String SCRIPT_PATH = "python_modules/executor.py";

    public static void execute(String command) {
        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python", SCRIPT_PATH, command);
                // Ensure working directory is project root or set absolute path
                // Assuming run from project root or scripts folder...
                // We might need to adjust SCRIPT_PATH based on working dir.
                // Best to use relative path if running from 'scripts' ->
                // '../python_modules/executor.py'

                File scriptFile = new File(SCRIPT_PATH);
                if (!scriptFile.exists()) {
                    // Try ../python_modules/executor.py (if running from scripts/)
                    pb.command("python", "../python_modules/executor.py", command);
                }

                Process p = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[Python]: " + line);
                }

                int exitCode = p.waitFor();
                System.out.println("[Python] Exited with code " + exitCode);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Execute command and return output via callback (for commands that need to
     * report results)
     */
    public static void executeWithCallback(String command, java.util.function.Consumer<String> callback) {
        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python", SCRIPT_PATH, command);
                File scriptFile = new File(SCRIPT_PATH);
                if (!scriptFile.exists()) {
                    pb.command("python", "../python_modules/executor.py", command);
                }

                Process p = pb.start();

                StringBuilder output = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    System.out.println("[Python]: " + line);
                }

                // Also capture stderr
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while ((line = errorReader.readLine()) != null) {
                    output.append("[ERROR] ").append(line).append("\n");
                }

                int exitCode = p.waitFor();

                if (callback != null) {
                    String result = output.toString().trim();
                    if (result.isEmpty()) {
                        result = "(Command completed with exit code " + exitCode + ")";
                    }
                    callback.accept(result);
                }

            } catch (Exception e) {
                if (callback != null) {
                    callback.accept("Error: " + e.getMessage());
                }
            }
        }).start();
    }

    public static void askAI(String prompt, java.util.function.Consumer<String> callback) {
        new Thread(() -> {
            try {
                // Use list command to handle spaces properly
                ProcessBuilder pb = new ProcessBuilder("python", "python_modules/ai_interface.py", prompt);
                File scriptFile = new File("python_modules/ai_interface.py");
                if (!scriptFile.exists()) {
                    pb.command("python", "../python_modules/ai_interface.py", prompt);
                }

                Process p = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }

                p.waitFor();
                if (callback != null) {
                    callback.accept(response.toString().trim());
                }

            } catch (Exception e) {
                if (callback != null)
                    callback.accept("Error asking Ghost AI: " + e.getMessage());
            }
        }).start();
    }
}
