package dev.goldeneggovo.xporbtrails;

import com.google.gson.annotations.JsonAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TrailConfig extends ProfileSettings {
    public double pointSpacing = 0.18;
    public double orbYOffset = 0.175;
    public List<SavedPreset> savedPresets = new ArrayList<>();
    public List<SavedProfile> savedProfiles = new ArrayList<>();

    void restoreAdvanced(TrailConfig defaults, String... sections) {
        var updated = advanced.deepCopy();
        Workshop.restore(updated, defaults.advanced, sections);
        for (String section : sections) {
            if (defaults.advanced.has(section)) updated.add(section, defaults.advanced.get(section).deepCopy());
            else updated.remove(section);
        }
        if (!updated.equals(advanced)) advanced = updated;
    }

    boolean showsVanillaBody(boolean drawsArtwork) {
        return !enabled || showVanillaOrb || (!drawsArtwork && !"none".equals(flightEffect));
    }

    public TrailConfig sanitized() {
        var defaults = new TrailConfig();
        sanitizeSettings(defaults);
        pointSpacing = Math.clamp(finite(pointSpacing, defaults.pointSpacing), 0.03, 1.0);
        orbYOffset = finite(orbYOffset, defaults.orbYOffset);
        if (savedPresets == null) savedPresets = new ArrayList<>();
        savedPresets.removeIf(p -> p == null || p.name == null || p.name.isBlank());
        if (savedPresets.size() > 12) savedPresets = new ArrayList<>(savedPresets.subList(0, 12));
        for (SavedPreset preset : savedPresets) preset.gradientStops = validStops(preset.gradientStops);
        if (savedProfiles == null) savedProfiles = new ArrayList<>();
        savedProfiles.removeIf(p -> p == null || p.name == null || p.name.isBlank());
        if (savedProfiles.size() > 8) savedProfiles = new ArrayList<>(savedProfiles.subList(0, 8));
        for (SavedProfile profile : savedProfiles) profile.sanitizeSettings(defaults);
        return this;
    }

    public static final class SavedPreset {
        public String name;
        public int startColor;
        public int endColor;
        public int[] gradientStops = {};

        public SavedPreset() { }
        public SavedPreset(String name, int startColor, int endColor, int... gradientStops) {
            this.name = name;
            this.startColor = startColor;
            this.endColor = endColor;
            this.gradientStops = validStops(gradientStops).clone();
        }
    }

    public static final class SavedProfile extends ProfileSettings {
        public String name;

        public SavedProfile() { }

        public SavedProfile(String name, TrailConfig config) {
            this.name = name;
            copySettings(config);
        }

        public void applyTo(TrailConfig config) {
            config.copySettings(this);
            config.sanitizeSettings(new TrailConfig());
        }
    }
}

// Settings captured by a named profile. Global sampling and player libraries live on TrailConfig.
abstract class ProfileSettings {
    static final List<String> RIBBON_MAPPINGS = List.of("stretch", "tile", "distribute_per_segment", "repeat_per_segment", "static");
    private static final String DEFAULT_PICKUP_STYLE = "file:pickup/soft";
    static final int MAX_GRADIENT_STOPS = 8;
    public com.google.gson.JsonObject advanced = new com.google.gson.JsonObject();
    public boolean enabled = true;
    public int trailCap = 96;
    public double renderRange = 36.0;
    public double lifetimeSeconds = 2.6;
    public double width = 0.1;
    public int crossSectionSides = 2;
    @JsonAdapter(CrossSectionRadii.class)
    public double[] crossSectionRadii = CrossSectionRadii.NONE;
    public boolean customCrossSectionEnabled = true;
    public double effectStrength = 0.78;
    public int startColor = 0xE6FF2A;
    public int endColor = 0x66FF00;
    public int[] gradientStops = {};
    public boolean flightPulse;
    public String flightEffect = "none";
    public boolean showVanillaOrb = true;
    public double orbScale = 1.0;
    public String trailSprite = "none";
    public String trailSpriteMode = "stamps";
    public boolean fixedStamps;
    public String ribbonMapping = "stretch";
    public double ribbonRepeatLength = 0.5;
    public double stampSpacing = 1.0;
    public double stampScale = 1.0;
    public double pulseSpeed = 1.0;
    public double motionShift = 0.10;
    public double cameraPush = 0.10;
    public double smoothFlow = 0.48;
    public double pickupFadeSeconds = 0.35;
    public String colorMode = "gradient";
    public double rainbowSpeed = 0.20;
    public double tailWidthScale = 0.18;
    public double middleWidthScale = 0.70;
    public double headWidthScale = 1.0;
    public boolean pickupFlash = true;
    public double pickupFlashStrength = 0.55;
    public double pickupFlashSeconds = 0.22;
    public double pickupFlashSize = 0.65;
    public String pickupFlashStyle = DEFAULT_PICKUP_STYLE;
    public int shardCount = 6;
    public double spiralTurns = 0.65;
    public boolean additiveGlow = true;

