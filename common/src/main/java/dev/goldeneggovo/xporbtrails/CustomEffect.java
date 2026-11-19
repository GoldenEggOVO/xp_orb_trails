package dev.goldeneggovo.xporbtrails;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** A file-defined animation shared by flight, pickup and the settings preview. */
final class CustomEffect {
    private static final int HEAD = -1, TAIL = -2, INHERIT = -3;
    private static final Pattern RGB = Pattern.compile("#[0-9a-fA-F]{6}");
    private final String name, nameKey;
    private final List<Layer> layers;
    final ParticleEmitter emitter;
    final boolean reflow;
    final float rainbowSaturation;

    private enum Ease {
        LINEAR, SMOOTH, OUT;

        double at(double t) {
            return switch (this) {
                case LINEAR -> t;
                case SMOOTH -> TrailEffects.smooth(t);
                case OUT -> t * (2 - t);
            };
        }
    }

    private static final class Layer {
        EffectShape shape = EffectShape.RECTANGLE;
        RadialShape radial;
        int count = 1, color = HEAD, alternateColor = INHERIT, segments = 1;
        boolean contrast, rotateCopies = true, useShardCount, useSpiralTurns;
        double turns, sweep, startRadiusScale = 1, startOpacity = 1, light;
        double radiusVariation, radiusFrequency, sizeVariation, sizeFrequency;
        Ease easing = Ease.LINEAR, radiusEasing, opacityEasing;
        List<Frame> frames;

        int geometryCost() {
            return (useShardCount ? 16 : count) * (radial == null ? segments : radial.size());
        }
    }

    private record Frame(double t, double radius, double angle, double x, double y,
                         double width, double height, double rotation, double opacity, int color, int alternateColor) {
        private static final Frame DEFAULT = new Frame(0, 0, 0, 0, 0, 0.25, 0.25, 0, 1, INHERIT, INHERIT);

        int primary(Layer layer) {
            return color == INHERIT ? layer.color : color;
        }

        int secondary(Layer layer) {
            if (alternateColor != INHERIT) return alternateColor;
            return layer.alternateColor == INHERIT ? primary(layer) : layer.alternateColor;
        }
    }

    private CustomEffect(String name, String nameKey, List<Layer> layers, boolean reflow, double rainbowSaturation,
                         ParticleEmitter emitter) {
        this.name = name;
        this.nameKey = nameKey;
        this.layers = layers;
        this.reflow = reflow;
        this.rainbowSaturation = (float) rainbowSaturation;
        this.emitter = emitter;
        if (emitter != null) emitter.limitGeometry(layers.stream().mapToInt(Layer::geometryCost).sum());
    }

    String name() {
        return name;
    }

    Component displayName() {
        return nameKey == null ? Component.literal(name) : Component.translatableWithFallback(nameKey, name);
    }

    boolean usesTrailColors() {
        for (Layer layer : layers) {
            for (Frame frame : layer.frames) {
                if (usesTrailColor(frame.primary(layer))
                        || ((layer.count > 1 || layer.useShardCount) && usesTrailColor(frame.secondary(layer)))) return true;
            }
        }
        return false;
    }

    private boolean usesTrailColor(int color) {
        return color == TAIL || (color == HEAD && (emitter == null || !emitter.hasPalette()));
    }

    private static int rgb(int color, int tail, int head) {
        return color == HEAD ? head : color == TAIL ? tail : color;
    }

    private static int blend(int from, int to, double u, int tail, int head) {
        int start = rgb(from, tail, head);
        return from == to ? start : TrailEffects.mix(start, rgb(to, tail, head), u);
    }

    void draw(double progress, double strength, TrailConfig config, int tailColor, int headColor, TrailEffects.PieceSink sink) {
        draw(progress, strength, config, tailColor, headColor, 1, 0, sink);
    }

    void drawParticle(ParticleEmitter.Particle particle, double strength, TrailConfig config, int tailColor, int headColor,
                      TrailEffects.PieceSink sink) {
        draw(particle.progress(), strength, config, tailColor, particle.headColor() < 0 ? headColor : particle.headColor(),
                particle.size(), Math.toRadians(particle.rotation()), sink);
    }

