package com.king.ui;

import com.king.ai.OllamaService;
import com.king.database.User;
import com.king.net.CommandPacket;
import com.king.net.DiscoveryService;
import com.king.net.KingClient;
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
import javafx.scene.shape.*;
import javafx.stage.*;
import javafx.util.Duration;
import java.io.*;
import java.util.Base64;

/**
 * King of Lab — Student Dashboard v2.
 *
 * Phase 2 features:
 *  - Picture-based themes (BMW/cars, pink/floral, galaxy, + gradient themes)
 *  - ✋ Raise Hand button (visible to admin on their dashboard)
 *  - ⏱ Exam Timer countdown display
 *  - 📊 Poll response panel
 *  - 🎯 Focus Mode overlay
 *  - 🏫 Session status banner
 *  - AI with conversation context
 *  - JavaFX animations (card pulses, status glow, notification slide-in)
 *  - King of Lab premium UI feel
 */
public class StudentDashboard {

    private static KingClient      client;
    private static DiscoveryService discoveryService;

    private static StackPane  root;
    private static VBox       focusOverlay;
    private static VBox       pollOverlay;
    private static ImageView  streamView;
    // Background replaced by CSS styling
    private static TextArea   chatArea;
    private static VBox       chatPanel;
    private static VBox       aiPanel;
    private static TextArea   aiChatArea;
    private static VBox       settingsPanel;

    private static Label notificationLabel;
    private static Circle statusDot;
    private static Label  statusLabel;
    private static String currentUsername;

    private static String downloadFolder = System.getProperty("user.home") + "/Downloads/KingLab";
    private static boolean handRaised = false;
    private static Button  raiseHandBtn;
    private static long    lastFrameTime = 0;

    // -----------------------------------------------------------------------
    // SHOW
    // -----------------------------------------------------------------------

    public static void show(Stage stage, User user) {
        currentUsername = user.getUsername();
        new File(downloadFolder).mkdirs();

        // Discover admin
        discoveryService = new DiscoveryService();
        discoveryService.setListener((serverIp, port) -> {
            if (client == null) {
                client = new KingClient(serverIp, user);
                client.setListener(StudentDashboard::handleCommand);
                client.connect();
                AuditLogger.logSystem("Student " + currentUsername + " discovered admin at " + serverIp);
            } else {
                client.updateAdminIp(serverIp);
            }
        });
        discoveryService.startListening();

        root = new StackPane();
        root.setStyle(StitchStyles.appRoot());

        // Dark tech grid/radial background
        Region bgLayer = new Region();
        bgLayer.setStyle("-fx-background-color: radial-gradient(radius 120%, " + StitchStyles.rgba(StitchStyles.C_PRIMARY, 0.05) + " 0%, " +
                StitchStyles.C_SURFACE + " 65%, #05070a 100%);");
        bgLayer.prefWidthProperty().bind(root.widthProperty());
        bgLayer.prefHeightProperty().bind(root.heightProperty());

        // ===== MAIN CONTENT =====
        BorderPane main = new BorderPane();
        main.setPadding(new Insets(16));
        main.setStyle("-fx-background-color: transparent;");

        main.setTop(buildHeader(user));
        main.setCenter(buildCenterArea());
        main.setBottom(buildToolbar());

        root.getChildren().addAll(bgLayer, main);

        // ===== NOTIFICATION (slides in from top) =====
        notificationLabel = new Label();
        notificationLabel.setStyle(
                "-fx-background-color: rgba(0,200,140,0.93);" +
                "-fx-text-fill: #050a0f;" +
                "-fx-padding: 12 28;" +
                "-fx-background-radius: 40;" +
                "-fx-font-weight: bold;" +
                "-fx-font-size: 13px;");
        notificationLabel.setVisible(false);
        notificationLabel.setTranslateY(-60);
        StackPane.setAlignment(notificationLabel, Pos.TOP_CENTER);
        StackPane.setMargin(notificationLabel, new Insets(12, 0, 0, 0));
        root.getChildren().add(notificationLabel);

        // lockOverlay removed in favor of native OS lock


        // ===== FOCUS MODE OVERLAY =====
        focusOverlay = buildFocusOverlay();
        root.getChildren().add(focusOverlay);

        // ===== POLL OVERLAY =====
        pollOverlay = buildPollOverlay();
        root.getChildren().add(pollOverlay);

        // ===== SLIDE-IN PANELS =====
        chatPanel = buildChatPanel();
        chatPanel.setVisible(false);
        chatPanel.setTranslateX(320);
        StackPane.setAlignment(chatPanel, Pos.CENTER_RIGHT);
        root.getChildren().add(chatPanel);

        aiPanel = buildAiPanel();
        aiPanel.setVisible(false);
        aiPanel.setTranslateX(-380);
        StackPane.setAlignment(aiPanel, Pos.CENTER_LEFT);
        root.getChildren().add(aiPanel);

        settingsPanel = buildSettingsPanel(stage);
        settingsPanel.setVisible(false);
        settingsPanel.setScaleX(0.85);
        settingsPanel.setScaleY(0.85);
        settingsPanel.setOpacity(0);
        StackPane.setAlignment(settingsPanel, Pos.CENTER);
        root.getChildren().add(settingsPanel);

        Scene scene = new Scene(root, 1100, 760);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — " + user.getUsername());
        stage.setMaximized(true);

        SystemTrayManager.init(stage, "STUDENT", user);
        stage.setOnCloseRequest(e -> { e.consume(); SystemTrayManager.hideWindow(); });
        startStaleStreamMonitor();
        stage.show();
    }