    final void copySettings(ProfileSettings other) {
        advanced = other.advanced == null ? new com.google.gson.JsonObject() : other.advanced.deepCopy();
        enabled = other.enabled;
        trailCap = other.trailCap;
        renderRange = other.renderRange;
        lifetimeSeconds = other.lifetimeSeconds;
        width = other.width;
        crossSectionSides = other.crossSectionSides;
        crossSectionRadii = other.crossSectionRadii == null ? CrossSectionRadii.NONE : other.crossSectionRadii.clone();
        customCrossSectionEnabled = other.customCrossSectionEnabled;
        effectStrength = other.effectStrength;
        startColor = other.startColor;
        endColor = other.endColor;
        gradientStops = validStops(other.gradientStops).clone();
        flightPulse = other.flightPulse;
        flightEffect = other.flightEffect;
        showVanillaOrb = other.showVanillaOrb;
        orbScale = other.orbScale;
        trailSprite = other.trailSprite;
        trailSpriteMode = other.trailSpriteMode;
        fixedStamps = other.fixedStamps;
        ribbonMapping = other.ribbonMapping;
        ribbonRepeatLength = other.ribbonRepeatLength;
        stampSpacing = other.stampSpacing;
        stampScale = other.stampScale;
        pulseSpeed = other.pulseSpeed;
        motionShift = other.motionShift;
        cameraPush = other.cameraPush;
        smoothFlow = other.smoothFlow;
        pickupFadeSeconds = other.pickupFadeSeconds;
        colorMode = other.colorMode;
        rainbowSpeed = other.rainbowSpeed;
        tailWidthScale = other.tailWidthScale;
        middleWidthScale = other.middleWidthScale;
        headWidthScale = other.headWidthScale;
        pickupFlash = other.pickupFlash;
        pickupFlashStrength = other.pickupFlashStrength;
        pickupFlashSeconds = other.pickupFlashSeconds;
        pickupFlashSize = other.pickupFlashSize;
        pickupFlashStyle = other.pickupFlashStyle;
        shardCount = other.shardCount;
        spiralTurns = other.spiralTurns;
        additiveGlow = other.additiveGlow;
    }

