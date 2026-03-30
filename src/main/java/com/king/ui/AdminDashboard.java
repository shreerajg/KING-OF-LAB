package com.king.ui;

import com.king.database.User;
import com.king.net.CommandPacket;
import com.king.net.DiscoveryService;
import com.king.net.KingServer;
import com.king.util.*;
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
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.*;
import javafx.util.Duration;

import java.io.*;
import java.util.*;

/**
 * King of Lab — Admin Dashboard v6 (Stitch Hologram Design - Tactical Hologram).
 * UI rebuilt to match the "Command Sentinel" tactical hologram design spec.
 * Uses 100% inline CSS styling for compatibility with the native batch build process.
 * 
 * Design System: The Command Sentinel - "Tactical Hologram"
 * - Glassmorphism with 60% opacity + backdrop blur effect
 * - No 1px solid borders - use background shifts
 * - Ambient glow with primary_container at 5% opacity
 * - Pulse Monitor sparkline for real-time data flow
 * - Space Grotesk (display) + Inter (body) typography
 */
public class AdminDashboard {

    private static KingServer      server;
    private static DiscoveryService discoveryService;
    
    // Core Layout Nodes
    private static FlowPane         thumbnailGrid;
    private static ListView<String> activityFeed;
    private static TextArea         termOutput;
    private static Canvas           pulseMonitorCanvas;
    private static double[]         pulseData = new double[60];
    private static int              pulseIndex = 0;
    
    // Status Indicators
    private static Label connectedLabel;
    private static Label aiStatusLabel;
    private static Label sessionInfoLabel;
    private static Label healthLabel;

    // Focus & Special Actions
    private static StackPane focusOverlay;
    private static ImageView focusImgView;
    private static String    currentlyFocusedStudent;

    // Command Toggles
    private static Button standardModeBtn;
    private static Button ultraWebRtcBtn;
    private static Button aiToggleBtn;
    private static Button shareScreenBtn;

    // Caching Architecture
    private static final Map<String, StackPane> studentCards     = new LinkedHashMap<>();
    private static final Map<String, ImageView> studentImages    = new HashMap<>();
    private static final Map<String, Label>     handRaisedLabels = new HashMap<>();
    private static final Map<String, Circle>    statusDots       = new HashMap<>();
    private static final Map<String, Long>      lastGridUpdate   = new HashMap<>();

    private static boolean isUltraActive = false;
    private static boolean isSharingScreen = false;
    private static long startTime = System.currentTimeMillis();

    // Design System Colors - The Command Sentinel Palette (delegated to StitchStyles)
    public static final String C_SURFACE              = StitchStyles.C_SURFACE;
    public static final String C_SURFACE_CONTAINER    = StitchStyles.C_SURFACE_CONTAINER;
    public static final String C_SURFACE_HIGH         = StitchStyles.C_SURFACE_HIGH;
    public static final String C_SURFACE_HIGHEST      = StitchStyles.C_SURFACE_HIGHEST;
    public static final String C_SURFACE_LOWEST       = StitchStyles.C_SURFACE_LOWEST;
    public static final String C_PRIMARY              = StitchStyles.C_PRIMARY;
    public static final String C_PRIMARY_DIM          = "#00dbe9";
    public static final String C_PRIMARY_GLASS        = StitchStyles.C_PRIMARY_GLASS;
    public static final String C_SECONDARY            = "#dcb8ff";
    public static final String C_SECONDARY_CONTAINER  = StitchStyles.C_SECONDARY_CONTAINER;
    public static final String C_SUCCESS              = "#7af19c";
    public static final String C_WARNING              = "#ffc857";
    public static final String C_DANGER               = "#ffb4ab";
    public static final String C_ERROR_CONTAINER      = "#ffdad9";
    public static final String C_TERTIARY_CONTAINER   = "#aaf0ca";
    public static final String C_TEXT_MAIN            = StitchStyles.C_TEXT_MAIN;
    public static final String C_TEXT_MUTED           = StitchStyles.C_TEXT_MUTED;
    public static final String C_ON_PRIMARY_FIXED     = StitchStyles.C_ON_PRIMARY_FIXED;
    public static final String C_BORDER               = "rgba(255,255,255,0.05)";
    public static final String C_OUTLINE_VARIANT      = "rgba(59,73,75,0.15)";

    public static void show(Stage stage, User user) {
        if (server == null) {
            server = new KingServer();
            server.setScreenListener(new KingServer.BinaryScreenListener() {
                @Override public void onScreenUpdate(String name, String base64) {
                    Platform.runLater(() -> updateStudentScreen(name, base64));
                }
                @Override public void onBinaryUpdate(String name, Image image) {
                    Platform.runLater(() -> updateStudentScreenBinary(name, image));
                }
                @Override public void onShellOutput(String name, String output) {
                    Platform.runLater(() -> appendTerminal("--- " + name + " ---\n" + output));
                }
            });
            server.setStatusListener(new KingServer.ClientStatusListener() {
                @Override public void onClientConnected(String name) {
                    Platform.runLater(() -> updateCountLabel());
                }
                @Override public void onClientDisconnected(String name) {
                    Platform.runLater(() -> { 
                        removeStudentCard(name); 
                        updateCountLabel(); 
                        appendActivity("NETWORK", "Connection dropped: " + name, C_DANGER); 
                    });
                }
            });
            server.setExtendedListener((type, sender, payload) -> Platform.runLater(() -> {
                if (type == CommandPacket.Type.RAISE_HAND) updateHandRaised(sender, "UP".equals(payload));
            }));
            server.start();
            discoveryService = new DiscoveryService();
            discoveryService.startBroadcasting();
            AuditLogger.logSystem("King of Lab Admin dashboard mapped");
        }

        startTime = System.currentTimeMillis();

        // Base App Container
        BorderPane root = new BorderPane();
        root.setStyle(StitchStyles.appRoot());

        // UI Zones
        root.setTop(buildTopNavBar(user));

        HBox body = new HBox(0);
        body.setStyle("-fx-background-color: " + C_SURFACE + ";");
        HBox.setHgrow(body, Priority.ALWAYS);

        VBox sideNav = buildSideNavBar(user, stage);
        VBox mainCanvas = buildMainCanvas(stage, user);
        HBox.setHgrow(mainCanvas, Priority.ALWAYS);

        body.getChildren().addAll(sideNav, mainCanvas);
        root.setCenter(body);

        // Focus Overlay Stack
        StackPane rootStack = new StackPane(root);
        buildFocusOverlay();
        rootStack.getChildren().add(focusOverlay);

        Scene scene = new Scene(rootStack, 1440, 900);
        stage.setScene(scene);
        stage.setTitle("King of Lab | Command Sentinel");
        stage.setMaximized(true);

        SystemTrayManager.init(stage, "ADMIN", user);
        stage.setOnCloseRequest(e -> {
            e.consume();
            AuditLogger.logSystem("Admin dashboard closed offline");
            SystemTrayManager.hideWindow();
            System.exit(0);
        });
        stage.show();
        
        startSessionTimer();
        startPulseMonitor();
    }