    private void draw(double progress, double strength, TrailConfig config, int tailColor, int headColor,
                      double size, double spin, TrailEffects.PieceSink sink) {
        if (!(progress >= 0 && progress < 1) || strength <= 0) return;
        double cos = Math.cos(spin), sin = Math.sin(spin);
        for (Layer layer : layers) {
            List<Frame> frames = layer.frames;
            int end = 1;
            while (progress > frames.get(end).t) end++;
            Frame a = frames.get(end - 1), b = frames.get(end);
            double time = (progress - a.t) / (b.t - a.t);
            double u = layer.easing.at(time);
            double localX = lerp(a.x, b.x, u), localY = lerp(a.y, b.y, u);
            double x = size * (localX * cos - localY * sin), y = size * (localX * sin + localY * cos);
            double radius = size * lerp(a.radius, b.radius, layer.radiusEasing.at(time));
            double turns = layer.useSpiralTurns ? config.spiralTurns : layer.turns;
            double angle = spin + Math.toRadians(lerp(a.angle, b.angle, u)) + Math.TAU * turns * progress;
            double rotation = spin + Math.toRadians(lerp(a.rotation, b.rotation, u));
            double halfWidth = size * lerp(a.width, b.width, u) * 0.5;
            double halfHeight = size * lerp(a.height, b.height, u) * 0.5;
            double opacity = strength * lerp(a.opacity, b.opacity, layer.opacityEasing.at(time));
            if (opacity <= 0 || halfWidth == 0 || halfHeight == 0) continue;
            int count = layer.useShardCount ? config.shardCount : layer.count;
            int primary = blend(a.primary(layer), b.primary(layer), u, tailColor, headColor);
            int secondary = blend(a.secondary(layer), b.secondary(layer), u, tailColor, headColor);
            if (layer.contrast && secondary == primary) {
                int brightness = 3 * (primary >> 16 & 255) + 6 * (primary >> 8 & 255) + (primary & 255);
                secondary = brightness > 1920 ? TrailEffects.mix(primary, 0, 0.38) : TrailEffects.litColor(primary, 0.8);
            }
            for (int i = 0; i < count; i++) {
                double offset = Math.TAU * i / count;
                double reach = radius * (1 - layer.radiusVariation * (1 - Math.cos(i * layer.radiusFrequency)));
                double scale = 1 - layer.sizeVariation * (1 - Math.sin(i * layer.sizeFrequency));
                int color = TrailEffects.litColor((i & 1) == 0 ? primary : secondary, layer.light);
                layer.shape.draw(sink, x, y, angle + offset, reach, rotation + (layer.rotateCopies ? offset : 0),
                        halfWidth * scale, halfHeight * scale, opacity, color, layer.segments,
                        layer.sweep, layer.startRadiusScale, layer.startOpacity, layer.radial);
            }
        }
    }

    private static double lerp(double from, double to, double u) {
        return (1 - u) * from + u * to;
    }

    static CustomEffect read(Reader reader, String fallbackName) throws IOException {
        JsonReader json = new JsonReader(reader);
        json.setStrictness(Strictness.STRICT);
        String name = fallbackName, nameKey = null;
        boolean reflow = false;
        double rainbowSaturation = 0.88;
        List<Layer> layers = null;
        ParticleEmitter emitter = null;
        Set<String> seen = new HashSet<>();
        try {
            json.beginObject();
            while (json.hasNext()) {
                switch (nextField(json, seen)) {
                    case "version" -> integer(json, 1, 1);
                    case "name" -> name = string(json).strip();
                    case "nameKey" -> nameKey = string(json);
                    case "reflow" -> reflow = bool(json);
                    case "rainbowSaturation" -> rainbowSaturation = number(json, 0, 1);
                    case "layers" -> layers = readLayers(json);
                    case "emitter" -> emitter = ParticleEmitter.read(json);
                    default -> throw unknownField(json);
                }
            }
            json.endObject();
            expect(json, JsonToken.END_DOCUMENT);
        } catch (IllegalStateException | NumberFormatException exception) {
            throw new IOException("Invalid effect at " + json.getPath() + ": " + exception.getMessage(), exception);
        }
        if (!seen.contains("version")) throw new IOException("Missing required field version (expected 1)");
        if (name.isBlank()) throw new IOException("Effect name must contain visible text");
        if (layers == null) throw new IOException("Missing required field layers");
        return new CustomEffect(name, seen.contains("name") ? null : nameKey, layers, reflow, rainbowSaturation, emitter);
    }

