package dev.vadyanik.smartshots.search;

import dev.vadyanik.smartshots.storage.Library;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Autocomplete vocabulary built from everything seen across the library. */
public final class Suggestions {
	public record Entry(String insert, String label, String kind, int count, String haystack) {
	}

	private final List<Entry> entries = new ArrayList<>();

	public Suggestions(Library lib) {
		Map<String, int[]> counts = new HashMap<>();
		Map<String, String[]> info = new HashMap<>();
		for (Library.Shot s : lib.shots) {
			var m = s.meta;
			for (String p : m.players) {
				add(counts, info, "player:" + quoteIfNeeded(p), p, "player");
			}
			for (String id : m.entities.keySet()) {
				add(counts, info, "mob:" + shortId(id), Names.entity(id, m.names), "mob");
			}
			for (String id : m.items.keySet()) {
				add(counts, info, "item:" + shortId(id), Names.item(id, m.names), "item");
			}
			for (String id : m.blocks.keySet()) {
				add(counts, info, "block:" + shortId(id), Names.block(id, m.names), "block");
			}
			if (m.biome != null) {
				add(counts, info, "biome:" + shortId(m.biome), Names.biome(m.biome), "biome");
			}
			for (String a : m.advancements) {
				add(counts, info, "adv:" + quoteIfNeeded(a), a, "adv");
			}
			for (String t : m.tags) {
				if (t.startsWith("met:")) {
					// First meeting with a player: offered as met:<nick>.
					add(counts, info, "met:" + quoteIfNeeded(t.substring(4)), Names.tag(t), "tag");
					continue;
				}
				add(counts, info, "tag:" + quoteIfNeeded(t), Names.tag(t), "tag");
			}
			if (m.signs != null) {
				for (String t : m.signs) {
					add(counts, info, "sign:" + quoteIfNeeded(t), t, "sign");
				}
			}
		}
		for (String type : List.of("manual", "auto", "advancement", "new_player")) {
			add(counts, info, "type:" + type, Component.translatable("smartshots.trigger." + type).getString(), "type");
		}
		counts.forEach((insert, c) -> {
			String[] i = info.get(insert);
			entries.add(new Entry(insert, i[0], i[1], c[0], (i[0] + " " + insert).toLowerCase(Locale.ROOT)));
		});
		entries.sort(Comparator.comparingInt(Entry::count).reversed());
	}

	private static void add(Map<String, int[]> counts, Map<String, String[]> info, String insert, String label, String kind) {
		counts.computeIfAbsent(insert, k -> new int[1])[0]++;
		info.putIfAbsent(insert, new String[]{label, kind});
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	private static String quoteIfNeeded(String s) {
		return s.contains(" ") ? "\"" + s + "\"" : s;
	}

	/** Suggestions for the word currently being typed. */
	public List<Entry> complete(String word, int limit) {
		String w = word.toLowerCase(Locale.ROOT);
		int colon = w.indexOf(':');
		String value = colon >= 0 ? w.substring(colon + 1) : w;
		if (value.length() < 1 && colon < 0) {
			return List.of();
		}
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries) {
			boolean hit = colon >= 0
				? e.insert.toLowerCase(Locale.ROOT).startsWith(w.substring(0, colon + 1).replace("моб:", "mob:").replace("блок:", "block:")
					.replace("игрок:", "player:").replace("биом:", "biome:")) && Query.fuzzyContains(e.haystack, value)
				: Query.fuzzyContains(e.haystack, value);
			if (hit) {
				out.add(e);
				if (out.size() >= limit) {
					break;
				}
			}
		}
		return out;
	}
}
