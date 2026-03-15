package com.ghost.ui;

import com.ghost.ai.OllamaService;
import com.ghost.database.User;
import com.ghost.net.CommandPacket;
import com.ghost.net.DiscoveryService;
import com.ghost.net.GhostClient;
import com.ghost.util.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;

/**
 * King of Lab — Student Dashboard.
 *
 * Upgrades:
 *  - 10 student themes
 *  - AI panel calls OllamaService (pure Java, no Python)
 *  - Handles AI_TOGGLE command to disable AI globally
 *  - King of Lab branding
 *  - Adaptive FPS reflected (handled by GhostClient + AdaptiveStreamController)
 */
public class StudentDashboard {

    private static GhostClient      client;
    private static DiscoveryService discoveryService;

    private static StackPane  root;
    private static VBox       lockOverlay;
    private static ImageView  streamView;
    private static TextArea   chatArea;
    private static VBox       chatPanel;
    private static VBox       aiPanel;
    private static TextArea   aiChatArea;
    private static VBox       settingsPanel;

    private static String  currentTheme    = "cyberpunk";
    private static String  downloadFolder  = System.getProperty("user.home") + "/Downloads/KingLab";
    private static Label   notificationLabel;
    private static Circle  statusDot;
    private static Label   statusLabel;
    private static String  currentUsername;

    // 10 themes: key → background style
    private static final String[][] THEMES = {
        {"cyberpunk",  "linear-gradient(to bottom right, #0f0f1f, #1a1a3e)"},
        {"ocean",      "linear-gradient(to bottom right, #0c2461, #1e3799)"},
        {"midnight",   "linear-gradient(to bottom right, #0a0a0a, #1a1a1a)"},
        {"matrix",     "linear-gradient(to bottom right, #001100, #003300)"},
        {"neon",       "linear-gradient(to bottom right, #1a0033, #330066)"},
        {"solarized",  "linear-gradient(to bottom right, #002b36, #073642)"},
        {"dracula",    "linear-gradient(to bottom right, #282a36, #44475a)"},
        {"forest",     "linear-gradient(to bottom right, #0a1a0a, #1a3a1a)"},
        {"lava",       "linear-gradient(to bottom right, #1a0500, #3a0a00)"},
        {"arctic",     "linear-gradient(to bottom right, #0a1a2e, #1a2e4a)"},
    };

    // -----------------------------------------------------------------------
    // Show
    // -----------------------------------------------------------------------

    public static void show(Stage stage, User user) {
        currentUsername = user.getUsername();
        new File(downloadFolder).mkdirs();

        // Auto-discover admin
        discoveryService = new DiscoveryService();
        discoveryService.setListener((serverIp, port) -> {
            if (client == null) {
                client = new GhostClient(serverIp, user);
                client.setListener(packet -> handleCommand(packet));
                client.connect();
                AuditLogger.logSystem("Student " + currentUsername + " discovered admin at " + serverIp);
            } else {
                client.updateAdminIp(serverIp);
            }
        });
        discoveryService.startListening();

        root = new StackPane();
        root.setStyle("-fx-background-color: " + themeGradient(currentTheme) + ";");

        // ===== MAIN CONTENT =====
        BorderPane main = new BorderPane();
        main.setPadding(new Insets(18));

        main.setTop(buildHeader(user));
        main.setCenter(buildStreamArea());
        main.setBottom(buildToolbar());

        root.getChildren().add(main);

        // ===== NOTIFICATION =====
        notificationLabel = new Label();
        notificationLabel.setStyle(
                "-fx-background-color: rgba(0,200,140,0.92); -fx-text-fill: #0a0a1a; " +
                "-fx-padding: 12 22; -fx-background-radius: 10; " +
                "-fx-font-weight: bold; -fx-font-size: 13px;");
        notificationLabel.setVisible(false);
        StackPane.setAlignment(notificationLabel, Pos.TOP_CENTER);
        StackPane.setMargin(notificationLabel, new Insets(20, 0, 0, 0));
        root.getChildren().add(notificationLabel);

        // ===== LOCK OVERLAY =====
        lockOverlay = new VBox(18);
        lockOverlay.setAlignment(Pos.CENTER);
        lockOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.96);");
        lockOverlay.setVisible(false);
        Label lockIcon   = new Label("🔒");  lockIcon.setStyle("-fx-font-size: 70px;");
        Label lockText   = new Label("SYSTEM LOCKED");
        lockText.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 40px; -fx-font-weight: bold;");
        Label lockSub    = new Label("Please give your full attention to the instructor.");
        lockSub.setStyle("-fx-text-fill: #888; -fx-font-size: 16px;");
        lockOverlay.getChildren().addAll(lockIcon, lockText, lockSub);
        root.getChildren().add(lockOverlay);

