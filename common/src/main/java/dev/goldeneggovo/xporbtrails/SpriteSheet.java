package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

final class SpriteSheet {
    record Frame(int x, int y, int width, int height, int sourceWidth, int sourceHeight,
                 int offsetX, int offsetY, double pivotX, double pivotY) {}
    record Ribbon(int tailPixels, int headPixels, String middle) {}

    private static final int MAX_DIMENSION = 4096;
    private static final int MAX_PIXELS = 4 * 1024 * 1024;
    private static final int MAX_FRAMES = 1024;
    private static final int DEFAULT_FPS = 12;
    private final Component displayName;
    private final boolean loop;
    private final BufferedImage image;
    private final Frame[] frames;
    private final int[] endMillis;
    private final Ribbon ribbon;

    private SpriteSheet(Component displayName, boolean loop, BufferedImage image, Frame[] frames, int[] endMillis, Ribbon ribbon) {
        this.displayName = displayName;
        this.loop = loop;
        this.image = image;
        this.frames = frames;
        this.endMillis = endMillis;
        this.ribbon = ribbon;
    }

    Component displayName() { return displayName; }
    boolean loop() { return loop; }
    Ribbon ribbon() { return ribbon; }
    BufferedImage image() { return image; }
    int width() { return image.getWidth(); }
    int height() { return image.getHeight(); }
    int durationMillis() { return endMillis[endMillis.length - 1]; }

    Frame frameAt(double seconds, boolean repeat) {
        double millis = seconds * 1000;
        if (!Double.isFinite(millis) || millis < 0) return null;
        int duration = durationMillis();
        if (!repeat && seconds >= duration / 1000.0) return null;
        // Compare in seconds so the representable timestamp 1.001 starts millisecond 1001.
        double nearest = Math.rint(millis);
        double wholeMillis = seconds < nearest / 1000.0 ? nearest - 1 : nearest;
        int position = (int) (wholeMillis % duration);
        int index = Arrays.binarySearch(endMillis, position + 1);
        return frames[index >= 0 ? index : -index - 1];
    }

    static SpriteSheet read(ImageReader png, JsonObject descriptor, String stem, int availablePixels) throws IOException {
        int width = png.getWidth(0);
        int height = png.getHeight(0);
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw new IOException("PNG exceeds atlas dimensions (4096 per side, 4 Mi pixels per atlas)");
        }
        if (width * height > availablePixels) throw new IOException("Total sprite atlas pixel budget exceeded");

        JsonObject meta = optionalObject(descriptor, "meta");
        if (meta.has("size")) {
            JsonObject size = object(meta.get("size"), "meta.size");
            if (positive(size, "w") != width || positive(size, "h") != height) {
                throw new IOException("meta.size does not match the PNG dimensions");
            }
        }
        JsonElement entries = descriptor.get("frames");
        boolean atlas = entries != null && (entries.isJsonObject() || entries.isJsonArray());
        JsonObject settings = atlas ? optionalObject(meta, "pixelfx") : descriptor;
        Component displayName;
        if (settings.has("name")) {
            String name = string(settings, "name").strip();
            if (name.isEmpty()) throw new IOException("Sprite name must contain visible text");
            displayName = Component.literal(name);
        } else {
            displayName = Component.translatableWithFallback("sprite.xporbtrails." + stem, stem);
        }
        boolean loop = bool(settings, "loop", true);
        double fps = settings.has("fps") ? number(settings.get("fps"), "fps") : DEFAULT_FPS;
        if (fps <= 0 || fps > 1000) throw new IOException("fps must be positive and at most 1000");
        double[] pivot = null;
        if (settings.has("pivot")) {
            JsonElement raw = settings.get("pivot");
            if (!raw.isJsonArray() || raw.getAsJsonArray().size() != 2) throw new IOException("Expected pivot [x,y]");
            JsonArray coordinates = raw.getAsJsonArray();
            pivot = new double[]{number(coordinates.get(0), "pivot.x"), number(coordinates.get(1), "pivot.y")};
        }