    private static void startPulseMonitor() {
        Timeline t = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            double val = 0.3 + Math.random() * 0.4;
            pulseData[pulseIndex] = val;
            pulseIndex = (pulseIndex + 1) % pulseData.length;
            if (pulseMonitorCanvas != null) drawPulseMonitor();
        }));
        t.setCycleCount(Animation.INDEFINITE); t.play();
    }

    private static void drawPulseMonitor() {
        if (pulseMonitorCanvas == null) return;
        GraphicsContext gc = pulseMonitorCanvas.getGraphicsContext2D();
        double w = pulseMonitorCanvas.getWidth();
        double h = pulseMonitorCanvas.getHeight();
        gc.clearRect(0, 0, w, h);
        gc.setFill(Color.web(C_PRIMARY, 0.1));
        gc.beginPath();
        gc.moveTo(0, h);
        for (int i = 0; i < pulseData.length; i++) {
            int idx = (pulseIndex + i) % pulseData.length;
            double x = (double) i / pulseData.length * w;
            double y = h - pulseData[idx] * h;
            gc.lineTo(x, y);
        }
        gc.lineTo(w, h);
        gc.closePath();
        gc.fill();
        gc.setStroke(Color.web(C_PRIMARY_GLASS));
        gc.setLineWidth(1.5);
        gc.beginPath();
        for (int i = 0; i < pulseData.length; i++) {
            int idx = (pulseIndex + i) % pulseData.length;
            double x = (double) i / pulseData.length * w;
            double y = h - pulseData[idx] * h;
            if (i == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
        }
        gc.stroke();
    }

    // -----------------------------------------------------------------------
    // TOP NAVIGATION (Header)
    // -----------------------------------------------------------------------
    private static HBox buildTopNavBar(User user) {
        HBox topNav = new HBox(30);
        topNav.setPrefHeight(64);
        topNav.setAlignment(Pos.CENTER_LEFT);
        topNav.setPadding(new Insets(0, 32, 0, 32));
        // "No-Line" rule: avoid divider borders; use tonal shift instead.
        topNav.setStyle("-fx-background-color: rgba(10, 14, 20, 0.82);");

        // Logo
        VBox lBox = new VBox(-4);
        lBox.setAlignment(Pos.CENTER_LEFT);
        Label l1 = new Label("King of"); l1.setStyle("-fx-text-fill: " + C_PRIMARY + "; -fx-font-size: 18px; -fx-font-weight: bold; -fx-letter-spacing: 0.02em;");
        Label l2 = new Label("Lab");     l2.setStyle("-fx-text-fill: " + C_PRIMARY + "; -fx-font-size: 18px; -fx-font-weight: bold; -fx-letter-spacing: 0.02em;");
        lBox.getChildren().addAll(l1, l2);

        // Links
        HBox links = new HBox(32);
        links.setAlignment(Pos.CENTER);
        links.setPadding(new Insets(0, 0, 0, 32));
        Label lStu = new Label("STUDENTS"); lStu.setStyle("-fx-text-fill: " + C_PRIMARY + "; -fx-font-weight: bold; -fx-font-size: 12px; -fx-border-color: " + C_PRIMARY + "; -fx-border-width: 0 0 2 0; -fx-padding: 0 0 6 0; -fx-cursor: hand;");
        Label lNet = new Label("NETWORK");  lNet.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand;");
        Label lAi  = new Label("AI STATUS");lAi.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand;");
        Label lAn  = new Label("ANALYTICS");lAn.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand;");
        links.getChildren().addAll(lStu, lNet, lAi, lAn);

        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);

        // Health Indicators (Grouped Pill)
        HBox healthPill = new HBox(16);
        healthPill.setAlignment(Pos.CENTER);
        healthPill.setPadding(new Insets(8, 20, 8, 20));
        healthPill.setStyle(
            StitchStyles.glassPanel(0.55, 22) +
            "-fx-padding: 8 20 8 20;"
        );
        
        Circle dot = new Circle(3.5, Color.web(C_SUCCESS));
        dot.setEffect(new DropShadow(8, Color.web(C_SUCCESS, 0.8)));
        connectedLabel = new Label("0\nCONNECTED");
        connectedLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.8); -fx-font-weight: 900; -fx-font-size: 9px; -fx-label-padding: 0 0 0 -5; -fx-line-spacing: -2px; -fx-letter-spacing: 0.1em;");
        HBox bxConn = new HBox(8, dot, connectedLabel); bxConn.setAlignment(Pos.CENTER_LEFT);

        Label hIcn = new Label("📶"); hIcn.setStyle("-fx-text-fill: " + C_PRIMARY + ";");
        healthLabel = new Label("HEALTH:\n98%");
        healthLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.8); -fx-font-weight: 900; -fx-font-size: 9px; -fx-label-padding: 0 0 0 -5; -fx-line-spacing: -2px; -fx-letter-spacing: 0.1em;");
        HBox bxHealth = new HBox(6, hIcn, healthLabel); bxHealth.setAlignment(Pos.CENTER_LEFT);

        Label aIcn = new Label("🧠"); aIcn.setStyle("-fx-text-fill: " + C_SECONDARY + ";");
        aiStatusLabel = new Label("AI:\nON");
        aiStatusLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.8); -fx-font-weight: 900; -fx-font-size: 9px; -fx-line-spacing: -2px; -fx-letter-spacing: 0.1em;");
        HBox bxAi = new HBox(6, aIcn, aiStatusLabel); bxAi.setAlignment(Pos.CENTER_LEFT);

        healthPill.getChildren().addAll(bxConn, buildVDivider(), bxHealth, buildVDivider(), bxAi);

        // Profile Section with Pulse Monitor
        HBox profileSection = new HBox(16);
        profileSection.setAlignment(Pos.CENTER);

        pulseMonitorCanvas = new Canvas(80, 24);
        pulseMonitorCanvas.setStyle("-fx-background-color: transparent;");
        StackPane pulseContainer = new StackPane(pulseMonitorCanvas);
        pulseContainer.setStyle("-fx-background-color: rgba(0,240,255,0.05); -fx-background-radius: 8px; -fx-border-color: rgba(0,240,255,0.1); -fx-border-radius: 8px; -fx-padding: 4;");
        StackPane.setAlignment(pulseContainer, Pos.CENTER_LEFT);
        StackPane.setMargin(pulseContainer, new Insets(0, 16, 0, 0));

        Button bBell = new Button("🔔"); bBell.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(223,226,235,0.6); -fx-cursor: hand;");
        Button bGear = new Button("⚙"); bGear.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(223,226,235,0.6); -fx-cursor: hand;");

        VBox adminTxt = new VBox(-1);
        adminTxt.setAlignment(Pos.CENTER_RIGHT);
        Label p1 = new Label("Admin Panel"); p1.setStyle("-fx-text-fill: #DFE2EB; -fx-font-weight: bold; -fx-font-size: 11px;");
        Label p2 = new Label("CHIEF OVERSEER"); p2.setStyle("-fx-text-fill: rgba(223,226,235,0.5); -fx-font-weight: 900; -fx-font-size: 8px; -fx-letter-spacing: 0.1em;");
        adminTxt.getChildren().addAll(p1, p2);

        StackPane avatarPane = new StackPane();
        Rectangle avBg = new Rectangle(32, 32);
        avBg.setArcWidth(12); avBg.setArcHeight(12);
        avBg.setFill(new LinearGradient(0,0,1,1,true,CycleMethod.NO_CYCLE, new Stop(0, Color.web("#00dbe9")), new Stop(1, Color.web("#7701d0"))));
        Label avT = new Label("👤"); avT.setStyle("-fx-text-fill: white; -fx-font-size: 18px;");
        avatarPane.getChildren().addAll(avBg, avT);

        profileSection.getChildren().addAll(pulseContainer, bBell, bGear, adminTxt, avatarPane);

        topNav.getChildren().addAll(lBox, links, spacer, healthPill, profileSection);
        return topNav;
    }

    private static Region buildVDivider() {
        Region r = new Region();
        r.setPrefWidth(1); r.setPrefHeight(20);
        // Softer "ghost divider" instead of hard line.
        r.setStyle("-fx-background-color: rgba(223,226,235,0.08);");
        return r;
    }

    // -----------------------------------------------------------------------
    // SIDE NAVIGATION (COMMAND SENTINEL)
    // -----------------------------------------------------------------------
    private static VBox buildSideNavBar(User user, Stage stage) {
        VBox side = new VBox(0);
        side.setPrefWidth(256);
        side.setStyle("-fx-background-color: #0b0f14;");
        
        // Sentinel Overlay Badge
        HBox badge = new HBox(12);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(16));
        badge.setStyle(StitchStyles.glassPanel(0.55, 14));
        VBox.setMargin(badge, new Insets(30, 24, 40, 24));
        
        StackPane shieldBox = new StackPane();
        Rectangle sb = new Rectangle(36, 36); sb.setFill(Color.web("rgba(0,240,255,0.1)")); sb.setArcWidth(12); sb.setArcHeight(12);
        Label sIcon = new Label("🛡"); sIcon.setStyle("-fx-text-fill: " + C_PRIMARY + "; -fx-font-size: 18px;");
        shieldBox.getChildren().addAll(sb, sIcon);

        VBox sInfo = new VBox(1);
        Label sTitle = new Label("COMMAND\nSENTINEL");
        sTitle.setStyle("-fx-text-fill: " + C_PRIMARY + "; -fx-font-size: 10px; -fx-font-weight: 900; -fx-line-spacing: -2px; -fx-letter-spacing: 0.1em;");
        sessionInfoLabel = new Label("Session: 00h 00m");
        sessionInfoLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 10px;");
        sInfo.getChildren().addAll(sTitle, sessionInfoLabel);
        badge.getChildren().addAll(shieldBox, sInfo);

        // Core Actions
        VBox navMenu = new VBox(8);
        navMenu.setPadding(new Insets(0, 16, 0, 16));

        Button navClass = buildSideAction("🔓", "CLASS CONTROL", true);
        Button navNet = buildSideAction("📶", "NETWORK GUARD", false);
        navNet.setOnAction(e -> { 
            HostsFileManager.blockSites(); 
            appendActivity("NETWORK", "Global firewall restricted", C_WARNING); 
        });

        Button navPower = buildSideAction("⏻", "POWER CENTER", false);
        aiToggleBtn = buildSideAction("🧠", "AI COMMAND", false);
        aiToggleBtn.setOnAction(e -> toggleAi(user.getUsername()));

        navMenu.getChildren().addAll(navClass, navNet, navPower, aiToggleBtn);
        
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);

        // Bottom Broadcast
        VBox bottomActions = new VBox(20);
        bottomActions.setPadding(new Insets(32, 24, 32, 24));
        bottomActions.setStyle("-fx-background-color: rgba(0,0,0,0.08);");
        
        Button broadcastBtn = new Button("BROADCAST MESSAGE");
        broadcastBtn.setMaxWidth(Double.MAX_VALUE);
        broadcastBtn.setPadding(new Insets(16, 0, 16, 0));
        broadcastBtn.setStyle(
            StitchStyles.gradientPrimaryCta(12) +
            "-fx-font-size: 11px;" +
            "-fx-padding: 16 0 16 0;" +
            "-fx-effect: dropshadow(gaussian, rgba(0, 240, 255, 0.22), 26, 0, 0, 6);"
        );
        broadcastBtn.setOnAction(e -> promptGlobalMessage());

        HBox terminalLogLinks = new HBox(16);
        Label tLink = new Label("📺 TERMINAL"); tLink.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 9px; -fx-font-weight: 900; -fx-cursor: hand;");
        Label lLink = new Label("🕒 LOGS"); lLink.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 9px; -fx-font-weight: 900; -fx-cursor: hand;");
        terminalLogLinks.getChildren().addAll(tLink, lLink);
        
        bottomActions.getChildren().addAll(broadcastBtn, terminalLogLinks);

        side.getChildren().addAll(badge, navMenu, spacer, bottomActions);
        return side;
    }

    private static Button buildSideAction(String icon, String text, boolean active) {
        String baseColor = active ? C_PRIMARY : "rgba(223, 226, 235, 0.5)";
        String bg = active ? "rgba(0, 240, 255, 0.05)" : "transparent";
        String border = active ? "rgba(0, 240, 255, 0.1)" : "transparent";

        Button btn = new Button(icon + "   " + text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setPadding(new Insets(16, 20, 16, 20));
        btn.setStyle(
            "-fx-background-color: " + bg + ";" + 
            "-fx-text-fill: " + baseColor + ";" + 
            "-fx-font-size: 12px; -fx-font-weight: 900; -fx-letter-spacing: 0.1em; " +
            "-fx-background-radius: 12px; -fx-border-color: " + border + "; -fx-border-radius: 12px; -fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> { if(!active) btn.setStyle(btn.getStyle().replace("transparent", "rgba(255,255,255,0.05)").replace("0.5)", "1.0)")); });
        btn.setOnMouseExited(e -> { if(!active) btn.setStyle(btn.getStyle().replace("rgba(255,255,255,0.05)", "transparent").replace("1.0)", "0.5)")); });
        return btn;
    }

    // -----------------------------------------------------------------------
    // MAIN CANVAS (Ribbons and Grid)
    // -----------------------------------------------------------------------
    private static VBox buildMainCanvas(Stage stage, User admin) {
        VBox mainCanvas = new VBox(0);
        mainCanvas.setStyle("-fx-background-color: " + C_SURFACE + ";");

        // Command Ribbon
        HBox ribbon = new HBox(40);
        ribbon.setAlignment(Pos.CENTER_LEFT);
        ribbon.setPadding(new Insets(24, 40, 24, 40));
        // No-line rule: remove hard divider border; keep an ultra-subtle tonal band.
        ribbon.setStyle("-fx-background-color: rgba(255,255,255,0.015);");

        // Ribbon Block 1: Class Access
        VBox bClass = new VBox(12);
        Label lClass = new Label("CLASS ACCESS"); lClass.setStyle("-fx-text-fill: rgba(223,226,235,0.4); -fx-font-size: 9px; -fx-font-weight: 900; -fx-letter-spacing: 0.25em;");
        HBox hcClass = new HBox(0); hcClass.setStyle(StitchStyles.glassPanel(0.45, 12) + "-fx-padding: 4;");
        Button lockBtn = ribbonPillBtn("🔒 Lock", true); lockBtn.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.LOCK, "{}")));
        Button focusBtn = ribbonPillBtn("🎯 Focus", false); focusBtn.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.UNLOCK, "{}")));
        hcClass.getChildren().addAll(lockBtn, focusBtn);
        bClass.getChildren().addAll(lClass, hcClass);

        // Ribbon Block 2: Connectivity
        VBox bConn = new VBox(12);
        Label lConn = new Label("CONNECTIVITY"); lConn.setStyle("-fx-text-fill: rgba(223,226,235,0.4); -fx-font-size: 9px; -fx-font-weight: 900; -fx-letter-spacing: 0.25em;");
        HBox hcConn = new HBox(0); hcConn.setStyle(StitchStyles.glassPanel(0.45, 12) + "-fx-padding: 4;");
        Button blockBtn = ribbonPillBtn("🚫 Block", false); blockBtn.setOnAction(e -> openUrlOnAll());
        Button allowBtn = ribbonPillBtn("✅ Allow", false); allowBtn.setStyle(allowBtn.getStyle().replace("transparent", "rgba(0,240,255,0.1)").replace(C_TEXT_MUTED, C_PRIMARY));
        hcConn.getChildren().addAll(blockBtn, allowBtn);
        bConn.getChildren().addAll(lConn, hcConn);

        // Ribbon Block 3: Energy
        VBox bEgy = new VBox(12);
        Label lEgy = new Label("ENERGY"); lEgy.setStyle("-fx-text-fill: rgba(223,226,235,0.4); -fx-font-size: 9px; -fx-font-weight: 900; -fx-letter-spacing: 0.25em;");
        HBox hcEgy = new HBox(12);
        Button restBtn = ribbonCirBtn("⟳"); restBtn.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.RESTART, "{}")));
        Button shutBtn = ribbonCirBtn("⏻"); shutBtn.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.SHUTDOWN, "{}")));
        hcEgy.getChildren().addAll(restBtn, shutBtn);
        bEgy.getChildren().addAll(lEgy, hcEgy);

        Region rbSpacer = new Region(); HBox.setHgrow(rbSpacer, Priority.ALWAYS);

        // Ribbon Block 4: Node Search
        VBox bSrch = new VBox(12);
        Label lSrch = new Label("NODE SEARCH"); lSrch.setStyle("-fx-text-fill: rgba(223,226,235,0.4); -fx-font-size: 9px; -fx-font-weight: 900; -fx-letter-spacing: 0.25em;");
        StackPane srchPane = new StackPane();
        TextField srchFld = new TextField(); 
        srchFld.setPromptText("Enter workstation ID...");
        srchFld.setStyle(
            "-fx-background-color: rgba(10,14,20,0.55);" +
            "-fx-background-radius: 12px;" +
            "-fx-text-fill: " + C_TEXT_MAIN + ";" +
            "-fx-prompt-text-fill: rgba(223,226,235,0.28);" +
            "-fx-pref-width: 220;" +
            "-fx-padding: 10 16;" +
            "-fx-font-size: 11px;"
        );
        Label mag = new Label("🔍"); mag.setStyle("-fx-text-fill: rgba(223,226,235,0.2);");
        StackPane.setAlignment(mag, Pos.CENTER_RIGHT); StackPane.setMargin(mag, new Insets(0,16,0,0));
        srchPane.getChildren().addAll(srchFld, mag);
        bSrch.getChildren().addAll(lSrch, srchPane);

        ribbon.getChildren().addAll(bClass, buildRibbonDiv(), bConn, buildRibbonDiv(), bEgy, rbSpacer, bSrch);

        // Student Grid 
        thumbnailGrid = new FlowPane(32, 32);
        thumbnailGrid.setPadding(new Insets(40));
        thumbnailGrid.setStyle("-fx-background-color: transparent;");

        ScrollPane scrollGrid = new ScrollPane(thumbnailGrid);
        scrollGrid.setFitToWidth(true);
        scrollGrid.setFitToHeight(true);
        scrollGrid.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-control-inner-background: transparent;");
        VBox.setVgrow(scrollGrid, Priority.ALWAYS);
        
        mainCanvas.getChildren().addAll(ribbon, scrollGrid, buildBottomPanel());
        return mainCanvas;
    }

    private static Button ribbonPillBtn(String text, boolean active) {
        Button btn = new Button(text);
        if (active) {
            btn.setStyle("-fx-background-color: " + C_PRIMARY_DIM + "; -fx-text-fill: #002022; -fx-font-weight: 900; -fx-font-size: 11px; -fx-padding: 8 24; -fx-background-radius: 8px; -fx-cursor: hand;");
        } else {
            btn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + C_TEXT_MUTED + "; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 8 24; -fx-background-radius: 8px; -fx-cursor: hand;");
            btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle().replace("transparent", "rgba(255,255,255,0.05)").replace(C_TEXT_MUTED, C_TEXT_MAIN)));
            btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle().replace("rgba(255,255,255,0.05)", "transparent").replace(C_TEXT_MAIN, C_TEXT_MUTED)));
        }
        return btn;
    }

    private static Button ribbonCirBtn(String icon) {
        Button btn = new Button(icon);
        btn.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-text-fill: rgba(223,226,235,0.6); -fx-font-size: 14px; -fx-min-width: 40px; -fx-min-height: 40px; -fx-background-radius: 12px; -fx-border-color: rgba(255,255,255,0.05); -fx-border-radius: 12px; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle().replace("0.03)", "0.1)")));
        btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle().replace("0.1)", "0.03)")));
        return btn;
    }

    private static Region buildRibbonDiv() {
        Region r = new Region();
        r.setPrefWidth(1); r.setPrefHeight(40);
        r.setStyle("-fx-background-color: rgba(223,226,235,0.06);");
        return r;
    }

    // -----------------------------------------------------------------------
    // GRID CARDS (Mapping exactly to PC-01 / PC-03 states)
    // -----------------------------------------------------------------------
    private static void addStudentCard(String name, Image screenshot) {
        if (studentCards.containsKey(name)) return;

        StackPane card = new StackPane();
        card.setPrefSize(270, 240);
        // Card becomes an "Action Tier" glass panel rather than a bordered tile.
        card.setStyle(
            "-fx-background-color: rgba(28,32,38,0.82);" +
            "-fx-background-radius: 20px;"
        );
        card.setEffect(new DropShadow(BlurType.GAUSSIAN, Color.web("#000000", 0.4), 15, 0, 0, 8));

        // Top Half: image
        ImageView imgView = new ImageView();
        imgView.setFitWidth(268); imgView.setFitHeight(150); 
        imgView.setPreserveRatio(false);
        if (screenshot != null) imgView.setImage(screenshot);
        
        Rectangle clip = new Rectangle(268, 150); clip.setArcWidth(20); clip.setArcHeight(20);
        imgView.setClip(clip);
        
        ColorAdjust dim = new ColorAdjust(); dim.setBrightness(-0.3);
        imgView.setEffect(dim);
        StackPane imgTop = new StackPane(imgView);
        StackPane.setAlignment(imgTop, Pos.TOP_CENTER);

        // Internal Tags
        HBox pActive = new HBox(6);
        pActive.setAlignment(Pos.CENTER); pActive.setPadding(new Insets(4, 10, 4, 10));
        pActive.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 6px; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 6px;");
        Circle cA = new Circle(3, Color.web(C_SUCCESS)); cA.setEffect(new DropShadow(5, Color.web(C_SUCCESS)));
        statusDots.put(name, cA);
        Label lAc = new Label("ACTIVE"); lAc.setStyle("-fx-text-fill: white; -fx-font-weight: 900; -fx-font-size: 8px; -fx-letter-spacing: 0.1em;");
        pActive.getChildren().addAll(cA, lAc);
        StackPane.setAlignment(pActive, Pos.BOTTOM_LEFT); StackPane.setMargin(pActive, new Insets(0,0,12,12));

        Label pingL = new Label("12MS");
        pingL.setStyle("-fx-background-color: rgba(0,0,0,0.4); -fx-text-fill: rgba(255,255,255,0.6); -fx-font-weight: bold; -fx-font-size: 8px; -fx-padding: 4 8; -fx-background-radius: 6px; -fx-border-color: rgba(255,255,255,0.05); -fx-border-radius: 6px;");
        StackPane.setAlignment(pingL, Pos.TOP_RIGHT); StackPane.setMargin(pingL, new Insets(12, 12, 0, 0));

        // Default Hover Menu
        HBox hoverAction = new HBox(12); hoverAction.setAlignment(Pos.CENTER);
        hoverAction.setStyle("-fx-background-color: rgba(0,0,0,0.6);"); hoverAction.setOpacity(0);
        hoverAction.setPrefHeight(150);
        Button hvChat = cardBtn("💬", "white"); hvChat.setOnAction(e -> promptMessage(name));
        Button hvEye  = cardBtn("👁", C_PRIMARY); hvEye.setStyle(hvEye.getStyle().replace("rgba(255,255,255,0.1)", "linear-gradient(to bottom right, #00dbe9 0%, #00f0ff 100%)").replace(C_PRIMARY, "#002022") + "-fx-font-size: 24px;"); hvEye.setOnAction(e -> openFullScreenView(name, imgView.getImage()));
        Button hvLock = cardBtn("🔒", C_DANGER); hvLock.setOnAction(e -> server.sendToClient(name, pkt(CommandPacket.Type.LOCK, "{}")));
        hoverAction.getChildren().addAll(hvChat, hvEye, hvLock);

        // PC-03 Support Needed overlay
        VBox supportOverlay = new VBox(12); supportOverlay.setAlignment(Pos.CENTER);
        supportOverlay.setStyle("-fx-background-color: rgba(119, 1, 208, 0.2);"); supportOverlay.setVisible(false);
        HBox alertBox = new HBox(8); alertBox.setStyle("-fx-background-color: " + C_SURFACE + "; -fx-border-color: " + C_SECONDARY + "; -fx-border-radius: 12px; -fx-background-radius: 12px; -fx-padding: 8 16;");
        Label alI = new Label("✋"); alI.setStyle("-fx-text-fill: " + C_SECONDARY + ";");
        Label alT = new Label("SUPPORT NEEDED"); alT.setStyle("-fx-text-fill: white; -fx-font-weight: 900; -fx-font-size: 9px; -fx-letter-spacing: 0.15em;");
        alertBox.getChildren().addAll(alI, alT); alertBox.setAlignment(Pos.CENTER);
        Button respBtn = new Button("RESPOND NOW");
        respBtn.setStyle("-fx-background-color: " + C_SECONDARY + "; -fx-text-fill: #2c0051; -fx-font-weight: 900; -fx-font-size: 9px; -fx-padding: 8 20; -fx-background-radius: 8px; -fx-cursor: hand;");
        respBtn.setOnAction(e -> { supportOverlay.setVisible(false); promptMessage(name); });
        supportOverlay.getChildren().addAll(alertBox, respBtn);
        handRaisedLabels.put(name, alT); // store reference to trigger alert

        imgTop.getChildren().addAll(hoverAction, supportOverlay, pActive, pingL);

        // Bottom Info Card Text
        HBox infoBox = new HBox();
        infoBox.setAlignment(Pos.CENTER_LEFT);
        infoBox.setPadding(new Insets(16, 20, 16, 20));
        
        VBox nBox = new VBox(2);
        Label nL = new Label(name); nL.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        Label sL = new Label("Student Node"); sL.setStyle("-fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 10px;");
        nBox.getChildren().addAll(nL, sL);

        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        Button moreBtn = new Button("⋮");
        moreBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 16px; -fx-cursor: hand;");
        moreBtn.setOnAction(e -> promptCommand(name));
        infoBox.getChildren().addAll(nBox, space, moreBtn);

        StackPane.setAlignment(infoBox, Pos.BOTTOM_CENTER);
        card.getChildren().addAll(imgTop, infoBox);

        // Hover events
        card.setOnMouseEntered(e -> {
            card.setStyle(
                "-fx-background-color: rgba(28, 32, 38, 0.88);" +
                "-fx-background-radius: 20px;" +
                "-fx-translate-y: -4;"
            );
            dim.setBrightness(0);
            if (!supportOverlay.isVisible()) hoverAction.setOpacity(1.0);
        });
        card.setOnMouseExited(e -> {
            card.setStyle(
                "-fx-background-color: rgba(28,32,38,0.82);" +
                "-fx-background-radius: 20px;" +
                "-fx-translate-y: 0;"
            );
            dim.setBrightness(-0.3);
            hoverAction.setOpacity(0.0);
        });

        thumbnailGrid.getChildren().add(card);
        studentCards.put(name, card);
        studentImages.put(name, imgView);
        updateCountLabel();
    }

    private static Button cardBtn(String ic, String fill) {
        Button b = new Button(ic);
        b.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + fill + "; -fx-font-size: 16px; -fx-background-radius: 50em; -fx-padding: 10; -fx-cursor: hand;");
        b.setOnMouseEntered(e -> { b.setScaleX(1.1); b.setScaleY(1.1); });
        b.setOnMouseExited(e -> { b.setScaleX(1.0); b.setScaleY(1.0); });
        return b;
    }

    // -----------------------------------------------------------------------
    // BOTTOM SPLIT PANELS
    // -----------------------------------------------------------------------
    private static HBox buildBottomPanel() {
        HBox split = new HBox();
        split.setPrefHeight(250);
        split.setStyle("-fx-background-color: rgba(0,0,0,0.08);");

        // SECURITY LEDGER (Left)
        VBox ledger = new VBox(); ledger.setPrefWidth(600);
        ledger.setStyle("-fx-background-color: rgba(13, 17, 23, 0.45);");
        
        HBox lHdr = new HBox(12); lHdr.setPadding(new Insets(16, 32, 16, 32)); lHdr.setAlignment(Pos.CENTER_LEFT);
        lHdr.setStyle("-fx-background-color: rgba(255,255,255,0.01);");
        Label i1 = new Label("≡ SECURITY LEDGER"); i1.setStyle("-fx-text-fill: white; -fx-font-weight: 900; -fx-font-size: 10px; -fx-letter-spacing: 0.15em;");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        HBox liveTag = new HBox(6); liveTag.setAlignment(Pos.CENTER); liveTag.setPadding(new Insets(4,10,4,10)); liveTag.setStyle("-fx-background-color: rgba(0,240,255,0.1); -fx-background-radius: 8px;");
        Circle ld = new Circle(3, Color.web(C_PRIMARY)); Label ll = new Label("LIVE"); ll.setStyle("-fx-text-fill: " + C_PRIMARY + "; -fx-font-weight: 900; -fx-font-size: 8px; -fx-letter-spacing: 0.1em;");
        liveTag.getChildren().addAll(ld, ll);
        lHdr.getChildren().addAll(i1, sp, liveTag);

        activityFeed = new ListView<>();
        activityFeed.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-font-family: 'Inter', monospace; -fx-padding: 16;");
        VBox.setVgrow(activityFeed, Priority.ALWAYS);
        ledger.getChildren().addAll(lHdr, activityFeed);

        // TERMINAL TABS (Right)
        VBox term = new VBox();
        HBox.setHgrow(term, Priority.ALWAYS);
        term.setStyle("-fx-background-color: rgba(0,0,0,0.2);");

        HBox tHdr = new HBox(0); tHdr.setStyle("-fx-background-color: rgba(255,255,255,0.01);");
        Label tb1 = new Label("TERMINAL"); tb1.setStyle("-fx-text-fill: " + C_PRIMARY + "; -fx-font-weight: 900; -fx-font-size: 10px; -fx-letter-spacing: 0.25em; -fx-padding: 16 32; -fx-border-color: " + C_PRIMARY + "; -fx-border-width: 0 0 2 0; -fx-background-color: rgba(0,240,255,0.02);");
        Label tb2 = new Label("CHAT"); tb2.setStyle("-fx-text-fill: rgba(255,255,255,0.3); -fx-font-weight: 900; -fx-font-size: 10px; -fx-letter-spacing: 0.25em; -fx-padding: 16 32;");
        Label tb3 = new Label("AI DIAGNOSTICS"); tb3.setStyle("-fx-text-fill: rgba(255,255,255,0.3); -fx-font-weight: 900; -fx-font-size: 10px; -fx-letter-spacing: 0.25em; -fx-padding: 16 32;");
        tHdr.getChildren().addAll(tb1, tb2, tb3);

        termOutput = new TextArea();
        termOutput.setEditable(false);
        termOutput.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-text-fill: rgba(0,240,255,0.6); -fx-padding: 16;");
        VBox.setVgrow(termOutput, Priority.ALWAYS);

        appendTerminal("root@king_of_lab:~$ monitor --health --detailed\nAnalyzing neural link integrity across segment...\nLatency stable at 14ms mean.\n>>> CLUSTER STATUS: OPTIMAL\n");

        HBox tInp = new HBox(12); tInp.setPadding(new Insets(0, 16, 16, 16)); tInp.setAlignment(Pos.CENTER_LEFT);
        Label pr = new Label("root@king_of_lab:~$"); pr.setStyle("-fx-text-fill: " + C_SUCCESS + "; -fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        TextField termF = new TextField(); 
        termF.setPromptText("broadcast --priority high ...");
        termF.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-family: 'Consolas', monospace;");
        HBox.setHgrow(termF, Priority.ALWAYS);
        termF.setOnAction(e -> {
            String c = termF.getText().trim();
            if(!c.isEmpty()) {
                server.broadcast(pkt(CommandPacket.Type.SHELL, c));
                appendTerminal("root@king_of_lab:~$ " + c + "\nDistributing encrypted packet to all nodes... [Done]");
                termF.clear();
            }
        });
        tInp.getChildren().addAll(pr, termF);

        term.getChildren().addAll(tHdr, termOutput, tInp);
        split.getChildren().addAll(ledger, term);
        return split;
    }


    // -----------------------------------------------------------------------
    // FULLSCREEN OVERLAY
    // -----------------------------------------------------------------------
    private static void buildFocusOverlay() {
        focusOverlay = new StackPane();
        focusOverlay.setVisible(false);
        focusOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.85);"); 
        
        focusImgView = new ImageView();
        focusImgView.setPreserveRatio(true);
        focusImgView.fitWidthProperty().bind(focusOverlay.widthProperty().multiply(0.95));
        focusImgView.fitHeightProperty().bind(focusOverlay.heightProperty().multiply(0.85));

        focusOverlay.setFocusTraversable(true);
        focusOverlay.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) hideFocusView(); });

        Button closeBtn = new Button("✕ EXIT FOCUS");
        closeBtn.setStyle("-fx-background-color: rgba(255,0,0,0.2); -fx-text-fill: " + C_DANGER + "; -fx-padding: 12 32; -fx-font-weight: bold; -fx-background-radius: 12px; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> hideFocusView());
        
        StackPane.setAlignment(closeBtn, Pos.BOTTOM_CENTER);
        StackPane.setMargin(closeBtn, new Insets(0, 0, 40, 0));
        focusOverlay.getChildren().addAll(focusImgView, closeBtn);
    }

    private static void openFullScreenView(String name, Image screenshot) {
        currentlyFocusedStudent = name;
        focusImgView.setImage(screenshot);
        focusOverlay.setVisible(true);
        focusOverlay.requestFocus();
    }
    private static void hideFocusView() { currentlyFocusedStudent = null; focusOverlay.setVisible(false); }


    // -----------------------------------------------------------------------
    // DATA BINDINGS & INTERNAL LOGIC
    // -----------------------------------------------------------------------
    private static void updateStudentScreenBinary(String name, Image image) {
        try {
            PerformanceMonitor.recordFrame();
            if (studentImages.containsKey(name)) {
                long now = System.currentTimeMillis();
                long last = lastGridUpdate.getOrDefault(name, 0L);
                if (now - last > 100) { studentImages.get(name).setImage(image); lastGridUpdate.put(name, now); }
            } else {
                addStudentCard(name, image);
                lastGridUpdate.put(name, System.currentTimeMillis());
            }
            if (name.equals(currentlyFocusedStudent) && focusOverlay.isVisible()) focusImgView.setImage(image);
        } catch (Exception e) {}
    }

    private static void updateStudentScreen(String name, String base64) {
        try { updateStudentScreenBinary(name, new Image(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))); } catch (Exception e) {}
    }

    private static void removeStudentCard(String name) {
        StackPane card = studentCards.remove(name);
        if (card != null) thumbnailGrid.getChildren().remove(card);
        studentImages.remove(name); handRaisedLabels.remove(name); statusDots.remove(name); lastGridUpdate.remove(name);
    }

    private static void updateCountLabel() {
        int n = studentCards.size();
        if (connectedLabel != null) connectedLabel.setText(n + "\nCONNECTED");
    }

    private static void updateHandRaised(String student, boolean up) {
        StackPane card = studentCards.get(student);
        if(card != null && card.getChildren().get(0) instanceof StackPane) {
            StackPane imgTop = (StackPane) card.getChildren().get(0);
            if(imgTop.getChildren().size() > 1 && imgTop.getChildren().get(1) instanceof VBox) {
                imgTop.getChildren().get(1).setVisible(up);
            }
        }
        if (up) appendActivity("INCIDENT", "Support requested at " + student, C_SECONDARY);
    }

    private static void startSessionTimer() {
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long sec = (System.currentTimeMillis() - startTime) / 1000;
            if(sessionInfoLabel != null) sessionInfoLabel.setText(String.format("Session: %02dh %02dm", (sec / 3600), ((sec / 60) % 60)));
        }));
        t.setCycleCount(Animation.INDEFINITE); t.play();
    }

    private static void appendActivity(String type, String msg, String colorHex) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
        Platform.runLater(() -> {
            // Using standard strings parsed via javaFX CSS node rendering trick if needed, or plain text
            String log = String.format("%s   [%s] %s", time, type, msg);
            activityFeed.getItems().add(log);
            activityFeed.scrollTo(activityFeed.getItems().size() - 1);
        });
    }

    private static void appendTerminal(String msg) {
        if (termOutput != null) termOutput.appendText(msg);
    }

    private static void toggleAi(String adm) {
        Config.aiEnabled = !Config.aiEnabled;
        server.broadcast(pkt(CommandPacket.Type.AI_TOGGLE, Config.aiEnabled ? "ENABLE" : "DISABLE"));
        if(aiStatusLabel != null) aiStatusLabel.setText("AI:\n" + (Config.aiEnabled ? "ON" : "OFF"));
    }

    private static void promptGlobalMessage() {
        TextInputDialog d = new TextInputDialog(); d.setHeaderText("Global Broadcast Message");
        d.showAndWait().ifPresent(msg -> {
            if (!msg.trim().isEmpty()) {
                server.broadcast(pkt(CommandPacket.Type.MSG, msg));
                appendActivity("MSG", "Broadcast transmitted globally", C_PRIMARY);
            }
        });
    }

    private static void promptMessage(String name) {
        TextInputDialog d = new TextInputDialog(); d.setHeaderText("Message to " + name);
        d.showAndWait().ifPresent(msg -> {
            if (!msg.trim().isEmpty()) { server.sendToClient(name, pkt(CommandPacket.Type.MSG, msg)); appendActivity("MSG", "Private to " + name, C_SUCCESS); }
        });
    }

    private static void promptCommand(String name) {
        TextInputDialog d = new TextInputDialog(); d.setHeaderText("Shell Command for " + name);
        d.showAndWait().ifPresent(c -> {
            if (!c.trim().isEmpty()) { server.sendToClient(name, pkt(CommandPacket.Type.SHELL, c)); appendActivity("SHELL", "Executed on " + name, C_SUCCESS); }
        });
    }

    private static void openUrlOnAll() {
        TextInputDialog d = new TextInputDialog("https://"); d.setHeaderText("Broadcast URL");
        d.showAndWait().ifPresent(url -> { if(!url.trim().isEmpty()) server.broadcast(pkt(CommandPacket.Type.OPEN_URL, url)); });
    }

    private static CommandPacket pkt(CommandPacket.Type t, String p) { return new CommandPacket(t, "ADMIN", p); }
}
