package com.ghost.ui;

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
    private static final Map<String, Long>      lastGridUpdate   = new HashMap<>();

    private static final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(AdminDashboard.class);
    private static String adminTheme = prefs.get("adminTheme", "command");
    private static Label  perfBarLabel;
    private static Button aiToggleBtn;
    private static Label  studentCountLabel;
    private static Label  connectedLabel;
    private static Label  aiStatusLabel;
    private static Button ultraStreamBtn;
    private static boolean isUltraActive = false;
    private static boolean isSharingScreen = false;
    private static Button  shareScreenBtn;
    
    // NEW UI Components
    private static ListView<String> activityFeed;
    private static TabPane          bottomTabPane;
    private static StackPane        focusOverlay;
    private static ImageView        focusImgView;
    private static String           currentlyFocusedStudent;

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
            server.setScreenListener(new GhostServer.BinaryScreenListener() {
                @Override public void onScreenUpdate(String name, String base64) {
                    Platform.runLater(() -> updateStudentScreen(name, base64));
                }
                @Override public void onBinaryUpdate(String name, Image image) {
                    Platform.runLater(() -> updateStudentScreenBinary(name, image));
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

        // CENTER & BOTTOM: SplitPane for flexible resizing
        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        mainSplit.setStyle("-fx-background-color: transparent; -fx-box-border: transparent;");
        mainSplit.getItems().addAll(buildCenterGrid(), buildBottomPanels(user));
        mainSplit.setDividerPositions(0.75); // 75% for grid, 25% for bottom

        root.setCenter(mainSplit);

        rootStack.getChildren().addAll(bgImg, root);
        
        // Focus Overlay Layer (Cinematic)
        buildFocusOverlay(rootStack);

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
        
        aiStatusLabel = new Label("AI: " + (Config.aiEnabled ? "ACTIVE" : "OFF"));
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
                ribbonBtn("🤖 Toggle AI", ACCENT_COLOR, () -> toggleAi(user.getUsername()))
            ),
            buildCmdGroup("PIPELINE", 
                ribbonBtn("🚄 Ultra Stream: OFF", PRIMARY_COLOR, () -> toggleUltraStream())
            ),
            buildCmdGroup("TOOLS", 
                ribbonBtn("📸 Screenshot", ACCENT_COLOR,  () -> captureAllScreenshots(stage)),
                ribbonBtn("📁 Send File",  PRIMARY_COLOR, () -> sendFilesToStudents(stage)),
                ribbonBtn("📺 Share Screen", SUCCESS_COLOR, () -> toggleScreenShare()),
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
            "-fx-background-color: linear-gradient(to bottom, rgba(255,255,255,0.15) 0%, rgba(255,255,255,0.02) 100%), " +
            "linear-gradient(to bottom, " + PANEL_COLOR + " 0%, #050510 100%);" +
            "-fx-text-fill: white;" + "-fx-font-size: 12px;" + "-fx-font-weight: bold;" +
            "-fx-padding: 8 16;" + "-fx-background-radius: 6;" +
            "-fx-border-color: rgba(255,255,255,0.2) rgba(0,0,0,0.3) rgba(0,0,0,0.3) rgba(255,255,255,0.2);" +
            "-fx-border-radius: 6;" + "-fx-border-width: 1.5;"
        );
        btn.setCursor(javafx.scene.Cursor.HAND);
        
        DropShadow glow = new DropShadow(BlurType.GAUSSIAN, Color.web(glowColor, 0.4), 10, 0, 0, 2);
        InnerShadow shine = new InnerShadow(BlurType.GAUSSIAN, Color.color(1,1,1,0.15), 1, 0, 1, 1);
        btn.setEffect(new Blend(BlendMode.OVERLAY, glow, shine));
        
        btn.setOnMouseEntered(e -> { btn.setOpacity(0.9); glow.setRadius(18); glow.setColor(Color.web(glowColor, 0.6)); });
        btn.setOnMouseExited(e -> { btn.setOpacity(1.0); glow.setRadius(10); glow.setColor(Color.web(glowColor, 0.4)); });
        btn.setOnAction(e -> { action.run(); appendActivity("COMMAND", "Executed: " + text); });
        
        if (text.contains("Toggle AI")) aiToggleBtn = btn;
        if (text.contains("Ultra Stream")) ultraStreamBtn = btn;
        if (text.contains("Share Screen")) shareScreenBtn = btn;
        
        return btn;
    }

    private static void toggleScreenShare() {
        isSharingScreen = !isSharingScreen;
        if (isSharingScreen) {
            ScreenCapture.startAsyncCapture();
            startAdminShareLoop();
            appendActivity("SYSTEM", "Admin screen share ENABLED");
        } else {
            ScreenCapture.stopAsyncCapture();
            appendActivity("SYSTEM", "Admin screen share DISABLED");
        }
        
        if (shareScreenBtn != null) {
            shareScreenBtn.setText(isSharingScreen ? "📺 Stop Share" : "📺 Share Screen");
            shareScreenBtn.setStyle(shareScreenBtn.getStyle() + "-fx-border-color: " + (isSharingScreen ? SUCCESS_COLOR : DANGER_COLOR) + ";");
        }
    }

    private static void startAdminShareLoop() {
        Thread t = new Thread(() -> {
            while (isSharingScreen) {
                try {
                    String frame = ScreenCapture.getLatestFrame();
                    if (frame != null) {
                        server.broadcast(new CommandPacket(CommandPacket.Type.ADMIN_SCREEN, "ADMIN", frame));
                    }
                    Thread.sleep(100); // ~10 FPS
                } catch (Exception e) {
                    break;
                }
            }
        });
        t.setDaemon(true);
        t.setName("AdminShareBroadcast");
        t.start();
    }

    private static void toggleUltraStream() {
        isUltraActive = !isUltraActive;
        String mode = isUltraActive ? "ULTRA_WEBRTC" : "LEGACY_CPU";
        server.broadcast(new CommandPacket(CommandPacket.Type.STREAM_MODE, "ADMIN", mode));
        
        if (ultraStreamBtn != null) {
            ultraStreamBtn.setText(isUltraActive ? "🚄 Ultra Stream: ON" : "🚄 Ultra Stream: OFF");
            ultraStreamBtn.setStyle(ultraStreamBtn.getStyle() + "-fx-border-color: " + (isUltraActive ? SUCCESS_COLOR : DANGER_COLOR) + ";");
        }
        
        appendActivity("PIPELINE", "Stream Mode set to " + mode);
        AuditLogger.logSystem("Stream Mode switched to " + mode);
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

    private static SplitPane buildBottomPanels(User admin) {
        SplitPane bottomSplit = new SplitPane();
        bottomSplit.setPrefHeight(250);
        bottomSplit.setStyle("-fx-background-color: rgba(10, 15, 31, 0.85); -fx-border-color: rgba(0, 209, 255, 0.2); -fx-border-width: 1 0 0 0;");

        // LEFT: Activity Feed
        VBox activityPanel = new VBox(8);
        activityPanel.setPadding(new Insets(15, 7, 15, 15));
        
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
        rightPanel.setPadding(new Insets(15, 15, 15, 7));

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
        
        bottomSplit.getItems().addAll(activityPanel, rightPanel);
        bottomSplit.setDividerPositions(0.35); // Activity feed gets 35% default
        return bottomSplit;
    }

    // -----------------------------------------------------------------------
    // STUDENT CARDS (PREMIUM HOVER AND GLOW)
    // -----------------------------------------------------------------------

    private static void addStudentCard(String name, Image screenshot) {
        if (studentCards.containsKey(name)) return;

        VBox card = new VBox(0); // Changed to VBox to prevent name covering screen
        card.setPrefWidth(250);
        card.setStyle(
                "-fx-background-color: " + PANEL_COLOR + ";" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: rgba(255, 255, 255, 0.1);" +
                "-fx-border-radius: 12;" +
                "-fx-border-width: 1;");

        DropShadow cardShadow = new DropShadow(15, Color.web("#000000", 0.6));
        card.setEffect(cardShadow);

        card.setTranslateY(30); card.setOpacity(0);
        TranslateTransition tt = new TranslateTransition(Duration.millis(300), card);
        tt.setToY(0);
        FadeTransition ft = new FadeTransition(Duration.millis(300), card);
        ft.setToValue(1);
        tt.play(); ft.play();

        // Thumbnail with rounded corners
        ImageView imgView = new ImageView();
        imgView.setFitWidth(248); imgView.setFitHeight(150);
        imgView.setPreserveRatio(false);
        if (screenshot != null) imgView.setImage(screenshot);
        
        ColorAdjust vivid = new ColorAdjust();
        vivid.setBrightness(0.05); vivid.setSaturation(0.2); vivid.setContrast(0.05);
        imgView.setEffect(vivid);
        
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(248, 150);
        clip.setArcWidth(18); clip.setArcHeight(18);
        imgView.setClip(clip);

        StackPane imgContainer = new StackPane(imgView);
        imgContainer.setPadding(new Insets(1));

        // Info bar below image
        HBox infoBar = new HBox(6);
        infoBar.setAlignment(Pos.CENTER_LEFT);
        infoBar.setPadding(new Insets(8, 10, 8, 10));
        infoBar.setStyle("-fx-background-color: rgba(0,0,0,0.3); -fx-background-radius: 0 0 12 12;");
        
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
        
        infoBar.getChildren().addAll(dot, nameLabel, sp, handLbl, pingLbl);

        // Hover Overlay (Center-Aligned buttons)
        HBox actionOverlay = new HBox(10);
        actionOverlay.setAlignment(Pos.CENTER);
        actionOverlay.setStyle("-fx-background-color: rgba(10, 15, 31, 0.7); -fx-background-radius: 12;");
        actionOverlay.setOpacity(0);
        actionOverlay.setMouseTransparent(true);
        actionOverlay.setPickOnBounds(false); // Let clicks pass through empty space to the card
        actionOverlay.getChildren().addAll(
                cardBtn("🔒", WARNING_COLOR, "Lock", () -> { server.sendToClient(name, pkt(CommandPacket.Type.LOCK, "{}")); appendActivity("LOCK", name); }),
                cardBtn("💬", ACCENT_COLOR, "Chat", () -> promptMessage(name)),
                cardBtn("⚡", PRIMARY_COLOR, "Command", () -> promptCommand(name)),
                cardBtn("👁", SUCCESS_COLOR, "Focus", () -> openFullScreenView(name, imgView.getImage()))
        );
        
        StackPane overlayStack = new StackPane(imgContainer, actionOverlay);

        card.setOnMouseEntered(e -> {
            card.setStyle(card.getStyle().replace("rgba(255, 255, 255, 0.1)", ACCENT_COLOR));
            FadeTransition fo = new FadeTransition(Duration.millis(200), actionOverlay);
            fo.setToValue(1); fo.play();
            actionOverlay.setMouseTransparent(false);
        });
        card.setOnMouseExited(e -> {
            card.setStyle(card.getStyle().replace(ACCENT_COLOR, "rgba(255, 255, 255, 0.1)"));
            FadeTransition fo = new FadeTransition(Duration.millis(200), actionOverlay);
            fo.setToValue(0); fo.play();
            actionOverlay.setMouseTransparent(true);
        });

        card.getChildren().addAll(overlayStack, infoBar);

        // Wrap in StackPane for internal studentCards map compatibility
        StackPane wrapper = new StackPane(card);
        wrapper.setCursor(javafx.scene.Cursor.HAND);
        wrapper.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1) openFullScreenView(name, imgView.getImage());
        });

        thumbnailGrid.getChildren().add(wrapper);
        
        // Performance: Enable caching for the card
        wrapper.setCache(true);
        wrapper.setCacheHint(javafx.scene.CacheHint.SPEED);
        
        studentCards.put(name, wrapper);
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

    private static void updateStudentScreenBinary(String name, Image image) {
        try {
            PerformanceMonitor.recordFrame();
            
            // Update Mini Card - Throttled for Grid performance
            if (studentImages.containsKey(name)) {
                long now = System.currentTimeMillis();
                long last = lastGridUpdate.getOrDefault(name, 0L);
                if (now - last > 120) { // ~8 FPS for grid thumbnails
                    studentImages.get(name).setImage(image);
                    lastGridUpdate.put(name, now);
                }
            } else {
                addStudentCard(name, image);
                lastGridUpdate.put(name, System.currentTimeMillis());
            }

            // Update Fullscreen Overlay if focused - zero latency
            if (name.equals(currentlyFocusedStudent) && focusOverlay.isVisible()) {
                focusImgView.setImage(image);
            }
        } catch (Exception e) {
            AuditLogger.logError("updateStudentScreenBinary", e.getMessage());
        }
    }

    private static void updateStudentScreen(String name, String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            Image image  = new Image(new ByteArrayInputStream(bytes));
            PerformanceMonitor.recordFrame();
            
            // Update Mini Card - Throttled for Grid performance
            if (studentImages.containsKey(name)) {
                long now = System.currentTimeMillis();
                long last = lastGridUpdate.getOrDefault(name, 0L);
                if (now - last > 120) { // ~8 FPS for grid thumbnails
                    studentImages.get(name).setImage(image);
                    lastGridUpdate.put(name, now);
                }
            } else {
                addStudentCard(name, image);
                lastGridUpdate.put(name, System.currentTimeMillis());
            }

            // Update Fullscreen Overlay if focused
            if (name.equals(currentlyFocusedStudent) && focusOverlay.isVisible()) {
                focusImgView.setImage(image);
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
        lastGridUpdate.remove(name);
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

    private static void buildFocusOverlay(StackPane root) {
        focusOverlay = new StackPane();
        focusOverlay.setVisible(false);
        focusOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.85);"); // Dark semi-transparent
        
        // Blur effect for objects behind the overlay
        BoxBlur blur = new BoxBlur(10, 10, 3);
        
        focusImgView = new ImageView();
        focusImgView.setPreserveRatio(true);
        focusImgView.fitWidthProperty().bind(focusOverlay.widthProperty().multiply(0.95));
        focusImgView.fitHeightProperty().bind(focusOverlay.heightProperty().multiply(0.85));

        // Close logic on ESC
        focusOverlay.setFocusTraversable(true);
        focusOverlay.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) hideFocusView();
        });

        // Toolbar
        HBox toolbar = new HBox(25);
        toolbar.setAlignment(Pos.CENTER);
        toolbar.setPadding(new Insets(15, 40, 15, 40));
        toolbar.setMaxHeight(80);
        toolbar.setStyle(
            "-fx-background-color: linear-gradient(to bottom, rgba(30, 40, 70, 0.95), rgba(10, 15, 30, 0.98));" +
            "-fx-background-radius: 50; -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 50; -fx-border-width: 1.5;"
        );
        
        Button closeBtn = ribbonBtn("✕ EXIT FOCUS", DANGER_COLOR, () -> hideFocusView());
        
        toolbar.getChildren().addAll(
            ribbonBtn("🔒 LOCK", WARNING_COLOR, () -> { if (currentlyFocusedStudent != null) server.sendToClient(currentlyFocusedStudent, pkt(CommandPacket.Type.LOCK, "{}")); }),
            ribbonBtn("💬 CHAT", ACCENT_COLOR, () -> { if (currentlyFocusedStudent != null) promptMessage(currentlyFocusedStudent); }),
            ribbonBtn("⚡ SHELL", PRIMARY_COLOR, () -> { if (currentlyFocusedStudent != null) promptCommand(currentlyFocusedStudent); }),
            ribbonBtn("⏻ STOP", DANGER_COLOR, () -> { if (currentlyFocusedStudent != null) { server.sendToClient(currentlyFocusedStudent, pkt(CommandPacket.Type.SHUTDOWN, "{}")); hideFocusView(); } }),
            closeBtn
        );

        StackPane.setAlignment(toolbar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toolbar, new Insets(0, 0, 50, 0));

        focusOverlay.getChildren().addAll(focusImgView, toolbar);
        root.getChildren().add(focusOverlay);

        // Transition logic
        focusOverlay.visibleProperty().addListener((obs, oldV, newV) -> {
            if (newV) {
                FadeTransition ft = new FadeTransition(Duration.millis(300), focusOverlay);
                ft.setFromValue(0); ft.setToValue(1); ft.play();
                focusOverlay.requestFocus();
                // Apply blur to dashboard behind
                root.getChildren().get(1).setEffect(blur); // root BorderPane
            } else {
                root.getChildren().get(1).setEffect(null);
            }
        });
    }

    private static void openFullScreenView(String studentName, Image screenshot) {
        currentlyFocusedStudent = studentName;
        focusImgView.setImage(screenshot);
        focusOverlay.setVisible(true);
        appendActivity("FOCUS", "Active: " + studentName);
    }

    private static void hideFocusView() {
        currentlyFocusedStudent = null;
        focusOverlay.setVisible(false);
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
            aiToggleBtn.setText(Config.aiEnabled ? "🤖 AI: ON" : "🤖 AI: OFF");
            aiToggleBtn.setStyle(aiToggleBtn.getStyle() + "-fx-border-color: " + (Config.aiEnabled ? SUCCESS_COLOR : DANGER_COLOR) + ";");
        }
        
        if (aiStatusLabel != null) {
            aiStatusLabel.setText("AI: " + (Config.aiEnabled ? "ON" : "OFF"));
            aiStatusLabel.setStyle("-fx-text-fill: " + (Config.aiEnabled ? SUCCESS_COLOR : "#888") + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        }
        
        appendActivity("SYSTEM", "AI Assistance " + payload + "D");
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
        btn.setStyle(
            "-fx-background-color: linear-gradient(to bottom, rgba(255,255,255,0.1) 0%, rgba(255,255,255,0.01) 100%), " +
            "linear-gradient(to bottom, " + color + " 0%, #151525 100%);" +
            "-fx-text-fill: white; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 6 10; -fx-font-size: 11px;" +
            "-fx-border-color: rgba(255,255,255,0.15) rgba(0,0,0,0.2) rgba(0,0,0,0.2) rgba(255,255,255,0.15);" +
            "-fx-border-width: 1; -fx-border-radius: 6;"
        );
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(e -> action.run());
        
        DropShadow glow = new DropShadow(BlurType.GAUSSIAN, Color.web(color, 0.3), 8, 0, 0, 1);
        btn.setEffect(glow);

        btn.setOnMouseEntered(e -> { 
            ScaleTransition sc = new ScaleTransition(Duration.millis(80), btn); sc.setToX(1.1); sc.setToY(1.1); sc.play();
            glow.setRadius(12); glow.setColor(Color.web(color, 0.5));
        });
        btn.setOnMouseExited(e  -> { 
            ScaleTransition sc = new ScaleTransition(Duration.millis(80), btn); sc.setToX(1.0);  sc.setToY(1.0);  sc.play();
            glow.setRadius(8); glow.setColor(Color.web(color, 0.3));
        });
        return btn;
    }
}
