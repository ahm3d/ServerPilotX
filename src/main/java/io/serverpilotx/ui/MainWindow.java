package io.serverpilotx.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import io.serverpilotx.core.Metrics;
import io.serverpilotx.view.AuditView;
import io.serverpilotx.view.DashboardView;
import io.serverpilotx.view.DockerView;
import io.serverpilotx.view.FilesView;
import io.serverpilotx.view.FirewallView;
import io.serverpilotx.view.ServersView;
import io.serverpilotx.view.ServicesView;
import io.serverpilotx.view.SettingsView;
import io.serverpilotx.view.SitesView;
import io.serverpilotx.view.SslView;
import io.serverpilotx.view.TerminalView;
import io.serverpilotx.view.UsersView;
import io.serverpilotx.view.View;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Application shell: sidebar navigation, top bar with server picker, content area, toasts, palette. */
public final class MainWindow {
    private static MainWindow instance;

    private final Stage stage;
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, ToggleButton> navButtons = new LinkedHashMap<>();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final StackPane content = new StackPane();
    private final Label title = Ui.label("", "h1");
    private final Label subtitle = Ui.label("", "muted");
    private final MenuButton serverPicker = new MenuButton();
    private final Button themeBtn = new Button();
    private final CommandPalette palette;
    private View current;

    public static MainWindow get() { return instance; }

    public MainWindow(Stage stage, Image icon) {
        instance = this;
        this.stage = stage;

        add(new DashboardView(), "OVERVIEW");
        add(new ServersView(), null);
        add(new DockerView(), "APPS");
        add(new SitesView(), null);
        add(new SslView(), null);
        add(new FirewallView(), "SECURITY");
        add(new UsersView(), null);
        add(new ServicesView(), "SYSTEM");
        add(new FilesView(), null);
        add(new TerminalView(), null);
        add(new AuditView(), "ADMIN");
        add(new SettingsView(), null);

        // ---- sidebar
        ImageView logo = new ImageView(icon);
        logo.setFitWidth(38);
        logo.setFitHeight(38);
        logo.setSmooth(true);
        HBox brandName = new HBox(0, Ui.label("ServerPilot", "brand-title"), Ui.label("X", "brand-x"));
        VBox brandText = new VBox(0, brandName, Ui.label("LINUX SERVER MANAGER", "brand-sub"));
        brandText.setAlignment(Pos.CENTER_LEFT);
        HBox brand = new HBox(10, logo, brandText);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setStyle("-fx-padding: 2 6 14 6;");

        VBox nav = new VBox(2);
        nav.getChildren().add(brand);
        for (Map.Entry<String, View> e : views.entrySet()) {
            String section = sections.get(e.getKey());
            if (section != null) nav.getChildren().add(Ui.label(section, "nav-section"));
            View v = e.getValue();
            ToggleButton b = new ToggleButton(v.title(), Icons.of(v.icon(), 17));
            b.getStyleClass().setAll("nav-button");
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(x -> navigate(v.id()));
            navButtons.put(v.id(), b);
            nav.getChildren().add(b);
        }
        navGroup.selectedToggleProperty().addListener((o, a, b) -> { if (b == null && a != null) a.setSelected(true); });
        Label hint = Ui.label("Ctrl+K  command palette", "faint", "small");
        VBox sidebar = new VBox(nav, Ui.spacer(), hint);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(222);
        sidebar.setMinWidth(222);
        ScrollPane sideScroll = new ScrollPane(sidebar);
        sideScroll.setFitToWidth(true);
        sideScroll.setFitToHeight(true);
        sideScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sideScroll.setMinWidth(224);

        // ---- top bar
        ProgressIndicator busy = new ProgressIndicator();
        busy.setPrefSize(18, 18);
        busy.setMaxSize(18, 18);
        busy.visibleProperty().bind(Ui.BUSY.greaterThan(0));

        serverPicker.getStyleClass().add("server-picker");
        serverPicker.setOnShowing(e -> rebuildServerMenu());
        Button paletteBtn = new Button("Search", Icons.of("search", 15));
        paletteBtn.setTooltip(new Tooltip("Command palette (Ctrl+K)"));
        paletteBtn.setOnAction(e -> palette().toggle());
        themeBtn.getStyleClass().addAll("btn-icon");
        themeBtn.setTooltip(new Tooltip("Toggle dark / light (Ctrl+Shift+L)"));
        themeBtn.setOnAction(e -> Theme.toggle());
        Theme.onChange(d -> updateThemeIcon());
        updateThemeIcon();
        Button refresh = Ui.iconButton("refresh", "Refresh (F5)", e -> { if (current != null) current.refresh(); });

        Ui.noShrink(paletteBtn, serverPicker, themeBtn, title);
        subtitle.setMinWidth(0);
        VBox titles = new VBox(2, title, subtitle);
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.SOMETIMES);
        HBox topbar = new HBox(12, titles, Ui.spacer(), busy, refresh, paletteBtn, serverPicker, themeBtn);
        topbar.getStyleClass().add("topbar");

        content.getStyleClass().add("content");
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox main = new VBox(topbar, content);

        BorderPane shell = new BorderPane();
        shell.setLeft(sideScroll);
        shell.setCenter(main);

