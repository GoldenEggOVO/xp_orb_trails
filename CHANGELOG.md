# Changelog

## 2.1.0 — Minecraft 26.1.x

- Install a creator’s ZIP from **Profiles → Open Packs Folder** and reload files. Each pack can share its profiles and artwork together.
- Try the optional **Effect Collection** with 24 profiles, including Butterfly Homecoming, River Procession and Surface Prints. Create animated bodies, variable trail geometry, terrain-aware pickup particles and light or heat effects.
- Built-in profiles and artwork now have English and Simplified Chinese names. Find the ZIP installation guide, versioned examples and bilingual player wiki from the README.
- Keep active effects continuous during server clock corrections. Fix stale pickup flashes after switching styles, missing default artwork in custom packs, menu scrollbar dragging and cleanup of scene textures.

## 2.0.0 — Minecraft 26.1.x

- Customize orb bodies, trails and pickup effects independently. Load editable JSON animations, PNG sprites and file profiles from the in-game menu.
- Add textured ribbons with five mapping modes, fixed stamps, orb afterimages and custom cross-sections. Starfall demonstrates particle emission, physics and a five-point trail.
- Organize settings into General, Trail, Pickup, Orb Body and Profiles. The preview can replay the full flight and pickup.
- Keep untracked experience orbs visible when replacement artwork is selected. Recover cleanly when an edited gradient contains an invalid entry.
- Read settings from `config/xp_orb_trails.json` and assets from `config/xp_orb_trails/`. Version 2.0 starts with fresh defaults when only older configuration paths exist.

## 1.4.1 — Minecraft 26.1.x

- Draw trails after final world composition so shader packs do not overwrite them.
- Share the rendering hook across all loaders while preserving depth occlusion.

## 1.4.0 — Minecraft 26.1.x

- Add Fabric, Forge, and NeoForge support for Minecraft 26.1, 26.1.1, and 26.1.2.
- Adapt screen navigation, render buffers, and depth testing to the 26.1 rendering API.
- Preserve trail appearance, settings, saved colors, and profiles from the 26.2/26.3 implementation.
- Reduce repeated mesh and smoothing calculations; trim excess trail history in one operation.
- Add regression checks for trail history limits, point timing, and smooth movement.

Earlier changes are documented in the [Minecraft 26.2 changelog](https://github.com/GoldenEggOVO/xp_orb_trails/blob/26.2/CHANGELOG.md).
