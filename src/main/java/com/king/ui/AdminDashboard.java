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
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.*;
import javafx.util.Duration;

import java.io.*;
import java.util.*;

/**
 * King of Lab — Admin Dashboard v4 (Stitch Hologram Design).
 * UI completely overhauled to match "The Tactical Hologram" specification.
 */
public class AdminDashboard {

    private static KingServer      server;
    private static DiscoveryService discoveryService;
    
    // UI Nodes
    private static FlowPane         thumbnailGrid;
    private static ListView<String> activityFeed;
    private static TextArea         termOutput;
    private static Label            connectedLabel;
    private static Label            aiStatusLabel;
    private static Label            healthLabel;
    private static Label            sessionTimerLabel;
    
    private static StackPane        focusOverlay;
    private static ImageView        focusImgView;
    private static String           currentlyFocusedStudent;

    private static Button           standardModeBtn;
    private static Button           ultraWebRtcBtn;
    private static Button           aiToggleBtn;
    private static Button           shareScreenBtn;

    // State Maps
    private static final Map<String, StackPane> studentCards     = new LinkedHashMap<>();
    private static final Map<String, ImageView> studentImages    = new HashMap<>();
    private static final Map<String, Label>     handRaisedLabels = new HashMap<>();
    private static final Map<String, Circle>    statusDots       = new HashMap<>();
    private static final Map<String, Long>      lastGridUpdate   = new HashMap<>();

    private static boolean isUltraActive = false;
    private static boolean isSharingScreen = false;
    private static long startTime = System.currentTimeMillis();

