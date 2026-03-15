package com.ghost.ui;

import com.ghost.database.DatabaseManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class StudentRegistrationView {

    public static void show(Stage stage) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #222;");

        Label title = new Label("STUDENT REGISTRATION");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");

        TextField nameField = new TextField();
        nameField.setPromptText("Username");

        PasswordField passField = new PasswordField();
        passField.setPromptText("Create Password");

        // Roll Number field
        TextField rollField = new TextField();
        rollField.setPromptText("Roll Number (e.g., 1, 2, 3...)");

        // Class text field (instead of dropdown for flexibility)
        TextField classField = new TextField();
        classField.setPromptText("Class (e.g., FY, SY, TY)");

        // Division text field (instead of dropdown for flexibility)
        TextField divField = new TextField();
        divField.setPromptText("Division (e.g., A, B, C)");

        Button registerBtn = new Button("Register");
        registerBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white;");

        Label statusLbl = new Label("");
        statusLbl.setStyle("-fx-text-fill: red;");

        Button backBtn = new Button("Back to Login");
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
                statusLbl.setStyle("-fx-text-fill: red;");
                return;
            }

            // Validate roll number is numeric
            int rollNumber;
            try {
                rollNumber = Integer.parseInt(rollStr);
                if (rollNumber <= 0) {
                    statusLbl.setText("Roll number must be positive!");
                    statusLbl.setStyle("-fx-text-fill: red;");
                    return;
                }
            } catch (NumberFormatException ex) {
                statusLbl.setText("Roll number must be a number!");
                statusLbl.setStyle("-fx-text-fill: red;");
                return;
            }

            if (DatabaseManager.registerStudent(name, pass, "{}", rollNumber, className, division)) {
                statusLbl.setText("Registration Successful!");
                statusLbl.setStyle("-fx-text-fill: green;");
                // Auto-redirect to login after 1 second
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.seconds(1));
                pause.setOnFinished(ev -> LoginView.show(stage));
                pause.play();
            } else {
                statusLbl.setText("Registration Failed (Username taken?)");
                statusLbl.setStyle("-fx-text-fill: red;");
            }
        });

        root.getChildren().addAll(title, nameField, passField, rollField,
                classField, divField, registerBtn, statusLbl, backBtn);

        Scene scene = new Scene(root, 400, 500);
        stage.setScene(scene);
        stage.setTitle("Ghost - Registration");
    }
}
