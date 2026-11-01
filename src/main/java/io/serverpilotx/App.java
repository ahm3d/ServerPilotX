package io.serverpilotx;

import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import io.serverpilotx.core.Metrics;
import io.serverpilotx.ui.MainWindow;
import io.serverpilotx.ui.Snapshot;
import io.serverpilotx.ui.Splash;
import io.serverpilotx.ui.Theme;
import io.serverpilotx.ui.Ui;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/** ServerPilotX entry point: shows the poster splash while connecting, then the main window. */
public final class App extends Application {
    public static final String VERSION = "1.0.0";

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(true);
        Theme.init();
        Image icon = new Image(App.class.getResource("/io/serverpilotx/img/icon-256.png").toExternalForm());
        Splash splash = new Splash(icon);
        splash.show();
        if (Snapshot.dir() != null) {
            javafx.animation.PauseTransition snap = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.2));
            snap.setOnFinished(e -> Snapshot.save(splash.scene(), "00-splash"));
            snap.play();
        }

        long started = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            String warning = null;
            splash.progress(0.10, "Loading settings…");
            Hosts.load();
            Host h = Hosts.active();
            splash.progress(0.30, "Connecting to " + h.name() + "…");
            try {
                h.detect();
            } catch (RuntimeException e) {
                warning = "Could not connect to " + h.name() + ": " + e.getMessage() + " — switched to this computer.";
                Host local = Hosts.all().get(0);
                local.detect();
                Hosts.setActive(local);
                h = local;
            }
            splash.progress(0.55, "Detected " + h.os() + "…");
            pause(250);
            splash.progress(0.70, "Starting metrics collector…");
            Metrics.get().start(h);
            pause(250);
            splash.progress(0.88, "Building interface…");
            long left = 2200 - (System.currentTimeMillis() - started);
            if (left > 0) pause(left);
            String warn = warning;
            Platform.runLater(() -> {
                new MainWindow(stage, icon);
                splash.progress(1.0, "Ready");
                splash.close(() -> {
                    stage.show();
                    stage.toFront();
                    if (warn != null) Ui.toast(warn, Ui.Kind.ERROR);
                    if (Snapshot.dir() != null) tour(stage);
                });
            });
        }, "spx-startup");
        t.setDaemon(true);
        t.start();
    }

    /** Debug: visits every page in both themes and saves snapshots (SPX_SNAPSHOT_DIR), then exits. */
    private static void tour(Stage stage) {
        String only = System.getenv("SPX_SNAPSHOT_PAGES");
        java.util.List<String> pages = only != null ? java.util.List.of(only.split(","))
                : java.util.List.of("dashboard", "servers", "docker", "sites", "ssl", "firewall", "users", "services", "files", "terminal", "audit", "settings");
        javafx.animation.SequentialTransition seq = new javafx.animation.SequentialTransition();
        int i = 1;
        for (String theme : new String[]{"DARK", "LIGHT"}) {
            if ("LIGHT".equals(theme) && System.getenv("SPX_SNAPSHOT_LIGHT") == null) continue;
            for (String p : pages) {
                int n = i++;
                javafx.animation.PauseTransition go = new javafx.animation.PauseTransition(javafx.util.Duration.millis(50));
                go.setOnFinished(e -> {
                    Theme.set(io.serverpilotx.core.Settings.Theme.valueOf(theme));
                    MainWindow.get().navigate(p);
                });
                javafx.animation.PauseTransition shot = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
                shot.setOnFinished(e -> Snapshot.save(stage.getScene(), String.format("%02d-%s-%s", n, p, theme.toLowerCase())));
                seq.getChildren().addAll(go, shot);
            }
        }
        seq.setOnFinished(e -> { Theme.set(io.serverpilotx.core.Settings.Theme.DARK); Platform.exit(); System.exit(0); });
        seq.play();
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) {
        System.setProperty("prism.lcdtext", "false");
        launch(args);
    }
}
