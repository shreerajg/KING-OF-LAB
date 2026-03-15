package com.ghost.ui;

import com.ghost.database.User;
import com.ghost.net.CommandPacket;
import com.ghost.net.DiscoveryService;
import com.ghost.net.GhostServer;
import com.ghost.util.*;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * King of Lab — Admin Dashboard.
 *
 * Upgrades:
 *  - Rebranded: 👑 KING OF LAB
 *  - AI enable/disable toggle with global broadcast
 *  - 4 admin monitoring themes
 *  - Live performance stats bar (CPU, RAM, FPS, clients)
 *  - Audit logging
 */
public class AdminDashboard {

    private static GhostServer       server;
    private static DiscoveryService  discoveryService;
    private static FlowPane          thumbnailGrid;
    private static TextArea          chatArea;
    private static boolean           internetKilled  = false;
    private static boolean           screenSharing   = false;
    private static java.util.concurrent.ScheduledExecutorService screenScheduler;

    private static final Map<String, VBox>      studentCards  = new HashMap<>();
    private static final Map<String, ImageView> studentImages = new HashMap<>();

    // Current admin theme
    private static String adminTheme = "dark";

    // Performance bar label
    private static Label perfBarLabel;

    // AI toggle button reference
    private static Button aiToggleBtn;

    // -----------------------------------------------------------------------
    // Show
    // -----------------------------------------------------------------------

    public static void show(Stage stage, User user) {

        if (server == null) {
            server = new GhostServer();
            server.setScreenListener(new GhostServer.ScreenUpdateListener() {
                @Override
                public void onScreenUpdate(String name, String base64) {
                    Platform.runLater(() -> updateStudentScreen(name, base64));
                }
                @Override
                public void onShellOutput(String name, String output) {
                    Platform.runLater(() -> appendChat("--- Output from " + name + " ---\n" + output));
                }
            });
            server.setStatusListener(new GhostServer.ClientStatusListener() {
                @Override public void onClientConnected(String name) { /* card created on first frame */ }
                @Override public void onClientDisconnected(String name) {
                    Platform.runLater(() -> removeStudentCard(name));
                }
            });
            server.start();

            discoveryService = new DiscoveryService();
            discoveryService.startBroadcasting();
            AuditLogger.logSystem("Admin dashboard started, discovery broadcasting");
        }

        // ===== ROOT LAYOUT =====
        BorderPane root = new BorderPane();
        applyAdminTheme(root, adminTheme);

        // ===== LEFT SIDEBAR =====
        VBox sidebar = buildSidebar(stage, user, root);
        root.setLeft(sidebar);

        // ===== CENTER GRID =====
        VBox center = buildCenterGrid();
        root.setCenter(center);

        // ===== RIGHT PANEL (Chat + Console) =====
        VBox right = buildRightPanel();
        root.setRight(right);

        // ===== BOTTOM STATS + CONSOLE =====
        VBox bottom = buildBottomBar();
        root.setBottom(bottom);

        Scene scene = new Scene(root, 1280, 780);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — Admin: " + user.getUsername());
        stage.setMaximized(true);

        SystemTrayManager.init(stage, "ADMIN", user);
        stage.setOnCloseRequest(e -> {
            e.consume();
            java.util.List<String> files = AttendanceTracker.generateAttendanceCSV();
            files.forEach(f -> System.out.println("  Attendance: " + f));
            AuditLogger.logSystem("Admin closed dashboard, attendance exported");
            SystemTrayManager.hideWindow();
        });

        stage.show();

        // Start performance stats refresh every 2 s
        startPerfTimer();
    }

    // -----------------------------------------------------------------------
    // Sidebar
    // -----------------------------------------------------------------------