        VBox toasts = new VBox(8);
        toasts.setAlignment(Pos.BOTTOM_RIGHT);
        toasts.setPickOnBounds(false);
        toasts.setMaxSize(440, Double.MAX_VALUE);
        toasts.setStyle("-fx-padding: 0 20 20 0;");
        StackPane.setAlignment(toasts, Pos.BOTTOM_RIGHT);

        palette = new CommandPalette(this::commands);

        StackPane root = new StackPane(shell, toasts, palette);
        Scene scene = new Scene(root, 1360, 860);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("ServerPilotX — Linux Server Manager");
        stage.getIcons().add(icon);
        stage.setMinWidth(1040);
        stage.setMinHeight(680);

        Ui.init(stage, toasts);

        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN), palette::toggle);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), Theme::toggle);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), () -> { if (current != null) current.refresh(); });
        int n = 1;
        for (String id : views.keySet()) {
            if (n > 9) break;
            KeyCode kc = KeyCode.valueOf("DIGIT" + n++);
            scene.getAccelerators().put(new KeyCodeCombination(kc, KeyCombination.SHORTCUT_DOWN), () -> navigate(id));
        }

        Hosts.onChange(h -> Ui.fx(this::hostChanged));
        updateServerPicker();
        navigate("dashboard");
    }

    private final Map<String, String> sections = new LinkedHashMap<>();

    private void add(View v, String section) {
        views.put(v.id(), v);
        if (section != null) sections.put(v.id(), section);
    }

    private CommandPalette palette() { return palette; }

    private void updateThemeIcon() {
        themeBtn.setGraphic(Icons.of(Theme.isDark() ? "sun" : "moon", 17));
    }

    public void navigate(String id) {
        View v = views.get(id);
        if (v == null) return;
        if (current != null && current != v) current.onHide();
        current = v;
        ToggleButton b = navButtons.get(id);
        if (b != null) b.setSelected(true);
        title.setText(v.title());
        updateSubtitle();
        Node n = v.node();
        content.getChildren().setAll(n);
        n.setOpacity(0);
        FadeTransition f = new FadeTransition(Duration.millis(160), n);
        f.setToValue(1);
        f.play();
        v.onShow();
    }

    public View view(String id) { return views.get(id); }

    private void updateSubtitle() {
        Host h = Hosts.active();
        String sub = current == null ? "" : current.subtitle();
        subtitle.setText(sub + (sub.isEmpty() ? "" : "  ·  ") + h.name() + (h.isLocal() ? "" : " (" + h.address() + ")") + "  ·  " + h.os());
    }

    private void updateServerPicker() {
        Host h = Hosts.active();
        HBox g = new HBox(8, Ui.dot(Metrics.get().error() == null ? "green" : "red"), Icons.of("server", 15));
        g.setAlignment(Pos.CENTER_LEFT);
        serverPicker.setGraphic(g);
        serverPicker.setText(h.name());
    }

    private void rebuildServerMenu() {
        serverPicker.getItems().clear();
        for (Host h : Hosts.all()) {
            MenuItem mi = new MenuItem(h.label(), Icons.of(h == Hosts.active() ? "check" : "server", 14));
            mi.setOnAction(e -> switchHost(h));
            serverPicker.getItems().add(mi);
        }
        serverPicker.getItems().add(new SeparatorMenuItem());
        MenuItem manage = new MenuItem("Manage servers…", Icons.of("settings", 14));
        manage.setOnAction(e -> navigate("servers"));
        serverPicker.getItems().add(manage);
    }

    /** Connects to a server (detects tools) and makes it active. */
    public void switchHost(Host h) {
        if (h == Hosts.active()) return;
        Ui.toast("Connecting to " + h.name() + "…", Ui.Kind.INFO);
        Ui.bg(() -> { h.detect(); return h; }, ok -> {
            Hosts.setActive(ok);
            Ui.toast("Now managing " + ok.name(), Ui.Kind.OK);
        }, err -> Ui.errorDialog("Cannot connect to " + h.name(), err
                + "\n\nServerPilotX connects with SSH key authentication. Check that\n  ssh -p " + h.port() + " " + h.user() + "@" + h.address()
                + "\nworks in a terminal without a password prompt."));
    }

    private void hostChanged() {
        Metrics.get().start(Hosts.active());
        updateServerPicker();
        updateSubtitle();
        for (View v : views.values()) v.onHostChanged();
    }

    private List<CommandPalette.Command> commands() {
        List<CommandPalette.Command> l = new ArrayList<>();
        for (View v : views.values()) {
            l.add(new CommandPalette.Command("Go to " + v.title(), v.subtitle(), v.icon(), () -> navigate(v.id())));
        }
        l.add(new CommandPalette.Command("Toggle dark / light theme", "Ctrl+Shift+L", "sun", Theme::toggle));
        l.add(new CommandPalette.Command("Refresh current page", "F5", "refresh", () -> { if (current != null) current.refresh(); }));
        for (Host h : Hosts.all()) {
            l.add(new CommandPalette.Command("Switch server: " + h.name(), h.isLocal() ? "local" : h.address(), "server", () -> switchHost(h)));
        }
        l.addAll(DashboardView.quickCommands());
        return l;
    }

    public Stage stage() { return stage; }
}
