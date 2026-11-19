# Advanced customization for XP Orb Trails

This guide covers XP Orb Trails **2.0.0** on Fabric, Forge and NeoForge. Start with the profile below to get a visible result using artwork included with the mod. Then swap in your own orb sprite, draw a ribbon or write a pickup animation. Paths beginning with `config/` or `logs/` start in your Minecraft instance. Shorter asset paths start inside `config/xp_orb_trails/`.

[First profile](#make-your-first-file-profile) · [Sprites](#bring-your-own-sprite) · [Trails](#choose-how-a-trail-is-drawn) · [Pickup effects](#write-a-pickup-effect) · [Troubleshooting](#test-and-share-your-look)

## Make your first file profile

1. Open the mod settings through Mod Menu on Fabric, the Mods screen on Forge or NeoForge, or the **Open Trail Settings** key binding.
2. On **Profiles**, click **Open Config Folder**. Inside the opened folder, create `profiles/moonwake.json` and paste the complete example below.
3. Choose **Profiles → File Profiles → Reload Files**, then click **Moonwake**. Reload reads the files. Clicking the profile applies its settings.
4. Open **Effect Preview** and click **Replay Animation**. Look for a moth in place of the orb, a blue-to-mint trail and an expanding ring at pickup. Collect an orb in your world to see the trail from different angles.

```json
{
  "version": 1,
  "name": "Moonwake",
  "showVanillaOrb": false,
  "flightEffect": "sprite:orb/luna-moth",
  "trailSprite": "none",
  "width": 0.22,
  "crossSectionRadii": [1, 0.32, 1, 0.32, 1, 0.32, 1, 0.32, 1, 0.32],
  "colorMode": "gradient",
  "startColor": "#3650A9",
  "gradientStops": ["#42B7DB"],
  "endColor": "#B0FFDE",
  "pickupFlash": true,
  "pickupFlashStyle": "file:pickup/ring"
}
```

`version` is the JSON format version and takes the number `1`. The ten alternating radii give the geometric trail a five-point cross-section. Change `0.32` to `0.7` in all five places for shallower grooves. Save, reload and select **Moonwake** again to compare.

## Where your files go

Open `config/xp_orb_trails/` to find these directories. An effect ID uses the JSON filename without its extension. A sprite ID uses its **folder** name. For either ID, use 1–64 lowercase letters, digits, underscores or hyphens. Use `name` inside the JSON for the label players see.

| What you are making | Example path inside the config folder | How a profile selects it |
| --- | --- | --- |
| Animated orb body | `orb/sprites/moon_moth/sheet.png` | `"flightEffect": "sprite:orb/moon_moth"` |
| Drawn orb effect | `orb/effects/moon_orbit.json` | `"flightEffect": "file:orb/moon_orbit"` |
| Pickup sprite | `pickup/sprites/moon_pop/sheet.png` | `"pickupFlashStyle": "sprite:pickup/moon_pop"` |
| Drawn pickup effect | `pickup/effects/moon_burst.json` | `"pickupFlashStyle": "file:pickup/moon_burst"` |
| Trail stamps or ribbon | `trail/sprites/moon_band/sheet.png` | `"trailSprite": "sprite:trail/moon_band"` |
| Shareable look | `profiles/moonwake.json` | Select **Moonwake** under **File Profiles**. |

A partial file profile gets its omitted visual settings from `defaults.json`. Applying it preserves the player's master switch, render distance and trail limit. Profiles saved through **Saved Profiles** capture those settings too. The separate `config/xp_orb_trails.json` holds active settings and personal saves. For a shareable profile, write colors as `"#RRGGBB"` and include `"version": 1`. The active settings file and `defaults.json` store colors as integers.

## Bring your own sprite

The [sprite sheet examples](../examples/README.md) include a plain PNG strip, a timed pickup atlas and a ribbon with separate head and tail caps. Each comes with a profile you can load in-game.

For a still image, place a single transparent square PNG in `orb/sprites/moon_moth/`. A horizontal or vertical strip of square frames also works on its own and plays at 12 FPS. To set a different frame rate, use rectangular frames or read a grid, put a descriptor next to the PNG.

For example, draw six 16 × 16 frames in a 48 × 32 PNG, three across and two down. Save the image as `sheet.png` and the following as `orb/sprites/moon_moth/sheet.json`.

```json
{
  "image": "sheet.png",
  "name": "Moon Moth",
  "frameWidth": 16,
  "frameHeight": 16,
  "frames": 6,
  "fps": 10,
  "loop": true,
  "pivot": [8, 8]
}
```

Frames play left to right, then top to bottom. The pivot is the anchor in frame pixels. Change `flightEffect` in Moonwake to `"sprite:orb/moon_moth"` and reload and apply the profile. A replacement orb sheet loops while the orb exists. With **Show Vanilla Orb** enabled, the artwork appears alongside the original. Pickup sheets play once using their own frame timing.

For a **PixelFX Studio** export, put the exported atlas JSON and its PNG together in one sprite folder. Keep frame rotation disabled when exporting. Trimmed frames need both `sourceSize` and `spriteSourceSize` so the mod can put each frame back in position. The [bundled Collect Burst atlas](../common/src/main/resources/assets/xporbtrails/sprites/pickup/collect-burst/) is a working pickup example.

## Choose how a trail is drawn

| Desired result | Fields to change in your profile |
| --- | --- |
| Flat ribbon or round tube without a texture | Set `trailSprite` to `"none"`. Remove `crossSectionRadii` and set `crossSectionSides` to `2` for a camera-facing ribbon or `3`–`32` for a tube. |
| Custom geometric cross-section | Set `trailSprite` to `"none"` and give `crossSectionRadii` 3–32 values, each greater than zero and at most one. Each value sets the radius at one equally spaced angle. |
| A row of sprite stamps | Select a `sprite:trail/…` asset and set `trailSpriteMode` to `"stamps"`. `stampSpacing: 1` targets a stamp every 0.2 blocks. `stampScale` adjusts size independently. Set `fixedStamps` to `true` to leave each stamp at its placement point. |
| Afterimages of the orb | Set `trailSprite` to `"body"`. Each mark captures the orb body's appearance at placement. Particles emitted by the body continue moving independently. |
| A continuous textured ribbon | Select a `sprite:trail/…` asset and set `trailSpriteMode` to `"ribbon"`. Draw the tail on the left of each frame and the head on the right. |

`width` sets the overall geometric trail or ribbon width. `tailWidthScale`, `middleWidthScale` and `headWidthScale` shape it along the path. `lifetimeSeconds` controls how long the trail history lasts. `pickupFadeSeconds` controls how quickly the trail fades after pickup. The pickup flash has its own size and timing.

### Map a texture along the ribbon

For a seamless pattern, set `ribbonMapping` to `"tile"` and `ribbonRepeatLength` to `0.5` for one repeat per half block. Choose a different mode when the spacing or movement of the pattern matters.

| `ribbonMapping` | What happens on screen |
| --- | --- |
| `stretch` | Fits one copy between tail and head. The image stretches as the path grows. |
| `tile` | Repeats the image at the chosen length along the current path. |
| `distribute_per_segment` | Divides one image evenly among segments. A long segment receives the same share as a short one. |
| `repeat_per_segment` | Draws a whole copy on every segment. Spacing follows segment length. |
| `static` | Anchors texture coordinates to accumulated travel distance so surviving points keep their place in the pattern as the tail expires. |

For a shaped tip or tail, use the [Comet Pennant sprite descriptor](../common/src/main/resources/assets/xporbtrails/sprites/trail/comet-pennant/sheet.json) as a template. Its `ribbon` object reserves the first 10 pixels of each frame for the tail and the last 14 for the head. Its `middle` field sets the mapping between the caps and takes any of the five values above. Leave at least one middle pixel in every frame.

## Write a pickup effect

Save this as `pickup/effects/moon_burst.json`. In your Moonwake profile, change `pickupFlashStyle` to `"file:pickup/moon_burst"` and set `pickupFlashSeconds` to `0.45`. Reload, apply the profile and replay the preview. Two waves of diamonds should rise, spread out and fade.

```json
{
  "version": 1,
  "name": "Moon Burst",
  "emitter": {
    "space": "world",
    "bursts": [{"time": 0, "count": 10}, {"time": 0.18, "count": 5}],
    "direction": [0, 1, 0],
    "spread": 45,
    "lifetime": [0.35, 0.7],
    "speed": [0.8, 1.8],
    "size": [0.7, 1.2],
    "spin": [-120, 120],
    "gravity": 2.5,
    "drag": 0.6,
    "colors": ["#8CEFFF", "#C1A2FF"]
  },
  "layers": [{
    "shape": "diamond_xy",
    "easing": "smooth",
    "frames": [
      {"t": 0, "width": 0.6, "height": 0.6, "color": "head", "opacity": 1},
      {"t": 1, "width": 0.15, "height": 0.15, "opacity": 0}
    ]
  }]
}
```

### Animate a shape

Each layer needs 2–16 keyframes with increasing `t` values from `0` to `1`. For an emitter, that interval covers each particle's own lifetime. Omitted values carry forward from the previous frame. Use `x` and `y` to move a shape, `radius` and `angle` to orbit it, `rotation` to turn the shape itself and `opacity` to fade it. Angles are in degrees. Colors accept `"head"`, `"tail"` or a literal `"#RRGGBB"`. In Moon Burst, `"head"` picks up each particle's sampled palette color.

Use `rectangle` for a spark, `ring` or `arc` for a flash, and `diamond_xy`, `diamond_yz` or `diamond_zx` for diamonds in different planes. For a custom silhouette, use `shape: "radial"` with a layer-level `radii` array. Choose `linear` for steady interpolation, `smooth` to ease in and out, or `out` to slow toward the next keyframe. Layer `count` repeats a shape around the center and `alternateColor` changes every other copy. Try the [Twin Orbit layers](../common/src/main/resources/assets/xporbtrails/effects/twin-orbit.json) to see how two groups can orbit in opposite directions, or edit the `segments` of the [built-in Ring](../common/src/main/resources/assets/xporbtrails/effects/pickup/ring.json) for a more angular flash.

To make a looping orb animation with keyframes alone, copy `orb/effects/twin-orbit.json` to `orb/effects/moon_orbit.json` and change its `name`. In the second layer, change the middle frame’s `radius` from `1.05` to `2.0`. Select the new name under **Orb Body → Orb Artwork** after reloading. The five head-colored pieces should now swing wider than the six tail-colored pieces. To include it in a shared profile, use `"flightEffect": "file:orb/moon_orbit"`.

### Tune the emitter

| Field | What to try |
| --- | --- |
| `rate`, `distance`, `bursts` | Emit per second, every N blocks traveled, or at a list of times in seconds. Enable at least one. For a moving orb, `distance` leaves an even trail of particles when its speed changes. |
| `speed`, `size`, `lifetime` | Use a number or `[min, max]`. Each particle samples its own launch speed in blocks/second, size multiplier and lifetime in seconds. |
| `direction`, `spread` | `[0, 1, 0]` points up. `spread` is the cone's half-angle in degrees. Try `10` for a narrow jet or `80` for a broad spray. |
| `rotation`, `spin`, `colors` | Randomize the starting angle, turning speed in degrees/second and color. Reference `"head"` in the layer to use the palette. |
| `gravity`, `drag`, `space` | Positive gravity pulls downward. Drag slows particles. In `world` space, particles start at their emission point and move independently of the orb. In `local` space, their origins move with the orb. |

To emit along the flight path, copy the effect into `orb/effects/`, add `"distance": 0.25` to its emitter and select it through `flightEffect`. Leave `showVanillaOrb` enabled if you want the orb visible between emissions. The [complete Starfall example](../examples/starfall/config/xp_orb_trails/) shows an orb emitter, a pickup emitter and a profile that connects them.

## Test and share your look

- **A new profile is missing.** Reload files and check `logs/latest.log` in your instance. File profiles require valid JSON, exact field names and `"version": 1`. Duplicate keys, unknown fields and colors missing the leading `#` in a profile can cause the file to be skipped.
- **An edit appears to do nothing.** Apply the profile again after reloading. An invalid edit to an already loaded effect or sprite can leave its last working version visible. Check the reload result and log.
- **An orb shows its vanilla texture.** Check the role and spelling of the selected ID. With a missing replacement or an orb outside the trail distance or count limit, the game can show the vanilla body. Choosing no artwork and turning off **Show Vanilla Orb** hides it deliberately.
- **A sheet fails to load or animates incorrectly.** Check the PNG dimensions, frame grid, filename and pivot. A sheet supports up to 1,024 frames, 4,096 pixels per side and 4,194,304 pixels total. Each sprite folder accepts one JSON descriptor.
- **A complex particle effect loses detail.** Start with one layer and a small burst. An effect supports up to eight layers with a combined geometry budget of 96. Rings and radial outlines consume several pieces per copy. Simpler shapes allow more particles to remain visible together.
- **To share your pack**, include the profile and each custom asset it references. Keep the `profiles/`, `orb/`, `pickup/` and `trail/` paths intact so players can copy the contents into their own `config/xp_orb_trails/`, reload and select the profile. Try the pack in a fresh instance to catch missing references.