    private static VBox buildSidebar(Stage stage, User user, BorderPane root) {
        VBox sidebar = new VBox(14);
        sidebar.setPadding(new Insets(20, 15, 20, 15));
        sidebar.setStyle("-fx-background-color: rgba(0,0,0,0.35); -fx-background-radius: 0 18 18 0;");
        sidebar.setPrefWidth(230);
        sidebar.setAlignment(Pos.TOP_CENTER);

        Label logo = new Label("👑 KING OF LAB");
        logo.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #c39bd3;");

        Label subtitle = new Label("COMMANDER");
        subtitle.setStyle("-fx-font-size: 10px; -fx-text-fill: #777; -fx-letter-spacing: 3px;");

        VBox userBox = new VBox(4);
        userBox.setAlignment(Pos.CENTER);
        userBox.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 10; -fx-padding: 12;");
        Circle avatar = new Circle(22, Color.web("#c39bd3"));
        Label uname   = new Label(user.getUsername().toUpperCase());
        uname.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        Label role    = new Label("Administrator");
        role.setStyle("-fx-text-fill: #666; -fx-font-size: 10px;");
        userBox.getChildren().addAll(avatar, uname, role);

        // --- INTERNET CONTROLS ---
        VBox netSection = sectionBox("INTERNET",
                styledBtn("🌐 BLOCK INTERNET", "#c0392b", () -> {
                    HostsFileManager.blockSites();
                    internetKilled = true;
                    AuditLogger.logCommand(user.getUsername(), "ALL", "BLOCK INTERNET");
                }),
                styledBtn("✅ UNBLOCK INTERNET", "#27ae60", () -> {
                    HostsFileManager.restoreHostsFile();
                    internetKilled = false;
                    AuditLogger.logCommand(user.getUsername(), "ALL", "UNBLOCK INTERNET");
                }));

        // --- POWER CONTROLS ---
        VBox powerSection = sectionBox("POWER",
                styledBtn("🔒 LOCK ALL",     "#c0392b", () -> {
                    server.broadcast(new CommandPacket(CommandPacket.Type.LOCK,     "ADMIN", "{}"));
                    AuditLogger.logCommand(user.getUsername(), "ALL", "LOCK ALL");
                }),
                styledBtn("🔓 UNLOCK ALL",   "#27ae60", () -> {
                    server.broadcast(new CommandPacket(CommandPacket.Type.UNLOCK,   "ADMIN", "{}"));
                    AuditLogger.logCommand(user.getUsername(), "ALL", "UNLOCK ALL");
                }),
                styledBtn("⏻ SHUTDOWN ALL", "#8e44ad", () -> {
                    server.broadcast(new CommandPacket(CommandPacket.Type.SHUTDOWN, "ADMIN", "{}"));
                    AuditLogger.logCommand(user.getUsername(), "ALL", "SHUTDOWN ALL");
                }),
                styledBtn("🔄 RESTART ALL",  "#e67e22", () -> {
                    server.broadcast(new CommandPacket(CommandPacket.Type.RESTART,  "ADMIN", "{}"));
                    AuditLogger.logCommand(user.getUsername(), "ALL", "RESTART ALL");
                }));

        // --- AI CONTROL ---
        aiToggleBtn = new Button("🤖 DISABLE AI");
        aiToggleBtn.setMaxWidth(Double.MAX_VALUE);
        aiToggleBtn.setStyle("-fx-background-color: #1a5276; -fx-text-fill: white; " +
                "-fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 10 12; -fx-cursor: hand;");
        aiToggleBtn.setOnAction(e -> toggleAi(user.getUsername()));

        Label aiModelLabel = new Label("Model: " + Config.AI_MODEL);
        aiModelLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 9px;");

        VBox aiSection = new VBox(6, new Label("AI CONTROL") {{
            setStyle("-fx-text-fill: #555; -fx-font-size: 10px; -fx-font-weight: bold;");
        }}, aiToggleBtn, aiModelLabel);
        aiSection.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 10; -fx-padding: 12;");

        // --- SCREEN SHARE ---
        VBox shareSection = sectionBox("SCREEN SHARE", buildScreenShareToggle());

        // --- EXTRA TOOLS ---
        VBox extraSection = sectionBox("TOOLS",
                styledBtn("📸 SCREENSHOT ALL", "#f39c12", () -> captureAllScreenshots(stage)),
                styledBtn("🌐 OPEN URL",        "#27ae60", () -> openUrlOnAll()),
                styledBtn("📁 SEND FILES",       "#3498db", () -> sendFilesToStudents(stage)),
                styledBtn("📊 ATTENDANCE",       "#16a085", () -> generateAttendanceManually(stage)));

        // --- THEME ---
        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll("🌑 Dark", "⚡ High Contrast", "🔵 Blue Steel", "🌲 Forest Night");
        themeBox.setValue("🌑 Dark");
        themeBox.setStyle("-fx-background-color: #2a2a3e; -fx-font-size: 11px;");
        themeBox.setMaxWidth(Double.MAX_VALUE);
        themeBox.setOnAction(e -> {
            String v = themeBox.getValue();
            if      (v.contains("Dark"))    applyAdminTheme(root, "dark");
            else if (v.contains("Contrast"))applyAdminTheme(root, "contrast");
            else if (v.contains("Steel"))   applyAdminTheme(root, "steel");
            else if (v.contains("Forest"))  applyAdminTheme(root, "forest");
        });
        VBox themeSection = sectionBox("ADMIN THEME", themeBox);

        // Scroll wrapper
        VBox sideContent = new VBox(10, logo, subtitle, new Separator(), userBox,
                new Separator(), netSection, powerSection, aiSection, shareSection, extraSection, themeSection);
        ScrollPane scroll = new ScrollPane(sideContent);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        sidebar.getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return sidebar;
    }

