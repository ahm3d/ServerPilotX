package io.serverpilotx.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.serverpilotx.core.Catalog;
import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.Docker;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Json;
import io.serverpilotx.core.Quote;
import io.serverpilotx.core.Settings;
import io.serverpilotx.ui.Icons;
import io.serverpilotx.ui.LogViewer;
import io.serverpilotx.ui.MainWindow;
import io.serverpilotx.ui.ResponsiveGrid;
import io.serverpilotx.ui.Terminals;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Docker: containers, compose stacks, images, volumes, networks and the app catalog. */
public final class DockerView extends View {
    private final StackPane root = new StackPane();
    private TabPane tabs;

    private final ObservableList<Map<String, Object>> containers = FXCollections.observableArrayList();
    private final FilteredList<Map<String, Object>> filteredContainers = new FilteredList<>(containers);
    private final TableView<Map<String, Object>> containerTable = new TableView<>(filteredContainers);
    private final TableView<Map<String, Object>> stackTable = new TableView<>();
    private final TableView<Map<String, Object>> imageTable = new TableView<>();
    private final TableView<Map<String, Object>> volumeTable = new TableView<>();
    private final TableView<Map<String, Object>> networkTable = new TableView<>();

    @Override public String id() { return "docker"; }
    @Override public String title() { return "Docker"; }
    @Override public String subtitle() { return "Containers, stacks & images"; }
    @Override public String icon() { return "docker"; }

    @Override
    protected Node build() {
        tabs = new TabPane(
                tab("Containers", containersTab()),
                tab("Stacks", stacksTab()),
                tab("Images", imagesTab()),
                tab("Volumes", volumesTab()),
                tab("Networks", networksTab()),
                tab("App catalog", catalogTab()));
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> refresh());
        VBox page = new VBox(tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        page.getStyleClass().add("page");
        root.getChildren().add(page);
        return root;
    }

    private static Tab tab(String name, Node content) {
        Tab t = new Tab(name, content);
        t.setClosable(false);
        return t;
    }

    private static String s(Map<String, Object> m, String k) { return Json.str(m, k); }

    private static String stateKind(String v) {
        String l = v.toLowerCase();
        if (l.startsWith("running") || l.equals("in sync")) return "green";
        if (l.startsWith("paused") || l.startsWith("restarting") || l.contains("drift") || l.startsWith("partial")) return "amber";
        if (l.startsWith("exited") || l.startsWith("dead") || l.startsWith("unhealthy")) return "red";
        return null;
    }

    private Map<String, Object> selected(TableView<Map<String, Object>> t) {
        Map<String, Object> m = t.getSelectionModel().getSelectedItem();
        if (m == null) Ui.toast("Select a row first", Ui.Kind.INFO);
        return m;
    }

    private void withSel(TableView<Map<String, Object>> t, Consumer<Map<String, Object>> c) {
        Map<String, Object> m = selected(t);
        if (m != null) c.accept(m);
    }

    // ================================================================ containers

