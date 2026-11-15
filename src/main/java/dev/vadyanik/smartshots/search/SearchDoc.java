package dev.vadyanik.smartshots.search;

import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.StyledNames;
import dev.vadyanik.smartshots.storage.Trigger;
import dev.vadyanik.smartshots.storage.WorldContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pre-lowercased searchable text of one screenshot, grouped by field. */
public final class SearchDoc {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

	public final List<String> players = new ArrayList<>();
	public final List<String> blocks = new ArrayList<>();
	public final List<String> entities = new ArrayList<>();
	public final List<String> items = new ArrayList<>();
	public final List<String> biome = new ArrayList<>();
	public final List<String> world = new ArrayList<>();
	public final List<String> advancements = new ArrayList<>();
	public final List<String> tags = new ArrayList<>();
	public final List<String> signs = new ArrayList<>();
	public final List<String> chat = new ArrayList<>();
	public final List<String> misc = new ArrayList<>();
	public final Trigger trigger;

	public SearchDoc(Library.Shot shot) {
		ShotMeta m = shot.meta;
		trigger = m.trigger();
		for (String p : m.players) {
			players.add(lc(p));
			// The name as the server styled it (prefix, rank...): free text only, player: matches nicks.
			String styled = StyledNames.plainText(m, p);
			if (styled != null) {
				misc.add(lc(styled));
			}
		}
		for (String id : m.blocks.keySet()) {
			addId(blocks, id);
			blocks.add(lc(Names.block(id, m.names)));
		}
		for (String id : m.entities.keySet()) {
			addId(entities, id);
			entities.add(lc(Names.entity(id, m.names)));
		}
		for (String id : m.items.keySet()) {
			addId(items, id);
			items.add(lc(Names.item(id, m.names)));
		}
		if (m.biome != null) {
			addId(biome, m.biome);
			biome.add(lc(Names.biome(m.biome)));
		}
		for (Library.Node n = shot.location; n != null; n = n.parent) {
			if (n.name != null) {
				world.add(lc(n.name));
			}
			if (n.dir != null && n.kind != Library.Kind.UNSORTED) {
				world.add(lc(n.dir.getFileName().toString()));
			}
		}
		if (m.dimension != null) {
			world.add(lc(m.dimension));
			world.add(lc(WorldContext.dimensionName(m.dimension)));
		}
		for (String a : m.advancements) {
			advancements.add(lc(a));
		}
		for (String a : m.advancementIds) {
			advancements.add(lc(a));
		}
		for (String t : m.tags) {
			tags.add(lc(t));
			tags.add(lc(Names.tag(t)));
		}
		if (m.signs != null) {
			for (String t : m.signs) {
				signs.add(t);
			}
		}
		if (m.chat != null) {
			chat.addAll(m.chat);
		}
		misc.add(lc(shot.file.getFileName().toString()));
		misc.add(lc(trigger.label().getString()));
		misc.add(trigger.id);
		for (String t : dev.vadyanik.smartshots.storage.EditOp.texts(m.edits)) {
			misc.add(lc(t));
		}
		if (m.note != null && !m.note.isBlank()) {
			misc.add(lc(m.note));
		}
		if (m.time > 0) {
			misc.add(DATE.format(Instant.ofEpochMilli(m.time)));
		}
	}

	/** "minecraft:wolf" and "wolf", so /^wolf/ works too. */
	private static void addId(List<String> list, String id) {
		String lc = lc(id);
		list.add(lc);
		int colon = lc.indexOf(':');
		if (colon >= 0) {
			list.add(lc.substring(colon + 1));
		}
	}

	static String lc(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