    // -----------------------------------------------------------------------
    // Center grid
    // -----------------------------------------------------------------------

    private static VBox buildCenterGrid() {
        VBox center = new VBox(10);
        center.setPadding(new Insets(20));

        Label gridTitle = new Label("CONNECTED STUDENTS");
        gridTitle.setStyle("-fx-font-size: 15px; -fx-text-fill: #888; -fx-font-weight: bold;");

        thumbnailGrid = new FlowPane(20, 20);
        thumbnailGrid.setPadding(new Insets(10));
        thumbnailGrid.setStyle("-fx-background-color: transparent;");

        ScrollPane scrollPane = new ScrollPane(thumbnailGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        center.getChildren().addAll(gridTitle, scrollPane);
        return center;
    }

    // -----------------------------------------------------------------------
    // Right panel (chat)
    // -----------------------------------------------------------------------

    private static VBox buildRightPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(20));
        panel.setPrefWidth(280);
        panel.setStyle("-fx-background-color: rgba(0,0,0,0.2);");

        Label chatTitle = new Label("💬 BROADCAST CHAT");
        chatTitle.setStyle("-fx-font-size: 13px; -fx-text-fill: #c39bd3; -fx-font-weight: bold;");

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.setStyle("-fx-control-inner-background: #0d0d1a; -fx-text-fill: #ccc; -fx-font-family: 'Consolas';");
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        HBox inputRow = new HBox(8);
        TextField msgField = new TextField();
        msgField.setPromptText("Type message...");
        msgField.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: white; -fx-prompt-text-fill: #555;");
        HBox.setHgrow(msgField, Priority.ALWAYS);

        Button sendBtn = new Button("SEND");
        sendBtn.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-weight: bold;");
        sendBtn.setOnAction(e -> {
            String msg = msgField.getText().trim();
            if (!msg.isEmpty()) {
                appendChat("[ADMIN]: " + msg);
                server.broadcast(new CommandPacket(CommandPacket.Type.MSG, "ADMIN", msg));
                msgField.clear();
            }
        });
        msgField.setOnAction(e -> sendBtn.fire());
        inputRow.getChildren().addAll(msgField, sendBtn);

