package io.serverpilotx.view;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Linux users, SSH authorized keys, sshd hardening and login history. */
public final class UsersView extends View {
    private static final String HARDEN_FILE = "/etc/ssh/sshd_config.d/00-serverpilotx.conf";
    private static final Pattern KEY = Pattern.compile("^(.*?\\s)?((?:ssh|ecdsa|sk)-[a-z0-9@.-]+)\\s+([A-Za-z0-9+/=]+)(?:\\s+(.*))?$");

    private final TableView<Map<String, Object>> users = new TableView<>();
    private final TableView<Map<String, Object>> keys = new TableView<>();
    private final TableView<Map<String, Object>> sessions = new TableView<>();
    private final TextArea lastLogins = new TextArea();
    private final ComboBox<String> keyUser = new ComboBox<>();
    private final VBox hardenBox = new VBox(14);
    private TabPane tabs;
    private List<String> rawKeyLines = new ArrayList<>();

    @Override public String id() { return "users"; }
    @Override public String title() { return "Users & SSH"; }
    @Override public String subtitle() { return "Accounts, keys & sshd hardening"; }
    @Override public String icon() { return "users"; }

    private static String s(Map<String, Object> m, String k) { return Json.str(m, k); }

    @Override
    protected Node build() {
        tabs = new TabPane(tab("Linux users", usersTab()), tab("SSH keys", keysTab()), tab("Hardening", hardenTab()), tab("Logins", loginsTab()));
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

    private void selUser(java.util.function.Consumer<Map<String, Object>> c) {
        Map<String, Object> m = users.getSelectionModel().getSelectedItem();
        if (m == null) Ui.toast("Select a user first", Ui.Kind.INFO);
        else c.accept(m);
    }

    // ============================================================ users

    private Node usersTab() {
        users.getColumns().add(Ui.col("User", m -> s(m, "name"), 140));
        users.getColumns().add(Ui.col("UID", m -> s(m, "uid"), 70));
        users.getColumns().add(Ui.badgeCol("Status", m -> s(m, "status"), v -> v.equals("Active") ? "green" : v.equals("Locked") ? "red" : "amber", 100));
        users.getColumns().add(Ui.badgeCol("Sudo", m -> s(m, "sudo"), v -> v.isEmpty() ? null : "purple", 80));
        users.getColumns().add(Ui.monoCol("Shell", m -> s(m, "shell"), 140));
        users.getColumns().add(Ui.monoCol("Home", m -> s(m, "home"), 180));
        users.getColumns().add(Ui.col("Groups", m -> s(m, "groups"), 260));
        users.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        users.setPlaceholder(Ui.label("No users", "muted"));

        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                mi("Set password…", "key", () -> selUser(this::setPassword)),
                mi("Change shell…", "terminal", () -> selUser(this::changeShell)),
                mi("Edit groups…", "users", () -> selUser(this::editGroups)),
                mi("Toggle sudo", "shield", () -> selUser(this::toggleSudo)),
                mi("Manage SSH keys", "key", () -> selUser(m -> { keyUser.setValue(s(m, "name")); tabs.getSelectionModel().select(1); })));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.primary("Create user", "plus", e -> createUser()), Ui.spacer(),
                Ui.button("Lock", "lock", e -> selUser(m -> lock(m, true))),
                Ui.button("Unlock", "check", e -> selUser(m -> lock(m, false))),
                more,
                Ui.danger("Delete", "trash", e -> selUser(this::deleteUser)));
        VBox v = new VBox(12, bar, users);
        VBox.setVgrow(users, Priority.ALWAYS);
        return v;
    }

    private void loadUsers() {
        Host h = host();
        Ui.bg(() -> {
            CmdResult r = h.sh("getent passwd | awk -F: '($3>=1000 && $3<60000) || $3==0 {print \"U:\"$1\":\"$3\":\"$6\":\"$7}';"
                    + " getent group | awk -F: '{print \"G:\"$1\":\"$4}'");
            List<Map<String, Object>> list = new ArrayList<>();
            Map<String, List<String>> groupsOf = new HashMap<>();
            for (String l : r.lines()) {
                if (!l.startsWith("G:")) continue;
                String[] p = l.split(":", -1);
                for (String u : p[2].split(",")) if (!u.isBlank()) groupsOf.computeIfAbsent(u, k -> new ArrayList<>()).add(p[1]);
            }
            StringBuilder names = new StringBuilder();
            for (String l : r.lines()) {
                if (!l.startsWith("U:")) continue;
                String[] p = l.split(":", -1);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p[1]);
                m.put("uid", p[2]);
                m.put("home", p[3]);
                m.put("shell", p[4]);
                List<String> g = groupsOf.getOrDefault(p[1], List.of());
                m.put("groups", String.join(", ", g));
                m.put("sudo", p[1].equals("root") ? "root" : g.contains("sudo") || g.contains("wheel") || g.contains("admin") ? "yes" : "");
                m.put("status", p[4].endsWith("nologin") || p[4].endsWith("false") ? "No login" : "Active");
                list.add(m);
                names.append(' ').append(Quote.sh(p[1]));
            }
            Map<String, String> lockMap = new HashMap<>();
            String[] statusLines;
            try {
                statusLines = h.sudoSh("for u in" + names + "; do passwd -S \"$u\" 2>/dev/null; done; true").lines();
            } catch (CmdException e) {
                statusLines = new String[0]; // locked status needs root; show the list anyway
            }
            for (String l : statusLines) {
                String[] f = l.strip().split("\\s+");
                if (f.length >= 2) lockMap.put(f[0], f[1]);
            }
            for (Map<String, Object> m : list) {
                String code = lockMap.get(s(m, "name"));
                if (code != null && (code.equals("L") || code.equals("LK"))) m.put("status", "Locked");
            }
            return list;
        }, l -> {
            users.setItems(FXCollections.observableArrayList(l));
            String cur = keyUser.getValue();
            keyUser.getItems().setAll(l.stream().map(m -> s(m, "name")).toList());
            if (cur != null && keyUser.getItems().contains(cur)) keyUser.setValue(cur);
            else if (keyUser.getItems().contains(host().user())) keyUser.setValue(host().user());
        });
    }

    private void createUser() {
        TextField name = new TextField();
        PasswordField pw = new PasswordField();
        pw.setPromptText("Optional — leave empty for key-only login");
        ComboBox<String> shell = new ComboBox<>(FXCollections.observableArrayList("/bin/bash", "/bin/sh", "/usr/bin/zsh", "/usr/sbin/nologin"));
        shell.setValue("/bin/bash");
        TextField groups = new TextField();
        groups.setPromptText("docker, www-data");
        CheckBox sudo = new CheckBox("Grant sudo (administrator)");
        CheckBox nopass = new CheckBox("sudo without password (NOPASSWD)");
        nopass.disableProperty().bind(sudo.selectedProperty().not());
        TextArea key = new TextArea();
        key.setPromptText("ssh-ed25519 AAAA… user@laptop  (optional)");
        key.setPrefRowCount(3);
        Ui.Form f = new Ui.Form().row("Username", name).row("Password", pw).row("Shell", shell).row("Extra groups", groups)
                .full(sudo).full(nopass).row("SSH public key", key);
        if (!Ui.dialog("Create user", "Create Linux user", f, "Create", () -> {
            if (!name.getText().matches("[a-z_][a-z0-9_-]{0,31}")) return "Invalid username";
            if (!key.getText().isBlank() && !KEY.matcher(key.getText().strip()).matches()) return "SSH key format not recognised";
            return null;
        })) return;
        Host h = host();
        String u = name.getText();
        Ui.action("Create user", u, () -> {
            List<String> a = new ArrayList<>(List.of("useradd", "-m", "-s", shell.getValue()));
            List<String> gs = new ArrayList<>();
            for (String g : groups.getText().split("[,\\s]+")) if (!g.isBlank()) gs.add(g);
            if (sudo.isSelected()) gs.add(sudoGroup(h));
            if (!gs.isEmpty()) { a.add("-G"); a.add(String.join(",", gs)); }
            a.add(u);
            h.sudo(a, Host.DEFAULT_TIMEOUT).orThrow();
            if (!pw.getText().isEmpty()) setPasswordNow(h, u, pw.getText());
            if (sudo.isSelected() && nopass.isSelected()) {
                h.writeFileRoot("/etc/sudoers.d/spx-" + u, u + " ALL=(ALL) NOPASSWD:ALL\n", "440");
                CmdResult v = h.sudo("visudo", "-cf", "/etc/sudoers.d/spx-" + u);
                if (!v.ok()) { h.sudo("rm", "-f", "/etc/sudoers.d/spx-" + u); throw new CmdException("sudoers validation failed: " + v.text()); }
            }
            if (!key.getText().isBlank()) writeKeys(h, u, key.getText().strip() + "\n");
            return u;
        }, this::loadUsers);
    }

    private static String sudoGroup(Host h) {
        return h.run("getent", "group", "sudo").ok() ? "sudo" : "wheel";
    }

    private static void setPasswordNow(Host h, String user, String pw) {
        String hash = h.runIn(pw + "\n", "openssl", "passwd", "-6", "-stdin").orThrow().strip();
        h.sudo("usermod", "-p", hash, user).orThrow();
    }

    private void setPassword(Map<String, Object> m) {
        PasswordField a = new PasswordField();
        PasswordField b = new PasswordField();
        Ui.Form f = new Ui.Form().row("New password", a).row("Repeat", b);
        if (!Ui.dialog("Set password", "Set password for " + s(m, "name"), f, "Set password", () -> {
            if (a.getText().length() < 8) return "Use at least 8 characters";
            if (!a.getText().equals(b.getText())) return "Passwords do not match";
            return null;
        })) return;
        Host h = host();
        Ui.action("Set password", s(m, "name"), () -> { setPasswordNow(h, s(m, "name"), a.getText()); return "password changed"; }, this::loadUsers);
    }

    private void changeShell(Map<String, Object> m) {
        ComboBox<String> shell = new ComboBox<>(FXCollections.observableArrayList("/bin/bash", "/bin/sh", "/usr/bin/zsh", "/usr/bin/fish", "/usr/sbin/nologin"));
        shell.setEditable(true);
        shell.setValue(s(m, "shell"));
        if (!Ui.dialog("Change shell", null, new Ui.Form().row("Shell", shell), "Save", null)) return;
        Host h = host();
        Ui.action("Change shell", s(m, "name") + " → " + shell.getValue(), () -> h.sudo("usermod", "-s", shell.getValue(), s(m, "name")).orThrow(), this::loadUsers);
    }

    private void editGroups(Map<String, Object> m) {
        TextField g = new TextField(s(m, "groups"));
        if (!Ui.dialog("Edit groups", "Supplementary groups of " + s(m, "name"), new Ui.Form().row("Groups", g, "Comma separated. Replaces the current list."), "Save", null)) return;
        Host h = host();
        String list = String.join(",", g.getText().split("[,\\s]+"));
        Ui.action("Edit groups", s(m, "name"), () -> h.sudo("usermod", "-G", list, s(m, "name")).orThrow(), this::loadUsers);
    }

    private void toggleSudo(Map<String, Object> m) {
        String u = s(m, "name");
        if (u.equals("root")) return;
        boolean has = s(m, "sudo").equals("yes");
        Host h = host();
        if (has && u.equals(h.user()) && !Ui.confirm("Remove your own sudo?", "You are connected as " + u + ". Removing sudo may lock you out of administration.", "Remove anyway", true)) return;
        Ui.action(has ? "Remove sudo" : "Grant sudo", u, () -> {
            String g = sudoGroup(h);
            if (has) { h.sudo("gpasswd", "-d", u, g).orThrow(); h.sudo("rm", "-f", "/etc/sudoers.d/spx-" + u); }
            else h.sudo("usermod", "-aG", g, u).orThrow();
            return g;
        }, this::loadUsers);
    }

    private void lock(Map<String, Object> m, boolean lock) {
        String u = s(m, "name");
        if (lock && !Ui.confirm("Lock user", "Lock " + u + "? Password login is disabled and the account expires (SSH keys stop working too).", "Lock", true)) return;
        Host h = host();
        Ui.action(lock ? "Lock user" : "Unlock user", u, () -> {
            h.sudo("usermod", lock ? "-L" : "-U", u).orThrow();
            return h.sudo("chage", "-E", lock ? "0" : "-1", u).orThrow();
        }, this::loadUsers);
    }

    private void deleteUser(Map<String, Object> m) {
        String u = s(m, "name");
        if (u.equals("root") || u.equals(host().user())) { Ui.toast("Refusing to delete root or the user you are connected as", Ui.Kind.ERROR); return; }
        CheckBox home = new CheckBox("Also delete home directory and mail spool");
        Ui.Form f = new Ui.Form().full(Ui.label("Delete user " + u + "?")).full(home);
        if (!Ui.dialog("Delete user", null, f, "Delete", null)) return;
        Host h = host();
        Ui.action("Delete user", u, () -> {
            h.sudo("rm", "-f", "/etc/sudoers.d/spx-" + u);
            return home.isSelected() ? h.sudo("userdel", "-r", u).orThrow() : h.sudo("userdel", u).orThrow();
        }, this::loadUsers);
    }

    // ============================================================ ssh keys

    private Node keysTab() {
        keys.getColumns().add(Ui.col("Label", m -> s(m, "comment"), 200));
        keys.getColumns().add(Ui.col("Type", m -> s(m, "type"), 120));
        keys.getColumns().add(Ui.monoCol("Fingerprint", m -> s(m, "fp"), 340));
        keys.getColumns().add(Ui.badgeCol("Expires", m -> s(m, "expires"), v -> v.equals("never") ? null : v.startsWith("expired") ? "red" : "amber", 120));
        keys.getColumns().add(Ui.col("Last used", m -> s(m, "lastUsed"), 170));
        keys.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        keys.setPlaceholder(Ui.label("No authorized keys for this user", "muted"));
        keyUser.setOnAction(e -> { if (tabs != null && tabs.getSelectionModel().getSelectedIndex() == 1) loadKeys(); });
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.label("User", "muted"), keyUser, Ui.primary("Add key", "plus", e -> addKey()), Ui.spacer(),
                Ui.button("Edit raw", "edit", e -> editRawKeys()),
                Ui.danger("Remove key", "trash", e -> removeKey()));
        Label hint = Ui.label("Expiry uses OpenSSH's expiry-time option. Last used is read from the SSH journal (last 90 days).", "faint", "small");
        VBox v = new VBox(12, bar, keys, hint);
        VBox.setVgrow(keys, Priority.ALWAYS);
        return v;
    }

    private static String home(Host h, String user) {
        String line = h.run("getent", "passwd", user).out().strip();
        String[] p = line.split(":");
        if (p.length < 6) throw new CmdException("Unknown user " + user);
        return p[5];
    }

    private static String readKeys(Host h, String user) {
        String path = home(h, user) + "/.ssh/authorized_keys";
        if (user.equals(h.user())) {
            CmdResult r = h.run("cat", path);
            return r.ok() ? r.out() : "";
        }
        CmdResult r = h.sudo("cat", path);
        return r.ok() ? r.out() : "";
    }

    private static void writeKeys(Host h, String user, String content) {
        String home = home(h, user);
        String dir = home + "/.ssh";
        String path = dir + "/authorized_keys";
        if (user.equals(h.user()) && !h.isRoot()) {
            h.run("mkdir", "-p", dir).orThrow();
            h.run("chmod", "700", dir);
            h.writeFile(path, content);
            h.run("chmod", "600", path);
            return;
        }
        String group = h.run("id", "-gn", user).out().strip();
        h.sudo("install", "-d", "-m", "700", "-o", user, "-g", group, dir).orThrow();
        h.writeFileRoot(path, content, "600");
        h.sudo("chown", user + ":" + group, path).orThrow();
    }

    static String fingerprint(String base64) {
        try {
            byte[] blob = Base64.getDecoder().decode(base64);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            return "invalid key";
        }
    }

    private void loadKeys() {
        String user = keyUser.getValue();
        if (user == null) return;
        Host h = host();
        Ui.bg(() -> {
            String content = readKeys(h, user);
            CmdResult j = h.sudoSh("(journalctl -q --no-pager -o short-iso --since '-90 days' -t sshd -t sshd-session 2>/dev/null;"
                    + " cat /var/log/auth.log /var/log/secure 2>/dev/null) | grep 'Accepted publickey for " + user.replace("'", "") + " ' | tail -n 2000");
            Map<String, String> last = new HashMap<>();
            for (String l : j.lines()) {
                Matcher m = Pattern.compile("(SHA256:[A-Za-z0-9+/]+)").matcher(l);
                if (m.find()) last.put(m.group(1), l.split("\\s+")[0].replace('T', ' ').replaceAll("\\+.*", ""));
            }
            List<String> lines = new ArrayList<>(List.of(content.split("\\R")));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                Matcher m = KEY.matcher(l);
                if (!m.matches()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                String opts = m.group(1) == null ? "" : m.group(1);
                row.put("line", i);
                row.put("type", m.group(2));
                row.put("fp", fingerprint(m.group(3)));
                row.put("comment", m.group(4) == null ? "" : m.group(4));
                Matcher ex = Pattern.compile("expiry-time=\"?(\\d{8})").matcher(opts);
                if (ex.find()) {
                    LocalDate d = LocalDate.parse(ex.group(1), DateTimeFormatter.BASIC_ISO_DATE);
                    row.put("expires", d.isBefore(LocalDate.now()) ? "expired " + d : d.toString());
                } else row.put("expires", "never");
                row.put("lastUsed", last.getOrDefault(s(row, "fp"), "—"));
                rows.add(row);
            }
            return new Object[]{lines, rows};
        }, res -> {
            @SuppressWarnings("unchecked") List<String> lines = (List<String>) res[0];
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) res[1];
            rawKeyLines = lines;
            keys.setItems(FXCollections.observableArrayList(rows));
        });
    }

    private void addKey() {
        String user = keyUser.getValue();
        if (user == null) return;
        TextArea key = new TextArea();
        key.setPromptText("ssh-ed25519 AAAAC3Nza… you@laptop");
        key.setPrefRowCount(4);
        key.setWrapText(true);
        TextField label = new TextField();
        label.setPromptText("laptop (replaces the key comment)");
        DatePicker expiry = new DatePicker();
        expiry.setPromptText("No expiry");
        Ui.Form f = new Ui.Form().row("Public key", key).row("Label", label).row("Expires", expiry, "Optional. OpenSSH refuses the key after this date.");
        if (!Ui.dialog("Add SSH key", "Add authorized key for " + user, f, "Add key", () ->
                KEY.matcher(key.getText().strip().replace("\n", "")).matches() ? null : "Not a valid OpenSSH public key")) return;
        Matcher m = KEY.matcher(key.getText().strip().replace("\n", ""));
        m.matches();
        StringBuilder line = new StringBuilder();
        if (expiry.getValue() != null) line.append("expiry-time=\"").append(expiry.getValue().format(DateTimeFormatter.BASIC_ISO_DATE)).append("\" ");
        else if (m.group(1) != null) line.append(m.group(1));
        line.append(m.group(2)).append(' ').append(m.group(3));
        String comment = label.getText().isBlank() ? (m.group(4) == null ? "" : m.group(4)) : label.getText().strip();
        if (!comment.isEmpty()) line.append(' ').append(comment);
        Host h = host();
        Ui.action("Add SSH key", user + " · " + comment, () -> {
            String cur = readKeys(h, user);
            String fp = fingerprint(m.group(3));
            for (String l : cur.split("\\R")) {
                Matcher x = KEY.matcher(l.strip());
                if (x.matches() && fingerprint(x.group(3)).equals(fp)) throw new CmdException("This key is already authorized for " + user);
            }
            String next = (cur.isBlank() ? "" : cur.stripTrailing() + "\n") + line + "\n";
            writeKeys(h, user, next);
            return fp;
        }, this::loadKeys);
    }

    private void removeKey() {
        Map<String, Object> k = keys.getSelectionModel().getSelectedItem();
        if (k == null) { Ui.toast("Select a key first", Ui.Kind.INFO); return; }
        String user = keyUser.getValue();
        Host h = host();
        if (user.equals(h.user()) && keys.getItems().size() == 1 && !Ui.confirm("Remove your last key?",
                "This is the only key for " + user + ", the user ServerPilotX connects as. You may lose SSH access.", "Remove anyway", true)) return;
        if (!Ui.confirm("Remove key", "Remove " + s(k, "type") + " " + s(k, "comment") + " (" + s(k, "fp") + ") from " + user + "?", "Remove", true)) return;
        int idx = ((Number) k.get("line")).intValue();
        List<String> lines = new ArrayList<>(rawKeyLines);
        String fp = s(k, "fp");
        Ui.action("Remove SSH key", user + " · " + s(k, "comment"), () -> {
            if (idx < lines.size()) lines.remove(idx);
            String content = String.join("\n", lines).strip();
            writeKeys(h, user, content.isEmpty() ? "" : content + "\n");
            return fp;
        }, this::loadKeys);
    }

    private void editRawKeys() {
        String user = keyUser.getValue();
        if (user == null) return;
        Host h = host();
        Ui.bg(() -> readKeys(h, user), cur -> Ui.editor("authorized_keys · " + user, "One key per line. Options such as from=\"…\" and expiry-time=\"YYYYMMDD\" are supported.", cur, text -> {
            writeKeys(h, user, text.endsWith("\n") || text.isEmpty() ? text : text + "\n");
            AuditLog.log("Edit authorized_keys", user, "", true);
            Ui.fx(this::loadKeys);
            return "Saved authorized_keys";
        }));
    }

    // ============================================================ hardening

    private Node hardenTab() {
        ScrollPane sp = new ScrollPane(hardenBox);
        sp.setFitToWidth(true);
        return sp;
    }

    private void loadHarden() {
        Host h = host();
        Ui.bg(() -> {
            Map<String, String> eff = new HashMap<>();
            for (String l : h.sudo("sshd", "-T").lines()) {
                int i = l.indexOf(' ');
                if (i > 0) eff.put(l.substring(0, i), l.substring(i + 1).strip());
            }
            boolean include = h.sh("grep -qE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config.d' /etc/ssh/sshd_config").ok()
                    || h.sudo("grep", "-qE", "^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config.d", "/etc/ssh/sshd_config").ok();
            return new Object[]{eff, include};
        }, res -> {
            @SuppressWarnings("unchecked") Map<String, String> eff = (Map<String, String>) res[0];
            boolean include = (Boolean) res[1];
            buildHardenForm(eff, include);
        });
    }

    private void buildHardenForm(Map<String, String> eff, boolean include) {
        hardenBox.getChildren().clear();
        Host h = host();
        CheckBox noPassword = new CheckBox("Disable password authentication (keys only)");
        noPassword.setSelected("no".equals(eff.get("passwordauthentication")));
        ComboBox<String> rootLogin = new ComboBox<>(FXCollections.observableArrayList("no", "prohibit-password", "yes"));
        rootLogin.setValue(eff.getOrDefault("permitrootlogin", "prohibit-password").replace("without-password", "prohibit-password"));
        Spinner<Integer> port = new Spinner<>(1, 65535, parse(eff.get("port"), 22));
        port.setEditable(true);
        TextField allow = new TextField(eff.getOrDefault("allowusers", ""));
        allow.setPromptText("Empty = all users. e.g. deploy admin");
        Spinner<Integer> tries = new Spinner<>(1, 20, parse(eff.get("maxauthtries"), 6));
        Spinner<Integer> alive = new Spinner<>(0, 3600, parse(eff.get("clientaliveinterval"), 0));
        alive.setEditable(true);
        CheckBox x11 = new CheckBox("Disable X11 forwarding");
        x11.setSelected("no".equals(eff.get("x11forwarding")));

        Ui.Form f = new Ui.Form()
                .full(noPassword)
                .row("Root login", rootLogin, "prohibit-password = root may log in with keys only")
                .row("SSH port", port, "If UFW is active the new port is allowed automatically")
                .row("AllowUsers", allow)
                .row("MaxAuthTries", tries)
                .row("ClientAliveInterval", alive, "Seconds; 0 = off. Keeps idle sessions alive / detects dead ones")
                .full(x11);
        VBox card = Ui.card("SSH hardening", Ui.label("Writes " + HARDEN_FILE + ", validates with sshd -t and reloads sshd. Existing sessions stay connected.", "muted"), f,
                Ui.toolbar(Ui.primary("Apply hardening", "shield", e -> applyHarden(h, eff, noPassword.isSelected(), rootLogin.getValue(), port.getValue(),
                        allow.getText().strip(), tries.getValue(), alive.getValue(), x11.isSelected()))));
        if (!include) hardenBox.getChildren().add(Ui.banner("red", "alert",
                "/etc/ssh/sshd_config has no 'Include /etc/ssh/sshd_config.d/*.conf' line — the hardening file would be ignored.",
                Ui.button("Add Include line", "plus", e -> Ui.action("Add sshd Include", h.name(), () -> {
                    String cur = h.sudo("cat", "/etc/ssh/sshd_config").orThrow();
                    h.writeFileRoot("/etc/ssh/sshd_config", "Include /etc/ssh/sshd_config.d/*.conf\n" + cur, "644");
                    h.sudo("mkdir", "-p", "/etc/ssh/sshd_config.d");
                    return h.sudo("sshd", "-t").orThrow();
                }, this::loadHarden))));
        VBox current = Ui.card("Effective configuration (sshd -T)");
        for (String k : List.of("port", "passwordauthentication", "permitrootlogin", "pubkeyauthentication", "maxauthtries", "clientaliveinterval", "allowusers", "x11forwarding")) {
            HBox r = new HBox(10, Ui.label(k, "muted"), Ui.spacer(), Ui.label(eff.getOrDefault(k, "—"), "mono"));
            current.getChildren().add(r);
        }
        hardenBox.getChildren().addAll(card, current);
    }

    private static int parse(String s, int d) {
        try { return Integer.parseInt(s.strip()); } catch (Exception e) { return d; }
    }

    private void applyHarden(Host h, Map<String, String> eff, boolean noPw, String root, int port, String allow, int tries, int alive, boolean noX11) {
        StringBuilder b = new StringBuilder("# Managed by ServerPilotX — SSH hardening\n");
        b.append("PasswordAuthentication ").append(noPw ? "no" : "yes").append('\n');
        b.append("KbdInteractiveAuthentication ").append(noPw ? "no" : "yes").append('\n');
        b.append("PermitRootLogin ").append(root).append('\n');
        b.append("Port ").append(port).append('\n');
        if (!allow.isBlank()) b.append("AllowUsers ").append(allow).append('\n');
        b.append("MaxAuthTries ").append(tries).append('\n');
        if (alive > 0) b.append("ClientAliveInterval ").append(alive).append("\nClientAliveCountMax 3\n");
        if (noX11) b.append("X11Forwarding no\n");
        int oldPort = parse(eff.get("port"), 22);
        String warn = "";
        if (noPw) warn += "• Password login will be disabled — make sure your SSH key works.\n";
        if (!allow.isBlank() && !List.of(allow.split("\\s+")).contains(h.user())) warn += "• " + h.user() + " (you) is not in AllowUsers.\n";
        if (port != oldPort) warn += "• SSH moves from port " + oldPort + " to " + port + ".\n";
        if (!Ui.confirm("Apply SSH hardening", (warn.isEmpty() ? "" : warn + "\n") + "Apply these settings on " + h.name() + "?", "Apply", !warn.isEmpty())) return;
        Ui.bg(() -> {
            String old = h.exists(HARDEN_FILE) ? h.sudo("cat", HARDEN_FILE).out() : null;
            if (port != oldPort && h.has("ufw") && h.sudo("ufw", "status").out().contains("Status: active")) {
                h.sudo("ufw", "allow", port + "/tcp", "comment", "ssh").orThrow();
            }
            h.sudo("mkdir", "-p", "/etc/ssh/sshd_config.d");
            h.writeFileRoot(HARDEN_FILE, b.toString(), "644");
            CmdResult t = h.sudo("sshd", "-t");
            if (!t.ok()) {
                restore(h, old);
                throw new CmdException("sshd -t failed — previous configuration restored.\n\n" + t.text());
            }
            reloadSsh(h, port != oldPort);
            if (!h.isLocal()) {
                Host probe = new Host("probe", h.name(), h.address(), h.user(), port, h.identityFile());
                if (!probe.probeFreshConnection()) {
                    restore(h, old);
                    reloadSsh(h, port != oldPort);
                    throw new CmdException("A new SSH connection failed after the change, so it was rolled back. Check your keys and AllowUsers.");
                }
            }
            return port != oldPort;
        }, portChanged -> {
            AuditLog.log("SSH hardening", h.name(), b.toString().replace('\n', ' '), true);
            Ui.toast("SSH hardening applied", Ui.Kind.OK);
            if (portChanged && !h.isLocal()) {
                Ui.toast("SSH now listens on port " + port + ". Update this server's port under Servers.", Ui.Kind.INFO);
            }
            loadHarden();
        }, err -> {
            AuditLog.log("SSH hardening", h.name(), err, false);
            Ui.errorDialog("SSH hardening failed", err);
        });
    }

    private static void restore(Host h, String old) {
        if (old == null) h.sudo("rm", "-f", HARDEN_FILE);
        else h.writeFileRoot(HARDEN_FILE, old, "644");
    }

    private static void reloadSsh(Host h, boolean portChanged) {
        if (portChanged && h.sudoSh("systemctl is-active -q ssh.socket").ok()) {
            h.sudoSh("systemctl daemon-reload && systemctl restart ssh.socket").orThrow();
            return;
        }
        CmdResult r = h.sudoSh("systemctl reload ssh 2>/dev/null || systemctl reload sshd");
        if (!r.ok()) throw new CmdException("Could not reload sshd: " + r.text());
    }

    // ============================================================ logins

    private Node loginsTab() {
        sessions.getColumns().add(Ui.col("User", m -> s(m, "user"), 130));
        sessions.getColumns().add(Ui.col("Terminal", m -> s(m, "tty"), 110));
        sessions.getColumns().add(Ui.col("Since", m -> s(m, "since"), 170));
        sessions.getColumns().add(Ui.monoCol("From", m -> s(m, "from"), 200));
        sessions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sessions.setPrefHeight(180);
        sessions.setPlaceholder(Ui.label("No active sessions", "muted"));
        lastLogins.setEditable(false);
        lastLogins.getStyleClass().add("console");
        VBox v = new VBox(10, Ui.label("Active sessions", "h3"), sessions, Ui.label("Recent logins (last)", "h3"), lastLogins,
                Ui.toolbar(Ui.button("Failed logins (lastb)", "alert", e -> {
                    Host h = host();
                    Ui.bg(() -> h.sudo("lastb", "-n", "200", "-w").text(), out -> Ui.textDialog("Failed logins", out));
                })));
        VBox.setVgrow(lastLogins, Priority.ALWAYS);
        return v;
    }

    private void loadLogins() {
        Host h = host();
        Ui.bg(() -> new CmdResult[]{h.run("who", "-u"), h.sh("last -n 100 -w -F 2>/dev/null || last -n 100")}, r -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String l : r[0].lines()) {
                String[] f = l.strip().split("\\s+");
                if (f.length < 4) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("user", f[0]);
                m.put("tty", f[1]);
                m.put("since", f[2] + " " + f[3]);
                Matcher fm = Pattern.compile("\\(([^)]*)\\)").matcher(l);
                m.put("from", fm.find() ? fm.group(1) : "local");
                rows.add(m);
            }
            sessions.setItems(FXCollections.observableArrayList(rows));
            lastLogins.setText(r[1].text());
        });
    }

    @Override
    public void refresh() {
        if (!built()) return;
        switch (tabs.getSelectionModel().getSelectedIndex()) {
            case 0 -> loadUsers();
            case 1 -> { if (keyUser.getItems().isEmpty()) loadUsers(); else loadKeys(); }
            case 2 -> loadHarden();
            default -> loadLogins();
        }
    }

    @Override
    public void onHostChanged() {
        keyUser.getItems().clear();
        keyUser.setValue(null);
        super.onHostChanged();
    }
}