    private static List<Layer> readLayers(JsonReader json) throws IOException {
        List<Layer> layers = new ArrayList<>();
        int total = 0;
        json.beginArray();
        while (json.hasNext()) {
            if (layers.size() == 8) throw new IOException("layers accepts at most 8 layers");
            Layer layer = new Layer();
            Set<String> seen = new HashSet<>();
            json.beginObject();
            while (json.hasNext()) {
                switch (nextField(json, seen)) {
                    case "shape" -> layer.shape = choice(json, EffectShape.class);
                    case "radii" -> layer.radial = RadialShape.read(json);
                    case "count" -> {
                        layer.useShardCount = binding(json, "shardCount");
                        if (!layer.useShardCount) layer.count = integer(json, 1, 32);
                    }
                    case "color" -> layer.color = color(json);
                    case "alternateColor" -> layer.alternateColor = color(json);
                    case "contrast" -> layer.contrast = bool(json);
                    case "rotateCopies" -> layer.rotateCopies = bool(json);
                    case "segments" -> layer.segments = integer(json, 1, 64);
                    case "turns" -> {
                        layer.useSpiralTurns = binding(json, "spiralTurns");
                        if (!layer.useSpiralTurns) layer.turns = number(json, -10, 10);
                    }
                    case "sweep" -> layer.sweep = Math.toRadians(number(json, -720, 720));
                    case "startRadiusScale" -> layer.startRadiusScale = number(json, 0, 4);
                    case "startOpacity" -> layer.startOpacity = number(json, 0, 1);
                    case "light" -> layer.light = number(json, 0, 1);
                    case "radiusVariation" -> layer.radiusVariation = number(json, 0, 0.5);
                    case "radiusFrequency" -> layer.radiusFrequency = number(json, -32, 32);
                    case "sizeVariation" -> layer.sizeVariation = number(json, 0, 0.5);
                    case "sizeFrequency" -> layer.sizeFrequency = number(json, -32, 32);
                    case "easing" -> layer.easing = choice(json, Ease.class);
                    case "radiusEasing" -> layer.radiusEasing = choice(json, Ease.class);
                    case "opacityEasing" -> layer.opacityEasing = choice(json, Ease.class);
                    case "frames" -> layer.frames = readFrames(json);
                    default -> throw unknownField(json);
                }
            }
            if (layer.frames == null) throw new IOException(json.getPath() + " requires frames");
            if ((layer.shape == EffectShape.RADIAL) != (layer.radial != null)) {
                throw new IOException(json.getPath() + ": radii must be set exactly when shape is radial");
            }
            json.endObject();
            if (layer.radiusEasing == null) layer.radiusEasing = layer.easing;
            if (layer.opacityEasing == null) layer.opacityEasing = layer.easing;
            if (layer.shape == EffectShape.RING && layer.segments < 3) throw new IOException("A ring requires at least 3 segments");
            total += layer.geometryCost();
            if (total > 96) throw new IOException("Effect exceeds 96 total copies");
            layers.add(layer);
        }
        json.endArray();
        if (layers.isEmpty()) throw new IOException("layers must contain at least one layer");
        return layers;
    }

    private static List<Frame> readFrames(JsonReader json) throws IOException {
        List<Frame> frames = new ArrayList<>();
        Frame previous = Frame.DEFAULT;
        json.beginArray();
        while (json.hasNext()) {
            if (frames.size() == 16) throw new IOException(json.getPath() + " exceeds the 16-frame limit");
            Frame frame = readFrame(json, previous);
            if (frames.isEmpty() ? frame.t != 0 : frame.t <= previous.t) {
                throw new IOException(json.getPreviousPath() + ".t must start at 0 and increase strictly");
            }
            frames.add(frame);
            previous = frame;
        }
        json.endArray();
        if (frames.size() < 2 || previous.t != 1) {
            throw new IOException(json.getPreviousPath() + " requires at least two frames and a final t of 1");
        }
        return frames;
    }