        // ===== PANELS =====
        chatPanel = buildChatPanel();
        chatPanel.setVisible(false);
        StackPane.setAlignment(chatPanel, Pos.CENTER_RIGHT);
        root.getChildren().add(chatPanel);

        aiPanel = buildAiPanel();
        aiPanel.setVisible(false);
        StackPane.setAlignment(aiPanel, Pos.CENTER_LEFT);
        root.getChildren().add(aiPanel);

        settingsPanel = buildSettingsPanel(stage);
        settingsPanel.setVisible(false);
        StackPane.setAlignment(settingsPanel, Pos.CENTER);
        root.getChildren().add(settingsPanel);

        Scene scene = new Scene(root, 1100, 750);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — " + user.getUsername());
        stage.setMaximized(true);

        SystemTrayManager.init(stage, "STUDENT", user);
        stage.setOnCloseRequest(e -> {
            e.consume();
            SystemTrayManager.hideWindow();
        });
        stage.show();
    }

    // -----------------------------------------------------------------------
    // Header
    // -----------------------------------------------------------------------

    private static HBox buildHeader(User user) {
        HBox header = new HBox(18);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 18, 10, 18));
        header.setStyle("-fx-background-color: rgba(0,0,0,0.32); -fx-background-radius: 14;");

        Label logo = new Label("👑 KING");
        logo.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #c39bd3;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusBox = new HBox(8);
        statusBox.setAlignment(Pos.CENTER);
        statusDot  = new Circle(6, Color.web("#f39c12"));
        statusLabel = new Label("Searching for Admin...");
        statusLabel.setStyle("-fx-text-fill: #888;");
        statusBox.getChildren().addAll(statusDot, statusLabel);

        Label userName = new Label("👤 " + user.getUsername());
        userName.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");

        // Theme selector
        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll(
                "🌃 Cyberpunk","🌊 Ocean","🌙 Midnight","💚 Matrix",
                "🔮 Neon","☀️ Solarized","🧛 Dracula","🌲 Forest","🌋 Lava","❄️ Arctic");
        themeBox.setValue("🌃 Cyberpunk");
        themeBox.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-font-size: 11px;");
        themeBox.setOnAction(e -> {
            String v = themeBox.getValue();
            if      (v.contains("Cyberpunk"))  currentTheme = "cyberpunk";
            else if (v.contains("Ocean"))      currentTheme = "ocean";
            else if (v.contains("Midnight"))   currentTheme = "midnight";
            else if (v.contains("Matrix"))     currentTheme = "matrix";
            else if (v.contains("Neon"))       currentTheme = "neon";
            else if (v.contains("Solarized"))  currentTheme = "solarized";
            else if (v.contains("Dracula"))    currentTheme = "dracula";
            else if (v.contains("Forest"))     currentTheme = "forest";
            else if (v.contains("Lava"))       currentTheme = "lava";
            else if (v.contains("Arctic"))     currentTheme = "arctic";
            applyTheme();
        });

        header.getChildren().addAll(logo, spacer, statusBox, userName, themeBox);
        return header;
    }

    // -----------------------------------------------------------------------
    // Stream area
    // -----------------------------------------------------------------------

    private static VBox buildStreamArea() {
        VBox container = new VBox(12);
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(8));

        Label title = new Label("📺 ADMIN LIVE STREAM  —  Click to fullscreen");
        title.setStyle("-fx-font-size: 14px; -fx-text-fill: #c39bd3; -fx-font-weight: bold;");

        StackPane streamBox = new StackPane();
        streamBox.setStyle("-fx-background-color: rgba(0,0,0,0.5); -fx-background-radius: 14; -fx-cursor: hand;");
        streamBox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        streamBox.setEffect(new DropShadow(18, Color.web("#9b59b6", 0.28)));

        streamView = new ImageView();
        streamView.setPreserveRatio(true);
        streamView.fitWidthProperty().bind(streamBox.widthProperty().subtract(18));
        streamView.fitHeightProperty().bind(streamBox.heightProperty().subtract(18));

        Label waiting = new Label("⏳ Waiting for Admin to start screen share...");
        waiting.setStyle("-fx-text-fill: #555; -fx-font-size: 17px;");
        waiting.setId("waitingLabel");

        streamBox.setOnMouseClicked(e -> openFullScreenStream());
        streamBox.getChildren().addAll(waiting, streamView);

        container.getChildren().addAll(title, streamBox);
        VBox.setVgrow(container, Priority.ALWAYS);
        VBox.setVgrow(streamBox, Priority.ALWAYS);
        return container;
    }

    // -----------------------------------------------------------------------
    // Toolbar
    // -----------------------------------------------------------------------

    private static HBox buildToolbar() {
        HBox toolbar = new HBox(14);
        toolbar.setAlignment(Pos.CENTER);
        toolbar.setPadding(new Insets(14));
        toolbar.setStyle("-fx-background-color: rgba(0,0,0,0.4); -fx-background-radius: 14 14 0 0;");

        toolbar.getChildren().addAll(
                toolBtn("💬 CHAT", "#2980b9", () -> toggle(chatPanel, aiPanel, settingsPanel)),
                toolBtn("🤖 KING AI", "#7d3c98", () -> toggle(aiPanel, chatPanel, settingsPanel)),
                toolBtn("📁 FILES", "#d35400", () -> {
                    try { java.awt.Desktop.getDesktop().open(new File(downloadFolder)); }
                    catch (Exception ex) { showNotification("Cannot open: " + downloadFolder); }
                }),
                toolBtn("⚙️ SETTINGS", "#616a6b", () -> toggle(settingsPanel, chatPanel, aiPanel))
        );
        return toolbar;
    }

    private static Button toolBtn(String text, String color, Runnable action) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; " +
                "-fx-font-weight: bold; -fx-background-radius: 18; -fx-padding: 11 22; -fx-cursor: hand;");
        btn.setOnAction(e -> action.run());
        btn.setOnMouseEntered(e -> btn.setOpacity(0.80));
        btn.setOnMouseExited(e  -> btn.setOpacity(1.0));
        return btn;
    }

    private static void toggle(VBox target, VBox... others) {
        boolean nowVisible = !target.isVisible();
        for (VBox o : others) o.setVisible(false);
        target.setVisible(nowVisible);
    }

    // -----------------------------------------------------------------------
    // Chat panel
    // -----------------------------------------------------------------------

    private static VBox buildChatPanel() {
        VBox p = new VBox(10);
        p.setPrefWidth(300);
        p.setMaxWidth(300);
        p.setPadding(new Insets(18));
        p.setStyle("-fx-background-color: rgba(18,18,38,0.96); -fx-background-radius: 14 0 0 14;");

        Label title = new Label("💬 LAN CHAT");
        title.setStyle("-fx-font-size: 15px; -fx-text-fill: #c39bd3; -fx-font-weight: bold;");
        Button close = closeBtn(p);

        HBox hdr = new HBox();
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        hdr.getChildren().addAll(title, sp, close);

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.setStyle("-fx-control-inner-background: #0d0d1a; -fx-text-fill: #ccc;");
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        HBox row = new HBox(8);
        TextField inp = new TextField();
        inp.setPromptText("Type message...");
        inp.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: white; -fx-prompt-text-fill: #555;");
        HBox.setHgrow(inp, Priority.ALWAYS);
        Button send = new Button("→");
        send.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-weight: bold;");
        send.setOnAction(e -> {
            String msg = inp.getText().trim();
            if (!msg.isEmpty() && client != null) {
                chatArea.appendText("[YOU]: " + msg + "\n");
                client.sendMessage(new CommandPacket(CommandPacket.Type.MSG, currentUsername, msg));
                inp.clear();
            }
        });
        inp.setOnAction(e -> send.fire());
        row.getChildren().addAll(inp, send);

        p.getChildren().addAll(hdr, chatArea, row);
        return p;
    }

    // -----------------------------------------------------------------------
    // AI panel
    // -----------------------------------------------------------------------

    private static VBox buildAiPanel() {
        VBox p = new VBox(10);
        p.setPrefWidth(360);
        p.setMaxWidth(360);
        p.setPadding(new Insets(18));
        p.setStyle("-fx-background-color: rgba(18,18,38,0.96); -fx-background-radius: 0 14 14 0;");

        Label title = new Label("🤖 KING AI ASSISTANT");
        title.setStyle("-fx-font-size: 14px; -fx-text-fill: #9b59b6; -fx-font-weight: bold;");
        Label model = new Label("Model: " + Config.AI_MODEL);
        model.setStyle("-fx-text-fill: #555; -fx-font-size: 9px;");
        Button close = closeBtn(p);

        HBox hdr = new HBox(6);
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        hdr.getChildren().addAll(title, model, sp, close);
        hdr.setAlignment(Pos.CENTER_LEFT);

        aiChatArea = new TextArea();
        aiChatArea.setEditable(false);
        aiChatArea.setWrapText(true);
        aiChatArea.setStyle("-fx-control-inner-background: #0d0d1a; -fx-text-fill: #ccc;");
        aiChatArea.appendText(
                "👑 Welcome to KING AI!\n\n" +
                "I guide you with hints and explanations — not full solutions.\n" +
                "Ask me anything about your programming assignment!\n\n");
        VBox.setVgrow(aiChatArea, Priority.ALWAYS);

        HBox row = new HBox(8);
        TextField inp = new TextField();
        inp.setPromptText("Ask KING AI...");
        inp.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: white; -fx-prompt-text-fill: #555;");
        HBox.setHgrow(inp, Priority.ALWAYS);

        Button askBtn = new Button("ASK");
        askBtn.setStyle("-fx-background-color: #7d3c98; -fx-text-fill: white; -fx-font-weight: bold;");
        askBtn.setOnAction(e -> {
            String q = inp.getText().trim();
            if (q.isEmpty()) return;

            // If AI disabled by admin
            if (!Config.aiEnabled) {
                aiChatArea.appendText("[KING AI]: ⚠️ AI assistance is currently disabled by the administrator.\n\n");
                inp.clear();
                return;
            }

            aiChatArea.appendText("[YOU]: " + q + "\n");
            aiChatArea.appendText("[KING AI]: ⏳ Thinking...\n");
            aiChatArea.setScrollTop(Double.MAX_VALUE);
            inp.clear();
            askBtn.setDisable(true);

            OllamaService.askAsync(currentUsername, q, response -> {
                Platform.runLater(() -> {
                    String current = aiChatArea.getText();
                    aiChatArea.setText(current.replace("[KING AI]: ⏳ Thinking...\n",
                            "[KING AI]: " + response + "\n\n"));
                    aiChatArea.setScrollTop(Double.MAX_VALUE);
                    askBtn.setDisable(false);
                });
            });
        });
        inp.setOnAction(e -> askBtn.fire());
        row.getChildren().addAll(inp, askBtn);

        p.getChildren().addAll(hdr, aiChatArea, row);
        return p;
    }

    // -----------------------------------------------------------------------
    // Settings panel
    // -----------------------------------------------------------------------

    private static VBox buildSettingsPanel(Stage stage) {
        VBox p = new VBox(18);
        p.setPrefWidth(420);
        p.setMaxWidth(420);
        p.setPadding(new Insets(28));
        p.setStyle("-fx-background-color: rgba(18,18,38,0.98); -fx-background-radius: 18;");
        p.setEffect(new DropShadow(28, Color.web("#000", 0.6)));
        p.setAlignment(Pos.TOP_CENTER);

        Label title = new Label("⚙️ SETTINGS");
        title.setStyle("-fx-font-size: 20px; -fx-text-fill: #c39bd3; -fx-font-weight: bold;");
        Button close = closeBtn(p);
        HBox hdr = new HBox();
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        hdr.getChildren().addAll(title, sp, close);

        // Download folder
        VBox dlSection = new VBox(8);
        Label dlLabel = new Label("📁 Download Folder");
        dlLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        Label curFolder = new Label(downloadFolder);
        curFolder.setStyle("-fx-text-fill: #888; -fx-font-size: 10px;");
        curFolder.setWrapText(true);
        Button browse = new Button("Change Folder");
        browse.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white;");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select Download Folder");
            File dir = dc.showDialog(stage);
            if (dir != null) { downloadFolder = dir.getAbsolutePath(); curFolder.setText(downloadFolder); }
        });
        dlSection.getChildren().addAll(dlLabel, curFolder, browse);

        // Screen capture toggle
        VBox scSection = new VBox(8);
        Label scLabel = new Label("📺 Screen Capture");
        scLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        CheckBox screenToggle = new CheckBox("Send my screen to Admin");
        screenToggle.setSelected(true);
        screenToggle.setStyle("-fx-text-fill: #888;");
        screenToggle.setOnAction(e -> { if (client != null) client.setScreenSending(screenToggle.isSelected()); });
        scSection.getChildren().addAll(scLabel, screenToggle);

        // About
        Label about = new Label("King of Lab v" + Config.APP_VER + " — Educational Lab Management System");
        about.setStyle("-fx-text-fill: #444; -fx-font-size: 10px;");

        p.getChildren().addAll(hdr, new Separator(), dlSection, new Separator(), scSection,
                new Region(), about);
        VBox.setVgrow(p.getChildren().get(p.getChildren().size() - 2), Priority.ALWAYS);
        return p;
    }

    // -----------------------------------------------------------------------
    // Command handler
    // -----------------------------------------------------------------------

    private static void handleCommand(CommandPacket packet) {
        Platform.runLater(() -> {
            switch (packet.getType()) {
                case LOCK:
                    lockOverlay.setVisible(true);
                    lockOverlay.toFront();
                    showNotification("🔒 Screen locked by instructor");
                    break;
                case UNLOCK:
                    lockOverlay.setVisible(false);
                    showNotification("🔓 Screen unlocked");
                    break;
                case MSG:
                    if (chatArea != null) {
                        String sender = packet.getSender();
                        String prefix = "ADMIN".equalsIgnoreCase(sender) ? "[ADMIN]" : "[" + sender + "]";
                        chatArea.appendText(prefix + ": " + packet.getPayload() + "\n");
                    }
                    if (!chatPanel.isVisible()) showNotification("💬 Message from " + packet.getSender());
                    break;
                case ADMIN_SCREEN:
                    try {
                        byte[] bytes = Base64.getDecoder().decode(packet.getPayload());
                        Image img = new Image(new ByteArrayInputStream(bytes));
                        if (streamView != null) {
                            streamView.setImage(img);
                            javafx.scene.Node wl = streamView.getParent().lookup("#waitingLabel");
                            if (wl != null) wl.setVisible(false);
                        }
                    } catch (Exception ex) {
                        AuditLogger.logError("StudentDashboard.ADMIN_SCREEN", ex.getMessage());
                    }
                    break;
                case FILE_DATA:
                    try {
                        String payload = packet.getPayload();
                        int sep      = payload.indexOf('|');
                        String fname = payload.substring(0, sep);
                        byte[] data  = Base64.getDecoder().decode(payload.substring(sep + 1));
                        File out     = new File(downloadFolder, fname);
                        try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(data); }
                        showNotification("📁 File received: " + fname);
                    } catch (Exception ex) {
                        AuditLogger.logError("StudentDashboard.FILE_DATA", ex.getMessage());
                    }
                    break;
                case INTERNET:
                    if ("DISABLE".equals(packet.getPayload())) {
                        HostsFileManager.blockSites();
                        showNotification("🌐 Distracting sites blocked by Admin");
                    } else {
                        HostsFileManager.restoreHostsFile();
                        showNotification("🌐 Internet restored");
                    }
                    break;
                case NOTIFICATION:
                    String np = packet.getPayload();
                    if ("CONNECTED".equals(np)) {
                        if (statusDot   != null) statusDot.setFill(Color.web("#00ff7f"));
                        if (statusLabel != null) statusLabel.setText("● Connected to Admin");
                        showNotification("✅ Connected to Admin!");
                    } else if (np != null && (np.contains("disconnected") || np.contains("lost"))) {
                        if (statusDot   != null) statusDot.setFill(Color.web("#e74c3c"));
                        if (statusLabel != null) statusLabel.setText("● Disconnected");
                        showNotification(np);
                        if (streamView != null) streamView.setImage(null);
                    } else {
                        showNotification(np);
                    }
                    break;
                case OPEN_URL:
                    try {
                        Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", packet.getPayload()});
                        showNotification("🔗 Opening: " + packet.getPayload());
                    } catch (Exception ex) {
                        AuditLogger.logError("OPEN_URL", ex.getMessage());
                    }
                    break;
                case AI_TOGGLE:
                    Config.aiEnabled = "ENABLE".equals(packet.getPayload());
                    String msg = Config.aiEnabled
                            ? "🤖 AI assistance is now ENABLED"
                            : "🚫 AI assistance has been DISABLED by administrator";
                    showNotification(msg);
                    if (aiChatArea != null) aiChatArea.appendText("[SYSTEM]: " + msg + "\n\n");
                    break;
                case HEARTBEAT_ACK:
                    // Server acknowledged our heartbeat — connection healthy
                    break;
                default:
                    break;
            }
        });
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static void showNotification(String message) {
        if (notificationLabel == null) return;
        notificationLabel.setText(message);
        notificationLabel.setVisible(true);
        new Thread(() -> {
            try {
                Thread.sleep(3200);
                Platform.runLater(() -> notificationLabel.setVisible(false));
            } catch (InterruptedException ignored) {}
        }).start();
    }

    private static void applyTheme() {
        String grad = themeGradient(currentTheme);
        if (root != null) root.setStyle("-fx-background-color: " + grad + ";");
    }

    private static String themeGradient(String theme) {
        for (String[] t : THEMES) {
            if (t[0].equals(theme)) return t[1];
        }
        return THEMES[0][1];
    }

    private static Button closeBtn(VBox panel) {
        Button b = new Button("✕");
        b.setStyle("-fx-background-color: transparent; -fx-text-fill: #777; -fx-font-size: 15px; -fx-cursor: hand;");
        b.setOnAction(e -> panel.setVisible(false));
        return b;
    }

    // -----------------------------------------------------------------------
    // Fullscreen stream viewer
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
        fv.fitHeightProperty().bind(fs.heightProperty().subtract(38));

        Label info = new Label("🔴 LIVE — Press ESC or click to close");
        info.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 8;");

        VBox fsRoot = new VBox(info, fv);
        fsRoot.setStyle("-fx-background-color: #0a0a1a;");
        fsRoot.setAlignment(Pos.CENTER);

        Scene scene = new Scene(fsRoot, 1280, 720);
        scene.setOnMouseClicked(e -> fs.close());
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) fs.close(); });
        fs.setScene(scene);
        fs.setMaximized(true);
        fs.show();

        Thread upd = new Thread(() -> {
            while (fs.isShowing()) {
                try {
                    Thread.sleep(50);
                    if (streamView != null && streamView.getImage() != null) {
                        Image latest = streamView.getImage();
                        Platform.runLater(() -> fv.setImage(latest));
                    }
                } catch (InterruptedException ignored) { break; }
            }
        });
        upd.setDaemon(true);
        upd.start();
    }
}
