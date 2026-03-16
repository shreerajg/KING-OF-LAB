package com.ghost.ui;

import com.ghost.ai.OllamaService;
import com.ghost.database.User;
import com.ghost.net.CommandPacket;
import com.ghost.net.DiscoveryService;
import com.ghost.net.GhostServer;
import com.ghost.util.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.*;
import javafx.util.Duration;
import java.io.*;
import java.util.*;

/**
 * King of Lab — Admin Dashboard v3 (Phase 3).
 * Removed: Timer, Poll, Session.
 * Improved: Premium UI, brighter student cards, more practical UX.
 */
public class AdminDashboard {

    private static GhostServer      server;
    private static DiscoveryService discoveryService;
    private static FlowPane         thumbnailGrid;
    private static TextArea         chatArea;
    private static final Map<String, StackPane> studentCards     = new LinkedHashMap<>();
    private static final Map<String, ImageView> studentImages    = new HashMap<>();
    private static final Map<String, Label>     handRaisedLabels = new HashMap<>();
    private static final Map<String, Circle>    statusDots       = new HashMap<>();

    private static final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(AdminDashboard.class);
    private static String adminTheme = prefs.get("adminTheme", "command");
    private static Label  perfBarLabel;
    private static Button aiToggleBtn;
    private static Label  studentCountLabel;
    private static Label  connectedLabel;
    
    // NEW UI Components
    private static ListView<String> activityFeed;
    private static TabPane          bottomTabPane;

    // Premium Color System
    public static final String BG_COLOR     = "#0A0F1F";
    public static final String PANEL_COLOR  = "#121A33";
    public static final String PRIMARY_COLOR= "#7C5CFF";
    public static final String ACCENT_COLOR = "#00D1FF";
    public static final String SUCCESS_COLOR= "#28D17C";
    public static final String WARNING_COLOR= "#FFC857";
    public static final String DANGER_COLOR = "#FF4D4D";


    // -----------------------------------------------------------------------
    // ADMIN THEMES
    // -----------------------------------------------------------------------
    private static final Object[][] ADMIN_THEMES = {
        {"command", "🖥 Command Center", "theme_admin_command.png", null},
        {"dark",    "🌑 Dark",           null, "#07071a, #0e0e24"},
        {"galaxy",  "🌌 Galaxy",         "theme_galaxy.png",        null},
        {"steel",   "🔵 Blue Steel",     null, "#060e1c, #0e1f3a"},
        {"forest",  "🌲 Forest Night",   null, "#06100a, #0d2015"},
    };

    // -----------------------------------------------------------------------
    // SHOW
    // -----------------------------------------------------------------------

    public static void show(Stage stage, User user) {
        if (server == null) {
            server = new GhostServer();
            server.setScreenListener(new GhostServer.ScreenUpdateListener() {
                @Override public void onScreenUpdate(String name, String base64) {
                    Platform.runLater(() -> updateStudentScreen(name, base64));
                }
                @Override public void onShellOutput(String name, String output) {
                    Platform.runLater(() -> appendChat("--- " + name + " ---\n" + output));
                }
            });
            server.setStatusListener(new GhostServer.ClientStatusListener() {
                @Override public void onClientConnected(String name) {
                    Platform.runLater(() -> updateCountLabel());
                }
                @Override public void onClientDisconnected(String name) {
                    Platform.runLater(() -> { removeStudentCard(name); updateCountLabel(); appendActivity("DISCONNECTED", name + " left the session"); });
                }
            });
            server.setExtendedListener((type, sender, payload) -> Platform.runLater(() -> {
                if (type == CommandPacket.Type.RAISE_HAND) {
                    updateHandRaised(sender, "UP".equals(payload));
                }
            }));
            server.start();
            discoveryService = new DiscoveryService();
            discoveryService.startBroadcasting();
            AuditLogger.logSystem("King of Lab Admin started");
        }

        // Root StackPane (for bg image + overlay)
        StackPane rootStack = new StackPane();

        // Background image layer
        ImageView bgImg = new ImageView();
        bgImg.setPreserveRatio(false);
        bgImg.fitWidthProperty().bind(rootStack.widthProperty());
        bgImg.fitHeightProperty().bind(rootStack.heightProperty());
        bgImg.setOpacity(0.55); // subtle tinted bg
        loadAdminBgImage(bgImg, adminTheme);

        // Main layout on top
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG_COLOR + ";"); // Premium solid fallback

        applyAdminBg(root, rootStack, bgImg, adminTheme);

        // TOP: Status Bar + Command Ribbon
        VBox topSection = buildTopSection(stage, user, root, rootStack, bgImg);
        root.setTop(topSection);

        // CENTER: Student Monitor Grid
        root.setCenter(buildCenterGrid());

        // BOTTOM: Activity Feed + Chat/Terminal
        root.setBottom(buildBottomPanels(user));

        rootStack.getChildren().addAll(bgImg, root);

        Scene scene = new Scene(rootStack, 1280, 800);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — Command Center: " + user.getUsername());
        stage.setMaximized(true);

