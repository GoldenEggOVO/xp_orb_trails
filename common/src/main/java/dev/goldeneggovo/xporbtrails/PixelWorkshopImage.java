package dev.goldeneggovo.xporbtrails;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Atlas sampling in original, untrimmed sprite coordinates. */
final class PixelWorkshopImage {
    record Image(BufferedImage png, SpriteSheet.Frame frame) {
        int relative(double u, double v) {
            int x = (int) Math.floor(u * frame.sourceWidth()) - frame.offsetX();
            int y = (int) Math.floor(v * frame.sourceHeight()) - frame.offsetY();
            if (x < 0 || y < 0 || x >= frame.width() || y >= frame.height()) return 0;
            return png.getRGB(frame.x() + x, frame.y() + y);
        }
    }

    record Cell(double x, double y, int width, int height, int argb, boolean edge) {}
    record Strip(SpriteSheet.Frame uv, int shard, double x, double y) {}
    record IntactStrip(SpriteSheet.Frame uv, double x, double y) {}

    static List<IntactStrip> intactStrips(SpriteSheet.Frame frame, int step, boolean[] holes) {
        int cols = Math.ceilDiv(frame.width(), step);
        var strips = new ArrayList<IntactStrip>();
        for (int y = 0; y < frame.height(); y += step) {
            int h = Math.min(step, frame.height() - y);
            int row = y / step * cols;
            for (int x = 0; x < cols;) {
                while (x < cols && holes[row + x]) x++;
                int start = x;
                while (x < cols && !holes[row + x]) x++;
                if (x == start) continue;
                int left = start * step;
                int w = Math.min(frame.width(), x * step) - left;
                var uv = new SpriteSheet.Frame(frame.x() + left, frame.y() + y, w, h, w, h, 0, 0, w * .5, h * .5);
                strips.add(new IntactStrip(uv, frame.offsetX() + left + w * .5 - frame.pivotX(),
                        frame.pivotY() - frame.offsetY() - y - h * .5));
            }
        }
        return strips;
    }

    private record SampleKey(Image image, int resolution) {}
    private record Samples(List<Cell> fill, List<Cell> outline) {}
    private static final Map<SampleKey, Samples> samples = new LinkedHashMap<>(32, .75f, true);
    static void clear() { samples.clear(); }

    static List<Cell> cells(Image image) {
        return cells(image, 32, false);
    }

    static List<Cell> cells(Image image, int resolution, boolean edgeOnly) {
        var key = new SampleKey(image, resolution);
        var cached = samples.get(key);
        if (cached != null) return edgeOnly ? cached.outline : cached.fill;
        if (samples.size() >= 64) samples.remove(samples.keySet().iterator().next());
        var f = image.frame;
        int step = Math.ceilDiv(Math.max(f.width(), f.height()), resolution);
        List<Cell> result = new ArrayList<>();
        List<Cell> outline = new ArrayList<>();
        for (int y = 0; y < f.height(); y += step) {
            int h = Math.min(step, f.height() - y);
            for (int x = 0; x < f.width(); x += step) {
                int w = Math.min(step, f.width() - x);
                int sx = x + w / 2, sy = y + h / 2;
                int color = image.png.getRGB(f.x() + sx, f.y() + sy);
                if (color >>> 24 == 0) continue;
                boolean edge = empty(image, sx - step, sy) || empty(image, sx + step, sy)
                        || empty(image, sx, sy - step) || empty(image, sx, sy + step);
                var cell = new Cell(f.offsetX() + x + w * .5 - f.pivotX(),
                        f.pivotY() - f.offsetY() - y - h * .5, w, h, color, edge);
                result.add(cell);
                if (edge) outline.add(cell);
            }
        }
        var frozen = new Samples(List.copyOf(result), List.copyOf(outline));
        samples.put(key, frozen);
        return edgeOnly ? frozen.outline : frozen.fill;
    }

    private static boolean empty(Image source, int x, int y) {
        var f = source.frame;
        return x < 0 || y < 0 || x >= f.width() || y >= f.height()
                || source.png.getRGB(f.x() + x, f.y() + y) >>> 24 == 0;
    }

    static double brightness(int argb) {
        return (.2126 * (argb >> 16 & 255) + .7152 * (argb >> 8 & 255) + .0722 * (argb & 255))
                * (argb >>> 24) / (255 * 255);
    }

