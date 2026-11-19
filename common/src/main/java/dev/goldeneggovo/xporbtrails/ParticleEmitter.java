package dev.goldeneggovo.xporbtrails;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;

/** A file-defined emission schedule. Time is in seconds, distances in blocks and positive Y is up. */
final class ParticleEmitter {
    private static final int MAX_PARTICLES = 64, MAX_QUADS = 384;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private enum Space { WORLD, LOCAL }
    private record Burst(double time, int count) {}
    private record Range(double min, double max) {
        double sample(SplittableRandom random) {
            return min + (max - min) * random.nextDouble();
        }

        static Range read(JsonReader json, double lower, double upper) throws IOException {
            if (json.peek() != JsonToken.BEGIN_ARRAY) {
                double value = CustomEffect.number(json, lower, upper);
                return new Range(value, value);
            }
            json.beginArray();
            double min = CustomEffect.number(json, lower, upper);
            double max = CustomEffect.number(json, min, upper);
            json.endArray();
            return new Range(min, max);
        }
    }

    private double rate, distance, spread, gravity, drag;
    private Range lifetime = new Range(1, 1), speed = new Range(0, 0), size = new Range(1, 1);
    private Range rotation = new Range(0, 0), spin = new Range(0, 0);
    private Space space = Space.WORLD;
    private final List<Burst> bursts = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private Vec3 direction = UP, side, tangent;
    private int capacity = MAX_PARTICLES;

    static ParticleEmitter read(JsonReader json) throws IOException {
        var emitter = new ParticleEmitter();
        var seen = new HashSet<String>();
        json.beginObject();
        while (json.hasNext()) {
            switch (CustomEffect.nextField(json, seen)) {
                case "rate" -> emitter.rate = CustomEffect.number(json, 0, 128);
                case "distance" -> emitter.distance = CustomEffect.number(json, 0.01, 64);
                case "bursts" -> emitter.readBursts(json);
                case "lifetime" -> emitter.lifetime = Range.read(json, 0.01, 10);
                case "speed" -> emitter.speed = Range.read(json, 0, 32);
                case "size" -> emitter.size = Range.read(json, 0.01, 8);
                case "rotation" -> emitter.rotation = Range.read(json, -3600, 3600);
                case "spin" -> emitter.spin = Range.read(json, -3600, 3600);
                case "direction" -> emitter.direction = readDirection(json);
                case "spread" -> emitter.spread = Math.toRadians(CustomEffect.number(json, 0, 180));
                case "gravity" -> emitter.gravity = CustomEffect.number(json, -64, 64);
                case "drag" -> emitter.drag = CustomEffect.number(json, 0, 32);
                case "space" -> emitter.space = CustomEffect.choice(json, Space.class);
                case "colors" -> emitter.readColors(json);
                default -> throw CustomEffect.unknownField(json);
            }
        }
        json.endObject();
        if (emitter.rate == 0 && emitter.distance == 0 && emitter.bursts.isEmpty()) {
            throw new IOException("emitter requires a positive rate, distance or at least one burst");
        }
        Vec3 axis = Math.abs(emitter.direction.y) < 0.9 ? UP : new Vec3(1, 0, 0);
        emitter.side = emitter.direction.cross(axis).normalize();
        emitter.tangent = emitter.direction.cross(emitter.side);
        return emitter;
    }

    private static Vec3 readDirection(JsonReader json) throws IOException {
        json.beginArray();
        Vec3 vector = new Vec3(CustomEffect.number(json, -1e6, 1e6), CustomEffect.number(json, -1e6, 1e6),
                CustomEffect.number(json, -1e6, 1e6));
        json.endArray();
        double scale = Math.max(Math.abs(vector.x), Math.max(Math.abs(vector.y), Math.abs(vector.z)));
        if (scale == 0) throw new IOException(json.getPreviousPath() + " requires a nonzero direction");
        return new Vec3(vector.x / scale, vector.y / scale, vector.z / scale).normalize();
    }

    private void readBursts(JsonReader json) throws IOException {
        json.beginArray();
        while (json.hasNext()) {
            if (bursts.size() == 16) throw new IOException("emitter.bursts accepts at most 16 waves");
            var seen = new HashSet<String>();
            double time = -1;
            int count = 0;
            json.beginObject();
            while (json.hasNext()) {
                switch (CustomEffect.nextField(json, seen)) {
                    case "time" -> time = CustomEffect.number(json, 0, 30);
                    case "count" -> count = CustomEffect.integer(json, 1, MAX_PARTICLES);
                    default -> throw CustomEffect.unknownField(json);
                }
            }
            json.endObject();
            if (time < 0 || count == 0) throw new IOException(json.getPreviousPath() + " requires time and count");
            bursts.add(new Burst(time, count));
        }
        json.endArray();
        bursts.sort(Comparator.comparingDouble(Burst::time));
    }

    private void readColors(JsonReader json) throws IOException {
        json.beginArray();
        while (json.hasNext()) {
            if (colors.size() == 16) throw new IOException("emitter.colors accepts at most 16 colors");
            int color = CustomEffect.color(json);
            if (color < 0) throw new IOException(json.getPreviousPath() + " requires #RRGGBB");
            colors.add(color);
        }
        json.endArray();
        if (colors.isEmpty()) throw new IOException("emitter.colors requires at least one color");
    }

    boolean hasPalette() { return !colors.isEmpty(); }
    double maxLifetime() { return lifetime.max; }
    double lastBurstTime() { return bursts.isEmpty() ? 0 : bursts.getLast().time; }
    int capacity() { return capacity; }
    void limitGeometry(int quadsPerParticle) { capacity = Math.min(MAX_PARTICLES, MAX_QUADS / quadsPerParticle); }
    State start(long seed) { return new State(seed); }