        SystemTrayManager.init(stage, "ADMIN", user);
        stage.setOnCloseRequest(e -> {
            e.consume();
            AttendanceTracker.generateAttendanceCSV().forEach(f -> appendChat("[ATTENDANCE]: " + f));
            AuditLogger.logSystem("Admin dashboard closed");
            SystemTrayManager.hideWindow();
        });
        stage.show();
        startPerfTimer();
    }

    // -----------------------------------------------------------------------
    // TOP SECTION (GLOBAL STATUS BAR & COMMAND RIBBON)
    // -----------------------------------------------------------------------

    private static VBox buildTopSection(Stage stage, User user, BorderPane root,
                                     StackPane rootStack, ImageView bgImg) {
        VBox topSection = new VBox(0);

        // 1. GLOBAL STATUS BAR
        HBox statusBar = new HBox(20);
        statusBar.setPrefHeight(44);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(0, 20, 0, 20));
        statusBar.setStyle(
                "-fx-background-color: rgba(18, 26, 51, 0.75);" +
                "-fx-border-color: rgba(0, 209, 255, 0.4);" +
                "-fx-border-width: 0 0 1 0;");
                
        Label logo = new Label("👑 KING OF LAB");
        logo.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: white; -fx-letter-spacing: 2px;");
        logo.setEffect(new DropShadow(12, Color.web(ACCENT_COLOR)));
        
        Label sessionTimer = new Label("SESSION: ACTIVE");
        sessionTimer.setStyle("-fx-text-fill: " + ACCENT_COLOR + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        
        Region spacer1 = new Region(); HBox.setHgrow(spacer1, Priority.ALWAYS);
        
        connectedLabel = new Label("● 0 Students");
        connectedLabel.setStyle("-fx-text-fill: " + SUCCESS_COLOR + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        
        Label aiStatusLabel = new Label("AI: " + (Config.aiEnabled ? "ACTIVE" : "OFF"));
        aiStatusLabel.setStyle("-fx-text-fill: " + PRIMARY_COLOR + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        
        Label netHealth = new Label("NET: 100%");
        netHealth.setStyle("-fx-text-fill: " + SUCCESS_COLOR + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        
        Region spacer2 = new Region(); HBox.setHgrow(spacer2, Priority.ALWAYS);
        
        Circle avatar = new Circle(12, Color.web(PRIMARY_COLOR));
        Label uLabel = new Label(user.getUsername().toUpperCase());
        uLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;");
        
        ComboBox<String> themeBox = new ComboBox<>();
        for (Object[] t : ADMIN_THEMES) { themeBox.getItems().add((String) t[1]); if (t[0].equals(adminTheme)) themeBox.setValue((String) t[1]); }
        if (themeBox.getValue() == null) themeBox.setValue("🖥 Command Center");
        themeBox.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: white; -fx-font-size: 10px;");
        themeBox.setOnAction(e -> {
            int i = themeBox.getSelectionModel().getSelectedIndex();
            if (i >= 0) { adminTheme = (String) ADMIN_THEMES[i][0]; prefs.put("adminTheme", adminTheme); applyAdminBg(root, rootStack, bgImg, adminTheme); }
        });
        
        Button logoutBtn = new Button("LOGOUT");
        logoutBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + DANGER_COLOR + "; -fx-font-weight: bold; -fx-cursor: hand;");
        logoutBtn.setOnAction(e -> { SystemTrayManager.hideWindow(); System.exit(0); });
        
        statusBar.getChildren().addAll(logo, sessionTimer, spacer1, connectedLabel, aiStatusLabel, netHealth, spacer2, avatar, uLabel, themeBox, logoutBtn);

        // 2. COMMAND RIBBON
        HBox ribbon = new HBox(30);
        ribbon.setPadding(new Insets(12, 25, 12, 25));
        ribbon.setAlignment(Pos.CENTER_LEFT);
        ribbon.setStyle("-fx-background-color: rgba(10, 15, 31, 0.5); -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 0 1 0;");
        
        ribbon.getChildren().addAll(
            buildCmdGroup("CLASS CONTROL", 
                ribbonBtn("🔒 Lock",   WARNING_COLOR, () -> { server.broadcast(pkt(CommandPacket.Type.LOCK, "{}")); auditLog(user, "ALL", "LOCK ALL"); }),
                ribbonBtn("🔓 Unlock", SUCCESS_COLOR, () -> { server.broadcast(pkt(CommandPacket.Type.UNLOCK, "{}")); auditLog(user, "ALL", "UNLOCK ALL"); })
            ),
            buildCmdGroup("INTERNET", 
                ribbonBtn("🚫 Block", DANGER_COLOR,  () -> { HostsFileManager.blockSites(); auditLog(user, "ALL", "BLOCK INTERNET"); }),
                ribbonBtn("✅ Restore",SUCCESS_COLOR, () -> { HostsFileManager.restoreHostsFile(); auditLog(user, "ALL", "RESTORE"); })
            ),
            buildCmdGroup("POWER", 
                ribbonBtn("⏻ Shutdown", DANGER_COLOR,  () -> { server.broadcast(pkt(CommandPacket.Type.SHUTDOWN, "{}")); auditLog(user, "ALL", "SHUTDOWN"); }),
                ribbonBtn("🔄 Restart",  WARNING_COLOR, () -> { server.broadcast(pkt(CommandPacket.Type.RESTART, "{}")); auditLog(user, "ALL", "RESTART"); })
            ),
            buildCmdGroup("AI & SYSTEM", 
                ribbonBtn("🗑 Reset AI", PRIMARY_COLOR, () -> {
                    OllamaService.clearAllHistories();
                    server.broadcast(pkt(CommandPacket.Type.AI_CLEAR_HISTORY, "ALL"));
                    auditLog(user, "ALL", "AI_CLEAR_HISTORY");
                }),
                ribbonBtn("🤖 Toggle AI", ACCENT_COLOR, () -> toggleAi(user.getUsername())),
                buildStreamModeBox()
            ),
            buildCmdGroup("TOOLS", 
                ribbonBtn("📸 Screenshot", ACCENT_COLOR,  () -> captureAllScreenshots(stage)),
                ribbonBtn("📁 Send File",  PRIMARY_COLOR, () -> sendFilesToStudents(stage)),
                ribbonBtn("🌐 Open URL",   SUCCESS_COLOR, () -> openUrlOnAll()),
                ribbonBtn("📋 Attend",     WARNING_COLOR, () -> generateAttendanceManually(stage))
            )
        );
        
        ScrollPane scrollRibbon = new ScrollPane(ribbon);
        scrollRibbon.setFitToHeight(true);
        scrollRibbon.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollRibbon.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-padding: 0;");
        
        topSection.getChildren().addAll(statusBar, scrollRibbon);
        return topSection;
    }

    private static VBox buildCmdGroup(String title, javafx.scene.Node... btns) {
        VBox group = new VBox(8);
        group.setAlignment(Pos.CENTER);
        Label lbl = new Label(title);
        lbl.setStyle("-fx-text-fill: #5d6d7e; -fx-font-size: 10px; -fx-font-weight: bold; -fx-letter-spacing: 1.5px;");
        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER);
        btnRow.getChildren().addAll(btns);
        group.getChildren().addAll(lbl, btnRow);
        return group;
    }

    private static Button ribbonBtn(String text, String glowColor, Runnable action) {
        Button btn = new Button(text);
        btn.setStyle(
            "-fx-background-color: rgba(255,255,255,0.06);" +
            "-fx-text-fill: white;" + "-fx-font-size: 12px;" + "-fx-font-weight: bold;" +
            "-fx-padding: 7 14;" + "-fx-background-radius: 20;" +
            "-fx-border-color: " + glowColor + ";" + "-fx-border-radius: 20;" + "-fx-border-width: 1;"
        );
        btn.setCursor(javafx.scene.Cursor.HAND);
        
        DropShadow glow = new DropShadow(8, Color.web(glowColor, 0.5));
        btn.setEffect(glow);
        
        btn.setOnMouseEntered(e -> { glow.setRadius(15); glow.setColor(Color.web(glowColor, 0.8)); });
        btn.setOnMouseExited(e -> { glow.setRadius(8); glow.setColor(Color.web(glowColor, 0.5)); });
        btn.setOnAction(e -> { action.run(); appendActivity("COMMAND", "Executed: " + text); });
        return btn;
    }

    private static ComboBox<String> buildStreamModeBox() {
        ComboBox<String> streamModeBox = new ComboBox<>();
        streamModeBox.getItems().addAll("LEGACY_CPU", "ULTRA_WEBRTC");
        streamModeBox.setValue("LEGACY_CPU");
        streamModeBox.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 20;");
        streamModeBox.setOnAction(e -> {
            String mode = streamModeBox.getValue();
            server.broadcast(new CommandPacket(CommandPacket.Type.STREAM_MODE, "ADMIN", mode));
            appendActivity("PIPELINE", "Stream Mode set to " + mode);
            AuditLogger.logSystem("Stream Mode switched to " + mode);
        });
        return streamModeBox;
    }

    private static void appendActivity(String type, String msg) {
        if (activityFeed != null) {
            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
            Platform.runLater(() -> {
                activityFeed.getItems().add("[" + time + "] [" + type + "] " + msg);
                activityFeed.scrollTo(activityFeed.getItems().size() - 1);
            });
        }
        appendChat("[" + type + "] " + msg);
    }

    // -----------------------------------------------------------------------
    // CENTER GRID — student thumbnails
    // -----------------------------------------------------------------------

    private static VBox buildCenterGrid() {
        VBox center = new VBox(12);
        center.setPadding(new Insets(18, 18, 0, 18));
        center.setStyle("-fx-background-color: transparent;");

        // Stats ribbon at top
        HBox stats = new HBox(24);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.setPadding(new Insets(10, 16, 10, 16));
        stats.setStyle("-fx-background-color: rgba(0,0,0,0.3); -fx-background-radius: 12;");

        studentCountLabel = new Label("👥  0 Students Connected");
        studentCountLabel.setStyle("-fx-text-fill: #9b59b6; -fx-font-weight: bold; -fx-font-size: 14px;");

        Label hint = new Label("Click any screen to view fullscreen  •  Per-student controls below each card");
        hint.setStyle("-fx-text-fill: #444; -fx-font-size: 10px;");

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        stats.getChildren().addAll(studentCountLabel, sp, hint);

        // Grid
        thumbnailGrid = new FlowPane(16, 16);
        thumbnailGrid.setPadding(new Insets(10));
        thumbnailGrid.setStyle("-fx-background-color: transparent;");

        ScrollPane scroll = new ScrollPane(thumbnailGrid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        center.getChildren().addAll(stats, scroll);
        VBox.setVgrow(center, Priority.ALWAYS);
        return center;
    }

    // -----------------------------------------------------------------------
    // BOTTOM PANELS (ACTIVITY FEED & CHAT/TERMINAL)
    // -----------------------------------------------------------------------

    private static HBox buildBottomPanels(User admin) {
        HBox bottomBar = new HBox(15);
        bottomBar.setPadding(new Insets(15));
        bottomBar.setPrefHeight(250);
        bottomBar.setStyle("-fx-background-color: rgba(10, 15, 31, 0.85); -fx-border-color: rgba(0, 209, 255, 0.2); -fx-border-width: 1 0 0 0;");

        // LEFT: Activity Feed
        VBox activityPanel = new VBox(8);
        activityPanel.setPrefWidth(350);
        activityPanel.setMaxWidth(350);
        
        Label actTitle = new Label("⚡ ACTIVITY FEED");
        actTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ACCENT_COLOR + "; -fx-font-weight: bold; -fx-letter-spacing: 1px;");
        
        activityFeed = new ListView<>();
        activityFeed.setStyle(
            "-fx-control-inner-background: " + BG_COLOR + ";" +
            "-fx-background-color: " + BG_COLOR + ";" +
            "-fx-text-fill: white;" + "-fx-font-family: 'Consolas';" + "-fx-font-size: 11px;" +
            "-fx-border-color: rgba(255,255,255,0.05); -fx-border-radius: 4;"
        );
        VBox.setVgrow(activityFeed, Priority.ALWAYS);
        activityPanel.getChildren().addAll(actTitle, activityFeed);

        // RIGHT: TabPane (Chat & Terminal)
        VBox rightPanel = new VBox(8);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);

        bottomTabPane = new TabPane();
        bottomTabPane.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(bottomTabPane, Priority.ALWAYS);

        // TAB 1: Chat
        Tab chatTab = new Tab("💬 Broadcast Chat");
        chatTab.setClosable(false);
        VBox chatBox = new VBox(8);
        chatBox.setPadding(new Insets(8, 0, 0, 0));
        
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.setStyle("-fx-control-inner-background: #05050f; -fx-text-fill: #aaa; -fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        VBox.setVgrow(chatArea, Priority.ALWAYS);
        
        TextField msgField = new TextField();
        msgField.setPromptText("Type message to all students...");
        msgField.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-text-fill: white; -fx-background-radius: 6;");
        Button sendBtn = new Button("SEND");
        sendBtn.setStyle("-fx-background-color: " + PRIMARY_COLOR + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
        sendBtn.setOnAction(e -> {
            String msg = msgField.getText().trim();
            if (!msg.isEmpty()) {
                server.broadcast(new CommandPacket(CommandPacket.Type.MSG, "ADMIN", msg));
                appendChat("[TO ALL]: " + msg);
                appendActivity("MSG", "Sent message to all students");
                msgField.clear();
            }
        });
        msgField.setOnAction(e -> sendBtn.fire());
        HBox chatInputRow = new HBox(8, msgField, sendBtn);
        HBox.setHgrow(msgField, Priority.ALWAYS);
        chatBox.getChildren().addAll(chatArea, chatInputRow);
        chatTab.setContent(chatBox);

        // TAB 2: Terminal
        Tab termTab = new Tab("⚡ Command Terminal");
        termTab.setClosable(false);
        VBox termBox = new VBox(8);
        termBox.setPadding(new Insets(8, 0, 0, 0));
        
        TextArea termOutput = new TextArea();
        termOutput.setEditable(false);
        termOutput.setStyle("-fx-control-inner-background: #040410; -fx-text-fill: " + SUCCESS_COLOR + "; -fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        VBox.setVgrow(termOutput, Priority.ALWAYS);
        
        HBox shell = new HBox(10);
        shell.setAlignment(Pos.CENTER_LEFT);
        Label prompt = new Label("CMD ▶");
        prompt.setStyle("-fx-text-fill: " + SUCCESS_COLOR + "; -fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-font-weight: bold;");
        TextField cmdInput = new TextField();
        cmdInput.setPromptText("Execute shell command on ALL students...");
        cmdInput.setStyle("-fx-background-color: #040410; -fx-text-fill: " + SUCCESS_COLOR + "; -fx-font-family: 'Consolas'; -fx-background-radius: 6;");
        HBox.setHgrow(cmdInput, Priority.ALWAYS);
        Button execBtn = new Button("RUN ALL");
        execBtn.setStyle("-fx-background-color: " + DANGER_COLOR + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
        execBtn.setOnAction(e -> {
            String cmd = cmdInput.getText().trim();
            if (!cmd.isEmpty()) {
                server.broadcast(new CommandPacket(CommandPacket.Type.SHELL, "ADMIN", cmd));
                termOutput.appendText("[CMD→ALL]: " + cmd + "\n");
                appendActivity("SHELL", "Broadcasted command: " + cmd);
                cmdInput.clear();
            }
        });
        cmdInput.setOnAction(e -> execBtn.fire());
        shell.getChildren().addAll(prompt, cmdInput, execBtn);
        termBox.getChildren().addAll(termOutput, shell);
        termTab.setContent(termBox);

        bottomTabPane.getTabs().addAll(chatTab, termTab);
        
        // Perf bar at the bottom right
        perfBarLabel = new Label();
        perfBarLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 10px; -fx-font-family: 'Consolas';");
        HBox perfBar = new HBox(perfBarLabel);
        perfBar.setAlignment(Pos.CENTER_RIGHT);
        
        rightPanel.getChildren().addAll(bottomTabPane, perfBar);
        
        bottomBar.getChildren().addAll(activityPanel, rightPanel);
        return bottomBar;
    }

    // -----------------------------------------------------------------------
    // STUDENT CARDS (PREMIUM HOVER AND GLOW)
    // -----------------------------------------------------------------------

    private static void addStudentCard(String name, Image screenshot) {
        if (studentCards.containsKey(name)) return;

        StackPane card = new StackPane();
        card.setPrefSize(250, 160);
        card.setStyle(
                "-fx-background-color: " + PANEL_COLOR + ";" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: rgba(0, 209, 255, 0.3);" +
                "-fx-border-radius: 12;" +
                "-fx-border-width: 1;");

        DropShadow cardGlow = new DropShadow(15, Color.web(ACCENT_COLOR, 0.2));
        card.setEffect(cardGlow);

        card.setTranslateY(30); card.setOpacity(0);
        TranslateTransition tt = new TranslateTransition(Duration.millis(300), card);
        tt.setToY(0);
        FadeTransition ft = new FadeTransition(Duration.millis(300), card);
        ft.setToValue(1);
        tt.play(); ft.play();

        ImageView imgView = new ImageView();
        imgView.setFitWidth(248); imgView.setFitHeight(158);
        imgView.setPreserveRatio(false);
        if (screenshot != null) imgView.setImage(screenshot);
        
        ColorAdjust vivid = new ColorAdjust();
        vivid.setBrightness(0.05); vivid.setSaturation(0.2); vivid.setContrast(0.05);
        imgView.setEffect(vivid);
        
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(248, 158);
        clip.setArcWidth(18); clip.setArcHeight(18);
        imgView.setClip(clip);

        HBox topBar = new HBox(6);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(6, 10, 6, 10));
        topBar.setStyle("-fx-background-color: rgba(10, 15, 31, 0.65); -fx-background-radius: 12 12 0 0;");
        Circle dot = new Circle(4, Color.web(SUCCESS_COLOR));
        statusDots.put(name, dot);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");
        Label handLbl = new Label("");
        handLbl.setStyle("-fx-font-size: 12px;");
        handRaisedLabels.put(name, handLbl);
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Label pingLbl = new Label("12ms 🟢");
        pingLbl.setStyle("-fx-text-fill: #aaa; -fx-font-size: 10px;");
        topBar.getChildren().addAll(dot, nameLabel, sp, handLbl, pingLbl);
        StackPane.setAlignment(topBar, Pos.TOP_CENTER);

        HBox actionOverlay = new HBox(8);
        actionOverlay.setAlignment(Pos.CENTER);
        actionOverlay.setStyle("-fx-background-color: rgba(10, 15, 31, 0.85); -fx-background-radius: 0 0 12 12;");
        actionOverlay.setPadding(new Insets(10));
        actionOverlay.setOpacity(0);
        actionOverlay.getChildren().addAll(
                cardBtn("🔒", WARNING_COLOR, "Lock", () -> { server.sendToClient(name, pkt(CommandPacket.Type.LOCK, "{}")); appendActivity("LOCK", name); }),
                cardBtn("💬", ACCENT_COLOR, "Chat", () -> promptMessage(name)),
                cardBtn("⚡", PRIMARY_COLOR, "Command", () -> promptCommand(name)),
                cardBtn("👁", SUCCESS_COLOR, "Focus", () -> openFullScreenView(name, imgView.getImage()))
        );
        StackPane.setAlignment(actionOverlay, Pos.BOTTOM_CENTER);

        card.setOnMouseEntered(e -> {
            ScaleTransition sc = new ScaleTransition(Duration.millis(200), card);
            sc.setToX(1.05); sc.setToY(1.05); sc.play();
            cardGlow.setColor(Color.web(ACCENT_COLOR, 0.6));
            cardGlow.setRadius(25);
            FadeTransition fo = new FadeTransition(Duration.millis(200), actionOverlay);
            fo.setToValue(1); fo.play();
        });
        card.setOnMouseExited(e -> {
            ScaleTransition sc = new ScaleTransition(Duration.millis(200), card);
            sc.setToX(1.0); sc.setToY(1.0); sc.play();
            cardGlow.setColor(Color.web(ACCENT_COLOR, 0.2));
            cardGlow.setRadius(15);
            FadeTransition fo = new FadeTransition(Duration.millis(200), actionOverlay);
            fo.setToValue(0); fo.play();
        });

        imgView.setOnMouseClicked(e -> openFullScreenView(name, imgView.getImage()));

        card.getChildren().addAll(imgView, topBar, actionOverlay);
        thumbnailGrid.getChildren().add(card);
        studentCards.put(name, card);
        studentImages.put(name, imgView);
        updateCountLabel();
    }

    private static void updateHandRaised(String student, boolean up) {
        Label lbl = handRaisedLabels.get(student);
        if (lbl != null) lbl.setText(up ? "✋" : "");
        if (up) {
            appendActivity("HAND RAISED", student + " has a question!");
            StackPane card = studentCards.get(student);
            if (card != null) {
                FadeTransition flash = new FadeTransition(Duration.millis(200), card);
                flash.setFromValue(1.0); flash.setToValue(0.5);
                flash.setCycleCount(6); flash.setAutoReverse(true);
                flash.setOnFinished(e -> card.setOpacity(1.0));
                flash.play();
            }
        }
    }

    // -----------------------------------------------------------------------
    // WebRTC / Lazy Rendering Scaffold
    // -----------------------------------------------------------------------

    public static void checkThumbnailVisibility() {
        // Scaffold for Lazy Rendering
    }

    private static void updateStudentScreen(String name, String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            Image image  = new Image(new ByteArrayInputStream(bytes));
            PerformanceMonitor.recordFrame();
            if (studentImages.containsKey(name)) {
                studentImages.get(name).setImage(image);
            } else {
                addStudentCard(name, image);
            }
        } catch (Exception e) {
            AuditLogger.logError("updateStudentScreen", e.getMessage());
        }
    }

    private static void removeStudentCard(String name) {
        StackPane card = studentCards.remove(name);
        if (card != null) {
            FadeTransition ft = new FadeTransition(Duration.millis(280), card);
            ft.setToValue(0);
            ft.setOnFinished(e -> thumbnailGrid.getChildren().remove(card));
            ft.play();
        }
        studentImages.remove(name);
        handRaisedLabels.remove(name);
        statusDots.remove(name);
        appendActivity("SYSTEM", name + " disconnected");
        updateCountLabel();
    }

    private static void updateCountLabel() {
        int n = studentCards.size();
        if (studentCountLabel != null)
            studentCountLabel.setText("👥  " + n + " Student" + (n != 1 ? "s" : "") + " Connected");
        if (connectedLabel != null) {
            connectedLabel.setText("● " + n + " online");
            connectedLabel.setStyle("-fx-text-fill: " + (n>0?"#2ecc71":"#888") + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        }
    }

    // -----------------------------------------------------------------------
    // FULLSCREEN VIEW
    // -----------------------------------------------------------------------

    private static void openFullScreenView(String studentName, Image screenshot) {
        if (screenshot == null) return;
        Stage fs = new Stage();
        fs.initStyle(StageStyle.UNDECORATED); // Cinematic full screen look
        fs.setTitle("👁 FOCUS: " + studentName);

        ImageView fullView = new ImageView(screenshot);
        fullView.setPreserveRatio(true);
        fullView.fitWidthProperty().bind(fs.widthProperty().multiply(0.9));
        fullView.fitHeightProperty().bind(fs.heightProperty().multiply(0.9));
        
        // 400ms entrance animation
        fullView.setOpacity(0);
        fullView.setScaleX(0.8); fullView.setScaleY(0.8);
        FadeTransition ft = new FadeTransition(Duration.millis(400), fullView);
        ft.setToValue(1);
        ScaleTransition st = new ScaleTransition(Duration.millis(400), fullView);
        st.setToX(1.0); st.setToY(1.0);
        ft.play(); st.play();

        DropShadow shadow = new DropShadow(40, Color.web(ACCENT_COLOR, 0.4));
        fullView.setEffect(shadow);

        HBox toolbar = new HBox(15);
        toolbar.setAlignment(Pos.CENTER);
        toolbar.setPadding(new Insets(15, 25, 15, 25));
        toolbar.setStyle("-fx-background-color: rgba(10, 15, 31, 0.85); -fx-background-radius: 30; -fx-border-color: rgba(0, 209, 255, 0.3); -fx-border-radius: 30; -fx-border-width: 1;");
        
        toolbar.getChildren().addAll(
                cardBtn("🔒 Lock", WARNING_COLOR, "Lock screen", () -> { server.sendToClient(studentName, pkt(CommandPacket.Type.LOCK, "{}")); appendActivity("LOCK", studentName); }),
                cardBtn("💬 Chat", ACCENT_COLOR, "Message", () -> promptMessage(studentName)),
                cardBtn("⚡ Terminal", PRIMARY_COLOR, "Shell", () -> promptCommand(studentName)),
                cardBtn("⏻ Shutdown", DANGER_COLOR, "Shutdown PC", () -> { server.sendToClient(studentName, pkt(CommandPacket.Type.SHUTDOWN, "{}")); appendActivity("SHUTDOWN", studentName); }),
                cardBtn("🗑 AI Reset", PRIMARY_COLOR, "Reset AI", () -> { OllamaService.clearHistory(studentName); server.sendToClient(studentName, pkt(CommandPacket.Type.AI_CLEAR_HISTORY, studentName)); appendActivity("AI CLEAR", studentName); }),
                cardBtn("✕ Close Focus", "#444", "Close", () -> fs.close())
        );

        toolbar.setTranslateY(50);
        toolbar.setOpacity(0);
        TranslateTransition ttb = new TranslateTransition(Duration.millis(400), toolbar);
        ttb.setToY(0); ttb.setDelay(Duration.millis(200));
        FadeTransition ftb = new FadeTransition(Duration.millis(400), toolbar);
        ftb.setToValue(1); ftb.setDelay(Duration.millis(200));
        ttb.play(); ftb.play();

        StackPane fsRoot = new StackPane(fullView, toolbar);
        StackPane.setAlignment(toolbar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toolbar, new Insets(0, 0, 40, 0));
        fsRoot.setStyle("-fx-background-color: rgba(3, 3, 7, 0.95);");
        
        Scene scene = new Scene(fsRoot, 1280, 820);
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) fs.close(); });
        fs.setScene(scene); 
        fs.setFullScreen(true); // real cinematic mode
        fs.setFullScreenExitHint("");
        fs.show();

        Thread t = new Thread(() -> {
            while (fs.isShowing()) {
                try {
                    Thread.sleep(60);
                    ImageView iv = studentImages.get(studentName);
                    if (iv != null && iv.getImage() != null)
                        Platform.runLater(() -> fullView.setImage(iv.getImage()));
                } catch (InterruptedException ignored) { break; }
            }
        });
        t.setDaemon(true); t.start();
    }



    // -----------------------------------------------------------------------
    // AI TOGGLE
    // -----------------------------------------------------------------------

    private static void toggleAi(String adminName) {
        Config.aiEnabled = !Config.aiEnabled;
        String payload = Config.aiEnabled ? "ENABLE" : "DISABLE";
        server.broadcast(new CommandPacket(CommandPacket.Type.AI_TOGGLE, "ADMIN", payload));
        AuditLogger.logCommand(adminName, "ALL", "AI_TOGGLE → " + payload);
        if (aiToggleBtn != null) {
            aiToggleBtn.setText(Config.aiEnabled ? "🤖 Disable AI Assistance" : "✅ Enable AI Assistance");
            aiToggleBtn.setStyle("-fx-background-color: " + (Config.aiEnabled ? "#1a5276" : "#1e8449") +
                    "; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 10; -fx-cursor: hand;");
        }
        appendChat("[SYSTEM]: AI " + payload + "D");
    }

    // -----------------------------------------------------------------------
    // THEME
    // -----------------------------------------------------------------------

    private static void applyAdminBg(BorderPane root, StackPane rootStack, ImageView bgImg, String theme) {
        adminTheme = theme;
        for (Object[] t : ADMIN_THEMES) {
            if (t[0].equals(theme)) {
                String imgFile  = (String) t[2];
                String gradient = (String) t[3];
                if (imgFile != null) {
                    loadAdminBgImage(bgImg, theme);
                    rootStack.setStyle("-fx-background-color: #07070a;");
                    root.setStyle("-fx-background-color: transparent;");
                } else {
                    bgImg.setImage(null);
                    rootStack.setStyle("-fx-background-color: linear-gradient(to bottom right, " + gradient + ");");
                    root.setStyle("-fx-background-color: transparent;");
                }
                return;
            }
        }
    }

    private static void loadAdminBgImage(ImageView bgImg, String theme) {
        for (Object[] t : ADMIN_THEMES) {
            if (t[0].equals(theme) && t[2] != null) {
                try {
                    java.net.URL url = AdminDashboard.class.getResource("/themes/" + t[2]);
                    if (url != null) {
                        bgImg.setImage(new Image(url.toString()));
                        bgImg.setOpacity(0.55);
                    } else {
                        bgImg.setImage(null);
                    }
                } catch (Exception ex) {
                    bgImg.setImage(null);
                }
                return;
            }
        }
    }

    // -----------------------------------------------------------------------
    // PERF TIMER
    // -----------------------------------------------------------------------

    private static void startPerfTimer() {
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            if (perfBarLabel != null)
                perfBarLabel.setText(PerformanceMonitor.getSummary(server.getClientCount()));
        }));
        t.setCycleCount(Animation.INDEFINITE); t.play();
    }

    // -----------------------------------------------------------------------
    // CHAT / MESSAGE HELPERS
    // -----------------------------------------------------------------------

    private static void appendChat(String msg) {
        if (chatArea != null) { chatArea.appendText(msg + "\n"); chatArea.setScrollTop(Double.MAX_VALUE); }
    }

    private static void promptMessage(String name) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Message → " + name); d.setHeaderText(null); d.setContentText("Message:");
        d.showAndWait().ifPresent(msg -> {
            if (!msg.trim().isEmpty()) {
                server.sendToClient(name, new CommandPacket(CommandPacket.Type.MSG, "ADMIN", msg));
                appendChat("[TO " + name + "]: " + msg);
                AuditLogger.logCommand("admin", name, "MSG: " + msg);
            }
        });
    }

    private static void promptCommand(String name) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("CMD → " + name); d.setHeaderText(null); d.setContentText("Command:");
        d.showAndWait().ifPresent(cmd -> {
            if (!cmd.trim().isEmpty()) {
                server.sendToClient(name, new CommandPacket(CommandPacket.Type.SHELL, "ADMIN", cmd));
                appendChat("[CMD→" + name + "]: " + cmd);
                AuditLogger.logCommand("admin", name, "CMD: " + cmd);
            }
        });
    }

    // -----------------------------------------------------------------------
    // TOOLS
    // -----------------------------------------------------------------------

    private static void sendFilesToStudents(Stage stage) {
        FileChooser fc = new FileChooser();
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;
        for (File file : files) {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                String b64   = Base64.getEncoder().encodeToString(bytes);
                server.broadcast(new CommandPacket(CommandPacket.Type.FILE_DATA, "ADMIN", file.getName() + "|" + b64));
                appendChat("[FILE]: Sent " + file.getName());
                AuditLogger.logCommand("admin", "ALL", "SEND FILE: " + file.getName());
            } catch (Exception e) { appendChat("[ERROR]: " + file.getName() + " — " + e.getMessage()); }
        }
    }

    private static void captureAllScreenshots(Stage stage) {
        if (studentImages.isEmpty()) { appendChat("[SYSTEM]: No students connected"); return; }
        File dir = new File(System.getProperty("user.home"), "KingLab Screenshots");
        dir.mkdirs();
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        for (Map.Entry<String, ImageView> entry : studentImages.entrySet()) {
            Image img = entry.getValue().getImage();
            if (img != null) {
                try {
                    File out = new File(dir, entry.getKey() + "_" + ts + ".png");
                    java.nio.file.Files.write(out.toPath(), imageToPng(img));
                } catch (Exception e) { appendChat("[ERROR]: " + e.getMessage()); }
            }
        }
        appendChat("[SCREENSHOT]: Saved to " + dir.getAbsolutePath());
        AuditLogger.logCommand("admin", "ALL", "SCREENSHOT_ALL");
    }

    private static byte[] imageToPng(Image image) throws Exception {
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                (int)image.getWidth(), (int)image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pr = image.getPixelReader();
        for (int y=0;y<(int)image.getHeight();y++) for (int x=0;x<(int)image.getWidth();x++) bi.setRGB(x,y,pr.getArgb(x,y));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi,"png",baos); return baos.toByteArray();
    }

    private static void openUrlOnAll() {
        TextInputDialog d = new TextInputDialog("https://");
        d.setTitle("Open URL"); d.setHeaderText(null); d.setContentText("URL to open on all PCs:");
        d.showAndWait().ifPresent(url -> {
            if (!url.trim().isEmpty()) {
                server.broadcast(new CommandPacket(CommandPacket.Type.OPEN_URL, "ADMIN", url));
                appendChat("[URL]: " + url);
                AuditLogger.logCommand("admin", "ALL", "OPEN_URL: " + url);
            }
        });
    }

    private static void generateAttendanceManually(Stage stage) {
        List<String> files = AttendanceTracker.generateAttendanceCSV();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Attendance Exported");
        alert.setHeaderText(null);
        StringBuilder sb = new StringBuilder();
        if (files.isEmpty()) sb.append("No attendance data recorded yet.");
        else files.forEach(f -> sb.append("📄 ").append(f).append("\n"));
        alert.setContentText(sb.toString());
        alert.showAndWait();
    }

    // -----------------------------------------------------------------------
    // UTILITY
    // -----------------------------------------------------------------------
    private static CommandPacket pkt(CommandPacket.Type type, String payload) {
        return new CommandPacket(type, "ADMIN", payload);
    }

    private static void auditLog(User user, String target, String cmd) {
        AuditLogger.logCommand(user.getUsername(), target, cmd);
    }
    private static Button cardBtn(String icon, String color, String tooltip, Runnable action) {
        Button btn = new Button(icon);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-background-radius: 7; -fx-cursor: hand; -fx-padding: 5 8; -fx-font-size: 11px;");
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(e -> action.run());
        btn.setOnMouseEntered(e -> { ScaleTransition sc = new ScaleTransition(Duration.millis(80), btn); sc.setToX(1.13); sc.setToY(1.13); sc.play(); });
        btn.setOnMouseExited(e  -> { ScaleTransition sc = new ScaleTransition(Duration.millis(80), btn); sc.setToX(1.0);  sc.setToY(1.0);  sc.play(); });
        return btn;
    }
}
