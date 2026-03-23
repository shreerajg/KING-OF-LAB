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
 * King of Lab — redesigned login page.
 * Dark gradient background, crown branding, animated glow on logo.
 */
public class LoginView {

    public static void show(Stage stage) {

        // ========== ROOT ==========
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #0a0a1a, #0f0f2e, #1a0a2e);");

        // Decorative circle backdrop
        Circle glow = new Circle(200, Color.web("#6c3483", 0.08));
        glow.setEffect(new DropShadow(80, Color.web("#9b59b6", 0.3)));
        StackPane.setAlignment(glow, Pos.CENTER);

        // ========== LOGIN CARD ==========
        VBox card = new VBox(20);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(50, 60, 50, 60));
        card.setMaxWidth(420);
        card.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-background-radius: 24;" +
                "-fx-border-color: rgba(155,89,182,0.5);" +
                "-fx-border-radius: 24;" +
                "-fx-border-width: 1.5;");
        card.setEffect(new DropShadow(40, Color.web("#9b59b6", 0.4)));

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
        title.setFont(Font.font("Arial", FontWeight.EXTRA_BOLD, 30));
        title.setStyle("-fx-text-fill: #c39bd3;");
        title.setEffect(new DropShadow(12, Color.web("#9b59b6", 0.6)));

        Label subtitle = new Label("Lab Management System v3.0");
        subtitle.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 12px;");

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: rgba(155,89,182,0.4);");

        // Fields
        TextField userField = createField("👤  Username");
        PasswordField passField = createPassField("🔑  Password");

        Button loginBtn = new Button("ENTER THE LAB");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setStyle(
                "-fx-background-color: linear-gradient(to right, #8e44ad, #9b59b6);" +
                "-fx-text-fill: white;" +
                "-fx-font-weight: bold;" +
                "-fx-font-size: 14px;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 14 20;" +
                "-fx-cursor: hand;");
        loginBtn.setEffect(new DropShadow(12, Color.web("#9b59b6", 0.5)));
        loginBtn.setOnMouseEntered(e -> loginBtn.setStyle(loginBtn.getStyle().replace("0.5)", "0.9)")));
        loginBtn.setOnMouseExited(e  -> loginBtn.setStyle(loginBtn.getStyle().replace("0.9)", "0.5)")));

        Label statusLbl = new Label("");
        statusLbl.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 12px;");

        Button registerBtn = new Button("New Student? Register here");
        registerBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: #9b59b6;" +
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
                "-fx-background-color: rgba(255,255,255,0.07);" +
                "-fx-text-fill: white;" +
                "-fx-prompt-text-fill: #666;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 12;" +
                "-fx-font-size: 13px;");
        f.focusedProperty().addListener((obs, o, n) -> {
            if (n) f.setStyle(f.getStyle() + "-fx-border-color: #9b59b6; -fx-border-radius: 10;");
            else   f.setStyle(f.getStyle().replace("-fx-border-color: #9b59b6; -fx-border-radius: 10;", ""));
        });
        return f;
    }

    private static PasswordField createPassField(String prompt) {
        PasswordField f = new PasswordField();
        f.setPromptText(prompt);
        f.setStyle(
                "-fx-background-color: rgba(255,255,255,0.07);" +
                "-fx-text-fill: white;" +
                "-fx-prompt-text-fill: #666;" +
                "-fx-background-radius: 10;" +
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
