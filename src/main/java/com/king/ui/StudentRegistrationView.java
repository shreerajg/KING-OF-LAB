package com.king.ui;

import com.king.database.DatabaseManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class StudentRegistrationView {

    public static void show(Stage stage) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(24));
        root.setAlignment(Pos.CENTER);
        root.setStyle(StitchStyles.appRoot());

        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(28, 28, 22, 28));
        card.setMaxWidth(420);
        card.setStyle(StitchStyles.glassPanel(0.60, 22));

        Label title = new Label("STUDENT REGISTRATION");
        title.setStyle(StitchStyles.titleMd() + "-fx-font-size: 18px; -fx-letter-spacing: 0.12em;");

        TextField nameField = new TextField();
        nameField.setPromptText("Username");
        nameField.setStyle(fieldStyle());

        PasswordField passField = new PasswordField();
        passField.setPromptText("Create Password");
        passField.setStyle(fieldStyle());

        // Roll Number field
        TextField rollField = new TextField();
        rollField.setPromptText("Roll Number (e.g., 1, 2, 3...)");
        rollField.setStyle(fieldStyle());

        // Class text field (instead of dropdown for flexibility)
        TextField classField = new TextField();
        classField.setPromptText("Class (e.g., FY, SY, TY)");
        classField.setStyle(fieldStyle());

        // Division text field (instead of dropdown for flexibility)
        TextField divField = new TextField();
        divField.setPromptText("Division (e.g., A, B, C)");
        divField.setStyle(fieldStyle());

        Button registerBtn = new Button("Register");
        registerBtn.setMaxWidth(Double.MAX_VALUE);
        registerBtn.setStyle(
                StitchStyles.gradientPrimaryCta(12) +
                "-fx-font-size: 12px;" +
                "-fx-padding: 12 16;"
        );

        Label statusLbl = new Label("");
        statusLbl.setStyle("-fx-text-fill: #ffb4ab;");

        Button backBtn = new Button("Back to Login");
        backBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: " + StitchStyles.rgba(StitchStyles.C_TEXT_MAIN, 0.55) + ";" +
                "-fx-underline: true;" +
                "-fx-cursor: hand;"
        );
        backBtn.setOnAction(e -> LoginView.show(stage));

        registerBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            String pass = passField.getText();
            String rollStr = rollField.getText().trim();
            String className = classField.getText().trim();
            String division = divField.getText().trim();

            if (name.isEmpty() || pass.isEmpty() || rollStr.isEmpty() ||
                    className.isEmpty() || division.isEmpty()) {
                statusLbl.setText("All fields are required!");
                statusLbl.setStyle("-fx-text-fill: #ffb4ab;");
                return;
            }

            // Validate roll number is numeric
            int rollNumber;
            try {
                rollNumber = Integer.parseInt(rollStr);
                if (rollNumber <= 0) {
                    statusLbl.setText("Roll number must be positive!");
                    statusLbl.setStyle("-fx-text-fill: #ffb4ab;");
                    return;
                }
            } catch (NumberFormatException ex) {
                statusLbl.setText("Roll number must be a number!");
                statusLbl.setStyle("-fx-text-fill: #ffb4ab;");
                return;
            }

            if (DatabaseManager.registerStudent(name, pass, "{}", rollNumber, className, division)) {
                statusLbl.setText("Registration Successful!");
                statusLbl.setStyle("-fx-text-fill: #7af19c;");
                // Auto-redirect to login after 1 second
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.seconds(1));
                pause.setOnFinished(ev -> LoginView.show(stage));
                pause.play();
            } else {
                statusLbl.setText("Registration Failed (Username taken?)");
                statusLbl.setStyle("-fx-text-fill: #ffb4ab;");
            }
        });

        card.getChildren().addAll(
                title,
                nameField,
                passField,
                rollField,
                classField,
                divField,
                registerBtn,
                statusLbl,
                backBtn
        );

        root.getChildren().add(card);

        Scene scene = new Scene(root, 400, 500);
        stage.setScene(scene);
        stage.setTitle("👑 King of Lab — Registration");
    }

    private static String fieldStyle() {
        return "-fx-background-color: rgba(10,14,20,0.55);" +
               "-fx-text-fill: " + StitchStyles.C_TEXT_MAIN + ";" +
               "-fx-prompt-text-fill: rgba(223,226,235,0.28);" +
               "-fx-background-radius: 12;" +
               "-fx-padding: 12;" +
               "-fx-font-size: 13px;";
    }
}