    // -----------------------------------------------------------------------
    // HEADER
    // -----------------------------------------------------------------------

    private static HBox buildHeader(User user) {
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 24, 10, 24));
        header.setStyle(StitchStyles.glassPanel(0.55, 16));

        // Animated logo
        Label logo = new Label("KING OF LAB");
        logo.setStyle("-fx-font-size: 16px; -fx-font-weight: 900; -fx-letter-spacing: 0.15em; -fx-text-fill: " + StitchStyles.C_PRIMARY + ";");
        Glow logoGlow = new Glow(0.6);
        logo.setEffect(logoGlow);
        Timeline logoAnim = new Timeline(
                new KeyFrame(Duration.ZERO,     new KeyValue(logoGlow.levelProperty(), 0.3)),
                new KeyFrame(Duration.seconds(2), new KeyValue(logoGlow.levelProperty(), 0.8)));
        logoAnim.setCycleCount(Animation.INDEFINITE);
        logoAnim.setAutoReverse(true);
        logoAnim.play();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Status indicator
        HBox statusBox = new HBox(8);
        statusBox.setAlignment(Pos.CENTER);
        statusBox.setStyle("-fx-background-color: rgba(0, 240, 255, 0.05); -fx-background-radius: 20; -fx-padding: 6 16; -fx-border-color: rgba(0, 240, 255, 0.15); -fx-border-radius: 20;");
        statusDot = new Circle(4, Color.web("#ffc857")); // Warning
        statusDot.setEffect(new DropShadow(8, Color.web("#ffc857")));
        // Pulsing status dot
        ScaleTransition dotPulse = new ScaleTransition(Duration.seconds(1.2), statusDot);
        dotPulse.setFromX(0.7); dotPulse.setToX(1.3);
        dotPulse.setFromY(0.7); dotPulse.setToY(1.3);
        dotPulse.setCycleCount(Animation.INDEFINITE);
        dotPulse.setAutoReverse(true);
        dotPulse.play();
        statusLabel = new Label("SEARCHING...");
        statusLabel.setStyle("-fx-text-fill: " + StitchStyles.rgba(StitchStyles.C_TEXT_MAIN, 0.6) + "; -fx-font-size: 10px; -fx-font-weight: 900; -fx-letter-spacing: 0.1em;");
        statusBox.getChildren().addAll(statusDot, statusLabel);

        // Raise Hand button (always visible in header)
        raiseHandBtn = new Button("✋ RAISE HAND");
        raiseHandBtn.setStyle(
                "-fx-background-color: rgba(157, 92, 255, 0.1);" +
                "-fx-text-fill: #9D5CFF;" +
                "-fx-font-weight: 900;" +
                "-fx-font-size: 10px;" +
                "-fx-letter-spacing: 0.1em;" +
                "-fx-background-radius: 20;" +
                "-fx-padding: 8 20;" +
                "-fx-cursor: hand;" +
                "-fx-border-color: rgba(157, 92, 255, 0.3);" +
                "-fx-border-radius: 20;");
        raiseHandBtn.setOnAction(e -> toggleRaiseHand());

        Label userLabel = new Label("👤 " + user.getUsername().toUpperCase());
        userLabel.setStyle("-fx-text-fill: " + StitchStyles.C_TEXT_MAIN + "; -fx-font-weight: 900; -fx-font-size: 11px; -fx-letter-spacing: 0.1em;");

        header.getChildren().addAll(logo, spacer, raiseHandBtn, statusBox, userLabel);
        return header;
    }

    // -----------------------------------------------------------------------
    // CENTER AREA (Stream + Stats strip)
    // -----------------------------------------------------------------------

    private static VBox buildCenterArea() {
        VBox container = new VBox(12);
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(16, 0, 16, 0));
        VBox.setVgrow(container, Priority.ALWAYS);

        // Stream title with live indicator
        HBox streamHeader = new HBox(12);
        streamHeader.setAlignment(Pos.CENTER_LEFT);
        Circle liveIndicator = new Circle(4, Color.web(StitchStyles.C_PRIMARY));
        liveIndicator.setEffect(new DropShadow(8, Color.web(StitchStyles.C_PRIMARY)));
        FadeTransition liveFade = new FadeTransition(Duration.seconds(0.9), liveIndicator);
        liveFade.setFromValue(0.2); liveFade.setToValue(1.0);
        liveFade.setCycleCount(Animation.INDEFINITE); liveFade.setAutoReverse(true);
        liveFade.play();
        Label streamTitle = new Label("ACTIVE NODE STREAM   —   CLICK TO FULLSCREEN");
        streamTitle.setStyle("-fx-font-size: 10px; -fx-letter-spacing: 0.15em; -fx-text-fill: " + StitchStyles.rgba(StitchStyles.C_TEXT_MAIN, 0.5) + "; -fx-font-weight: 900;");
        streamHeader.getChildren().addAll(liveIndicator, streamTitle);

        // Stream viewport
        StackPane streamBox = new StackPane();
        streamBox.setStyle(StitchStyles.glassPanel(0.4, 20) + "-fx-cursor: hand;");
        streamBox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(streamBox, Priority.ALWAYS);

        streamView = new ImageView();
        streamView.setPreserveRatio(true);
        streamView.fitWidthProperty().bind(streamBox.widthProperty().subtract(16));
        streamView.fitHeightProperty().bind(streamBox.heightProperty().subtract(16));

        VBox waitBox = new VBox(12);
        waitBox.setAlignment(Pos.CENTER);
        waitBox.setId("waitBox");
        Label waitIcon = new Label("📡");
        waitIcon.setStyle("-fx-font-size: 40px; -fx-text-fill: " + StitchStyles.C_PRIMARY + ";");
        waitIcon.setEffect(new DropShadow(15, Color.web(StitchStyles.C_PRIMARY, 0.4)));
        TranslateTransition waitBob = new TranslateTransition(Duration.seconds(1.8), waitIcon);
        waitBob.setByY(-10); waitBob.setAutoReverse(true); waitBob.setCycleCount(Animation.INDEFINITE);
        waitBob.play();
        Label waitLabel = new Label("WAITING FOR SIGNAL...");
        waitLabel.setStyle("-fx-text-fill: " + StitchStyles.C_TEXT_MAIN + "; -fx-font-size: 16px; -fx-font-weight: 800; -fx-letter-spacing: 0.1em;");
        Label waitSub   = new Label("NODE STANDBY. BACKGROUND TELEMETRY ACTIVE.");
        waitSub.setStyle("-fx-text-fill: " + StitchStyles.rgba(StitchStyles.C_TEXT_MAIN, 0.4) + "; -fx-font-size: 10px; -fx-letter-spacing: 0.15em; -fx-font-weight: 800;");
        waitBox.getChildren().addAll(waitIcon, waitLabel, waitSub);

        streamBox.getChildren().addAll(waitBox, streamView);
        streamBox.setOnMouseClicked(e -> openFullScreenStream());

        container.getChildren().addAll(streamHeader, streamBox);
        return container;
    }

    // -----------------------------------------------------------------------
    // TOOLBAR
    // -----------------------------------------------------------------------

    private static HBox buildToolbar() {
        HBox toolbar = new HBox(16);
        toolbar.setAlignment(Pos.CENTER);
        toolbar.setPadding(new Insets(12));
        toolbar.setStyle(StitchStyles.glassPanel(0.5, 24));

        toolbar.getChildren().addAll(
                toolBtn("💬 LAN CHAT",     StitchStyles.rgba(StitchStyles.C_PRIMARY, 0.1), StitchStyles.C_PRIMARY, () -> slideToggle(chatPanel, true,  aiPanel, settingsPanel)),
                toolBtn("🧠 KING AI",      StitchStyles.rgba("#9D5CFF", 0.1), "#9D5CFF", () -> slideToggle(aiPanel, false, chatPanel, settingsPanel)),
                toolBtn("📂 FILES",        StitchStyles.rgba("#7af19c", 0.1), "#7af19c", () -> openFolder()),
                toolBtn("⚙ SETTINGS",      StitchStyles.rgba(StitchStyles.C_TEXT_MAIN, 0.05), StitchStyles.C_TEXT_MAIN, () -> popToggle(settingsPanel, chatPanel, aiPanel))
        );
        return toolbar;
    }

    private static Button toolBtn(String text, String bgHex, String textHex, Runnable action) {
        Button btn = new Button(text);
        btn.setStyle(
                "-fx-background-color: " + bgHex + ";" +
                "-fx-text-fill: " + textHex + ";" +
                "-fx-font-weight: 900;" +
                "-fx-letter-spacing: 0.1em;" +
                "-fx-font-size: 11px;" +
                "-fx-background-radius: 20;" +
                "-fx-padding: 10 24;" +
                "-fx-border-color: " + StitchStyles.rgba(textHex, 0.2) + ";" +
                "-fx-border-radius: 20;" +
                "-fx-cursor: hand;");
        btn.setOnAction(e -> action.run());
        btn.setOnMouseEntered(e -> {
            ScaleTransition sc = new ScaleTransition(Duration.millis(120), btn);
            sc.setToX(1.05); sc.setToY(1.05); sc.play();
            btn.setStyle(btn.getStyle().replace(bgHex, StitchStyles.rgba(textHex, 0.25)));
        });
        btn.setOnMouseExited(e -> {
            ScaleTransition sc = new ScaleTransition(Duration.millis(120), btn);
            sc.setToX(1.0); sc.setToY(1.0); sc.play();
            btn.setStyle(btn.getStyle().replace(StitchStyles.rgba(textHex, 0.25), bgHex));
        });
        return btn;
    }

    // -----------------------------------------------------------------------
    // OVERLAYS
    // -----------------------------------------------------------------------



    private static VBox buildFocusOverlay() {
        VBox ov = new VBox(16);
        ov.setAlignment(Pos.CENTER);
        ov.setStyle("-fx-background-color: rgba(0,0,0,0.88);");
        ov.setVisible(false);

        Label focusIcon = new Label("🎯");
        focusIcon.setStyle("-fx-font-size: 60px;");
        Label focusText = new Label("FOCUS MODE ACTIVE");
        focusText.setStyle("-fx-text-fill: #f1c40f; -fx-font-size: 36px; -fx-font-weight: bold;");
        Label focusSub  = new Label("Please focus on the admin stream");
        focusSub.setStyle("-fx-text-fill: #888; -fx-font-size: 16px;");

        ov.getChildren().addAll(focusIcon, focusText, focusSub);
        return ov;
    }

    private static Label pollQuestionLabel;

    private static VBox buildPollOverlay() {
        VBox ov = new VBox(18);
        ov.setAlignment(Pos.CENTER);
        ov.setMaxWidth(500);
        ov.setPadding(new Insets(40));
        ov.setStyle(StitchStyles.glassPanel(0.85, 24));
        ov.setEffect(new DropShadow(30, Color.web("#9D5CFF", 0.2)));
        ov.setVisible(false);

        Label pollTitle = new Label("NODE QUERY");
        pollTitle.setStyle("-fx-text-fill: #9D5CFF; -fx-font-size: 14px; -fx-font-weight: 900; -fx-letter-spacing: 0.2em;");
        pollQuestionLabel = new Label("Question here");
        pollQuestionLabel.setStyle("-fx-text-fill: " + StitchStyles.C_TEXT_MAIN + "; -fx-font-size: 18px; -fx-font-weight: bold; -fx-wrap-text: true; -fx-text-alignment: center;");
        pollQuestionLabel.setWrapText(true);
        pollQuestionLabel.setMaxWidth(420);

        ov.getChildren().addAll(pollTitle, pollQuestionLabel);
        StackPane.setAlignment(ov, Pos.CENTER);
        return ov;
    }

    // -----------------------------------------------------------------------
    // CHAT PANEL (slides in from right)
    // -----------------------------------------------------------------------

    private static VBox buildChatPanel() {
        VBox p = new VBox(10);
        p.setPrefWidth(310);
        p.setMaxWidth(310);
        p.setPadding(new Insets(18));
        p.setStyle(StitchStyles.glassPanel(0.9, 16));
        p.setEffect(new DropShadow(20, Color.BLACK));

        Label title = new Label("LAN CHAT");
        title.setStyle("-fx-font-size: 13px; -fx-text-fill: " + StitchStyles.C_PRIMARY + "; -fx-font-weight: 900; -fx-letter-spacing: 0.15em;");
        Button close = smallCloseBtn(() -> slideOut(p, true));
        HBox hdr = headerRow(title, close);

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.setStyle("-fx-control-inner-background: #080814; -fx-text-fill: #ccc; -fx-font-size: 12px;");
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        TextField inp = styledField("Type message...");
        Button send = accentBtn("→", "#3498db");
        send.setOnAction(e -> {
            String msg = inp.getText().trim();
            if (!msg.isEmpty() && client != null) {
                chatArea.appendText("[YOU]: " + msg + "\n");
                client.sendMessage(new CommandPacket(CommandPacket.Type.MSG, currentUsername, msg));
                inp.clear();
            }
        });
        inp.setOnAction(e -> send.fire());

        HBox row = new HBox(8, inp, send);
        HBox.setHgrow(inp, Priority.ALWAYS);

        p.getChildren().addAll(hdr, chatArea, row);
        return p;
    }

    // -----------------------------------------------------------------------
    // AI PANEL (slides in from left)
    // -----------------------------------------------------------------------

    private static VBox buildAiPanel() {
        VBox p = new VBox(10);
        p.setPrefWidth(380);
        p.setMaxWidth(380);
        p.setPadding(new Insets(18));
        p.setStyle(StitchStyles.glassPanel(0.9, 16));
        p.setEffect(new DropShadow(20, Color.BLACK));

        Label title = new Label("KING AI COMMLINK");
        title.setStyle("-fx-font-size: 13px; -fx-text-fill: #9D5CFF; -fx-font-weight: 900; -fx-letter-spacing: 0.15em;");
        title.setEffect(new Glow(0.5));
        Label modelLabel = new Label("MODEL: " + Config.AI_MODEL.toUpperCase() + "  |  CTX: LOCAL");
        modelLabel.setStyle("-fx-text-fill: " + StitchStyles.rgba(StitchStyles.C_TEXT_MAIN, 0.4) + "; -fx-font-size: 9px; -fx-font-weight: bold;");
        Button clearBtn = new Button("🗑 Clear");
        clearBtn.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-text-fill: #888; -fx-font-size: 10px; -fx-cursor: hand; -fx-background-radius: 6;");
        clearBtn.setOnAction(e -> {
            OllamaService.clearHistory(currentUsername);
            aiChatArea.clear();
            aiChatArea.appendText("💬 Chat history cleared. Starting a fresh conversation!\n\n");
        });
        Button close = smallCloseBtn(() -> slideOut(p, false));

        HBox titleRow = new HBox(6, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Region sp2 = new Region(); HBox.setHgrow(sp2, Priority.ALWAYS);
        HBox hdr = new HBox(6, titleRow, sp2, clearBtn, close);
        hdr.setAlignment(Pos.CENTER_LEFT);

        aiChatArea = new TextArea();
        aiChatArea.setEditable(false);
        aiChatArea.setWrapText(true);
        aiChatArea.setStyle(
                "-fx-control-inner-background: #080814;" +
                "-fx-text-fill: #d7bde2;" +
                "-fx-font-size: 12px;");
        aiChatArea.appendText(
                "👑 Welcome to KING AI!\n\n" +
                "I remember our entire conversation — answer my follow-up\n" +
                "questions naturally (e.g., 'yes', 'try again', etc.).\n\n" +
                "I guide with HINTS — never full code. Ask away! 🚀\n\n");
        VBox.setVgrow(aiChatArea, Priority.ALWAYS);

        Label modelNote = new Label(modelLabel.getText());
        modelNote.setStyle("-fx-text-fill: #333; -fx-font-size: 9px;");

        TextField inp = styledField("Ask KING AI anything...");
        Button askBtn = accentBtn("ASK ▶", "#7d3c98");
        askBtn.setOnAction(e -> submitAiQuestion(inp, askBtn));
        inp.setOnAction(e -> askBtn.fire());

        HBox row = new HBox(8, inp, askBtn);
        HBox.setHgrow(inp, Priority.ALWAYS);

        p.getChildren().addAll(hdr, aiChatArea, row, modelNote);
        return p;
    }

    private static void submitAiQuestion(TextField inp, Button askBtn) {
        String q = inp.getText().trim();
        if (q.isEmpty()) return;

        if (!Config.aiEnabled) {
            aiChatArea.appendText("[KING AI]: ⚠️ AI is currently disabled by the administrator.\n\n");
            inp.clear();
            return;
        }

        aiChatArea.appendText("🧑 " + q + "\n");
        aiChatArea.appendText("🤖 ⏳ Checking AI engine...\n");
        aiChatArea.setScrollTop(Double.MAX_VALUE);
        inp.clear();
        askBtn.setDisable(true);

        OllamaService.askAsync(currentUsername, q, response -> Platform.runLater(() -> {
            String current = aiChatArea.getText();
            if (response.startsWith("[SYSTEM]: ")) {
                // Install/start progress: replace the last status line
                String statusMsg = response.substring("[SYSTEM]: ".length());
                // Remove old checking/status line and show the new one
                int lastNewline = current.lastIndexOf('\n', current.length() - 2);
                String withoutLast = lastNewline >= 0 ? current.substring(0, lastNewline + 1) : current;
                aiChatArea.setText(withoutLast + "🤖 ⏳ " + statusMsg + "\n");
            } else {
                // Final answer — replace the last status line with the real response
                aiChatArea.setText(current.replaceAll("🤖 ⏳[^\n]*\n", "") + "🤖 " + response + "\n\n");
            }
            aiChatArea.setScrollTop(Double.MAX_VALUE);
            // Re-enable only when we get the real (non-system) response
            if (!response.startsWith("[SYSTEM]: ")) {
                askBtn.setDisable(false);
            }
        }));
    }


    // -----------------------------------------------------------------------
    // SETTINGS PANEL (pop scale animation)
    // -----------------------------------------------------------------------

    private static VBox buildSettingsPanel(Stage stage) {
        VBox p = new VBox(18);
        p.setPrefWidth(440);
        p.setMaxWidth(440);
        p.setPadding(new Insets(30));
        p.setStyle(StitchStyles.glassPanel(0.9, 20));
        p.setEffect(new DropShadow(36, Color.web("#000", 0.7)));
        p.setAlignment(Pos.TOP_CENTER);

        Label title = new Label("SYSTEM SETTINGS");
        title.setStyle("-fx-font-size: 16px; -fx-text-fill: " + StitchStyles.C_TEXT_MAIN + "; -fx-font-weight: 900; -fx-letter-spacing: 0.15em;");
        Button close = smallCloseBtn(() -> popClose(p));
        HBox hdr = headerRow(title, close);

        // Download folder
        Label folderPath = new Label(downloadFolder);
        folderPath.setStyle("-fx-text-fill: #777; -fx-font-size: 10px;");
        folderPath.setWrapText(true);
        VBox dlSec = new VBox(7, sectionTitle("📁 Download Folder"), folderPath);
        Button browseBtn = accentBtn("Change Folder", "#2980b9");
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            File dir = dc.showDialog(stage);
            if (dir != null) { downloadFolder = dir.getAbsolutePath(); folderPath.setText(downloadFolder); }
        });
        dlSec.getChildren().add(browseBtn);

        // AI history clear
        VBox aiSec = new VBox(7, sectionTitle("🤖 AI Context"));
        Button clearAI = accentBtn("Clear My AI Chat History", "#7d3c98");
        clearAI.setOnAction(e -> {
            OllamaService.clearHistory(currentUsername);
            if (aiChatArea != null) {
                aiChatArea.clear();
                aiChatArea.appendText("AI chat history cleared.\n\n");
            }
            showNotification("🗑 AI history cleared");
        });
        aiSec.getChildren().add(clearAI);

        Label about = new Label("👑 King of Lab v" + Config.APP_VER + " — Educational Lab Management");
        about.setStyle("-fx-text-fill: #333; -fx-font-size: 10px;");

        p.getChildren().addAll(hdr, new Separator(), dlSec, new Separator(), aiSec, new Region(), about);
        VBox.setVgrow(p.getChildren().get(p.getChildren().size() - 2), Priority.ALWAYS);
        return p;
    }

    // -----------------------------------------------------------------------
    // COMMAND HANDLER
    // -----------------------------------------------------------------------

    private static void handleCommand(CommandPacket packet) {
        Platform.runLater(() -> {
            switch (packet.getType()) {
                case LOCK:
                    try {
                        Runtime.getRuntime().exec("rundll32.exe user32.dll,LockWorkStation");
                        showNotification("🔒 System locked by instructor");
                    } catch (Exception ex) {
                        AuditLogger.logError("LOCK_CMD", ex.getMessage());
                    }
                    break;
                case UNLOCK:
                    showNotification("🔓 System release signal received");
                    break;
                case FOCUS_MODE:
                    boolean focusOn = "ON".equals(packet.getPayload());
                    focusOverlay.setVisible(focusOn);
                    if (focusOn) focusOverlay.toFront();
                    showNotification(focusOn ? "🎯 Focus Mode enabled" : "Focus Mode ended");
                    break;
                case SESSION_START:
                    showNotification("🏫 Session: " + packet.getPayload());
                    break;
                case SESSION_END:
                    showNotification("📴 Session ended by instructor");
                    break;
                case TIMER_START:
                    // Timer functionality removed
                    break;
                case TIMER_STOP:
                    // Timer functionality removed
                    break;
                case POLL_QUESTION:
                    showPoll(packet.getPayload());
                    break;
                case ALERT_STUDENT:
                    flashAlert(packet.getPayload());
                    break;
                case MSG:
                    if (chatArea != null) {
                        String s = packet.getSender();
                        chatArea.appendText(("ADMIN".equalsIgnoreCase(s) ? "[ADMIN]" : "[" + s + "]")
                                + ": " + packet.getPayload() + "\n");
                    }
                    if (!chatPanel.isVisible()) showNotification("💬 " + packet.getSender() + ": " + packet.getPayload());
                    break;
                case ADMIN_SCREEN:
                    // Decode off the FX thread so the UI render loop is never blocked.
                    // 1) Grab the payload while still on the FX thread (cheap String copy)
                    final String screenPayload = packet.getPayload();
                    if (screenPayload == null || screenPayload.isEmpty()) break;
                    Thread decodeThread = new Thread(() -> {
                        try {
                            lastFrameTime = System.currentTimeMillis();
                            byte[] b = Base64.getDecoder().decode(screenPayload);
                            // Decode JPEG to BufferedImage on the background thread
                            java.awt.image.BufferedImage bi =
                                    javax.imageio.ImageIO.read(new ByteArrayInputStream(b));
                            if (bi == null) return;
                            int w = bi.getWidth(), h = bi.getHeight();
                            int[] pixels = new int[w * h];
                            bi.getRGB(0, 0, w, h, pixels, 0, w);
                            // Build a WritableImage and swap on the FX thread (paint only, no decode)
                            Platform.runLater(() -> {
                                try {
                                    javafx.scene.image.WritableImage wi =
                                            new javafx.scene.image.WritableImage(w, h);
                                    javafx.scene.image.PixelWriter pw = wi.getPixelWriter();
                                    pw.setPixels(0, 0, w, h,
                                            javafx.scene.image.PixelFormat.getIntArgbInstance(),
                                            pixels, 0, w);
                                    streamView.setImage(wi);
                                    javafx.scene.Node wb = root.lookup("#waitBox");
                                    if (wb != null) wb.setVisible(false);
                                } catch (Exception ex) {
                                    AuditLogger.logError("ADMIN_SCREEN_FX", ex.getMessage());
                                }
                            });
                        } catch (Exception ex) {
                            AuditLogger.logError("ADMIN_SCREEN", ex.getMessage());
                        }
                    }, "ScreenDecoder");
                    decodeThread.setDaemon(true);
                    decodeThread.setPriority(Thread.NORM_PRIORITY - 1);
                    decodeThread.start();
                    break;
                case FILE_DATA:
                    try {
                        String pl  = packet.getPayload();
                        int sep    = pl.indexOf('|');
                        String fn  = pl.substring(0, sep);
                        byte[] dat = Base64.getDecoder().decode(pl.substring(sep + 1));
                        try (FileOutputStream fos = new FileOutputStream(new File(downloadFolder, fn))) { fos.write(dat); }
                        showNotification("📁 File received: " + fn);
                    } catch (Exception ex) { AuditLogger.logError("FILE_DATA", ex.getMessage()); }
                    break;
                case INTERNET:
                    if ("DISABLE".equals(packet.getPayload())) {
                        HostsFileManager.blockSites(); showNotification("🌐 Sites blocked by Admin");
                    } else {
                        HostsFileManager.restoreHostsFile(); showNotification("🌐 Internet restored");
                    }
                    break;
                case NOTIFICATION:
                    String np = packet.getPayload();
                    if ("CONNECTED".equals(np)) {
                        if (statusDot   != null) statusDot.setFill(Color.web("#2ecc71"));
                        if (statusLabel != null) statusLabel.setText("Connected");
                        showNotification("✅ Connected to Admin!");
                    } else if (np != null && (np.contains("disconnected") || np.contains("lost"))) {
                        if (statusDot   != null) statusDot.setFill(Color.web("#e74c3c"));
                        if (statusLabel != null) statusLabel.setText("Disconnected");
                        if (streamView  != null) streamView.setImage(null);
                        showNotification(np);
                    } else {
                        showNotification(np);
                    }
                    break;
                case OPEN_URL:
                    try { Runtime.getRuntime().exec(new String[]{"cmd","/c","start",packet.getPayload()}); } catch (Exception ignored) {}
                    showNotification("🔗 Opening: " + packet.getPayload());
                    break;
                case AI_TOGGLE:
                    Config.aiEnabled = "ENABLE".equals(packet.getPayload());
                    String aiMsg = Config.aiEnabled ? "🤖 AI ENABLED" : "🚫 AI DISABLED by administrator";
                    showNotification(aiMsg);
                    if (aiChatArea != null) aiChatArea.appendText("[SYSTEM]: " + aiMsg + "\n\n");
                    break;
                case AI_CLEAR_HISTORY:
                    String target = packet.getPayload();
                    if ("ALL".equals(target) || currentUsername.equals(target)) {
                        OllamaService.clearHistory(currentUsername);
                        if (aiChatArea != null) aiChatArea.appendText("[SYSTEM]: AI history cleared by admin\n\n");
                    }
                    break;
                case HEARTBEAT_ACK:
                    break;
                default:
                    break;
            }
        });
    }

    // -----------------------------------------------------------------------
    // STALE STREAM MONITOR
    // -----------------------------------------------------------------------

    private static void startStaleStreamMonitor() {
        Timeline monitor = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (lastFrameTime > 0 && (System.currentTimeMillis() - lastFrameTime > 2000)) {
                if (streamView != null && streamView.getImage() != null) {
                    streamView.setImage(null);
                    javafx.scene.Node wb = root.lookup("#waitBox");
                    if (wb != null) wb.setVisible(true);
                    lastFrameTime = 0; // Reset
                }
            }
        }));
        monitor.setCycleCount(Animation.INDEFINITE);
        monitor.play();
    }

    // -----------------------------------------------------------------------
    // RAISE HAND
    // -----------------------------------------------------------------------

    private static void toggleRaiseHand() {
        handRaised = !handRaised;
        String payload = handRaised ? "UP" : "DOWN";
        if (client != null)
            client.sendMessage(new CommandPacket(CommandPacket.Type.RAISE_HAND, currentUsername, payload));

        if (handRaised) {
            raiseHandBtn.setStyle(
                    "-fx-background-color: #f1c40f;" +
                    "-fx-text-fill: #1a1a1a;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 20;" +
                    "-fx-padding: 7 16;" +
                    "-fx-cursor: hand;");
            raiseHandBtn.setText("✋ Hand Raised!");
            // Bounce animation
            ScaleTransition sc = new ScaleTransition(Duration.millis(150), raiseHandBtn);
            sc.setToX(1.15); sc.setToY(1.15); sc.setAutoReverse(true); sc.setCycleCount(2);
            sc.play();
            showNotification("✋ Hand raised — Admin notified!");
        } else {
            raiseHandBtn.setStyle(
                    "-fx-background-color: rgba(241,196,15,0.15);" +
                    "-fx-text-fill: #f1c40f;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 20;" +
                    "-fx-padding: 7 16;" +
                    "-fx-cursor: hand;" +
                    "-fx-border-color: rgba(241,196,15,0.4);" +
                    "-fx-border-radius: 20;");
            raiseHandBtn.setText("✋ Raise Hand");
            showNotification("Hand lowered");
        }
    }

    // -----------------------------------------------------------------------
    // POLL
    // -----------------------------------------------------------------------

    private static void showPoll(String pollPayload) {
        // format: "question|optA|optB|optC"
        String[] parts = pollPayload.split("\\|");
        if (parts.length < 2) return;

        pollQuestionLabel.setText(parts[0]);
        // Remove old option buttons
        pollOverlay.getChildren().removeIf(n -> n instanceof Button || n instanceof HBox && ((HBox)n).getStyleClass().contains("poll-opts"));

        HBox optRow = new HBox(10);
        optRow.setAlignment(Pos.CENTER);
        optRow.getStyleClass().add("poll-opts");
        for (int i = 1; i < parts.length; i++) {
            final int idx = i - 1;
            Button optBtn = new Button(parts[i]);
            optBtn.setStyle("-fx-background-color: #7d3c98; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 10; -fx-padding: 10 20; -fx-cursor: hand;");
            optBtn.setOnAction(e -> {
                if (client != null)
                    client.sendMessage(new CommandPacket(CommandPacket.Type.POLL_ANSWER, currentUsername, String.valueOf(idx)));
                pollOverlay.setVisible(false);
                showNotification("📊 Answer submitted!");
            });
            optRow.getChildren().add(optBtn);
        }

        pollOverlay.getChildren().add(optRow);
        pollOverlay.setVisible(true);
        pollOverlay.toFront();

        // Fade in
        FadeTransition ft = new FadeTransition(Duration.millis(300), pollOverlay);
        ft.setFromValue(0); ft.setToValue(1); ft.play();
    }

    // -----------------------------------------------------------------------
    // FLASH ALERT
    // -----------------------------------------------------------------------

    private static void flashAlert(String message) {
        // Create a temporary full-screen flash
        VBox alertBox = new VBox(10);
        alertBox.setAlignment(Pos.CENTER);
        alertBox.setStyle("-fx-background-color: rgba(231,76,60,0.92); -fx-background-radius: 20; -fx-padding: 30 60;");
        alertBox.setEffect(new DropShadow(30, Color.web("#e74c3c")));
        Label alertLabel = new Label(message);
        alertLabel.setStyle("-fx-text-fill: white; -fx-font-size: 28px; -fx-font-weight: bold;");
        alertBox.getChildren().add(alertLabel);
        StackPane.setAlignment(alertBox, Pos.CENTER);
        root.getChildren().add(alertBox);

        Timeline hide = new Timeline(new KeyFrame(Duration.seconds(3), e -> root.getChildren().remove(alertBox)));
        FadeTransition ft = new FadeTransition(Duration.millis(400), alertBox);
        ft.setFromValue(0); ft.setToValue(1);
        ft.setOnFinished(e -> hide.play());
        ft.play();
    }

    // -----------------------------------------------------------------------
    // THEME APPLICATION - Deprecated with Stitch Hologram Redesign
    // -----------------------------------------------------------------------

    // Legacy theme application logic no longer needed

    // -----------------------------------------------------------------------
    // ANIMATIONS (slide-in/out panels)
    // -----------------------------------------------------------------------

    private static void slideToggle(VBox panel, boolean fromRight, VBox... others) {
        boolean show = !panel.isVisible();
        for (VBox o : others) { if (o.isVisible()) slideOut(o, o == chatPanel); }
        if (show) slideIn(panel, fromRight);
        else      slideOut(panel, fromRight);
    }

    private static void slideIn(VBox panel, boolean fromRight) {
        panel.setVisible(true);
        double startX = fromRight ? 320 : -380;
        panel.setTranslateX(startX);
        TranslateTransition tt = new TranslateTransition(Duration.millis(220), panel);
        tt.setToX(0); tt.play();
    }

    private static void slideOut(VBox panel, boolean toRight) {
        double endX = toRight ? 320 : -380;
        TranslateTransition tt = new TranslateTransition(Duration.millis(180), panel);
        tt.setToX(endX);
        tt.setOnFinished(e -> panel.setVisible(false));
        tt.play();
    }

    private static void popToggle(VBox panel, VBox... others) {
        for (VBox o : others) { if (o.isVisible()) slideOut(o, o == chatPanel); }
        if (!panel.isVisible()) popOpen(panel);
        else popClose(panel);
    }

    private static void popOpen(VBox panel) {
        panel.setVisible(true);
        panel.setOpacity(0);
        panel.setScaleX(0.85); panel.setScaleY(0.85);
        ScaleTransition sc = new ScaleTransition(Duration.millis(220), panel);
        sc.setToX(1.0); sc.setToY(1.0); sc.play();
        FadeTransition ft = new FadeTransition(Duration.millis(220), panel);
        ft.setToValue(1.0); ft.play();
    }

    private static void popClose(VBox panel) {
        ScaleTransition sc = new ScaleTransition(Duration.millis(160), panel);
        sc.setToX(0.85); sc.setToY(0.85);
        FadeTransition ft = new FadeTransition(Duration.millis(160), panel);
        ft.setToValue(0);
        ft.setOnFinished(e -> panel.setVisible(false));
        sc.play(); ft.play();
    }

    // -----------------------------------------------------------------------
    // NOTIFICATIONS
    // -----------------------------------------------------------------------

    private static Timeline notifTimeline;

    private static void showNotification(String message) {
        if (notificationLabel == null) return;
        if (notifTimeline != null) notifTimeline.stop();
        notificationLabel.setText(message);
        notificationLabel.setVisible(true);
        // Slide in from top
        TranslateTransition tt = new TranslateTransition(Duration.millis(300), notificationLabel);
        tt.setFromY(-60); tt.setToY(0); tt.play();

        notifTimeline = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
            TranslateTransition hide = new TranslateTransition(Duration.millis(250), notificationLabel);
            hide.setToY(-60);
            hide.setOnFinished(ev -> notificationLabel.setVisible(false));
            hide.play();
        }));
        notifTimeline.play();
    }

    // -----------------------------------------------------------------------
    // FULLSCREEN STREAM
    // -----------------------------------------------------------------------

    private static void openFullScreenStream() {
        if (streamView == null || streamView.getImage() == null) {
            showNotification("⏳ No stream available yet");
            return;
        }
        Stage fs = new Stage();
        fs.setTitle("📺 Admin Live Stream — ESC to close");
        ImageView fv = new ImageView(streamView.getImage());
        fv.setPreserveRatio(true);
        fv.fitWidthProperty().bind(fs.widthProperty());
        fv.fitHeightProperty().bind(fs.heightProperty().subtract(36));
        VBox fsRoot = new VBox(
                new Label("🔴 LIVE — ESC or click to close") {{
                    setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6;");
                }}, fv);
        fsRoot.setStyle("-fx-background-color: #050508;");
        fsRoot.setAlignment(Pos.CENTER);
        Scene scene = new Scene(fsRoot, 1280, 720);
        scene.setOnMouseClicked(e -> fs.close());
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) fs.close(); });
        fs.setScene(scene); fs.setMaximized(true); fs.show();
        Thread t = new Thread(() -> {
            while (fs.isShowing()) {
                try {
                    Thread.sleep(50);
                    if (streamView.getImage() != null) {
                        Image latest = streamView.getImage();
                        Platform.runLater(() -> fv.setImage(latest));
                    }
                } catch (InterruptedException ignored) { break; }
            }
        });
        t.setDaemon(true); t.start();
    }

    private static void openFolder() {
        try { java.awt.Desktop.getDesktop().open(new File(downloadFolder)); }
        catch (Exception ex) { showNotification("Cannot open: " + downloadFolder); }
    }

    // -----------------------------------------------------------------------
    // UI HELPERS
    // -----------------------------------------------------------------------

    private static HBox headerRow(Label title, Button close) {
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        HBox h = new HBox(title, sp, close);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Button smallCloseBtn(Runnable action) {
        Button b = new Button("✕");
        b.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-text-fill: #888; -fx-font-size: 13px; -fx-cursor: hand; -fx-background-radius: 50;");
        b.setOnAction(e -> action.run());
        b.setOnMouseEntered(e -> b.setStyle(b.getStyle().replace("0.06", "0.14")));
        b.setOnMouseExited(e  -> b.setStyle(b.getStyle().replace("0.14", "0.06")));
        return b;
    }

    private static TextField styledField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-text-fill: white; -fx-prompt-text-fill: #444; -fx-background-radius: 8; -fx-padding: 9;");
        return f;
    }

    private static Button accentBtn(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 9 16;");
        b.setOnMouseEntered(e -> b.setOpacity(0.82));
        b.setOnMouseExited(e  -> b.setOpacity(1.0));
        return b;
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
        return l;
    }
}
