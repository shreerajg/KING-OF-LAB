package com.king.util;

import javafx.application.Platform;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Stage;
import com.king.database.DatabaseManager;
import com.king.database.User;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Manages system tray icon and menu for KING application.
 * Supports both Admin and Student modes with role-specific behaviors.
 */
public class SystemTrayManager {
    private static SystemTray tray;
    private static TrayIcon trayIcon;
    private static Stage primaryStage;
    private static String userRole; // "ADMIN" or "STUDENT"

    /**
     * Initialize system tray with role-specific menu
     * 
     * @param stage The primary JavaFX stage to show/hide
     * @param role  "ADMIN" or "STUDENT"
     * @param user  The logged-in user (for password verification)
     */
    public static void init(Stage stage, String role, User user) {
        if (!SystemTray.isSupported()) {
            System.err.println("System tray is not supported!");
            return;
        }

        primaryStage = stage;
        userRole = role;

        try {
            tray = SystemTray.getSystemTray();

            // Create tray icon — guard against missing resource (NPE in AWT fetcher)
            java.net.URL iconUrl = SystemTrayManager.class.getResource("/king_icon.png");
            Image image;
            if (iconUrl != null) {
                image = Toolkit.getDefaultToolkit().createImage(iconUrl);
                // If AWT returned a stub with invalid dimensions, fall back
                if (image == null || image.getWidth(null) <= 0) {
                    image = createDefaultIcon();
                }
            } else {
                // Resource not found — use the programmatically drawn icon
                image = createDefaultIcon();
            }

            PopupMenu popup = createPopupMenu(role);
            trayIcon = new TrayIcon(image, "KING - " + role, popup);
            trayIcon.setImageAutoSize(true);

            // Double-click to show/hide window
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                        Platform.runLater(() -> toggleWindow());
                    }
                }
            });

            tray.add(trayIcon);
            System.out.println("System tray initialized for " + role);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Create popup menu based on user role
     */
    private static PopupMenu createPopupMenu(String role) {
        PopupMenu popup = new PopupMenu();

        // Show/Hide option
        MenuItem showItem = new MenuItem("Show");
        showItem.addActionListener(e -> Platform.runLater(() -> showWindow()));
        popup.add(showItem);

        popup.addSeparator();

        // Exit option (password-protected for both admin and student)
        MenuItem exitItem = new MenuItem("Exit (Admin Password Required)");
        exitItem.addActionListener(e -> Platform.runLater(() -> attemptExit()));
        popup.add(exitItem);

        return popup;
    }

    /**
     * Create a simple default icon if image file is not found
     */
    private static Image createDefaultIcon() {
        int size = 16;
        java.awt.image.BufferedImage bufferedImage = new java.awt.image.BufferedImage(size, size,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bufferedImage.createGraphics();

        // Draw a simple king-like shape
        g.setColor(new Color(0, 255, 170)); // Cyan color
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(Color.BLACK);
        g.drawOval(2, 2, size - 4, size - 4);

        g.dispose();
        return bufferedImage;
    }

    /**
     * Show the main window
     */
    public static void showWindow() {
        if (primaryStage != null) {
            primaryStage.show();
            primaryStage.toFront();
        }
    }

    /**
     * Hide the main window to tray
     */
    public static void hideWindow() {
        if (primaryStage != null) {
            primaryStage.hide();
            showTrayNotification(
                    "KING Running in Background",
                    userRole.equals("STUDENT")
                            ? "You are still being monitored by Admin"
                            : "Server is still running");
        }
    }

    /**
     * Toggle window visibility
     */
    private static void toggleWindow() {
        if (primaryStage != null) {
            if (primaryStage.isShowing()) {
                hideWindow();
            } else {
                showWindow();
            }
        }
    }

    /**
     * Attempt to exit the application (requires admin password)
     */
    private static void attemptExit() {
        // Create password dialog
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Exit KING");
        dialog.setHeaderText("Admin Password Required");
        dialog.setContentText("Enter admin password to exit:");

        // Use PasswordField for password input
        PasswordField passwordField = new PasswordField();
        dialog.getDialogPane().setContent(passwordField);

        dialog.showAndWait().ifPresent(input -> {
            String password = passwordField.getText();

            // Verify admin password
            User admin = DatabaseManager.login("admin", password);
            if (admin != null && "ADMIN".equalsIgnoreCase(admin.getRole())) {
                // Password correct - exit application
                exitApplication();
            } else {
                // Wrong password
                showTrayNotification("Access Denied", "Incorrect admin password!");
            }
        });
    }

    /**
     * Exit the application completely
     */
    private static void exitApplication() {
        try {
            // For admin, export attendance before exiting
            if ("ADMIN".equals(userRole)) {
                System.out.println("[Admin] Exporting attendance before exit...");
                java.util.List<String> files = AttendanceTracker.generateAttendanceCSV();
                if (!files.isEmpty()) {
                    System.out.println("[Admin] Attendance exported to:");
                    for (String file : files) {
                        System.out.println("  - " + file);
                    }
                }
            }

            // Remove tray icon
            if (tray != null && trayIcon != null) {
                tray.remove(trayIcon);
            }

            // Cleanup hosts file
            HostsFileManager.restoreHostsFile();

            System.out.println("KING exiting...");
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Show notification in system tray
     */
    public static void showTrayNotification(String title, String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    /**
     * Remove tray icon
     */
    public static void remove() {
        if (tray != null && trayIcon != null) {
            tray.remove(trayIcon);
        }
    }
}
