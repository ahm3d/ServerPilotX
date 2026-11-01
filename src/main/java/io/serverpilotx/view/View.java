package io.serverpilotx.view;

import io.serverpilotx.core.Host;
import io.serverpilotx.core.Hosts;
import javafx.scene.Node;

/** A page in the main window. The node is built lazily the first time it is shown. */
public abstract class View {
    private Node node;
    protected boolean visible;

    public abstract String id();

    public abstract String title();

    public abstract String subtitle();

    public abstract String icon();

    protected abstract Node build();

    public final Node node() {
        if (node == null) node = build();
        return node;
    }

    public final boolean built() {
        return node != null;
    }

    /** Called each time the page becomes visible. */
    public void onShow() {
        visible = true;
        refresh();
    }

    public void onHide() {
        visible = false;
    }

    /** Called when the active server changes. */
    public void onHostChanged() {
        if (visible) refresh();
    }

    /** Reload data from the server. */
    public void refresh() {}

    protected Host host() {
        return Hosts.active();
    }
}
