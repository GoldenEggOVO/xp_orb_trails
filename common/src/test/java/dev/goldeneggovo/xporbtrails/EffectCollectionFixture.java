package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

final class EffectCollectionFixture {
    static final Path ARCHIVE = Path.of(System.getProperty("xporb.effectCollection", "../examples/2.1.0example/2.1.0example.zip"));

    static List<FileTrailStyles.Style> install(Path config) throws IOException {
        Path packs = Files.createDirectories(config.resolve("xp_orb_trails/packs"));
        Files.copy(ARCHIVE, packs.resolve(ARCHIVE.getFileName()));
        XpOrbTrailsClient.initialize(config);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        return XpOrbTrailsClient.fileStyles();
    }

    static JsonObject profile(String name) {
        try (var zip = new ZipFile(ARCHIVE.toFile())) {
            var entry = zip.getEntry("profiles/" + name + ".json");
            assertNotNull(entry, name);
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
