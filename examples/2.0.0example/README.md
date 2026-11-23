# Sprite sheet examples for XP Orb Trails 2.0.0

[Download the four sprite profiles](2.0.0example.zip?raw=1) or browse their editable files below.

**On 2.0.0**, extract the ZIP into your instance’s `config/xp_orb_trails/` folder, merging the role folders with the ones already there. Open **Profiles → File Profiles → Reload Files** and choose a profile. **On 2.1.0**, you can install the same ZIP directly using the [pack installation guide](../README.md).

The animated swatches below show the **orb**, **trail** and **pickup** artwork from left to right.

| Profile | Sprite preview | What to try |
| --- | --- | --- |
| [Koi Stream](pack/profiles/koi_stream.json) | ![A white and orange koi, a turquoise current, and a crown of water droplets](previews/koi_stream.gif) | The koi is a horizontal PNG strip. The ribbon tiles a wave along the flight path. Change `ribbonRepeatLength` to put its crests closer together. |
| [Lantern Bloom](pack/profiles/lantern_bloom.json) | ![A glowing paper lantern, drifting pink petals, and an unfolding five-petal bloom](previews/lantern_bloom.gif) | A red lantern leaves petal stamps fixed in the world. Increase `stampSpacing` to scatter them farther apart. |
| [Brass Clockwork](pack/profiles/brass_clockwork.json) | ![A brass gear, turning wind-up keys, and an uncoiling spring](previews/brass_clockwork.gif) | The orb uses a vertical strip. The pickup atlas gives each frame its own `duration` in milliseconds. Lengthen the middle frames to slow the spring as it opens. |
| [Ion Drive](pack/profiles/ion_drive.json) | ![A small spacecraft, a pulsing cyan rail, and an electric hexagonal flash](previews/ion_drive.gif) | The ribbon descriptor reserves 7 pixels for the tail and 9 for the head. Only its middle section tiles along the path. |

To draw a replacement, edit a `sheet.png` in the role’s `sprites/` folder. Most sheets have a neighboring `sheet.json` that defines frame size, order and timing. Koi Stream’s orb demonstrates automatic square-frame detection and plays at 12 FPS using the PNG alone. After editing, reload files and select the profile again.

The [player guide](https://goldeneggovo.github.io/xp_orb_trails/wiki/) explains sprite sheets, texture mapping and sharing profiles.
