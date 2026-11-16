# Changelog

## 1.11.0+mc26.3
- Players screen (person button in the gallery): how many players you have met in total, how many are on
  your shots, and a ranking of who got on your screenshots most / least often. Search, sort, click a player
  to see their shots.
- Viewer info panel: long lists (players, mobs, items, blocks, tags) are collapsed to two rows with an
  "N more" chip that expands them; section titles show how many things there are.
- Xaero: screenshots no longer put waypoints on the map by themselves. The viewer has a button that adds a
  normal (saved) Xaero waypoint where the shot was taken.
- The PepeLand lobby is excluded from new-player detection by default (can be turned back on in Settings).
- Removed the movement / inventory log.

## 1.10.3+mc26.3
- Auto shots are taken on round wall-clock times (every 10 min: 19:10, 19:20...; every 5 min: 19:05, 19:10...).
  Boundaries missed during a pause/menu/unfocus collapse into one shot when you're back.

## 1.10.2+mc26.3
- Auto shots now run on real time: every N real minutes. If a menu is open, the game is paused or the window
  is unfocused when it's due, the shot is taken right as you're back in the game.

## 1.10.1+mc26.3
- Fix: highlighting found nothing (or only a few stray lines) on shots taken with chunk borders (F3+G) or
  other debug overlays on: their pass clears the depth buffer, so the mask is now read just before it.
- Viewer: chips of things not visible on the shot (e.g. a met player behind you) are dimmed and explain
  why clicking them doesn't highlight anything.

## 1.10.0+mc26.3
- New-player detection can be switched off per place: in Settings (while in a world) ignore the current
  sub-world (e.g. a server lobby) or the whole server / world. Nothing is shot or remembered there.

## 1.9.2+mc26.3
- Fix: crash (ConcurrentModificationException) when opening the viewer with a named-entity chip; icon cache no longer
  uses computeIfAbsent with a recursive lookup.

## 1.9.1+mc26.3
- Fix: the gallery stayed on "Loading…" forever when opened from the main menu (item names need a loaded
  world in 26.3); a failed scan now shows an error instead of loading forever.
- Settings: descriptions wrap onto more lines instead of being cut off; segmented choices no longer cover them.
- Settings: sliders follow the mouse on the track (clicking the title no longer changes the value), all tracks
  line up, discrete sliders show notches.

## 1.9.0+mc26.3
- Chat on screenshots is remembered (the messages visible at that moment) and searchable: plain search or
  `chat:` / `чат:`; shown in the viewer's info panel ("Chat").
- Editor: stickers — any item (blocks as small 3D cubes), mob (spawn egg) or player head, from this shot or from
  the whole game; resource packs apply.
- Editor: caption from a template ({date} {time} {x} {y} {z} {world} {dimension} {biome} {player}) in any corner.
- Editor: filters — brightness, contrast, saturation, warmth, vignette, black and white, sepia (removable like
  every other edit).
- Xaero's Minimap / World Map: screenshots of the current world appear as waypoints (temporary, not written to
  Xaero's waypoint files; favorites, advancements and meetings in their own colors); "Show on Xaero's map" (M) in
  the viewer opens the world map at the shot.

## 1.8.0+mc26.3
- Inventory log writes items in full, in the game's own item format: every component, including the contents of
  shulker boxes and bundles (nested), enchantments, lore, custom data, trims, potions; plus durability left.
- The ender chest is logged while it is open (on opening and on every change).

## 1.7.0+mc26.3
- Inventory log next to the movement log (`<session>.inventory.jsonl`): full snapshots at the start, every 5 minutes
  and on death / respawn / dimension change / teleport / leave, plus every change (slots with item, count,
  durability, name, enchantments, and the net gain or loss per item) with time and position.
- Movement log: new `held` column (item in the main hand).

## 1.6.0+mc26.3
- Screenshot editor (E or the pencil in the viewer): pen, highlighter, line, arrow, rectangle, ellipse, text
  (with optional background), numbered markers, blur, pixelate, spotlight, crop, move and eraser; colors, sizes,
  undo/redo, zoom and pan. Changes are written into the PNG right away, but the original is kept, so any edit can
  be erased, moved or undone later (also in another session). Text on shots is searchable.
- Share: upload a shot (catbox.moe, Litterbox, 0x0.st or a custom ShareX-style service) and copy the link; the
  link is reused while the shot is unchanged. "Upload last screenshot" key (unbound by default).
- After F2: optionally upload every shot, and copy the image, the file or the link to the clipboard.
- Edited shots are skipped by the archiver.
- Movement log (on by default, separate from screenshots): position, dimension, world/server, speed, way of moving
  (walk/sprint/swim/elytra/ride...), health, biome, weather and events (join, leave, death, teleport, dimension
  change, pause) with exact timestamps, one CSV per session in `movement-log/`.

## 1.5.0+mc26.3
- Copy a screenshot as an image to the system clipboard: copy button / Ctrl+C in the viewer, Ctrl+C on the
  hovered tile in the gallery, and a "Copy last screenshot" key (unbound by default). Uses wl-copy / xclip on
  Linux, PowerShell on Windows, osascript on macOS.
- Search: /regex/ is advertised in the search hint, works while typing (closing slash optional), turns the
  box red when invalid, doesn't trigger autocomplete; ids match with and without "minecraft:".

## 1.4.0+mc26.3
- Mobs renamed with a name tag get their own chip in the viewer ("Mobs" section) and can be highlighted
  one by one; new `named` metadata field and `n:` mask key (new shots).
- Viewer side panel: click only highlights on this shot; Shift+click searches all shots (RMB on chips no
  longer searches; on the image RMB or Shift+click still do).
- Fixed dropped items (e.g. arrows) being outlined together with the grass around them (new shots).

## 1.3.1+mc26.3
- Viewer: hovering the image or a chip no longer previews outlines or shows the pick card; things are
  highlighted only on click.

## 1.3.0+mc26.3
- Player names keep the server's TAB styling (colors, gradients, prefixes, badges) in the viewer and tooltips;
  missing custom glyphs fall back gracefully. Visible name text is searchable.

## 1.2.0+mc26.3
- "First meeting" shot: one screenshot when you see new players for the first time (well framed, batched,
  cooldown); met players remembered globally across worlds, servers and launcher instances.
- Gallery shows screenshots from other Prism Launcher instances ("Other instances"), deduplicated.
- Leaves count as see-through: blocks and mobs behind leaves are listed and highlightable.
- New-player badge, filter and search (type:new_player, met:<nick>).

## 1.1.0+mc26.3 (fixes)
- Fixed black screenshots (GL read framebuffer left without color after the depth readback).
- Block counts on block chips; blocks behind glass/ice/water; blocks listed right after the shot.

## 1.1.0+mc26.3
- Highlight blocks, mobs, players, items and signs on a shot with real silhouette outlines (hover / click,
  baked into the PNG, removable); pick objects right on the image; right click searches.
- Info panel: separate Players / Entities / Items / Blocks sections with real icons (resource-pack aware).
- Items on the ground and in item frames as a category; local player listed by nickname (no "Me").
- Invisible auto shots without notifications; auto shots hidden in the gallery by default.
- Freecam / Legacy Freecam detection with a "Freecam" label.
- Preview-only notification with configurable size and display time.
- Separate resolution for normal and auto shots, archiving of old shots.
- Square gallery button in the pause menu.

## 1.0.0+mc26.3
- First version.
