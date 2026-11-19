package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpriteRibbonMetadataTest {
    private static final String ATLAS = """
            {"frames":[{"frame":{"x":0,"y":0,"w":4,"h":2}},
                       {"frame":{"x":4,"y":0,"w":2,"h":2}}],%s}
            """;

    @Test
    void supportsPixelFxMetadataAndPrefersAnExplicitRootOverride() throws IOException {
        String tile = "{\"tailPixels\":1,\"headPixels\":0,\"middle\":\"tile\"}";
        String stretch = "{\"tailPixels\":0,\"headPixels\":1,\"middle\":\"stretch\"}";
        String meta = "\"meta\":{\"pixelfx\":{\"ribbon\":" + tile + "}}";
        assertEquals(new SpriteSheet.Ribbon(1, 0, "tile"), read(ATLAS.formatted(meta)).ribbon());
        assertEquals(new SpriteSheet.Ribbon(0, 1, "stretch"), read(ATLAS.formatted(meta + ",\"ribbon\":" + stretch)).ribbon());
        assertNull(read(ATLAS.formatted("\"meta\":{}")).ribbon());
    }

    @Test
    void acceptsEveryConfiguredMappingForAnAtlasMiddle() throws IOException {
        for (String mapping : TrailConfig.RIBBON_MAPPINGS) {
            String metadata = "\"ribbon\":{\"tailPixels\":1,\"headPixels\":0,\"middle\":\"" + mapping + "\"}";
            assertEquals(mapping, read(ATLAS.formatted(metadata)).ribbon().middle());
            var cfg = new TrailConfig();
            cfg.ribbonMapping = mapping;
            assertEquals(mapping, cfg.sanitized().ribbonMapping);
        }
    }

    @Test
    void rejectsCapsOutsideAnyUntrimmedFrameAndMalformedMetadata() throws IOException {
        for (String ribbon : List.of("null", "[]",
                "{\"tailPixels\":-1,\"headPixels\":0,\"middle\":\"tile\"}",
                "{\"tailPixels\":0.5,\"headPixels\":0,\"middle\":\"tile\"}",
                "{\"tailPixels\":0,\"headPixels\":0,\"middle\":\"repeat\"}",
                "{\"tailPixels\":1,\"headPixels\":1,\"middle\":\"tile\"}",
                "{\"tailPixels\":2147483647,\"headPixels\":1,\"middle\":\"tile\"}")) {
            assertThrows(IOException.class, () -> read(ATLAS.formatted("\"ribbon\":" + ribbon)), ribbon);
        }
        assertThrows(IOException.class, () -> read(ATLAS.formatted(
                "\"meta\":{\"pixelfx\":{\"ribbon\":{\"tailPixels\":2,\"headPixels\":1,\"middle\":\"tile\"}}}")));
    }

    private static SpriteSheet read(String json) throws IOException {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6, 2, BufferedImage.TYPE_INT_ARGB), "png", png);
        var reader = ImageIO.getImageReadersByFormatName("PNG").next();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(png.toByteArray()))) {
            reader.setInput(stream);
            return SpriteSheet.read(reader, JsonParser.parseString(json).getAsJsonObject(), "sheet", 12);
        } finally {
            reader.dispose();
        }
    }
}
