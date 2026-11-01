package io.serverpilotx.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.core.Certs;
import io.serverpilotx.core.CmdException;
import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import io.serverpilotx.core.Json;
import io.serverpilotx.core.Nginx;
import io.serverpilotx.ui.Icons;
import io.serverpilotx.ui.LogViewer;
import io.serverpilotx.ui.MainWindow;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Nginx virtual hosts: list, wizard (proxy/static/PHP/redirect), raw editor, enable/disable, logs. */
public final class SitesView extends View {
    private final StackPane root = new StackPane();
    private final TableView<Map<String, Object>> table = new TableView<>();
    private FilteredList<Map<String, Object>> filtered;
    private Nginx.Layout layout;

    @Override public String id() { return "sites"; }
    @Override public String title() { return "Sites"; }
    @Override public String subtitle() { return "Nginx reverse proxy & virtual hosts"; }
    @Override public String icon() { return "globe"; }

    private static String s(Map<String, Object> m, String k) { return Json.str(m, k); }

    @Override
    protected Node build() {
        table.getColumns().add(Ui.col("Domains", m -> s(m, "domains"), 260));
        table.getColumns().add(Ui.badgeCol("Type", m -> s(m, "type"), v -> switch (v) {
            case "Reverse proxy" -> "blue"; case "Static" -> "purple"; case "PHP" -> "amber"; default -> null; }, 120));
        table.getColumns().add(Ui.monoCol("Target", m -> s(m, "target"), 240));
        table.getColumns().add(Ui.badgeCol("SSL", m -> s(m, "ssl"), v -> v.equals("HTTPS") ? "green" : null, 80));
        table.getColumns().add(Ui.badgeCol("Status", m -> s(m, "enabled").equals("yes") ? "Enabled" : "Disabled", v -> v.equals("Enabled") ? "green" : null, 95));
        table.getColumns().add(Ui.col("Managed", m -> s(m, "managed").equals("yes") ? "ServerPilotX" : "manual", 110));
        table.getColumns().add(Ui.monoCol("File", m -> s(m, "path"), 260));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No sites yet", "muted"));
        table.setRowFactory(tv -> {
            TableRow<Map<String, Object>> r = new TableRow<>();
            r.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !r.isEmpty()) edit(r.getItem()); });
            return r;
        });
        filtered = new FilteredList<>(FXCollections.observableArrayList());
        table.setItems(filtered);

        TextField search = new TextField();
        search.setPromptText("Filter sites…");
        search.textProperty().addListener((o, a, q) -> filtered.setPredicate(m -> q == null || q.isBlank()
                || (s(m, "domains") + s(m, "target") + s(m, "path")).toLowerCase().contains(q.toLowerCase())));

        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                mi("Edit raw config", "file", () -> sel(this::editRaw)),
                mi("Access log", "logs", () -> sel(m -> logs(m, "access"))),
                mi("Error log", "alert", () -> sel(m -> logs(m, "error"))),
                mi("Issue SSL certificate…", "lock", () -> sel(m -> SslView.issueFor(s(m, "domains").replace(",", " "), s(m, "path")))),
                mi("Test configuration (nginx -t)", "check", this::testConfig),
                mi("Reload Nginx", "refresh", () -> {
                    Host h = host();
                    Ui.action("Reload nginx", h.name(), () -> { Nginx.reload(h); return "reloaded"; }, null);
                }));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.primary("New site", "plus", e -> wizard(null, null, null)), search, Ui.spacer(),
                Ui.button("Edit", "edit", e -> sel(this::edit)),
                Ui.button("Enable", "play", e -> sel(m -> toggle(m, true))),
                Ui.button("Disable", "pause", e -> sel(m -> toggle(m, false))),
                more,
                Ui.danger("Delete", "trash", e -> sel(this::delete)));
        HBox.setHgrow(search, Priority.SOMETIMES);
        VBox page = new VBox(12, bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        page.getStyleClass().add("page");
        root.getChildren().add(page);
        return root;
    }

    private static MenuItem mi(String t, String icon, Runnable r) {
        MenuItem m = new MenuItem(t, Icons.of(icon, 14));
        m.setOnAction(e -> r.run());
        return m;
    }

    private void sel(java.util.function.Consumer<Map<String, Object>> c) {
        Map<String, Object> m = table.getSelectionModel().getSelectedItem();
        if (m == null) Ui.toast("Select a site first", Ui.Kind.INFO);
        else c.accept(m);
    }

    // ------------------------------------------------------------ loading

    @Override
    public void refresh() {
        if (!built()) return;
        Host h = host();
        if (root.getChildren().size() > 1) root.getChildren().remove(1, root.getChildren().size());
        if (!h.has("nginx")) {
            Button install = Ui.primary("Install Nginx", "download", e -> Ui.stream("Install Nginx", h, h.installArgv("nginx"), true, c -> {
                Ui.bg(() -> { h.detect(); h.sudo("systemctl", "enable", "--now", "nginx"); return null; }, x -> refresh());
            }));
            VBox empty = Ui.empty("globe", "Nginx is not installed", "ServerPilotX uses Nginx to serve sites and reverse-proxy your apps.", install);
            empty.setStyle("-fx-background-color: -spx-bg;");
            root.getChildren().add(empty);
            return;
        }
        Ui.bg(() -> load(h), rows -> {
            List<Map<String, Object>> l = rows;
            filtered = new FilteredList<>(FXCollections.observableArrayList(l));
            table.setItems(filtered);
        });
    }

    private List<Map<String, Object>> load(Host h) {
        layout = Nginx.layout(h);
        String glob = layout.debian() ? layout.availableDir() + "/*" : layout.availableDir() + "/*.conf " + layout.availableDir() + "/*.conf.disabled";
        String script = "for f in " + glob + "; do [ -f \"$f\" ] || continue; n=$(basename \"$f\"); en=yes;"
                + (layout.debian() ? " [ -e " + layout.enabledDir() + "/\"$n\" ] || en=no;" : " case \"$f\" in *.disabled) en=no;; esac;")
                + " echo \"@@$f|$en\"; grep -E 'server_name|proxy_pass|^[[:space:]]*root |return 30|listen .*443|SPX-META|fastcgi_pass' \"$f\" 2>/dev/null; done";
        CmdResult r = h.sh(script);
        if (!r.ok() && r.out().isBlank()) r = h.sudoSh(script);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String block : r.out().split("@@")) {
            if (block.isBlank()) continue;
            String[] lines = block.split("\\R");
            String[] head = lines[0].split("\\|");
            if (head.length < 2) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", head[0]);
            m.put("enabled", head[1]);
            List<String> names = new ArrayList<>();
            String target = "", type = "Custom";
            boolean ssl = false, managed = false;
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i].strip();
                if (l.startsWith("#") && !l.startsWith(Nginx.META)) continue;
                if (l.startsWith(Nginx.META)) {
                    managed = true;
                    Nginx.Site site = Nginx.parseMeta(l);
                    if (site != null) m.put("meta", site);
                } else if (l.startsWith("server_name")) {
                    for (String n : l.replace("server_name", "").replace(";", "").strip().split("\\s+")) {
                        if (!n.isBlank() && !names.contains(n)) names.add(n);
                    }
                } else if (l.startsWith("proxy_pass")) { target = l.replace("proxy_pass", "").replace(";", "").strip(); type = "Reverse proxy"; }
                else if (l.startsWith("fastcgi_pass")) { type = "PHP"; }
                else if (l.startsWith("root ") && target.isEmpty()) { target = l.replace("root", "").replace(";", "").strip(); if (type.equals("Custom")) type = "Static"; }
                else if (l.startsWith("return 30") && type.equals("Custom") && !l.contains("https://$host")) {
                    target = l.replaceAll("return 30\\d", "").replace(";", "").strip(); type = "Redirect";
                }
                else if (l.contains("443")) ssl = true;
            }
            m.put("domains", String.join(", ", names));
            m.put("target", target);
            m.put("type", type);
            m.put("ssl", ssl ? "HTTPS" : "HTTP");
            m.put("managed", managed ? "yes" : "no");
            rows.add(m);
        }
        return rows;
    }

    // ------------------------------------------------------------ actions

    private void edit(Map<String, Object> m) {
        Object meta = m.get("meta");
        if (meta instanceof Nginx.Site site) wizard(site, s(m, "path"), null);
        else editRaw(m);
    }

    private void editRaw(Map<String, Object> m) {
        Host h = host();
        String path = s(m, "path");
        Ui.bg(() -> h.readFile(path), content -> Ui.editor("Edit " + path,
                "Raw Nginx config. Validated with nginx -t before reload; on failure the previous file is restored."
                        + (content.contains(Nginx.BEGIN) ? " Text outside the SPX MANAGED block is kept when the wizard regenerates." : ""),
                content, text -> {
                    Nginx.apply(h, layout, path, text, s(m, "enabled").equals("yes"));
                    AuditLog.log("Edit site config", path, "raw edit", true);
                    Ui.fx(this::refresh);
                    return "Saved and reloaded " + path;
                }));
    }

    private void toggle(Map<String, Object> m, boolean enable) {
        Host h = host();
        String path = s(m, "path");
        Ui.action(enable ? "Enable site" : "Disable site", s(m, "domains"), () -> {
            Nginx.setEnabled(h, layout, path, enable);
            return path;
        }, this::refresh);
    }

    private void delete(Map<String, Object> m) {
        if (!Ui.confirm("Delete site", "Delete the Nginx site " + s(m, "domains") + " (" + s(m, "path") + ")? Certificates and web files are kept.", "Delete", true)) return;
        Host h = host();
        String path = s(m, "path");
        Ui.action("Delete site", s(m, "domains"), () -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (layout.debian()) h.sudo("rm", "-f", layout.enabledDir() + "/" + name);
            h.sudo("rm", "-f", path).orThrow();
            CmdResult t = h.sudo("nginx", "-t");
            if (t.ok()) Nginx.reload(h);
            return path;
        }, this::refresh);
    }

    private void logs(Map<String, Object> m, String kind) {
        Host h = host();
        Object meta = m.get("meta");
        String id = meta instanceof Nginx.Site site ? Nginx.id(site) : null;
        String file = id != null ? "/var/log/nginx/" + id + "." + kind + ".log" : "/var/log/nginx/" + kind + ".log";
        LogViewer.open(kind + " log · " + s(m, "domains"), h, true, (n, f) -> {
            List<String> a = new ArrayList<>(List.of("tail"));
            a.add("-n");
            a.add(n < 0 ? "+1" : String.valueOf(n));
            if (f) a.add("-F");
            a.add(file);
            return a;
        });
    }

    private void testConfig() {
        Host h = host();
        Ui.bg(() -> h.sudo("nginx", "-t"), r -> {
            if (r.ok()) Ui.toast("nginx -t: configuration OK", Ui.Kind.OK);
            else Ui.errorDialog("nginx -t failed", r.text());
        });
    }

    // ------------------------------------------------------------ wizard

    /** Opens the wizard pre-filled for a new proxy site (used by the Docker catalog). */
    public static void newSiteFor(String domain, String upstream) {
        MainWindow.get().navigate("sites");
        if (MainWindow.get().view("sites") instanceof SitesView v) {
            Nginx.Site s = new Nginx.Site();
            s.domains = domain;
            s.upstream = upstream;
            v.wizard(null, null, s);
        }
    }

    /** Re-applies a managed site with a certificate (used after issuing SSL). */
    public static void attachCert(Host h, String path, String cert, String key) {
        String content = h.readFile(path);
        Nginx.Site site = Nginx.parseMeta(content);
        if (site == null) throw new CmdException(path + " is not managed by ServerPilotX; add ssl_certificate manually.");
        site.cert = cert;
        site.key = key;
        site.forceHttps = true;
        Nginx.Layout l = Nginx.layout(h);
        Nginx.apply(h, l, path, Nginx.merge(content, Nginx.generate(site, Nginx.modernHttp2(h))), true);
        AuditLog.log("Attach certificate", path, cert, true);
    }

    void wizard(Nginx.Site existing, String existingPath, Nginx.Site preset) {
        Nginx.Site s = existing != null ? existing : preset != null ? preset : new Nginx.Site();
        Host h = Hosts.active();

        // General
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("Reverse proxy", "Static", "PHP-FPM", "Redirect"));
        type.setValue(switch (s.type) { case "static" -> "Static"; case "php" -> "PHP-FPM"; case "redirect" -> "Redirect"; default -> "Reverse proxy"; });
        TextField domains = new TextField(s.domains);
        domains.setPromptText("app.example.com www.app.example.com");
        TextField upstream = new TextField(s.upstream);
        CheckBox ws = new CheckBox("WebSocket upgrade");
        ws.setSelected(s.websocket);
        TextField rootDir = new TextField(s.root);
        CheckBox spa = new CheckBox("Single-page app fallback (serve /index.html)");
        spa.setSelected(s.spa);
        ComboBox<String> php = new ComboBox<>(FXCollections.observableArrayList("7.4", "8.1", "8.2", "8.3", "8.4"));
        php.setEditable(true);
        php.setValue(s.php);
        TextField redirect = new TextField(s.redirectTo);
        redirect.setPromptText("https://new.example.com");
        ComboBox<Integer> code = new ComboBox<>(FXCollections.observableArrayList(301, 302, 307, 308));
        code.setValue(s.redirectCode);
        CheckBox keepPath = new CheckBox("Keep path and query");
        keepPath.setSelected(s.keepPath);
        ComboBox<String> canonical = new ComboBox<>(FXCollections.observableArrayList("No www redirect", "Redirect to www", "Redirect to non-www"));
        canonical.setValue(switch (s.canonical) { case "www" -> "Redirect to www"; case "non-www" -> "Redirect to non-www"; default -> "No www redirect"; });

        Ui.Form general = new Ui.Form()
                .row("Site type", type)
                .row("Domains", domains, "Space separated. The first one is the primary name.")
                .row("Upstream", upstream, "e.g. http://127.0.0.1:3000 or http://container-name:80")
                .row("", ws)
                .row("Document root", rootDir)
                .row("", spa)
                .row("PHP version", php, "Uses /run/php/php<version>-fpm.sock")
                .row("Redirect to", redirect)
                .row("Status code", code)
                .row("", keepPath)
                .row("www handling", canonical);
        Runnable vis = () -> {
            String t = type.getValue();
            show(general, upstream, t.equals("Reverse proxy"));
            show(general, ws, t.equals("Reverse proxy"));
            show(general, rootDir, t.equals("Static") || t.equals("PHP-FPM"));
            show(general, spa, t.equals("Static"));
            show(general, php, t.equals("PHP-FPM"));
            show(general, redirect, t.equals("Redirect"));
            show(general, code, t.equals("Redirect"));
            show(general, keepPath, t.equals("Redirect"));
        };
        type.setOnAction(e -> vis.run());
        vis.run();

        // SSL
        ComboBox<String> certBox = new ComboBox<>();
        certBox.getItems().add("None (HTTP only)");
        certBox.setValue("None (HTTP only)");
        Map<String, Certs.Cert> certMap = new LinkedHashMap<>();
        TextField certPath = new TextField(s.cert);
        TextField keyPath = new TextField(s.key);
        certBox.setOnAction(e -> {
            Certs.Cert c = certMap.get(certBox.getValue());
            if (c != null) { certPath.setText(c.certPath()); keyPath.setText(c.keyPath()); }
            else if (certBox.getValue() != null && certBox.getValue().startsWith("None")) { certPath.clear(); keyPath.clear(); }
        });
        Ui.bg(() -> Certs.scan(h), list -> {
            for (Certs.Cert c : list) {
                String label = String.join(", ", c.domains()) + "  (" + c.source() + ", " + c.daysLeft() + " days)";
                certMap.put(label, c);
                certBox.getItems().add(label);
                if (c.certPath().equals(s.cert)) certBox.setValue(label);
            }
        }, err -> { });
        CheckBox force = new CheckBox("Redirect HTTP → HTTPS");
        force.setSelected(s.forceHttps);
        CheckBox hsts = new CheckBox("HSTS (Strict-Transport-Security, 2 years)");
        hsts.setSelected(s.hsts);
        CheckBox issueLe = new CheckBox("Issue a Let's Encrypt certificate after saving");
        Ui.Form ssl = new Ui.Form()
                .row("Certificate", certBox, "Pick from the certificate inventory, or set paths below")
                .row("Certificate file", certPath)
                .row("Key file", keyPath)
                .full(force).full(hsts)
                .full(issueLe);

        // Security
        TextArea allow = new TextArea(s.allow);
        allow.setPromptText("Only allow these IPs / CIDRs (one per line). Empty = everyone.");
        allow.setPrefRowCount(3);
        TextField authUser = new TextField(s.authUser);
        authUser.setPromptText("Leave empty to disable basic auth");
        PasswordField authPass = new PasswordField();
        authPass.setPromptText(s.authUser.isBlank() ? "Password" : "Leave empty to keep current password");
        Spinner<Integer> rate = new Spinner<>(0, 10000, s.rateLimit);
        rate.setEditable(true);
        Ui.Form security = new Ui.Form()
                .row("IP allow list", allow)
                .row("Basic auth user", authUser)
                .row("Basic auth password", authPass)
                .row("Rate limit", rate, "Requests per second per client IP (0 = off)");

        // Advanced
        CheckBox gzip = new CheckBox("Gzip compression");
        gzip.setSelected(s.gzip);
        TextField maxBody = new TextField(s.maxBody);
        TextArea headers = new TextArea(s.headers);
        headers.setPromptText("X-Frame-Options: SAMEORIGIN");
        headers.setPrefRowCount(3);
        headers.getStyleClass().add("code-area");
        TextArea custom = new TextArea(s.custom);
        custom.setPromptText("location /api/ {\n    proxy_pass http://127.0.0.1:4000;\n}");
        custom.setPrefRowCount(6);
        custom.getStyleClass().add("code-area");
        Ui.Form advanced = new Ui.Form()
                .full(gzip)
                .row("Max upload size", maxBody, "client_max_body_size, e.g. 64m")
                .row("Custom headers", headers, "One 'Name: value' per line")
                .row("Custom directives", custom, "Inserted into the server block (e.g. extra location blocks)");

        TabPane tp = new TabPane(tab("General", general), tab("SSL", ssl), tab("Security", security), tab("Advanced", advanced));
        tp.setPrefWidth(640);

        String title = existing != null ? "Edit site " + s.primary() : "New site";
        if (!Ui.dialog(title, title, tp, existing != null ? "Save & reload" : "Create site", () -> {
            List<String> d = Nginx.parseDomains(domains.getText());
            if (d.isEmpty()) return "At least one domain is required";
            for (String x : d) if (!x.matches("[a-z0-9*][a-z0-9.*-]*")) return "Invalid domain: " + x;
            if (type.getValue().equals("Reverse proxy") && !upstream.getText().matches("https?://.+")) return "Upstream must start with http:// or https://";
            if (type.getValue().equals("Redirect") && !redirect.getText().matches("https?://.+")) return "Redirect target must be a URL";
            if (certPath.getText().isBlank() != keyPath.getText().isBlank()) return "Set both certificate and key, or neither";
            if (!authUser.getText().isBlank() && authPass.getText().isBlank() && s.authUser.isBlank()) return "Basic auth password required";
            return null;
        })) return;

        Nginx.Site n = new Nginx.Site();
        n.type = switch (type.getValue()) { case "Static" -> "static"; case "PHP-FPM" -> "php"; case "Redirect" -> "redirect"; default -> "proxy"; };
        n.domains = String.join(" ", Nginx.parseDomains(domains.getText()));
        n.upstream = upstream.getText().strip();
        n.websocket = ws.isSelected();
        n.root = rootDir.getText().strip();
        n.spa = spa.isSelected();
        n.php = php.getValue();
        n.redirectTo = redirect.getText().strip();
        n.redirectCode = code.getValue();
        n.keepPath = keepPath.isSelected();
        n.canonical = canonical.getValue().equals("Redirect to www") ? "www" : canonical.getValue().equals("Redirect to non-www") ? "non-www" : "none";
        n.cert = certPath.getText().strip();
        n.key = keyPath.getText().strip();
        n.forceHttps = force.isSelected();
        n.hsts = hsts.isSelected();
        n.allow = allow.getText().strip();
        n.authUser = authUser.getText().strip();
        n.rateLimit = rate.getValue();
        n.gzip = gzip.isSelected();
        n.maxBody = maxBody.getText().strip();
        n.headers = headers.getText().strip();
        n.custom = custom.getText().strip();
        String pw = authPass.getText();
        boolean issue = issueLe.isSelected();

        Ui.bg(() -> {
            Nginx.Layout l = layout != null ? layout : Nginx.layout(h);
            String path = existingPath != null ? existingPath : l.pathFor(Nginx.id(n));
            if (existingPath == null && h.exists(path)) throw new CmdException("A site config already exists at " + path);
            h.sudo("mkdir", "-p", Nginx.ACME_ROOT);
            if (!n.authUser.isBlank() && !pw.isBlank()) Nginx.setBasicAuth(h, Nginx.id(n), n.authUser, pw);
            String generated = Nginx.generate(n, Nginx.modernHttp2(h));
            String content = existingPath != null ? Nginx.merge(h.readFile(path), generated) : generated;
            Nginx.apply(h, l, path, content, true);
            AuditLog.log(existingPath != null ? "Update site" : "Create site", n.domains, path, true);
            return path;
        }, path -> {
            Ui.toast("Site saved: " + n.primary(), Ui.Kind.OK);
            refresh();
            if (issue) SslView.issueFor(n.domains, path);
        }, err -> {
            AuditLog.log(existingPath != null ? "Update site" : "Create site", n.domains, err, false);
            Ui.errorDialog("Could not save site", err);
        });
    }

    private static Tab tab(String name, Node content) {
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(430);
        sp.setStyle("-fx-padding: 8 4 4 4;");
        Tab t = new Tab(name, sp);
        t.setClosable(false);
        return t;
    }

    private static void show(Ui.Form f, Node control, boolean visible) {
        for (Node n : f.getChildren()) {
            Integer row = javafx.scene.layout.GridPane.getRowIndex(n);
            Integer crow = javafx.scene.layout.GridPane.getRowIndex(control.getParent() instanceof VBox v && f.getChildren().contains(v) ? v : control);
            if (row != null && row.equals(crow)) {
                n.setVisible(visible);
                n.setManaged(visible);
            }
        }
    }
}
