package dev.goldeneggovo.xporbtrails;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Distance crossings along the orb's movement, independent of the smoothed tube. */
final class FixedStampHistory {
    record Mark(Vec3 position, long time, double orbAge, int icon) {
        SpriteSheet.Frame spriteFrame(SpriteSheet sheet, long now, long orbStart, boolean body, boolean vanilla) {
            return body ? sheet.frameAt((time - orbStart) / 1.0E9, !vanilla || sheet.loop())
                    : sheet.frameAt((now - time) / 1.0E9, sheet.loop());
        }
    }

    // Old marks belong to the actual loaded artwork and body animation settings at capture.
    record Source(boolean body, SpriteSheet sprite, CustomEffect vector, boolean vanilla,
                  double spacing, double yOffset, double orbScale, double pulseSpeed,
                  String colorMode, int startColor, int endColor, double rainbowSpeed) {
        static Source selected(TrailConfig cfg) {
            var role = "body".equals(cfg.trailSprite) ? AssetRole.ORB : AssetRole.TRAIL;
            return selected(cfg, XpOrbTrailsClient.sprites(role), XpOrbTrailsClient.effects(AssetRole.ORB));
        }

        static Source selected(TrailConfig cfg, Map<String, SpriteSheet> sprites, Map<String, CustomEffect> effects) {
            boolean body = "body".equals(cfg.trailSprite);
            if (!body && (!cfg.fixedStamps || !"stamps".equals(cfg.trailSpriteMode))) return null;
            SpriteSheet sprite = sprites.get(body ? cfg.flightEffect : cfg.trailSprite);
            CustomEffect vector = body ? effects.get(cfg.flightEffect) : null;
            boolean vanilla = body && cfg.showsVanillaBody(sprite != null || vector != null);
            if (vector != null && vector.emitter != null) vector = null;
            if (sprite == null && vector == null && !vanilla) return null;
            return new Source(body, sprite, vector, vanilla, TrailRenderer.stampSpacing(cfg), cfg.orbYOffset,
                    body ? cfg.orbScale : 0, vector == null ? 0 : cfg.pulseSpeed,
                    vector == null ? null : cfg.colorMode, vector == null ? 0 : cfg.startColor,
                    vector == null ? 0 : cfg.endColor, vector == null ? 0 : cfg.rainbowSpeed);
        }
    }

    private final Deque<Mark> marks = new ArrayDeque<>(TrailRenderer.MAX_TRAIL_STAMPS);
    private List<Mark> cached = List.of();
    private Vec3 previous;
    private long previousTime;
    private double previousAge;
    private int previousIcon;
    private double remaining;

    void append(Vec3 position, long time, double spacing) {
        append(position, time, spacing, time / 50_000_000.0, 5);
    }

    void append(Vec3 position, long time, double spacing, double orbAge, int icon) {
        if (previous == null) {
            remaining = spacing;
        } else {
            double distance = previous.distanceTo(position);
            double tolerance = spacing * 1.0E-9;
            if (distance > 0 && distance + tolerance >= remaining) {
                // Jump directly to the newest crossings on long moves; work remains bounded.
                double afterFirst = Math.max(0, distance - remaining);
                double intervals = Math.floor((afterFirst + tolerance) / spacing);
                double remainder = Math.max(0, afterFirst - intervals * spacing);
                double last = distance - remainder;
                int count = 1 + (int) Math.min(TrailRenderer.MAX_TRAIL_STAMPS - 1, intervals);
                for (int i = count - 1; i >= 0; i--) {
                    double t = Math.clamp((last - i * spacing) / distance, 0, 1);
                    if (marks.size() == TrailRenderer.MAX_TRAIL_STAMPS) marks.removeFirst();
                    marks.addLast(new Mark(previous.lerp(position, t), previousTime + Math.round((time - previousTime) * t),
                            previousAge + (orbAge - previousAge) * t, t < 1 ? previousIcon : icon));
                }
                cached = null;
                remaining = spacing - remainder;
            } else {
                remaining -= distance;
            }
        }
        previous = position;
        previousTime = time;
        previousAge = orbAge;
        previousIcon = icon;
    }

    List<Mark> snapshot(long oldest) {
        while (!marks.isEmpty() && marks.getFirst().time < oldest) {
            marks.removeFirst();
            cached = null;
        }
        if (cached == null) cached = List.copyOf(marks);
        return cached;
    }

    void reset() {
        marks.clear();
        cached = List.of();
        previous = null;
    }

    static int vanillaTint(double ageInTicks) {
        float phase = (float) ageInTicks * 0.5F;
        int red = (int) ((Mth.sin(phase) + 1) * 0.5F * 255);
        int blue = (int) ((Mth.sin(phase + 4.1887903F) + 1) * 0.1F * 255);
        return red << 16 | 0x00FF00 | blue;
    }
}
