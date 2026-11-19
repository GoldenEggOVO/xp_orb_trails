package dev.goldeneggovo.xporbtrails;

import net.minecraft.util.Util;
import dev.goldeneggovo.xporbtrails.SettingsButton.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

public final class TrailConfigScreen extends Screen {
    private enum Page { GENERAL, TRAIL, PICKUP, ORB_BODY, PROFILES }
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("xporbtrails", "icon.png");
    static final int ACCENT = 0xFF86EBA5;
    static final int TEXT = 0xFFE6EBE8;
    static final int PANEL = 0xF4101716;
    static final int RULE = 0xFF425A51;
    private final Map<Page, Double> pageScrolls = new EnumMap<>(Page.class);
    private final Map<String, Boolean> expandedGroups = new HashMap<>();
    private final Screen parent;
    private final TrailPreviewScreen preview;
    private Page page = Page.GENERAL;
    private TrailSettingsList list;
    private double scroll;
    private int selectedColor = -1;
    private int selectedProfile = -1;
    private TrailConfig.SavedProfile appliedProfile;
    private String lastTrailArtwork;
    private Component fileReloadStatus = Component.empty();
    private Button activeTab;
    private boolean wide;
    private int panelX, panelY, panelWidth, panelBottom;
    private int contentX, sidebarWidth, listY, footerY, previewX, previewWidth;

