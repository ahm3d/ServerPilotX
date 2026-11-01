package io.serverpilotx.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.core.CmdException;
import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Json;
import io.serverpilotx.core.Quote;
import io.serverpilotx.ui.Icons;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** UFW rules with presets and lockout protection, Fail2ban jails, raw nftables ruleset. */
public final class FirewallView extends View {
    private static final Pattern RULE = Pattern.compile(
            "^\\[\\s*(\\d+)]\\s+(.+?)\\s{2,}(ALLOW|DENY|REJECT|LIMIT)\\s*(IN|OUT|FWD)?\\s+(.+?)(?:\\s+#\\s*(.*))?$");
    private static final String BACKUP = "/var/lib/serverpilotx/fw-backup";
    private static final String DOCKER_BEGIN = "# BEGIN UFW AND DOCKER";

    private final TableView<Map<String, Object>> rules = new TableView<>();
    private final TableView<Map<String, Object>> jails = new TableView<>();
    private final TextArea nftText = new TextArea();
    private final Label status = Ui.badge("…", null);
    private final Label defaults = Ui.label("", "muted");
    private final StackPane rulesHolder = new StackPane();
    private final StackPane jailsHolder = new StackPane();
    private Button toggleBtn;
    private CheckBox dockerAware;
    private boolean active;
    private TabPane tabs;

    @Override public String id() { return "firewall"; }
    @Override public String title() { return "Firewall"; }
    @Override public String subtitle() { return "UFW / nftables & Fail2ban"; }
    @Override public String icon() { return "shield"; }

    private static String s(Map<String, Object> m, String k) { return Json.str(m, k); }

    @Override
    protected Node build() {
        tabs = new TabPane(tab("Rules", rulesTab()), tab("Fail2ban", jailsTab()), tab("nftables", nftTab()));
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> refresh());
        VBox page = new VBox(tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        page.getStyleClass().add("page");
        return page;
    }

    private static Tab tab(String n, Node c) {
        Tab t = new Tab(n, c);
        t.setClosable(false);
        return t;
    }

    // ============================================================ rules

