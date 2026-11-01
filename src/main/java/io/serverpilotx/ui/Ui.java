package io.serverpilotx.ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.core.CmdException;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Privilege;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.IntegerProperty;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

/** Shared UI helpers: background work, toasts, dialogs and small widgets. */
public final class Ui {
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "spx-worker");
        t.setDaemon(true);
        return t;
    });

    /** Number of running background jobs; the top bar shows a spinner while > 0. */
    public static final IntegerProperty BUSY = new SimpleIntegerProperty(0);

    private static Window owner;
    private static VBox toastLayer;

    private Ui() {}

    public static void init(Window w, VBox toasts) {
        owner = w;
        toastLayer = toasts;
        Privilege.setPrompter(Snapshot.dir() != null ? (h, m) -> null : Ui::askPassword);
    }

    public static Window owner() { return owner; }

    public static void fx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    // ------------------------------------------------------------ background

    public static <T> void bg(Callable<T> work, Consumer<T> onOk) {
        bg(work, onOk, null);
    }

    public static <T> void bg(Callable<T> work, Consumer<T> onOk, Consumer<String> onErr) {
        fx(() -> BUSY.set(BUSY.get() + 1));
        POOL.submit(() -> {
            try {
                T v = work.call();
                fx(() -> {
                    BUSY.set(BUSY.get() - 1);
                    if (onOk != null) onOk.accept(v);
                });
            } catch (Throwable e) {
                String msg = message(e);
                fx(() -> {
                    BUSY.set(BUSY.get() - 1);
                    if (onErr != null) onErr.accept(msg);
                    else toast(msg, Kind.ERROR);
                });
            }
        });
    }

    /** Runs a change, writes it to the audit log, shows a toast and calls {@code after}. */
    public static void action(String action, String target, Callable<String> work, Runnable after) {
        bg(work, detail -> {
            AuditLog.log(action, target, detail, true);
            toast(action + (target == null || target.isBlank() ? "" : " · " + target), Kind.OK);
            if (after != null) after.run();
        }, err -> {
            AuditLog.log(action, target, err, false);
            errorDialog(action + " failed", err);
            if (after != null) after.run();
        });
    }

    public static void runAsync(Runnable r) { POOL.submit(r); }

    public static String message(Throwable e) {
        if (e instanceof CmdException) return e.getMessage();
        if (e.getCause() instanceof CmdException c) return c.getMessage();
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    // ---------------------------------------------------------------- toasts

    public enum Kind { OK, ERROR, INFO }

    public static void toast(String text, Kind kind) {
        fx(() -> {
            if (toastLayer == null) return;
            String icon = switch (kind) { case OK -> "check"; case ERROR -> "alert"; case INFO -> "info"; };
            String ic = switch (kind) { case OK -> "icon-green"; case ERROR -> "icon-red"; case INFO -> "icon-accent"; };
            Label l = new Label(text.length() > 220 ? text.substring(0, 220) + "…" : text);
            l.setWrapText(true);
            HBox t = new HBox(Icons.of(icon, 18, ic), l);
            t.getStyleClass().addAll("toast", "toast-" + kind.name().toLowerCase());
            t.setOpacity(0);
            toastLayer.getChildren().add(t);
            FadeTransition in = new FadeTransition(Duration.millis(180), t);
            in.setToValue(1);
            in.play();
            PauseTransition stay = new PauseTransition(Duration.seconds(kind == Kind.ERROR ? 7 : 3.5));
            stay.setOnFinished(e -> {
                FadeTransition out = new FadeTransition(Duration.millis(250), t);
                out.setToValue(0);
                out.setOnFinished(x -> toastLayer.getChildren().remove(t));
                out.play();
            });
            stay.play();
            t.setOnMouseClicked(e -> toastLayer.getChildren().remove(t));
        });
    }

    // --------------------------------------------------------------- dialogs

    private static void prep(Dialog<?> d, String title, String header) {
        d.setTitle(title);
        d.setHeaderText(header);
        if (owner != null) d.initOwner(owner);
        d.initModality(Modality.WINDOW_MODAL);
        d.setGraphic(null);
        Theme.apply(d);
    }

    public static boolean confirm(String title, String message, String okText, boolean danger) {
        Dialog<ButtonType> d = new Dialog<>();
        prep(d, title, title);
        Label l = new Label(message);
        l.setWrapText(true);
        l.setMaxWidth(460);
        d.getDialogPane().setContent(l);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ok);
        Button b = (Button) d.getDialogPane().lookupButton(ok);
        if (danger) {
            b.setDefaultButton(false);
            b.getStyleClass().add("btn-danger");
        }
        return d.showAndWait().orElse(ButtonType.CANCEL) == ok;
    }

    public static void errorDialog(String title, String message) {
        Dialog<ButtonType> d = new Dialog<>();
        prep(d, title, title);
        TextArea ta = new TextArea(message);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.getStyleClass().add("code-area");
        ta.setPrefSize(560, Math.min(320, 60 + 18 * message.split("\n").length));
        d.getDialogPane().setContent(ta);
        d.getDialogPane().getButtonTypes().add(ButtonType.OK);
        d.showAndWait();
    }

    public static Optional<String> prompt(String title, String label, String initial) {
        TextField tf = new TextField(initial == null ? "" : initial);
        tf.setPrefColumnCount(28);
        Form f = new Form();
        f.row(label, tf);
        Platform.runLater(tf::requestFocus);
        if (!dialog(title, null, f, "OK", () -> tf.getText().isBlank() ? "Value required" : null)) return Optional.empty();
        return Optional.of(tf.getText().strip());
    }

    /** Generic form dialog. validator returns an error message, or null when input is valid. */
    public static boolean dialog(String title, String header, Node content, String okText, Supplier<String> validator) {
        Dialog<ButtonType> d = new Dialog<>();
        prep(d, title, header == null ? title : header);
        Label err = new Label();
        err.getStyleClass().add("text-red");
        err.setWrapText(true);
        err.setManaged(false);
        err.setVisible(false);
        VBox box = new VBox(12, content, err);
        d.getDialogPane().setContent(box);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ok);
        Button okBtn = (Button) d.getDialogPane().lookupButton(ok);
        okBtn.addEventFilter(ActionEvent.ACTION, e -> {
            String msg = validator == null ? null : validator.get();
            if (msg != null) {
                err.setText(msg);
                err.setManaged(true);
                err.setVisible(true);
                d.getDialogPane().getScene().getWindow().sizeToScene();
                e.consume();
            }
        });
        return d.showAndWait().orElse(ButtonType.CANCEL) == ok;
    }

    /** Read-only text viewer with optional save. */
    public static void textDialog(String title, String text) {
        Dialog<ButtonType> d = new Dialog<>();
        prep(d, title, title);
        TextArea ta = new TextArea(text);
        ta.setEditable(false);
        ta.getStyleClass().add("console");
        ta.setPrefSize(860, 520);
        TextField find = new TextField();
        find.setPromptText("Find…");
        find.setOnAction(e -> findNext(ta, find.getText()));
        Button save = button("Save as…", "download", e -> saveText(title.replaceAll("\\W+", "-") + ".txt", ta.getText()));
        HBox bar = new HBox(8, find, spacer(), save);
        HBox.setHgrow(find, Priority.ALWAYS);
        VBox box = new VBox(10, bar, ta);
        VBox.setVgrow(ta, Priority.ALWAYS);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.setResizable(true);
        d.showAndWait();
    }

    public static void findNext(TextArea ta, String q) {
        if (q == null || q.isEmpty()) return;
        String text = ta.getText().toLowerCase();
        int from = ta.getSelection().getEnd();
        int i = text.indexOf(q.toLowerCase(), from);
        if (i < 0) i = text.indexOf(q.toLowerCase());
        if (i >= 0) ta.selectRange(i, i + q.length());
    }

    /** Editable text dialog. onSave runs in background and may throw; returns when the dialog closes. */
    public static void editor(String title, String subtitle, String initial, Function<String, String> onSave) {
        Dialog<ButtonType> d = new Dialog<>();
        prep(d, title, title);
        TextArea ta = new TextArea(initial);
        ta.getStyleClass().add("code-area");
        ta.setPrefSize(880, 540);
        Label sub = new Label(subtitle == null ? "" : subtitle);
        sub.getStyleClass().add("muted");
        sub.setWrapText(true);
        Label status = new Label();
        status.setWrapText(true);
        VBox box = new VBox(10, sub, ta, status);
        VBox.setVgrow(ta, Priority.ALWAYS);
        d.getDialogPane().setContent(box);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, saveType);
        d.setResizable(true);
        Button save = (Button) d.getDialogPane().lookupButton(saveType);
        save.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            save.setDisable(true);
            status.getStyleClass().setAll("label", "muted");
            status.setText("Saving…");
            String content = ta.getText();
            bg(() -> onSave.apply(content), msg -> {
                d.setResult(saveType);
                d.close();
                if (msg != null && !msg.isBlank()) toast(msg, Kind.OK);
            }, err -> {
                save.setDisable(false);
                status.getStyleClass().setAll("label", "text-red");
                status.setText(err);
            });
        });
        d.showAndWait();
    }

    /**
     * Runs a long command and streams its output into a console dialog.
     * onDone receives the exit code (on the FX thread).
     */
    public static void stream(String title, Host host, List<String> argv, boolean asRoot, Consumer<Integer> onDone) {
        Dialog<ButtonType> d = new Dialog<>();
        prep(d, title, title);
        TextArea ta = new TextArea();
        ta.setEditable(false);
        ta.getStyleClass().add("console");
        ta.setPrefSize(860, 460);
        ta.setWrapText(true);
        Label status = new Label("Running…");
        status.getStyleClass().add("muted");
        Label cmd = new Label("$ " + (asRoot ? "sudo " : "") + String.join(" ", argv));
        cmd.getStyleClass().addAll("mono", "muted");
        cmd.setWrapText(true);
        VBox box = new VBox(8, cmd, ta, status);
        VBox.setVgrow(ta, Priority.ALWAYS);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.setResizable(true);
        Button close = (Button) d.getDialogPane().lookupButton(ButtonType.CLOSE);
        close.setText("Stop");
        Process[] proc = new Process[1];
        boolean[] finished = {false};
        close.addEventFilter(ActionEvent.ACTION, e -> {
            if (!finished[0] && proc[0] != null) {
                proc[0].descendants().forEach(ProcessHandle::destroy);
                proc[0].destroy();
            }
        });
        fx(() -> BUSY.set(BUSY.get() + 1));
        POOL.submit(() -> {
            int code;
            try {
                proc[0] = host.stream(argv, asRoot);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc[0].getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder buf = new StringBuilder();
                    long lastFlush = 0;
                    while ((line = r.readLine()) != null) {
                        buf.append(stripAnsi(line)).append('\n');
                        long now = System.currentTimeMillis();
                        if (now - lastFlush > 120) {
                            String chunk = buf.toString();
                            buf.setLength(0);
                            lastFlush = now;
                            fx(() -> ta.appendText(chunk));
                        }
                    }
                    String rest = buf.toString();
                    fx(() -> ta.appendText(rest));
                }
                code = proc[0].waitFor();
            } catch (Throwable ex) {
                String m = message(ex);
                fx(() -> ta.appendText("\n" + m + "\n"));
                code = -1;
            }
            int c = code;
            fx(() -> {
                BUSY.set(BUSY.get() - 1);
                finished[0] = true;
                close.setText("Close");
                status.getStyleClass().setAll("label", c == 0 ? "text-green" : "text-red");
                status.setText(c == 0 ? "Finished successfully." : "Exited with code " + c + ".");
                AuditLog.log(title, String.join(" ", argv), "exit " + c, c == 0);
                if (onDone != null) onDone.accept(c);
            });
        });
        d.show();
    }

    public static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;?0-9]*[A-Za-z]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "").replace("\r", "");
    }

    private static String askPassword(Host host, String message) {
        if (Platform.isFxApplicationThread()) return passwordDialog(host, message);
        CompletableFuture<String> f = new CompletableFuture<>();
        Platform.runLater(() -> f.complete(passwordDialog(host, message)));
        try {
            return f.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static String passwordDialog(Host host, String message) {
        Dialog<ButtonType> d = new Dialog<>();
        prep(d, "Authentication required", "Authentication required");
        d.setGraphic(Icons.of("lock", 32, "icon-accent"));
        PasswordField pf = new PasswordField();
        pf.setPromptText("Password");
        Label l = new Label(message);
        l.setWrapText(true);
        l.setMaxWidth(380);
        Label hint = new Label("Kept in memory for this session only.");
        hint.getStyleClass().add("form-hint");
        d.getDialogPane().setContent(new VBox(12, l, pf, hint));
        ButtonType ok = new ButtonType("Authenticate", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ok);
        Platform.runLater(pf::requestFocus);
        return d.showAndWait().orElse(ButtonType.CANCEL) == ok ? pf.getText() : null;
    }

    // ------------------------------------------------------------ files

    public static void saveText(String name, String text) {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(name);
        File f = fc.showSaveDialog(owner);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), text);
            toast("Saved " + f.getName(), Kind.OK);
        } catch (Exception e) {
            toast(message(e), Kind.ERROR);
        }
    }

    // ------------------------------------------------------------- widgets

    public static Button button(String text, String icon, EventHandler<ActionEvent> h) {
        Button b = new Button(text);
        b.setMinWidth(Region.USE_PREF_SIZE);
        if (icon != null) b.setGraphic(Icons.of(icon, 15));
        if (h != null) b.setOnAction(h);
        return b;
    }

    public static Button primary(String text, String icon, EventHandler<ActionEvent> h) {
        Button b = button(text, icon, h);
        b.getStyleClass().add("btn-primary");
        return b;
    }

    public static Button danger(String text, String icon, EventHandler<ActionEvent> h) {
        Button b = button(text, icon, h);
        b.getStyleClass().add("btn-danger");
        return b;
    }

    public static Button iconButton(String icon, String tooltip, EventHandler<ActionEvent> h) {
        Button b = new Button();
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setGraphic(Icons.of(icon, 16));
        b.getStyleClass().addAll("btn-ghost", "btn-icon");
        b.setTooltip(new Tooltip(tooltip));
        if (h != null) b.setOnAction(h);
        return b;
    }

    /** Prevents controls from being squeezed into "…" when space is tight. */
    public static void noShrink(Region... rs) {
        for (Region r : rs) r.setMinWidth(Region.USE_PREF_SIZE);
    }

    public static Region spacer() {
        Region r = new Region();
        r.getStyleClass().add(ToolBarPane.SPACER);
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Label label(String text, String... classes) {
        Label l = new Label(text);
        l.getStyleClass().addAll(classes);
        return l;
    }

    public static Label badge(String text, String kind) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.getStyleClass().add("badge");
        if (kind != null) l.getStyleClass().add("badge-" + kind);
        return l;
    }

    public static Node dot(String color) {
        Region r = new Region();
        r.getStyleClass().addAll("dot", "dot-" + color);
        return r;
    }

    public static VBox card(String title, Node... children) {
        VBox v = new VBox();
        v.getStyleClass().add("card");
        if (title != null) v.getChildren().add(label(title, "card-title"));
        v.getChildren().addAll(children);
        return v;
    }

    /** Toolbar row: items before a spacer() go left, after it right; wraps when narrow. */
    public static ToolBarPane toolbar(Node... nodes) {
        for (Node n : nodes) {
            if (n instanceof javafx.scene.control.TextField tf && tf.getPrefColumnCount() == 12) tf.setPrefColumnCount(18);
            if (n instanceof Region r && !(n instanceof javafx.scene.control.TextField) && !r.getStyleClass().contains(ToolBarPane.SPACER)) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        return new ToolBarPane(nodes);
    }

    public static HBox banner(String kind, String icon, String text, Node... actions) {
        Label l = new Label(text);
        l.setWrapText(true);
        HBox h = new HBox(10, Icons.of(icon, 18, kind.equals("red") ? "icon-red" : kind.equals("info") ? "icon-accent" : "icon-amber"), l, spacer());
        h.getChildren().addAll(actions);
        h.getStyleClass().add("banner");
        if (!kind.equals("amber")) h.getStyleClass().add("banner-" + kind);
        HBox.setHgrow(l, Priority.SOMETIMES);
        return h;
    }

    public static VBox empty(String icon, String title, String text, Node... actions) {
        VBox v = new VBox(Icons.of(icon, 44, "icon-muted"), label(title, "h2"));
        if (text != null) {
            Label t = label(text, "muted");
            t.setWrapText(true);
            t.setMaxWidth(480);
            t.setAlignment(Pos.CENTER);
            v.getChildren().add(t);
        }
        if (actions.length > 0) {
            HBox h = new HBox(8, actions);
            h.setAlignment(Pos.CENTER);
            v.getChildren().add(h);
        }
        v.getStyleClass().add("empty-state");
        return v;
    }

    public static StackPane center(Node n) {
        return new StackPane(n);
    }

    // -------------------------------------------------------------- tables

    public static <T> TableColumn<T, String> col(String title, Function<T, String> f, double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        if (width > 0) c.setPrefWidth(width);
        return c;
    }

    /** Column rendering a colored badge; kind derived from the value. */
    public static <T> TableColumn<T, String> badgeCol(String title, Function<T, String> f, Function<String, String> kind, double width) {
        TableColumn<T, String> c = col(title, f, width);
        c.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(null);
                setGraphic(empty || v == null || v.isEmpty() ? null : badge(v, kind.apply(v)));
            }
        });
        return c;
    }

    /** Column using a monospace font. */
    public static <T> TableColumn<T, String> monoCol(String title, Function<T, String> f, double width) {
        TableColumn<T, String> c = col(title, f, width);
        c.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : v);
                if (!getStyleClass().contains("mono")) getStyleClass().add("mono");
            }
        });
        return c;
    }

    // ---------------------------------------------------------------- form

    /** Two-column label/control grid used by all dialogs. */
    public static final class Form extends GridPane {
        private int row;

        public Form() {
            setHgap(14);
            setVgap(10);
            setPadding(new Insets(4, 0, 4, 0));
            ColumnConstraints a = new ColumnConstraints();
            a.setMinWidth(130);
            ColumnConstraints b = new ColumnConstraints();
            b.setHgrow(Priority.ALWAYS);
            b.setMinWidth(320);
            getColumnConstraints().addAll(a, b);
        }

        public Form row(String label, Node control) {
            return row(label, control, null);
        }

        public Form row(String label, Node control, String hint) {
            Label l = new Label(label);
            l.getStyleClass().add("form-label");
            add(l, 0, row);
            if (hint != null) {
                Label h = new Label(hint);
                h.getStyleClass().add("form-hint");
                h.setWrapText(true);
                VBox v = new VBox(4, control, h);
                add(v, 1, row);
            } else {
                add(control, 1, row);
            }
            if (control instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
            row++;
            return this;
        }

        public Form full(Node n) {
            add(n, 0, row, 2, 1);
            row++;
            return this;
        }

        public Form section(String title) {
            Label l = new Label(title);
            l.getStyleClass().add("h3");
            l.setPadding(new Insets(8, 0, 0, 0));
            return full(l);
        }
    }
}
