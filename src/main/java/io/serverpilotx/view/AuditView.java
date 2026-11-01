package io.serverpilotx.view;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import io.serverpilotx.core.AuditLog;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Every change made through ServerPilotX: who, what, where, when and the outcome. */
public final class AuditView extends View {
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ObservableList<AuditLog.Entry> entries = FXCollections.observableArrayList();
    private final FilteredList<AuditLog.Entry> filtered = new FilteredList<>(entries);
    private final TableView<AuditLog.Entry> table = new TableView<>(filtered);
    private final ComboBox<String> server = new ComboBox<>();
    private final ComboBox<String> result = new ComboBox<>(FXCollections.observableArrayList("All results", "Succeeded", "Failed"));
    private final TextField search = new TextField();

    @Override public String id() { return "audit"; }
    @Override public String title() { return "Audit log"; }
    @Override public String subtitle() { return "Every change, who and when"; }
    @Override public String icon() { return "audit"; }

    @Override
    protected Node build() {
        table.getColumns().add(Ui.col("Time", e -> DT.format(Instant.ofEpochMilli(e.ts())), 150));
        table.getColumns().add(Ui.col("User", AuditLog.Entry::user, 90));
        table.getColumns().add(Ui.col("Server", AuditLog.Entry::server, 130));
        table.getColumns().add(Ui.col("Action", AuditLog.Entry::action, 190));
        table.getColumns().add(Ui.monoCol("Target", AuditLog.Entry::target, 240));
        table.getColumns().add(Ui.badgeCol("Result", e -> e.ok() ? "OK" : "Failed", v -> v.equals("OK") ? "green" : "red", 80));
        table.getColumns().add(Ui.col("Detail", e -> e.detail().replace('\n', ' '), 300));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No actions recorded yet", "muted"));
        table.setRowFactory(tv -> {
            TableRow<AuditLog.Entry> r = new TableRow<>();
            r.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !r.isEmpty()) {
                    AuditLog.Entry e = r.getItem();
                    Ui.textDialog("Audit entry", "Time:    " + DT.format(Instant.ofEpochMilli(e.ts())) + "\nUser:    " + e.user()
                            + "\nServer:  " + e.server() + "\nAction:  " + e.action() + "\nTarget:  " + e.target()
                            + "\nResult:  " + (e.ok() ? "OK" : "Failed") + "\n\n" + e.detail());
                }
            });
            return r;
        });

        search.setPromptText("Search actions, targets, details…");
        server.setValue("All servers");
        result.setValue("All results");
        search.textProperty().addListener((o, a, b) -> filter());
        server.setOnAction(e -> filter());
        result.setOnAction(e -> filter());
        io.serverpilotx.ui.ToolBarPane bar = Ui.toolbar(search, server, result, Ui.spacer(),
                Ui.button("Export CSV", "download", e -> export()),
                Ui.label(AuditLog.file().toString(), "faint", "small"));
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox page = new VBox(12, bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        page.getStyleClass().add("page");
        AuditLog.onEntry(e -> Ui.fx(() -> entries.add(0, e)));
        return page;
    }

    private void filter() {
        String q = search.getText() == null ? "" : search.getText().toLowerCase();
        String sv = server.getValue();
        String rv = result.getValue();
        filtered.setPredicate(e -> {
            if (sv != null && !sv.equals("All servers") && !e.server().equals(sv)) return false;
            if ("Succeeded".equals(rv) && !e.ok()) return false;
            if ("Failed".equals(rv) && e.ok()) return false;
            return q.isEmpty() || (e.action() + " " + e.target() + " " + e.detail() + " " + e.user()).toLowerCase().contains(q);
        });
    }

    @Override
    public void refresh() {
        if (!built()) return;
        Ui.bg(() -> {
            List<AuditLog.Entry> l = new ArrayList<>(AuditLog.readAll());
            Collections.reverse(l);
            return l;
        }, l -> {
            entries.setAll(l);
            TreeSet<String> servers = new TreeSet<>();
            l.forEach(e -> servers.add(e.server()));
            String cur = server.getValue();
            server.getItems().setAll("All servers");
            server.getItems().addAll(servers);
            server.setValue(cur != null && server.getItems().contains(cur) ? cur : "All servers");
            filter();
        });
    }

    private void export() {
        StringBuilder b = new StringBuilder("time,user,server,action,target,result,detail\n");
        for (AuditLog.Entry e : filtered) {
            b.append(DT.format(Instant.ofEpochMilli(e.ts()))).append(',').append(csv(e.user())).append(',').append(csv(e.server()))
                    .append(',').append(csv(e.action())).append(',').append(csv(e.target())).append(',').append(e.ok() ? "ok" : "failed")
                    .append(',').append(csv(e.detail())).append('\n');
        }
        Ui.saveText("serverpilotx-audit.csv", b.toString());
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
