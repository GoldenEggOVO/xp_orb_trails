package dev.goldeneggovo.xporbtrails;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XpOrbTrailsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("XP Orb Trails");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(TrailConfig.class, (InstanceCreator<TrailConfig>) type -> defaults())
            .setPrettyPrinting().create();
    public static TrailConfig CONFIG = new TrailConfig();
    private static Path configPath;
    private static Path assetDirectory;
    private static ConfigDefaults configDefaults;
    private static FileTrailStyles styleLibrary;
    private static EffectLibrary effectLibrary;
    private static SpriteLibrary spriteLibrary;

    private XpOrbTrailsClient() { }

    public static void initialize(Path configDirectory) {
        configPath = configDirectory.resolve("xp_orb_trails.json");
        assetDirectory = configDirectory.resolve("xp_orb_trails");
        configDefaults = new ConfigDefaults(assetDirectory);
        styleLibrary = new FileTrailStyles(assetDirectory, configDefaults::create);
        effectLibrary = new EffectLibrary(assetDirectory);
        spriteLibrary = new SpriteLibrary(assetDirectory);
        configDefaults.reload();
        CONFIG = defaults();
        if (Files.exists(configPath)) {
            try {
                try (Reader reader = Files.newBufferedReader(configPath)) {
                    JsonObject settings = GSON.fromJson(reader, JsonObject.class);
                    if (settings == null) throw new JsonParseException("Expected a JSON object");
                    CONFIG = GSON.fromJson(settings, TrailConfig.class).sanitized();
                    CONFIG.resolveCrossSection(settings.has("crossSectionSides"), settings.has("crossSectionRadii"),
                            settings.has("customCrossSectionEnabled"));
                }
                saveConfig();
            } catch (IOException | JsonParseException | IllegalArgumentException exception) {
                LOGGER.warn("Could not read XP Orb Trails config; backing it up and restoring defaults", exception);
                CONFIG = defaults();
                if (backupBrokenConfig()) saveConfig();
            }
        } else {
            saveConfig();
        }
        reloadLibraries();
    }

    static Path assetDirectory() {
        return assetDirectory;
    }

    static TrailConfig defaults() {
        return configDefaults == null ? new TrailConfig() : configDefaults.create();
    }

    static List<FileTrailStyles.Style> fileStyles() {
        return styleLibrary == null ? List.of() : styleLibrary.styles();
    }

    static Map<String, CustomEffect> effects() {
        return effectLibrary == null ? Map.of() : effectLibrary.effects();
    }

    static Map<String, CustomEffect> effects(AssetRole role) {
        return effectLibrary == null ? Map.of() : effectLibrary.effects(role);
    }

    static CustomEffect effect(AssetRole role, String id) {
        return effects(role).get(id);
    }

    static int reloadFileStyles() {
        if (styleLibrary == null) {
            LOGGER.warn("Could not reload trail styles before client initialization");
            return 1;
        }
        return configDefaults.reload() + reloadLibraries();
    }

    private static int reloadLibraries() {
        try (var packs = new PresetPacks()) {
            int errors = packs.scan(assetDirectory.resolve("packs"));
            errors += styleLibrary.reload(packs.profiles);
            if (errors == 0) {
                try {
                    ProfileUpgrades.retireSprites(assetDirectory, CONFIG, defaults(), styleLibrary.styles());
                } catch (IOException | java.io.UncheckedIOException exception) {
                    LOGGER.warn("Could not retire beta sprite templates: {}", exception.getMessage());
                    errors++;
                }
            }
            errors += effectLibrary.reload(packs.effects) + spriteLibrary.reload(packs.sprites);
            SpriteRenderer.reload();
            return errors;
        }
    }

    static Map<String, SpriteSheet> sprites() {
        return spriteLibrary == null ? Map.of() : spriteLibrary.sheets();
    }

    static Map<String, SpriteSheet> sprites(AssetRole role) {
        return spriteLibrary == null ? Map.of() : spriteLibrary.sheets(role);
    }

    static SpriteSheet sprite(AssetRole role, String id) {
        return sprites(role).get(id);
    }

    public static Screen createConfigScreen(Screen parent) {
        return new TrailConfigScreen(parent);
    }

    public static void saveConfig() {
        if (configPath == null) throw new IllegalStateException("XP Orb Trails has not been initialized");
        Path temporaryPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(temporaryPath)) {
                GSON.toJson(CONFIG.sanitized(), writer);
            }
            try {
                Files.move(temporaryPath, configPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save XP Orb Trails config to {}", configPath, exception);
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException cleanupException) {
                LOGGER.debug("Could not remove temporary XP Orb Trails config {}", temporaryPath, cleanupException);
            }
        }
    }

    private static boolean backupBrokenConfig() {
        if (!Files.isRegularFile(configPath)) return false;
        for (int suffix = 0; ; suffix++) {
            String name = configPath.getFileName() + ".broken" + (suffix == 0 ? "" : "-" + suffix);
            Path backupPath = configPath.resolveSibling(name);
            try {
                Files.move(configPath, backupPath);
                LOGGER.warn("Saved the unreadable config as {}", backupPath);
                return true;
            } catch (FileAlreadyExistsException ignored) {
                // Preserve every previous backup.
            } catch (IOException exception) {
                LOGGER.warn("Could not back up unreadable config {}", configPath, exception);
                return false;
            }
        }
    }
}
