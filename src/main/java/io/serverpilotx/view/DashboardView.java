package io.serverpilotx.view;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import io.serverpilotx.core.Metrics;
import io.serverpilotx.ui.CommandPalette;
import io.serverpilotx.ui.Gauge;
import io.serverpilotx.ui.Icons;
import io.serverpilotx.ui.MainWindow;
import io.serverpilotx.ui.ResponsiveGrid;
import io.serverpilotx.ui.Ui;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import javafx.util.StringConverter;

/** Server overview: live gauges, history chart, health checks, services and quick actions. */
public final class DashboardView extends View {
    private final Gauge cpuGauge = new Gauge(96, Color.web("#22c55e"));
    private final Gauge memGauge = new Gauge(96, Color.web("#2f8cff"));
    private final Gauge diskGauge = new Gauge(96, Color.web("#f5a524"));
    private final Label cpuSub = Ui.label("", "card-sub");
    private final Label memSub = Ui.label("", "card-sub");
    private final Label diskSub = Ui.label("", "card-sub");
    private final Label netUp = Ui.label("–", "h2");
    private final Label netDown = Ui.label("–", "h2");
    private final Label diskIo = Ui.label("", "card-sub");

    private final Map<String, Label> info = new LinkedHashMap<>();
    private final VBox healthList = new VBox(2);
    private final VBox servicesList = new VBox(2);

    private AreaChart<Number, Number> chart;
    private NumberAxis xAxis;
    private NumberAxis yAxis;
    private long rangeSeconds = 3600;
    private String chartMode = "cpu";
    private Timeline slowTimer;
    private long lastChart;

    @Override public String id() { return "dashboard"; }
    @Override public String title() { return "Dashboard"; }
    @Override public String subtitle() { return "Overview"; }
    @Override public String icon() { return "dashboard"; }