        panel.getChildren().addAll(chatTitle, chatArea, inputRow);
        return panel;
    }

    // -----------------------------------------------------------------------
    // Bottom bar (command console + perf stats)
    // -----------------------------------------------------------------------

    private static VBox buildBottomBar() {
        // Performance stats
        perfBarLabel = new Label("CPU: --  |  RAM: --  |  Clients: 0  |  FPS: 0");
        perfBarLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 11px; -fx-font-family: 'Consolas';");

        HBox perfBar = new HBox(perfBarLabel);
        perfBar.setAlignment(Pos.CENTER_RIGHT);
        perfBar.setPadding(new Insets(4, 16, 4, 16));
        perfBar.setStyle("-fx-background-color: rgba(0,0,0,0.3);");

        // Command console
        HBox console = new HBox(12);
        console.setPadding(new Insets(12, 20, 12, 20));
        console.setAlignment(Pos.CENTER_LEFT);
        console.setStyle("-fx-background-color: rgba(0,0,0,0.45);");

        Label prompt = new Label("CMD >");
        prompt.setStyle("-fx-text-fill: #00ff00; -fx-font-family: 'Consolas'; -fx-font-size: 13px;");

        TextField cmdInput = new TextField();
        cmdInput.setPromptText("Execute shell command on all students...");
        cmdInput.setStyle("-fx-background-color: #0d0d1a; -fx-text-fill: #0f0; -fx-font-family: 'Consolas'; -fx-prompt-text-fill: #444;");
        HBox.setHgrow(cmdInput, Priority.ALWAYS);

        Button execAllBtn = new Button("ALL");
        execAllBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16;");
        execAllBtn.setOnAction(e -> {
            String cmd = cmdInput.getText().trim();
            if (!cmd.isEmpty()) {
                server.broadcast(new CommandPacket(CommandPacket.Type.SHELL, "ADMIN", cmd));
                appendChat("[CMD→ALL]: " + cmd);
                cmdInput.clear();
                AuditLogger.logCommand("admin", "ALL", cmd);
            }
        });
        cmdInput.setOnAction(e -> execAllBtn.fire());
        console.getChildren().addAll(prompt, cmdInput, execAllBtn);

        VBox bottom = new VBox(perfBar, console);
        return bottom;
    }

    // -----------------------------------------------------------------------
    // Performance stats timer
    // -----------------------------------------------------------------------

    private static void startPerfTimer() {
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            if (perfBarLabel != null) {
                String summary = PerformanceMonitor.getSummary(server.getClientCount());
                perfBarLabel.setText(summary);
            }
        }));
        t.setCycleCount(Animation.INDEFINITE);
        t.play();
    }

    // -----------------------------------------------------------------------
    // AI toggle
    // -----------------------------------------------------------------------

    private static void toggleAi(String adminName) {
        Config.aiEnabled = !Config.aiEnabled;
        String payload = Config.aiEnabled ? "ENABLE" : "DISABLE";
        server.broadcast(new CommandPacket(CommandPacket.Type.AI_TOGGLE, "ADMIN", payload));
        AuditLogger.logCommand(adminName, "ALL", "AI_TOGGLE → " + payload);
        updateAiButton();
        appendChat("[SYSTEM]: AI " + payload + "D globally by admin");
    }

    private static void updateAiButton() {
        if (aiToggleBtn == null) return;
        if (Config.aiEnabled) {
            aiToggleBtn.setText("🤖 DISABLE AI");
            aiToggleBtn.setStyle(aiToggleBtn.getStyle().replace("#27ae60", "#1a5276"));
        } else {
            aiToggleBtn.setText("🤖 ENABLE AI");
            aiToggleBtn.setStyle(aiToggleBtn.getStyle().replace("#1a5276", "#27ae60"));
        }
    }

    // -----------------------------------------------------------------------
    // Admin themes
    // -----------------------------------------------------------------------

    private static void applyAdminTheme(Region root, String theme) {
        adminTheme = theme;
        switch (theme) {
            case "dark":
                root.setStyle("-fx-background-color: linear-gradient(to bottom right, #0a0a1a, #12122e);");
                break;
            case "contrast":
                root.setStyle("-fx-background-color: linear-gradient(to bottom right, #000000, #1a1a1a);");
                break;
            case "steel":
                root.setStyle("-fx-background-color: linear-gradient(to bottom right, #0a1628, #14274e);");
                break;
            case "forest":
                root.setStyle("-fx-background-color: linear-gradient(to bottom right, #0a1a0a, #1a2e1a);");
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Student card management
    // -----------------------------------------------------------------------

    private static void addStudentCard(String name, Image screenshot) {
        VBox card = new VBox(8);
        card.setPrefWidth(240);
        card.setStyle(
                "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-background-radius: 14;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 4);" +
                "-fx-padding: 10;");
        card.setAlignment(Pos.CENTER);

        ImageView imgView = new ImageView();
        imgView.setFitWidth(280);
        imgView.setFitHeight(175);
        imgView.setPreserveRatio(false);
        imgView.setStyle("-fx-cursor: hand;");
        imgView.setOnMouseClicked(e -> openFullScreenView(name, imgView.getImage()));

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");

        HBox statusBox = new HBox(5);
        statusBox.setAlignment(Pos.CENTER);
        statusBox.getChildren().addAll(new Circle(4, Color.web("#2ecc71")),
                label("Connected", "-fx-text-fill: #888; -fx-font-size: 10px;"));

        HBox ctrlRow = new HBox(6);
        ctrlRow.setAlignment(Pos.CENTER);

        Button lockBtn = iconBtn("🔒", "#c0392b");
        lockBtn.setOnAction(e -> {
            server.sendToClient(name, new CommandPacket(CommandPacket.Type.LOCK, "ADMIN", "{}"));
            AuditLogger.logCommand("admin", name, "LOCK");
        });

        Button msgBtn  = iconBtn("💬", "#2980b9");
        msgBtn.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Message → " + name);
            d.setHeaderText(null);
            d.setContentText("Message:");
            d.showAndWait().ifPresent(msg -> {
                server.sendToClient(name, new CommandPacket(CommandPacket.Type.MSG, "ADMIN", msg));
                appendChat("[TO " + name + "]: " + msg);
                AuditLogger.logCommand("admin", name, "MSG: " + msg);
            });
        });

        Button cmdBtn  = iconBtn("⌨", "#7d3c98");
        cmdBtn.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog();
            d.setTitle("CMD → " + name);
            d.setHeaderText(null);
            d.setContentText("Command:");
            d.showAndWait().ifPresent(cmd -> {
                server.sendToClient(name, new CommandPacket(CommandPacket.Type.SHELL, "ADMIN", cmd));
                appendChat("[CMD→" + name + "]: " + cmd);
                AuditLogger.logCommand("admin", name, "CMD: " + cmd);
            });
        });

        ctrlRow.getChildren().addAll(lockBtn, msgBtn, cmdBtn);
        card.getChildren().addAll(imgView, nameLabel, statusBox, ctrlRow);

        thumbnailGrid.getChildren().add(card);
        studentCards.put(name, card);
        studentImages.put(name, imgView);
    }

    private static void updateStudentScreen(String clientName, String base64Image) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Image);
            Image image  = new Image(new ByteArrayInputStream(bytes));
            PerformanceMonitor.recordFrame();

            if (studentImages.containsKey(clientName)) {
                studentImages.get(clientName).setImage(image);
            } else {
                addStudentCard(clientName, image);
            }
        } catch (Exception e) {
            AuditLogger.logError("updateStudentScreen", e.getMessage());
        }
    }

    private static void removeStudentCard(String clientName) {
        VBox card = studentCards.remove(clientName);
        if (card != null) thumbnailGrid.getChildren().remove(card);
        studentImages.remove(clientName);
        appendChat("[SYSTEM]: " + clientName + " disconnected");
    }

    // -----------------------------------------------------------------------
    // Full-screen view
    // -----------------------------------------------------------------------

    private static void openFullScreenView(String studentName, Image screenshot) {
        if (screenshot == null) return;
        Stage fs = new Stage();
        fs.setTitle("👁 Viewing: " + studentName);

        ImageView fullView = new ImageView(screenshot);
        fullView.setPreserveRatio(true);
        fullView.fitWidthProperty().bind(fs.widthProperty());
        fullView.fitHeightProperty().bind(fs.heightProperty().subtract(40));

        Label info = new Label("📺 " + studentName + "  —  ESC or click to close");
        info.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8;");

        VBox fsRoot = new VBox(info, fullView);
        fsRoot.setStyle("-fx-background-color: #0a0a1a;");
        fsRoot.setAlignment(Pos.CENTER);

        Scene scene = new Scene(fsRoot, 1280, 820);
        scene.setOnMouseClicked(e -> fs.close());
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) fs.close(); });
        fs.setScene(scene);
        fs.show();

        Thread updThread = new Thread(() -> {
            while (fs.isShowing()) {
                try {
                    Thread.sleep(80);
                    ImageView iv = studentImages.get(studentName);
                    if (iv != null && iv.getImage() != null) {
                        Image latest = iv.getImage();
                        Platform.runLater(() -> fullView.setImage(latest));
                    }
                } catch (InterruptedException ignored) { break; }
            }
        });
        updThread.setDaemon(true);
        updThread.start();
    }

    // -----------------------------------------------------------------------
    // Screen share (admin → students)
    // -----------------------------------------------------------------------

    private static HBox buildScreenShareToggle() {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);

        Label statusLabel = new Label("● OFF");
        statusLabel.setStyle("-fx-text-fill: #888; -fx-font-weight: bold;");

        ToggleButton toggle = new ToggleButton("START");
        toggle.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 12;");
        toggle.setOnAction(e -> {
            screenSharing = toggle.isSelected();
            if (screenSharing) {
                toggle.setText("STOP");
                toggle.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 12;");
                statusLabel.setText("● LIVE");
                statusLabel.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                startAdminScreenShare();
            } else {
                toggle.setText("START");
                toggle.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 12;");
                statusLabel.setText("● OFF");
                statusLabel.setStyle("-fx-text-fill: #888; -fx-font-weight: bold;");
                stopAdminScreenShare();
            }
        });
        box.getChildren().addAll(statusLabel, toggle);
        return box;
    }

    private static void startAdminScreenShare() {
        ScreenCapture.startAsyncCapture();
        screenScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        screenScheduler.scheduleAtFixedRate(() -> {
            if (screenSharing) {
                String base64 = ScreenCapture.getLatestFrame();
                if (base64 != null)
                    server.broadcast(new CommandPacket(CommandPacket.Type.ADMIN_SCREEN, "ADMIN", base64));
            }
        }, 0, 25, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void stopAdminScreenShare() {
        ScreenCapture.stopAsyncCapture();
        if (screenScheduler != null) { screenScheduler.shutdown(); screenScheduler = null; }
    }

    // -----------------------------------------------------------------------
    // File / URL / Screenshot / Attendance actions
    // -----------------------------------------------------------------------

    private static void sendFilesToStudents(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Files to Send");
        java.util.List<File> files = fc.showOpenMultipleDialog(stage);
        if (files != null) {
            for (File file : files) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                    String b64   = Base64.getEncoder().encodeToString(bytes);
                    server.broadcast(new CommandPacket(CommandPacket.Type.FILE_DATA, "ADMIN",
                            file.getName() + "|" + b64));
                    appendChat("[FILE]: Sent " + file.getName());
                    AuditLogger.logCommand("admin", "ALL", "SEND FILE: " + file.getName());
                } catch (Exception e) {
                    appendChat("[ERROR]: " + file.getName() + " — " + e.getMessage());
                }
            }
        }
    }

    private static void captureAllScreenshots(Stage stage) {
        if (studentImages.isEmpty()) { appendChat("[SYSTEM]: No students connected"); return; }
        File dir = new File(System.getProperty("user.home"), "KingLab Screenshots");
        dir.mkdirs();
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
        for (Map.Entry<String, ImageView> entry : studentImages.entrySet()) {
            String name  = entry.getKey();
            Image  image = entry.getValue().getImage();
            if (image != null) {
                try {
                    File out = new File(dir, name + "_" + ts + ".png");
                    java.nio.file.Files.write(out.toPath(), imageToPngBytes(image));
                    appendChat("[SCREENSHOT]: Saved " + name);
                } catch (Exception e) {
                    appendChat("[ERROR]: " + name + " — " + e.getMessage());
                }
            }
        }
        appendChat("[SYSTEM]: Screenshots → " + dir.getAbsolutePath());
    }

    private static byte[] imageToPngBytes(Image image) throws Exception {
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                (int)image.getWidth(), (int)image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pr = image.getPixelReader();
        for (int y=0;y<(int)image.getHeight();y++) for (int x=0;x<(int)image.getWidth();x++) bi.setRGB(x,y,pr.getArgb(x,y));
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi,"png",baos);
        return baos.toByteArray();
    }

    private static void openUrlOnAll() {
        TextInputDialog d = new TextInputDialog("https://");
        d.setTitle("Open URL on All Students");
        d.setHeaderText(null);
        d.setContentText("URL:");
        d.showAndWait().ifPresent(url -> {
            if (!url.trim().isEmpty()) {
                server.broadcast(new CommandPacket(CommandPacket.Type.OPEN_URL, "ADMIN", url));
                appendChat("[SYSTEM]: Opening URL: " + url);
                AuditLogger.logCommand("admin", "ALL", "OPEN_URL: " + url);
            }
        });
    }

    private static void generateAttendanceManually(Stage stage) {
        java.util.List<String> files = AttendanceTracker.generateAttendanceCSV();
        Alert alert = new Alert(files.isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.INFORMATION);
        alert.setTitle("Attendance Export");
        if (files.isEmpty()) {
            alert.setHeaderText("No students connected yet.");
        } else {
            StringBuilder sb = new StringBuilder("Exported:\n");
            files.forEach(f -> sb.append("📄 ").append(f).append("\n"));
            alert.setHeaderText("Attendance Generated!");
            alert.setContentText(sb.toString());
        }
        alert.showAndWait();
    }

    // -----------------------------------------------------------------------
    // Utility builders
    // -----------------------------------------------------------------------

    private static void appendChat(String msg) {
        if (chatArea != null) {
            chatArea.appendText(msg + "\n");
            chatArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private static VBox sectionBox(String title, javafx.scene.Node... nodes) {
        VBox box = new VBox(8);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 10; -fx-padding: 12;");
        Label lbl = new Label(title);
        lbl.setStyle("-fx-text-fill: #555; -fx-font-size: 10px; -fx-font-weight: bold;");
        box.getChildren().add(lbl);
        for (javafx.scene.Node n : nodes) box.getChildren().add(n);
        return box;
    }

    private static Button styledBtn(String text, String color, Runnable action) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold; " +
                "-fx-background-radius: 8; -fx-padding: 10 12; -fx-cursor: hand;");
        btn.setOnAction(e -> action.run());
        btn.setOnMouseEntered(e -> btn.setOpacity(0.82));
        btn.setOnMouseExited(e  -> btn.setOpacity(1.0));
        return btn;
    }

    private static Button iconBtn(String icon, String color) {
        Button btn = new Button(icon);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-background-radius: 6; -fx-cursor: hand;");
        return btn;
    }

    private static Label label(String text, String style) {
        Label l = new Label(text);
        l.setStyle(style);
        return l;
    }
}
