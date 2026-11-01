package io.serverpilotx.ui;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/** Loading screen showing the ServerPilotX poster with a progress bar. */
public final class Splash {
    private static final double W = 840, H = 560;

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final ProgressBar bar = new ProgressBar(0);
    private final Label status = new Label("Starting…");

    public Splash(Image icon) {
        Image poster = new Image(Splash.class.getResource("/io/serverpilotx/img/poster.jpg").toExternalForm(), W, H, true, true);
        ImageView iv = new ImageView(poster);
        iv.setFitWidth(W);
        iv.setFitHeight(H);

        status.setStyle("-fx-text-fill: #cfe0ff; -fx-font-size: 12.5px; -fx-font-family: 'Noto Sans';");
        bar.setPrefWidth(W - 80);
        bar.setPrefHeight(6);
        bar.setStyle("-fx-accent: #2f8cff; -fx-control-inner-background: rgba(255,255,255,0.12);"
                + "-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");
        Label version = new Label("v1.0.0");
        version.setStyle("-fx-text-fill: #7f93b8; -fx-font-size: 11px;");

        VBox overlay = new VBox(8, status, bar, version);
        overlay.setAlignment(Pos.BOTTOM_LEFT);
        overlay.setPadding(new Insets(0, 40, 22, 40));
        overlay.setMaxHeight(110);
        overlay.setStyle("-fx-background-color: linear-gradient(to bottom, rgba(6,10,22,0), rgba(6,10,22,0.92) 45%);");
        StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);

        StackPane card = new StackPane(iv, overlay);
        card.setMinSize(W, H);
        card.setMaxSize(W, H);
        card.setStyle("-fx-background-color: #0a1020; -fx-background-radius: 16;");
        Rectangle clip = new Rectangle(W, H);
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        card.setClip(clip);
        // Tiling window managers may resize the splash; keep the poster centred on a matching background.
        StackPane root = new StackPane(card);
        root.setStyle("-fx-background-color: transparent;");
        stage.widthProperty().addListener((o, a, b) -> root.setStyle(b.doubleValue() > W + 1
                ? "-fx-background-color: #060a16;" : "-fx-background-color: transparent;"));

        Scene scene = new Scene(root, W, H);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setTitle("ServerPilotX");
        stage.setResizable(false); // fixed size hint makes tiling WMs (sway, i3) float the splash
        stage.setWidth(W);
        stage.setHeight(H);
        if (icon != null) stage.getIcons().add(icon);
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        stage.setX(vb.getMinX() + (vb.getWidth() - W) / 2);
        stage.setY(vb.getMinY() + (vb.getHeight() - H) / 2);
    }

    public void show() {
        stage.show();
    }

    public Scene scene() {
        return stage.getScene();
    }

    public void progress(double p, String text) {
        Ui.fx(() -> {
            bar.setProgress(p);
            status.setText(text);
        });
    }

    public void close(Runnable after) {
        FadeTransition f = new FadeTransition(Duration.millis(260), stage.getScene().getRoot());
        f.setToValue(0);
        f.setOnFinished(e -> {
            stage.close();
            if (after != null) after.run();
        });
        f.play();
    }
}
