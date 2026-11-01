package io.serverpilotx.view;

import java.nio.file.Files;
import java.nio.file.Path;

import io.serverpilotx.App;
import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import io.serverpilotx.core.Metrics;
import io.serverpilotx.core.Settings;
import io.serverpilotx.ui.ResponsiveGrid;
import io.serverpilotx.ui.Terminals;
import io.serverpilotx.ui.Theme;
import io.serverpilotx.ui.Ui;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** App preferences and about. */
public final class SettingsView extends View {
    private final ToggleGroup themeGroup = new ToggleGroup();

    @Override public String id() { return "settings"; }
    @Override public String title() { return "Settings"; }
    @Override public String subtitle() { return "Preferences"; }
    @Override public String icon() { return "settings"; }

    @Override
    protected Node build() {
        // appearance
        RadioButton sys = radio("Follow system", Settings.Theme.SYSTEM);
        RadioButton dark = radio("Dark", Settings.Theme.DARK);
        RadioButton light = radio("Light", Settings.Theme.LIGHT);
        Theme.onChange(d -> syncTheme());
        VBox appearance = Ui.card("Appearance", new HBox(18, dark, light, sys),
                Ui.label("Shortcut: Ctrl+Shift+L toggles dark / light.", "faint", "small"));

        // privileges
        ComboBox<String> priv = new ComboBox<>(FXCollections.observableArrayList("sudo (password asked once per session)", "pkexec (polkit prompt each time)"));
        priv.setValue(Settings.privMethod() == Settings.PrivMethod.SUDO ? priv.getItems().get(0) : priv.getItems().get(1));
        priv.setOnAction(e -> Settings.privMethod(priv.getSelectionModel().getSelectedIndex() == 0 ? Settings.PrivMethod.SUDO : Settings.PrivMethod.PKEXEC));
        VBox privileges = Ui.card("Administrator access",
                new Ui.Form().row("Local method", priv, "Remote servers always use sudo over SSH"),
                Ui.toolbar(Ui.button("Forget saved passwords", "lock", e -> {
                    for (Host h : Hosts.all()) h.privilege().clear();
                    Ui.toast("Cached sudo passwords cleared", Ui.Kind.OK);
                })));

        // metrics
        Spinner<Integer> interval = new Spinner<>(1, 60, Settings.sampleSeconds());
        interval.valueProperty().addListener((o, a, b) -> { Settings.sampleSeconds(b); Metrics.get().start(Hosts.active()); });
        Spinner<Integer> retention = new Spinner<>(1, 365, Settings.retentionDays());
        retention.valueProperty().addListener((o, a, b) -> Settings.retentionDays(b));
        VBox metrics = Ui.card("Metrics",
                new Ui.Form().row("Sample every (s)", interval).row("Keep history (days)", retention, "One averaged point per minute is stored per server"),
                Ui.toolbar(Ui.button("Clear history", "trash", e -> {
                    if (!Ui.confirm("Clear metrics history", "Delete stored metrics for all servers?", "Clear", true)) return;
                    try (var s = Files.list(Settings.dataDir().resolve("metrics"))) {
                        s.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
                        Ui.toast("Metrics history cleared", Ui.Kind.OK);
                    } catch (Exception ex) {
                        Ui.toast(Ui.message(ex), Ui.Kind.ERROR);
                    }
                })));

        // paths
        TextField stacks = new TextField(Settings.stacksRoot());
        stacks.textProperty().addListener((o, a, b) -> { if (b.startsWith("/")) Settings.stacksRoot(b.strip()); });
        TextField roots = new TextField(String.join(",", Settings.fileRoots()));
        roots.textProperty().addListener((o, a, b) -> Settings.fileRoots(b));
        TextField email = new TextField(Settings.acmeEmail());
        email.textProperty().addListener((o, a, b) -> Settings.acmeEmail(b.strip()));
        VBox paths = Ui.card("Locations",
                new Ui.Form().row("Compose stacks", stacks, "Plain docker-compose.yml folders, hand-editable")
                        .row("File manager roots", roots, "Comma separated; ~ is the SSH user's home")
                        .row("ACME email", email, "Default contact for Let's Encrypt"));

        // terminal
        ComboBox<String> term = new ComboBox<>(FXCollections.observableArrayList("auto", "foot", "kitty", "alacritty", "wezterm",
                "gnome-terminal", "konsole", "xfce4-terminal", "tilix", "ptyxis", "xterm"));
        term.setEditable(true);
        term.setValue(Settings.terminalEmulator());
        term.valueProperty().addListener((o, a, b) -> { if (b != null && !b.isBlank()) Settings.terminalEmulator(b.strip()); });
        Label detected = Ui.label("Detected: " + (Terminals.detect() == null ? "none" : Terminals.detect()), "faint", "small");
        CheckBox record = new CheckBox("Record terminal commands in the audit log");
        record.setSelected(Settings.recordTerminal());
        record.setOnAction(e -> Settings.recordTerminal(record.isSelected()));
        VBox terminal = Ui.card("Terminal", new Ui.Form().row("Terminal emulator", term).full(detected).full(record));

        // about
        ImageView logo = new ImageView(new Image(App.class.getResource("/io/serverpilotx/img/icon-256.png").toExternalForm()));
        logo.setFitWidth(72);
        logo.setFitHeight(72);
        HBox nameRow = new HBox(0, Ui.label("ServerPilot", "h1"), Ui.label("X", "h1", "text-accent"));
        Label desc = Ui.label("A self-hosted Linux server manager for Docker, Nginx, SSL, firewalls, SSH and metrics. "
                + "Everything it writes is plain config on disk — uninstalling leaves a working server. No telemetry.", "muted");
        desc.setWrapText(true);
        VBox aboutText = new VBox(4, nameRow, Ui.label("Version " + App.VERSION + "  ·  AGPL-3.0", "muted"), desc,
                Ui.label("Data: " + Settings.dataDir(), "faint", "small"), Ui.label("Java " + System.getProperty("java.version")
                        + "  ·  JavaFX " + System.getProperty("javafx.runtime.version", "?"), "faint", "small"));
        HBox about = new HBox(18, logo, aboutText);
        about.setAlignment(Pos.TOP_LEFT);
        VBox aboutCard = Ui.card("About", about);

        ResponsiveGrid grid = new ResponsiveGrid(380, 16, null, appearance, privileges, metrics, terminal);
        VBox page = new VBox(16, grid, paths, aboutCard);
        page.getStyleClass().add("page");
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        syncTheme();
        return sp;
    }

    private RadioButton radio(String text, Settings.Theme t) {
        RadioButton r = new RadioButton(text);
        r.setUserData(t);
        r.setToggleGroup(themeGroup);
        r.setOnAction(e -> Theme.set(t));
        return r;
    }

    private void syncTheme() {
        Settings.Theme cur = Settings.theme();
        themeGroup.getToggles().forEach(t -> t.setSelected(t.getUserData() == cur));
    }

    static Path data() { return Settings.dataDir(); }
}
