package dev.vadyanik.smartshots.search;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Localized names for block / entity / biome ids in the current game language. */
public final class Names {
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private Names() {
	}

	public static void clearCache() {
		CACHE.clear();
	}

	public static String block(String id, Map<String, String> fallback) {
		return CACHE.computeIfAbsent("b|" + id, k -> {
			Identifier rl = Identifier.tryParse(id);
			if (rl != null) {
				var block = BuiltInRegistries.BLOCK.getOptional(rl);
				if (block.isPresent()) {
					return block.get().getName().getString();
				}
			}
			return fallback != null && fallback.containsKey(id) ? fallback.get(id) : path(id);
		});
	}

	public static String entity(String id, Map<String, String> fallback) {
		return CACHE.computeIfAbsent("e|" + id, k -> {
			Identifier rl = Identifier.tryParse(id);
			if (rl != null) {
				var type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl);
				if (type.isPresent()) {
					return type.get().getDescription().getString();
				}
			}
			return fallback != null && fallback.containsKey(id) ? fallback.get(id) : path(id);
		});
	}

	/** Item names. {@code fallback} is {@link dev.vadyanik.smartshots.storage.ShotMeta#names}; items are stored there as "item|&lt;id&gt;". */
	public static String item(String id, Map<String, String> fallback) {
		return CACHE.computeIfAbsent("i|" + id, k -> {
			Identifier rl = Identifier.tryParse(id);
			if (rl != null) {
				var item = BuiltInRegistries.ITEM.getOptional(rl);
				if (item.isPresent()) {
					return itemName(item.get());
				}
			}
			String key = "item|" + id;
			return fallback != null && fallback.containsKey(key) ? fallback.get(key) : path(id);
		});
	}

	/**
	 * Item components are only bound while a world is loaded: in the main menu
	 * {@code getDefaultInstance()} throws, so fall back to the plain translation key.
	 */
	private static String itemName(net.minecraft.world.item.Item item) {
		try {
			return item.getName(item.getDefaultInstance()).getString();
		} catch (RuntimeException e) {
			return Component.translatable(item.getDescriptionId()).getString();
		}
	}

	public static String biome(String id) {
		if (id == null) {
			return "";
		}
		return CACHE.computeIfAbsent("bi|" + id, k -> {
			Identifier rl = Identifier.tryParse(id);
			if (rl == null) {
				return id;
			}
			String key = "biome." + rl.getNamespace() + "." + rl.getPath();
			String s = Component.translatable(key).getString();
			return s.equals(key) ? path(id) : s;
		});
	}

	/** "smartshots.tag.night" -> "Night"; unknown tags are shown as-is. */
	public static String tag(String tag) {
		int colon = tag.indexOf(':');
		if (colon > 0) {
			String kind = tag.substring(0, colon);
			String key = "smartshots.tag." + kind;
			String label = Component.translatable(key).getString();
			return (label.equals(key) ? kind : label) + ": " + tag.substring(colon + 1);
		}
		String key = "smartshots.tag." + tag;
		String s = Component.translatable(key).getString();
		return s.equals(key) ? tag : s;
	}

	public static String path(String id) {
		int i = id.indexOf(':');
		return (i >= 0 ? id.substring(i + 1) : id).replace('_', ' ');
	}
}