    final void sanitizeSettings(ProfileSettings defaults) {
        if (advanced == null) advanced = new com.google.gson.JsonObject();
        if (!advanced.isEmpty()) {
            ProfileUpgrades.removeRetiredEffects(advanced);
            try { AdvancedSettings.validate(advanced); }
            catch (IllegalArgumentException invalid) {
                org.slf4j.LoggerFactory.getLogger("XP Orb Trails").warn("Ignoring invalid advanced settings: {}", invalid.getMessage());
                advanced = new com.google.gson.JsonObject();
            }
        }
        trailCap = Math.clamp(trailCap, 8, 256);
        renderRange = Math.clamp(finite(renderRange, defaults.renderRange), 4.0, 128.0);
        lifetimeSeconds = Math.clamp(finite(lifetimeSeconds, defaults.lifetimeSeconds), 0.25, 10.0);
        width = Math.clamp(finite(width, defaults.width), 0.02, 1.0);
        crossSectionSides = Math.clamp(crossSectionSides, 2, 32);
        if (!CrossSectionRadii.valid(crossSectionRadii)) crossSectionRadii = CrossSectionRadii.NONE;
        motionShift = Math.clamp(finite(motionShift, defaults.motionShift), 0.0, 1.0);
        cameraPush = Math.clamp(finite(cameraPush, defaults.cameraPush), 0.0, 1.0);
        smoothFlow = Math.clamp(finite(smoothFlow, defaults.smoothFlow), 0.0, 1.0);
        pickupFadeSeconds = Math.clamp(finite(pickupFadeSeconds, defaults.pickupFadeSeconds), 0.05, 3.0);
        if (!"solid".equals(colorMode) && !"gradient".equals(colorMode) && !"rainbow".equals(colorMode)) colorMode = "gradient";
        rainbowSpeed = Math.clamp(finite(rainbowSpeed, defaults.rainbowSpeed), 0.02, 2.0);
        effectStrength = Math.clamp(finite(effectStrength, defaults.effectStrength), 0.005, 2.0);
        tailWidthScale = Math.clamp(finite(tailWidthScale, defaults.tailWidthScale), 0.0, 1.5);
        middleWidthScale = Math.clamp(finite(middleWidthScale, defaults.middleWidthScale), 0.0, 2.0);
        headWidthScale = Math.clamp(finite(headWidthScale, defaults.headWidthScale), 0.05, 2.0);
        pickupFlashStrength = Math.clamp(finite(pickupFlashStrength, defaults.pickupFlashStrength), 0.1, 2.0);
        pickupFlashSeconds = Math.clamp(finite(pickupFlashSeconds, defaults.pickupFlashSeconds), 0.08, 1.0);
        pickupFlashSize = Math.clamp(finite(pickupFlashSize, defaults.pickupFlashSize), 0.02, 8.34);
        if (!"none".equals(pickupFlashStyle) && !isExternalEffect(pickupFlashStyle, AssetRole.PICKUP)) pickupFlashStyle = DEFAULT_PICKUP_STYLE;
        if (!isExternalEffect(flightEffect, AssetRole.ORB)) flightEffect = "none";
        if (!validTrailArtwork(trailSprite)) trailSprite = "none";
        if (!"ribbon".equals(trailSpriteMode)) trailSpriteMode = "stamps";
        if (ribbonMapping == null || !RIBBON_MAPPINGS.contains(ribbonMapping)) ribbonMapping = "stretch";
        ribbonRepeatLength = Math.clamp(finite(ribbonRepeatLength, defaults.ribbonRepeatLength), 0.1, 4.0);
        orbScale = Math.clamp(finite(orbScale, defaults.orbScale), 0.25, 4.0);
        stampSpacing = Math.clamp(finite(stampSpacing, defaults.stampSpacing), 0.25, 10.0);
        stampScale = Math.clamp(finite(stampScale, defaults.stampScale), 0.20, 10.0);
        pulseSpeed = Math.clamp(finite(pulseSpeed, defaults.pulseSpeed), 0.1, 4.0);
        shardCount = Math.clamp(shardCount, 3, 16);
        spiralTurns = Math.clamp(finite(spiralTurns, defaults.spiralTurns), 0.15, 2.0);
        gradientStops = validStops(gradientStops);
    }

    static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    boolean usesCustomCrossSection() {
        return customCrossSectionEnabled && crossSectionRadii.length != 0;
    }

    void resolveCrossSection(boolean hasSides, boolean hasRadii, boolean hasSwitch) {
        if (hasSwitch) return;
        if (hasRadii) customCrossSectionEnabled = true;
        else if (hasSides && crossSectionRadii.length != 0) customCrossSectionEnabled = false;
    }

    static boolean isExternalEffect(String id, AssetRole role) {
        return (EffectLibrary.isFileId(id) || SpriteLibrary.isSpriteId(id)) && role.accepts(id);
    }

    static boolean validTrailArtwork(String id) {
        return "none".equals(id) || "body".equals(id) || (SpriteLibrary.isSpriteId(id) && AssetRole.TRAIL.accepts(id));
    }

    static int[] validStops(int[] colors) {
        if (colors == null) return new int[0];
        return colors.length <= MAX_GRADIENT_STOPS ? colors : Arrays.copyOf(colors, MAX_GRADIENT_STOPS);
    }

    int gradientColor(double progress) {
        double position = Math.clamp(progress, 0.0, 1.0) * (gradientStops.length + 1);
        int segment = Math.min((int) position, gradientStops.length);
        int a = segment == 0 ? startColor : gradientStops[segment - 1];
        int b = segment == gradientStops.length ? endColor : gradientStops[segment];
        float t = (float) (position - segment);
        return lerpChannel(a >> 16, b >> 16, t) << 16
                | lerpChannel(a >> 8, b >> 8, t) << 8 | lerpChannel(a, b, t);
    }

    private static int lerpChannel(int a, int b, float t) {
        a &= 255;
        return (int) (a + ((b & 255) - a) * t);
    }
}
