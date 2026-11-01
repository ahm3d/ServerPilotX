package io.serverpilotx.ui;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

/**
 * Debug helper: saves a PNG of a scene when SPX_SNAPSHOT_DIR is set.
 * Uses a tiny built-in PNG encoder so no extra JDK modules are needed.
 */
public final class Snapshot {
    private Snapshot() {}

    public static String dir() {
        return System.getenv("SPX_SNAPSHOT_DIR");
    }

    public static void save(Scene scene, String name) {
        String d = dir();
        if (d == null || scene == null) return;
        try {
            WritableImage img = scene.snapshot(null);
            writePng(img, Path.of(d, name + ".png"));
        } catch (Exception e) {
            System.err.println("snapshot failed: " + e);
        }
    }

    private static void writePng(WritableImage img, Path out) throws IOException {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        PixelReader pr = img.getPixelReader();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(raw)) {
            byte[] row = new byte[1 + w * 3];
            for (int y = 0; y < h; y++) {
                row[0] = 0;
                for (int x = 0; x < w; x++) {
                    int argb = pr.getArgb(x, y);
                    row[1 + x * 3] = (byte) (argb >> 16);
                    row[2 + x * 3] = (byte) (argb >> 8);
                    row[3 + x * 3] = (byte) argb;
                }
                z.write(row);
            }
        }
        try (OutputStream fo = Files.newOutputStream(out); DataOutputStream o = new DataOutputStream(fo)) {
            o.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            DataOutputStream ih = new DataOutputStream(ihdr);
            ih.writeInt(w);
            ih.writeInt(h);
            ih.write(new byte[]{8, 2, 0, 0, 0});
            chunk(o, "IHDR", ihdr.toByteArray());
            chunk(o, "IDAT", raw.toByteArray());
            chunk(o, "IEND", new byte[0]);
        }
    }

    private static void chunk(DataOutputStream o, String type, byte[] data) throws IOException {
        o.writeInt(data.length);
        byte[] t = type.getBytes();
        o.write(t);
        o.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        o.writeInt((int) crc.getValue());
    }
}
