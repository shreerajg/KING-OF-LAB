package com.king;

import com.king.database.DatabaseManager;
import com.king.ui.LoginView;
import com.king.util.InternetBlocker;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        // Prevent JavaFX from exiting when all windows are hidden (needed for system
        // tray)
        Platform.setImplicitExit(false);

        // Initialize Database
        DatabaseManager.init();

        // Cleanup any proxy blocks from a previous crash
        System.out.println("[Main] Ensuring internet is unblocked on startup");
        InternetBlocker.allowInternet();

        // Register JVM shutdown hook to ALWAYS restore proxy on exit
        // This handles: System.exit(), Ctrl+C, Task Manager kill, crashes
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Main] Shutdown hook: Restoring internet...");
            InternetBlocker.allowInternet();
        }, "KingShutdownHook"));

        // Always show login screen - no auto-login
        LoginView.show(primaryStage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
