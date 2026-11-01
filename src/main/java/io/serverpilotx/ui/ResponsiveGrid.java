package io.serverpilotx.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Lays children out in as many equal-ish columns as fit (each at least {@code minCol} wide),
 * wrapping to new rows on narrow windows. Optional weights make some cells wider.
 */
public final class ResponsiveGrid extends Pane {
    private final double minCol;
    private final double gap;
    private final double[] weights;

    public ResponsiveGrid(double minCol, double gap, double[] weights, Node... children) {
        this.minCol = minCol;
        this.gap = gap;
        this.weights = weights;
        getChildren().addAll(children);
    }

    public ResponsiveGrid(double minCol, Node... children) {
        this(minCol, 16, null, children);
    }

    private int columns(double w) {
        int n = managed().size();
        if (n == 0) return 1;
        int c = (int) Math.floor((w + gap) / (minCol + gap));
        return Math.max(1, Math.min(n, c));
    }

    private List<Node> managed() {
        return getManagedChildren();
    }

    private double weight(int i) {
        return weights != null && i < weights.length ? weights[i] : 1;
    }

    private double layoutRows(double width, boolean apply) {
        Insets in = getInsets();
        double w = width - in.getLeft() - in.getRight();
        List<Node> kids = managed();
        int cols = columns(w);
        double y = in.getTop();
        for (int start = 0; start < kids.size(); start += cols) {
            int end = Math.min(kids.size(), start + cols);
            double total = 0;
            for (int i = start; i < end; i++) total += weight(i);
            double avail = w - gap * (end - start - 1);
            double rowH = 0;
            double[] widths = new double[end - start];
            for (int i = start; i < end; i++) {
                widths[i - start] = avail * weight(i) / total;
                rowH = Math.max(rowH, kids.get(i).prefHeight(widths[i - start]));
            }
            if (apply) {
                double x = in.getLeft();
                for (int i = start; i < end; i++) {
                    kids.get(i).resizeRelocate(x, y, widths[i - start], rowH);
                    x += widths[i - start] + gap;
                }
            }
            y += rowH + gap;
        }
        return y - (kids.isEmpty() ? 0 : gap) + in.getBottom();
    }

    @Override
    public javafx.geometry.Orientation getContentBias() {
        return javafx.geometry.Orientation.HORIZONTAL;
    }

    @Override
    protected void layoutChildren() {
        layoutRows(getWidth(), true);
    }

    @Override
    protected double computePrefHeight(double width) {
        return layoutRows(width < 0 ? Math.max(getWidth(), minCol) : width, false);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computeMinWidth(double height) {
        return minCol;
    }

    @Override
    protected double computePrefWidth(double height) {
        return minCol * Math.max(1, managed().size()) + gap * Math.max(0, managed().size() - 1);
    }
}
