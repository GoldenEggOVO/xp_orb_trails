package dev.goldeneggovo.xporbtrails;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Arrays;

/** A radius at each equally spaced angle, measured in units of the current tube radius. */
final class CrossSectionRadii extends TypeAdapter<double[]> {
    static final int MAX_POINTS = 32;
    static final double[] NONE = {};

    static boolean valid(double[] radii) {
        if (radii == null || radii.length < 3 || radii.length > MAX_POINTS) return false;
        for (double radius : radii) if (!(radius > 0 && radius <= 1)) return false;
        return true;
    }

    static double[] readProfile(JsonReader json) throws IOException {
        double[] radii = readArray(json);
        if (radii == null) throw new IOException("crossSectionRadii must be empty or contain 3 to 32 numbers greater than 0 and at most 1");
        return radii;
    }

    private static double[] readArray(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            return null;
        }
        json.beginArray();
        if (!json.hasNext()) {
            json.endArray();
            return NONE;
        }
        double[] radii = new double[MAX_POINTS];
        int count = 0;
        boolean invalid = false;
        while (json.hasNext()) {
            if (count == MAX_POINTS || json.peek() != JsonToken.NUMBER) {
                json.skipValue();
                invalid = true;
            } else {
                // Parse as a token so numeric overflow invalidates only this outline.
                radii[count++] = Double.parseDouble(json.nextString());
            }
        }
        json.endArray();
        double[] result = Arrays.copyOf(radii, count);
        return !invalid && valid(result) ? result : null;
    }

    @Override public double[] read(JsonReader json) throws IOException {
        double[] result = readArray(json);
        return result == null ? NONE : result;
    }

    @Override public void write(JsonWriter json, double[] radii) throws IOException {
        json.beginArray();
        for (double radius : radii) json.value(radius);
        json.endArray();
    }
}
