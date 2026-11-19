package dev.goldeneggovo.xporbtrails;

/** Shared trail lighting and camera-facing shapes in normalized effect coordinates. */
final class TrailEffects {
    private TrailEffects() { }

    interface PieceSink {
        void vertex(double x, double y, double z, boolean worldAxes, int argb);

        default void rectangle(double x, double y, double angle, double halfLength, double halfWidth, int color) {
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double dx = cos * halfLength, dy = sin * halfLength, wx = -sin * halfWidth, wy = cos * halfWidth;
            vertex(x - dx - wx, y - dy - wy, 0, false, color);
            vertex(x + dx - wx, y + dy - wy, 0, false, color);
            vertex(x + dx + wx, y + dy + wy, 0, false, color);
            vertex(x - dx + wx, y - dy + wy, 0, false, color);
        }
    }

    static double pulseFront(double seconds, double speed) {
        return flightProgress(seconds, speed) * 1.24 - 0.12;
    }

    static double flightProgress(double seconds, double speed) {
        double phase = seconds * speed;
        return phase - Math.floor(phase);
    }

    static double flightSize(TrailConfig c) {
        return 0.36 * c.orbScale;
    }

    static double reflowFront(double progress) {
        return smooth(progress);
    }

    static double reflowVisibility(double path, double front) {
        return smooth(1 + (path - front) / 0.025);
    }

    static double band(double path, double front, double halfWidth) {
        return smooth(1 - Math.abs(path - front) / halfWidth);
    }

    static double reflowBandWidth(double size) {
        return Math.min(0.20, 0.045 + size * 0.045);
    }

    static int litColor(int rgb, double light) {
        return mix(rgb, 0xFFFFFF, Math.min(0.78, light * 0.65));
    }

    static int bandColor(int rgb, double baseOpacity, double strength, double light, double visibility) {
        int alpha = alpha((baseOpacity + strength * light) * visibility);
        return (alpha << 24) | litColor(rgb, light);
    }

    static double smooth(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    private static int alpha(double opacity) {
        return Math.clamp((int) (255 * opacity), 0, 255);
    }

    static int colorAt(TrailConfig c, double p, double seconds, float saturation) {
        if ("solid".equals(c.colorMode)) return c.startColor;
        if ("rainbow".equals(c.colorMode)) {
            double phase = p * 0.72 + seconds * c.rainbowSpeed;
            return hsvToRgb((float) (phase - Math.floor(phase)), saturation, 1.0F);
        }
        if (c.gradientStops.length > 0) return c.gradientColor(p);
        return mix(c.startColor, c.endColor, p);
    }

    static int hsvToRgb(float h, float s, float v) {
        float sector = (h - (float) Math.floor(h)) * 6.0F;
        int i = (int) sector;
        float f = sector - i;
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    static int mix(int a, int b, double amount) {
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * amount);
        int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * amount);
        int blue = (int) ((a & 255) + ((b & 255) - (a & 255)) * amount);
        return r << 16 | g << 8 | blue;
    }
}