    // Premium Color System from DESIGN.md
    public static final String SURFACE           = "#10141A";
    public static final String SURFACE_LOW       = "#181C22";
    public static final String SURFACE_CONTAINER = "#1C2026";
    public static final String SURFACE_HIGH      = "#262A31";
    public static final String SURFACE_LOWEST    = "#0A0E14";
    public static final String PRIMARY_CONTAINER = "#00F0FF";
    public static final String SECONDARY         = "#DCB8FF";
    public static final String SECONDARY_CONT    = "#7701D0";
    public static final String SUCCESS_COLOR     = "#7AF19C";
    public static final String WARNING_COLOR     = "#FFC857";
    public static final String DANGER_COLOR      = "#FFB4AB";

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
                        appendActivity("DISCONNECTED", name + " left the session", DANGER_COLOR); 
                    });
                }
            });
            server.setExtendedListener((type, sender, payload) -> Platform.runLater(() -> {
                if (type == CommandPacket.Type.RAISE_HAND) updateHandRaised(sender, "UP".equals(payload));
            }));
            server.start();
            discoveryService = new DiscoveryService();
            discoveryService.startBroadcasting();
            AuditLogger.logSystem("King of Lab Admin started");
        }

        startTime = System.currentTimeMillis();

        // Main Layout Structure
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        
        // Load Custom Fonts (Silently fails if not reachable and falls back)
        try {
            Font.loadFont("https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;700&display=swap", 12);
            Font.loadFont("https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap", 12);
        } catch (Exception ignored) {}

        // Applying Global Stylesheet
        try {
            root.getStylesheets().add(AdminDashboard.class.getResource("/stitch.css").toExternalForm());
        } catch (Exception e) {
            AuditLogger.logError("CSS", "Could not load stitch.css");
        }

        // 1. Top Navigation Bar
        root.setTop(buildTopNavBar(user));

        // Center split containing Left Nav and Main Area
        HBox body = new HBox(0);
        body.setStyle("-fx-background-color: " + SURFACE_LOWEST + ";");
        HBox.setHgrow(body, Priority.ALWAYS);

        // 2. Left Side Navigation
        VBox sideNav = buildSideNavBar(user);
        
        // 3. Main Flex Canvas
        VBox mainCanvas = buildMainCanvas(stage, user);
        HBox.setHgrow(mainCanvas, Priority.ALWAYS);

        body.getChildren().addAll(sideNav, mainCanvas);
        root.setCenter(body);

        // Ensure proper layer stacking for focus view
        StackPane rootStack = new StackPane(root);
        buildFocusOverlay();
        rootStack.getChildren().add(focusOverlay);

        Scene scene = new Scene(rootStack, 1366, 800);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — Command Sentinel");
        stage.setMaximized(true);

        SystemTrayManager.init(stage, "ADMIN", user);
        stage.setOnCloseRequest(e -> {
            e.consume();
            AttendanceTracker.generateAttendanceCSV().forEach(f -> appendTerminal("[ATTENDANCE]: " + f));
            AuditLogger.logSystem("Admin dashboard closed");
            SystemTrayManager.hideWindow();
        });
        stage.show();

        startSessionTimer();
    }

    // -----------------------------------------------------------------------
    // TOP NAVIGATION BAR
    // -----------------------------------------------------------------------
    private static HBox buildTopNavBar(User user) {
        HBox topNav = new HBox(20);
        topNav.setPrefHeight(64);
        topNav.setAlignment(Pos.CENTER_LEFT);
        topNav.setPadding(new Insets(0, 32, 0, 32));
        topNav.setStyle("-fx-background-color: rgba(10, 14, 20, 0.8); -fx-border-color: rgba(255, 255, 255, 0.05); -fx-border-width: 0 0 1 0;");

        // Brand
        Label title = new Label("King of Lab");
        title.getStyleClass().addAll("headline", "title-text");
        
        // Nav Links
        HBox links = new HBox(32);
        links.setAlignment(Pos.CENTER);
        links.setPadding(new Insets(0, 0, 0, 40));
        
        Label lStudents = new Label("STUDENTS"); lStudents.getStyleClass().addAll("headline", "nav-link", "active");
        Label lNetwork  = new Label("NETWORK");  lNetwork.getStyleClass().addAll("headline", "nav-link");
        Label lAiStatus = new Label("AI STATUS");lAiStatus.getStyleClass().addAll("headline", "nav-link");
        Label lAnalyze  = new Label("ANALYTICS");lAnalyze.getStyleClass().addAll("headline", "nav-link");
        links.getChildren().addAll(lStudents, lNetwork, lAiStatus, lAnalyze);
        
        Region spacer1 = new Region(); HBox.setHgrow(spacer1, Priority.ALWAYS);

        // Health Indicators Glass Panel
        HBox healthPanel = new HBox(20);
        healthPanel.setAlignment(Pos.CENTER);
        healthPanel.setPadding(new Insets(8, 20, 8, 20));
        healthPanel.getStyleClass().add("glass-panel");

        Circle connDot = new Circle(4, Color.web(SUCCESS_COLOR));
        connectedLabel = new Label("0 CONNECTED");
        connectedLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.8); -fx-font-weight: bold; -fx-font-size: 10px; -fx-letter-spacing: 0.1em;");
        HBox connBox = new HBox(8, connDot, connectedLabel);
        connBox.setAlignment(Pos.CENTER);
        
        healthLabel = new Label("HEALTH: 100%");
        healthLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.8); -fx-font-weight: bold; -fx-font-size: 10px; -fx-letter-spacing: 0.1em;");
        
        aiStatusLabel = new Label("AI: " + (Config.aiEnabled ? "ON" : "OFF"));
        aiStatusLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.8); -fx-font-weight: bold; -fx-font-size: 10px; -fx-letter-spacing: 0.1em;");
        
        healthPanel.getChildren().addAll(connBox, createVertDivider(), healthLabel, createVertDivider(), aiStatusLabel);

        // Admin Profile
        VBox profileInfo = new VBox(2);
        profileInfo.setAlignment(Pos.CENTER_RIGHT);
        Label pName = new Label("Admin Panel");
        pName.setStyle("-fx-text-fill: #DFE2EB; -fx-font-weight: bold; -fx-font-size: 12px;");
        Label pRole = new Label(user.getUsername().toUpperCase());
        pRole.setStyle("-fx-text-fill: rgba(223,226,235,0.6); -fx-font-weight: bold; -fx-font-size: 9px; -fx-letter-spacing: 0.1em;");
        profileInfo.getChildren().addAll(pName, pRole);
        
        Button logoutBtn = new Button("LOGOUT");
        logoutBtn.getStyleClass().add("btn-glass");
        logoutBtn.setOnAction(e -> { SystemTrayManager.hideWindow(); System.exit(0); });

        topNav.getChildren().addAll(title, links, spacer1, healthPanel, profileInfo, logoutBtn);
        return topNav;
    }

    private static javafx.scene.Node createVertDivider() {
        Region r = new Region();
        r.setPrefWidth(1); r.setPrefHeight(12);
        r.setStyle("-fx-background-color: rgba(255,255,255,0.1);");
        return r;
    }

    // -----------------------------------------------------------------------
    // SIDE NAVIGATION BAR
    // -----------------------------------------------------------------------
    private static VBox buildSideNavBar(User user) {
        VBox side = new VBox(0);
        side.setPrefWidth(256);
        side.setStyle("-fx-background-color: #0D1117; -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 1 0 0;");
        
        // Sentinel badge
        HBox badge = new HBox(12);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(16));
        badge.getStyleClass().add("glass-panel");
        VBox.setMargin(badge, new Insets(24, 24, 40, 24));
        
        Label sIcon = new Label("🛡"); sIcon.setStyle("-fx-text-fill: " + PRIMARY_CONTAINER + "; -fx-font-size: 24px;");
        VBox sInfo = new VBox(2);
        Label sTitle = new Label("COMMAND SENTINEL");
        sTitle.getStyleClass().add("headline");
        sTitle.setStyle("-fx-text-fill: " + PRIMARY_CONTAINER + "; -fx-font-size: 10px; -fx-font-weight: bold; -fx-letter-spacing: 0.15em;");
        sessionTimerLabel = new Label("Session: 00h 00m");
        sessionTimerLabel.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 10px;");
        sInfo.getChildren().addAll(sTitle, sessionTimerLabel);
        badge.getChildren().addAll(sIcon, sInfo);

        // Core Nav Actions
        VBox navMenu = new VBox(8);
        navMenu.setPadding(new Insets(0, 16, 0, 16));
        
        Button navClass = new Button("🔒 CLASS CONTROL");
        navClass.getStyleClass().addAll("headline", "sidenav-btn", "active");
        navClass.setMaxWidth(Double.MAX_VALUE);

        Button navNet = new Button("🌐 NETWORK GUARD");
        navNet.getStyleClass().addAll("headline", "sidenav-btn");
        navNet.setMaxWidth(Double.MAX_VALUE);

        Button navPower = new Button("⏻ POWER CENTER");
        navPower.getStyleClass().addAll("headline", "sidenav-btn");
        navPower.setMaxWidth(Double.MAX_VALUE);

        Button navAi = new Button("🤖 AI COMMAND");
        navAi.getStyleClass().addAll("headline", "sidenav-btn");
        navAi.setMaxWidth(Double.MAX_VALUE);

        navMenu.getChildren().addAll(navClass, navNet, navPower, navAi);
        
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);

        // Bottom Actions
        VBox bottomActions = new VBox(20);
        bottomActions.setPadding(new Insets(32, 24, 32, 24));
        bottomActions.setStyle("-fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 1 0 0 0;");
        
        Button broadcastBtn = new Button("BROADCAST MESSAGE");
        broadcastBtn.getStyleClass().addAll("btn-warm-cyan");
        broadcastBtn.setMaxWidth(Double.MAX_VALUE);
        broadcastBtn.setPadding(new Insets(16, 0, 16, 0));
        broadcastBtn.setOnAction(e -> promptGlobalMessage());

        HBox terminalLogLinks = new HBox(10);
        terminalLogLinks.setAlignment(Pos.CENTER_BETWEEN);
        Label tLink = new Label("\uD83D\uDDBC Screenshots");
        tLink.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 10px; -fx-font-weight: bold; -fx-text-transform: uppercase; -fx-cursor: hand;");
        tLink.setOnMouseClicked(e -> captureAllScreenshots(null));
        Label lLink = new Label("📁 Send File");
        lLink.setStyle("-fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 10px; -fx-font-weight: bold; -fx-text-transform: uppercase; -fx-cursor: hand;");
        lLink.setOnMouseClicked(e -> sendFilesToStudents(null));
        terminalLogLinks.getChildren().addAll(tLink, lLink);
        
        bottomActions.getChildren().addAll(broadcastBtn, terminalLogLinks);

        side.getChildren().addAll(badge, navMenu, spacer, bottomActions);
        return side;
    }

    // -----------------------------------------------------------------------
    // MAIN CANVAS (Ribbon + Grid + Bottom Panel)
    // -----------------------------------------------------------------------
    private static VBox buildMainCanvas(Stage stage, User admin) {
        VBox mainCanvas = new VBox(0);
        mainCanvas.setStyle("-fx-background-color: " + SURFACE_LOWEST + ";");

        // Command Ribbon
        HBox commandRibbon = new HBox(40);
        commandRibbon.setAlignment(Pos.CENTER_LEFT);
        commandRibbon.setPadding(new Insets(24, 40, 24, 40));
        commandRibbon.setStyle("-fx-background-color: rgba(16, 20, 26, 0.3); -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 0 1 0;");

        // Box: Class Access
        VBox bClass = new VBox(12);
        Label lClass = new Label("CLASS ACCESS"); lClass.getStyleClass().add("section-label");
        HBox hcClass = new HBox(0); hcClass.getStyleClass().add("glass-panel"); hcClass.setPadding(new Insets(6));
        Button btnLock = ribbonToggle("🔒 Lock", DANGER_COLOR); btnLock.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.LOCK, "{}")));
        Button btnFree = ribbonToggle("🔓 Free", SUCCESS_COLOR); btnFree.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.UNLOCK, "{}")));
        hcClass.getChildren().addAll(btnLock, btnFree);
        bClass.getChildren().addAll(lClass, hcClass);

        // Box: Connectivity
        VBox bConn = new VBox(12);
        Label lConn = new Label("CONNECTIVITY"); lConn.getStyleClass().add("section-label");
        HBox hcConn = new HBox(0); hcConn.getStyleClass().add("glass-panel"); hcConn.setPadding(new Insets(6));
        Button btnBlk = ribbonToggle("🚫 Block", DANGER_COLOR); btnBlk.setOnAction(e -> { HostsFileManager.blockSites(); appendActivity("NET", "Hosts file blocked", WARNING_COLOR); });
        Button btnAlw = ribbonToggle("✅ Allow", SUCCESS_COLOR); btnAlw.setOnAction(e -> { HostsFileManager.restoreHostsFile(); appendActivity("NET", "Hosts file restored", SUCCESS_COLOR); });
        hcConn.getChildren().addAll(btnBlk, btnAlw);
        bConn.getChildren().addAll(lConn, hcConn);

        // Box: Energy
        VBox bEgy = new VBox(12);
        Label lEgy = new Label("ENERGY"); lEgy.getStyleClass().add("section-label");
        HBox hcEgy = new HBox(12);
        Button btnRest = ribbonIcon("🔄", WARNING_COLOR); btnRest.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.RESTART, "{}")));
        Button btnShut = ribbonIcon("⏻", DANGER_COLOR); btnShut.setOnAction(e -> server.broadcast(pkt(CommandPacket.Type.SHUTDOWN, "{}")));
        hcEgy.getChildren().addAll(btnRest, btnShut);
        bEgy.getChildren().addAll(lEgy, hcEgy);
        
        // Box: Advanced (Mode & Screen Share)
        VBox bAdv = new VBox(12);
        Label lAdv = new Label("PIPELINE"); lAdv.getStyleClass().add("section-label");
        HBox hcAdv = new HBox(12);
        standardModeBtn = ribbonToggle("AWT UI", SUCCESS_COLOR); standardModeBtn.setOnAction(e -> activateStandardMode());
        ultraWebRtcBtn  = ribbonToggle("DXGI H.264", PRIMARY_CONTAINER); ultraWebRtcBtn.setOnAction(e -> activateUltraMode());
        shareScreenBtn  = ribbonToggle("📺 Share Screen", WARNING_COLOR); shareScreenBtn.setOnAction(e -> toggleScreenShare());
        hcAdv.getChildren().addAll(standardModeBtn, ultraWebRtcBtn, shareScreenBtn);
        bAdv.getChildren().addAll(lAdv, hcAdv);
        
        Region sp2 = new Region(); HBox.setHgrow(sp2, Priority.ALWAYS);

        commandRibbon.getChildren().addAll(bClass, createVertDividerLarge(), bConn, createVertDividerLarge(), bEgy, createVertDividerLarge(), bAdv, sp2);

        // Student Grid 
        thumbnailGrid = new FlowPane(32, 32);
        thumbnailGrid.setPadding(new Insets(40));
        thumbnailGrid.setStyle("-fx-background-color: transparent;");

        ScrollPane scrollGrid = new ScrollPane(thumbnailGrid);
        scrollGrid.getStyleClass().add("scroll-pane");
        scrollGrid.setFitToWidth(true);
        VBox.setVgrow(scrollGrid, Priority.ALWAYS);
        
        // Bottom Feed SplitPane
        SplitPane bottomSplit = buildBottomPanel();
        
        mainCanvas.getChildren().addAll(commandRibbon, scrollGrid, bottomSplit);
        return mainCanvas;
    }

    private static javafx.scene.Node createVertDividerLarge() {
        Region r = new Region();
        r.setPrefWidth(1); r.setPrefHeight(40);
        r.setStyle("-fx-background-color: rgba(255,255,255,0.05);");
        return r;
    }

    private static Button ribbonToggle(String text, String activeColor) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(223,226,235,0.7); -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 10 24; -fx-background-radius: 12px;");
        btn.setCursor(javafx.scene.Cursor.HAND);
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-text-fill: #DFE2EB; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 10 24; -fx-background-radius: 12px;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(223,226,235,0.7); -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 10 24; -fx-background-radius: 12px;"));
        return btn;
    }

    private static Button ribbonIcon(String icon, String hoverColor) {
        Button btn = new Button(icon);
        btn.getStyleClass().add("glass-panel");
        btn.setStyle("-fx-text-fill: rgba(223,226,235,0.6); -fx-font-size: 16px; -fx-min-width: 44px; -fx-min-height: 44px; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-text-fill: " + hoverColor + "; -fx-font-size: 16px; -fx-min-width: 44px; -fx-min-height: 44px; -fx-cursor: hand; -fx-border-color: " + hoverColor + "40; -fx-border-radius: 12; -fx-background-radius: 12;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-text-fill: rgba(223,226,235,0.6); -fx-font-size: 16px; -fx-min-width: 44px; -fx-min-height: 44px; -fx-cursor: hand;"));
        return btn;
    }

    // -----------------------------------------------------------------------
    // BOTTOM PANEL (Activity Feed + Terminal)
    // -----------------------------------------------------------------------
    private static SplitPane buildBottomPanel() {
        SplitPane split = new SplitPane();
        split.setPrefHeight(280);
        split.setStyle("-fx-background-color: rgba(13, 17, 23, 0.5); -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 1 0 0 0;");

        // Left Activity Feed
        VBox ledger = new VBox(0);
        ledger.setStyle("-fx-background-color: transparent;");
        
        HBox ledgHeader = new HBox(12);
        ledgHeader.setPadding(new Insets(16, 32, 16, 32));
        ledgHeader.setAlignment(Pos.CENTER_LEFT);
        ledgHeader.setStyle("-fx-background-color: rgba(16, 20, 26, 0.3); -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 0 1 0;");
        Label lIcon = new Label("⚡"); lIcon.setStyle("-fx-text-fill: " + PRIMARY_CONTAINER + ";");
        Label lText = new Label("SECURITY LEDGER"); lText.getStyleClass().add("section-label");
        
        HBox liveBadge = new HBox(6);
        liveBadge.setAlignment(Pos.CENTER);
        liveBadge.setPadding(new Insets(4, 10, 4, 10));
        liveBadge.setStyle("-fx-background-color: rgba(0,240,255,0.1); -fx-background-radius: 8px;");
        Circle lDot = new Circle(3, Color.web(PRIMARY_CONTAINER));
        Label lLbl = new Label("LIVE"); lLbl.setStyle("-fx-text-fill: " + PRIMARY_CONTAINER + "; -fx-font-weight: bold; -fx-font-size: 9px; -fx-letter-spacing: 0.1em;");
        liveBadge.getChildren().addAll(lDot, lLbl);

        Region lsrg = new Region(); HBox.setHgrow(lsrg, Priority.ALWAYS);
        ledgHeader.getChildren().addAll(lIcon, lText, lsrg, liveBadge);

        activityFeed = new ListView<>();
        activityFeed.getStyleClass().add("list-view");
        activityFeed.setStyle("-fx-font-family: 'Inter', monospace; -fx-font-size: 12px; -fx-padding: 16;");
        VBox.setVgrow(activityFeed, Priority.ALWAYS);
        
        ledger.getChildren().addAll(ledgHeader, activityFeed);

        // Right Terminal
        VBox terminal = new VBox(0);
        terminal.setStyle("-fx-background-color: rgba(0, 0, 0, 0.2);");

        HBox termTabs = new HBox(0);
        termTabs.setStyle("-fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 0 1 0;");
        Label tabTerm = new Label("TERMINAL"); tabTerm.getStyleClass().addAll("section-label", "terminal-tab-btn", "active");
        termTabs.getChildren().add(tabTerm);

        termOutput = new TextArea();
        termOutput.setEditable(false);
        termOutput.getStyleClass().add("text-area");
        termOutput.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-text-fill: rgba(0, 240, 255, 0.6); -fx-padding: 16;");
        VBox.setVgrow(termOutput, Priority.ALWAYS);

        HBox inputRow = new HBox(12);
        inputRow.setPadding(new Insets(16));
        inputRow.setAlignment(Pos.CENTER_LEFT);
        Label prmt = new Label("root@king_of_lab:~$"); prmt.setStyle("-fx-text-fill: " + SUCCESS_COLOR + "; -fx-font-family: monospace; -fx-font-size: 12px;");
        TextField cmdField = new TextField();
        cmdField.getStyleClass().add("txt-input");
        cmdField.setPromptText("broadcast --priority high ...");
        HBox.setHgrow(cmdField, Priority.ALWAYS);
        cmdField.setOnAction(e -> {
            String c = cmdField.getText().trim();
            if(!c.isEmpty()) {
                server.broadcast(pkt(CommandPacket.Type.SHELL, c));
                appendTerminal("[root@king~]$ " + c);
                cmdField.clear();
            }
        });
        inputRow.getChildren().addAll(prmt, cmdField);

        terminal.getChildren().addAll(termTabs, termOutput, inputRow);

        split.getItems().addAll(ledger, terminal);
        split.setDividerPositions(0.5);
        return split;
    }


    // -----------------------------------------------------------------------
    // STUDENT CARDS (GLASSMORPHISM)
    // -----------------------------------------------------------------------
    private static void addStudentCard(String name, Image screenshot) {
        if (studentCards.containsKey(name)) return;

        StackPane card = new StackPane();
        card.setPrefSize(300, 250);
        card.getStyleClass().add("glass-panel-high");
        card.setStyle("-fx-background-color: rgba(28, 32, 38, 0.4);"); // Ensure opacity
        
        // Hover glow effect
        DropShadow glow = new DropShadow(BlurType.GAUSSIAN, Color.web(PRIMARY_CONTAINER, 0.0), 20, 0, 0, 10);
        card.setEffect(glow);

        // Thumbnail Map
        ImageView imgView = new ImageView();
        imgView.setFitWidth(300); imgView.setFitHeight(180);
        imgView.setPreserveRatio(false);
        if (screenshot != null) imgView.setImage(screenshot);
        
        Rectangle clip = new Rectangle(300, 180);
        clip.setArcWidth(24); clip.setArcHeight(24);
        imgView.setClip(clip);
        
        // Filter out bright colors to match "cyber" aesthetic
        ColorAdjust vivid = new ColorAdjust();
        vivid.setBrightness(-0.2); vivid.setContrast(0.1);
        imgView.setEffect(vivid);

        StackPane imgContainer = new StackPane(imgView);
        StackPane.setAlignment(imgContainer, Pos.TOP_CENTER);
        
        // Status overlays on image
        HBox statusBadge = new HBox(6);
        statusBadge.setAlignment(Pos.CENTER);
        statusBadge.setPadding(new Insets(6, 12, 6, 12));
        statusBadge.getStyleClass().add("glass-panel");
        statusBadge.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 8px; -fx-border-radius: 8px;");
        Circle dot = new Circle(4, Color.web(SUCCESS_COLOR));
        dot.setEffect(new DropShadow(8, Color.web(SUCCESS_COLOR)));
        statusDots.put(name, dot);
        Label statL = new Label("ACTIVE");
        statL.setStyle("-fx-text-fill: rgba(255,255,255,0.9); -fx-font-weight: 900; -fx-font-size: 9px; -fx-letter-spacing: 0.1em;");
        statusBadge.getChildren().addAll(dot, statL);
        StackPane.setAlignment(statusBadge, Pos.BOTTOM_LEFT);
        StackPane.setMargin(statusBadge, new Insets(0, 0, 12, 12));

        Label pingBadge = new Label("14MS");
        pingBadge.getStyleClass().add("glass-panel");
        pingBadge.setStyle("-fx-background-color: rgba(0,0,0,0.4); -fx-text-fill: rgba(223, 226, 235, 0.6); -fx-font-weight: bold; -fx-font-size: 9px; -fx-padding: 4 8; -fx-background-radius: 8px; -fx-border-radius: 8px;");
        StackPane.setAlignment(pingBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(pingBadge, new Insets(12, 12, 0, 0));

        // Hover Overlay Actions
        HBox actionOverlay = new HBox(16);
        actionOverlay.setAlignment(Pos.CENTER);
        actionOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 24 24 0 0;");
        actionOverlay.setOpacity(0);
        
        Button btnFocus = cardActionBtn("👁", PRIMARY_CONTAINER, true);
        btnFocus.setOnAction(e -> openFullScreenView(name, imgView.getImage()));
        Button btnChat = cardActionBtn("💬", "white", false);
        btnChat.setOnAction(e -> promptMessage(name));
        Button btnLock = cardActionBtn("🔒", DANGER_COLOR, false);
        btnLock.setOnAction(e -> server.sendToClient(name, pkt(CommandPacket.Type.LOCK, "{}")));
        actionOverlay.getChildren().addAll(btnChat, btnFocus, btnLock);

        imgContainer.getChildren().addAll(actionOverlay, statusBadge, pingBadge);

        // Bottom Info Area
        HBox infoBox = new HBox();
        infoBox.setAlignment(Pos.CENTER_LEFT);
        infoBox.setPadding(new Insets(20, 24, 20, 24));
        
        VBox nBox = new VBox(4);
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("headline");
        nameLabel.setStyle("-fx-text-fill: #DFE2EB; -fx-font-weight: bold; -fx-font-size: 15px; -fx-letter-spacing: 0.05em;");
        Label handLbl = new Label("");
        handLbl.setStyle("-fx-text-fill: " + SECONDARY + "; -fx-font-weight: bold; -fx-font-size: 11px;");
        handRaisedLabels.put(name, handLbl);
        nBox.getChildren().addAll(nameLabel, handLbl);

        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        Button moreBtn = new Button("⋮");
        moreBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(223, 226, 235, 0.4); -fx-font-size: 20px; -fx-cursor: hand;");
        
        infoBox.getChildren().addAll(nBox, space, moreBtn);
        StackPane.setAlignment(infoBox, Pos.BOTTOM_CENTER);

        card.getChildren().addAll(imgContainer, infoBox);

        // Hover events
        card.setOnMouseEntered(e -> {
            glow.setColor(Color.web("rgba(0,0,0,0.4)")); // Shadow drop
            card.setStyle("-fx-background-color: rgba(28, 32, 38, 0.6); -fx-translate-y: -5; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 24; -fx-background-radius: 24;");
            vivid.setBrightness(0.0); // Remove darkened filter
            FadeTransition ft = new FadeTransition(Duration.millis(300), actionOverlay);
            ft.setToValue(1.0); ft.play();
        });
        card.setOnMouseExited(e -> {
            glow.setColor(Color.TRANSPARENT);
            card.setStyle("-fx-background-color: rgba(28, 32, 38, 0.4); -fx-translate-y: 0; -fx-border-color: rgba(255,255,255,0.05); -fx-border-radius: 24; -fx-background-radius: 24;");
            vivid.setBrightness(-0.2); // Restore darkened filter
            FadeTransition ft = new FadeTransition(Duration.millis(300), actionOverlay);
            ft.setToValue(0.0); ft.play();
        });

        thumbnailGrid.getChildren().add(card);
        studentCards.put(name, card);
        studentImages.put(name, imgView);
        
        updateCountLabel();
    }

    private static Button cardActionBtn(String icon, String color, boolean isPrimary) {
        Button btn = new Button(icon);
        if (isPrimary) {
            btn.setStyle("-fx-background-color: linear-gradient(to bottom right, #00dbe9 0%, #00f0ff 100%); -fx-text-fill: #002022; -fx-font-size: 24px; -fx-padding: 12; -fx-background-radius: 50em; -fx-cursor: hand;");
            btn.setEffect(new DropShadow(15, Color.web(PRIMARY_CONTAINER, 0.4)));
        } else {
            btn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: " + color + "; -fx-font-size: 20px; -fx-padding: 10; -fx-background-radius: 50em; -fx-cursor: hand;");
        }
        btn.setOnMouseEntered(e -> btn.setScaleX(1.1));
        btn.setOnMouseEntered(e -> btn.setScaleY(1.1));
        btn.setOnMouseExited(e -> { btn.setScaleX(1.0); btn.setScaleY(1.0); });
        return btn;
    }

    // -----------------------------------------------------------------------
    // FULLSCREEN FOCUS
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
        focusOverlay.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) hideFocusView();
        });

        Button closeBtn = new Button("✕ EXIT FOCUS");
        closeBtn.getStyleClass().add("btn-glass");
        closeBtn.setStyle("-fx-background-color: rgba(255,0,0,0.2); -fx-text-fill: " + DANGER_COLOR + "; -fx-padding: 12 32; -fx-font-size: 14px;");
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

    private static void hideFocusView() {
        currentlyFocusedStudent = null;
        focusOverlay.setVisible(false);
    }

    // -----------------------------------------------------------------------
    // CORE LOGIC PASSTHROUGHS
    // -----------------------------------------------------------------------
    private static void updateStudentScreenBinary(String name, Image image) {
        try {
            PerformanceMonitor.recordFrame();
            if (studentImages.containsKey(name)) {
                long now = System.currentTimeMillis();
                long last = lastGridUpdate.getOrDefault(name, 0L);
                if (now - last > 120) { studentImages.get(name).setImage(image); lastGridUpdate.put(name, now); }
            } else {
                addStudentCard(name, image);
                lastGridUpdate.put(name, System.currentTimeMillis());
            }
            if (name.equals(currentlyFocusedStudent) && focusOverlay.isVisible()) focusImgView.setImage(image);
        } catch (Exception e) {}
    }

    private static void updateStudentScreen(String name, String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            Image image  = new Image(new ByteArrayInputStream(bytes));
            updateStudentScreenBinary(name, image);
        } catch (Exception e) {}
    }

    private static void removeStudentCard(String name) {
        StackPane card = studentCards.remove(name);
        if (card != null) {
            FadeTransition ft = new FadeTransition(Duration.millis(280), card);
            ft.setToValue(0);
            ft.setOnFinished(e -> thumbnailGrid.getChildren().remove(card));
            ft.play();
        }
        studentImages.remove(name); handRaisedLabels.remove(name); statusDots.remove(name); lastGridUpdate.remove(name);
    }

    private static void updateCountLabel() {
        int n = studentCards.size();
        if (connectedLabel != null) connectedLabel.setText(n + " CONNECTED");
    }

    private static void updateHandRaised(String student, boolean up) {
        Label lbl = handRaisedLabels.get(student);
        if (lbl != null) lbl.setText(up ? "✋ Support Needed" : "");
        if (up) appendActivity("INCIDENT", "High priority help request from " + student, SECONDARY);
    }

    private static void startSessionTimer() {
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long sec = (System.currentTimeMillis() - startTime) / 1000;
            long m = (sec / 60) % 60;
            long h = (sec / 3600);
            if(sessionTimerLabel != null) sessionTimerLabel.setText(String.format("Session: %02dh %02dm", h, m));
        }));
        t.setCycleCount(Animation.INDEFINITE); t.play();
    }

    private static void appendActivity(String type, String msg, String color) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
        Platform.runLater(() -> {
            activityFeed.getItems().add(time + "   [" + type + "]  " + msg);
            activityFeed.scrollTo(activityFeed.getItems().size() - 1);
        });
    }

    private static void appendTerminal(String msg) {
        if (termOutput != null) { termOutput.appendText(msg + "\n"); }
    }

    private static void activateStandardMode() {
        isUltraActive = false;
        server.broadcast(pkt(CommandPacket.Type.STREAM_MODE, "LEGACY_CPU"));
        appendActivity("SYSTEM", "Mode -> AWT Render Engine", SUCCESS_COLOR);
    }

    private static void activateUltraMode() {
        isUltraActive = true;
        server.broadcast(pkt(CommandPacket.Type.STREAM_MODE, "ULTRA_WEBRTC"));
        appendActivity("SYSTEM", "Mode -> DXGI Hardware Pipeline", PRIMARY_CONTAINER);
    }

    private static void toggleScreenShare() {
        isSharingScreen = !isSharingScreen;
        if (isSharingScreen) {
            ScreenCapture.startAsyncCapture();
            Thread t = new Thread(() -> {
                while (isSharingScreen) {
                    try {
                        String frame = ScreenCapture.getLatestFrame();
                        if (frame != null) server.broadcast(pkt(CommandPacket.Type.ADMIN_SCREEN, frame));
                        Thread.sleep(100);
                    } catch (Exception e) { break; }
                }
            });
            t.setDaemon(true); t.start();
            appendActivity("SYSTEM", "Admin screen share ENABLED", WARNING_COLOR);
        } else {
            ScreenCapture.stopAsyncCapture();
            appendActivity("SYSTEM", "Admin screen share DISABLED", SUCCESS_COLOR);
        }
    }

    private static void promptMessage(String name) {
        TextInputDialog d = new TextInputDialog(); d.setHeaderText("Message to " + name);
        d.showAndWait().ifPresent(msg -> {
            if (!msg.trim().isEmpty()) {
                server.sendToClient(name, pkt(CommandPacket.Type.MSG, msg));
                appendActivity("MSG", "Sent private message to " + name, SUCCESS_COLOR);
            }
        });
    }

    private static void promptGlobalMessage() {
        TextInputDialog d = new TextInputDialog(); d.setHeaderText("Global Broadcast Message");
        d.showAndWait().ifPresent(msg -> {
            if (!msg.trim().isEmpty()) {
                server.broadcast(pkt(CommandPacket.Type.MSG, msg));
                appendActivity("MSG", "Global broadcast sent", PRIMARY_CONTAINER);
            }
        });
    }

    private static void sendFilesToStudents(Stage stage) {
        FileChooser fc = new FileChooser();
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;
        for (File f : files) {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                String b64 = Base64.getEncoder().encodeToString(bytes);
                server.broadcast(pkt(CommandPacket.Type.FILE_DATA, f.getName() + "|" + b64));
                appendActivity("FILE", "Sent " + f.getName() + " to cluster", SUCCESS_COLOR);
            } catch (Exception e) {}
        }
    }

    private static void captureAllScreenshots(Stage stage) {
        File dir = new File(System.getProperty("user.home"), "KingLab Screenshots"); dir.mkdirs();
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        for (Map.Entry<String, ImageView> entry : studentImages.entrySet()) {
            Image img = entry.getValue().getImage();
            if (img != null) {
                try {
                    java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage((int)img.getWidth(), (int)img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    javafx.scene.image.PixelReader pr = img.getPixelReader();
                    for (int y=0; y<(int)img.getHeight(); y++) for (int x=0; x<(int)img.getWidth(); x++) bi.setRGB(x,y,pr.getArgb(x,y));
                    File out = new File(dir, entry.getKey() + "_" + ts + ".png");
                    javax.imageio.ImageIO.write(bi, "png", out);
                } catch (Exception e) {}
            }
        }
        appendActivity("SYSTEM", "Saved cluster snapshots to disk", SUCCESS_COLOR);
    }
    
    private static CommandPacket pkt(CommandPacket.Type type, String payload) {
        return new CommandPacket(type, "ADMIN", payload);
    }
}