    private static Frame readFrame(JsonReader json, Frame previous) throws IOException {
        double t = -1, radius = previous.radius, angle = previous.angle, x = previous.x, y = previous.y;
        double width = previous.width, height = previous.height, rotation = previous.rotation, opacity = previous.opacity;
        int color = previous.color, alternateColor = previous.alternateColor;
        Set<String> seen = new HashSet<>();
        json.beginObject();
        while (json.hasNext()) {
            switch (nextField(json, seen)) {
                case "t" -> t = number(json, 0, 1);
                case "radius" -> radius = number(json, 0, 8);
                case "angle" -> angle = number(json, -3600, 3600);
                case "x" -> x = number(json, -8, 8);
                case "y" -> y = number(json, -8, 8);
                case "width" -> width = number(json, 0, 8);
                case "height" -> height = number(json, 0, 8);
                case "rotation" -> rotation = number(json, -3600, 3600);
                case "opacity" -> opacity = number(json, 0, 1);
                case "color" -> color = color(json);
                case "alternateColor" -> alternateColor = color(json);
                default -> throw unknownField(json);
            }
        }
        if (t < 0) throw new IOException(json.getPath() + " requires t");
        json.endObject();
        return new Frame(t, radius, angle, x, y, width, height, rotation, opacity, color, alternateColor);
    }

    static String nextField(JsonReader json, Set<String> seen) throws IOException {
        String field = json.nextName();
        if (!seen.add(field)) throw new IOException("Duplicate field " + json.getPath());
        return field;
    }

    static IOException unknownField(JsonReader json) {
        return new IOException("Unknown field " + json.getPath());
    }

    private static boolean binding(JsonReader json, String key) throws IOException {
        if (json.peek() != JsonToken.STRING) return false;
        if (!key.equals(string(json))) throw new IOException(json.getPreviousPath() + " expected a number or " + key);
        return true;
    }

    private static boolean bool(JsonReader json) throws IOException {
        expect(json, JsonToken.BOOLEAN);
        return json.nextBoolean();
    }

    static <T extends Enum<T>> T choice(JsonReader json, Class<T> type) throws IOException {
        String value = string(json);
        for (T choice : type.getEnumConstants()) {
            if (choice.name().toLowerCase(java.util.Locale.ROOT).equals(value)) return choice;
        }
        throw new IOException("Unknown " + type.getSimpleName() + " at " + json.getPreviousPath() + ": " + value);
    }

    private static String string(JsonReader json) throws IOException {
        expect(json, JsonToken.STRING);
        return json.nextString();
    }

    static int color(JsonReader json) throws IOException {
        String value = string(json);
        return switch (value) {
            case "head" -> HEAD;
            case "tail" -> TAIL;
            default -> {
                if (!RGB.matcher(value).matches()) {
                    throw new IOException(json.getPreviousPath() + " expected head, tail or #RRGGBB");
                }
                yield Integer.parseInt(value.substring(1), 16);
            }
        };
    }

    static int integer(JsonReader json, int min, int max) throws IOException {
        expect(json, JsonToken.NUMBER);
        int value;
        try {
            value = new BigDecimal(json.nextString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException(json.getPreviousPath() + " must be an integer", exception);
        }
        if (value < min || value > max) throw new IOException(json.getPreviousPath() + " expected " + min + ".." + max);
        return value;
    }

    static double number(JsonReader json, double min, double max) throws IOException {
        expect(json, JsonToken.NUMBER);
        double value = json.nextDouble();
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IOException(json.getPreviousPath() + " expected a finite number in " + min + ".." + max);
        }
        return value;
    }

    private static void expect(JsonReader json, JsonToken type) throws IOException {
        if (json.peek() != type) throw new IOException(json.getPath() + " expected " + type + ", found " + json.peek());
    }
}
