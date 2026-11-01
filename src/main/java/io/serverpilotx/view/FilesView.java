package io.serverpilotx.view;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.core.CmdException;
import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Json;
import io.serverpilotx.core.Metrics;
import io.serverpilotx.core.Quote;
import io.serverpilotx.core.Settings;
import io.serverpilotx.ui.Icons;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;

/** File manager with editor, upload/download, permissions, archives and a disk usage treemap. */
public final class FilesView extends View {
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final long MAX_EDIT = 2 * 1024 * 1024;

    private final ListView<String> roots = new ListView<>();
    private final TableView<Map<String, Object>> table = new TableView<>();
    private final TextField pathField = new TextField();
    private final Label info = Ui.label("", "muted", "small");
    private String cwd;
    private boolean elevated;

    @Override public String id() { return "files"; }
    @Override public String title() { return "Files"; }
    @Override public String subtitle() { return "File manager"; }
    @Override public String icon() { return "folder"; }

    private static String s(Map<String, Object> m, String k) { return Json.str(m, k); }

    @Override
    protected Node build() {
        roots.setPrefWidth(200);
        roots.setMinWidth(160);
        roots.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String it, boolean empty) {
                super.updateItem(it, empty);
                setText(empty ? null : it);
                setGraphic(empty ? null : Icons.of(it != null && it.equals("~") ? "home" : "folder", 15));
            }
        });
        roots.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> { if (b != null) open(expand(b)); });

        TableColumn<Map<String, Object>, String> name = Ui.col("Name", m -> s(m, "name"), 320);
        name.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                Map<String, Object> m = getTableRow() == null ? null : getTableRow().getItem();
                String t = m == null ? "f" : s(m, "type");
                setText(v);
                setGraphic(Icons.of(t.equals("d") ? "folder" : t.equals("l") ? "link" : "file", 15, t.equals("d") ? "icon-accent" : "icon-muted"));
            }
        });
        table.getColumns().add(name);
        table.getColumns().add(Ui.col("Size", m -> s(m, "type").equals("d") ? "" : Metrics.bytes(Double.parseDouble(s(m, "size"))), 90));
        table.getColumns().add(Ui.col("Modified", m -> s(m, "mtime"), 140));
        table.getColumns().add(Ui.monoCol("Mode", m -> s(m, "mode"), 70));
        table.getColumns().add(Ui.col("Owner", m -> s(m, "owner") + ":" + s(m, "group"), 140));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(Ui.label("Empty folder", "muted"));
        table.setRowFactory(tv -> {
            TableRow<Map<String, Object>> r = new TableRow<>();
            r.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !r.isEmpty()) activate(r.getItem()); });
            return r;
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && table.getSelectionModel().getSelectedItem() != null) activate(table.getSelectionModel().getSelectedItem());
            if (e.getCode() == KeyCode.BACK_SPACE) up();
            if (e.getCode() == KeyCode.DELETE) delete();
        });

        pathField.setOnAction(e -> open(pathField.getText().strip()));
        io.serverpilotx.ui.ToolBarPane nav = Ui.toolbar(Ui.iconButton("up", "Parent folder", e -> up()), Ui.iconButton("refresh", "Reload", e -> refresh()), pathField);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        MenuButton newMenu = new MenuButton("New", Icons.of("plus", 15));
        newMenu.getItems().addAll(mi("File", "file", this::newFile), mi("Folder", "folder", this::newFolder));
        MenuButton more = new MenuButton("More", Icons.of("more", 15));
        more.getItems().addAll(
                mi("Rename…", "edit", this::rename),
                mi("Permissions (chmod)…", "lock", this::chmod),
                mi("Owner (chown)…", "users", this::chown),
                mi("Compress to .tar.gz", "archive", this::archive),
                mi("Extract here", "archive", this::extract),
                mi("Copy path", "copy", () -> withOne(m -> {
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(join(cwd, s(m, "name")));
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                    Ui.toast("Path copied", Ui.Kind.INFO);
                })));
        io.serverpilotx.ui.ToolBarPane actions = Ui.toolbar(newMenu, Ui.button("Upload", "upload", e -> upload()), Ui.button("Download", "download", e -> download()),
                Ui.button("Edit", "edit", e -> withOne(this::edit)), more, Ui.button("Disk usage", "chart", e -> diskUsage()), Ui.spacer(), info,
                Ui.danger("Delete", "trash", e -> delete()));

        VBox right = new VBox(10, nav, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox left = new VBox(8, Ui.label("Locations", "nav-section"), roots);
        VBox.setVgrow(roots, Priority.ALWAYS);
        HBox body = new HBox(14, left, right);
        VBox page = new VBox(body);
        VBox.setVgrow(body, Priority.ALWAYS);
        page.getStyleClass().add("page");
        return page;
    }

    private static MenuItem mi(String t, String icon, Runnable r) {
        MenuItem m = new MenuItem(t, Icons.of(icon, 14));
        m.setOnAction(e -> r.run());
        return m;
    }

    private String expand(String root) {
        Host h = host();
        String home = h.isRoot() ? "/root" : (h.isLocal() ? System.getProperty("user.home") : "/home/" + h.user());
        return root.equals("~") ? home : root.startsWith("~/") ? home + root.substring(1) : root;
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private void withOne(java.util.function.Consumer<Map<String, Object>> c) {
        Map<String, Object> m = table.getSelectionModel().getSelectedItem();
        if (m == null) Ui.toast("Select a file first", Ui.Kind.INFO);
        else c.accept(m);
    }

    private List<Map<String, Object>> selection() {
        return new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    private CmdResult runAs(Host h, String... argv) {
        return elevated ? h.sudo(argv) : h.run(argv);
    }

    // ------------------------------------------------------------ listing

    @Override
    public void refresh() {
        if (!built()) return;
        if (roots.getItems().isEmpty() || !roots.getItems().equals(Settings.fileRoots())) {
            roots.getItems().setAll(Settings.fileRoots());
        }
        if (cwd == null) {
            roots.getSelectionModel().select(roots.getItems().contains("~") ? "~" : roots.getItems().get(0));
            if (cwd == null) open(expand(roots.getItems().get(0)));
            return;
        }
        open(cwd);
    }

    @Override
    public void onHostChanged() {
        cwd = null;
        super.onHostChanged();
    }

    private void open(String path) {
        Host h = host();
        String p = path.isEmpty() ? "/" : path;
        String find = "find " + Quote.sh(p) + " -mindepth 1 -maxdepth 1 -printf '%y\\t%s\\t%m\\t%u\\t%g\\t%T@\\t%f\\n'";
        Ui.bg(() -> {
            CmdResult r = h.sh("cd " + Quote.sh(p) + " 2>/dev/null && " + find);
            boolean elev = false;
            if (!r.ok() && (r.err().contains("Permission denied") || r.out().isBlank())) {
                CmdResult t = h.run("test", "-d", p);
                if (!t.ok()) {
                    CmdResult rs = h.sudo("test", "-d", p);
                    if (!rs.ok()) throw new CmdException(p + " is not a folder or does not exist");
                }
                r = h.sudoSh(find);
                elev = true;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String l : r.lines()) {
                String[] f = l.split("\t", 7);
                if (f.length < 7) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", f[0]);
                m.put("size", f[1]);
                m.put("mode", f[2]);
                m.put("owner", f[3]);
                m.put("group", f[4]);
                double t = 0;
                try { t = Double.parseDouble(f[5]); } catch (NumberFormatException ignored) { }
                m.put("mtime", DT.format(Instant.ofEpochSecond((long) t)));
                m.put("name", f[6]);
                rows.add(m);
            }
            rows.sort(Comparator.<Map<String, Object>, Boolean>comparing(m -> !s(m, "type").equals("d")).thenComparing(m -> s(m, "name").toLowerCase()));
            return new Object[]{rows, elev};
        }, res -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) res[0];
            cwd = p;
            elevated = (Boolean) res[1];
            pathField.setText(p);
            table.setItems(FXCollections.observableArrayList(rows));
            info.setText(rows.size() + " items" + (elevated ? "  ·  root access" : ""));
        });
    }

    private void up() {
        if (cwd == null || cwd.equals("/")) return;
        String parent = cwd.replaceAll("/+$", "");
        parent = parent.substring(0, parent.lastIndexOf('/'));
        open(parent.isEmpty() ? "/" : parent);
    }

    private void activate(Map<String, Object> m) {
        String t = s(m, "type");
        String path = join(cwd, s(m, "name"));
        if (t.equals("d")) open(path);
        else if (t.equals("l")) {
            Host h = host();
            Ui.bg(() -> h.run("test", "-d", path).ok(), isDir -> { if (isDir) open(path); else edit(m); });
        } else edit(m);
    }

    // ------------------------------------------------------------ actions

    private void edit(Map<String, Object> m) {
        long size = 0;
        try { size = Long.parseLong(s(m, "size")); } catch (NumberFormatException ignored) { }
        if (size > MAX_EDIT) { Ui.toast("File is too large to edit here (" + Metrics.bytes(size) + ") — download it instead", Ui.Kind.INFO); return; }
        Host h = host();
        String path = join(cwd, s(m, "name"));
        Ui.bg(() -> {
            String content = h.readFile(path);
            if (content.indexOf('\0') >= 0) throw new CmdException("This looks like a binary file — download it instead.");
            return content;
        }, content -> Ui.editor(path, "Saved as " + (elevated || !s(m, "owner").equals(h.user()) ? "root if needed" : h.user()) + ". Permissions are kept.", content, text -> {
            h.writeFileAuto(path, text, null);
            AuditLog.log("Edit file", path, text.length() + " chars", true);
            Ui.fx(this::refresh);
            return "Saved " + s(m, "name");
        }));
    }

    private void newFile() {
        Ui.prompt("New file", "File name", "").ifPresent(n -> {
            Host h = host();
            String path = join(cwd, n);
            Ui.action("Create file", path, () -> {
                if (h.exists(path)) throw new CmdException(n + " already exists");
                if (elevated || !h.run("test", "-w", cwd).ok()) h.sudo("touch", path).orThrow();
                else h.run("touch", path).orThrow();
                return path;
            }, this::refresh);
        });
    }

    private void newFolder() {
        Ui.prompt("New folder", "Folder name", "").ifPresent(n -> {
            Host h = host();
            String path = join(cwd, n);
            Ui.action("Create folder", path, () -> {
                if (elevated || !h.run("test", "-w", cwd).ok()) return h.sudo("mkdir", "-p", path).orThrow();
                return h.run("mkdir", "-p", path).orThrow();
            }, this::refresh);
        });
    }

    private void rename() {
        withOne(m -> Ui.prompt("Rename", "New name", s(m, "name")).ifPresent(n -> {
            Host h = host();
            String from = join(cwd, s(m, "name")), to = join(cwd, n);
            Ui.action("Rename", from + " → " + to, () -> {
                CmdResult r = h.run("mv", "--", from, to);
                if (!r.ok()) h.sudo("mv", "--", from, to).orThrow();
                return to;
            }, this::refresh);
        }));
    }

    private void delete() {
        List<Map<String, Object>> sel = selection();
        if (sel.isEmpty()) { Ui.toast("Select files first", Ui.Kind.INFO); return; }
        List<String> paths = sel.stream().map(m -> join(cwd, s(m, "name"))).toList();
        if (!Ui.confirm("Delete", "Permanently delete " + (paths.size() == 1 ? paths.get(0) : paths.size() + " items") + "?", "Delete", true)) return;
        Host h = host();
        Ui.action("Delete files", String.join(" ", paths), () -> {
            List<String> a = new ArrayList<>(List.of("rm", "-rf", "--"));
            a.addAll(paths);
            CmdResult r = h.exec(a, null, Host.DEFAULT_TIMEOUT);
            if (!r.ok()) h.sudo(a, Host.DEFAULT_TIMEOUT).orThrow();
            return paths.size() + " item(s)";
        }, this::refresh);
    }

    private void chmod() {
        List<Map<String, Object>> sel = selection();
        if (sel.isEmpty()) { Ui.toast("Select files first", Ui.Kind.INFO); return; }
        TextField mode = new TextField(s(sel.get(0), "mode"));
        javafx.scene.control.CheckBox rec = new javafx.scene.control.CheckBox("Apply recursively");
        if (!Ui.dialog("Permissions", null, new Ui.Form().row("Mode", mode, "Octal (e.g. 644, 755) or symbolic (u+x)").full(rec), "Apply",
                () -> mode.getText().strip().matches("[0-7]{3,4}|[ugoa]*[+=-][rwxXst]+") ? null : "Invalid mode")) return;
        Host h = host();
        List<String> a = new ArrayList<>(List.of("chmod"));
        if (rec.isSelected()) a.add("-R");
        a.add(mode.getText().strip());
        a.add("--");
        sel.forEach(m -> a.add(join(cwd, s(m, "name"))));
        Ui.action("chmod " + mode.getText().strip(), sel.size() + " item(s)", () -> {
            CmdResult r = h.exec(a, null, Host.DEFAULT_TIMEOUT);
            if (!r.ok()) h.sudo(a, Host.DEFAULT_TIMEOUT).orThrow();
            return String.join(" ", a);
        }, this::refresh);
    }

    private void chown() {
        List<Map<String, Object>> sel = selection();
        if (sel.isEmpty()) { Ui.toast("Select files first", Ui.Kind.INFO); return; }
        TextField owner = new TextField(s(sel.get(0), "owner") + ":" + s(sel.get(0), "group"));
        javafx.scene.control.CheckBox rec = new javafx.scene.control.CheckBox("Apply recursively");
        if (!Ui.dialog("Owner", null, new Ui.Form().row("Owner", owner, "user:group").full(rec), "Apply",
                () -> owner.getText().strip().matches("[a-z_][a-z0-9_-]*(:[a-z_][a-z0-9_-]*)?") ? null : "Use user or user:group")) return;
        Host h = host();
        List<String> a = new ArrayList<>(List.of("chown"));
        if (rec.isSelected()) a.add("-R");
        a.add(owner.getText().strip());
        a.add("--");
        sel.forEach(m -> a.add(join(cwd, s(m, "name"))));
        Ui.action("chown " + owner.getText().strip(), sel.size() + " item(s)", () -> h.sudo(a, Host.DEFAULT_TIMEOUT).orThrow(), this::refresh);
    }

    private void archive() {
        List<Map<String, Object>> sel = selection();
        if (sel.isEmpty()) { Ui.toast("Select files first", Ui.Kind.INFO); return; }
        String def = (sel.size() == 1 ? s(sel.get(0), "name") : "archive") + ".tar.gz";
        Ui.prompt("Compress", "Archive name", def).ifPresent(n -> {
            Host h = host();
            List<String> a = new ArrayList<>(List.of("tar", "-czf", join(cwd, n), "-C", cwd, "--"));
            sel.forEach(m -> a.add(s(m, "name")));
            Ui.stream("Compress " + n, h, a, elevated || !h.run("test", "-w", cwd).ok(), c -> refresh());
        });
    }

    private void extract() {
        withOne(m -> {
            String n = s(m, "name");
            String path = join(cwd, n);
            List<String> a;
            if (n.endsWith(".zip")) a = List.of("unzip", "-o", path, "-d", cwd);
            else if (n.matches(".*\\.(tar|tar\\.gz|tgz|tar\\.bz2|tbz2|tar\\.xz|txz|tar\\.zst)$")) a = List.of("tar", "-xf", path, "-C", cwd);
            else { Ui.toast("Not a supported archive (.zip, .tar.*)", Ui.Kind.INFO); return; }
            Host h = host();
            Ui.stream("Extract " + n, h, a, elevated || !h.run("test", "-w", cwd).ok(), c -> refresh());
        });
    }

    private void upload() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Upload to " + cwd);
        List<File> files = fc.showOpenMultipleDialog(Ui.owner());
        if (files == null || files.isEmpty()) return;
        Host h = host();
        Ui.action("Upload files", cwd, () -> {
            boolean root = elevated || !h.run("test", "-w", cwd).ok();
            for (File f : files) h.upload(f.toPath(), join(cwd, f.getName()), root);
            return files.size() + " file(s) → " + cwd;
        }, this::refresh);
    }

    private void download() {
        withOne(m -> {
            if (s(m, "type").equals("d")) { Ui.toast("Compress the folder first, then download the archive", Ui.Kind.INFO); return; }
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(s(m, "name"));
            File f = fc.showSaveDialog(Ui.owner());
            if (f == null) return;
            Host h = host();
            String path = join(cwd, s(m, "name"));
            Ui.action("Download file", path, () -> {
                boolean root = elevated || !h.run("test", "-r", path).ok();
                h.download(path, f.toPath(), root);
                return f.getAbsolutePath();
            }, null);
        });
    }

    // ------------------------------------------------------------ disk usage treemap

    private record Entry(String name, long bytes, boolean dir) {}

    private void diskUsage() {
        Host h = host();
        String dir = cwd;
        Ui.bg(() -> {
            String script = "cd " + Quote.sh(dir) + " && du -x -s -b -- .[!.]* * 2>/dev/null; true";
            CmdResult r = elevated ? h.sudoSh(script) : h.sh(script);
            List<Entry> list = new ArrayList<>();
            for (String l : r.lines()) {
                String[] f = l.split("\t", 2);
                if (f.length < 2) continue;
                try { list.add(new Entry(f[1], Long.parseLong(f[0].strip()), false)); } catch (NumberFormatException ignored) { }
            }
            String dirs = h.sh("cd " + Quote.sh(dir) + " && find . -mindepth 1 -maxdepth 1 -type d -printf '%f\\n' 2>/dev/null").out();
            List<Entry> out = new ArrayList<>();
            for (Entry e : list) out.add(new Entry(e.name(), e.bytes(), dirs.lines().anyMatch(d -> d.equals(e.name()))));
            out.sort(Comparator.comparingLong(Entry::bytes).reversed());
            return out;
        }, list -> showTreemap(dir, list));
    }

    private static final String[] COLORS = {"#2f6fdf", "#7c3aed", "#0891b2", "#16a34a", "#d97706", "#db2777", "#4f46e5", "#0d9488", "#ca8a04", "#9333ea"};

    private void showTreemap(String dir, List<Entry> list) {
        long total = list.stream().mapToLong(Entry::bytes).sum();
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Disk usage");
        d.setHeaderText("Disk usage · " + dir + "  (" + Metrics.bytes(total) + ")");
        d.initOwner(Ui.owner());
        d.initModality(Modality.WINDOW_MODAL);
        io.serverpilotx.ui.Theme.apply(d);
        d.setResizable(true);
        Pane canvas = new Pane();
        canvas.setPrefSize(900, 520);
        List<Entry> shown = list.stream().filter(e -> e.bytes() > 0).limit(80).toList();
        Runnable draw = () -> {
            canvas.getChildren().clear();
            layout(canvas, shown, 0, 0, canvas.getWidth(), canvas.getHeight(), d, dir);
        };
        canvas.widthProperty().addListener((o, a, b) -> draw.run());
        canvas.heightProperty().addListener((o, a, b) -> draw.run());
        Label hint = Ui.label("Click a folder to open it. Largest 80 entries shown.", "faint", "small");
        VBox box = new VBox(8, canvas, hint);
        VBox.setVgrow(canvas, Priority.ALWAYS);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.show();
    }

    private void layout(Pane p, List<Entry> items, double x, double y, double w, double h, Dialog<?> d, String dir) {
        if (items.isEmpty() || w < 2 || h < 2) return;
        if (items.size() == 1) {
            Entry e = items.get(0);
            StackPane cell = new StackPane();
            cell.getStyleClass().add("treemap-cell");
            cell.setStyle("-fx-background-color: " + COLORS[Math.abs(e.name().hashCode()) % COLORS.length] + ";");
            cell.resizeRelocate(x, y, w, h);
            cell.setLayoutX(x);
            cell.setLayoutY(y);
            cell.setPrefSize(w, h);
            cell.setMinSize(w, h);
            cell.setMaxSize(w, h);
            if (w > 60 && h > 30) {
                Label l = new Label(e.name() + "\n" + Metrics.bytes(e.bytes()));
                StackPane.setAlignment(l, Pos.TOP_LEFT);
                cell.getChildren().add(l);
            }
            Tooltip.install(cell, new Tooltip(e.name() + " — " + Metrics.bytes(e.bytes())));
            if (e.dir()) {
                cell.setStyle(cell.getStyle() + "-fx-cursor: hand;");
                cell.setOnMouseClicked(ev -> { d.close(); open(join(dir, e.name())); });
            }
            p.getChildren().add(cell);
            return;
        }
        long total = items.stream().mapToLong(Entry::bytes).sum();
        long acc = 0;
        int split = 0;
        while (split < items.size() - 1 && acc + items.get(split).bytes() <= total / 2) acc += items.get(split++).bytes();
        if (split == 0) { acc = items.get(0).bytes(); split = 1; }
        double frac = total == 0 ? 0.5 : (double) acc / total;
        List<Entry> a = items.subList(0, split), b = items.subList(split, items.size());
        if (w >= h) {
            layout(p, a, x, y, w * frac, h, d, dir);
            layout(p, b, x + w * frac, y, w * (1 - frac), h, d, dir);
        } else {
            layout(p, a, x, y, w, h * frac, d, dir);
            layout(p, b, x, y + h * frac, w, h * (1 - frac), d, dir);
        }
    }
}
