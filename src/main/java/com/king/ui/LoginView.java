package com.king.ui;

import com.king.database.DatabaseManager;
import com.king.database.User;
import com.king.util.AuditLogger;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Glow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * King of Lab — login page.
 * Refined to match the Stitch "Command Sentinel" (Tactical Hologram) design system.
 */
public class LoginView {

    public static void show(Stage stage) {

        // ========== ROOT ==========
        StackPane root = new StackPane();
        root.setStyle(
                StitchStyles.appRoot() +
                "-fx-background-color: radial-gradient(radius 120%, " + StitchStyles.rgba(StitchStyles.C_PRIMARY, 0.12) + " 0%, " +
                StitchStyles.C_SURFACE + " 55%, #070a0f 100%);"
        );

        // Decorative circle backdrop
        Circle glow = new Circle(220, Color.web(StitchStyles.C_PRIMARY, 0.08));
        glow.setEffect(new DropShadow(90, Color.web(StitchStyles.C_PRIMARY, 0.22)));
        StackPane.setAlignment(glow, Pos.CENTER);

        // ========== LOGIN CARD ==========
        VBox card = new VBox(20);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(50, 60, 50, 60));
        card.setMaxWidth(420);
        card.setStyle(
                StitchStyles.glassPanel(0.60, 24) +
                "-fx-padding: 50 60 50 60;"
        );
        card.setEffect(new DropShadow(44, Color.web(StitchStyles.C_PRIMARY, 0.10)));

        // Crown + Title
        Label crown = new Label("👑");
        crown.setStyle("-fx-font-size: 56px;");
        Glow crownGlow = new Glow(0.6);
        crown.setEffect(crownGlow);

        // Pulsing glow animation on crown
        Timeline glowAnim = new Timeline(
                new KeyFrame(Duration.ZERO,    new KeyValue(crownGlow.levelProperty(), 0.4)),
                new KeyFrame(Duration.seconds(1.5), new KeyValue(crownGlow.levelProperty(), 0.9))
        );
        glowAnim.setCycleCount(Animation.INDEFINITE);
        glowAnim.setAutoReverse(true);
        glowAnim.play();

        Label title = new Label("KING OF LAB");
        title.setFont(Font.font("Space Grotesk", FontWeight.EXTRA_BOLD, 30));
        title.setStyle("-fx-text-fill: " + StitchStyles.C_TEXT_MAIN + "; -fx-letter-spacing: 0.12em;");
        title.setEffect(new DropShadow(18, Color.web(StitchStyles.C_PRIMARY, 0.22)));

        Label subtitle = new Label("Lab Management System v3.0");
        subtitle.setStyle("-fx-text-fill: " + StitchStyles.rgba(StitchStyles.C_TEXT_MAIN, 0.45) + "; -fx-font-size: 12px;");

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: rgba(223,226,235,0.12);");

        // Fields
        TextField userField = createField("👤  Username");
        PasswordField passField = createPassField("🔑  Password");

        Button loginBtn = new Button("ENTER THE LAB");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setStyle(
                StitchStyles.gradientPrimaryCta(12) +
                "-fx-font-size: 13px;" +
                "-fx-padding: 14 20;"
        );
        loginBtn.setEffect(new DropShadow(18, Color.web(StitchStyles.C_PRIMARY, 0.22)));
        loginBtn.setOnMouseEntered(e -> loginBtn.setOpacity(0.92));
        loginBtn.setOnMouseExited(e  -> loginBtn.setOpacity(1.0));

        Label statusLbl = new Label("");
        statusLbl.setStyle("-fx-text-fill: #ffb4ab; -fx-font-size: 12px;");

        Button registerBtn = new Button("New Student? Register here");
        registerBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: " + StitchStyles.rgba(StitchStyles.C_PRIMARY, 0.85) + ";" +
                "-fx-underline: true;" +
                "-fx-cursor: hand;");

        // Keyboard nav
        userField.setOnAction(e -> passField.requestFocus());
        passField.setOnAction(e -> loginBtn.fire());
        registerBtn.setOnAction(e -> StudentRegistrationView.show(stage));

        loginBtn.setOnAction(e -> {
            String user = userField.getText().trim();
            String pass = passField.getText();

            if (user.isEmpty() || pass.isEmpty()) {
                statusLbl.setText("Please enter username and password.");
                return;
            }

            User u = DatabaseManager.login(user, pass);
            if (u != null) {
                AuditLogger.logLogin(user, true);
                if ("ADMIN".equalsIgnoreCase(u.getRole())) {
                    AdminDashboard.show(stage, u);
                } else {
                    StudentDashboard.show(stage, u);
                }
            } else {
                AuditLogger.logLogin(user, false);
                statusLbl.setText("❌ Invalid username or password.");
                // Shake animation on card
                shakeNode(card);
            }
        });

        card.getChildren().addAll(crown, title, subtitle, sep,
                userField, passField, loginBtn, statusLbl, registerBtn);

        root.getChildren().addAll(glow, card);

        Scene scene = new Scene(root, 500, 580);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — Login");
        stage.setResizable(false);
        stage.show();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static TextField createField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setStyle(
                "-fx-background-color: rgba(10,14,20,0.55);" +
                "-fx-text-fill: " + StitchStyles.C_TEXT_MAIN + ";" +
                "-fx-prompt-text-fill: rgba(223,226,235,0.28);" +
                "-fx-background-radius: 12;" +
                "-fx-padding: 12;" +
                "-fx-font-size: 13px;");
        f.focusedProperty().addListener((obs, o, n) -> {
            // "bottom glow" style hint (JavaFX can't do border-bottom only reliably without CSS),
            // so we slightly brighten the surface and add a faint inner-glow border.
            if (n) f.setStyle(f.getStyle() + "-fx-border-color: rgba(0,240,255,0.30); -fx-border-radius: 12;");
            else   f.setStyle(f.getStyle().replace("-fx-border-color: rgba(0,240,255,0.30); -fx-border-radius: 12;", ""));
        });
        return f;
    }

    private static PasswordField createPassField(String prompt) {
        PasswordField f = new PasswordField();
        f.setPromptText(prompt);
        f.setStyle(
                "-fx-background-color: rgba(10,14,20,0.55);" +
                "-fx-text-fill: " + StitchStyles.C_TEXT_MAIN + ";" +
                "-fx-prompt-text-fill: rgba(223,226,235,0.28);" +
                "-fx-background-radius: 12;" +
                "-fx-padding: 12;" +
                "-fx-font-size: 13px;");
        return f;
    }

    private static void shakeNode(javafx.scene.Node node) {
        TranslateTransition shake = new TranslateTransition(Duration.millis(60), node);
        shake.setFromX(0);
        shake.setByX(12);
        shake.setCycleCount(5);
        shake.setAutoReverse(true);
        shake.setOnFinished(e -> node.setTranslateX(0));
        shake.play();
    }
}
