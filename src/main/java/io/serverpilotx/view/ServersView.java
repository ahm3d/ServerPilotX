package io.serverpilotx.view;

import java.io.File;

import io.serverpilotx.core.CmdResult;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import io.serverpilotx.ui.MainWindow;
import io.serverpilotx.ui.Terminals;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Configured servers: this computer plus remote hosts reached over SSH. */
public final class ServersView extends View {
    private final TableView<Host> table = new TableView<>();

    @Override public String id() { return "servers"; }
    @Override public String title() { return "Servers"; }
    @Override public String subtitle() { return "Local and remote hosts"; }
    @Override public String icon() { return "server"; }

    @Override
    protected Node build() {
        table.getColumns().add(Ui.col("Name", Host::name, 200));
        table.getColumns().add(Ui.col("Address", h -> h.isLocal() ? "local" : h.address(), 200));
        table.getColumns().add(Ui.col("User", Host::user, 120));
        table.getColumns().add(Ui.col("Port", h -> h.isLocal() ? "" : String.valueOf(h.port()), 70));
        table.getColumns().add(Ui.col("Key", h -> h.identityFile().isEmpty() ? "default" : h.identityFile(), 220));
        table.getColumns().add(Ui.badgeCol("Status", h -> h == Hosts.active() ? "Active" : "", v -> "green", 100));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<Host>();
            row.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !row.isEmpty()) MainWindow.get().switchHost(row.getItem()); });
            return row;
        });

        Button connect = Ui.primary("Manage this server", "chevron-right", e -> sel(h -> MainWindow.get().switchHost(h)));
        Button test = Ui.button("Test connection", "activity", e -> sel(this::test));
        Button ssh = Ui.button("Open SSH terminal", "terminal", e -> sel(h -> Terminals.open(h, "SSH " + h.name(), null)));
        Button remove = Ui.danger("Remove", "trash", e -> sel(h -> {
            if (h.isLocal()) { Ui.toast("This computer cannot be removed", Ui.Kind.INFO); return; }
            if (Ui.confirm("Remove server", "Remove " + h.name() + " from ServerPilotX? Nothing is changed on the server.", "Remove", true)) {
                Hosts.remove(h);
                refresh();
            }
        }));
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(Ui.primary("Add server", "plus", e -> add()), Ui.spacer(), connect, test, ssh, remove);

        VBox help = Ui.card("How remote servers work",
                Ui.label("ServerPilotX manages remote servers over SSH using key authentication — nothing has to be installed on them. "
                        + "Commands run as your SSH user; changes that need root use sudo (you are asked for the password once per session). "
                        + "Set up a key first with:  ssh-copy-id user@server", "muted"));
        ((javafx.scene.control.Label) help.getChildren().get(1)).setWrapText(true);

        VBox page = new VBox(14, bar, table, help);
        VBox.setVgrow(table, Priority.ALWAYS);
        page.getStyleClass().add("page");
        return page;
    }

    private void sel(java.util.function.Consumer<Host> c) {
        Host h = table.getSelectionModel().getSelectedItem();
        if (h == null) Ui.toast("Select a server first", Ui.Kind.INFO);
        else c.accept(h);
    }

    @Override
    public void refresh() {
        table.setItems(FXCollections.observableArrayList(Hosts.all()));
        table.refresh();
    }

    private void test(Host h) {
        Ui.bg(() -> h.sh("echo ok; uname -sr; uptime"), (CmdResult r) -> {
            if (r.ok()) Ui.toast(h.name() + ": connected — " + r.lines()[Math.min(1, r.lines().length - 1)], Ui.Kind.OK);
            else Ui.errorDialog("Connection to " + h.name() + " failed", r.text());
        });
    }

    private void add() {
        TextField name = new TextField();
        name.setPromptText("vps1");
        TextField addr = new TextField();
        addr.setPromptText("203.0.113.10 or vps1.example.com");
        TextField user = new TextField("root");
        Spinner<Integer> port = new Spinner<>(1, 65535, 22);
        port.setEditable(true);
        TextField key = new TextField();
        key.setPromptText("~/.ssh/id_ed25519 (optional)");
        Button browse = Ui.button("Browse…", "folder", e -> {
            FileChooser fc = new FileChooser();
            fc.setInitialDirectory(new File(System.getProperty("user.home"), ".ssh").isDirectory()
                    ? new File(System.getProperty("user.home"), ".ssh") : new File(System.getProperty("user.home")));
            File f = fc.showOpenDialog(Ui.owner());
            if (f != null) key.setText(f.getAbsolutePath());
        });
        HBox keyRow = new HBox(8, key, browse);
        HBox.setHgrow(key, Priority.ALWAYS);
        Ui.Form f = new Ui.Form()
                .row("Name", name)
                .row("Host / IP", addr)
                .row("SSH user", user, "Use root, or a user with sudo rights")
                .row("Port", port)
                .row("Private key", keyRow, "Leave empty to use your SSH agent / default keys");
        if (!Ui.dialog("Add server", "Add a remote server", f, "Add server", () -> {
            if (addr.getText().isBlank()) return "Host is required";
            if (user.getText().isBlank()) return "User is required";
            return null;
        })) return;
        String nm = name.getText().isBlank() ? addr.getText().strip() : name.getText().strip();
        Host h = Hosts.add(nm, addr.getText().strip(), user.getText().strip(), port.getValue(), key.getText().strip());
        refresh();
        test(h);
    }
}