    private Node containersTab() {
        containerTable.getColumns().add(Ui.col("Name", m -> s(m, "Names"), 190));
        containerTable.getColumns().add(Ui.badgeCol("State", m -> s(m, "State"), DockerView::stateKind, 95));
        containerTable.getColumns().add(Ui.col("Image", m -> s(m, "Image"), 210));
        containerTable.getColumns().add(Ui.col("Status", m -> s(m, "Status"), 160));
        containerTable.getColumns().add(Ui.monoCol("Ports", m -> s(m, "Ports").replace(", ", "\n").replaceAll("\\[::]:[^\\n]*\\n?", "").strip(), 210));
        containerTable.getColumns().add(Ui.col("CPU", m -> s(m, "_cpu"), 70));
        containerTable.getColumns().add(Ui.col("Memory", m -> s(m, "_mem"), 150));
        containerTable.getColumns().add(Ui.col("Stack", m -> composeProject(m), 110));
        containerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        containerTable.setPlaceholder(Ui.label("No containers", "muted"));
        containerTable.setRowFactory(tv -> {
            TableRow<Map<String, Object>> r = new TableRow<>();
            r.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !r.isEmpty()) logs(r.getItem()); });
            return r;
        });

        TextField search = new TextField();
        search.setPromptText("Filter containers…");
        search.textProperty().addListener((o, a, q) -> filteredContainers.setPredicate(m ->
                q == null || q.isBlank() || (s(m, "Names") + " " + s(m, "Image")).toLowerCase().contains(q.toLowerCase())));
        CheckBox all = new CheckBox("Show stopped");
        all.setSelected(true);
        all.setOnAction(e -> filteredContainers.setPredicate(m -> all.isSelected() || "running".equals(s(m, "State"))));

        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                item("Pause", "pause", () -> withSel(containerTable, m -> simple("pause", m))),
                item("Unpause", "play", () -> withSel(containerTable, m -> simple("unpause", m))),
                item("Rename…", "edit", () -> withSel(containerTable, this::rename)),
                item("Inspect", "eye", () -> withSel(containerTable, this::inspect)),
                item("Run command…", "exec", () -> withSel(containerTable, this::runCommand)),
                item("Schedule weekly prune…", "clock", this::schedulePrune));

        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(search, all, Ui.spacer(),
                Ui.button("Start", "play", e -> withSel(containerTable, m -> simple("start", m))),
                Ui.button("Stop", "stop", e -> withSel(containerTable, m -> simple("stop", m))),
                Ui.button("Restart", "restart", e -> withSel(containerTable, m -> simple("restart", m))),
                Ui.button("Logs", "logs", e -> withSel(containerTable, this::logs)),
                Ui.button("Shell", "terminal", e -> withSel(containerTable, this::shell)),
                more,
                Ui.danger("Remove", "trash", e -> withSel(containerTable, this::remove)));
        HBox.setHgrow(search, Priority.SOMETIMES);
        VBox v = new VBox(12, bar, containerTable);
        VBox.setVgrow(containerTable, Priority.ALWAYS);
        return v;
    }

    private static MenuItem item(String text, String icon, Runnable r) {
        MenuItem mi = new MenuItem(text, Icons.of(icon, 14));
        mi.setOnAction(e -> r.run());
        return mi;
    }

    private static String composeProject(Map<String, Object> m) {
        String labels = s(m, "Labels");
        for (String kv : labels.split(",")) {
            if (kv.startsWith("com.docker.compose.project=")) return kv.substring(kv.indexOf('=') + 1);
        }
        return "";
    }

    private void loadContainers() {
        Host h = host();
        Ui.bg(() -> Docker.json(h, "ps", "-a", "--no-trunc", "--format", "{{json .}}"), list -> {
            containers.setAll(list);
            loadStats(h);
        }, this::showProblem);
    }

    private void loadStats(Host h) {
        Ui.bg(() -> Docker.json(h, "stats", "--no-stream", "--format", "{{json .}}"), stats -> {
            Map<String, Map<String, Object>> byName = new HashMap<>();
            for (Map<String, Object> st : stats) byName.put(s(st, "Name"), st);
            for (Map<String, Object> c : containers) {
                Map<String, Object> st = byName.get(s(c, "Names"));
                if (st != null) {
                    c.put("_cpu", s(st, "CPUPerc"));
                    c.put("_mem", s(st, "MemUsage"));
                }
            }
            containerTable.refresh();
        }, err -> { });
    }

    private void simple(String verb, Map<String, Object> m) {
        String name = s(m, "Names");
        Host h = host();
        Ui.action("Container " + verb, name, () -> Docker.run(h, verb, name).orThrow(), this::loadContainers);
    }

    private void remove(Map<String, Object> m) {
        String name = s(m, "Names");
        CheckBox vols = new CheckBox("Also remove anonymous volumes");
        CheckBox force = new CheckBox("Force (stop if running)");
        force.setSelected("running".equals(s(m, "State")));
        Ui.Form f = new Ui.Form().full(Ui.label("Remove container " + name + "? This cannot be undone.")).full(force).full(vols);
        if (!Ui.dialog("Remove container", "Remove " + name, f, "Remove", null)) return;
        List<String> a = new ArrayList<>(List.of("rm"));
        if (force.isSelected()) a.add("-f");
        if (vols.isSelected()) a.add("-v");
        a.add(name);
        Host h = host();
        Ui.action("Remove container", name, () -> Docker.run(h, a.toArray(String[]::new)).orThrow(), this::loadContainers);
    }

    private void rename(Map<String, Object> m) {
        String name = s(m, "Names");
        Ui.prompt("Rename container", "New name", name).ifPresent(n -> {
            Host h = host();
            Ui.action("Rename container", name + " → " + n, () -> Docker.run(h, "rename", name, n).orThrow(), this::loadContainers);
        });
    }

    private void logs(Map<String, Object> m) {
        String name = s(m, "Names");
        Host h = host();
        LogViewer.open("Logs · " + name, h, Docker.needsRoot(h), (n, follow) -> {
            List<String> a = Docker.argv("logs", "--timestamps");
            if (n > 0) { a.add("--tail"); a.add(String.valueOf(n)); }
            if (follow) a.add("-f");
            a.add(name);
            return a;
        });
    }

    private void inspect(Map<String, Object> m) {
        String name = s(m, "Names");
        Host h = host();
        Ui.bg(() -> Docker.run(h, "inspect", name).orThrow(), out -> Ui.textDialog("Inspect · " + name, Docker.maskSecrets(out)));
    }

    private void shell(Map<String, Object> m) {
        String name = s(m, "Names");
        Host h = host();
        String docker = Docker.needsRoot(h) ? "sudo docker" : "docker";
        Terminals.open(h, "Shell · " + name, docker + " exec -it " + Quote.sh(name) + " sh -c 'command -v bash >/dev/null && exec bash || exec sh'");
    }

    private void runCommand(Map<String, Object> m) {
        String name = s(m, "Names");
        Ui.prompt("Run in " + name, "Command", "ls -la /").ifPresent(cmd -> {
            Host h = host();
            Ui.bg(() -> Docker.run(h, "exec", name, "sh", "-c", cmd), r -> Ui.textDialog(name + " $ " + cmd, r.text()));
        });
    }

    private void schedulePrune() {
        ComboBox<String> when = new ComboBox<>(FXCollections.observableArrayList("Weekly (Sunday 04:00)", "Daily (04:00)"));
        when.setValue("Weekly (Sunday 04:00)");
        ComboBox<String> keep = new ComboBox<>(FXCollections.observableArrayList("24h", "72h", "168h", "720h"));
        keep.setValue("168h");
        CheckBox volumes = new CheckBox("Also prune unused volumes (data loss risk)");
        Ui.Form f = new Ui.Form().row("Schedule", when).row("Keep newer than", keep, "Unused images/containers younger than this are kept").full(volumes);
        if (!Ui.dialog("Scheduled prune", "Scheduled Docker cleanup", f, "Save schedule", null)) return;
        String cron = (when.getValue().startsWith("Weekly") ? "0 4 * * 0" : "0 4 * * *")
                + " docker system prune -af --filter until=" + keep.getValue() + (volumes.isSelected() ? " --volumes" : "")
                + " >/dev/null 2>&1 # spx-docker-prune";
        Host h = host();
        Ui.action("Schedule docker prune", cron, () -> {
            String cur = h.sudo("crontab", "-l").out();
            StringBuilder b = new StringBuilder();
            for (String l : cur.split("\\R")) if (!l.contains("# spx-docker-prune") && !l.isBlank()) b.append(l).append('\n');
            b.append(cron).append('\n');
            String tmp = h.run("mktemp", "/tmp/spx.XXXXXXXX").orThrow().strip();
            h.writeFile(tmp, b.toString());
            try { h.sudo("crontab", tmp).orThrow(); } finally { h.run("rm", "-f", tmp); }
            return cron;
        }, null);
    }

    // ================================================================ stacks

    private Node stacksTab() {
        stackTable.getColumns().add(Ui.col("Stack", m -> s(m, "name"), 180));
        stackTable.getColumns().add(Ui.badgeCol("Status", m -> s(m, "status"), DockerView::stateKind, 130));
        stackTable.getColumns().add(Ui.badgeCol("Deployed", m -> s(m, "drift"), DockerView::stateKind, 150));
        stackTable.getColumns().add(Ui.monoCol("Compose file", m -> s(m, "file"), 380));
        stackTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        stackTable.setPlaceholder(Ui.label("No stacks yet — create one or deploy an app from the catalog", "muted"));

        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                item("Pull images", "download", () -> withSel(stackTable, m -> compose(m, "Pull", "pull"))),
                item("Restart", "restart", () -> withSel(stackTable, m -> compose(m, "Restart", "restart"))),
                item("Edit .env", "key", () -> withSel(stackTable, this::editEnv)),
                item("Logs", "logs", () -> withSel(stackTable, this::stackLogs)),
                item("Create Nginx site…", "globe", () -> withSel(stackTable, m -> SitesView.newSiteFor(s(m, "name") + ".example.com", "http://127.0.0.1:8080"))));
        javafx.scene.control.Label rootLbl = Ui.label("Stored in " + Settings.stacksRoot(), "muted", "small");
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.primary("New stack", "plus", e -> newStack()), rootLbl, Ui.spacer(),
                Ui.button("Deploy (up)", "rocket", e -> withSel(stackTable, m -> compose(m, "Deploy", "up", "-d", "--remove-orphans"))),
                Ui.button("Stop (down)", "stop", e -> withSel(stackTable, m -> compose(m, "Stop", "down"))),
                Ui.button("Edit", "edit", e -> withSel(stackTable, this::editStack)),
                more,
                Ui.danger("Delete", "trash", e -> withSel(stackTable, this::deleteStack)));
        VBox v = new VBox(12, bar, stackTable);
        VBox.setVgrow(stackTable, Priority.ALWAYS);
        return v;
    }

    private void loadStacks() {
        Host h = host();
        String root = Settings.stacksRoot();
        String script = "cd " + Quote.sh(root) + " 2>/dev/null || exit 0; for d in */; do n=${d%/};"
                + " f=$n/docker-compose.yml; [ -f \"$f\" ] || f=$n/compose.yml; [ -f \"$f\" ] || f=$n/compose.yaml; [ -f \"$f\" ] || f=$n/docker-compose.yaml; [ -f \"$f\" ] || continue;"
                + " h=$(sha256sum \"$f\" | cut -d' ' -f1); dep=$(cat \"$n/.spx-deployed\" 2>/dev/null); echo \"$n|$PWD/$f|$h|$dep\"; done";
        Ui.bg(() -> {
            CmdResult r = h.sh(script);
            Map<String, String> status = new HashMap<>();
            try {
                CmdResult ls = Docker.run(h, "compose", "ls", "-a", "--format", "json");
                if (ls.ok()) for (Object o : Json.arr(ls.out())) {
                    if (o instanceof Map<?, ?> mm) status.put(String.valueOf(mm.get("Name")), String.valueOf(mm.get("Status")));
                }
            } catch (RuntimeException ignored) { }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : r.lines()) {
                String[] p = line.split("\\|", -1);
                if (p.length < 4) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p[0]);
                m.put("file", p[1]);
                m.put("dir", p[1].substring(0, p[1].lastIndexOf('/')));
                m.put("status", status.getOrDefault(p[0].toLowerCase(), "not deployed"));
                m.put("drift", p[3].isEmpty() ? "never" : p[3].strip().equals(p[2]) ? "in sync" : "drift: file changed");
                m.put("hash", p[2]);
                rows.add(m);
            }
            return rows;
        }, rows -> stackTable.setItems(FXCollections.observableArrayList(rows)), this::showProblem);
    }

    private void ensureStacksRoot(Host h) {
        String root = Settings.stacksRoot();
        if (h.run("test", "-w", root).ok()) return;
        h.sudo("mkdir", "-p", root).orThrow();
        h.sudo("chown", h.user(), root).orThrow();
    }

    private List<String> composeArgv(Map<String, Object> m, String... args) {
        List<String> a = Docker.argv("compose", "-p", s(m, "name").toLowerCase(), "--project-directory", s(m, "dir"), "-f", s(m, "file"));
        a.addAll(List.of(args));
        return a;
    }

    private void compose(Map<String, Object> m, String label, String... args) {
        Host h = host();
        Ui.stream(label + " · " + s(m, "name"), h, composeArgv(m, args), Docker.needsRoot(h), code -> {
            if (code == 0 && args[0].equals("up")) {
                Ui.bg(() -> { h.writeFileAuto(s(m, "dir") + "/.spx-deployed", s(m, "hash") + "\n", "644"); return null; }, x -> loadStacks());
            } else {
                loadStacks();
            }
        });
    }

    private void stackLogs(Map<String, Object> m) {
        Host h = host();
        LogViewer.open("Stack logs · " + s(m, "name"), h, Docker.needsRoot(h), (n, f) -> {
            List<String> a = composeArgv(m, "logs", "--timestamps");
            if (n > 0) { a.add("--tail"); a.add(String.valueOf(n)); }
            if (f) a.add("-f");
            return a;
        });
    }

    private String validateCompose(Host h, String dir, String content) {
        String tmp = h.run("mktemp", "/tmp/spx-compose.XXXXXXXX.yml").orThrow().strip();
        try {
            h.writeFile(tmp, content);
            CmdResult r = Docker.run(h, "compose", "--project-directory", dir, "-f", tmp, "config", "-q");
            return r.ok() ? null : r.text();
        } finally {
            h.run("rm", "-f", tmp);
        }
    }

    private void editStack(Map<String, Object> m) {
        Host h = host();
        Ui.bg(() -> h.readFile(s(m, "file")), content -> Ui.editor("Edit " + s(m, "name") + "/" + s(m, "file").substring(s(m, "file").lastIndexOf('/') + 1),
                "Plain docker-compose file. It is validated with `docker compose config` before saving. Deploy again to apply.",
                content, text -> {
                    String err = validateCompose(h, s(m, "dir"), text);
                    if (err != null) throw new io.serverpilotx.core.CmdException("Invalid compose file:\n" + err);
                    h.writeFileAuto(s(m, "file"), text, "644");
                    io.serverpilotx.core.AuditLog.log("Edit stack", s(m, "name"), "compose file saved", true);
                    Ui.fx(this::loadStacks);
                    return "Saved " + s(m, "name");
                }));
    }

    private void editEnv(Map<String, Object> m) {
        Host h = host();
        String path = s(m, "dir") + "/.env";
        Ui.bg(() -> h.exists(path) ? h.readFile(path) : "", content -> Ui.editor("Edit " + s(m, "name") + "/.env",
                "KEY=value lines, read by docker compose for ${KEY} substitution.", content, text -> {
                    h.writeFileAuto(path, text, "600");
                    io.serverpilotx.core.AuditLog.log("Edit stack .env", s(m, "name"), "", true);
                    return "Saved .env";
                }));
    }

    private void deleteStack(Map<String, Object> m) {
        String name = s(m, "name");
        CheckBox vols = new CheckBox("Also delete the stack's named volumes (all data)");
        Ui.Form f = new Ui.Form().full(Ui.label("This runs `docker compose down` and deletes " + s(m, "dir") + ".")).full(vols);
        if (!Ui.dialog("Delete stack", "Delete stack " + name, f, "Delete", null)) return;
        Host h = host();
        Ui.action("Delete stack", name, () -> {
            List<String> down = composeArgv(m, "down");
            if (vols.isSelected()) down.add("-v");
            down.remove(0);
            Docker.run(h, down.toArray(String[]::new));
            CmdResult r = h.run("rm", "-rf", "--", s(m, "dir"));
            if (!r.ok()) h.sudo("rm", "-rf", "--", s(m, "dir")).orThrow();
            return "removed " + s(m, "dir");
        }, this::loadStacks);
    }

    private static final String SAMPLE_COMPOSE = """
            services:
              web:
                image: nginx:alpine
                restart: unless-stopped
                ports:
                  - "127.0.0.1:8080:80"
            """;

    private void newStack() {
        TextField name = new TextField();
        name.setPromptText("my-app");
        ToggleGroup src = new ToggleGroup();
        RadioButton fromEditor = new RadioButton("Write compose file");
        RadioButton fromGit = new RadioButton("Clone Git repository");
        fromEditor.setToggleGroup(src);
        fromGit.setToggleGroup(src);
        fromEditor.setSelected(true);
        TextArea compose = new TextArea(SAMPLE_COMPOSE);
        compose.getStyleClass().add("code-area");
        compose.setPrefRowCount(14);
        TextField git = new TextField();
        git.setPromptText("https://github.com/user/repo.git");
        TextField ref = new TextField("main");
        TextArea env = new TextArea();
        env.setPromptText("KEY=value");
        env.getStyleClass().add("code-area");
        env.setPrefRowCount(3);
        CheckBox deploy = new CheckBox("Deploy now");
        deploy.setSelected(true);
        compose.disableProperty().bind(fromGit.selectedProperty());
        git.disableProperty().bind(fromEditor.selectedProperty());
        ref.disableProperty().bind(fromEditor.selectedProperty());
        Ui.Form f = new Ui.Form()
                .row("Name", name, "Lowercase letters, digits, - and _")
                .row("Source", new HBox(16, fromEditor, fromGit))
                .row("docker-compose.yml", compose)
                .row("Git URL", git)
                .row("Branch / tag", ref)
                .row(".env", env)
                .full(deploy);
        Host h = host();
        if (!Ui.dialog("New stack", "Create a compose stack", f, "Create", () -> {
            if (!name.getText().matches("[a-z0-9][a-z0-9_-]*")) return "Invalid name";
            if (fromGit.isSelected() && git.getText().isBlank()) return "Git URL required";
            return null;
        })) return;
        String n = name.getText();
        String dir = Settings.stacksRoot() + "/" + n;
        Ui.bg(() -> {
            ensureStacksRoot(h);
            if (h.exists(dir)) throw new io.serverpilotx.core.CmdException("A stack named " + n + " already exists");
            if (fromGit.isSelected()) {
                h.exec(List.of("git", "clone", "--depth", "1", "--branch", ref.getText().strip(), git.getText().strip(), dir), null,
                        java.time.Duration.ofMinutes(5)).orThrow();
            } else {
                h.run("mkdir", "-p", dir).orThrow();
                h.writeFile(dir + "/docker-compose.yml", compose.getText());
            }
            if (!env.getText().isBlank()) h.writeFile(dir + "/.env", env.getText().strip() + "\n");
            io.serverpilotx.core.AuditLog.log("Create stack", n, fromGit.isSelected() ? git.getText() : "editor", true);
            return n;
        }, ok -> {
            loadStacks();
            if (deploy.isSelected()) deployDir(h, n, dir);
        });
    }

    private void deployDir(Host h, String name, String dir) {
        Ui.bg(() -> {
            String f = h.sh("cd " + Quote.sh(dir) + " && for f in docker-compose.yml compose.yml compose.yaml docker-compose.yaml; do [ -f $f ] && echo $PWD/$f && break; done").out().strip();
            String hash = h.run("sha256sum", f).out().split(" ")[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("dir", dir);
            m.put("file", f);
            m.put("hash", hash);
            return m;
        }, m -> compose(m, "Deploy", "up", "-d", "--remove-orphans"));
    }

    // ================================================================ images

    private Node imagesTab() {
        imageTable.getColumns().add(Ui.col("Repository", m -> s(m, "Repository"), 280));
        imageTable.getColumns().add(Ui.col("Tag", m -> s(m, "Tag"), 120));
        imageTable.getColumns().add(Ui.monoCol("ID", m -> s(m, "ID").replace("sha256:", "").substring(0, Math.min(12, s(m, "ID").replace("sha256:", "").length())), 120));
        imageTable.getColumns().add(Ui.col("Created", m -> s(m, "CreatedSince"), 130));
        imageTable.getColumns().add(Ui.col("Size", m -> s(m, "Size"), 100));
        imageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        imageTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Host[] hh = new Host[1];
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(
                Ui.primary("Pull image", "download", e -> Ui.prompt("Pull image", "Image", "nginx:alpine").ifPresent(img -> {
                    Host h = host();
                    Ui.stream("Pull " + img, h, Docker.argv("pull", img), Docker.needsRoot(h), c -> loadImages());
                })),
                Ui.spacer(),
                Ui.button("Disk usage", "chart", e -> { hh[0] = host(); Ui.bg(() -> Docker.run(hh[0], "system", "df", "-v").orThrow(), out -> Ui.textDialog("Docker disk usage", out)); }),
                Ui.button("Prune dangling", "trash", e -> prune("Prune dangling images", "image", "prune", "-f")),
                Ui.button("Prune unused", "trash", e -> prune("Prune all unused images", "image", "prune", "-a", "-f")),
                Ui.danger("Remove", "trash", e -> {
                    var sel = new ArrayList<>(imageTable.getSelectionModel().getSelectedItems());
                    if (sel.isEmpty()) { Ui.toast("Select images first", Ui.Kind.INFO); return; }
                    List<String> ids = sel.stream().map(m -> s(m, "Repository").equals("<none>") ? s(m, "ID") : s(m, "Repository") + ":" + s(m, "Tag")).toList();
                    if (!Ui.confirm("Remove images", "Remove " + String.join(", ", ids) + "?", "Remove", true)) return;
                    Host h = host();
                    List<String> a = new ArrayList<>(List.of("rmi"));
                    a.addAll(ids);
                    Ui.action("Remove images", String.join(" ", ids), () -> Docker.run(h, a.toArray(String[]::new)).orThrow(), this::loadImages);
                }));
        VBox v = new VBox(12, bar, imageTable);
        VBox.setVgrow(imageTable, Priority.ALWAYS);
        return v;
    }

    private void prune(String title, String... args) {
        if (!Ui.confirm(title, title + " on " + host().name() + "?", "Prune", true)) return;
        Host h = host();
        Ui.action(title, "", () -> Docker.run(h, args).orThrow(), () -> { loadImages(); loadVolumes(); });
    }

    private void loadImages() {
        Host h = host();
        Ui.bg(() -> Docker.json(h, "images", "--format", "{{json .}}"), l -> imageTable.setItems(FXCollections.observableArrayList(l)), this::showProblem);
    }

    // ================================================================ volumes

    private Node volumesTab() {
        volumeTable.getColumns().add(Ui.col("Name", m -> s(m, "Name"), 300));
        volumeTable.getColumns().add(Ui.col("Driver", m -> s(m, "Driver"), 90));
        volumeTable.getColumns().add(Ui.col("Stack", m -> {
            String l = s(m, "Labels");
            int i = l.indexOf("com.docker.compose.project=");
            return i < 0 ? "" : l.substring(i + 27).split(",")[0];
        }, 140));
        volumeTable.getColumns().add(Ui.monoCol("Mountpoint", m -> s(m, "Mountpoint"), 360));
        volumeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(
                Ui.primary("Create volume", "plus", e -> Ui.prompt("Create volume", "Name", "").ifPresent(n -> {
                    Host h = host();
                    Ui.action("Create volume", n, () -> Docker.run(h, "volume", "create", n).orThrow(), this::loadVolumes);
                })),
                Ui.spacer(),
                Ui.button("Browse files", "folder", e -> withSel(volumeTable, m -> {
                    Host h = host();
                    Ui.stream("Files in volume " + s(m, "Name"), h, Docker.argv("run", "--rm", "-v", s(m, "Name") + ":/volume:ro", "busybox",
                            "sh", "-c", "cd /volume && du -sh . && find . -maxdepth 4 | head -n 3000"), Docker.needsRoot(h), null);
                })),
                Ui.button("Prune unused", "trash", e -> prune("Prune unused volumes", "volume", "prune", "-f")),
                Ui.danger("Remove", "trash", e -> withSel(volumeTable, m -> {
                    if (!Ui.confirm("Remove volume", "Delete volume " + s(m, "Name") + " and all its data?", "Delete", true)) return;
                    Host h = host();
                    Ui.action("Remove volume", s(m, "Name"), () -> Docker.run(h, "volume", "rm", s(m, "Name")).orThrow(), this::loadVolumes);
                })));
        VBox v = new VBox(12, bar, volumeTable);
        VBox.setVgrow(volumeTable, Priority.ALWAYS);
        return v;
    }

    private void loadVolumes() {
        Host h = host();
        Ui.bg(() -> Docker.json(h, "volume", "ls", "--format", "{{json .}}"), l -> volumeTable.setItems(FXCollections.observableArrayList(l)), this::showProblem);
    }

    // ================================================================ networks

    private Node networksTab() {
        networkTable.getColumns().add(Ui.col("Name", m -> s(m, "Name"), 240));
        networkTable.getColumns().add(Ui.col("Driver", m -> s(m, "Driver"), 100));
        networkTable.getColumns().add(Ui.col("Scope", m -> s(m, "Scope"), 90));
        networkTable.getColumns().add(Ui.monoCol("ID", m -> s(m, "ID"), 140));
        networkTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(
                Ui.primary("Create network", "plus", e -> {
                    TextField n = new TextField();
                    ComboBox<String> drv = new ComboBox<>(FXCollections.observableArrayList("bridge", "overlay", "macvlan", "ipvlan"));
                    drv.setValue("bridge");
                    TextField subnet = new TextField();
                    subnet.setPromptText("optional, e.g. 172.30.0.0/24");
                    Ui.Form f = new Ui.Form().row("Name", n).row("Driver", drv).row("Subnet", subnet);
                    if (!Ui.dialog("Create network", null, f, "Create", () -> n.getText().isBlank() ? "Name required" : null)) return;
                    List<String> a = new ArrayList<>(List.of("network", "create", "--driver", drv.getValue()));
                    if (!subnet.getText().isBlank()) { a.add("--subnet"); a.add(subnet.getText().strip()); }
                    a.add(n.getText().strip());
                    Host h = host();
                    Ui.action("Create network", n.getText(), () -> Docker.run(h, a.toArray(String[]::new)).orThrow(), this::loadNetworks);
                }),
                Ui.spacer(),
                Ui.button("Connect container", "link", e -> withSel(networkTable, m -> connect(m, true))),
                Ui.button("Disconnect", "x", e -> withSel(networkTable, m -> connect(m, false))),
                Ui.button("Inspect", "eye", e -> withSel(networkTable, m -> {
                    Host h = host();
                    Ui.bg(() -> Docker.run(h, "network", "inspect", s(m, "Name")).orThrow(), out -> Ui.textDialog("Network " + s(m, "Name"), out));
                })),
                Ui.danger("Remove", "trash", e -> withSel(networkTable, m -> {
                    if (!Ui.confirm("Remove network", "Remove network " + s(m, "Name") + "?", "Remove", true)) return;
                    Host h = host();
                    Ui.action("Remove network", s(m, "Name"), () -> Docker.run(h, "network", "rm", s(m, "Name")).orThrow(), this::loadNetworks);
                })));
        VBox v = new VBox(12, bar, networkTable);
        VBox.setVgrow(networkTable, Priority.ALWAYS);
        return v;
    }

    private void connect(Map<String, Object> net, boolean connect) {
        ComboBox<String> c = new ComboBox<>();
        for (Map<String, Object> m : containers) c.getItems().add(s(m, "Names"));
        if (c.getItems().isEmpty()) { Ui.toast("Open the Containers tab first to load containers", Ui.Kind.INFO); return; }
        c.setValue(c.getItems().get(0));
        Ui.Form f = new Ui.Form().row("Container", c);
        if (!Ui.dialog((connect ? "Connect to " : "Disconnect from ") + s(net, "Name"), null, f, connect ? "Connect" : "Disconnect", null)) return;
        Host h = host();
        Ui.action(connect ? "Connect network" : "Disconnect network", c.getValue() + " ↔ " + s(net, "Name"),
                () -> Docker.run(h, "network", connect ? "connect" : "disconnect", s(net, "Name"), c.getValue()).orThrow(), this::loadNetworks);
    }

    private void loadNetworks() {
        Host h = host();
        Ui.bg(() -> Docker.json(h, "network", "ls", "--format", "{{json .}}"), l -> networkTable.setItems(FXCollections.observableArrayList(l)), this::showProblem);
    }

    // ================================================================ catalog

    private Node catalogTab() {
        List<Node> cards = new ArrayList<>();
        for (Catalog.App app : Catalog.APPS) {
            javafx.scene.control.Label d = Ui.label(app.description(), "muted");
            d.setWrapText(true);
            d.setMinHeight(36);
            HBox head = new HBox(10, Icons.of(app.icon(), 24, "icon-accent"), Ui.label(app.name(), "h3"));
            head.setAlignment(Pos.CENTER_LEFT);
            Button deploy = Ui.primary("Deploy", "rocket", e -> deployApp(app));
            VBox card = Ui.card(null, head, d, Ui.spacer(), deploy);
            cards.add(card);
        }
        ResponsiveGrid grid = new ResponsiveGrid(240, cards.toArray(Node[]::new));
        javafx.scene.control.Label note = Ui.label("Apps are deployed as normal compose stacks in " + Settings.stacksRoot()
                + ". Web ports bind to 127.0.0.1 by default — publish them with an Nginx site.", "muted");
        note.setWrapText(true);
        VBox v = new VBox(14, note, grid);
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        return sp;
    }

    private void deployApp(Catalog.App app) {
        TextField name = new TextField(app.id());
        Ui.Form f = new Ui.Form().row("Stack name", name);
        Map<String, TextField> fields = new LinkedHashMap<>();
        for (Catalog.Var v : app.vars()) {
            TextField tf = v.secret() ? new PasswordField() : new TextField();
            tf.setText(v.secret() ? Catalog.randomSecret() : v.def());
            fields.put(v.key(), tf);
            f.row(v.label(), tf, v.secret() ? "Random value generated — stored in the stack's .env" : v.key().equals("BIND") ? "0.0.0.0 exposes the port publicly" : null);
        }
        CheckBox site = new CheckBox("Create an Nginx reverse-proxy site afterwards");
        site.setSelected(app.upstreamVar() != null);
        site.setDisable(app.upstreamVar() == null);
        f.full(site);
        if (!Ui.dialog("Deploy " + app.name(), "Deploy " + app.name(), f, "Deploy", () -> {
            if (!name.getText().matches("[a-z0-9][a-z0-9_-]*")) return "Invalid stack name";
            for (Map.Entry<String, TextField> e : fields.entrySet()) if (e.getValue().getText().isBlank()) return e.getKey() + " is required";
            return null;
        })) return;
        Host h = host();
        String n = name.getText();
        String dir = Settings.stacksRoot() + "/" + n;
        StringBuilder env = new StringBuilder();
        fields.forEach((k, tf) -> env.append(k).append('=').append(tf.getText().strip()).append('\n'));
        Ui.bg(() -> {
            String p = Docker.problem(h);
            if (p != null) throw new io.serverpilotx.core.CmdException(p);
            ensureStacksRoot(h);
            if (h.exists(dir)) throw new io.serverpilotx.core.CmdException("A stack named " + n + " already exists");
            h.run("mkdir", "-p", dir).orThrow();
            h.writeFile(dir + "/docker-compose.yml", app.compose());
            h.writeFile(dir + "/.env", env.toString());
            h.run("chmod", "600", dir + "/.env");
            io.serverpilotx.core.AuditLog.log("Deploy app", app.name(), n, true);
            return n;
        }, ok -> {
            deployDir(h, n, dir);
            if (site.isSelected() && app.upstreamVar() != null) {
                String port = fields.get(app.upstreamVar()).getText().strip();
                SitesView.newSiteFor(n + ".example.com", "http://127.0.0.1:" + port);
            }
        });
    }

    // ================================================================ common

    private void showProblem(String err) {
        Ui.toast(err, Ui.Kind.ERROR);
    }

    @Override
    public void refresh() {
        if (!built()) return;
        Host h = host();
        Ui.bg(() -> {
            String p = Docker.problem(h);
            return p == null ? "" : p;
        }, problem -> {
            if (!problem.isEmpty()) {
                showMissing(h, problem);
                return;
            }
            if (root.getChildren().size() > 1) root.getChildren().remove(1, root.getChildren().size());
            switch (tabs.getSelectionModel().getSelectedIndex()) {
                case 0 -> loadContainers();
                case 1 -> loadStacks();
                case 2 -> loadImages();
                case 3 -> loadVolumes();
                case 4 -> { loadNetworks(); if (containers.isEmpty()) loadContainers(); }
                default -> { }
            }
        });
    }

    private void showMissing(Host h, String problem) {
        if (root.getChildren().size() > 1) root.getChildren().remove(1, root.getChildren().size());
        Button install = Ui.primary("Install Docker", "download", e -> Ui.stream("Install Docker", h,
                h.installArgv(h.pkgManager().equals("apt") ? new String[]{"docker.io", "docker-compose-v2"} : new String[]{"docker", "docker-compose"}),
                true, c -> { Ui.runAsync(h::detect); refresh(); }));
        Button start = Ui.button("Start Docker service", "play", e -> Ui.action("Start docker", h.name(),
                () -> h.sudo("systemctl", "enable", "--now", "docker").orThrow(), this::refresh));
        VBox empty = Ui.empty("docker", "Docker unavailable", problem, h.has("docker") ? start : install);
        empty.setStyle("-fx-background-color: -spx-bg;");
        root.getChildren().add(empty);
    }

    /** Lets other pages jump to a tab. */
    public void showTab(String name) {
        if (tabs == null) return;
        for (Tab t : tabs.getTabs()) if (t.getText().equalsIgnoreCase(name)) tabs.getSelectionModel().select(t);
    }

    static void open() {
        MainWindow.get().navigate("docker");
    }
}
