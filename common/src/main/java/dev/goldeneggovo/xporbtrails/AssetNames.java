package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class AssetNames {
    private static final JsonObject ENGLISH = loadEnglish();

    // Version 2.0 wrote English names into editable files. Recognize the original
    // label at read time so custom names, edited settings and old ZIPs stay intact.
    static String translationKey(String name, String declaredKey, String... legacyKeys) {
        if (name == null) return declaredKey;
        for (String key : legacyKeys) {
            if ((declaredKey == null || declaredKey.equals(key)) && isEnglishLabel(key, name)) return key;
        }
        return null;
    }

    private static boolean isEnglishLabel(String key, String name) {
        return ENGLISH.has(key) && ENGLISH.get(key).getAsString().equals(name);
    }

    private static JsonObject loadEnglish() {
        try (var input = AssetNames.class.getResourceAsStream("/assets/xporbtrails/lang/en_us.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled English translations");
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read bundled English translations", exception);
        }
    }

    private AssetNames() {}
}
