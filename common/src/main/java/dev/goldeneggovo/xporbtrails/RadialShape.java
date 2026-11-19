package dev.goldeneggovo.xporbtrails;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;

final class RadialShape {
    private final double[] x, y;

    private RadialShape(double[] radii, int count) {
        x = new double[count];
        y = new double[count];
        for (int i = 0; i < count; i++) {
            double angle = Math.TAU * i / count;
            x[i] = -Math.sin(angle) * radii[i];
            y[i] = Math.cos(angle) * radii[i];
        }
    }

    static RadialShape read(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.BEGIN_ARRAY) throw new IOException("radii must be an array");
        double[] radii = new double[32];
        int count = 0;
        json.beginArray();
        while (json.hasNext()) {
            if (count == radii.length) throw new IOException("radii accepts at most 32 points");
            radii[count++] = CustomEffect.number(json, 0, 1);
        }
        json.endArray();
        if (count < 3) throw new IOException("radii requires at least 3 points");
        for (int i = 0; i < count; i++) {
            if (radii[i] > 0 && radii[(i + 1) % count] > 0) return new RadialShape(radii, count);
        }
        throw new IOException("radii needs two adjacent positive values to enclose an area");
    }

    int size() { return x.length; }

    void draw(TrailEffects.PieceSink sink, double centerX, double centerY, double rotation,
              double halfWidth, double halfHeight, double opacity, int rgb) {
        int alpha = (int) (Math.clamp(opacity, 0, 1) * 255);
        if (alpha == 0) return;
        int color = (alpha << 24) | (rgb & 0xFFFFFF);
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        double xx = halfWidth * cos, xy = halfWidth * sin, yx = -halfHeight * sin, yy = halfHeight * cos;
        double prevX = centerX + x[0] * xx + y[0] * yx, prevY = centerY + x[0] * xy + y[0] * yy;
        for (int i = 1; i <= x.length; i++) {
            int next = i == x.length ? 0 : i;
            double nextX = centerX + x[next] * xx + y[next] * yx;
            double nextY = centerY + x[next] * xy + y[next] * yy;
            sink.vertex(centerX, centerY, 0, false, color);
            sink.vertex(prevX, prevY, 0, false, color);
            sink.vertex(nextX, nextY, 0, false, color);
            sink.vertex(nextX, nextY, 0, false, color);
            prevX = nextX;
            prevY = nextY;
        }
    }
}
