package io.serverpilotx.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.core.CmdException;
import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.CronText;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Json;
import io.serverpilotx.core.Metrics;
import io.serverpilotx.ui.Icons;
import io.serverpilotx.ui.LogViewer;
import io.serverpilotx.ui.Ui;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** systemd services, cron jobs, processes and package updates. */
public final class ServicesView extends View {
    private TabPane tabs;

    private final ObservableList<Map<String, Object>> units = FXCollections.observableArrayList();
    private final FilteredList<Map<String, Object>> unitsFiltered = new FilteredList<>(units);
    private final TableView<Map<String, Object>> unitTable = new TableView<>(unitsFiltered);

    private final TableView<Map<String, Object>> cronTable = new TableView<>();
    private final ComboBox<String> cronUser = new ComboBox<>();
    private List<String> cronLines = new ArrayList<>();

    private final ObservableList<Map<String, Object>> procs = FXCollections.observableArrayList();
    private final FilteredList<Map<String, Object>> procsFiltered = new FilteredList<>(procs);
    private final TableView<Map<String, Object>> procTable = new TableView<>(procsFiltered);
    private Timeline procTimer;

    private final TableView<Map<String, Object>> updTable = new TableView<>();
    private final VBox updBanners = new VBox(8);
    private final Label updSummary = Ui.label("", "muted");
    private final CheckBox unattended = new CheckBox("Automatic security updates");

    @Override public String id() { return "services"; }
    @Override public String title() { return "Services"; }
    @Override public String subtitle() { return "systemd, cron, processes & updates"; }
    @Override public String icon() { return "services"; }

    private static String s(Map<String, Object> m, String k) { return Json.str(m, k); }