    record Particle(Vec3 position, double progress, double size, double rotation, int headColor) {}
    private record Emission(double time, long key, Vec3 origin) {}
    private record Birth(double time, Vec3 origin, Vec3 velocity, double lifetime, double size,
                         double rotation, double spin, int headColor) {}

    final class State {
        private final long seed;
        private final ArrayDeque<Birth> births = new ArrayDeque<>();
        private final ArrayList<Emission> pending = new ArrayList<>();
        private Vec3 previous;
        private double previousTime, traveled, distanceError;
        private long rateCount, distanceCount;
        private int nextBurst;

        private State(long seed) { this.seed = seed; }

        /** Advance along one straight source segment. Subdividing the segment preserves each crossing. */
        void advance(double seconds, Vec3 source) {
            if (seconds < 0 || (previous != null && seconds <= previousTime)) return;
            if (previous == null) previous = source;
            double length = previous.distanceTo(source);
            double endDistance = traveled;
            if (length > 0) {
                // Compensated summation keeps diagonal crossings stable across many small frames.
                double corrected = length - distanceError;
                endDistance = traveled + corrected;
                distanceError = (endDistance - traveled) - corrected;
            }
            // Only the latest capacity crossings from any one stream can survive the combined cap.
            if (rate > 0) {
                long end = crossings(seconds * rate);
                for (long i = Math.max(rateCount + 1, end - capacity + 1); i <= end; i++) {
                    double time = i / rate;
                    add(time, 3 * i, source, fraction(time, seconds));
                }
                rateCount = end;
            }
            if (distance > 0 && length > 0) {
                long end = crossings(endDistance / distance);
                for (long i = Math.max(distanceCount + 1, end - capacity + 1); i <= end; i++) {
                    double part = Math.clamp((i * distance - traveled) / length, 0, 1);
                    add(previousTime + part * (seconds - previousTime), 3 * i + 1, source, part);
                }
                distanceCount = end;
            }
            while (nextBurst < bursts.size() && bursts.get(nextBurst).time <= seconds) {
                Burst burst = bursts.get(nextBurst);
                if (burst.time + lifetime.max > seconds) {
                    for (int i = Math.max(0, burst.count - capacity); i < burst.count; i++) {
                        add(burst.time, 3L * (nextBurst * MAX_PARTICLES + i) + 2, source, fraction(burst.time, seconds));
                    }
                }
                nextBurst++;
            }
            if (!pending.isEmpty()) {
                pending.sort(Comparator.comparingDouble(Emission::time).thenComparingLong(Emission::key));
                for (int i = Math.max(0, pending.size() - capacity); i < pending.size(); i++) {
                    if (births.size() == capacity) births.removeFirst();
                    births.addLast(spawn(pending.get(i)));
                }
                pending.clear();
            }
            traveled = endDistance;
            previous = source;
            previousTime = seconds;
        }

        private double fraction(double time, double end) {
            return end == previousTime ? 0 : Math.clamp((time - previousTime) / (end - previousTime), 0, 1);
        }

        private void add(double time, long key, Vec3 source, double part) {
            // Use the same nanosecond resolution as the world clock when two emission modes coincide.
            double birthTime = Math.rint(time * 1e9) / 1e9;
            pending.add(new Emission(birthTime, key, space == Space.LOCAL ? Vec3.ZERO : previous.lerp(source, part)));
        }

        private Birth spawn(Emission emission) {
            var random = new SplittableRandom(seed ^ Long.rotateLeft(0x9e3779b97f4a7c15L * emission.key, 27));
            double cosTheta = 1 - random.nextDouble() * (1 - Math.cos(spread));
            double sinTheta = Math.sqrt(Math.max(0, 1 - cosTheta * cosTheta));
            double phi = random.nextDouble() * Math.TAU;
            Vec3 velocity = direction.scale(cosTheta).add(side.scale(sinTheta * Math.cos(phi)))
                    .add(tangent.scale(sinTheta * Math.sin(phi))).scale(speed.sample(random));
            return new Birth(emission.time, emission.origin, velocity, lifetime.sample(random), size.sample(random),
                    rotation.sample(random), spin.sample(random), colors.isEmpty() ? -1 : colors.get(random.nextInt(colors.size())));
        }

        /** Sample existing particles without emitting more, including after their source disappears. */
        List<Particle> sample(double seconds, Vec3 currentSource) {
            if (births.isEmpty()) return List.of();
            double nowNanos = Math.rint(seconds * 1e9);
            List<Particle> result = new ArrayList<>(births.size());
            for (Birth birth : births) {
                double age = (nowNanos - Math.rint(birth.time * 1e9)) / 1e9;
                if (age < 0 || age >= birth.lifetime) continue;
                double velocityTime = age, gravityTime = 0.5 * age * age;
                if (drag > 0) {
                    // Closed-form integral of dv/dt = gravity - drag*v. The series avoids cancellation near zero.
                    double x = drag * age;
                    if (x < 0.001) {
                        velocityTime = age * (1 - x / 2 + x * x / 6 - x * x * x / 24);
                        gravityTime = age * age * (0.5 - x / 6 + x * x / 24 - x * x * x / 120);
                    } else {
                        velocityTime = -Math.expm1(-x) / drag;
                        gravityTime = (age - velocityTime) / drag;
                    }
                }
                Vec3 anchor = space == Space.WORLD ? birth.origin : currentSource;
                Vec3 position = anchor.add(birth.velocity.scale(velocityTime)).add(0, -gravity * gravityTime, 0);
                result.add(new Particle(position, age / birth.lifetime, birth.size, birth.rotation + age * birth.spin, birth.headColor));
            }
            return result;
        }

        private static long crossings(double count) {
            return (long) Math.floor(count + 8 * Math.ulp(count));
        }
    }
}
