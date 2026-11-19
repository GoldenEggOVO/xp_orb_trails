package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class SpriteRibbon {
    private static final Vec3 GUI_VIEW = new Vec3(0, 0, -1);
    private static final int MAX_TILES = 256;
    private static final double TEXEL_INSET = 0.001;

    private SpriteRibbon() { }

    record FrameMap(SpriteSheet.Frame frame, int atlasWidth, int atlasHeight, SpriteSheet.Ribbon ribbon) {
        FrameMap(SpriteSheet sheet, SpriteSheet.Frame frame) {
            this(frame, sheet.width(), sheet.height(), sheet.ribbon());
        }

        double top() { return (frame.offsetY() - frame.pivotY()) / frame.sourceHeight(); }
        double bottom() { return (frame.offsetY() + frame.height() - frame.pivotY()) / frame.sourceHeight(); }
        float v0() { return (float) ((frame.y() + TEXEL_INSET) / atlasHeight); }
        float v1() { return (float) ((frame.y() + frame.height() - TEXEL_INSET) / atlasHeight); }

        private List<Span> spans(List<TrailRenderer.Sample> samples, double[] arc, double unitsPerBlock, TrailConfig cfg) {
            var spans = new ArrayList<Span>();
            double length = arc[arc.length - 1];
            double repeat = cfg.ribbonRepeatLength * unitsPerBlock;
            if (ribbon == null) {
                addMiddle(spans, 0, frame.sourceWidth(), 0, length, cfg.ribbonMapping, repeat, samples, arc);
                return spans;
            }

            double tail = ribbon.tailPixels() * unitsPerBlock * width(cfg, 0, 0) / frame.sourceHeight();
            double head = ribbon.headPixels() * unitsPerBlock * width(cfg, 1, 0) / frame.sourceHeight();
            boolean shortPath = tail + head >= length;
            double tailEnd = shortPath ? length * (tail / (tail + head)) : tail;
            double headStart = shortPath ? tailEnd : length - head;
            int headPixel = frame.sourceWidth() - ribbon.headPixels();
            addRegion(spans, 0, ribbon.tailPixels(), 0, tailEnd, tailEnd);
            addMiddle(spans, ribbon.tailPixels(), headPixel, tailEnd, headStart, ribbon.middle(), repeat, samples, arc);
            addRegion(spans, headPixel, frame.sourceWidth(), headStart, length, length - headStart);
            return spans;
        }

        private void addMiddle(List<Span> spans, int firstPixel, int endPixel, double start, double end,
                               String mode, double repeat, List<TrailRenderer.Sample> samples, double[] arc) {
            if (end <= start) return;
            if ("stretch".equals(mode) || "tile".equals(mode)) {
                addRegion(spans, firstPixel, endPixel, start, end, "tile".equals(mode) ? repeat : end - start);
                return;
            }

            double[] phase = new double[arc.length];
            boolean anchored = "static".equals(mode);
            phase[0] = anchored ? samples.getFirst().birthDistance() / repeat : 0;
            for (int i = 1; i < arc.length; i++) {
                phase[i] = anchored ? samples.get(i).birthDistance() / repeat
                        : phase[i - 1] + (arc[i] > arc[i - 1] ? samples.get(i).segmentStep() : 0);
            }
            // For a capped sheet, distribute the entire middle image between the two cap boundaries.
            boolean distribute = "distribute_per_segment".equals(mode);
            double origin = distribute ? phaseAt(start, arc, phase) : 0;
            double scale = distribute ? phaseAt(end, arc, phase) - origin : 1;
            for (int i = 1; i < arc.length; i++) {
                double a = Math.max(start, arc[i - 1]), b = Math.min(end, arc[i]);
                if (a >= b) continue;
                double u0 = (phaseAt(a, arc, phase, i) - origin) / scale;
                double u1 = (phaseAt(b, arc, phase, i) - origin) / scale;
                addCycles(spans, firstPixel, endPixel, a, b, u0, u1);
            }
        }

        private static double phaseAt(double distance, double[] arc, double[] phase) {
            int i = 1;
            while (i < arc.length - 1 && arc[i] < distance) i++;
            return phaseAt(distance, arc, phase, i);
        }

        private static double phaseAt(double distance, double[] arc, double[] phase, int i) {
            if (distance == arc[i]) return phase[i];
            double t = (distance - arc[i - 1]) / (arc[i] - arc[i - 1]);
            return phase[i - 1] + (phase[i] - phase[i - 1]) * t;
        }

        private void addRegion(List<Span> spans, int firstPixel, int endPixel, double start, double end, double period) {
            if (start < end) addCycles(spans, firstPixel, endPixel, start, end, 0, Math.min(MAX_TILES, (end - start) / period));
        }

        // The packed atlas can omit transparent margins. Clip each repeat to its visible source pixels.
        private void addCycles(List<Span> spans, int firstPixel, int endPixel, double start, double end,
                               double phaseStart, double phaseEnd) {
            int left = Math.max(firstPixel, frame.offsetX());
            int right = Math.min(endPixel, frame.offsetX() + frame.width());
            if (left >= right || start >= end) return;
            double from = (double) (left - firstPixel) / (endPixel - firstPixel);
            double to = (double) (right - firstPixel) / (endPixel - firstPixel);
            double u0 = (frame.x() + (left - frame.offsetX()) + TEXEL_INSET) / atlasWidth;
            double u1 = (frame.x() + (right - frame.offsetX()) - TEXEL_INSET) / atlasWidth;
            double first = phaseStart - Math.floor(phaseStart);
            double delta = phaseEnd - phaseStart;
            if (delta == 0) {
                if (first >= from && first <= to) {
                    double u = u0 + (u1 - u0) * ((first - from) / (to - from));
                    spans.add(new Span(start, end, u, u));
                }
                return;
            }
            // A teleport can cross many tiles in a single segment. Preserve both endpoint phases.
            if (delta > MAX_TILES) delta -= Math.floor(delta) - (MAX_TILES - 1);
            double last = first + delta;
            for (int i = 0; i < Math.ceil(last); i++) {
                double a = Math.max(first, i + from), b = Math.min(last, i + to);
                if (a >= b) continue;
                double x0 = a == first ? start : start + (end - start) * ((a - first) / delta);
                double x1 = b == last ? end : start + (end - start) * ((b - first) / delta);
                if (x0 < x1) spans.add(new Span(x0, x1, u0 + (u1 - u0) * ((a - i - from) / (to - from)),
                        u0 + (u1 - u0) * ((b - i - from) / (to - from))));
            }
        }
    }

    private record Span(double start, double end, double u0, double u1) {
        float u(double distance) {
            double t = Math.clamp((distance - start) / (end - start), 0, 1);
            return (float) (u0 + t * (u1 - u0));
        }
    }

    record Edge(Vec3 top, Vec3 bottom, float u, int color) { }

    interface QuadSink {
        void quad(Edge a, Edge b);
    }

    static double width(TrailConfig cfg, double progress, double light) {
        return cfg.width * TrailRenderer.widthScale(cfg, progress) * (1 + 0.28 * light);
    }

    // World positions use a perspective camera. GUI samples already lie in the screen plane.
    static void emit(FrameMap map, List<TrailRenderer.Sample> samples, Vec3 camera, double unitsPerBlock,
                     long fadeNowNanos, TrailConfig cfg, double fade, double reflowProgress, boolean pulse,
                     QuadSink sink) {
        int last = samples.size() - 1;
        if (last < 1) return;
        double[] arc = new double[samples.size()];
        for (int i = 1; i <= last; i++) arc[i] = arc[i - 1] + samples.get(i - 1).position().distanceTo(samples.get(i).position());
        double length = arc[last];
        if (!Double.isFinite(length) || length < 0.006 * unitsPerBlock) return;

        boolean reflow = reflowProgress >= 0;
        double front = reflow ? TrailEffects.reflowFront(reflowProgress)
                : TrailEffects.pulseFront(fadeNowNanos / 1.0E9, cfg.pulseSpeed);
        double bandWidth = reflow ? TrailEffects.reflowBandWidth(cfg.pickupFlashSize) : 0.12;
        var spans = map.spans(samples, arc, unitsPerBlock, cfg);
        int spanIndex = 0;
        Vec3 side = null;
        Edge previous = null;
        for (int i = 1; i <= last && spanIndex < spans.size(); i++) {
            var a = samples.get(i - 1);
            var b = samples.get(i);
            double distance = arc[i - 1], segmentEnd = arc[i];
            double segment = segmentEnd - distance;
            if (segment == 0) continue;
            if (side == null) side = sideAt(samples, i - 1, camera, null);
            Vec3 nextSide = sideAt(samples, i, camera, side);
            double clip = reflow ? distance + segment * Math.clamp(front * last - (i - 1), 0, 1) : distance;
            while (spanIndex < spans.size()) {
                Span span = spans.get(spanIndex);
                if (span.start >= segmentEnd) break;
                double start = Math.max(clip, span.start), end = Math.min(segmentEnd, span.end);
                if (start < end) {
                    double from = (start - distance) / segment;
                    double to = end == segmentEnd ? 1 : (end - distance) / segment;
                    if (previous == null) previous = edge(map, a, b, from, (i - 1 + from) / last,
                            span.u(start), side, nextSide, camera, unitsPerBlock,
                            fadeNowNanos, cfg, fade, front, bandWidth, reflow || pulse);
                    Edge next = edge(map, a, b, to, (i - 1 + to) / last, span.u(end),
                            side, nextSide, camera, unitsPerBlock, fadeNowNanos, cfg, fade, front, bandWidth, reflow || pulse);
                    sink.quad(previous, next);
                    previous = next;
                }
                if (span.end > segmentEnd) break;
                previous = null;
                spanIndex++;
            }
            side = nextSide;
        }
    }

    private static Vec3 sideAt(List<TrailRenderer.Sample> samples, int i, Vec3 camera, Vec3 previous) {
        Vec3 view = camera == null ? GUI_VIEW : camera.subtract(samples.get(i).position());
        return TrailRenderer.billboardRight(TrailRenderer.tangent(samples, i), view, previous);
    }

    private static Edge edge(FrameMap map, TrailRenderer.Sample a, TrailRenderer.Sample b, double t,
                             double progress, float u, Vec3 sideA, Vec3 sideB, Vec3 camera, double unitsPerBlock,
                             long fadeNow, TrailConfig cfg, double fade, double front, double bandWidth, boolean lit) {
        Vec3 point = t == 1 ? b.position() : a.position().lerp(b.position(), t);
        if (camera != null) point = point.subtract(camera);
        Vec3 side = sideA;
        if (t == 1) side = sideB;
        else if (t > 0) side = sideA.lerp(sideB, t).normalize();
        double light = lit ? TrailEffects.band(progress, front, bandWidth) : 0;
        double fullWidth = unitsPerBlock * width(cfg, progress, light);
        double time = a.time() + (b.time() - a.time()) * t;
        double life = 1 - TrailEffects.smooth((fadeNow - time) / (cfg.lifetimeSeconds * 1.0E9));
        double opacity = TrailRenderer.stampOpacity(cfg, fade, life, progress, light);
        int color = ((int) Math.round(255 * Math.clamp(opacity, 0, 1)) << 24) | 0xFFFFFF;
        return new Edge(point.add(side.scale(map.top() * fullWidth)), point.add(side.scale(map.bottom() * fullWidth)), u, color);
    }
}
