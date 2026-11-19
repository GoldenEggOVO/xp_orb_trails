package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Screenshot;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class TrailEffectsClientTest {
    private static final Vec3 CAMERA = new Vec3(0, 0, 4);
    private record Vertex(Vec3 position, int color) {}

    static void verifyWorld(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("tp @p 0 240 0 0 0");
        world.getServer().runCommand("time set midnight");
        world.getServer().runCommand("weather clear");
        world.getClientLevel().waitForChunksRender();
        context.waitTicks(12);
        int background = captureMagenta(context, "pickup-background");
        for (String style : List.of("soft", "star", "ring", "reflow", "shards", "spiral")) {
            context.runOnClient(client -> {
                TrailRenderer.updateWorld(null);
                var c = new TrailConfig();
                c.pickupFlashStyle = "file:pickup/" + style;
                c.pickupFlashSeconds = 1.0;
                c.pickupFlashSize = 2.5;
                c.effectStrength = c.pickupFlashStrength = 1.0;
                c.startColor = c.endColor = 0xFF00FF;
                c.additiveGlow = false;
                XpOrbTrailsClient.CONFIG = c;
                var camera = client.gameRenderer.getMainCamera().position();
                var orb = new ExperienceOrb(client.level, camera.x, camera.y, camera.z + 4, 1);
                orb.setId(100400);
                client.level.addEntity(orb);
                TrailRenderer.track(orb);
                client.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(orb.getId(), client.player.getId(), 1));
                client.level.removeEntity(orb.getId(), Entity.RemovalReason.DISCARDED);
            });
            context.waitTicks(2);
            int visible = captureMagenta(context, "pickup-world-" + style);
            require(visible >= background + 8, style + " pickup must draw visible magenta pixels: background="
                    + background + ", actual=" + visible);
        }
        verifyFileEffect(context, background);
        TrailSpriteClientTest.verifyWorld(context);
    }

    private static void verifyFileEffect(ClientGameTestContext context, int background) {
        var directory = FabricLoader.getInstance().getConfigDir().resolve("xp_orb_trails/orb/effects");
        try {
            var file = Files.createTempFile(Files.createDirectories(directory), "pixel-probe-", ".json");
            var pickupFile = directory.getParent().getParent().resolve("pickup/effects").resolve(file.getFileName());
            try {
                Files.writeString(file, """
                        {"version":1,"layers":[{"shape":"radial","radii":[1,0.4,1,0.4,1,0.4,1,0.4],
                          "color":"#00ff00","count":2,
                          "frames":[{"t":0,"color":"#ff00ff","radius":1.5,"width":1.2,"height":1.2},
                                    {"t":1,"color":"#db1cff","angle":360}]}]}
                        """);
                Files.copy(file, pickupFile);
                var basename = file.getFileName().toString().replace(".json", "");
                context.runOnClient(client -> {
                    XpOrbTrailsClient.reloadFileStyles();
                    require(XpOrbTrailsClient.effects(AssetRole.ORB).containsKey("file:orb/" + basename), "File effect must load from disk");
                    TrailRenderer.updateWorld(null);
                    var c = new TrailConfig();
                    c.startColor = c.endColor = 0x00FF00;
                    c.additiveGlow = false;
                    c.effectStrength = c.pickupFlashStrength = 1;
                    c.width = 0.3;
                    c.pickupFlash = false;
                    XpOrbTrailsClient.CONFIG = c;
                    var camera = client.gameRenderer.getMainCamera().position();
                    var orb = new ExperienceOrb(client.level, camera.x, camera.y, camera.z + 4, 1);
                    orb.setId(100401);
                    orb.setDeltaMovement(Vec3.ZERO);
                    orb.setNoGravity(true);
                    client.level.addEntity(orb);
                    TrailRenderer.track(orb);
                });
                context.waitTicks(3);
                int off = captureMagenta(context, "file-flight-off");
                context.runOnClient(client -> XpOrbTrailsClient.CONFIG.flightEffect = "file:orb/" + basename);
                context.waitTicks(3);
                int flight = captureMagenta(context, "file-flight-on");
                require(flight >= off + 8, "The file effect must be visible around a stationary orb with pickup off: "
                        + off + " -> " + flight);
                context.runOnClient(client -> {
                    var c = XpOrbTrailsClient.CONFIG;
                    c.flightEffect = "none";
                    c.pickupFlash = true;
                    c.pickupFlashStyle = "file:pickup/" + basename;
                    c.pickupFlashSize = 1.8;
                    c.pickupFlashSeconds = 1;
                    client.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(100401, client.player.getId(), 1));
                    client.level.removeEntity(100401, Entity.RemovalReason.DISCARDED);
                });
                context.waitTicks(2);
                int picked = captureMagenta(context, "file-pickup");
                require(picked >= Math.max(off, background) + 8,
                        "The custom star must render its keyframed magenta on a green trail: " + picked);
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(pickupFile);
                context.runOnClient(client -> XpOrbTrailsClient.reloadFileStyles());
            }
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static int captureMagenta(ClientGameTestContext context, String name) {
        return captureMagenta(context, name, 0.25, 0.75);
    }

    static int captureMagenta(ClientGameTestContext context, String name, double left, double right) {
        return screenshot(context, name, image -> {
            int count = 0;
            for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                for (int x = (int) (image.getWidth() * left); x < (int) (image.getWidth() * right); x++) {
                    int color = image.getPixel(x, y);
                    int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
                    if (Math.min(red, blue) > 110 && Math.min(red, blue) - green > 25) count++;
                }
            }
            System.out.println("XPORB_PICKUP_PIXELS " + name + " " + count);
            return count;
        });
    }

    static <T> T screenshot(ClientGameTestContext context, String name, Function<NativeImage, T> measure) {
        var result = new CompletableFuture<T>();
        context.runOnClient(client -> Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
            try (image) {
                var directory = client.gameDirectory.toPath().resolve("screenshots");
                Files.createDirectories(directory);
                image.writeToFile(directory.resolve(name + ".png"));
                result.complete(measure.apply(image));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }));
        context.waitFor(client -> result.isDone());
        return result.join();
    }

    static void verifyMeshes() {
        try {
            var samples = new ArrayList<TrailRenderer.Sample>();
            long now = 500_000_000L;
            for (int i = 0; i <= 16; i++) samples.add(new TrailRenderer.Sample(new Vec3(i, 0, 0), now));
            var c = new TrailConfig();
            c.startColor = 0x182940;
            c.endColor = 0xC8DC80;
            for (String mode : List.of("solid", "gradient", "rainbow")) {
                c.colorMode = mode;
                var vertices = tube(samples, now, c, -1, false);
                // Original tail, middle and head colors at t = 0.5s, rainbow speed = 0.2.
                var expected = switch (mode) {
                    case "solid" -> List.of(0x182940, 0x182940, 0x182940);
                    case "gradient" -> List.of(0x182940, 0x708260, 0xC8DC80);
                    case "rainbow" -> List.of(0xFFA51F, 0x1FFFC9, 0xED1FFF);
                    default -> throw new AssertionError(mode);
                };
                var actual = List.of(vertices.getFirst().color & 0xFFFFFF, vertices.get(32).color & 0xFFFFFF,
                        vertices.get(vertices.size() - 2).color & 0xFFFFFF);
                require(actual.equals(expected), "Original " + mode + " colors changed: " + actual);
            }
            c.colorMode = "gradient";
            c.gradientStops = new int[]{0xFF00FF};
            var plain = tube(samples, now, c, -1, false);
            require(plain.stream().anyMatch(v -> (v.color & 0xFFFFFF) == 0xFF00FF),
                    "The intermediate gradient stop must appear in the submitted world vertices");
            var pulse = tube(samples, now, c, -1, true);
            require(!plain.equals(pulse) && plain.getFirst().equals(pulse.getFirst()) && plain.getLast().equals(pulse.getLast()),
                    "The flight pulse must alter a band within the trail");
            c.pulseSpeed = 0.5;
            require(!pulse.equals(tube(samples, now, c, -1, true)), "Changing pulse speed must move the band");
            var reflow = tube(samples, now, c, 0.5, false);
            require(reflow.stream().noneMatch(v -> v.position.x < 8)
                            && reflow.stream().anyMatch(v -> v.position.x == 16 && (v.color >>> 24) > 0),
                    "After the reflow front passes, the tail must disappear and the head must remain visible");
            var position = rendererMethod("reflowPosition");
            require(Vec3.ZERO.equals(position.invoke(null, samples, CAMERA, 0.0))
                            && new Vec3(8, 0, 0).equals(position.invoke(null, samples, CAMERA, 0.5))
                            && new Vec3(16, 0, 0).equals(position.invoke(null, samples, CAMERA, 1.0)),
                    "Reflow must travel from the original tail to its pickup point");
            c.pickupFlashStyle = "file:pickup/reflow";
            require(!pieces(c).isEmpty(), "Reflow must draw its moving light");
            c.pickupFlashStyle = "file:pickup/shards";
            for (int count : new int[]{3, 9}) {
                c.shardCount = count;
                require(pieces(c).size() == count * 4, "Each selected shard must submit a quad");
            }
            var shards = pieces(c);
            c.pickupFlashStyle = "file:pickup/spiral";
            var spiral = pieces(c);
            require(!spiral.isEmpty() && !spiral.equals(shards), "Spiral must submit its own geometry");
            c.spiralTurns = 1.75;
            require(!spiral.equals(pieces(c)), "Changing spiral turns must move its submitted vertices");
            c.pickupFlashStyle = "file:pickup/twin-orbit";
            var orbitColors = pieces(c).stream().map(v -> v.color & 0xFFFFFF).distinct().toList();
            require(orbitColors.equals(List.of(0x182940, 0xC8DC80)),
                    "Twin Orbit must submit the tail color on the outer orbit and the head color on the inner orbit");
            c.colorMode = "solid";
            require(pieces(c).stream().allMatch(v -> (v.color & 0xFFFFFF) == 0x182940),
                    "Both orbits must follow the single shared color in solid mode");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static List<Vertex> tube(List<?> samples, long now, TrailConfig c, double reflow, boolean pulse)
            throws ReflectiveOperationException {
        return mesh("appendStyledTube", null, samples, CAMERA, now, 1_000_000_000L, c, c.effectStrength, reflow, pulse);
    }

    private static List<Vertex> pieces(TrailConfig c) throws ReflectiveOperationException {
        var effect = XpOrbTrailsClient.effect(AssetRole.PICKUP, c.pickupFlashStyle);
        require(effect != null, "Expected a pickup effect from disk: " + c.pickupFlashStyle);
        var vertices = mesh("appendEffectPieces", null, Vec3.ZERO, CAMERA, 0.375, c, 0.5, effect,
                0.24 * c.pickupFlashSize, c.effectStrength * c.pickupFlashStrength);
        require(vertices.stream().allMatch(v -> Double.isFinite(v.position.lengthSqr()) && (v.color >>> 24) > 0),
                "Pickup geometry must have finite coordinates and visible colors");
        return vertices;
    }

    private static List<Vertex> mesh(String name, Object... args) throws ReflectiveOperationException {
        var result = new ArrayList<Vertex>();
        Vec3[] lastPosition = new Vec3[1];
        args[0] = Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("addVertex") && values.length == 3)
                        lastPosition[0] = new Vec3((float) values[0], (float) values[1], (float) values[2]);
                    if (method.getName().equals("setColor") && values.length == 1)
                        result.add(new Vertex(lastPosition[0], (int) values[0]));
                    return proxy;
                });
        rendererMethod(name).invoke(null, args);
        return result;
    }

    private static Method rendererMethod(String name) {
        var method = Arrays.stream(TrailRenderer.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return method;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
