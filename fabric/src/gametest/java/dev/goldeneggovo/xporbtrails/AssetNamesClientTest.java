package dev.goldeneggovo.xporbtrails;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Path;
import java.util.List;

final class AssetNamesClientTest {
    static void run(ClientGameTestContext context) {
        Path sprites = Path.of(System.getProperty("xporb.spriteExamples"));
        String language = context.computeOnClient(mc -> mc.getLanguageManager().getSelected());
        var parent = context.computeOnClient(mc -> mc.gui.screen());
        var config = context.computeOnClient(mc -> XpOrbTrailsClient.CONFIG);
        try {
            EffectCollectionClientTest.withPack(context, () -> EffectCollectionClientTest.withPack(context, sprites, () -> {
                for (String code : List.of("en_us", "zh_cn")) {
                    if (!code.equals(context.computeOnClient(mc -> mc.getLanguageManager().getSelected())))
                        StarfallClientTest.language(context, code);
                    boolean zh = code.equals("zh_cn");
                    context.runOnClient(mc -> {
                        XpOrbTrailsClient.CONFIG = new TrailConfig();
                        mc.gui.setScreen(XpOrbTrailsClient.createConfigScreen(parent));
                    });
                    context.clickScreenButton("screen.xporbtrails.profiles");
                    context.runOnClient(mc -> {
                        for (var style : EffectCollectionClientTest.profiles()) {
                            String label = style.displayName().getString();
                            if (zh && label.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
                                throw new AssertionError("Missing Chinese profile name: " + label);
                            TrailClientTest.findControl(mc.gui.screen(), label);
                        }
                        TrailClientTest.findControl(mc.gui.screen(), zh ? "星落" : "Starfall");
                    });
                    String collection = Path.of(System.getProperty("xporb.effectCollection")).getFileName().toString();
                    TrailClientTest.click(context, (zh ? "蝶群归形" : "Butterfly Homecoming") + " · " + collection);
                    TrailClientTest.click(context, (zh ? "溪中锦鲤" : "Koi Stream") + " · " + sprites.getFileName());
                    artwork(context, "orb_body", "flight_effect", zh ? "瓷绘锦鲤" : "Porcelain Koi");
                    artwork(context, "trail", "trail_sprite", zh ? "潺潺流水" : "River Current");
                    artwork(context, "pickup", "flash_style", zh ? "冠状水花" : "Water Crown");
                    System.out.println("XPORB_LOCALIZATION " + code + " verified both packs, 24 profile labels and three artwork menus");
                }
            }));
        } finally {
            context.runOnClient(mc -> {
                mc.gui.setScreen(parent);
                XpOrbTrailsClient.CONFIG = config;
                XpOrbTrailsClient.saveConfig();
            });
            if (!language.equals(context.computeOnClient(mc -> mc.getLanguageManager().getSelected())))
                StarfallClientTest.language(context, language);
        }
    }

    private static void artwork(ClientGameTestContext context, String tab, String setting, String expected) {
        context.clickScreenButton("screen.xporbtrails." + tab);
        context.runOnClient(mc -> {
            String actual = TrailClientTest.findControl(mc.gui.screen(), "screen.xporbtrails." + setting).getMessage().getString();
            if (!expected.equals(actual)) throw new AssertionError(setting + ": expected " + expected + ", got " + actual);
        });
        TrailClientTest.click(context, "screen.xporbtrails." + setting);
        TrailClientTest.click(context, expected);
    }
}
