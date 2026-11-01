package io.serverpilotx.view;

import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.core.Certs;
import io.serverpilotx.core.CmdException;
import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import io.serverpilotx.core.Nginx;
import io.serverpilotx.core.Quote;
import io.serverpilotx.core.Settings;
import io.serverpilotx.ui.Icons;
import io.serverpilotx.ui.MainWindow;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
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
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Certificate inventory, ACME issuance (HTTP-01 / DNS-01 via certbot), self-signed and uploaded certificates. */
public final class SslView extends View {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final TableView<Certs.Cert> table = new TableView<>();
    private final TableView<String[]> dnsTable = new TableView<>();
    private final VBox banners = new VBox(8);
    private TabPane tabs;

    @Override public String id() { return "ssl"; }
    @Override public String title() { return "SSL / TLS"; }
    @Override public String subtitle() { return "Certificates & ACME"; }
    @Override public String icon() { return "lock"; }

    @Override
    protected Node build() {
        table.getColumns().add(Ui.col("Name", Certs.Cert::name, 160));
        table.getColumns().add(Ui.col("Domains", c -> String.join(", ", c.domains()), 260));
        table.getColumns().add(Ui.col("Issuer", Certs.Cert::issuer, 150));
        table.getColumns().add(Ui.col("Expires", c -> DATE.format(c.notAfter()), 100));
        table.getColumns().add(Ui.badgeCol("Days left", c -> String.valueOf(c.daysLeft()), v -> {
            long d = Long.parseLong(v);
            return d < 14 ? "red" : d < 30 ? "amber" : "green";
        }, 90));
        table.getColumns().add(Ui.col("Key", Certs.Cert::keyType, 110));
        table.getColumns().add(Ui.badgeCol("Source", Certs.Cert::source, v -> v.equals("ACME") ? "blue" : v.equals("Self-signed") ? "amber" : "purple", 100));
        table.getColumns().add(Ui.monoCol("Path", Certs.Cert::certPath, 300));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No certificates found", "muted"));

        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                mi("Renew all due (certbot renew)", "refresh", this::renewAll),
                mi("Generate self-signed…", "cert", this::selfSigned),
                mi("Upload certificate…", "upload", this::upload),
                mi("Set up auto-renew", "clock", this::autoRenew));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.primary("Issue certificate", "plus", e -> issueDialog("", null)), Ui.spacer(),
                Ui.button("Details", "eye", e -> sel(this::details)),
                Ui.button("Renew", "refresh", e -> sel(this::renew)),
                Ui.button("Use on site…", "globe", e -> sel(this::useOnSite)),
                more,
                Ui.danger("Delete", "trash", e -> sel(this::delete)));
        VBox certs = new VBox(12, bar, banners, table);
        VBox.setVgrow(table, Priority.ALWAYS);

        dnsTable.getColumns().add(Ui.col("Provider", r -> r[0], 200));
        dnsTable.getColumns().add(Ui.monoCol("Credentials file", r -> r[1], 400));
        dnsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        dnsTable.setPlaceholder(Ui.label("No DNS providers — add one to issue wildcard certificates via DNS-01", "muted"));
        io.serverpilotx.ui.ToolBarPane dnsBar = Ui.toolbar(Ui.primary("Add DNS provider", "plus", e -> addProvider()), Ui.spacer(),
                Ui.danger("Remove", "trash", e -> {
                    String[] r = dnsTable.getSelectionModel().getSelectedItem();
                    if (r == null || !Ui.confirm("Remove provider", "Delete " + r[1] + "?", "Delete", true)) return;
                    Host h = host();
                    Ui.action("Remove DNS provider", r[0], () -> h.sudo("rm", "-f", r[1]).orThrow(), this::loadProviders);
                }));
        VBox dns = new VBox(12, dnsBar, dnsTable);
        VBox.setVgrow(dnsTable, Priority.ALWAYS);

        tabs = new TabPane(tab("Certificates", certs), tab("DNS providers", dns));
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

    private static MenuItem mi(String t, String icon, Runnable r) {
        MenuItem m = new MenuItem(t, Icons.of(icon, 14));
        m.setOnAction(e -> r.run());
        return m;
    }

    private void sel(java.util.function.Consumer<Certs.Cert> c) {
        Certs.Cert x = table.getSelectionModel().getSelectedItem();
        if (x == null) Ui.toast("Select a certificate first", Ui.Kind.INFO);
        else c.accept(x);
    }

    @Override
    public void refresh() {
        if (!built()) return;
        if (tabs.getSelectionModel().getSelectedIndex() == 1) { loadProviders(); return; }
        Host h = host();
        Ui.bg(() -> Certs.scan(h), list -> {
            table.setItems(FXCollections.observableArrayList(list));
            banners.getChildren().clear();
            long expiring = list.stream().filter(c -> c.daysLeft() < 14).count();
            if (expiring > 0) banners.getChildren().add(Ui.banner("red", "alert", expiring + " certificate(s) expire within 14 days."));
        });
        Ui.bg(() -> h.sh("command -v certbot >/dev/null || echo NOCERTBOT; systemctl is-active certbot.timer snap.certbot.renew.timer 2>/dev/null | grep -q '^active' && echo TIMER;"
                + " (crontab -l 2>/dev/null; cat /etc/cron.d/* 2>/dev/null) | grep -q 'certbot' && echo CRON; true"), r -> {
            String o = r.out();
            if (o.contains("NOCERTBOT")) {
                Button install = Ui.button("Install certbot", "download", e -> Ui.stream("Install certbot", h, h.installArgv("certbot"), true, c -> {
                    Ui.runAsync(h::detect);
                    refresh();
                }));
                banners.getChildren().add(Ui.banner("info", "info", "certbot is not installed. It is used for Let's Encrypt / ACME certificates.", install));
            } else if (!o.contains("TIMER") && !o.contains("CRON")) {
                banners.getChildren().add(Ui.banner("amber", "clock", "Automatic renewal is not scheduled.",
                        Ui.button("Set up auto-renew", "clock", e -> autoRenew())));
            }
        });
    }

    // ------------------------------------------------------------ issue

    /** Opens the issue dialog prefilled (used from Sites). */
    public static void issueFor(String domains, String sitePath) {
        MainWindow.get().navigate("ssl");
        if (MainWindow.get().view("ssl") instanceof SslView v) v.issueDialog(domains, sitePath);
    }

    void issueDialog(String domains, String sitePath) {
        Host h = Hosts.active();
        TextField dom = new TextField(domains);
        dom.setPromptText("example.com www.example.com  (or *.example.com with DNS-01)");
        TextField email = new TextField(Settings.acmeEmail());
        email.setPromptText("admin@example.com");
        ComboBox<String> challenge = new ComboBox<>(FXCollections.observableArrayList(
                "HTTP-01 via Nginx webroot", "HTTP-01 standalone (port 80 free)", "TLS-ALPN-01 / HTTP via nginx plugin"));
        challenge.setValue("HTTP-01 via Nginx webroot");
        List<String[]> providers = new ArrayList<>();
        Ui.bg(() -> listProviders(h), list -> {
            providers.addAll(list);
            for (String[] p : list) challenge.getItems().add("DNS-01 · " + p[0]);
        }, err -> { });
        ComboBox<String> ca = new ComboBox<>(FXCollections.observableArrayList("Let's Encrypt", "Let's Encrypt (staging)", "Buypass"));
        ca.setValue("Let's Encrypt");
        ComboBox<String> key = new ComboBox<>(FXCollections.observableArrayList("ECDSA P-256", "ECDSA P-384", "RSA-2048", "RSA-4096"));
        key.setValue("ECDSA P-256");
        ComboBox<String> site = new ComboBox<>();
        site.getItems().add("Don't change any site");
        site.setValue("Don't change any site");
        if (sitePath != null) { site.getItems().add(sitePath); site.setValue(sitePath); }
        Ui.Form f = new Ui.Form()
                .row("Domains", dom, "All names go into one certificate. Wildcards need DNS-01.")
                .row("Email", email, "For expiry notices from the CA")
                .row("Challenge", challenge)
                .row("Authority", ca)
                .row("Key type", key)
                .row("Install on site", site, "The site is switched to HTTPS and reloaded");
        if (!Ui.dialog("Issue certificate", "Issue an ACME certificate", f, "Issue", () -> {
            if (Nginx.parseDomains(dom.getText()).isEmpty()) return "Domains required";
            if (!email.getText().contains("@")) return "A valid email is required";
            if (dom.getText().contains("*") && !challenge.getValue().startsWith("DNS-01")) return "Wildcard certificates need a DNS-01 provider";
            return null;
        })) return;
        Settings.acmeEmail(email.getText().strip());
        List<String> ds = Nginx.parseDomains(dom.getText());
        List<String> a = new ArrayList<>(List.of("certbot", "certonly", "--non-interactive", "--agree-tos", "-m", email.getText().strip(),
                "--cert-name", ds.get(0).replace("*.", "wildcard."), "--deploy-hook", "systemctl reload nginx"));
        String ch = challenge.getValue();
        if (ch.startsWith("HTTP-01 via")) a.addAll(List.of("--webroot", "-w", Nginx.ACME_ROOT));
        else if (ch.startsWith("HTTP-01 standalone")) a.add("--standalone");
        else if (ch.startsWith("TLS-ALPN")) a.add("--nginx");
        else {
            String pname = ch.substring("DNS-01 · ".length());
            for (String[] p : providers) if (p[0].equals(pname)) {
                String plugin = p[2];
                a.add("--" + plugin);
                a.add("--" + plugin + "-credentials");
                a.add(p[1]);
                a.add("--" + plugin + "-propagation-seconds");
                a.add("60");
            }
        }
        switch (ca.getValue()) {
            case "Let's Encrypt (staging)" -> a.add("--staging");
            case "Buypass" -> { a.add("--server"); a.add("https://api.buypass.com/acme/directory"); }
            default -> { }
        }
        if (key.getValue().startsWith("ECDSA")) {
            a.addAll(List.of("--key-type", "ecdsa", "--elliptic-curve", key.getValue().endsWith("384") ? "secp384r1" : "secp256r1"));
        } else {
            a.addAll(List.of("--key-type", "rsa", "--rsa-key-size", key.getValue().substring(4)));
        }
        for (String d : ds) { a.add("-d"); a.add(d); }
        String target = site.getValue();
        if (!h.has("certbot")) {
            Ui.errorDialog("certbot missing", "Install certbot first (button on the SSL page).");
            return;
        }
        Ui.bg(() -> { h.sudo("mkdir", "-p", Nginx.ACME_ROOT); return null; }, x ->
                Ui.stream("Issue certificate · " + ds.get(0), h, a, true, code -> {
                    if (code == 0 && target != null && target.startsWith("/")) {
                        String dir = "/etc/letsencrypt/live/" + ds.get(0).replace("*.", "wildcard.");
                        Ui.action("Install certificate on site", target, () -> {
                            SitesView.attachCert(h, target, dir + "/fullchain.pem", dir + "/privkey.pem");
                            return target;
                        }, this::refresh);
                    } else {
                        refresh();
                    }
                }));
    }

    private void renew(Certs.Cert c) {
        Host h = host();
        if (!c.source().equals("ACME")) {
            Ui.toast("Only ACME certificates can be renewed. Re-generate or upload a new one.", Ui.Kind.INFO);
            return;
        }
        CheckBox force = new CheckBox("Force renewal even if not due");
        Ui.Form f = new Ui.Form().full(Ui.label("Renew " + c.name() + " (" + c.daysLeft() + " days left)?")).full(force);
        if (!Ui.dialog("Renew certificate", null, f, "Renew", null)) return;
        List<String> a = new ArrayList<>(List.of("certbot", "renew", "--cert-name", c.name(), "--deploy-hook", "systemctl reload nginx"));
        if (force.isSelected()) a.add("--force-renewal");
        Ui.stream("Renew " + c.name(), h, a, true, code -> refresh());
    }

    private void renewAll() {
        Host h = host();
        Ui.stream("certbot renew", h, List.of("certbot", "renew", "--deploy-hook", "systemctl reload nginx"), true, c -> refresh());
    }

    private void autoRenew() {
        Host h = host();
        Ui.action("Enable certificate auto-renew", h.name(), () -> {
            CmdResult t = h.sudo("systemctl", "enable", "--now", "certbot.timer");
            if (t.ok()) return "certbot.timer enabled";
            String line = "17 3 * * * root certbot renew -q --deploy-hook 'systemctl reload nginx'\n";
            h.writeFileRoot("/etc/cron.d/serverpilotx-certbot", "# Managed by ServerPilotX\n" + line, "644");
            return "/etc/cron.d/serverpilotx-certbot";
        }, this::refresh);
    }

    private void details(Certs.Cert c) {
        Host h = host();
        Ui.bg(() -> h.sudo("openssl", "x509", "-in", c.certPath(), "-noout", "-text").orThrow(), out -> Ui.textDialog("Certificate " + c.name(), out));
    }

    private void useOnSite(Certs.Cert c) {
        Host h = host();
        Ui.bg(() -> {
            Nginx.Layout l = Nginx.layout(h);
            return h.sh("grep -l 'SPX-META' " + l.availableDir() + "/* 2>/dev/null; true").lines();
        }, files -> {
            if (files.length == 0) { Ui.toast("No ServerPilotX-managed sites found", Ui.Kind.INFO); return; }
            ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(files));
            box.setValue(files[0]);
            if (!Ui.dialog("Use certificate on site", null, new Ui.Form().row("Site config", box), "Apply", null)) return;
            Ui.action("Install certificate on site", box.getValue(), () -> {
                SitesView.attachCert(h, box.getValue(), c.certPath(), c.keyPath());
                return box.getValue();
            }, null);
        });
    }

    private void delete(Certs.Cert c) {
        Host h = host();
        if (c.source().equals("Nginx")) { Ui.toast("This certificate is referenced by an Nginx config; remove it there.", Ui.Kind.INFO); return; }
        if (!Ui.confirm("Delete certificate", "Delete " + c.name() + "? Sites still using it will fail nginx -t.", "Delete", true)) return;
        Ui.action("Delete certificate", c.name(), () -> {
            if (c.source().equals("ACME")) return h.sudo("certbot", "delete", "--non-interactive", "--cert-name", c.name()).orThrow();
            h.sudo("rm", "-rf", "--", Certs.CUSTOM_DIR + "/" + c.name()).orThrow();
            return c.certPath();
        }, this::refresh);
    }

    private void selfSigned() {
        TextField name = new TextField();
        name.setPromptText("internal-service");
        TextField doms = new TextField();
        doms.setPromptText("service.lan 10.0.0.5");
        Spinner<Integer> days = new Spinner<>(1, 3650, 825);
        days.setEditable(true);
        ComboBox<String> key = new ComboBox<>(FXCollections.observableArrayList("ECDSA P-256", "ECDSA P-384", "RSA-2048", "RSA-4096"));
        key.setValue("ECDSA P-256");
        Ui.Form f = new Ui.Form().row("Name", name).row("Domains / IPs", doms).row("Valid days", days).row("Key type", key);
        if (!Ui.dialog("Self-signed certificate", "Generate a self-signed certificate", f, "Generate", () -> {
            if (!name.getText().matches("[A-Za-z0-9._-]+")) return "Invalid name";
            if (Nginx.parseDomains(doms.getText()).isEmpty()) return "At least one domain or IP";
            return null;
        })) return;
        Host h = host();
        Ui.action("Generate self-signed certificate", name.getText(), () ->
                Certs.selfSigned(h, name.getText(), Nginx.parseDomains(doms.getText()), days.getValue(), key.getValue().replace("ECDSA ", "")), this::refresh);
    }

    private void upload() {
        TextField name = new TextField();
        TextField cert = new TextField();
        TextField key = new TextField();
        Button bc = Ui.button("Browse…", "folder", e -> pick(cert));
        Button bk = Ui.button("Browse…", "folder", e -> pick(key));
        HBox rc = new HBox(8, cert, bc);
        HBox rk = new HBox(8, key, bk);
        HBox.setHgrow(cert, Priority.ALWAYS);
        HBox.setHgrow(key, Priority.ALWAYS);
        Ui.Form f = new Ui.Form().row("Name", name).row("Certificate (PEM)", rc, "Full chain: leaf certificate first").row("Private key (PEM)", rk);
        if (!Ui.dialog("Upload certificate", null, f, "Upload", () -> {
            if (!name.getText().matches("[A-Za-z0-9._-]+")) return "Invalid name";
            if (!new File(cert.getText()).isFile() || !new File(key.getText()).isFile()) return "Choose both files";
            return null;
        })) return;
        Host h = host();
        Ui.action("Upload certificate", name.getText(), () -> {
            Certs.upload(h, name.getText(), Files.readString(new File(cert.getText()).toPath()), Files.readString(new File(key.getText()).toPath()));
            return name.getText();
        }, this::refresh);
    }

    private static void pick(TextField target) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("PEM files", "*.pem", "*.crt", "*.key", "*.cer"),
                new FileChooser.ExtensionFilter("All files", "*"));
        File f = fc.showOpenDialog(Ui.owner());
        if (f != null) target.setText(f.getAbsolutePath());
    }

    // ------------------------------------------------------------ DNS providers

    /** [display name, ini path, certbot plugin] */
    private static List<String[]> listProviders(Host h) {
        List<String[]> out = new ArrayList<>();
        for (String line : h.sudoSh("ls -1 " + Certs.DNS_DIR + "/*.ini 2>/dev/null; true").lines()) {
            String file = line.strip();
            if (file.isEmpty()) continue;
            String base = file.substring(file.lastIndexOf('/') + 1).replace(".ini", "");
            String plugin = "dns-" + base.split("-")[0];
            out.add(new String[]{base, file, plugin});
        }
        return out;
    }

    private void loadProviders() {
        Host h = host();
        Ui.bg(() -> listProviders(h), l -> dnsTable.setItems(FXCollections.observableArrayList(l)));
    }

    private void addProvider() {
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("cloudflare", "digitalocean", "rfc2136"));
        type.setValue("cloudflare");
        TextField label = new TextField("main");
        PasswordField token = new PasswordField();
        TextField server = new TextField();
        server.setPromptText("RFC2136: DNS server IP");
        TextField keyName = new TextField();
        keyName.setPromptText("RFC2136: TSIG key name");
        Ui.Form f = new Ui.Form()
                .row("Provider", type, "Needs the matching certbot DNS plugin (installed automatically on apt/dnf)")
                .row("Label", label)
                .row("API token / TSIG secret", token)
                .row("Server", server)
                .row("Key name", keyName);
        if (!Ui.dialog("Add DNS provider", "Add DNS-01 provider", f, "Save", () -> token.getText().isBlank() ? "Token required" : null)) return;
        String t = type.getValue();
        String ini = switch (t) {
            case "cloudflare" -> "dns_cloudflare_api_token = " + token.getText().strip() + "\n";
            case "digitalocean" -> "dns_digitalocean_token = " + token.getText().strip() + "\n";
            default -> "dns_rfc2136_server = " + server.getText().strip() + "\ndns_rfc2136_port = 53\ndns_rfc2136_name = "
                    + keyName.getText().strip() + "\ndns_rfc2136_secret = " + token.getText().strip() + "\ndns_rfc2136_algorithm = HMAC-SHA512\n";
        };
        Host h = host();
        String path = Certs.DNS_DIR + "/" + t + "-" + label.getText().strip().replaceAll("[^A-Za-z0-9_]", "_") + ".ini";
        Ui.action("Add DNS provider", t, () -> {
            h.sudo("mkdir", "-p", Certs.DNS_DIR).orThrow();
            h.sudo("chmod", "700", Certs.DNS_DIR);
            h.writeFileRoot(path, ini, "600");
            String pkg = h.pkgManager().equals("apt") || h.pkgManager().equals("dnf") ? "python3-certbot-dns-" + t : null;
            if (pkg != null) h.sudoSh("command -v certbot >/dev/null && (certbot plugins 2>/dev/null | grep -q dns-" + t + " || "
                    + Quote.join(h.installArgv(pkg)) + ") ; true");
            AuditLog.log("DNS provider stored", path, "", true);
            return path;
        }, this::loadProviders);
    }

    static void requireCertbot(Host h) {
        if (!h.has("certbot")) throw new CmdException("certbot is not installed on " + h.name());
    }
}
