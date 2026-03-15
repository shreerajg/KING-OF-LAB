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
import java.util.concurrent.*;

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
    private static boolean          screenSharing  = false;
    private static ScheduledExecutorService screenScheduler;

    private static final Map<String, VBox>      studentCards     = new LinkedHashMap<>();
    private static final Map<String, ImageView> studentImages    = new HashMap<>();
    private static final Map<String, Label>     handRaisedLabels = new HashMap<>();
    private static final Map<String, Circle>    statusDots       = new HashMap<>();

    private static final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(AdminDashboard.class);
    private static String adminTheme = prefs.get("adminTheme", "command");
    private static Label  perfBarLabel;
    private static Button aiToggleBtn;
    private static Label  studentCountLabel;
    private static Label  connectedLabel;

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
                    Platform.runLater(() -> { removeStudentCard(name); updateCountLabel(); });
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
        root.setStyle("-fx-background-color: transparent;");

        applyAdminBg(root, rootStack, bgImg, adminTheme);

        VBox sidebar = buildSidebar(stage, user, root, rootStack, bgImg);
        root.setLeft(sidebar);

        root.setCenter(buildCenterGrid());
        root.setRight(buildRightPanel());
        root.setBottom(buildBottomBar());

        rootStack.getChildren().addAll(bgImg, root);

        Scene scene = new Scene(rootStack, 1280, 800);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — Admin: " + user.getUsername());
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
    // SIDEBAR
    // -----------------------------------------------------------------------

    private static VBox buildSidebar(Stage stage, User user, BorderPane root,
                                     StackPane rootStack, ImageView bgImg) {
        VBox sidebar = new VBox();
        sidebar.setPrefWidth(240);
        sidebar.setMaxWidth(240);
        sidebar.setStyle(
                "-fx-background-color: rgba(0,0,0,0.6);" +
                "-fx-border-color: rgba(155,89,182,0.18);" +
                "-fx-border-width: 0 1 0 0;");

        // Header
        VBox header = new VBox(6);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(22, 14, 16, 14));
        header.setStyle("-fx-background-color: rgba(155,89,182,0.1); -fx-border-color: rgba(155,89,182,0.15); -fx-border-width: 0 0 1 0;");

        Label logo = new Label("👑 KING OF LAB");
        logo.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #d7bde2;");
        Glow logoGlow = new Glow(0.5);
        logo.setEffect(logoGlow);
        Timeline glowAnim = new Timeline(
                new KeyFrame(Duration.ZERO,       new KeyValue(logoGlow.levelProperty(), 0.2)),
                new KeyFrame(Duration.seconds(2), new KeyValue(logoGlow.levelProperty(), 0.9)));
        glowAnim.setCycleCount(Animation.INDEFINITE); glowAnim.setAutoReverse(true); glowAnim.play();

        Circle avatar = new Circle(22, Color.web("#9b59b6"));
        Label uLabel  = new Label("👑 " + user.getUsername().toUpperCase());
        uLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
        Label roleLabel = new Label("ADMINISTRATOR");
        roleLabel.setStyle("-fx-text-fill: #9b59b6; -fx-font-size: 9px; -fx-letter-spacing: 1.5px;");

        connectedLabel = new Label("● 0 students");
        connectedLabel.setStyle("-fx-text-fill: #2ecc71; -fx-font-size: 10px; -fx-font-weight: bold;");

        header.getChildren().addAll(logo, avatar, uLabel, roleLabel, connectedLabel);

        // Scrollable sections
        VBox sections = new VBox(10);
        sections.setPadding(new Insets(14));

        // ─── CLASS CONTROL ───────────────────────────────────────────────────
        VBox classCtrl = sectionBox("🏛 CLASS CONTROL",
                wideBtn("🔒 Lock All Screens",   "#b03a2e", () -> { server.broadcast(pkt(CommandPacket.Type.LOCK,   "{}")); auditLog(user, "ALL", "LOCK ALL");   appendChat("[CMD]: Locked all screens"); }),
                wideBtn("🔓 Unlock All Screens", "#1e8449", () -> { server.broadcast(pkt(CommandPacket.Type.UNLOCK, "{}")); auditLog(user, "ALL", "UNLOCK ALL"); appendChat("[CMD]: Unlocked all screens"); }));

        // ─── INTERNET ────────────────────────────────────────────────────────
        VBox internet = sectionBox("🌐 INTERNET CONTROL",
                wideBtn("🚫 Block Distracting Sites", "#922b21", () -> { HostsFileManager.blockSites(); auditLog(user, "ALL", "BLOCK INTERNET"); appendChat("[NETWORK]: Sites blocked"); }),
                wideBtn("✅ Restore Internet",        "#1a5276", () -> { HostsFileManager.restoreHostsFile(); auditLog(user, "ALL", "RESTORE INTERNET"); appendChat("[NETWORK]: Internet restored"); }));

        // ─── POWER MANAGEMENT ────────────────────────────────────────────────
        VBox power = sectionBox("⚡ POWER MANAGEMENT",
                wideBtn("⏻ Shutdown All PCs",  "#7b241c", () -> { server.broadcast(pkt(CommandPacket.Type.SHUTDOWN, "{}")); auditLog(user, "ALL", "SHUTDOWN ALL"); appendChat("[CMD]: Shutdown broadcast"); }),
                wideBtn("🔄 Restart All PCs",   "#784212", () -> { server.broadcast(pkt(CommandPacket.Type.RESTART,  "{}")); auditLog(user, "ALL", "RESTART ALL");  appendChat("[CMD]: Restart broadcast"); }));

        // ─── AI CONTROL ──────────────────────────────────────────────────────
        aiToggleBtn = wideBtn("🤖 Disable AI Assistance", "#1a5276", () -> toggleAi(user.getUsername()));
        Button clearAllAI = wideBtn("🗑 Clear All AI History", "#6c3483", () -> {
            OllamaService.clearAllHistories();
            server.broadcast(pkt(CommandPacket.Type.AI_CLEAR_HISTORY, "ALL"));
            appendChat("[AI]: All conversation histories cleared");
            auditLog(user, "ALL", "AI_CLEAR_HISTORY");
        });
        Label aiModel = new Label("Model: " + Config.AI_MODEL);
        aiModel.setStyle("-fx-text-fill: #444; -fx-font-size: 9px; -fx-padding: 0 0 0 6;");
        VBox aiCtrl = sectionBox("🤖 AI CONTROL", aiToggleBtn, clearAllAI, aiModel);

        // ─── SCREEN SHARE ────────────────────────────────────────────────────
        VBox shareBox = buildShareSection();

        // ─── TOOLS ───────────────────────────────────────────────────────────
        VBox tools = sectionBox("🔧 QUICK TOOLS",
                wideBtn("📸 Screenshot All",   "#d35400", () -> captureAllScreenshots(stage)),
                wideBtn("🌐 Open URL on All",   "#1e8449", () -> openUrlOnAll()),
                wideBtn("📁 Send Files to All", "#1f618d", () -> sendFilesToStudents(stage)),
                wideBtn("📋 Export Attendance", "#117a65", () -> generateAttendanceManually(stage)));

        // ─── THEME ───────────────────────────────────────────────────────────
        ComboBox<String> themeBox = new ComboBox<>();
        for (Object[] t : ADMIN_THEMES) {
            themeBox.getItems().add((String) t[1]);
            if (t[0].equals(adminTheme)) themeBox.setValue((String) t[1]);
        }
        if (themeBox.getValue() == null) themeBox.setValue("🖥 Command Center");
        themeBox.setMaxWidth(Double.MAX_VALUE);
        themeBox.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-font-size: 11px;");
        themeBox.setOnAction(e -> {
            int i = themeBox.getSelectionModel().getSelectedIndex();
            if (i >= 0) { 
                adminTheme = (String) ADMIN_THEMES[i][0]; 
                prefs.put("adminTheme", adminTheme);
                applyAdminBg(root, rootStack, bgImg, adminTheme); 
            }
        });
        VBox themeSection = sectionBox("🎨 THEME", themeBox);

        sections.getChildren().addAll(classCtrl, internet, power, aiCtrl, shareBox, tools, themeSection);

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        sidebar.getChildren().addAll(header, scroll);
        return sidebar;
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
    // RIGHT PANEL — chat
    // -----------------------------------------------------------------------

    private static VBox buildRightPanel() {
        VBox panel = new VBox(10);
        panel.setPrefWidth(270);
        panel.setMaxWidth(270);
        panel.setPadding(new Insets(16));
        panel.setStyle(
                "-fx-background-color: rgba(0,0,0,0.42);" +
                "-fx-border-color: rgba(155,89,182,0.15);" +
                "-fx-border-width: 0 0 0 1;");

        Label chatTitle = new Label("💬 LIVE BROADCAST CHAT");
        chatTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #9b59b6; -fx-font-weight: bold;");
        chatTitle.setEffect(new Glow(0.2));

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.setStyle(
                "-fx-control-inner-background: #05050f;" +
                "-fx-text-fill: #aaa;" +
                "-fx-font-family: 'Consolas';" +
                "-fx-font-size: 11px;");
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        TextField msgField = new TextField();
        msgField.setPromptText("Type to all students...");
        msgField.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-text-fill: white; -fx-prompt-text-fill: #444; -fx-background-radius: 8; -fx-padding: 9;");

        Button sendBtn = new Button("SEND");
        sendBtn.setStyle("-fx-background-color: #9b59b6; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 9 16;");
        sendBtn.setOnAction(e -> {
            String msg = msgField.getText().trim();
            if (!msg.isEmpty()) {
                server.broadcast(new CommandPacket(CommandPacket.Type.MSG, "ADMIN", msg));
                appendChat("[TO ALL]: " + msg);
                msgField.clear();
            }
        });
        msgField.setOnAction(e -> sendBtn.fire());

        HBox inputRow = new HBox(8, msgField, sendBtn);
        HBox.setHgrow(msgField, Priority.ALWAYS);

        panel.getChildren().addAll(chatTitle, chatArea, inputRow);
        return panel;
    }

    // -----------------------------------------------------------------------
    // BOTTOM BAR — perf + shell
    // -----------------------------------------------------------------------

    private static VBox buildBottomBar() {
        // Perf bar
        perfBarLabel = new Label();
        perfBarLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 10px; -fx-font-family: 'Consolas';");
        HBox perfBar = new HBox(perfBarLabel);
        perfBar.setAlignment(Pos.CENTER_RIGHT);
        perfBar.setPadding(new Insets(4, 18, 4, 18));
        perfBar.setStyle("-fx-background-color: rgba(0,0,0,0.55);");

        // Shell
        HBox shell = new HBox(10);
        shell.setPadding(new Insets(10, 18, 10, 18));
        shell.setAlignment(Pos.CENTER_LEFT);
        shell.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-border-color: rgba(0,255,100,0.1); -fx-border-width: 1 0 0 0;");
        Label prompt = new Label("CMD ▶");
        prompt.setStyle("-fx-text-fill: #00ff7f; -fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-font-weight: bold;");
        TextField cmdInput = new TextField();
        cmdInput.setPromptText("Shell command to ALL students...");
        cmdInput.setStyle("-fx-background-color: #040410; -fx-text-fill: #00ff7f; -fx-font-family: 'Consolas'; -fx-prompt-text-fill: #1a3a1a; -fx-background-radius: 6;");
        HBox.setHgrow(cmdInput, Priority.ALWAYS);
        Button execBtn = new Button("▶ RUN ALL");
        execBtn.setStyle("-fx-background-color: #b03a2e; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 8 18;");
        execBtn.setOnAction(e -> {
            String cmd = cmdInput.getText().trim();
            if (!cmd.isEmpty()) {
                server.broadcast(new CommandPacket(CommandPacket.Type.SHELL, "ADMIN", cmd));
                appendChat("[CMD→ALL]: " + cmd);
                cmdInput.clear();
            }
        });
        cmdInput.setOnAction(e -> execBtn.fire());
        shell.getChildren().addAll(prompt, cmdInput, execBtn);

        return new VBox(perfBar, shell);
    }

    // -----------------------------------------------------------------------
    // STUDENT CARDS  (brighter, vivid thumbnails)
    // -----------------------------------------------------------------------

    private static void addStudentCard(String name, Image screenshot) {
        if (studentCards.containsKey(name)) return; // prevent duplicates

        VBox card = new VBox(8);
        card.setPrefWidth(240);
        card.setStyle(
                "-fx-background-color: rgba(20,10,40,0.82);" +
                "-fx-background-radius: 16;" +
                "-fx-padding: 10;" +
                "-fx-border-color: rgba(155,89,182,0.35);" +
                "-fx-border-radius: 16;" +
                "-fx-border-width: 1;");
        card.setAlignment(Pos.CENTER);

        // Glow effect on card
        DropShadow cardGlow = new DropShadow(16, Color.web("#9b59b6", 0.4));
        card.setEffect(cardGlow);

        // Entrance animation
        card.setTranslateY(24); card.setOpacity(0);
        TranslateTransition tt = new TranslateTransition(Duration.millis(280), card);
        tt.setToY(0);
        FadeTransition ft = new FadeTransition(Duration.millis(280), card);
        ft.setToValue(1);
        tt.play(); ft.play();

        // Hover glow
        card.setOnMouseEntered(e -> {
            ScaleTransition sc = new ScaleTransition(Duration.millis(120), card);
            sc.setToX(1.02); sc.setToY(1.02); sc.play();
            cardGlow.setRadius(24); cardGlow.setColor(Color.web("#9b59b6", 0.7));
        });
        card.setOnMouseExited(e -> {
            ScaleTransition sc = new ScaleTransition(Duration.millis(120), card);
            sc.setToX(1.0); sc.setToY(1.0); sc.play();
            cardGlow.setRadius(16); cardGlow.setColor(Color.web("#9b59b6", 0.4));
        });

        // Screenshot thumbnail — BRIGHT & VIVID
        ImageView imgView = new ImageView();
        imgView.setFitWidth(220); imgView.setFitHeight(140);
        imgView.setPreserveRatio(false);
        imgView.setStyle("-fx-cursor: hand;");
        if (screenshot != null) imgView.setImage(screenshot);

        // Colour-adjust to make thumbnails brighter and more saturated
        ColorAdjust vivid = new ColorAdjust();
        vivid.setBrightness(0.08);
        vivid.setSaturation(0.25);
        vivid.setContrast(0.05);
        imgView.setEffect(vivid);

        // Rounded clip (StackPane trick)
        StackPane imgBox = new StackPane(imgView);
        imgBox.setStyle("-fx-background-color: #08080f; -fx-background-radius: 10; -fx-cursor: hand;");
        imgBox.setOnMouseClicked(e -> openFullScreenView(name, imgView.getImage()));

        // Student name
        HBox nameRow = new HBox(6);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Circle dot = new Circle(4, Color.web("#2ecc71"));
        statusDots.put(name, dot);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;");

        Label handLbl = new Label("");
        handLbl.setStyle("-fx-font-size: 14px;");
        handRaisedLabels.put(name, handLbl);

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        nameRow.getChildren().addAll(dot, nameLabel, sp, handLbl);

        // Action buttons
        HBox btnRow = new HBox(5);
        btnRow.setAlignment(Pos.CENTER);
        btnRow.getChildren().addAll(
                cardBtn("🔒", "#922b21", "Lock",       () -> { server.sendToClient(name, pkt(CommandPacket.Type.LOCK, "{}")); appendChat("[LOCK]: " + name); }),
                cardBtn("🔓", "#1e8449", "Unlock",     () -> { server.sendToClient(name, pkt(CommandPacket.Type.UNLOCK, "{}")); appendChat("[UNLOCK]: " + name); }),
                cardBtn("💬", "#1f618d", "Message",    () -> promptMessage(name)),
                cardBtn("⚡️", "#784212", "Command",    () -> promptCommand(name)),
                cardBtn("🗑",  "#444",    "Clear AI",   () -> { OllamaService.clearHistory(name); server.sendToClient(name, new CommandPacket(CommandPacket.Type.AI_CLEAR_HISTORY, "ADMIN", name)); appendChat("[AI CLEAR]: " + name); })
        );

        card.getChildren().addAll(imgBox, nameRow, btnRow);
        thumbnailGrid.getChildren().add(card);
        studentCards.put(name, card);
        studentImages.put(name, imgView);
        updateCountLabel();
    }

    private static void updateHandRaised(String student, boolean up) {
        Label lbl = handRaisedLabels.get(student);
        if (lbl != null) lbl.setText(up ? "✋" : "");
        if (up) {
            appendChat("✋ [HAND RAISED]: " + student + " has a question!");
            VBox card = studentCards.get(student);
            if (card != null) {
                FadeTransition flash = new FadeTransition(Duration.millis(200), card);
                flash.setFromValue(1.0); flash.setToValue(0.5);
                flash.setCycleCount(6); flash.setAutoReverse(true);
                flash.setOnFinished(e -> card.setOpacity(1.0));
                flash.play();
            }
        }
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
        VBox card = studentCards.remove(name);
        if (card != null) {
            FadeTransition ft = new FadeTransition(Duration.millis(280), card);
            ft.setToValue(0);
            ft.setOnFinished(e -> thumbnailGrid.getChildren().remove(card));
            ft.play();
        }
        studentImages.remove(name);
        handRaisedLabels.remove(name);
        statusDots.remove(name);
        appendChat("[SYSTEM]: " + name + " disconnected");
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
        fs.setTitle("👁 Viewing: " + studentName + "  —  ESC / click to close");

        ImageView fullView = new ImageView(screenshot);
        fullView.setPreserveRatio(true);
        fullView.fitWidthProperty().bind(fs.widthProperty());
        fullView.fitHeightProperty().bind(fs.heightProperty().subtract(38));

        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(8, 16, 8, 16));
        topBar.setStyle("-fx-background-color: rgba(0,0,0,0.75);");
        Label lbl = new Label("🔴 LIVE  ●  " + studentName);
        lbl.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 13px; -fx-font-weight: bold;");
        Button closeBtn = new Button("✕ Close");
        closeBtn.setStyle("-fx-background-color: #922b21; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> fs.close());
        Region sp2 = new Region(); HBox.setHgrow(sp2, Priority.ALWAYS);
        topBar.getChildren().addAll(lbl, sp2, closeBtn);

        VBox fsRoot = new VBox(topBar, fullView);
        fsRoot.setStyle("-fx-background-color: #030307;");
        Scene scene = new Scene(fsRoot, 1280, 820);
        scene.setOnMouseClicked(e -> fs.close());
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) fs.close(); });
        fs.setScene(scene); fs.show();

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
    // SCREEN SHARE
    // -----------------------------------------------------------------------

    private static VBox buildShareSection() {
        Label statusLbl  = new Label("● Off");
        statusLbl.setStyle("-fx-text-fill: #888; -fx-font-size: 10px; -fx-font-weight: bold;");

        ToggleButton toggle = new ToggleButton("📺 Share My Screen");
        toggle.setMaxWidth(Double.MAX_VALUE);
        toggle.setStyle("-fx-background-color: #1f618d; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand;");
        toggle.setOnAction(e -> {
            screenSharing = toggle.isSelected();
            if (screenSharing) {
                toggle.setText("⏹ Stop Sharing");
                toggle.setStyle("-fx-background-color: #922b21; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand;");
                statusLbl.setText("● LIVE"); statusLbl.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 10px; -fx-font-weight: bold;");
                startAdminShare();
            } else {
                toggle.setText("📺 Share My Screen");
                toggle.setStyle("-fx-background-color: #1f618d; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand;");
                statusLbl.setText("● Off"); statusLbl.setStyle("-fx-text-fill: #888; -fx-font-size: 10px; -fx-font-weight: bold;");
                stopAdminShare();
            }
        });
        return sectionBox("📺 SCREEN SHARE", statusLbl, toggle);
    }

    private static void startAdminShare() {
        ScreenCapture.startAsyncCapture();
        screenScheduler = Executors.newSingleThreadScheduledExecutor();
        screenScheduler.scheduleAtFixedRate(() -> {
            if (screenSharing) {
                String b64 = ScreenCapture.getLatestFrame();
                if (b64 != null) server.broadcast(new CommandPacket(CommandPacket.Type.ADMIN_SCREEN, "ADMIN", b64));
            }
        }, 0, 25, TimeUnit.MILLISECONDS);
    }

    private static void stopAdminShare() {
        ScreenCapture.stopAsyncCapture();
        if (screenScheduler != null) { screenScheduler.shutdown(); screenScheduler = null; }
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

    private static VBox sectionBox(String title, javafx.scene.Node... nodes) {
        VBox box = new VBox(7);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.028); -fx-background-radius: 10; -fx-padding: 12 10;");
        Label lbl = new Label(title);
        lbl.setStyle("-fx-text-fill: #5d6d7e; -fx-font-size: 9px; -fx-font-weight: bold; -fx-letter-spacing: 1.2px;");
        box.getChildren().add(lbl);
        for (javafx.scene.Node n : nodes) box.getChildren().add(n);
        return box;
    }

    private static Button wideBtn(String text, String color, Runnable action) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 9 12; -fx-cursor: hand; -fx-alignment: center-left;");
        btn.setOnAction(e -> action.run());
        btn.setOnMouseEntered(e -> btn.setOpacity(0.82));
        btn.setOnMouseExited(e  -> btn.setOpacity(1.0));
        return btn;
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
