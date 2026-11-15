package dev.vadyanik.smartshots.storage;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.mixin.BiomeManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Figures out which world / server / sub-world the player is in right now.
 *
 * <p>Singleplayer: {@code singleplayer/<level folder>/<dimension>}. The level folder id is unique
 * on disk, so two worlds with the same display name never collide.
 *
 * <p>Multiplayer: {@code multiplayer/<server address>/<sub-world>}. A server (or a proxy such as
 * BungeeCord / Velocity) can host many worlds that all claim to be "minecraft:overworld". To tell
 * them apart we fingerprint the sub-world with the dimension id <i>and</i> the hashed world seed
 * the server sends on join/respawn. Different backend worlds almost always have different seeds,
 * so a lobby and a survival world get separate folders even though their dimension names match.
 * The mapping fingerprint -> folder is kept in {@code .smartshots-world.json}, so folders stay
 * stable and can be renamed from the gallery.
 */
public final class WorldContext {
	private WorldContext() {
	}

	public static Location resolve(Minecraft mc) {
		Path root = ScreenshotStore.root();
		ClientLevel level = mc.level;
		if (level == null) {
			return new Location(Category.OTHER, root, "", root, "");
		}
		Identifier dim = level.dimension().identifier();
		IntegratedServer sp = mc.getSingleplayerServer();
		ServerData server = mc.getCurrentServer();

		Category category;
		String key;
		String displayName;
		if (sp != null) {
			category = Category.SINGLEPLAYER;
			Path worldPath = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			key = worldPath.getFileName() != null ? worldPath.getFileName().toString() : sp.getWorldData().getLevelName();
			displayName = sp.getWorldData().getLevelName();
		} else if (server != null) {
			if (server.isRealm()) {
				category = Category.REALMS;
				key = server.name;
			} else if (server.isLan()) {
				category = Category.MULTIPLAYER;
				key = "LAN - " + server.name;
			} else {
				category = Category.MULTIPLAYER;
				key = normalizeAddress(server.ip);
			}
			displayName = server.name;
		} else {
			category = Category.OTHER;
			key = "unknown";
			displayName = "Unknown";
		}

		Path instanceDir = root.resolve(category.folder).resolve(FileNames.sanitize(key));
		InstanceMeta meta = ScreenshotStore.instanceMeta(instanceDir);
		boolean dirty = false;
		if (meta.key == null) {
			meta.key = key;
			meta.category = category.folder;
			dirty = true;
		}
		if (displayName != null && !displayName.equals(meta.displayName)) {
			meta.displayName = displayName;
			dirty = true;
		}
		long now = System.currentTimeMillis();
		if (now - meta.lastSeen > 60_000) {
			meta.lastSeen = now;
			dirty = true;
		}

		String fingerprint;
		if (category == Category.SINGLEPLAYER) {
			fingerprint = SmartShotsConfig.get().separateDimensions ? dim.toString() : "all";
		} else {
			long seed = ((BiomeManagerAccessor) level.getBiomeManager()).smartshots$getBiomeZoomSeed();
			fingerprint = dim + "@" + Long.toHexString(seed);
		}

		InstanceMeta.SubWorld sub = meta.worlds.get(fingerprint);
		if (sub == null) {
			sub = new InstanceMeta.SubWorld();
			sub.dimension = dim.toString();
			sub.firstSeen = now;
			String base;
			if (fingerprint.equals("all")) {
				base = "world";
			} else {
				base = FileNames.sanitize(dim.getNamespace().equals("minecraft") ? dim.getPath() : dim.getNamespace() + "_" + dim.getPath());
				if (category != Category.SINGLEPLAYER) {
					String hint = sidebarTitle(level);
					if (hint != null) {
						sub.displayName = hint;
						base = FileNames.sanitize(hint.toLowerCase(Locale.ROOT)) + " - " + base;
					}
				}
			}
			Set<String> used = new HashSet<>();
			meta.worlds.values().forEach(w -> used.add(w.folder.toLowerCase(Locale.ROOT)));
			String folder = base;
			int n = 2;
			while (used.contains(folder.toLowerCase(Locale.ROOT))) {
				folder = base + "-" + n++;
			}
			sub.folder = folder;
			meta.worlds.put(fingerprint, sub);
			dirty = true;
		}
		if (dirty) {
			ScreenshotStore.saveInstanceMeta(instanceDir);
		}
		return new Location(category, instanceDir, meta.displayName, instanceDir.resolve(sub.folder), subWorldName(sub));
	}

	public static String subWorldName(InstanceMeta.SubWorld sub) {
		if (sub.displayName != null && !sub.displayName.isBlank()) {
			return sub.displayName;
		}
		return dimensionName(sub.dimension);
	}

	public static String dimensionName(String dimension) {
		if (dimension == null) {
			return "?";
		}
		String key = switch (dimension) {
			case "minecraft:overworld" -> "smartshots.dimension.overworld";
			case "minecraft:the_nether" -> "smartshots.dimension.the_nether";
			case "minecraft:the_end" -> "smartshots.dimension.the_end";
			default -> null;
		};
		if (key != null) {
			return net.minecraft.network.chat.Component.translatable(key).getString();
		}
		int i = dimension.indexOf(':');
		return i >= 0 ? dimension.substring(i + 1) : dimension;
	}

	private static String sidebarTitle(ClientLevel level) {
		try {
			Objective obj = level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
			if (obj == null) {
				return null;
			}
			String s = obj.getDisplayName().getString().replaceAll("§.", "").strip();
			return s.isEmpty() || s.length() > 40 ? null : s;
		} catch (Exception e) {
			return null;
		}
	}

	private static String normalizeAddress(String ip) {
		String s = ip.strip().toLowerCase(Locale.ROOT);
		if (s.endsWith(":25565")) {
			s = s.substring(0, s.length() - 6);
		}
		if (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}
}
