package dev.vadyanik.smartshots.storage;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Places where new players are not detected (a server lobby, a hub world...). An entry is either a
 * whole world / server ({@code multiplayer/play.example.net}) or one sub-world of it
 * ({@code multiplayer/play.example.net/lobby - overworld}); both are folder names of the library.
 */
public final class MeetBlacklist {
	private static final int CACHE_TICKS = 40;

	/** Where the player is now. */
	public record Place(String instanceKey, String subKey, String instanceName, String subName) {
	}

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
			cached = new Place(inst, inst + "/" + loc.locationDir().getFileName(), loc.instanceName(), loc.locationName());
			cachedLevel = mc.level;
			cachedAge = 0;
		}
		return cached;
	}

	public static boolean isBlocked(Minecraft mc) {
		List<String> list = SmartShotsConfig.get().meetBlacklist;
		if (list.isEmpty()) {
			return false;
		}
		Place p = current(mc);
		return p != null && (list.contains(p.instanceKey()) || list.contains(p.subKey()));
	}

	public static boolean contains(String key) {
		return SmartShotsConfig.get().meetBlacklist.contains(key);
	}

	public static void set(String key, boolean blocked) {
		List<String> list = SmartShotsConfig.get().meetBlacklist;
		if (blocked && !list.contains(key)) {
			list.add(key);
		} else if (!blocked) {
			list.remove(key);
		}
		SmartShotsConfig.save();
	}

	public static List<String> copy() {
		return new ArrayList<>(SmartShotsConfig.get().meetBlacklist);
	}
}
