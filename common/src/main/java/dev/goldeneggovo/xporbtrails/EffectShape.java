package dev.goldeneggovo.xporbtrails;

/** Normalized mesh primitives shared by JSON effects in the world and the settings preview. */
enum EffectShape {
    RECTANGLE, DIAMOND_XY, DIAMOND_YZ, DIAMOND_ZX, RING, ARC, RADIAL;

    void draw(TrailEffects.PieceSink sink, double x, double y, double angle, double radius, double rotation,
              double halfWidth, double halfHeight, double opacity, int rgb, int segments, double sweep,
              double startRadiusScale, double startOpacity, RadialShape radial) {
        if (this == ARC) {
            double a = angle - sweep;
            double previousX = x + Math.cos(a) * radius * startRadiusScale;
            double previousY = y + Math.sin(a) * radius * startRadiusScale;
            for (int step = 1; step <= segments; step++) {
                double along = step / (double) segments;
                a = angle - sweep * (1 - along);
                double r = radius * (startRadiusScale + (1 - startRadiusScale) * along);
                double nextX = x + Math.cos(a) * r, nextY = y + Math.sin(a) * r;
                double dx = nextX - previousX, dy = nextY - previousY;
                double width = halfWidth + (halfHeight - halfWidth) * along;
                int color = argb(opacity * (startOpacity + (1 - startOpacity) * along), rgb);
                sink.rectangle((previousX + nextX) * 0.5, (previousY + nextY) * 0.5, Math.atan2(dy, dx),
                        Math.hypot(dx, dy) * 0.5 + width * 0.08, width, color);
                previousX = nextX;
                previousY = nextY;
            }
            return;
        }
        x += radius * Math.cos(angle);
        y += radius * Math.sin(angle);
        int color = argb(opacity, rgb);
        if ((color >>> 24) == 0) return;
        switch (this) {
            case RECTANGLE -> sink.rectangle(x, y, rotation, halfWidth, halfHeight, color);
            case RADIAL -> radial.draw(sink, x, y, rotation, halfWidth, halfHeight, opacity, rgb);
            case RING -> {
                for (int i = 0; i < segments; i++) {
                    double a = rotation + Math.TAU * i / segments, b = rotation + Math.TAU * (i + 1) / segments;
                    double ax = Math.cos(a), ay = Math.sin(a), bx = Math.cos(b), by = Math.sin(b);
                    sink.vertex(x + ax * halfHeight, y + ay * halfHeight, 0, false, color);
                    sink.vertex(x + ax * halfWidth, y + ay * halfWidth, 0, false, color);
                    sink.vertex(x + bx * halfWidth, y + by * halfWidth, 0, false, color);
                    sink.vertex(x + bx * halfHeight, y + by * halfHeight, 0, false, color);
                }
            }
            case DIAMOND_XY, DIAMOND_YZ, DIAMOND_ZX -> {
                double ax = Math.cos(rotation) * halfWidth, ay = Math.sin(rotation) * halfWidth;
                double bx = -Math.sin(rotation) * halfHeight, by = Math.cos(rotation) * halfHeight;
                planeVertex(sink, x + ax, y + ay, color);
                planeVertex(sink, x + bx, y + by, color);
                planeVertex(sink, x - ax, y - ay, color);
                planeVertex(sink, x - bx, y - by, color);
            }
        }
    }

    private void planeVertex(TrailEffects.PieceSink sink, double x, double y, int color) {
        switch (this) {
            case DIAMOND_XY -> sink.vertex(x, y, 0, true, color);
            case DIAMOND_YZ -> sink.vertex(0, x, y, true, color);
            case DIAMOND_ZX -> sink.vertex(y, 0, x, true, color);
        }
    }

    private static int argb(double opacity, int rgb) {
        return (int) (255 * Math.clamp(opacity, 0, 1)) << 24 | (rgb & 0xFFFFFF);
    }
}
