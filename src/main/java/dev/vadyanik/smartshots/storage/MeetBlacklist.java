package dev.vadyanik.smartshots.storage;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.mixin.BiomeManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Places where new players are not detected (a server lobby, a hub world...). An entry is either a
 * whole world / server ({@code multiplayer/play.example.net}) or one sub-world of it
 * ({@code multiplayer/play.example.net/lobby - overworld}); both are folder names of the library.
 *
 * <p>Some well-known lobbies are blocked out of the box ({@link #BUILTIN}). They are matched by the
 * world fingerprint (dimension + hashed seed) rather than by folder name, because sub-world folders
 * are numbered in the order each player happened to visit them. The user can still turn them off.
 */
public final class MeetBlacklist {
	private static final int CACHE_TICKS = 40;

	/** Where the player is now; {@code builtin} is the id of the {@link #BUILTIN} entry it matches, or null. */
	public record Place(String instanceKey, String subKey, String instanceName, String subName, String builtin) {
	}

	/** A place blocked by default: a server whose address ends with {@code hostSuffix}, one sub-world of it. */
	private record Builtin(String id, String hostSuffix, String fingerprint) {
	}

	private static final List<Builtin> BUILTIN = List.of(
		new Builtin("pepeland-lobby", "pepeland.net", "minecraft:overworld@3977d239eae99c6a")
	);

	private static Place cached;
	private static ClientLevel cachedLevel;
	private static int cachedAge;

	private MeetBlacklist() {
	}

	/** The current place, or null outside a world. Cached for a couple of seconds. */
	public static Place current(Minecraft mc) {
		if (mc.level == null) {
			cached = null;
			cachedLevel = null;
			return null;
		}
		if (cached == null || cachedLevel != mc.level || ++cachedAge >= CACHE_TICKS) {
			Location loc = WorldContext.resolve(mc);
			String inst = loc.category().folder + "/" + loc.instanceDir().getFileName();
			cached = new Place(inst, inst + "/" + loc.locationDir().getFileName(), loc.instanceName(), loc.locationName(), builtin(mc, loc));
			cachedLevel = mc.level;
			cachedAge = 0;
		}
		return cached;
	}

	private static String builtin(Minecraft mc, Location loc) {
		if (loc.category() != Category.MULTIPLAYER || mc.getCurrentServer() == null || mc.level == null) {
			return null;
		}
		String host = mc.getCurrentServer().ip.strip().toLowerCase(java.util.Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon > 0) {
			host = host.substring(0, colon);
		}
		long seed = ((BiomeManagerAccessor) mc.level.getBiomeManager()).smartshots$getBiomeZoomSeed();
		String fingerprint = mc.level.dimension().identifier() + "@" + Long.toHexString(seed);
		for (Builtin b : BUILTIN) {
			if ((host.equals(b.hostSuffix) || host.endsWith("." + b.hostSuffix)) && fingerprint.equals(b.fingerprint)) {
				return b.id;
			}
		}
		return null;
	}

	private static boolean builtinActive(Place p) {
		return p != null && p.builtin() != null && !SmartShotsConfig.get().meetBuiltinOff.contains(p.builtin());
	}

	public static boolean isBlocked(Minecraft mc) {
		List<String> list = SmartShotsConfig.get().meetBlacklist;
		Place p = current(mc);
		return p != null && (list.contains(p.instanceKey()) || list.contains(p.subKey()) || builtinActive(p));
	}

	public static boolean contains(String key) {
		Place p = cached;
		return SmartShotsConfig.get().meetBlacklist.contains(key) || p != null && key.equals(p.subKey()) && builtinActive(p);
	}

	public static void set(String key, boolean blocked) {
		SmartShotsConfig c = SmartShotsConfig.get();
		List<String> list = c.meetBlacklist;
		Place p = cached;
		if (p != null && key.equals(p.subKey()) && p.builtin() != null) {
			// A default entry: switch the default itself, the user's own list stays as it was.
			if (blocked) {
				c.meetBuiltinOff.remove(p.builtin());
			} else if (!c.meetBuiltinOff.contains(p.builtin())) {
				c.meetBuiltinOff.add(p.builtin());
			}
		}
		if (blocked && !list.contains(key)) {
			if (p == null || !key.equals(p.subKey()) || p.builtin() == null) {
				list.add(key);
			}
		} else if (!blocked) {
			list.remove(key);
		}
		SmartShotsConfig.save();
	}

	public static List<String> copy() {
		return new ArrayList<>(SmartShotsConfig.get().meetBlacklist);
	}
}
