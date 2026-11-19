# XP Orb Trails

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x-62B47A)](#requirements)
[![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-DBD0B4)](#requirements)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Downloads](https://github.com/GoldenEggOVO/xp_orb_trails/releases) · [Changelog](CHANGELOG.md) · [Report an issue](https://github.com/GoldenEggOVO/xp_orb_trails/issues) · [Contributing](CONTRIBUTING.md)

XP Orb Trails is a client-side mod for Minecraft. Customize the moving orb, the trail it leaves behind and the flash when you collect it.

## Features

- Color a trail with a solid color, a multi-stop gradient or an animated rainbow.
- Draw a camera-facing ribbon, a tube, a custom cross-section or a line of sprite stamps. Adjust width, lifetime and render distance.
- Use PNG artwork or editable JSON animations for the orb body and pickup. Particle emitters support bursts, motion and per-particle variation.
- Switch between file profiles in-game and preview changes. English and Simplified Chinese follow the Minecraft language setting.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.1 / 26.1.1 / 26.1.2 |
| Java | 25 or newer |

Choose one loader:

| Loader | Tested version | Additional mods |
| --- | --- | --- |
| Fabric | Loader 0.19.5 | Fabric API 0.155.3+26.1.2; optional Mod Menu 18.0.2 |
| Forge | 64.1.5 (Minecraft 26.1.2) | None |
| NeoForge | 26.1.2.122 (Minecraft 26.1.2) | None |

## Installation

Choose the build for your loader and Minecraft version. The 2.0.0 filename for this branch is `xp_orb_trails-<loader>-2.0.0+26.1.2.jar`. Place one matching JAR in your client's `mods/` directory. On Fabric, also install Fabric API. Installation is client-side.

## Shader compatibility

The mod draws depth-tested trails after the world scene is composed. Shader packs are optional. When reporting a rendering issue, include your Minecraft version, loader, shader mod and pack.

## Configuration

Open the settings screen through Mod Menu's Config button (Fabric), the loader's
Mods configuration button (Forge/NeoForge), or bind
**Open Trail Settings** in Minecraft's Controls screen. Changes apply
immediately and are saved to `config/xp_orb_trails.json` when the screen closes.
The menu opens on **General**, followed by **Trail**, **Pickup**, **Orb Body**, and **Profiles**. **Orb Body** controls the vanilla orb and its artwork.
**Trail** groups the trail type, artwork, width or stamp size, timing, and motion.
**Pickup** controls the effect played when the orb is collected. **Profiles**
applies or saves complete looks. **General** holds the master switch, overall
strength and glow, shared colors, and performance limits. Wide windows show a
live preview beside the settings.
Each editable settings page has its own reset button, and hovering over a control
shows a short description.

File profiles preserve the enabled state, render range, and trail limit.
Profiles saved in-game restore the complete configuration. In **General → Shared Colors → Saved Colors**, click a pair to apply it or use **Manage** to rename,
overwrite, or delete it. You can keep up to 12 pairs. **Replay Animation** starts
with the orb moving and plays through pickup and fading.
The preview shows the current artwork or the geometric cross-section. Pickup
sprites use their own frame timing. Check the trail's full 3D appearance in-game.

### File profiles and assets

The mod creates these directories under `config/xp_orb_trails/`:

| Directory | Contents |
| --- | --- |
| `orb/sprites/`, `orb/effects/` | Orb body sprite sheets and keyframe animations |
| `pickup/sprites/`, `pickup/effects/` | Pickup sprite sheets and keyframe animations |
| `trail/sprites/` | Stamps and ribbon textures |
| `profiles/` | Standard, Animated Rainbow, Comet Pennant and Starfall, plus your own file profiles |
| `defaults.json` | Baseline settings used by new configurations, page resets and partial file profiles |

Open **Profiles → Open Config Folder** to browse these files. Version 2.0 reads the underscored paths above. Older settings and artwork stay in their original locations.

Edit a file and select **Profiles → File Profiles → Reload Files**. Click the
profile name to apply its new settings. Add a `"name"` to a bundled profile to
override its translated name. Copy a profile into `profiles/` to make another
look. Use role-qualified asset IDs such as `"sprite:orb/ice-comet"` in profiles.

Each file profile uses `"version": 1`. Omitted visual settings start at their defaults.
`gradientStops` accepts up to eight intermediate `"#RRGGBB"` colors spaced
evenly between `startColor` and `endColor`. Set `flightPulse` to true and
use `pulseSpeed` for cycles per second (0.1–4). Pickup flashes such as Soft, Ring and Starfall Bloom are editable JSON files in `pickup/effects/`.
For example, `"pickupFlashStyle": "file:pickup/ring"` selects `ring.json`.
For instance, `layers[0].segments` in `ring.json` sets the number of edges in
the ring, and its frame widths set the size at the beginning and end of the
animation. Add `"name": "My Ring"` to give your copy a menu label.
Copy the file as `my-ring.json` to give it the ID `file:pickup/my-ring`. Reload files after editing,
then select it under **Pickup**. Editing `defaults.json` changes what the page
reset buttons restore. Reloading defaults preserves your current settings.

### Create an animation

Copy [twin-orbit.json](common/src/main/resources/assets/xporbtrails/effects/twin-orbit.json)
from your instance's `config/xp_orb_trails/orb/effects/` folder to a new file such as
`comet.json`. Give it a display name. The two layers in Twin Orbit use `count` to
set how many pieces orbit in each direction. Each layer can have its own shape,
color and timing. The same format drives the supplied Soft and Star pickup
flashes in `pickup/effects/`.

Each layer has 2–16 keyframes, starting at `t: 0` and ending at `t: 1`.
Animate position (`x`, `y`), orbit (`radius`, `angle`), size (`width`, `height`),
`rotation`, `opacity` and `color`. Angles are in degrees. Colors accept
`head`, `tail` or `#RRGGBB`. Use `alternateColor` for every other copy.
Omitted values carry forward. The layer's `easing` controls interpolation
(`linear`, `smooth` or `out`).

To draw your own silhouette, select `"shape": "radial"`. Its `radii` array
specifies 3–32 lengths between 0 and 1, evenly spaced counterclockwise from
the top. Alternating long and short radii produces a star. Each effect supports
up to eight layers and a budget of 96 pieces, including each radial edge per copy.
This six-point star changes color and size as it turns:

```json
{
  "version": 1,
  "name": "Prism Star",
  "layers": [{
    "shape": "radial",
    "radii": [1, 0.3, 1, 0.3, 1, 0.3, 1, 0.3, 1, 0.3, 1, 0.3],
    "easing": "smooth",
    "frames": [
      {"t": 0,    "color": "head",    "width": 0.5, "height": 0.5, "rotation": 0,   "opacity": 0.15},
      {"t": 0.35, "color": "#69F7FF", "width": 2.6, "height": 2.6, "rotation": 45,  "opacity": 1},
      {"t": 0.7,  "color": "#CE89FF", "width": 1.7, "height": 1.7, "rotation": 120, "opacity": 0.85},
      {"t": 1,    "color": "head",    "width": 0.5, "height": 0.5, "rotation": 180, "opacity": 0.15}
    ]
  }]
}
```

The other layer shapes are `rectangle`, `diamond_xy`, `diamond_yz`,
`diamond_zx`, `ring` and `arc`.

Reload files under **Profiles → File Profiles**. Select your animation in
**Orb Body → Orb Artwork** to use it around the orb, or put a copy in `pickup/effects/` and select it under **Pickup** to play it once. To share a complete look, its style JSON can
reference `"flightEffect": "file:orb/comet"` and `"pickupFlashStyle": "file:pickup/comet"`.

### Emit particles from an orb or pickup

Add an optional `emitter` object alongside `layers` in an effect JSON. Each particle
plays those layers from `t: 0` to `t: 1` over its own lifetime. This complete
example launches two waves of diamond-shaped particles:

```json
{
  "version": 1, "name": "Double Burst",
  "emitter": {
    "bursts": [{"time": 0, "count": 12}, {"time": 0.25, "count": 6}],
    "lifetime": [0.5, 1.0], "speed": [0.8, 2.0], "size": [0.7, 1.2],
    "direction": [0, 1, 0], "spread": 65, "gravity": 3.0, "drag": 0.8
  },
  "layers": [{"shape": "diamond_xy", "frames": [
    {"t": 0, "width": 0.6, "height": 0.6, "color": "head", "opacity": 1},
    {"t": 1, "width": 0.2, "height": 0.2, "opacity": 0}
  ]}]
}
```

| Field | Meaning |
| --- | --- |
| `rate`, `distance`, `bursts` | Emit per second, every N blocks traveled, or at listed times in seconds after the source starts. Enable one or combine them. Each burst specifies its own `time` and `count`. |
| `lifetime`, `speed`, `size` | Seconds alive, initial speed in blocks/second, and a size multiplier. Use a number for a fixed value or `[min, max]` to sample once per particle. |
| `rotation`, `spin`, `colors` | Initial angle in degrees, angular speed in degrees/second, and an optional palette of `#RRGGBB` colors. Rotation and spin accept ranges too. The sampled color is available as `head` in the layers. |
| `direction`, `spread` | Launch along an XYZ vector, with a random direction inside a cone whose half-angle is `spread` degrees. Positive Y points up. |
| `gravity`, `drag` | Positive gravity pulls down in blocks/second². Drag slows motion continuously, in inverse seconds. |
| `space` | `world` leaves particles where they were emitted as the orb moves. `local` moves their origin with the orb. |

At pickup the source stays at the collection point; particles already launched can finish
after the flash window. For a moving trail of particles, put the effect in `orb/effects/`
and set `distance`. Select **Starfall** in **Profiles** to try both emitters with a five-point geometric trail.
Its [three editable files](examples/starfall/config/xp_orb_trails) are installed with the mod.

### Custom trail cross-sections

For a geometric trail, add `crossSectionRadii` to a file profile. Each of the
3–32 values is a fraction of the trail radius, above 0 and up to 1. Values are
spaced evenly around the path. Five alternating pairs of `1` and `0.32` give the
tube five points with recessed valleys:

```json
{"version":1, "name":"Five-point Star", "trailSprite":"none",
 "crossSectionRadii":[1,0.32,1,0.32,1,0.32,1,0.32,1,0.32]}
```

Use the Trail page to switch back to the 2–32 side slider. Two sides produce the
camera-facing ribbon. PNG ribbons use their own texture layout.

### Import pixel art and sprite sheets

Create `config/xp_orb_trails/orb/sprites/ice-comet/` in your instance. Copy both the
PNG and the JSON from a [PixelFX Studio export](https://pixelfx.io/effects/pickups/collect-burst/)
into that folder. The mod uses the frame rectangles, timing, and pivot from
the atlas. A single PNG also works for a square image or a horizontal or
vertical strip of square frames. Strips play at 12 FPS by default.

For a grid, put a JSON file next to the PNG. This example plays the first six
16×16 cells from left to right, then top to bottom:

```json
{"image":"sheet.png","name":"Ice Comet","frameWidth":16,"frameHeight":16,"frames":6,"fps":12,"loop":true,"pivot":[8,8]}
```

Click **Profiles → File Profiles → Reload Files**. Place the artwork in the directory for its intended use:

- **Orb Body** displays it on the moving or resting orb. Turn off **Show Vanilla Orb**
  to replace the original graphic. A replacement sheet loops for as long as the orb is present.
- **Pickup** plays the sheet once, at its own frame timing.
- **Trail** can place stamps along the path, wrap a ribbon around its curves,
  or leave an afterimage of the orb body. Stamps can stay where the orb placed
  them and fade individually. Afterimages freeze the body animation at each
  placement. For ribbons, draw the tail on the left and the head on the right.
  A seamless texture can repeat along the curve at a fixed length in blocks.

A file style uses `flightEffect`, `pickupFlashStyle`, and `trailSprite` to select
artwork by role and folder name, such as `"sprite:orb/ice-comet"`. Set `showVanillaOrb` to
`false` and `orbScale` to resize the replacement. Set `trailSpriteMode` to
`"ribbon"` or `"stamps"`. For stamps, `stampSpacing: 1` targets 0.2 blocks between
copies, and `stampScale: 1` uses their default size.

| Look | Fields in the style file |
| --- | --- |
| Fixed footprints | `"trailSpriteMode": "stamps"`, `"fixedStamps": true` |
| Afterimages of the selected orb body | `"trailSprite": "body"` |
| Repeating ribbon | `"trailSpriteMode": "ribbon"`, `"ribbonMapping": "tile"`, `"ribbonRepeatLength": 0.5` |

Continuous ribbons accept five texture mappings in `ribbonMapping`:

| Value | What happens as the path changes |
| --- | --- |
| `stretch` | The whole image spans the current length of the trail. |
| `tile` | The image repeats every `ribbonRepeatLength` blocks along the current path. |
| `distribute_per_segment` | Each segment takes an equal share of one image, even if segment lengths differ. |
| `repeat_per_segment` | Each segment displays one whole image. |
| `static` | Each point keeps its texture coordinate as the head grows or the tail expires. |

For a ribbon with a distinct tip and head, add a `ribbon` object to the
**sprite sheet descriptor**. In the example below, the first 10 pixels of
each frame form the tail cap and the last 14 pixels form the head cap. On
short paths the caps shrink together. The `middle` field accepts the same
five values as `ribbonMapping` and controls only the part between the caps.

```json
{
  "image": "sheet.png",
  "frameWidth": 56,
  "frameHeight": 14,
  "ribbon": {"tailPixels": 10, "headPixels": 14, "middle": "tile"}
}
```

If the configuration file is unreadable, the mod attempts to preserve it as
`xp_orb_trails.json.broken` (or a numbered variant), restores defaults, and
continues loading.
Non-finite numeric values in settings and saved profiles are repaired before
saving, while other settings and profile names are retained.

## Building from source

Use Java 25 and the included Gradle wrapper:

```sh
./gradlew build
```

On Windows, run `./gradlew.bat build` instead. The build runs the configuration
tests and checks all three loader JARs for settings UI, mixins, translations,
and platform metadata. Install the matching JAR from `<loader>/build/libs/`;
the `-sources.jar` file is for source inspection.

To build one loader, run `./gradlew -Ploaders=fabric build` (or `forge` or
`neoforge`). Shared code is in `common/`; loader entry points and build settings
are in `fabric/`, `forge/`, and `neoforge/`.

## Minecraft versions and branches

The project provides Fabric, Forge and NeoForge builds on each Minecraft branch.

| Minecraft | Source branch | Game version shown in the JAR filename |
| --- | --- | --- |
| 26.3 | [26.3](https://github.com/GoldenEggOVO/xp_orb_trails/tree/26.3) | `+26.3` |
| 26.2 | [26.2](https://github.com/GoldenEggOVO/xp_orb_trails/tree/26.2) | `+26.2` |
| 26.1, 26.1.1, 26.1.2 | [26.1](https://github.com/GoldenEggOVO/xp_orb_trails/tree/26.1) | `+26.1.2` |

## License

MIT License. See [LICENSE](LICENSE) for the copyright notice and terms.

This is an independent implementation and contains no Shine source code or
assets.