    private Node rulesTab() {
        rules.getColumns().add(Ui.col("#", m -> s(m, "num"), 50));
        rules.getColumns().add(Ui.monoCol("To (port)", m -> s(m, "to"), 200));
        rules.getColumns().add(Ui.badgeCol("Action", m -> s(m, "action"), v -> v.startsWith("ALLOW") ? "green" : v.startsWith("LIMIT") ? "amber" : "red", 110));
        rules.getColumns().add(Ui.col("Direction", m -> s(m, "dir"), 90));
        rules.getColumns().add(Ui.monoCol("From", m -> s(m, "from"), 200));
        rules.getColumns().add(Ui.col("Comment", m -> s(m, "comment"), 220));
        rules.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        rules.setPlaceholder(Ui.label("No rules", "muted"));

        toggleBtn = Ui.button("Enable firewall", "power", e -> toggleFirewall());
        MenuButton presets = new MenuButton("Presets", Icons.of("layers", 15));
        presets.getItems().addAll(
                preset("SSH (rate limited)", "limit", "22", "tcp", "ssh"),
                preset("HTTP + HTTPS", "allow", "80,443", "tcp", "web"),
                preset("HTTP/3 (QUIC)", "allow", "443", "udp", "http3"),
                preset("WireGuard", "allow", "51820", "udp", "wireguard"),
                preset("Mail (SMTP/Submission/IMAPS)", "allow", "25,465,587,993", "tcp", "mail"),
                preset("DNS", "allow", "53", "any", "dns"));
        dockerAware = new CheckBox("Docker respects UFW");
        dockerAware.setOnAction(e -> setDockerAware(dockerAware.isSelected()));
        io.serverpilotx.ui.ToolBarPane head = Ui.toolbar(Ui.label("Status", "muted"), status, defaults, Ui.spacer(), dockerAware, toggleBtn);
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.primary("Add rule", "plus", e -> addRule(null)), presets, Ui.spacer(),
                Ui.button("Show raw", "eye", e -> {
                    Host h = host();
                    Ui.bg(() -> h.sudo("ufw", "status", "verbose").text(), out -> Ui.textDialog("ufw status verbose", out));
                }),
                Ui.danger("Delete rule", "trash", e -> deleteRule()));
        Label note = Ui.label("Lockout protection: on remote servers every change is automatically reverted after 5 minutes unless you confirm it.", "faint", "small");
        note.setWrapText(true);
        VBox v = new VBox(12, head, bar, rules, note);
        VBox.setVgrow(rules, Priority.ALWAYS);
        rulesHolder.getChildren().add(v);
        return rulesHolder;
    }

    private MenuItem preset(String label, String action, String port, String proto, String comment) {
        MenuItem mi = new MenuItem(label);
        mi.setOnAction(e -> {
            List<String> a = new ArrayList<>(List.of("ufw", action));
            if (!proto.equals("any")) a.addAll(List.of("proto", proto));
            a.addAll(List.of("from", "any", "to", "any", "port", port, "comment", comment));
            applyRule("Add firewall rule", label, a);
        });
        return mi;
    }

    private void loadRules() {
        Host h = host();
        if (rulesHolder.getChildren().size() > 1) rulesHolder.getChildren().remove(1, rulesHolder.getChildren().size());
        if (!h.has("ufw")) {
            Button install = Ui.primary("Install UFW", "download", e -> Ui.stream("Install ufw", h, h.installArgv("ufw"), true, c -> {
                Ui.runAsync(h::detect);
                refresh();
            }));
            VBox empty = Ui.empty("shield", "UFW is not installed", "ServerPilotX manages firewall rules with UFW (on top of nftables). "
                    + "The nftables tab shows the raw ruleset.", install);
            empty.setStyle("-fx-background-color: -spx-bg;");
            rulesHolder.getChildren().add(empty);
            return;
        }
        Ui.bg(() -> {
            CmdResult st = h.sudo("ufw", "status", "numbered");
            CmdResult verbose = h.sudo("ufw", "status", "verbose");
            CmdResult after = h.sudo("grep", "-c", DOCKER_BEGIN, "/etc/ufw/after.rules");
            return new CmdResult[]{st, verbose, after};
        }, r -> {
            String out = r[0].orThrow();
            active = out.contains("Status: active");
            status.setText(active ? "Active" : "Inactive");
            status.getStyleClass().setAll("label", "badge", active ? "badge-green" : "badge-red");
            toggleBtn.setText(active ? "Disable firewall" : "Enable firewall");
            Matcher dm = Pattern.compile("Default: (.*)").matcher(r[1].out());
            defaults.setText(dm.find() ? "  ·  Default: " + dm.group(1) : "");
            dockerAware.setSelected(r[2].ok() && !r[2].out().strip().equals("0"));
            List<Map<String, Object>> list = new ArrayList<>();
            for (String line : out.split("\\R")) {
                Matcher m = RULE.matcher(line.strip());
                if (!m.matches()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("num", m.group(1));
                row.put("to", m.group(2).strip());
                row.put("action", m.group(3));
                row.put("dir", m.group(4) == null ? "IN" : m.group(4));
                row.put("from", m.group(5).strip());
                row.put("comment", m.group(6) == null ? "" : m.group(6).strip());
                list.add(row);
            }
            rules.setItems(FXCollections.observableArrayList(list));
        });
    }

    private void addRule(Map<String, Object> unused) {
        ComboBox<String> action = new ComboBox<>(FXCollections.observableArrayList("allow", "deny", "reject", "limit"));
        action.setValue("allow");
        ComboBox<String> dir = new ComboBox<>(FXCollections.observableArrayList("in", "out"));
        dir.setValue("in");
        TextField port = new TextField();
        port.setPromptText("22   or   80,443   or   6000:6100");
        ComboBox<String> proto = new ComboBox<>(FXCollections.observableArrayList("tcp", "udp", "any"));
        proto.setValue("tcp");
        TextField from = new TextField("any");
        from.setPromptText("any, 203.0.113.4 or 10.0.0.0/8");
        TextField comment = new TextField();
        Spinner<Integer> position = new Spinner<>(0, 999, 0);
        CheckBox route = new CheckBox("Route rule (for Docker containers / forwarded traffic)");
        Ui.Form f = new Ui.Form()
                .row("Action", action)
                .row("Direction", dir)
                .row("Port / range", port, "Empty = all ports. Ranges need tcp or udp.")
                .row("Protocol", proto)
                .row("Source", from)
                .row("Comment", comment)
                .row("Insert at", position, "0 = append to the end")
                .full(route);
        if (!Ui.dialog("Add firewall rule", "Add rule", f, "Preview", () -> {
            if (!port.getText().isBlank() && !port.getText().strip().matches("[0-9,:]+")) return "Invalid port";
            if (port.getText().contains(":") && proto.getValue().equals("any")) return "Port ranges need tcp or udp";
            if (!from.getText().strip().matches("any|[0-9a-fA-F:.]+(/\\d+)?")) return "Invalid source address";
            return null;
        })) return;
        List<String> a = new ArrayList<>(List.of("ufw"));
        if (route.isSelected()) a.add("route");
        if (position.getValue() > 0) { a.add("insert"); a.add(String.valueOf(position.getValue())); }
        a.add(action.getValue());
        if (!route.isSelected()) a.add(dir.getValue());
        if (!proto.getValue().equals("any")) { a.add("proto"); a.add(proto.getValue()); }
        a.add("from");
        a.add(from.getText().strip());
        a.add("to");
        a.add("any");
        if (!port.getText().isBlank()) { a.add("port"); a.add(port.getText().strip()); }
        if (!comment.getText().isBlank()) { a.add("comment"); a.add(comment.getText().strip()); }
        // dry run preview
        Host h = host();
        List<String> dry = new ArrayList<>(a);
        dry.add(1, "--dry-run");
        Ui.bg(() -> h.sudo(dry, Host.DEFAULT_TIMEOUT), r -> {
            TextArea ta = new TextArea(r.text());
            ta.setEditable(false);
            ta.getStyleClass().add("console");
            ta.setPrefSize(720, 340);
            Label cmd = Ui.label("$ sudo " + String.join(" ", a), "mono", "muted");
            cmd.setWrapText(true);
            if (!r.ok()) { Ui.errorDialog("Rule rejected by ufw", r.text()); return; }
            if (Ui.dialog("Review change", "Dry run — changes ufw would make", new VBox(8, cmd, ta), "Apply rule", null)) {
                applyRule("Add firewall rule", String.join(" ", a.subList(1, a.size())), a);
            }
        });
    }

    private void deleteRule() {
        Map<String, Object> m = rules.getSelectionModel().getSelectedItem();
        if (m == null) { Ui.toast("Select a rule first", Ui.Kind.INFO); return; }
        String desc = s(m, "action") + " " + s(m, "to") + " from " + s(m, "from");
        if (!Ui.confirm("Delete rule", "Delete rule #" + s(m, "num") + " (" + desc + ")?", "Delete", true)) return;
        applyRule("Delete firewall rule", desc, List.of("ufw", "--force", "delete", s(m, "num")));
    }

    private void applyRule(String label, String target, List<String> argv) {
        Host h = host();
        safeApply(h, label, target, () -> h.sudo(argv, Host.DEFAULT_TIMEOUT).orThrow());
    }

    private void toggleFirewall() {
        Host h = host();
        if (active) {
            if (!Ui.confirm("Disable firewall", "Turn off UFW on " + h.name() + "? All ports become reachable.", "Disable", true)) return;
            Ui.action("Disable firewall", h.name(), () -> h.sudo("ufw", "disable").orThrow(), this::loadRules);
            return;
        }
        Ui.bg(() -> sshPort(h), port -> {
            if (!Ui.confirm("Enable firewall", "UFW will block incoming connections that are not explicitly allowed.\n\n"
                    + "To keep you connected, SSH (port " + port + "/tcp) is allowed first.", "Enable", false)) return;
            safeApply(h, "Enable firewall", h.name(), () -> {
                String rulesNow = h.sudo("ufw", "show", "added").out();
                if (!rulesNow.matches("(?s).*\\b" + port + "(/tcp)?\\b.*")) {
                    h.sudo("ufw", "limit", port + "/tcp", "comment", "ssh (lockout protection)").orThrow();
                }
                return h.sudo("ufw", "--force", "enable").orThrow();
            });
        });
    }

    static String sshPort(Host h) {
        String p = h.sudoSh("sshd -T 2>/dev/null | awk '/^port /{print $2; exit}'").out().strip();
        return p.matches("\\d+") ? p : "22";
    }

    /**
     * Runs a firewall change. On remote hosts a revert timer is armed first and a fresh SSH connection
     * is tested afterwards; the user must confirm within 5 minutes or the old rules come back.
     */
    private void safeApply(Host h, String label, String target, Callable<String> change) {
        if (h.isLocal()) {
            Ui.action(label, target, change, this::loadRules);
            return;
        }
        String revert = "cp -a " + BACKUP + "/user.rules " + BACKUP + "/user6.rules /etc/ufw/ ; "
                + "if grep -q inactive " + BACKUP + "/state; then ufw --force disable; else ufw reload; fi";
        Ui.bg(() -> {
            h.sudoSh("systemctl stop spx-fw-revert.timer spx-fw-revert.service 2>/dev/null; systemctl reset-failed spx-fw-revert.service 2>/dev/null;"
                    + " mkdir -p " + BACKUP + " && cp -a /etc/ufw/user.rules /etc/ufw/user6.rules " + BACKUP + "/ &&"
                    + " (ufw status | grep -q 'Status: active' && echo active || echo inactive) > " + BACKUP + "/state").orThrow();
            h.sudo("systemd-run", "--on-active=300", "--unit=spx-fw-revert", "/bin/sh", "-c", revert).orThrow();
            String out = change.call();
            boolean reachable = h.probeFreshConnection();
            return new Object[]{out, reachable};
        }, res -> {
            boolean reachable = (Boolean) res[1];
            if (!reachable) {
                Ui.bg(() -> h.sudoSh(revert + "; systemctl stop spx-fw-revert.timer 2>/dev/null; true"), r -> {
                    AuditLog.log(label, target, "reverted: new SSH connections failed", false);
                    Ui.errorDialog("Change reverted", "A new SSH connection to " + h.name() + " failed after the change, so the previous rules were restored.");
                    loadRules();
                });
                return;
            }
            loadRules();
            boolean keep = Ui.confirm("Keep firewall changes?", "The change was applied and a new SSH connection still works.\n\n"
                    + "Keep it? If you do nothing, it is reverted automatically in 5 minutes.", "Keep changes", false);
            if (keep) {
                Ui.bg(() -> h.sudoSh("systemctl stop spx-fw-revert.timer 2>/dev/null; true"), r -> {
                    AuditLog.log(label, target, "confirmed", true);
                    Ui.toast(label + " · " + target, Ui.Kind.OK);
                });
            } else {
                Ui.bg(() -> h.sudoSh(revert + "; systemctl stop spx-fw-revert.timer 2>/dev/null; true"), r -> {
                    AuditLog.log(label, target, "reverted by user", false);
                    Ui.toast("Change reverted", Ui.Kind.INFO);
                    loadRules();
                });
            }
        }, err -> {
            AuditLog.log(label, target, err, false);
            Ui.errorDialog(label + " failed", err + "\n\nThe previous rules are being restored.");
            Ui.bg(() -> h.sudoSh(revert + "; systemctl stop spx-fw-revert.timer 2>/dev/null; true"), r -> loadRules());
        });
    }

    private static final String DOCKER_RULES = String.join("\n",
            DOCKER_BEGIN,
            "*filter",
            ":ufw-user-forward - [0:0]",
            ":ufw-docker-logging-deny - [0:0]",
            ":DOCKER-USER - [0:0]",
            "-A DOCKER-USER -j ufw-user-forward",
            "-A DOCKER-USER -j RETURN -s 10.0.0.0/8",
            "-A DOCKER-USER -j RETURN -s 172.16.0.0/12",
            "-A DOCKER-USER -j RETURN -s 192.168.0.0/16",
            "-A DOCKER-USER -p udp -m udp --sport 53 --dport 1024:65535 -j RETURN",
            "-A DOCKER-USER -j ufw-docker-logging-deny -p tcp -m tcp --tcp-flags FIN,SYN,RST,ACK SYN -d 192.168.0.0/16",
            "-A DOCKER-USER -j ufw-docker-logging-deny -p tcp -m tcp --tcp-flags FIN,SYN,RST,ACK SYN -d 10.0.0.0/8",
            "-A DOCKER-USER -j ufw-docker-logging-deny -p tcp -m tcp --tcp-flags FIN,SYN,RST,ACK SYN -d 172.16.0.0/12",
            "-A DOCKER-USER -j ufw-docker-logging-deny -p udp -m udp --dport 0:32767 -d 192.168.0.0/16",
            "-A DOCKER-USER -j ufw-docker-logging-deny -p udp -m udp --dport 0:32767 -d 10.0.0.0/8",
            "-A DOCKER-USER -j ufw-docker-logging-deny -p udp -m udp --dport 0:32767 -d 172.16.0.0/12",
            "-A DOCKER-USER -j RETURN",
            "-A ufw-docker-logging-deny -m limit --limit 3/min --limit-burst 10 -j LOG --log-prefix \"[UFW DOCKER BLOCK] \"",
            "-A ufw-docker-logging-deny -j DROP",
            "COMMIT",
            "# END UFW AND DOCKER", "");

    private void setDockerAware(boolean on) {
        Host h = host();
        String msg = on ? "Published Docker ports normally bypass UFW. This adds DOCKER-USER rules to /etc/ufw/after.rules so containers "
                + "are only reachable from outside when you add a matching *route* rule (Add rule → Route rule)."
                : "Remove the DOCKER-USER rules from /etc/ufw/after.rules? Published container ports will bypass UFW again.";
        if (!Ui.confirm(on ? "Make Docker respect UFW" : "Stop filtering Docker ports", msg, on ? "Enable" : "Remove", !on)) {
            dockerAware.setSelected(!on);
            return;
        }
        safeApply(h, on ? "Docker-aware firewall on" : "Docker-aware firewall off", h.name(), () -> {
            String cur = h.sudo("cat", "/etc/ufw/after.rules").orThrow();
            String stripped = cur.replaceAll("(?s)" + Pattern.quote(DOCKER_BEGIN) + ".*?# END UFW AND DOCKER\\R?", "");
            h.writeFileRoot("/etc/ufw/after.rules", on ? stripped.stripTrailing() + "\n\n" + DOCKER_RULES : stripped, "640");
            return h.sudo("ufw", "reload").orThrow();
        });
    }

    // ============================================================ fail2ban

    private Node jailsTab() {
        jails.getColumns().add(Ui.col("Jail", m -> s(m, "name"), 180));
        jails.getColumns().add(Ui.badgeCol("Banned now", m -> s(m, "current"), v -> v.equals("0") ? "green" : "red", 110));
        jails.getColumns().add(Ui.col("Total banned", m -> s(m, "total"), 110));
        jails.getColumns().add(Ui.col("Failures", m -> s(m, "failed"), 90));
        jails.getColumns().add(Ui.monoCol("Banned IPs", m -> s(m, "ips"), 400));
        jails.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        jails.setPlaceholder(Ui.label("No jails enabled", "muted"));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.primary("Configure jails", "settings", e -> configureJails()), Ui.spacer(),
                Ui.button("Ban IP…", "x", e -> banUnban(true)),
                Ui.button("Unban IP…", "check", e -> banUnban(false)));
        VBox v = new VBox(12, bar, jails);
        VBox.setVgrow(jails, Priority.ALWAYS);
        jailsHolder.getChildren().add(v);
        return jailsHolder;
    }

    private void loadJails() {
        Host h = host();
        if (jailsHolder.getChildren().size() > 1) jailsHolder.getChildren().remove(1, jailsHolder.getChildren().size());
        if (!h.has("fail2ban-client")) {
            Button install = Ui.primary("Install Fail2ban", "download", e -> Ui.stream("Install fail2ban", h, h.installArgv("fail2ban"), true, c -> {
                Ui.bg(() -> { h.detect(); h.sudo("systemctl", "enable", "--now", "fail2ban"); return null; }, x -> refresh());
            }));
            VBox empty = Ui.empty("shield", "Fail2ban is not installed", "Fail2ban bans IPs that repeatedly fail to log in (SSH, Nginx auth, bots).", install);
            empty.setStyle("-fx-background-color: -spx-bg;");
            jailsHolder.getChildren().add(empty);
            return;
        }
        Ui.bg(() -> {
            String script = "for j in $(fail2ban-client status | sed -n 's/.*Jail list:\\s*//p' | tr ',' ' '); do echo \"@@$j\"; fail2ban-client status $j; done";
            return h.sudoSh(script);
        }, r -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (String b : r.out().split("@@")) {
                if (b.isBlank()) continue;
                String[] lines = b.split("\\R");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", lines[0].strip());
                for (String l : lines) {
                    String x = l.replaceAll("^[|`\\s-]+", "");
                    if (x.startsWith("Currently banned:")) m.put("current", x.substring(17).strip());
                    if (x.startsWith("Total banned:")) m.put("total", x.substring(13).strip());
                    if (x.startsWith("Total failed:")) m.put("failed", x.substring(13).strip());
                    if (x.startsWith("Banned IP list:")) m.put("ips", x.substring(15).strip());
                }
                list.add(m);
            }
            jails.setItems(FXCollections.observableArrayList(list));
            if (!r.ok()) Ui.toast("fail2ban: " + r.text(), Ui.Kind.ERROR);
        });
    }

    private void banUnban(boolean ban) {
        Map<String, Object> j = jails.getSelectionModel().getSelectedItem();
        if (j == null) { Ui.toast("Select a jail first", Ui.Kind.INFO); return; }
        ComboBox<String> ip = new ComboBox<>();
        ip.setEditable(true);
        if (!ban) for (String x : s(j, "ips").split("\\s+")) if (!x.isBlank()) ip.getItems().add(x);
        if (!ip.getItems().isEmpty()) ip.setValue(ip.getItems().get(0));
        if (!Ui.dialog((ban ? "Ban" : "Unban") + " IP in " + s(j, "name"), null, new Ui.Form().row("IP address", ip), ban ? "Ban" : "Unban",
                () -> ip.getEditor().getText().strip().matches("[0-9a-fA-F:.]+(/\\d+)?") ? null : "Invalid IP")) return;
        String addr = ip.getEditor().getText().strip();
        Host h = host();
        Ui.action(ban ? "Ban IP" : "Unban IP", addr + " (" + s(j, "name") + ")",
                () -> h.sudo("fail2ban-client", "set", s(j, "name"), ban ? "banip" : "unbanip", addr).orThrow(), this::loadJails);
    }

    private static final String JAIL_FILE = "/etc/fail2ban/jail.d/serverpilotx.conf";

    private void configureJails() {
        Host h = host();
        Ui.bg(() -> h.exists(JAIL_FILE) ? h.readFile(JAIL_FILE) : "", cur -> {
            CheckBox sshd = new CheckBox("sshd — SSH brute force");
            CheckBox auth = new CheckBox("nginx-http-auth — basic auth failures");
            CheckBox limit = new CheckBox("nginx-limit-req — rate limit violations");
            CheckBox bots = new CheckBox("nginx-botsearch — vulnerability scanners");
            sshd.setSelected(cur.isEmpty() || cur.contains("[sshd]\nenabled = true"));
            auth.setSelected(cur.contains("[nginx-http-auth]\nenabled = true"));
            limit.setSelected(cur.contains("[nginx-limit-req]\nenabled = true"));
            bots.setSelected(cur.contains("[nginx-botsearch]\nenabled = true"));
            TextField bantime = new TextField(val(cur, "bantime", "1h"));
            TextField findtime = new TextField(val(cur, "findtime", "10m"));
            TextField maxretry = new TextField(val(cur, "maxretry", "5"));
            TextField ignore = new TextField(val(cur, "ignoreip", "127.0.0.1/8 ::1"));
            Ui.Form f = new Ui.Form().section("Jails").full(sshd).full(auth).full(limit).full(bots).section("Policy")
                    .row("Ban time", bantime).row("Find time", findtime).row("Max retries", maxretry)
                    .row("Whitelist (ignoreip)", ignore, "Space-separated IPs/CIDRs that are never banned — add your own IP");
            if (!Ui.dialog("Fail2ban jails", "Configure Fail2ban", f, "Save & restart", null)) return;
            StringBuilder b = new StringBuilder("# Managed by ServerPilotX\n[DEFAULT]\n");
            b.append("bantime = ").append(bantime.getText().strip()).append('\n');
            b.append("findtime = ").append(findtime.getText().strip()).append('\n');
            b.append("maxretry = ").append(maxretry.getText().strip()).append('\n');
            b.append("ignoreip = ").append(ignore.getText().strip()).append('\n');
            b.append("backend = auto\n\n");
            jail(b, "sshd", sshd.isSelected());
            jail(b, "nginx-http-auth", auth.isSelected());
            jail(b, "nginx-limit-req", limit.isSelected());
            jail(b, "nginx-botsearch", bots.isSelected());
            Ui.action("Configure fail2ban", JAIL_FILE, () -> {
                String old = cur;
                h.writeFileRoot(JAIL_FILE, b.toString(), "644");
                CmdResult t = h.sudo("fail2ban-client", "-t");
                if (!t.ok()) {
                    if (old.isEmpty()) h.sudo("rm", "-f", JAIL_FILE); else h.writeFileRoot(JAIL_FILE, old, "644");
                    throw new CmdException("fail2ban config test failed — reverted.\n\n" + t.text());
                }
                return h.sudo("systemctl", "restart", "fail2ban").orThrow();
            }, this::loadJails);
        });
    }

    private static void jail(StringBuilder b, String name, boolean on) {
        b.append('[').append(name).append("]\nenabled = ").append(on).append('\n');
        if (on && name.equals("sshd")) b.append("mode = aggressive\n");
        b.append('\n');
    }

    private static String val(String ini, String key, String def) {
        Matcher m = Pattern.compile("(?m)^" + key + "\\s*=\\s*(.*)$").matcher(ini);
        return m.find() ? m.group(1).strip() : def;
    }

    // ============================================================ nftables

    private Node nftTab() {
        nftText.setEditable(false);
        nftText.getStyleClass().add("console");
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.label("Live ruleset (nft list ruleset) — read-only view of everything UFW, Docker and others installed.", "muted"),
                Ui.spacer(),
                Ui.button("Edit /etc/nftables.conf", "edit", e -> {
                    Host h = host();
                    Ui.bg(() -> h.exists("/etc/nftables.conf") ? h.readFile("/etc/nftables.conf") : "#!/usr/sbin/nft -f\nflush ruleset\n",
                            cur -> Ui.editor("/etc/nftables.conf", "Validated with `nft -c -f` before saving. Applied on next nftables service restart.", cur, text -> {
                                String tmp = h.run("mktemp", "/tmp/spx-nft.XXXXXXXX").orThrow().strip();
                                try {
                                    h.writeFile(tmp, text);
                                    CmdResult c = h.sudo("nft", "-c", "-f", tmp);
                                    if (!c.ok()) throw new CmdException("nft check failed:\n" + c.text());
                                } finally {
                                    h.run("rm", "-f", tmp);
                                }
                                h.writeFileRoot("/etc/nftables.conf", text, "755");
                                AuditLog.log("Edit nftables.conf", h.name(), "", true);
                                return "Saved /etc/nftables.conf";
                            }));
                }));
        VBox v = new VBox(12, bar, nftText);
        VBox.setVgrow(nftText, Priority.ALWAYS);
        return v;
    }

    private void loadNft() {
        Host h = host();
        Ui.bg(() -> h.has("nft") ? h.sudo("nft", "list", "ruleset").text() : "nftables (nft) is not installed on this server.",
                txt -> nftText.setText(txt.isBlank() ? "(empty ruleset)" : txt));
    }

    @Override
    public void refresh() {
        if (!built()) return;
        switch (tabs.getSelectionModel().getSelectedIndex()) {
            case 0 -> loadRules();
            case 1 -> loadJails();
            default -> loadNft();
        }
    }

    static String q(String s) { return Quote.sh(s); }

    static HBox row(Node... n) {
        HBox h = new HBox(8, n);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}
