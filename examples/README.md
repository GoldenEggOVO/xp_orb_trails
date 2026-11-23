# Install an XP Orb Trails effect pack

[简体中文](README.zh-CN.md)

| Download | What’s inside |
| --- | --- |
| [Effect Collection for 2.1.0 (.zip)](2.1.0example/2.1.0example.zip?raw=1) | 24 profiles with artwork for orb bodies, trails and pickups. [See each effect and how to trigger it](2.1.0example/). |
| [Sprite sheet examples from 2.0.0 (.zip)](2.0.0example/2.0.0example.zip?raw=1) | Koi Stream, Lantern Bloom, Brass Clockwork and Ion Drive. [Browse the artwork, previews and Starfall source](2.0.0example/). |

Each version folder contains a ZIP with the same name. Browse its editable profiles and artwork in [the 2.0.0 pack](2.0.0example/pack/) or [the 2.1.0 pack](2.1.0example/pack/).

## Use a ZIP in 2.1.0

1. Download a pack using one of the links above.
2. In the mod settings, open **Profiles → Open Packs Folder**. Put the ZIP, still compressed, into the folder that opens. Its path is `config/xp_orb_trails/packs/` inside your Minecraft instance.
3. Open **File Profiles → Reload Files** on the same page. Select the profile you want. The menu shows the ZIP filename next to each profile from that pack.
4. Collect a few experience orbs to try it. Some effects need a second orb, moving water or a powered block. The Effect Collection guide lists the conditions for each one.

To update a pack, replace the ZIP with the new file under the same name, reload files and reapply the profile. To uninstall it, choose another profile, remove the ZIP and reload files.

If you already installed an example under its previous filename, remove the old copy when installing the renamed ZIP. Reload files and select your profile again.

**Playing on 2.0.0?** Download the sprite sheet examples, extract the ZIP, then merge its `profiles/`, `orb/`, `trail/` and `pickup/` folders into `config/xp_orb_trails/`. Reload files and select a profile in the settings.

## Share a pack

Place `profiles/` and the role folders your profiles use at the root of a ZIP. For example, `profiles/river_lights.json` can reference `orb/sprites/koi/sheet.png` as `sprite:orb/koi`. Pack your JSON and artwork together so players can install them in one step. [Player wiki](https://goldeneggovo.github.io/xp_orb_trails/wiki/#test-and-share-your-look)