    static int tint(int argb, int tint, double opacity) {
        return Math.clamp((int) Math.round((argb >>> 24) * (tint >>> 24) / 255.0 * opacity), 0, 255) << 24
                | ((argb >> 16 & 255) * (tint >> 16 & 255) / 255) << 16
                | ((argb >> 8 & 255) * (tint >> 8 & 255) / 255) << 8
                | (argb & 255) * (tint & 255) / 255;
    }

    static int sector(double dx, double dz, double heading) {
        double turns = (Math.atan2(-dx, dz) - heading) / (Math.PI / 4);
        return Math.floorMod((int) Math.floor(turns + .5), 8);
    }

    /** Each strip retains its complete source UV rectangle, even for high resolution artwork. */
    static List<Strip> fracture(SpriteSheet.Frame f, Image map, int pieces, long seed) {
        int step = Math.ceilDiv(Math.max(f.width(), f.height()), 32);
        int w = Math.ceilDiv(f.width(), step), h = Math.ceilDiv(f.height(), step);
        int[] group = new int[w * h];
        if (map == null || !mapRegions(f, map, step, w, h, group)) {
            var random = new Random(seed);
            double[] cx = new double[pieces], cy = new double[pieces];
            for (int i = 0; i < pieces; i++) {
                cx[i] = random.nextDouble(w);
                cy[i] = random.nextDouble(h);
            }
            for (int i = 0; i < group.length; i++) {
                double closest = Double.POSITIVE_INFINITY;
                for (int p = 0; p < pieces; p++) {
                    double x = i % w + .5 - cx[p], y = i / w + .5 - cy[p];
                    double distance = x * x + y * y;
                    if (distance < closest) { closest = distance; group[i] = p; }
                }
            }
        }
        var strips = new ArrayList<Strip>();
        for (int y = 0; y < h; y++) {
            for (int x = 0, end; x < w; x = end) {
                int shard = group[y * w + x];
                end = x + 1;
                while (end < w && group[y * w + end] == shard) end++;
                int width = Math.min(f.width(), end * step) - x * step;
                int height = Math.min(step, f.height() - y * step);
                var uv = new SpriteSheet.Frame(f.x() + x * step, f.y() + y * step, width, height,
                        width, height, 0, 0, width * .5, height * .5);
                strips.add(new Strip(uv, shard, f.offsetX() + x * step + width * .5 - f.pivotX(),
                        f.pivotY() - f.offsetY() - y * step - height * .5));
            }
        }
        return strips;
    }

    // Dark opaque lines divide white or transparent paper into four-connected islands.
    // Grow the islands back across the ink so no original texture pixels disappear.
    private static boolean mapRegions(SpriteSheet.Frame f, Image map, int step, int w, int h, int[] ids) {
        Arrays.fill(ids, -1);
        for (int i = 0; i < ids.length; i++) {
            double x = f.offsetX() + Math.min(f.width() - .5, (i % w + .5) * step);
            double y = f.offsetY() + Math.min(f.height() - .5, (i / w + .5) * step);
            int ink = map.relative(x / f.sourceWidth(), y / f.sourceHeight());
            if ((ink >>> 24) >= 128 && brightness(ink) < .4) ids[i] = -2;
        }
        var queue = new ArrayDeque<Integer>();
        int islands = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != -1) continue;
            ids[i] = islands++ % 64;
            queue.add(i);
            flood(ids, w, queue, -1);
        }
        if (islands == 0) return false;
        for (int i = 0; i < ids.length; i++) if (ids[i] >= 0) queue.add(i);
        flood(ids, w, queue, -2);
        return true;
    }

    private static void flood(int[] ids, int w, ArrayDeque<Integer> queue, int empty) {
        while (!queue.isEmpty()) {
            int i = queue.removeFirst();
            if (i % w > 0) fill(ids, queue, i - 1, ids[i], empty);
            if ((i + 1) % w > 0) fill(ids, queue, i + 1, ids[i], empty);
            if (i >= w) fill(ids, queue, i - w, ids[i], empty);
            if (i + w < ids.length) fill(ids, queue, i + w, ids[i], empty);
        }
    }

    private static void fill(int[] ids, ArrayDeque<Integer> queue, int i, int value, int empty) {
        if (ids[i] != empty) return;
        ids[i] = value;
        queue.addLast(i);
    }
}