    @Override
    protected Node build() {
        // ---- gauges row
        VBox cpuCard = gaugeCard("CPU", "cpu", cpuGauge, cpuSub);
        VBox memCard = gaugeCard("Memory", "layers", memGauge, memSub);
        VBox diskCard = gaugeCard("Disk  /", "database", diskGauge, diskSub);

        HBox up = new HBox(8, Icons.of("arrow-up", 18, "icon-green"), netUp);
        HBox down = new HBox(8, Icons.of("arrow-down", 18, "icon-red"), netDown);
        up.setAlignment(Pos.CENTER_LEFT);
        down.setAlignment(Pos.CENTER_LEFT);
        VBox netCard = Ui.card(null, header("Network", "network"), up, down, diskIo);
        netCard.setMinWidth(220);

        ResponsiveGrid gauges = new ResponsiveGrid(230, cpuCard, memCard, diskCard, netCard);

        // ---- chart
        xAxis = new NumberAxis();
        xAxis.setAutoRanging(false);
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number n) {
                LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault());
                return t.format(DateTimeFormatter.ofPattern(rangeSeconds > 86400 ? "MMM d" : "HH:mm"));
            }
            @Override public Number fromString(String s) { return 0; }
        });
        yAxis = new NumberAxis(0, 100, 25);
        chart = new AreaChart<>(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.setPrefHeight(280);
        chart.setMinHeight(220);

        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton modeCpu = toggle("CPU & Memory", modeGroup, () -> { chartMode = "cpu"; redrawChart(); });
        ToggleButton modeNet = toggle("Network", modeGroup, () -> { chartMode = "net"; redrawChart(); });
        modeCpu.setSelected(true);
        ToggleGroup rangeGroup = new ToggleGroup();
        HBox ranges = new HBox(4);
        for (Object[] r : new Object[][]{{"1h", 3600L}, {"24h", 86400L}, {"7d", 604800L}, {"30d", 2592000L}}) {
            ToggleButton b = toggle((String) r[0], rangeGroup, () -> { rangeSeconds = (Long) r[1]; redrawChart(); });
            if ((Long) r[1] == 3600L) b.setSelected(true);
            ranges.getChildren().add(b);
        }
        HBox modes = new HBox(4, modeCpu, modeNet);
        FlowPane chartHead = new FlowPane(10, 8, Ui.label("Usage history", "card-title"), modes, ranges);
        chartHead.setAlignment(Pos.CENTER_LEFT);
        VBox chartCard = Ui.card(null, chartHead, chart);
        VBox.setVgrow(chart, Priority.ALWAYS);
        HBox.setHgrow(chartCard, Priority.ALWAYS);

        // ---- server info
        VBox infoCard = Ui.card(null, header("Server", "server"));
        for (String k : List.of("Hostname", "OS", "Kernel", "Architecture", "CPU", "Uptime", "Load average", "Swap", "Temperature")) {
            Label v = Ui.label("–");
            v.setWrapText(true);
            info.put(k, v);
            Label kl = Ui.label(k, "muted");
            kl.setMinWidth(96);
            HBox row = new HBox(10, kl, v);
            row.getStyleClass().add("stat-row");
            infoCard.getChildren().add(row);
        }
        infoCard.setPrefWidth(-1);
        infoCard.setMinWidth(0);
        ResponsiveGrid row2 = new ResponsiveGrid(330, 16, new double[]{2.2, 1}, chartCard, infoCard);

        // ---- health, services, quick actions
        VBox healthCard = Ui.card(null, header("Health checks", "activity"), healthList);
        VBox servicesCard = Ui.card(null, header("Services", "services"), servicesList);
        FlowPane qa = new FlowPane(10, 10);
        qa.getChildren().addAll(
                quick("Terminal", "terminal", "qa-green", () -> MainWindow.get().navigate("terminal")),
                quick("File Manager", "folder", "qa-blue", () -> MainWindow.get().navigate("files")),
                quick("Docker", "docker", "qa-purple", () -> MainWindow.get().navigate("docker")),
                quick("Web Server", "globe", "qa-amber", () -> MainWindow.get().navigate("sites")));
        VBox actionsCol = new VBox(8,
                Ui.button("Restart Nginx", "restart", e -> restartService("nginx")),
                Ui.button("Restart Docker", "restart", e -> restartService("docker")),
                Ui.button("Check for updates", "package", e -> openUpdates()),
                Ui.danger("Reboot server", "power", e -> reboot()));
        for (Node n : actionsCol.getChildren()) ((Button) n).setMaxWidth(Double.MAX_VALUE);
        VBox quickCard = Ui.card(null, header("Quick actions", "rocket"), qa, actionsCol);

        ResponsiveGrid row3 = new ResponsiveGrid(290, healthCard, servicesCard, quickCard);

        VBox page = new VBox(16, gauges, row2, row3);
        page.getStyleClass().add("page");
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);

        Metrics.get().onSample(s -> Ui.fx(() -> onSample(s)));
        slowTimer = new Timeline(new KeyFrame(Duration.seconds(60), e -> { if (visible) loadHealth(); }));
        slowTimer.setCycleCount(Timeline.INDEFINITE);
        slowTimer.play();
        Metrics.Sample last = Metrics.get().last();
        if (last != null) onSample(last);
        return sp;
    }

    private static HBox header(String title, String icon) {
        HBox h = new HBox(8, Icons.of(icon, 16, "icon-accent"), Ui.label(title, "card-title"));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static VBox gaugeCard(String title, String icon, Gauge g, Label sub) {
        HBox body = new HBox(16, g, sub);
        body.setAlignment(Pos.CENTER_LEFT);
        sub.setWrapText(true);
        VBox c = Ui.card(null, header(title, icon), body);
        c.setMinWidth(200);
        return c;
    }

    private static ToggleButton toggle(String text, ToggleGroup g, Runnable r) {
        ToggleButton b = new ToggleButton(text);
        b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        b.setToggleGroup(g);
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true);
            r.run();
        });
        return b;
    }

    private static Button quick(String text, String icon, String color, Runnable r) {
        Button b = new Button(text, Icons.of(icon, 18));
        b.getStyleClass().setAll("button", "quick-action", color);
        b.setPrefWidth(150);
        b.setMinWidth(130);
        b.setOnAction(e -> r.run());
        return b;
    }

    // ------------------------------------------------------------ data

    private void onSample(Metrics.Sample s) {
        if (!built()) return;
        cpuGauge.set(s.cpu());
        memGauge.set(s.memPct());
        diskGauge.set(s.diskPct());
        cpuSub.setText(s.cores().length + " cores\nload " + String.format("%.2f", s.load1()));
        memSub.setText(Metrics.bytes(s.memUsed()) + "\nof " + Metrics.bytes(s.memTotal()));
        diskSub.setText(Metrics.bytes(s.diskUsed()) + "\nof " + Metrics.bytes(s.diskTotal()));
        netUp.setText(Metrics.rate(s.txBps()));
        netDown.setText(Metrics.rate(s.rxBps()));
        diskIo.setText("Disk I/O  read " + Metrics.rate(s.readBps()) + "  ·  write " + Metrics.rate(s.writeBps()));
        info.get("Uptime").setText(Metrics.uptime(s.uptimeSec()));
        info.get("Load average").setText(String.format("%.2f  %.2f  %.2f", s.load1(), s.load5(), s.load15()));
        info.get("Swap").setText(s.swapTotal() == 0 ? "none" : Metrics.bytes(s.swapUsed()) + " / " + Metrics.bytes(s.swapTotal()));
        info.get("Temperature").setText(Double.isNaN(s.tempC()) ? "n/a" : String.format("%.0f °C", s.tempC()));
        long now = System.currentTimeMillis();
        if (visible && (rangeSeconds <= 3600 || now - lastChart > 60_000)) redrawChart();
    }

    private void redrawChart() {
        if (chart == null) return;
        lastChart = System.currentTimeMillis();
        List<Metrics.Point> pts = Metrics.get().history(rangeSeconds, 300);
        long now = System.currentTimeMillis();
        xAxis.setLowerBound(now - rangeSeconds * 1000);
        xAxis.setUpperBound(now);
        xAxis.setTickUnit(rangeSeconds * 1000 / 6.0);
        XYChart.Series<Number, Number> a = new XYChart.Series<>();
        XYChart.Series<Number, Number> b = new XYChart.Series<>();
        List<XYChart.Data<Number, Number>> da = new ArrayList<>();
        List<XYChart.Data<Number, Number>> db = new ArrayList<>();
        if (chartMode.equals("cpu")) {
            a.setName("CPU %");
            b.setName("Memory %");
            yAxis.setAutoRanging(false);
            yAxis.setLowerBound(0);
            yAxis.setUpperBound(100);
            yAxis.setTickUnit(25);
            yAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(yAxis, null, "%"));
            for (Metrics.Point p : pts) {
                da.add(new XYChart.Data<>(p.time(), p.cpu()));
                db.add(new XYChart.Data<>(p.time(), p.mem()));
            }
        } else {
            a.setName("Download");
            b.setName("Upload");
            yAxis.setAutoRanging(true);
            yAxis.setTickLabelFormatter(new StringConverter<>() {
                @Override public String toString(Number n) { return Metrics.rate(n.doubleValue()); }
                @Override public Number fromString(String s) { return 0; }
            });
            for (Metrics.Point p : pts) {
                da.add(new XYChart.Data<>(p.time(), p.rx()));
                db.add(new XYChart.Data<>(p.time(), p.tx()));
            }
        }
        a.getData().setAll(da);
        b.getData().setAll(db);
        chart.getData().setAll(List.of(a, b));
    }

    @Override
    public void refresh() {
        if (!built()) return;
        redrawChart();
        Host h = host();
        Ui.bg(() -> h.sh("hostname; uname -r; uname -m; grep -m1 'model name' /proc/cpuinfo | cut -d: -f2; nproc"), r -> {
            String[] l = r.lines();
            info.get("Hostname").setText(l.length > 0 ? l[0] : "–");
            info.get("Kernel").setText(l.length > 1 ? l[1] : "–");
            info.get("Architecture").setText(l.length > 2 ? l[2] : "–");
            info.get("CPU").setText((l.length > 3 ? l[3].strip() : "?") + (l.length > 4 ? "  ×" + l[4] : ""));
            info.get("OS").setText(h.os());
        });
        loadHealth();
    }

    private static final String HEALTH = String.join("\n",
            "echo \"FAILED:$(systemctl --failed --no-legend --plain 2>/dev/null | awk '{print $1}' | paste -sd, -)\"",
            "if command -v docker >/dev/null 2>&1; then",
            "  echo \"UNHEALTHY:$(docker ps --filter health=unhealthy --format '{{.Names}}' 2>/dev/null | paste -sd, -)\"",
            "  echo \"RESTARTING:$(docker ps --filter status=restarting --format '{{.Names}}' 2>/dev/null | paste -sd, -)\"",
            "fi",
            "test -f /var/run/reboot-required && echo REBOOT:yes",
            "if command -v apt >/dev/null 2>&1; then U=$(apt list --upgradable 2>/dev/null | grep -c upgradable); S=$(apt list --upgradable 2>/dev/null | grep -c -- -security); echo \"UPDATES:$U:$S\";",
            "elif command -v dnf >/dev/null 2>&1; then echo \"UPDATES:$(dnf -q -C check-update 2>/dev/null | grep -cE '^[^ ]+ +[^ ]+ +[^ ]+$'):0\";",
            "elif command -v pacman >/dev/null 2>&1; then echo \"UPDATES:$(pacman -Qu 2>/dev/null | wc -l):0\"; fi",
            "df -P -x tmpfs -x devtmpfs -x squashfs -x overlay -x efivarfs 2>/dev/null | awk 'NR>1 && $5+0>85 {print \"DISK:\"$6\" \"$5}'",
            "for f in /etc/letsencrypt/live/*/cert.pem /etc/ssl/serverpilotx/*/fullchain.pem; do [ -r \"$f\" ] || continue;",
            "  openssl x509 -checkend 1209600 -noout -in \"$f\" >/dev/null 2>&1 || echo \"CERT:$f\"; done",
            "systemctl list-units --type=service --all --no-legend --plain 2>/dev/null | awk '{print \"SVC:\"$1\":\"$3\":\"$4}'",
            "true");

    private static final List<String> KNOWN = List.of("nginx", "apache2", "httpd", "docker", "containerd", "ssh", "sshd",
            "ufw", "nftables", "fail2ban", "crowdsec", "postgresql", "mysql", "mariadb", "redis-server", "redis",
            "cron", "crond", "php-fpm", "unattended-upgrades", "systemd-resolved");

    private void loadHealth() {
        Host h = host();
        Ui.bg(() -> h.sh(HEALTH), this::showHealth);
    }

    private void showHealth(CmdResult r) {
        healthList.getChildren().clear();
        servicesList.getChildren().clear();
        List<Node> items = new ArrayList<>();
        Map<String, String[]> svcs = new LinkedHashMap<>();
        boolean anyIssue = false;
        for (String line : r.lines()) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c), v = line.substring(c + 1).strip();
            switch (k) {
                case "FAILED" -> {
                    if (!v.isEmpty()) { anyIssue = true; items.add(health("red", "Failed systemd units", v.replace(",", ", "))); }
                    else items.add(health("green", "All systemd units OK", null));
                }
                case "UNHEALTHY" -> {
                    if (!v.isEmpty()) { anyIssue = true; items.add(health("red", "Unhealthy containers", v)); }
                    else items.add(health("green", "Containers healthy", null));
                }
                case "RESTARTING" -> { if (!v.isEmpty()) { anyIssue = true; items.add(health("amber", "Containers restarting", v)); } }
                case "REBOOT" -> { anyIssue = true; items.add(health("amber", "Reboot required", "A kernel or library update needs a restart")); }
                case "UPDATES" -> {
                    String[] p = v.split(":");
                    int n = parse(p[0]), sec = p.length > 1 ? parse(p[1]) : 0;
                    if (n > 0) { anyIssue = true; items.add(health(sec > 0 ? "red" : "amber", n + " package updates available", sec > 0 ? sec + " security updates" : null)); }
                    else items.add(health("green", "System up to date", null));
                }
                case "DISK" -> { anyIssue = true; items.add(health("red", "Disk almost full", v)); }
                case "CERT" -> { anyIssue = true; items.add(health("amber", "Certificate expires in < 14 days", v)); }
                case "SVC" -> {
                    String[] p = v.split(":");
                    String unit = p[0].replace(".service", "");
                    String base = unit.replaceAll("^php[0-9.]*-fpm$", "php-fpm").replaceAll("@.*", "");
                    if (KNOWN.contains(base) && p.length >= 3) svcs.put(unit, p);
                }
                default -> { }
            }
        }
        if (!anyIssue) items.add(0, health("green", "No problems detected", null));
        healthList.getChildren().setAll(items);
        if (svcs.isEmpty()) servicesList.getChildren().add(Ui.label("No common services found", "muted"));
        for (Map.Entry<String, String[]> e : svcs.entrySet()) {
            String active = e.getValue()[1], sub = e.getValue()[2];
            String kind = active.equals("active") ? "green" : active.equals("failed") ? "red" : "grey";
            String text = sub.equals("running") ? "Running" : active.equals("failed") ? "Failed" : sub.equals("exited") ? "Active" : "Stopped";
            Label badge = Ui.badge(text, kind.equals("grey") ? null : kind);
            HBox row = new HBox(10, Ui.dot(kind), Ui.label(e.getKey()), Ui.spacer(), badge,
                    Ui.iconButton("restart", "Restart " + e.getKey(), x -> restartService(e.getKey())));
            row.getStyleClass().add("health-item");
            servicesList.getChildren().add(row);
        }
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.strip()); } catch (Exception e) { return 0; }
    }

    private static Node health(String color, String title, String detail) {
        VBox text = new VBox(1, Ui.label(title));
        if (detail != null && !detail.isBlank()) {
            Label d = Ui.label(detail, "muted", "small");
            d.setWrapText(true);
            text.getChildren().add(d);
        }
        HBox row = new HBox(10, Ui.dot(color), text);
        row.getStyleClass().add("health-item");
        return row;
    }

    // ------------------------------------------------------------ quick actions

    public static void restartService(String unit) {
        Host h = Hosts.active();
        if (!Ui.confirm("Restart " + unit, "Restart the " + unit + " service on " + h.name() + "?", "Restart", false)) return;
        Ui.action("Restart service", unit, () -> {
            h.sudo("systemctl", "restart", unit).orThrow();
            return "systemctl restart " + unit;
        }, null);
    }

    public static void reboot() {
        Host h = Hosts.active();
        if (!Ui.confirm("Reboot " + h.name(), "The server will restart now. Running sites and containers will be unavailable until it is back.",
                "Reboot now", true)) return;
        Ui.action("Reboot server", h.name(), () -> {
            h.sudo("systemctl", "reboot");
            return "reboot requested";
        }, null);
    }

    private static void openUpdates() {
        MainWindow.get().navigate("services");
        if (MainWindow.get().view("services") instanceof ServicesView sv) sv.showTab("Updates");
    }

    public static List<CommandPalette.Command> quickCommands() {
        return List.of(
                new CommandPalette.Command("Restart Nginx", "systemctl restart nginx", "restart", () -> restartService("nginx")),
                new CommandPalette.Command("Restart Docker", "systemctl restart docker", "restart", () -> restartService("docker")),
                new CommandPalette.Command("Check for updates", "packages", "package", DashboardView::openUpdates),
                new CommandPalette.Command("Reboot server", "systemctl reboot", "power", DashboardView::reboot));
    }
}
