package io.serverpilotx.ui;

import java.util.List;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Ctrl+K quick launcher for pages and actions. */
public final class CommandPalette extends StackPane {
    public record Command(String title, String hint, String icon, Runnable run) {}

    private final Supplier<List<Command>> source;
    private final TextField search = new TextField();
    private final ListView<Command> list = new ListView<>();
    private final ObservableList<Command> items = FXCollections.observableArrayList();
    private List<Command> all = List.of();

    public CommandPalette(Supplier<List<Command>> source) {
        this.source = source;
        getStyleClass().add("palette-backdrop");
        setVisible(false);
        setOnMouseClicked(e -> { if (e.getTarget() == this) hide(); });

        search.setPromptText("Type a command or page…  (Esc to close)");
        list.setItems(items);
        list.setPrefHeight(340);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Command c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) { setGraphic(null); setText(null); return; }
                Label t = new Label(c.title());
                Label h = Ui.label(c.hint(), "faint", "small");
                HBox row = new HBox(10, Icons.of(c.icon(), 16), t, Ui.spacer(), h);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        list.setOnMouseClicked(e -> { if (e.getClickCount() >= 1) runSelected(); });

        search.textProperty().addListener((o, a, q) -> filter(q));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) { list.getSelectionModel().selectNext(); e.consume(); }
            else if (e.getCode() == KeyCode.UP) { list.getSelectionModel().selectPrevious(); e.consume(); }
            else if (e.getCode() == KeyCode.ENTER) { runSelected(); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { hide(); e.consume(); }
        });

        VBox box = new VBox(8, search, list);
        box.getStyleClass().add("palette");
        box.setMaxWidth(600);
        box.setMaxHeight(420);
        StackPane.setAlignment(box, Pos.TOP_CENTER);
        box.setTranslateY(90);
        getChildren().add(box);
    }

    private void filter(String q) {
        String s = q == null ? "" : q.toLowerCase().strip();
        items.setAll(all.stream().filter(c -> s.isEmpty() || (c.title() + " " + c.hint()).toLowerCase().contains(s)).toList());
        if (!items.isEmpty()) list.getSelectionModel().select(0);
    }

    private void runSelected() {
        Command c = list.getSelectionModel().getSelectedItem();
        if (c == null) return;
        hide();
        Platform.runLater(c.run());
    }

    public void show() {
        all = source.get();
        search.clear();
        filter("");
        setVisible(true);
        toFront();
        Platform.runLater(search::requestFocus);
    }

    public void hide() {
        setVisible(false);
    }

    public void toggle() {
        if (isVisible()) hide();
        else show();
    }
}