        Frame[] frames;
        int[] endMillis;
        if (atlas) {
            int count = entries.isJsonArray() ? entries.getAsJsonArray().size() : entries.getAsJsonObject().size();
            checkFrameCount(count);
            frames = new Frame[count];
            endMillis = new int[count];
            Iterable<JsonElement> sequence = entries.isJsonArray() ? entries.getAsJsonArray() : entries.getAsJsonObject().asMap().values();
            int index = 0;
            long total = 0;
            for (JsonElement entry : sequence) {
                JsonObject frame = object(entry, "frames[" + index + "]");
                frames[index] = atlasFrame(frame, width, height, pivot);
                total += frame.has("duration") ? positive(frame, "duration") : roundedMillis(1000 / fps);
                if (total > Integer.MAX_VALUE) throw new IOException("Animation duration exceeds supported range");
                endMillis[index++] = (int) total;
            }
        } else {
            int shortSide = Math.min(width, height);
            boolean grid = descriptor.has("frameWidth") || descriptor.has("frameHeight");
            int frameWidth = grid ? positive(descriptor, "frameWidth") : shortSide;
            int frameHeight = grid ? positive(descriptor, "frameHeight") : shortSide;
            if (width % frameWidth != 0 || height % frameHeight != 0) {
                throw new IOException("Frame dimensions must divide the PNG exactly; use a JSON descriptor for rectangular frames");
            }
            int columns = width / frameWidth;
            int cells = columns * (height / frameHeight);
            int count = descriptor.has("frames") ? positive(descriptor, "frames") : cells;
            checkFrameCount(count);
            if (count > cells) throw new IOException("Frame count exceeds the number of grid cells");
            frames = new Frame[count];
            endMillis = new int[count];
            for (int i = 0; i < count; i++) {
                frames[i] = new Frame(i % columns * frameWidth, i / columns * frameHeight, frameWidth, frameHeight,
                        frameWidth, frameHeight, 0, 0, pivot == null ? frameWidth / 2.0 : pivot[0],
                        pivot == null ? frameHeight / 2.0 : pivot[1]);
                endMillis[i] = roundedMillis((i + 1) * 1000.0 / fps);
            }
        }
        JsonElement rawRibbon = descriptor.has("ribbon") ? descriptor.get("ribbon") : settings.get("ribbon");
        Ribbon ribbon = rawRibbon == null ? null : readRibbon(rawRibbon, frames);
        return new SpriteSheet(displayName, loop, png.read(0), frames, endMillis, ribbon);
    }

    private static Ribbon readRibbon(JsonElement raw, Frame[] frames) throws IOException {
        JsonObject ribbon = object(raw, "ribbon");
        int tail = integer(ribbon, "tailPixels"), head = integer(ribbon, "headPixels");
        if (tail < 0 || head < 0) throw new IOException("Ribbon cap sizes must be non-negative");
        for (Frame frame : frames) {
            if ((long) tail + head >= frame.sourceWidth()) {
                throw new IOException("Ribbon caps must leave at least one middle pixel in every frame");
            }
        }
        String middle = string(ribbon, "middle");
        if (!TrailConfig.RIBBON_MAPPINGS.contains(middle)) throw new IOException("Unknown ribbon.middle: " + middle);
        return new Ribbon(tail, head, middle);
    }

    private static Frame atlasFrame(JsonObject entry, int atlasWidth, int atlasHeight, double[] pivot) throws IOException {
        if (bool(entry, "rotated", false)) throw new IOException("Rotated atlas frames are unsupported");
        JsonObject rect = object(entry.get("frame"), "frame");
        int x = integer(rect, "x"), y = integer(rect, "y");
        int w = positive(rect, "w"), h = positive(rect, "h");
        if (x < 0 || y < 0 || x > atlasWidth - w || y > atlasHeight - h) {
            throw new IOException("Frame rectangle lies outside the PNG");
        }
        int sourceW = w, sourceH = h, offsetX = 0, offsetY = 0;
        if (entry.has("sourceSize")) {
            JsonObject original = object(entry.get("sourceSize"), "sourceSize");
            sourceW = positive(original, "w");
            sourceH = positive(original, "h");
        }
        if (bool(entry, "trimmed", false) && !(entry.has("spriteSourceSize") && entry.has("sourceSize"))) {
            throw new IOException("Trimmed frames require sourceSize and spriteSourceSize");
        }
        if (entry.has("spriteSourceSize")) {
            JsonObject trim = object(entry.get("spriteSourceSize"), "spriteSourceSize");
            offsetX = integer(trim, "x");
            offsetY = integer(trim, "y");
            if (positive(trim, "w") != w || positive(trim, "h") != h) {
                throw new IOException("spriteSourceSize must match the unrotated frame rectangle");
            }
        }
        if (offsetX < 0 || offsetY < 0 || offsetX > sourceW - w || offsetY > sourceH - h) {
            throw new IOException("Trimmed rectangle lies outside sourceSize");
        }
        return new Frame(x, y, w, h, sourceW, sourceH, offsetX, offsetY,
                pivot == null ? sourceW / 2.0 : pivot[0], pivot == null ? sourceH / 2.0 : pivot[1]);
    }

    private static void checkFrameCount(int count) throws IOException {
        if (count < 1 || count > MAX_FRAMES) throw new IOException("Expected between 1 and " + MAX_FRAMES + " frames");
    }

    private static int roundedMillis(double millis) throws IOException {
        long rounded = Math.round(millis);
        if (rounded < 1 || rounded > Integer.MAX_VALUE) throw new IOException("Animation duration exceeds supported range");
        return (int) rounded;
    }

    static JsonObject object(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("Expected JSON object for " + field);
        return value.getAsJsonObject();
    }

    private static JsonObject optionalObject(JsonObject obj, String field) throws IOException {
        return obj.has(field) ? object(obj.get(field), field) : new JsonObject();
    }

    static String string(JsonObject obj, String field) throws IOException {
        JsonElement value = obj.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Expected string for " + field);
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject obj, String field, boolean fallback) throws IOException {
        if (!obj.has(field)) return fallback;
        JsonElement value = obj.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IOException("Expected boolean for " + field);
        return value.getAsBoolean();
    }

    private static double number(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(value.getAsDouble())) throw new IOException("Expected finite number for " + field);
        return value.getAsDouble();
    }

    private static int integer(JsonObject obj, String field) throws IOException {
        JsonElement value = obj.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Expected integer for " + field);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException("Expected integer for " + field);
        }
    }

    private static int positive(JsonObject obj, String field) throws IOException {
        int value = integer(obj, field);
        if (value <= 0) throw new IOException(field + " must be positive");
        return value;
    }
}
