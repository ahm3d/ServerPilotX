package io.serverpilotx.view;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Settings;
import io.serverpilotx.ui.Terminals;
import io.serverpilotx.ui.Ui;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Built-in console: a persistent shell on the active server (cd, variables, history work).
 * Full-screen TTY programs (vim, htop) open in the desktop terminal instead.
 */
public final class TerminalView extends View {
    private static final String MARK = "__SPX_DONE__";

    private final TextArea out = new TextArea();
    private final TextField in = new TextField();
    private final Label prompt = Ui.label("$", "console-prompt");
    private final Label state = Ui.label("", "faint", "small");
    private final List<String> history = new ArrayList<>();
    private int histPos;
    private Process proc;
    private OutputStream stdin;
    private Host sessionHost;
    private String cwd = "~";
    private String remotePid;
    private boolean busy;

    @Override public String id() { return "terminal"; }
    @Override public String title() { return "Terminal"; }
    @Override public String subtitle() { return "Shell on the active server"; }
    @Override public String icon() { return "terminal"; }

    @Override
    protected Node build() {
        out.setEditable(false);
        out.setWrapText(true);
        out.getStyleClass().add("console");
        in.getStyleClass().add("console-input");
        in.setPromptText("Type a command and press Enter  (↑/↓ history, Ctrl+C interrupt, Ctrl+L clear)");
        in.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> { send(in.getText()); e.consume(); }
                case UP -> { if (!history.isEmpty()) { histPos = Math.max(0, histPos - 1); in.setText(history.get(histPos)); in.end(); } e.consume(); }
                case DOWN -> { if (histPos < history.size() - 1) { histPos++; in.setText(history.get(histPos)); } else { histPos = history.size(); in.clear(); } in.end(); e.consume(); }
                case C -> { if (e.isControlDown() && in.getSelectedText().isEmpty()) { interrupt(); e.consume(); } }
                case L -> { if (e.isControlDown()) { out.clear(); e.consume(); } }
                default -> { }
            }
        });
        HBox inputRow = new HBox(8, prompt, in);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.setStyle("-fx-background-color: -spx-code-bg; -fx-background-radius: 8; -fx-padding: 4 10 4 10;");
        HBox.setHgrow(in, Priority.ALWAYS);

        FlowPane quick = new FlowPane(6, 6);
        for (String c : List.of("df -h", "free -h", "uptime", "docker ps", "systemctl --failed", "ss -tulpn", "journalctl -p err -n 50 --no-pager", "ip -br a")) {
            Button b = new Button(c);
            b.getStyleClass().addAll("btn-ghost", "mono");
            b.setOnAction(e -> send(c));
            quick.getChildren().add(b);
        }
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(state, Ui.spacer(),
                Ui.button("Interrupt", "stop", e -> interrupt()),
                Ui.button("Clear", "x", e -> out.clear()),
                Ui.button("Restart session", "restart", e -> restart()),
                Ui.primary("Open in system terminal", "terminal", e -> Terminals.open(host(), "ServerPilotX · " + host().name(), null)));
        Label note = Ui.label("Interactive full-screen programs (vim, htop, sudo password prompts) need a real TTY — use “Open in system terminal”. "
                + (Settings.recordTerminal() ? "Commands are recorded in the audit log." : ""), "faint", "small");
        note.setWrapText(true);
        VBox page = new VBox(10, bar, out, inputRow, quick, note);
        VBox.setVgrow(out, Priority.ALWAYS);
        page.getStyleClass().add("page");
        return page;
    }

    @Override
    public void onShow() {
        visible = true;
        if (proc == null || !proc.isAlive() || sessionHost != host()) restart();
        Platform.runLater(in::requestFocus);
    }

    @Override
    public void onHostChanged() {
        if (proc != null) restart();
    }

    @Override
    public void refresh() {
        Platform.runLater(in::requestFocus);
    }

    private void restart() {
        stop();
        Host h = host();
        sessionHost = h;
        cwd = "~";
        remotePid = null;
        out.appendText((out.getText().isEmpty() ? "" : "\n") + "── Session on " + h.label() + " ──\n");
        state.setText("Connecting…");
        Ui.runAsync(() -> {
            try {
                Process p = h.shell();
                proc = p;
                stdin = p.getOutputStream();
                write("export TERM=dumb PAGER=cat SYSTEMD_PAGER=cat GIT_PAGER=cat; cd ~ 2>/dev/null; echo " + MARK + "PID $$; printf '\\n" + MARK + " %s %s\\n' 0 \"$PWD\"\n");
                Thread reader = new Thread(() -> read(p), "spx-term");
                reader.setDaemon(true);
                reader.start();
            } catch (Exception e) {
                Ui.fx(() -> { out.appendText("Failed to start shell: " + Ui.message(e) + "\n"); state.setText("Disconnected"); });
            }
        });
    }

    private void stop() {
        if (proc != null) {
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
            proc = null;
        }
        busy = false;
    }

    private void read(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder chunk = new StringBuilder();
            long last = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(MARK + "PID ")) { remotePid = line.substring(MARK.length() + 4).strip(); continue; }
                int mi = line.indexOf(MARK + " ");
                if (mi >= 0) {
                    String before = line.substring(0, mi);
                    if (!before.isEmpty()) chunk.append(Ui.stripAnsi(before)).append('\n');
                    String[] f = line.substring(mi + MARK.length() + 1).split(" ", 2);
                    String code = f[0];
                    String dir = f.length > 1 ? f[1] : cwd;
                    if (chunk.length() > 0 && chunk.charAt(chunk.length() - 1) == '\n') chunk.setLength(chunk.length() - 1);
                    String c = chunk.toString();
                    chunk.setLength(0);
                    Ui.fx(() -> {
                        if (!c.isEmpty()) out.appendText(c);
                        if (!out.getText().isEmpty() && !out.getText().endsWith("\n")) out.appendText("\n");
                        if (!code.equals("0")) out.appendText("[exit " + code + "]\n");
                        cwd = dir;
                        busy = false;
                        updatePrompt();
                    });
                    continue;
                }
                chunk.append(Ui.stripAnsi(line)).append('\n');
                long now = System.currentTimeMillis();
                if (now - last > 100) {
                    String c = chunk.toString();
                    chunk.setLength(0);
                    last = now;
                    Ui.fx(() -> out.appendText(c));
                }
            }
            String c = chunk.toString();
            Ui.fx(() -> out.appendText(c));
        } catch (Exception ignored) {
            // stream closed
        }
        if (p == proc) Ui.fx(() -> { out.appendText("── session ended ──\n"); state.setText("Disconnected — press Restart session"); busy = false; });
    }

    private void updatePrompt() {
        Host h = sessionHost;
        String home = h.isRoot() ? "/root" : h.isLocal() ? System.getProperty("user.home") : "/home/" + h.user();
        String shown = cwd.equals(home) ? "~" : cwd.startsWith(home + "/") ? "~" + cwd.substring(home.length()) : cwd;
        prompt.setText(h.user() + "@" + (h.isLocal() ? "local" : h.name()) + ":" + shown + (h.isRoot() ? "#" : "$"));
        state.setText("Connected · " + h.label());
    }

    private void write(String s) {
        try {
            stdin.write(s.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception e) {
            Ui.fx(() -> out.appendText("(write failed: " + e.getMessage() + ")\n"));
        }
    }

    private void send(String cmd) {
        String c = cmd == null ? "" : cmd.strip();
        in.clear();
        if (proc == null || !proc.isAlive()) { restart(); return; }
        if (c.isEmpty()) return;
        if (c.equals("clear")) { out.clear(); return; }
        if (c.equals("exit")) { stop(); out.appendText("── session closed ──\n"); state.setText("Disconnected"); return; }
        if (busy) { out.appendText("(still running — press Interrupt or wait)\n"); return; }
        if (c.matches("^(vim?|nano|htop|top|less|more|man|mc|tmux|screen|ssh|su)(\\s.*)?$")) {
            out.appendText(prompt.getText() + " " + c + "\n" + c.split("\\s")[0] + " needs a full terminal — opening the system terminal…\n");
            Terminals.open(sessionHost, c, c);
            return;
        }
        history.remove(c);
        history.add(c);
        histPos = history.size();
        out.appendText(prompt.getText() + " " + c + "\n");
        busy = true;
        state.setText("Running…");
        if (Settings.recordTerminal()) AuditLog.log("Terminal command", sessionHost.name(), c, true);
        write("{ " + c + "\n} </dev/null\nprintf '\\n" + MARK + " %s %s\\n' \"$?\" \"$PWD\"\n");
    }

    private void interrupt() {
        if (!busy) return;
        Host h = sessionHost;
        String pid = remotePid;
        if (pid == null) return;
        Ui.runAsync(() -> h.run("pkill", "-INT", "-P", pid));
        out.appendText("^C\n");
    }
}