    public TrailConfigScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
        preview = new TrailPreviewScreen(this);
    }
    private static MutableComponent text(String key) { return Component.translatable("screen.xporbtrails." + key); }
    private static MutableComponent tabLabel(Page page) { return text(page.name().toLowerCase(Locale.ROOT)); }

    @Override protected void init() {
        wide = width >= 680 && height >= 220;
        panelWidth = Math.min(width - (wide ? 32 : 16), wide ? 920 : 480);
        panelX = (width - panelWidth) / 2;
        panelY = wide ? Math.max(12, (height - 372) / 2) : Math.max(6, (height - 448) / 2);
        panelBottom = height - panelY;
        sidebarWidth = wide ? panelWidth * 18 / 100 : 0;
        int panelRight = panelX + panelWidth;
        contentX = panelX + sidebarWidth;
        previewWidth = wide ? panelWidth / 4 : 0;
        previewX = panelRight - previewWidth - 12;
        listY = panelY + (wide ? 42 : 53);
        footerY = panelBottom - 32;
        int tabX = panelX + 8;
        int unusedWidth = panelWidth - 16;
        for (Page tab : Page.values()) unusedWidth -= font.width(tabLabel(tab));
        for (Page tab : Page.values()) {
            int tabWidth = wide ? sidebarWidth - 8 : font.width(tabLabel(tab))
                    + (unusedWidth * (tab.ordinal() + 1) / Page.values().length - unusedWidth * tab.ordinal() / Page.values().length);
            int tabY = panelY + (wide ? 58 + tab.ordinal() * 27 : 27);
            Button button = new SettingsButton(tabX, tabY, tabWidth, tabLabel(tab), b -> openPage(tab),
                    wide ? Style.SIDEBAR : Style.TAB, tab == page);
            if (!wide) tabX += tabWidth;
            if (tab == page) activeTab = button;
            addRenderableWidget(button);
        }
        int listWidth = (wide ? previewX - 12 : panelRight) - contentX - 16;
        list = addRenderableWidget(new TrailSettingsList(contentX + 8, listY, listWidth, footerY - listY - 4));
        switch (page) {
            case ORB_BODY -> orbBody();
            case TRAIL -> trail();
            case PICKUP -> pickup();
            case PROFILES -> profiles();
            case GENERAL -> general();
        }
        list.setScrollAmount(scroll);
        int fw = Math.min(100, (panelWidth - sidebarWidth - 32) / 3);
        int buttonY = footerY + 6;
        if (page == Page.PROFILES) {
            var folder = XpOrbTrailsClient.assetDirectory().toAbsolutePath().normalize();
            int available = panelRight - 24 - fw * 2 - (contentX + 8);
            var label = text("open_config_folder");
            var open = new SettingsButton(contentX + 8, buttonY, Math.min(available, font.width(label) + 16),
                    label, b -> Util.getPlatform().openPath(folder), Style.CONTROL, false);
            open.setTooltip(Tooltip.create(Component.literal(folder.toString())));
            addRenderableWidget(open);
        } else {
            addRenderableWidget(new SettingsButton(contentX + 8, buttonY, fw, text("reset"), b -> resetPage(),
                    Style.QUIET, false));
        }
        boolean pickupPage = page == Page.PICKUP;
        addRenderableWidget(new SettingsButton(panelRight - 16 - fw * 2, buttonY, fw,
                text(pickupPage ? "preview_pickup" : "open_preview"), b -> {
                    rememberScroll();
                    XpOrbTrailsClient.saveConfig();
                    if (pickupPage) preview.replayPickup();
                    else preview.replay();
                    minecraft.gui.setScreen(preview);
                }, Style.CONTROL, false));
        addRenderableWidget(new SettingsButton(panelRight - 8 - fw, buttonY, fw, Component.translatable("gui.done"),
                b -> onClose(), Style.PRIMARY, false));
    }

    private void openPage(Page next) {
        if (page == next) return;
        pageScrolls.put(page, list.scrollAmount());
        page = next;
        scroll = pageScrolls.getOrDefault(next, 0.0);
        rebuildWidgets();
    }

    private void orbBody() {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        toggle("show_vanilla_orb", () -> c.showVanillaOrb, v -> c.showVanillaOrb = v);
        selectEffect(AssetRole.ORB, "flight_effect", c.flightEffect, v -> c.flightEffect = v);
        var sprite = XpOrbTrailsClient.sprite(AssetRole.ORB, c.flightEffect);
        var effect = XpOrbTrailsClient.effect(AssetRole.ORB, c.flightEffect);
        if (sprite != null || effect != null) slider("orb_scale", c.orbScale, 0.25, 4, 2, v -> c.orbScale = v);
        if (sprite != null) list.note(text("png_colors"));
        else if (effect != null && effect.usesTrailColors()) sharedColorsLink();
    }

    private void pickup() {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        toggle("pickup_flash", () -> c.pickupFlash, v -> {
            c.pickupFlash = v;
            refresh();
            focusRow("pickup_flash");
        });
        if (!c.pickupFlash) return;
        selectEffect(AssetRole.PICKUP, "flash_style", c.pickupFlashStyle, v -> c.pickupFlashStyle = v);
        slider("pickup_flash_size", c.pickupFlashSize, 0.02, 8.34, 2, v -> c.pickupFlashSize = v);
        slider("pickup_flash_strength", c.pickupFlashStrength, 0.1, 2.0, 2, v -> c.pickupFlashStrength = v);
        if (XpOrbTrailsClient.sprite(AssetRole.PICKUP, c.pickupFlashStyle) != null) {
            list.note(text("pickup_png_timing"));
        } else {
            slider("pickup_flash_duration", c.pickupFlashSeconds, 0.08, 1.0, 2, v -> c.pickupFlashSeconds = v);
            var effect = XpOrbTrailsClient.effect(AssetRole.PICKUP, c.pickupFlashStyle);
            if (effect != null && effect.usesTrailColors()) sharedColorsLink();
        }
    }

    private void trail() {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        boolean body = "body".equals(c.trailSprite);
        boolean pngSelected = SpriteLibrary.isSpriteId(c.trailSprite);
        SpriteSheet sheet = XpOrbTrailsClient.sprite(AssetRole.TRAIL, c.trailSprite);
        boolean png = sheet != null;
        boolean stamps = png && "stamps".equals(c.trailSpriteMode);
        var artwork = new ArrayList<>(XpOrbTrailsClient.sprites(AssetRole.TRAIL).keySet());
        if (png) lastTrailArtwork = c.trailSprite;
        String type = pngSelected ? c.trailSpriteMode : c.trailSprite;
        row("trail_type", button(typeLabel(type), () -> {
            List<TrailChoiceScreen.Choice> choices = new ArrayList<>();
            for (String id : List.of("none", "stamps", "ribbon", "body")) {
                boolean needsPng = "stamps".equals(id) || "ribbon".equals(id);
                choices.add(new TrailChoiceScreen.Choice(typeLabel(id), () -> {
                    if (needsPng) {
                        c.trailSpriteMode = id;
                        c.trailSprite = lastTrailArtwork != null && XpOrbTrailsClient.sprite(AssetRole.TRAIL, lastTrailArtwork) != null
                                ? lastTrailArtwork : artwork.getFirst();
                    } else {
                        c.trailSprite = id;
                    }
                    changed();
                }, needsPng && artwork.isEmpty() ? text("no_trail_png") : null));
            }
            choose("trail_type", choices);
        }));
        if (pngSelected) {
            select("trail_sprite", TrailConfigScreen::trailLabel, c.trailSprite, artwork, v -> c.trailSprite = v);
            if (!png) list.note(text("missing_trail_png"));
        }
        if (body) {
            var sourceBody = FixedStampHistory.Source.selected(c);
            if (sourceBody == null) {
                list.row(Component.empty(), button(text("trail_sprite.body_hidden"), () -> openPage(Page.ORB_BODY)));
                return;
            }
            boolean art = sourceBody.sprite() != null || sourceBody.vector() != null;
            Component source = !art ? text("vanilla_orb") : !sourceBody.vanilla() ? effectLabel(AssetRole.ORB, c.flightEffect)
                    : Component.translatable("screen.xporbtrails.vanilla_with_art", effectLabel(AssetRole.ORB, c.flightEffect));
            row("afterimage_source", button(source, () -> openPage(Page.ORB_BODY)));
        }
        if (stamps || body) {
            slider("stamp_scale", c.stampScale, 0.20, 10, 2, v -> c.stampScale = v);
            slider("stamp_spacing", c.stampSpacing, 0.25, 10, 2, v -> c.stampSpacing = v);
            if (stamps) toggle("fixed_stamps", () -> c.fixedStamps, v -> c.fixedStamps = v);
        } else {
            slider("width", c.width, 0.02, 1.0, 2, v -> c.width = v);
            if (png) ribbonControls(c, sheet);
        }
        slider("lifetime", c.lifetimeSeconds, 0.25, 10.0, 2, v -> c.lifetimeSeconds = v);
        slider("pickup_fade", c.pickupFadeSeconds, 0.05, 3.0, 2, v -> c.pickupFadeSeconds = v);
        if (!png && !body) {
            if (c.crossSectionRadii.length != 0) {
                toggle("custom_cross_section", () -> c.customCrossSectionEnabled, enabled -> {
                    c.customCrossSectionEnabled = enabled;
                    refresh();
                    focusRow("custom_cross_section");
                });
            }
            if (c.usesCustomCrossSection()) {
                list.note(Component.translatable("screen.xporbtrails.custom_cross_section.points", c.crossSectionRadii.length));
            } else {
                slider("cross_section", c.crossSectionSides, 2, 32, 0, v -> c.crossSectionSides = (int) Math.round(v));
            }
            sharedColorsLink();
        } else if (png) {
            list.note(text("png_colors"));
        } else {
            var orbEffect = XpOrbTrailsClient.effect(AssetRole.ORB, c.flightEffect);
            if (orbEffect != null && orbEffect.usesTrailColors()) sharedColorsLink();
        }
        toggle("flight_pulse", () -> c.flightPulse, v -> c.flightPulse = v);
        group("animation", false, () -> {
            slider("smoothness", c.smoothFlow, 0.0, 1.0, 2, v -> c.smoothFlow = v);
            slider("motion_shift", c.motionShift, 0.0, 1.0, 2, v -> c.motionShift = v);
            slider("camera_push", c.cameraPush, 0.0, 1.0, 2, v -> c.cameraPush = v);
        });
        group("custom_shape", false, () -> {
            slider("tail_width", c.tailWidthScale, 0.0, 1.5, 2, v -> c.tailWidthScale = v);
            slider("middle_width", c.middleWidthScale, 0.0, 2.0, 2, v -> c.middleWidthScale = v);
            slider("head_width", c.headWidthScale, 0.05, 2.0, 2, v -> c.headWidthScale = v);
        });
    }

    private static Component typeLabel(String type) {
        return switch (type) {
            case "stamps", "ribbon" -> text("trail_sprite_mode." + type);
            default -> trailLabel(type);
        };
    }

    private void sharedColorsLink() {
        TrailConfig config = XpOrbTrailsClient.CONFIG;
        list.paletteRow(text("mode." + config.colorMode), config, button(text("edit_shared_colors"), () -> {
            expandedGroups.put("colors", true);
            openPage(Page.GENERAL);
            focusRow("color_mode");
        }));
    }

    static Component trailLabel(String id) {
        return switch (id) {
            case "none" -> text("trail_sprite.default");
            case "body" -> text("trail_sprite.body");
            default -> effectLabel(AssetRole.TRAIL, id);
        };
    }

    private void ribbonControls(TrailConfig c, SpriteSheet sheet) {
        var caps = sheet.ribbon();
        String mapping = caps == null ? c.ribbonMapping : caps.middle();
        if (caps == null) {
            select("ribbon_mapping", v -> text("ribbon_mapping." + v), c.ribbonMapping,
                    TrailConfig.RIBBON_MAPPINGS, v -> c.ribbonMapping = v);
        } else {
            var mode = button(text("ribbon_mapping." + mapping), () -> {});
            mode.active = false;
            row("ribbon_caps", mode);
        }
        if ("tile".equals(mapping) || "static".equals(mapping)) {
            slider("ribbon_repeat_length", c.ribbonRepeatLength, 0.1, 4, 2, v -> c.ribbonRepeatLength = v);
        }
    }

    private void colors(TrailConfig c) {
        group("colors", true, () -> {
            select("color_mode", v -> text("mode." + v), c.colorMode, List.of("solid", "gradient", "rainbow"), v -> c.colorMode = v);
            if ("rainbow".equals(c.colorMode)) {
                slider("rainbow_speed", c.rainbowSpeed, 0.02, 2.0, 2, v -> c.rainbowSpeed = v);
            } else {
                color("start_color", c.startColor, v -> c.startColor = v);
                if ("gradient".equals(c.colorMode)) {
                    color("end_color", c.endColor, v -> c.endColor = v);
                    if (c.gradientStops.length > 0) {
                        list.row(Component.translatable("screen.xporbtrails.gradient_stops", c.gradientStops.length),
                                button(text("clear_stops"), () -> {
                                    c.gradientStops = new int[0]; changed(); saveAndRefresh();
                                }));
                    }
                }
            }
            if (!"rainbow".equals(c.colorMode) || !c.savedPresets.isEmpty()) {
                group("saved_colors", false, () -> savedColors(c));
            }
        });
    }

    private void savedColors(TrailConfig c) {
        if (!"rainbow".equals(c.colorMode)) {
            Button save = button(text("save_preset"), () -> {
                if (c.savedPresets.size() >= 12) return;
                c.savedPresets.add(new TrailConfig.SavedPreset(
                        nextSavedName("saved_preset_name", c.savedPresets.stream().map(p -> p.name).toList()), c.startColor, c.endColor, c.gradientStops));
                saveAndRefresh();
            });
            save.setTooltip(Tooltip.create(text("save_preset.tip")));
            save.active = c.savedPresets.size() < 12;
            list.row(Component.empty(), save);
        }
        for (int i = 0; i < c.savedPresets.size(); i++) {
            int index = i;
            TrailConfig.SavedPreset preset = c.savedPresets.get(i);
            Button apply = button(Component.literal(preset.name).withStyle(ChatFormatting.WHITE), () -> {
                if ("rainbow".equals(c.colorMode)) c.colorMode = "gradient";
                c.startColor = preset.startColor;
                c.endColor = preset.endColor;
                c.gradientStops = preset.gradientStops.clone();
                changed();
                saveAndRefresh();
            }, preset.startColor, preset.endColor);
            apply.setTooltip(Tooltip.create(text("apply_color.tip")));
            list.actions(apply, button(text("manage_color"), () -> {
                selectedColor = selectedColor == index ? -1 : index;
                refresh();
            }));
            if (selectedColor != index) continue;
            list.nested(() -> {
                EditBox name = new SettingsEditBox(font, 0, 0, 150, 20, text("color_name"));
                name.setMaxLength(32); name.setValue(preset.name);
                row("color_name", name);
                Button rename = button(text("rename_color"), () -> {
                    String value = name.getValue().trim();
                    if (value.isEmpty()) return;
                    preset.name = value; saveAndRefresh();
                });
                rename.active = false;
                name.setResponder(value -> rename.active = !value.isBlank() && !value.trim().equals(preset.name));
                list.row(Component.empty(), rename);
                list.row(Component.empty(), button(text("overwrite_color"), () -> {
                    c.savedPresets.set(index, new TrailConfig.SavedPreset(preset.name, c.startColor, c.endColor, c.gradientStops));
                    saveAndRefresh();
                }));
                deleteButton("delete_color", "confirm_delete_color", () -> {
                    c.savedPresets.remove(index); selectedColor = -1; saveAndRefresh();
                });
            });
        }
    }

    private void general() {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        toggle("enabled", () -> c.enabled, v -> c.enabled = v);
        slider("effect_strength", c.effectStrength, 0.005, 2.0, 2, v -> c.effectStrength = v);
        toggle("glow", () -> c.additiveGlow, v -> c.additiveGlow = v);
        colors(c);
        group("performance", false, () -> {
            slider("range", c.renderRange, 4.0, 128.0, 0, v -> c.renderRange = v);
            slider("cap", c.trailCap, 8.0, 256.0, 0, v -> c.trailCap = (int) Math.round(v));
        });
    }

    private void profiles() {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        group("file_styles", true, () -> {
            var reload = button(text("reload_styles"), () -> {
                int errors = XpOrbTrailsClient.reloadFileStyles();
                fileReloadStatus = errors == 0
                        ? Component.translatable("screen.xporbtrails.loaded_styles", XpOrbTrailsClient.fileStyles().size(), XpOrbTrailsClient.effects().size() + XpOrbTrailsClient.sprites().size())
                        : Component.translatable("screen.xporbtrails.style_errors", errors).withStyle(ChatFormatting.GOLD);
                refresh();
                focusRow("reload_styles");
            });
            reload.setTooltip(Tooltip.create(text("reload_styles.tip")));
            list.row(fileReloadStatus, reload);
            XpOrbTrailsClient.fileStyles().forEach(this::styleRow);
        });
        group("saved_profiles", true, () -> savedProfiles(c));
    }

    private void styleRow(FileTrailStyles.Style style) {
        list.row(style.settings() == appliedProfile ? text("profile_in_use") : Component.empty(),
                button(style.displayName(), () -> {
                    apply(style.settings(), false);
                    saveAndRefresh();
                }));
    }

    private void savedProfiles(TrailConfig c) {
        for (int i = 0; i < c.savedProfiles.size(); i++) {
            int index = i;
            TrailConfig.SavedProfile p = c.savedProfiles.get(i);
            list.row(Component.literal(p.name), button(text("manage_profile"), () -> {
                selectedProfile = index;
                expandedGroups.put("edit_selected_profile", true);
                refresh();
                focusRow("profile_name");
            }));
        }
        Button save = button(text("save_profile"), () -> {
            if (c.savedProfiles.size() >= 8) return;
            c.savedProfiles.add(new TrailConfig.SavedProfile(
                    nextSavedName("saved_profile_name", c.savedProfiles.stream().map(p -> p.name).toList()), c));
            selectedProfile = c.savedProfiles.size() - 1;
            expandedGroups.put("edit_selected_profile", true);
            saveAndRefresh();
            focusRow("profile_name");
        });
        save.setTooltip(Tooltip.create(text("save_profile.tip")));
        save.active = c.savedProfiles.size() < 8;
        list.row(Component.empty(), save);
        if (selectedProfile < 0 || selectedProfile >= c.savedProfiles.size()) return;
        group("edit_selected_profile", true, () -> editProfile(c, c.savedProfiles.get(selectedProfile)));
    }

    private void editProfile(TrailConfig c, TrailConfig.SavedProfile selected) {
        list.row(Component.empty(), button(text("apply_profile"), () -> {
            apply(selected, true); saveAndRefresh();
        }));
        EditBox name = new SettingsEditBox(font, 0, 0, 150, 20, text("profile_name"));
        name.setMaxLength(32); name.setValue(selected.name);
        row("profile_name", name);
        Button rename = button(text("rename_profile"), () -> {
            String value = name.getValue().trim();
            if (value.isEmpty()) return;
            selected.name = value;
            saveAndRefresh();
        });
        rename.active = false;
        name.setResponder(value -> rename.active = !value.isBlank() && !value.trim().equals(selected.name));
        list.row(Component.empty(), rename);
        list.row(Component.empty(), button(text("overwrite_profile"), () -> {
            c.savedProfiles.set(selectedProfile, new TrailConfig.SavedProfile(selected.name, c)); saveAndRefresh();
        }));
        deleteButton("delete_profile", "confirm_delete_profile", () -> {
            c.savedProfiles.remove(selectedProfile); selectedProfile = -1; appliedProfile = null; saveAndRefresh();
        });
    }

    private void apply(TrailConfig.SavedProfile settings, boolean saved) {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        boolean enabled = c.enabled;
        double renderRange = c.renderRange;
        int trailCap = c.trailCap;
        settings.applyTo(c);
        if (!saved) {
            c.enabled = enabled; c.renderRange = renderRange; c.trailCap = trailCap;
        }
        appliedProfile = settings;
    }

    private void resetPage() {
        TrailConfig c = XpOrbTrailsClient.CONFIG, d = XpOrbTrailsClient.defaults();
        switch (page) {
            case ORB_BODY -> {
                c.flightEffect = d.flightEffect; c.showVanillaOrb = d.showVanillaOrb; c.orbScale = d.orbScale;
            }
            case TRAIL -> {
                c.width = d.width; c.lifetimeSeconds = d.lifetimeSeconds; c.pickupFadeSeconds = d.pickupFadeSeconds;
                c.trailSprite = d.trailSprite; c.trailSpriteMode = d.trailSpriteMode;
                c.stampScale = d.stampScale; c.stampSpacing = d.stampSpacing;
                c.fixedStamps = d.fixedStamps; c.ribbonMapping = d.ribbonMapping; c.ribbonRepeatLength = d.ribbonRepeatLength;
                c.flightPulse = d.flightPulse;
                c.tailWidthScale = d.tailWidthScale; c.middleWidthScale = d.middleWidthScale; c.headWidthScale = d.headWidthScale;
                c.crossSectionSides = d.crossSectionSides;
                c.crossSectionRadii = d.crossSectionRadii.clone(); c.customCrossSectionEnabled = d.customCrossSectionEnabled;
                c.smoothFlow = d.smoothFlow; c.motionShift = d.motionShift; c.cameraPush = d.cameraPush;
                lastTrailArtwork = null;
            }
            case PICKUP -> {
                c.pickupFlash = d.pickupFlash; c.pickupFlashStyle = d.pickupFlashStyle; c.pickupFlashStrength = d.pickupFlashStrength;
                c.pickupFlashSeconds = d.pickupFlashSeconds; c.pickupFlashSize = d.pickupFlashSize;
                c.shardCount = d.shardCount; c.spiralTurns = d.spiralTurns;
            }
            case GENERAL -> {
                c.enabled = d.enabled; c.effectStrength = d.effectStrength; c.additiveGlow = d.additiveGlow;
                c.colorMode = d.colorMode; c.startColor = d.startColor; c.endColor = d.endColor;
                c.gradientStops = d.gradientStops; c.rainbowSpeed = d.rainbowSpeed;
                c.renderRange = d.renderRange; c.trailCap = d.trailCap;
            }
            case PROFILES -> { return; }
        }
        changed(); saveAndRefresh();
    }

    private void row(String key, AbstractWidget widget) {
        widget.setTooltip(Tooltip.create(text(key + ".tip")));
        list.row(text(key), widget);
    }
    private Button button(Component label, Runnable action, int... swatches) {
        return new SettingsButton(0, 0, 150, label, b -> action.run(), Style.CONTROL, false, swatches);
    }
    private void deleteButton(String key, String confirmKey, Runnable remove) {
        Component label = text(key);
        var delete = new SettingsButton(0, 0, 150, label, b -> {
            if (b.getMessage().equals(label)) b.setMessage(text(confirmKey));
            else remove.run();
        }, Style.DELETE, false) {
            @Override public void setFocused(boolean focused) {
                super.setFocused(focused);
                if (!focused) setMessage(label);
            }
        };
        list.row(Component.empty(), delete);
    }
    private void group(String key, boolean initiallyExpanded, Runnable children) {
        boolean expanded = expandedGroups.getOrDefault(key, initiallyExpanded);
        var fold = new SettingsButton(0, 0, 150, text(key), b -> {
            expandedGroups.put(key, !expanded);
            refresh();
            focusRow(key);
        }, Style.FOLD, expanded) {
            @Override protected MutableComponent createNarrationMessage() {
                return getMessage().copy().append(" ").append(text(expanded ? "collapse" : "expand"));
            }
        };
        if (Language.getInstance().has("screen.xporbtrails." + key + ".tip")) fold.setTooltip(Tooltip.create(text(key + ".tip")));
        list.row(Component.empty(), fold);
        if (expanded) list.nested(children);
    }
    private void toggle(String key, BooleanSupplier value, Consumer<Boolean> setter) {
        row(key, new SettingsButton(0, 0, 150, toggleLabel(value.getAsBoolean()), b -> {
            boolean on = !value.getAsBoolean();
            changed();
            setter.accept(on);
            b.setMessage(toggleLabel(on));
            ((SettingsButton) b).selected = on;
        }, Style.TOGGLE, value.getAsBoolean()) {
            @Override protected MutableComponent createNarrationMessage() {
                return text(key).append(" ").append(getMessage());
            }
        });
    }
    private static MutableComponent toggleLabel(boolean on) {
        return Component.translatable(on ? "options.on" : "options.off");
    }
    private void slider(String key, double value, double min, double max, int decimals, DoubleConsumer setter) {
        row(key, new ConfigSlider(text(key), value, min, max, decimals, v -> { setter.accept(v); changed(); }));
    }
    static Component effectLabel(AssetRole role, String id) {
        if ("none".equals(id)) return Component.translatable("options.off");
        if (SpriteLibrary.isSpriteId(id)) {
            var sheet = XpOrbTrailsClient.sprite(role, id);
            return sheet == null ? Component.translatable("screen.xporbtrails.missing_effect", id) : sheet.displayName();
        }
        var effect = XpOrbTrailsClient.effect(role, id);
        return effect == null ? Component.translatable("screen.xporbtrails.missing_effect", id)
                : effect.displayName();
    }

    private void selectEffect(AssetRole role, String key, String current, Consumer<String> setter) {
        var options = new ArrayList<String>();
        if (role == AssetRole.ORB) options.add("none");
        options.addAll(XpOrbTrailsClient.effects(role).keySet());
        options.addAll(XpOrbTrailsClient.sprites(role).keySet());
        select(key, id -> effectLabel(role, id), current, options, setter);
    }

    private void select(String key, Function<String, Component> label, String current, List<String> values, Consumer<String> setter) {
        Button selector = button(label.apply(current), () -> {
            List<TrailChoiceScreen.Choice> choices = new ArrayList<>();
            for (String value : values) choices.add(new TrailChoiceScreen.Choice(label.apply(value), () -> { setter.accept(value); changed(); }));
            choose(key, choices);
        });
        selector.active = !values.isEmpty();
        row(key, selector);
    }
    private void color(String key, int value, IntConsumer setter) {
        var hex = Component.literal(String.format(Locale.ROOT, "#%06X", value & 0xFFFFFF)).withStyle(ChatFormatting.WHITE);
        row(key, button(hex, () -> {
            rememberScroll();
            minecraft.gui.setScreen(new ColorPickerScreen(this, value, v -> { setter.accept(v); changed(); }));
        }, value));
    }
    static Component crossSectionLabel(int sides) {
        return Component.translatable("screen.xporbtrails.cross_section." +
                (sides == 2 ? "flat" : sides == 32 ? "round" : "sides"), sides);
    }
    private void choose(String key, List<TrailChoiceScreen.Choice> choices) { rememberScroll(); minecraft.gui.setScreen(new TrailChoiceScreen(this, text(key), choices)); }
    private void changed() {
        appliedProfile = null;
    }
    private void rememberScroll() { if (list != null) scroll = list.scrollAmount(); }
    private static String nextSavedName(String key, List<String> names) {
        int number = 1;
        String name;
        do { name = Component.translatable("screen.xporbtrails." + key, number++).getString(); }
        while (names.contains(name));
        return name;
    }
    private void refresh() { rememberScroll(); rebuildWidgets(); }
    private void saveAndRefresh() { XpOrbTrailsClient.saveConfig(); refresh(); }
    private void focusRow(String key) {
        Component label = text(key);
        for (var row : list.children()) {
            if (row.matches(label)) {
                if (row.getY() < list.getY() || row.getY() + row.getHeight() > list.getBottom()) {
                    list.setScrollAmount(list.scrollAmount() + row.getY() - list.getY() - 4);
                }
                changeFocus(ComponentPath.path(row.children().getFirst(), row, list, this));
                return;
            }
        }
    }
    @Override protected void setInitialFocus() {
        if (minecraft.getLastInputType().isKeyboard()) setInitialFocus(activeTab);
    }
    @Override public void setFocused(GuiEventListener child) {
        // Mouse dispatch can finish after its click handler has replaced the widgets.
        if (child == null || children().contains(child)) super.setFocused(child);
    }
    @Override public void resize(int width, int height) { rememberScroll(); super.resize(width, height); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int right = panelX + panelWidth;
        graphics.fill(panelX, panelY, right, panelBottom, PANEL);
        graphics.outline(panelX, panelY, panelWidth, panelBottom - panelY, RULE);
        graphics.fill(contentX + 1, footerY, right - 1, footerY + 1, RULE);
        int iconSize = wide ? 30 : 18;
        int iconY = panelY + (wide ? 10 : 5);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, panelX + 10, iconY, 0, 0,
                iconSize, iconSize, 64, 64, 64, 64);
        graphics.text(font, wide ? Component.literal("XP Orb Trails") : title, panelX + 16 + iconSize,
                iconY + (iconSize - font.lineHeight) / 2, TEXT, false);
        if (wide) {
            graphics.fill(contentX, panelY + 1, contentX + 1, panelBottom - 1, RULE);
            graphics.fill(panelX + 1, panelY + 48, contentX, panelY + 49, RULE);
            graphics.pose().pushMatrix().translate(contentX + 16, panelY + 13).scale(1.5f);
            graphics.text(font, tabLabel(page).withStyle(ChatFormatting.BOLD), 0, 0, TEXT, false);
            graphics.pose().popMatrix();
            int previewHeight = Math.min(previewWidth + 6, footerY - listY - 8);
            preview.extractPreview(graphics, previewX, listY, previewWidth, previewHeight, true);
        } else {
            graphics.fill(panelX + 8, panelY + 47, right - 8, panelY + 48, RULE);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { XpOrbTrailsClient.saveConfig(); minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private final class ConfigSlider extends AbstractSliderButton {
        private static final int READOUT_WIDTH = 39;
        private final Component label;
        private final double min, max;
        private final int decimals;
        private final DoubleConsumer setter;
        ConfigSlider(Component label, double current, double min, double max, int decimals, DoubleConsumer setter) {
            super(0, 0, 150, 20, Component.empty(), Math.max(0, Math.min(1, (current - min) / (max - min))));
            this.label = label; this.min = min; this.max = max; this.decimals = decimals; this.setter = setter; updateMessage();
        }
        private double actual() { return min + value * (max - min); }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), middle = y + height / 2;
            int end = getRight() - READOUT_WIDTH;
            int handle = x + 4 + (int) Math.round((end - x - 8) * value);
            graphics.fill(x + 4, middle - 1, end, middle + 2, RULE);
            graphics.fill(x + 4, middle - 1, handle, middle + 2, 0xFF4E9869);
            graphics.fill(handle - 3, middle - 4, handle + 4, middle + 5, isHoveredOrFocused() ? 0xFFA6FFC0 : ACCENT);
            graphics.text(font, getMessage(), getRight() - font.width(getMessage()) - 4, y + (height - font.lineHeight) / 2, TEXT, false);
            if (isFocused()) graphics.outline(x, y, width, height, ACCENT);
            handleCursor(graphics);
        }

        @Override public boolean isMouseOver(double x, double y) {
            return x < getRight() - READOUT_WIDTH && super.isMouseOver(x, y);
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (canChangeValue && (event.isLeft() || event.isRight())) {
                double scale = Math.pow(10, decimals);
                double next = (Math.round(actual() * scale) + (event.isRight() ? 1 : -1)) / scale;
                setValue((next - min) / (max - min));
                return true;
            }
            return super.keyPressed(event);
        }

        @Override public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
            setTrackValue(event.x());
        }

        @Override protected void onDrag(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
            setTrackValue(event.x());
        }

        private void setTrackValue(double x) {
            setValue((x - getX() - 4) / Math.max(1, getWidth() - READOUT_WIDTH - 8));
        }
        @Override protected void updateMessage() { setMessage(Component.literal(String.format(Locale.ROOT, "%." + decimals + "f", actual()))); }
        @Override protected void applyValue() { setter.accept(actual()); }
        @Override protected MutableComponent createNarrationMessage() { return label.copy().append(": ").append(getMessage()); }
    }
}
