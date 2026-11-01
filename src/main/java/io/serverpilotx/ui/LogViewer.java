package io.serverpilotx.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiFunction;

import io.serverpilotx.core.Host;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * Log window with tail size, live follow, filter, find and download.
 * argvFor(tailLines, follow) returns the command to run (tailLines = -1 means all).
 */
public final class LogViewer {
    private final Host host;
    private final boolean asRoot;
    private final BiFunction<Integer, Boolean, List<String>> argvFor;
    private final TextArea area = new TextArea();
    private final Label status = Ui.label("", "muted", "small");
    private final ComboBox<String> tail = new ComboBox<>();
    private final ToggleButton follow = new ToggleButton("Follow");
    private final TextField filter = new TextField();
    private Process proc;
    private final StringBuilder full = new StringBuilder();

    private LogViewer(Host host, boolean asRoot, BiFunction<Integer, Boolean, List<String>> argvFor) {
        this.host = host;
        this.asRoot = asRoot;
        this.argvFor = argvFor;
    }

    public static void open(String title, Host host, boolean asRoot, BiFunction<Integer, Boolean, List<String>> argvFor) {
        new LogViewer(host, asRoot, argvFor).show(title);
    }

    private void show(String title) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(title);
        if (Ui.owner() != null) d.initOwner(Ui.owner());
        d.initModality(Modality.NONE);
        Theme.apply(d);
        d.setResizable(true);

        area.setEditable(false);
        area.getStyleClass().add("console");
        area.setPrefSize(980, 560);
        tail.getItems().addAll("100", "500", "1000", "5000", "All");
        tail.setValue("500");
        tail.setOnAction(e -> load());
        follow.setGraphic(Icons.of("activity", 14));
        follow.setOnAction(e -> load());
        filter.setPromptText("Filter lines (grep)…");
        filter.textProperty().addListener((o, a, b) -> applyFilter());
        TextField find = new TextField();
        find.setPromptText("Find…");
        find.setOnAction(e -> Ui.findNext(area, find.getText()));
        HBox bar = new HBox(8, Ui.label("Lines", "muted"), tail, follow, filter, find, Ui.spacer(),
                Ui.button("Reload", "refresh", e -> load()),
                Ui.button("Download", "download", e -> Ui.saveText(title.replaceAll("\\W+", "-") + ".log", full.toString())));
        bar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filter, Priority.ALWAYS);
        VBox box = new VBox(8, bar, area, status);
        VBox.setVgrow(area, Priority.ALWAYS);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.setOnHidden(e -> stop());
        d.show();
        load();
    }

    private void stop() {
        if (proc != null) {
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
            proc = null;
        }
    }

    private void load() {
        stop();
        full.setLength(0);
        area.clear();
        int n = "All".equals(tail.getValue()) ? -1 : Integer.parseInt(tail.getValue());
        boolean f = follow.isSelected();
        List<String> argv = argvFor.apply(n, f);
        status.setText(f ? "Following live output…" : "Loading…");
        Ui.runAsync(() -> {
            try {
                Process p = host.stream(argv, asRoot);
                proc = p;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder chunk = new StringBuilder();
                    long last = 0;
                    while ((line = r.readLine()) != null) {
                        chunk.append(Ui.stripAnsi(line)).append('\n');
                        long now = System.currentTimeMillis();
                        if (now - last > 150 || chunk.length() > 64_000) {
                            String c = chunk.toString();
                            chunk.setLength(0);
                            last = now;
                            if (p != proc) return;
                            Ui.fx(() -> append(c));
                        }
                    }
                    String c = chunk.toString();
                    if (p == proc) Ui.fx(() -> append(c));
                }
                int code = p.waitFor();
                if (p == proc) Ui.fx(() -> status.setText(code == 0 ? full.toString().split("\n").length + " lines" : "Exited with code " + code));
            } catch (Exception ex) {
                Ui.fx(() -> status.setText(Ui.message(ex)));
            }
        });
    }

    private void append(String chunk) {
        full.append(chunk);
        if (full.length() > 4_000_000) full.delete(0, full.length() - 3_000_000);
        String q = filter.getText();
        if (q == null || q.isBlank()) {
            area.appendText(chunk);
        } else {
            StringBuilder b = new StringBuilder();
            for (String l : chunk.split("\n")) if (l.toLowerCase().contains(q.toLowerCase())) b.append(l).append('\n');
            area.appendText(b.toString());
        }
        if (follow.isSelected()) area.setScrollTop(Double.MAX_VALUE);
    }

    private void applyFilter() {
        String q = filter.getText();
        if (q == null || q.isBlank()) {
            area.setText(full.toString());
            return;
        }
        StringBuilder b = new StringBuilder();
        for (String l : full.toString().split("\n")) if (l.toLowerCase().contains(q.toLowerCase())) b.append(l).append('\n');
        area.setText(b.toString());
    }
}