    @Override
    protected Node build() {
        tabs = new TabPane(tab("systemd", unitsTab()), tab("Cron", cronTab()), tab("Processes", procTab()), tab("Updates", updatesTab()));
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> refresh());
        procTimer = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
            if (visible && tabs.getSelectionModel().getSelectedIndex() == 2) loadProcs();
        }));
        procTimer.setCycleCount(Timeline.INDEFINITE);
        procTimer.play();
        VBox page = new VBox(tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        page.getStyleClass().add("page");
        return page;
    }

    public void showTab(String name) {
        node();
        for (Tab t : tabs.getTabs()) if (t.getText().equalsIgnoreCase(name)) tabs.getSelectionModel().select(t);
    }

    private static Tab tab(String n, Node c) {
        Tab t = new Tab(n, c);
        t.setClosable(false);
        return t;
    }

    private static MenuItem mi(String t, String icon, Runnable r) {
        MenuItem m = new MenuItem(t, Icons.of(icon, 14));
        m.setOnAction(e -> r.run());
        return m;
    }

    private void sel(TableView<Map<String, Object>> t, java.util.function.Consumer<Map<String, Object>> c) {
        Map<String, Object> m = t.getSelectionModel().getSelectedItem();
        if (m == null) Ui.toast("Select a row first", Ui.Kind.INFO);
        else c.accept(m);
    }

    // ============================================================ systemd

    private Node unitsTab() {
        unitTable.getColumns().add(Ui.col("Unit", m -> s(m, "unit"), 230));
        unitTable.getColumns().add(Ui.badgeCol("State", m -> s(m, "sub"), v -> switch (v) {
            case "running" -> "green"; case "failed" -> "red"; case "exited" -> "blue"; default -> null; }, 100));
        unitTable.getColumns().add(Ui.badgeCol("Boot", m -> s(m, "enabled"), v -> v.equals("enabled") ? "blue" : null, 100));
        unitTable.getColumns().add(Ui.col("Description", m -> s(m, "desc"), 380));
        unitTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        unitTable.setRowFactory(tv -> {
            TableRow<Map<String, Object>> r = new TableRow<>();
            r.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !r.isEmpty()) unitLogs(r.getItem()); });
            return r;
        });
        TextField search = new TextField();
        search.setPromptText("Filter services…");
        CheckBox running = new CheckBox("Running only");
        CheckBox failed = new CheckBox("Failed only");
        Runnable filter = () -> unitsFiltered.setPredicate(m -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase();
            if (running.isSelected() && !s(m, "sub").equals("running")) return false;
            if (failed.isSelected() && !s(m, "active").equals("failed")) return false;
            return q.isEmpty() || (s(m, "unit") + " " + s(m, "desc")).toLowerCase().contains(q);
        });
        search.textProperty().addListener((o, a, b) -> filter.run());
        running.setOnAction(e -> filter.run());
        failed.setOnAction(e -> filter.run());
        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                mi("Reload", "refresh", () -> sel(unitTable, m -> unitAction(m, "reload"))),
                mi("Enable at boot", "check", () -> sel(unitTable, m -> unitAction(m, "enable"))),
                mi("Disable at boot", "x", () -> sel(unitTable, m -> unitAction(m, "disable"))),
                mi("Status", "info", () -> sel(unitTable, m -> {
                    Host h = host();
                    Ui.bg(() -> h.run("systemctl", "status", "--no-pager", "-l", s(m, "unit")).text(), out -> Ui.textDialog("systemctl status " + s(m, "unit"), out));
                })),
                mi("Reset failed units", "refresh", () -> {
                    Host h = host();
                    Ui.action("Reset failed units", h.name(), () -> h.sudo("systemctl", "reset-failed").orThrow(), this::loadUnits);
                }));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(search, running, failed, Ui.spacer(),
                Ui.button("Start", "play", e -> sel(unitTable, m -> unitAction(m, "start"))),
                Ui.button("Stop", "stop", e -> sel(unitTable, m -> unitAction(m, "stop"))),
                Ui.button("Restart", "restart", e -> sel(unitTable, m -> unitAction(m, "restart"))),
                Ui.button("Logs", "logs", e -> sel(unitTable, this::unitLogs)),
                more);
        HBox.setHgrow(search, Priority.SOMETIMES);
        VBox v = new VBox(12, bar, unitTable);
        VBox.setVgrow(unitTable, Priority.ALWAYS);
        return v;
    }

    private void loadUnits() {
        Host h = host();
        Ui.bg(() -> {
            CmdResult a = h.run("systemctl", "list-units", "--type=service", "--all", "--no-legend", "--plain", "--no-pager");
            CmdResult b = h.run("systemctl", "list-unit-files", "--type=service", "--no-legend", "--plain", "--no-pager");
            Map<String, String> en = new HashMap<>();
            for (String l : b.lines()) {
                String[] f = l.strip().split("\\s+");
                if (f.length >= 2) en.put(f[0], f[1]);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String l : a.lines()) {
                String[] f = l.strip().replaceFirst("^●\\s*", "").split("\\s+", 5);
                if (f.length < 4 || !f[0].endsWith(".service")) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("unit", f[0]);
                m.put("load", f[1]);
                m.put("active", f[2]);
                m.put("sub", f[3]);
                m.put("desc", f.length > 4 ? f[4] : "");
                m.put("enabled", en.getOrDefault(f[0], ""));
                rows.add(m);
            }
            return rows;
        }, units::setAll);
    }

    private void unitAction(Map<String, Object> m, String verb) {
        String unit = s(m, "unit");
        if ((verb.equals("stop") || verb.equals("disable")) && (unit.startsWith("ssh") || unit.startsWith("sshd"))
                && !Ui.confirm(verb + " " + unit, "Stopping or disabling SSH can lock you out of a remote server. Continue?", "Continue", true)) return;
        Host h = host();
        Ui.action("systemctl " + verb, unit, () -> h.sudo("systemctl", verb, unit).orThrow(), this::loadUnits);
    }

    private void unitLogs(Map<String, Object> m) {
        Host h = host();
        String unit = s(m, "unit");
        LogViewer.open("Journal · " + unit, h, !h.isRoot(), (n, f) -> {
            List<String> a = new ArrayList<>(List.of("journalctl", "-u", unit, "--no-pager", "-o", "short-iso"));
            a.add("-n");
            a.add(n < 0 ? "all" : String.valueOf(n));
            if (f) a.add("-f");
            return a;
        });
    }

    // ============================================================ cron

    private Node cronTab() {
        cronTable.getColumns().add(Ui.monoCol("Schedule", m -> s(m, "schedule"), 140));
        cronTable.getColumns().add(Ui.col("When", m -> s(m, "human"), 230));
        cronTable.getColumns().add(Ui.monoCol("Command", m -> s(m, "command"), 380));
        cronTable.getColumns().add(Ui.badgeCol("Status", m -> s(m, "enabled").equals("yes") ? "Enabled" : "Disabled", v -> v.equals("Enabled") ? "green" : null, 95));
        cronTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        cronTable.setPlaceholder(Ui.label("No cron jobs for this user", "muted"));
        cronUser.setOnAction(e -> loadCron());
        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                mi("Run now", "play", () -> sel(cronTable, this::runCronNow)),
                mi("Run history", "clock", this::cronHistory),
                mi("Edit raw crontab", "edit", this::editRawCron));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.label("User", "muted"), cronUser, Ui.primary("Add job", "plus", e -> editCron(null)), Ui.spacer(),
                Ui.button("Edit", "edit", e -> sel(cronTable, this::editCron)),
                Ui.button("Enable / disable", "power", e -> sel(cronTable, this::toggleCron)),
                more,
                Ui.danger("Delete", "trash", e -> sel(cronTable, this::deleteCron)));
        VBox v = new VBox(12, bar, cronTable);
        VBox.setVgrow(cronTable, Priority.ALWAYS);
        return v;
    }

    private String readCrontab(Host h, String user) {
        CmdResult r = user.equals(h.user()) ? h.run("crontab", "-l") : h.sudo("crontab", "-u", user, "-l");
        if (!r.ok() && !r.err().contains("no crontab")) throw new CmdException(r.text());
        return r.ok() ? r.out() : "";
    }

    private void writeCrontab(Host h, String user, String content) {
        String tmp = h.run("mktemp", "/tmp/spx-cron.XXXXXXXX").orThrow().strip();
        try {
            h.writeFile(tmp, content.endsWith("\n") ? content : content + "\n");
            if (user.equals(h.user())) h.run("crontab", tmp).orThrow();
            else h.sudo("crontab", "-u", user, tmp).orThrow();
        } finally {
            h.run("rm", "-f", tmp);
        }
    }

    private void loadCron() {
        Host h = host();
        if (cronUser.getItems().isEmpty()) {
            Ui.bg(() -> h.sh("getent passwd | awk -F: '$3>=1000 && $3<60000 {print $1}'").lines(), l -> {
                List<String> us = new ArrayList<>(List.of(h.user()));
                if (!us.contains("root")) us.add("root");
                for (String u : l) if (!us.contains(u)) us.add(u);
                cronUser.getItems().setAll(us);
                cronUser.setValue(h.user());
            });
            return;
        }
        String user = cronUser.getValue();
        if (user == null) return;
        Ui.bg(() -> readCrontab(h, user), content -> {
            cronLines = new ArrayList<>(List.of(content.split("\\R", -1)));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < cronLines.size(); i++) {
                String l = cronLines.get(i).strip();
                boolean disabled = l.startsWith("#SPX-OFF ");
                if (disabled) l = l.substring(9).strip();
                if (l.isEmpty() || l.startsWith("#") || l.matches("[A-Za-z_][A-Za-z0-9_]*\\s*=.*")) continue;
                String sched, cmd;
                if (l.startsWith("@")) {
                    int sp = l.indexOf(' ');
                    if (sp < 0) continue;
                    sched = l.substring(0, sp);
                    cmd = l.substring(sp + 1).strip();
                } else {
                    String[] f = l.split("\\s+", 6);
                    if (f.length < 6) continue;
                    sched = String.join(" ", f[0], f[1], f[2], f[3], f[4]);
                    cmd = f[5];
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("line", i);
                m.put("schedule", sched);
                m.put("human", CronText.describe(sched));
                m.put("command", cmd);
                m.put("enabled", disabled ? "no" : "yes");
                rows.add(m);
            }
            cronTable.setItems(FXCollections.observableArrayList(rows));
        });
    }

    private void saveCron(String action, String target, List<String> lines) {
        Host h = host();
        String user = cronUser.getValue();
        Ui.action(action, user + " · " + target, () -> {
            writeCrontab(h, user, String.join("\n", lines).stripTrailing() + "\n");
            return target;
        }, this::loadCron);
    }

    private void editCron(Map<String, Object> m) {
        ComboBox<String> preset = new ComboBox<>(FXCollections.observableArrayList("Custom", "Every 5 minutes|*/5 * * * *", "Every hour|0 * * * *",
                "Every day at 03:00|0 3 * * *", "Every Sunday at 04:00|0 4 * * 0", "First of month|0 0 1 * *", "At boot|@reboot"));
        preset.setValue("Custom");
        TextField sched = new TextField(m == null ? "0 3 * * *" : s(m, "schedule"));
        Label human = Ui.label(CronText.describe(sched.getText()), "text-accent");
        sched.textProperty().addListener((o, a, b) -> human.setText(CronText.valid(b) ? CronText.describe(b) : "Invalid schedule"));
        preset.setOnAction(e -> { String v = preset.getValue(); if (v.contains("|")) sched.setText(v.substring(v.indexOf('|') + 1)); });
        TextField cmd = new TextField(m == null ? "" : s(m, "command"));
        cmd.setPromptText("/usr/local/bin/backup.sh >> /var/log/backup.log 2>&1");
        Ui.Form f = new Ui.Form().row("Preset", preset).row("Schedule", sched, "minute hour day-of-month month day-of-week").row("", human).row("Command", cmd);
        if (!Ui.dialog(m == null ? "Add cron job" : "Edit cron job", "Cron job for " + cronUser.getValue(), f, "Save", () -> {
            if (!CronText.valid(sched.getText())) return "Invalid schedule";
            if (cmd.getText().isBlank()) return "Command required";
            return null;
        })) return;
        List<String> lines = new ArrayList<>(cronLines);
        String entry = sched.getText().strip() + " " + cmd.getText().strip();
        if (m == null) {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
            lines.add(entry);
        } else {
            int i = ((Number) m.get("line")).intValue();
            lines.set(i, s(m, "enabled").equals("yes") ? entry : "#SPX-OFF " + entry);
        }
        saveCron(m == null ? "Add cron job" : "Edit cron job", entry, lines);
    }

    private void toggleCron(Map<String, Object> m) {
        List<String> lines = new ArrayList<>(cronLines);
        int i = ((Number) m.get("line")).intValue();
        String l = lines.get(i);
        boolean on = s(m, "enabled").equals("yes");
        lines.set(i, on ? "#SPX-OFF " + l : l.replaceFirst("^\\s*#SPX-OFF\\s+", ""));
        saveCron(on ? "Disable cron job" : "Enable cron job", s(m, "command"), lines);
    }

    private void deleteCron(Map<String, Object> m) {
        if (!Ui.confirm("Delete cron job", "Delete '" + s(m, "schedule") + " " + s(m, "command") + "'?", "Delete", true)) return;
        List<String> lines = new ArrayList<>(cronLines);
        lines.remove(((Number) m.get("line")).intValue());
        saveCron("Delete cron job", s(m, "command"), lines);
    }

    private void runCronNow(Map<String, Object> m) {
        Host h = host();
        String user = cronUser.getValue();
        String cmd = s(m, "command");
        if (!Ui.confirm("Run now", "Run as " + user + ":\n\n" + cmd, "Run", false)) return;
        List<String> argv = user.equals(h.user()) ? List.of("sh", "-c", cmd) : List.of("runuser", "-u", user, "--", "sh", "-c", cmd);
        Ui.stream("Run cron job · " + user, h, argv, !user.equals(h.user()), null);
    }

    private void cronHistory() {
        Host h = host();
        String user = cronUser.getValue();
        Ui.bg(() -> h.sudoSh("(journalctl -q --no-pager -o short-iso -t CRON -t cron -t crond --since '-7 days' 2>/dev/null;"
                + " grep -h CRON /var/log/syslog /var/log/cron 2>/dev/null) | grep -E '\\(" + user.replace("'", "") + "\\)' | tail -n 500").text(),
                out -> Ui.textDialog("Cron history · " + user, out.isBlank() ? "No cron runs recorded in the last 7 days." : out));
    }

    private void editRawCron() {
        Host h = host();
        String user = cronUser.getValue();
        Ui.bg(() -> readCrontab(h, user), cur -> Ui.editor("crontab · " + user, "Standard crontab format. Lines starting with #SPX-OFF are disabled jobs.", cur, text -> {
            writeCrontab(h, user, text);
            AuditLog.log("Edit crontab", user, "", true);
            Ui.fx(this::loadCron);
            return "Saved crontab";
        }));
    }

    // ============================================================ processes

    private Node procTab() {
        procTable.getColumns().add(Ui.col("PID", m -> s(m, "pid"), 70));
        procTable.getColumns().add(Ui.col("User", m -> s(m, "user"), 100));
        procTable.getColumns().add(Ui.col("CPU %", m -> s(m, "cpu"), 70));
        procTable.getColumns().add(Ui.col("Mem %", m -> s(m, "mem"), 70));
        procTable.getColumns().add(Ui.col("RSS", m -> s(m, "rss"), 90));
        procTable.getColumns().add(Ui.col("Nice", m -> s(m, "ni"), 55));
        procTable.getColumns().add(Ui.col("Uptime", m -> s(m, "etime"), 90));
        procTable.getColumns().add(Ui.monoCol("Command", m -> s(m, "args"), 420));
        procTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TextField search = new TextField();
        search.setPromptText("Filter processes…");
        search.textProperty().addListener((o, a, q) -> procsFiltered.setPredicate(m -> q == null || q.isBlank()
                || (s(m, "args") + " " + s(m, "user") + " " + s(m, "pid")).toLowerCase().contains(q.toLowerCase())));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(search, Ui.label("Top 300 by CPU · refreshes every 3 s", "faint", "small"), Ui.spacer(),
                Ui.button("Renice…", "settings", e -> sel(procTable, this::renice)),
                Ui.button("Terminate", "stop", e -> sel(procTable, m -> kill(m, "TERM"))),
                Ui.danger("Kill -9", "x", e -> sel(procTable, m -> kill(m, "KILL"))));
        HBox.setHgrow(search, Priority.SOMETIMES);
        VBox v = new VBox(12, bar, procTable);
        VBox.setVgrow(procTable, Priority.ALWAYS);
        return v;
    }

    private boolean loadingProcs;

    private void loadProcs() {
        if (loadingProcs) return;
        loadingProcs = true;
        Host h = host();
        Ui.bg(() -> h.sh("ps -eo pid=,user:20=,pcpu=,pmem=,rss=,ni=,etimes=,args= --sort=-pcpu | head -n 300"), r -> {
            loadingProcs = false;
            Object selPid = procTable.getSelectionModel().getSelectedItem() == null ? null : procTable.getSelectionModel().getSelectedItem().get("pid");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String l : r.lines()) {
                String[] f = l.strip().split("\\s+", 8);
                if (f.length < 8) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pid", f[0]);
                m.put("user", f[1]);
                m.put("cpu", f[2]);
                m.put("mem", f[3]);
                long kb = 0;
                try { kb = Long.parseLong(f[4]); } catch (NumberFormatException ignored) { }
                m.put("rss", Metrics.bytes(kb * 1024.0));
                m.put("ni", f[5]);
                long secs = 0;
                try { secs = Long.parseLong(f[6]); } catch (NumberFormatException ignored) { }
                m.put("etime", Metrics.uptime(secs));
                m.put("args", f[7]);
                rows.add(m);
            }
            procs.setAll(rows);
            if (selPid != null) for (Map<String, Object> m : procTable.getItems()) if (selPid.equals(m.get("pid"))) procTable.getSelectionModel().select(m);
        }, err -> loadingProcs = false);
    }

    private void kill(Map<String, Object> m, String sig) {
        if (!Ui.confirm("Send SIG" + sig, "Send SIG" + sig + " to PID " + s(m, "pid") + "?\n\n" + s(m, "args"), sig.equals("KILL") ? "Kill" : "Terminate", true)) return;
        Host h = host();
        boolean own = s(m, "user").equals(h.user());
        Ui.action("Kill process", s(m, "pid") + " (" + sig + ")", () -> own
                ? h.run("kill", "-" + sig, s(m, "pid")).orThrow()
                : h.sudo("kill", "-" + sig, s(m, "pid")).orThrow(), this::loadProcs);
    }

    private void renice(Map<String, Object> m) {
        int cur = 0;
        try { cur = Integer.parseInt(s(m, "ni")); } catch (NumberFormatException ignored) { }
        Spinner<Integer> ni = new Spinner<>(-20, 19, cur);
        if (!Ui.dialog("Renice PID " + s(m, "pid"), null, new Ui.Form().row("Nice value", ni, "-20 = highest priority, 19 = lowest"), "Apply", null)) return;
        Host h = host();
        Ui.action("Renice process", s(m, "pid") + " → " + ni.getValue(), () -> h.sudo("renice", "-n", String.valueOf(ni.getValue()), "-p", s(m, "pid")).orThrow(), this::loadProcs);
    }

    // ============================================================ updates

    private Node updatesTab() {
        updTable.getColumns().add(Ui.col("Package", m -> s(m, "name"), 260));
        updTable.getColumns().add(Ui.col("Current", m -> s(m, "from"), 200));
        updTable.getColumns().add(Ui.col("Available", m -> s(m, "to"), 200));
        updTable.getColumns().add(Ui.badgeCol("Type", m -> s(m, "security").equals("yes") ? "Security" : "", v -> "red", 100));
        updTable.getColumns().add(Ui.col("Source", m -> s(m, "repo"), 200));
        updTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        updTable.setPlaceholder(Ui.label("Everything is up to date", "muted"));
        unattended.setOnAction(e -> setUnattended(unattended.isSelected()));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(updSummary, Ui.spacer(), unattended,
                Ui.button("Refresh lists", "refresh", e -> refreshLists()),
                Ui.button("Install security updates", "shield", e -> applyUpdates(true)),
                Ui.primary("Install all updates", "download", e -> applyUpdates(false)));
        VBox v = new VBox(12, bar, updBanners, updTable);
        VBox.setVgrow(updTable, Priority.ALWAYS);
        return v;
    }

    private void loadUpdates() {
        Host h = host();
        String pm = h.pkgManager();
        updSummary.setText("Package manager: " + (pm.isEmpty() ? "unknown" : pm));
        Ui.bg(() -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean reboot;
            boolean auto;
            switch (pm) {
                case "apt" -> {
                    for (String l : h.sh("apt list --upgradable 2>/dev/null | tail -n +2").lines()) {
                        // name/suite version arch [upgradable from: old]
                        String[] f = l.split("\\s+");
                        if (f.length < 2 || !f[0].contains("/")) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", f[0].substring(0, f[0].indexOf('/')));
                        m.put("repo", f[0].substring(f[0].indexOf('/') + 1));
                        m.put("to", f[1]);
                        int i = l.indexOf("from: ");
                        m.put("from", i > 0 ? l.substring(i + 6).replace("]", "") : "");
                        m.put("security", s(m, "repo").contains("security") ? "yes" : "no");
                        rows.add(m);
                    }
                    reboot = h.run("test", "-f", "/var/run/reboot-required").ok();
                    auto = h.sh("grep -qs 'Unattended-Upgrade \"1\"' /etc/apt/apt.conf.d/20auto-upgrades").ok();
                }
                case "dnf", "yum" -> {
                    String sec = h.sh(pm + " -q updateinfo list --security 2>/dev/null | awk '{print $3}'").out();
                    for (String l : h.sh(pm + " -q -C check-update 2>/dev/null; true").lines()) {
                        String[] f = l.strip().split("\\s+");
                        if (f.length != 3 || !f[0].contains(".")) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", f[0]);
                        m.put("to", f[1]);
                        m.put("from", "");
                        m.put("repo", f[2]);
                        m.put("security", sec.contains(f[0].substring(0, f[0].lastIndexOf('.'))) ? "yes" : "no");
                        rows.add(m);
                    }
                    reboot = h.sh("command -v needs-restarting >/dev/null && ! needs-restarting -r >/dev/null 2>&1").ok();
                    auto = h.sh("systemctl is-enabled -q dnf-automatic-install.timer 2>/dev/null || systemctl is-enabled -q dnf-automatic.timer 2>/dev/null").ok();
                }
                case "pacman" -> {
                    for (String l : h.sh("pacman -Qu 2>/dev/null").lines()) {
                        String[] f = l.strip().split("\\s+");
                        if (f.length < 4) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", f[0]);
                        m.put("from", f[1]);
                        m.put("to", f[3]);
                        m.put("repo", "");
                        m.put("security", "no");
                        rows.add(m);
                    }
                    reboot = false;
                    auto = false;
                }
                default -> { reboot = false; auto = false; }
            }
            return new Object[]{rows, reboot, auto};
        }, res -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) res[0];
            updTable.setItems(FXCollections.observableArrayList(rows));
            long sec = rows.stream().filter(m -> s(m, "security").equals("yes")).count();
            updSummary.setText(rows.size() + " updates" + (sec > 0 ? " · " + sec + " security" : "") + "  ·  " + pm);
            updBanners.getChildren().clear();
            if ((Boolean) res[1]) updBanners.getChildren().add(Ui.banner("amber", "power", "A reboot is required to finish installing updates.",
                    Ui.danger("Reboot now", "power", e -> DashboardView.reboot())));
            unattended.setSelected((Boolean) res[2]);
            unattended.setDisable(pm.equals("pacman") || pm.isEmpty());
        });
    }

    private void refreshLists() {
        Host h = host();
        List<String> a = switch (h.pkgManager()) {
            case "apt" -> List.of("apt-get", "update");
            case "dnf", "yum" -> List.of(h.pkgManager(), "makecache");
            case "pacman" -> List.of("pacman", "-Sy");
            default -> null;
        };
        if (a == null) { Ui.toast("No supported package manager", Ui.Kind.ERROR); return; }
        Ui.stream("Refresh package lists", h, a, true, c -> loadUpdates());
    }

    private void applyUpdates(boolean securityOnly) {
        Host h = host();
        String pm = h.pkgManager();
        List<String> a;
        switch (pm) {
            case "apt" -> {
                if (securityOnly) {
                    if (h.has("unattended-upgrade")) a = List.of("unattended-upgrade", "-v");
                    else {
                        List<String> pk = updTable.getItems().stream().filter(m -> s(m, "security").equals("yes")).map(m -> s(m, "name")).toList();
                        if (pk.isEmpty()) { Ui.toast("No security updates pending", Ui.Kind.INFO); return; }
                        a = new ArrayList<>(List.of("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "--only-upgrade"));
                        a.addAll(pk);
                    }
                } else {
                    a = List.of("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "-y", "-o", "Dpkg::Options::=--force-confold", "upgrade");
                }
            }
            case "dnf", "yum" -> a = securityOnly ? List.of(pm, "-y", "upgrade", "--security") : List.of(pm, "-y", "upgrade");
            case "pacman" -> {
                if (securityOnly) { Ui.toast("pacman has no security-only mode", Ui.Kind.INFO); return; }
                a = List.of("pacman", "-Syu", "--noconfirm");
            }
            default -> { Ui.toast("No supported package manager", Ui.Kind.ERROR); return; }
        }
        if (!Ui.confirm(securityOnly ? "Install security updates" : "Install all updates",
                "Run on " + h.name() + ":\n\n" + String.join(" ", a), "Install", false)) return;
        List<String> argv = a;
        Ui.stream(securityOnly ? "Security updates" : "System upgrade", h, argv, true, c -> loadUpdates());
    }

    private void setUnattended(boolean on) {
        Host h = host();
        Ui.action(on ? "Enable automatic updates" : "Disable automatic updates", h.name(), () -> {
            if (h.pkgManager().equals("apt")) {
                if (on && !h.has("unattended-upgrade")) h.sudo(h.installArgv("unattended-upgrades"), java.time.Duration.ofMinutes(10)).orThrow();
                h.writeFileRoot("/etc/apt/apt.conf.d/20auto-upgrades", "APT::Periodic::Update-Package-Lists \"" + (on ? 1 : 0)
                        + "\";\nAPT::Periodic::Unattended-Upgrade \"" + (on ? 1 : 0) + "\";\n", "644");
                return "20auto-upgrades";
            }
            if (on) {
                h.sudo(h.installArgv("dnf-automatic"), java.time.Duration.ofMinutes(10));
                return h.sudoSh("systemctl enable --now dnf-automatic-install.timer 2>/dev/null || systemctl enable --now dnf-automatic.timer").orThrow();
            }
            return h.sudoSh("systemctl disable --now dnf-automatic-install.timer dnf-automatic.timer 2>/dev/null; true").orThrow();
        }, this::loadUpdates);
    }

    // ============================================================ common

    @Override
    public void refresh() {
        if (!built()) return;
        switch (tabs.getSelectionModel().getSelectedIndex()) {
            case 0 -> loadUnits();
            case 1 -> loadCron();
            case 2 -> loadProcs();
            default -> loadUpdates();
        }
    }

    @Override
    public void onHostChanged() {
        cronUser.getItems().clear();
        super.onHostChanged();
    }
}
