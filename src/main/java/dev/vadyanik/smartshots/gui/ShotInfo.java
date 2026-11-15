package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.search.Names;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.Trigger;
import dev.vadyanik.smartshots.storage.WorldContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Text helpers that describe a screenshot. */
public final class ShotInfo {
	private ShotInfo() {
	}

	public static String dateTime(long time) {
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
			.withLocale(GalleryScreen.gameLocale())
			.withZone(ZoneId.systemDefault())
			.format(Instant.ofEpochMilli(time));
	}

	public static String where(Library.Shot shot) {
		List<String> parts = new ArrayList<>();
		for (Library.Node n = shot.location; n != null; n = n.parent) {
			if (n.kind == Library.Kind.INSTANCE || n.kind == Library.Kind.LOCATION || n.kind == Library.Kind.PACK) {
				parts.addFirst(n.name);
			}
		}
		if (parts.isEmpty() && shot.meta.dimension != null) {
			parts.add(WorldContext.dimensionName(shot.meta.dimension));
		}
		return String.join(" › ", parts);
	}

	public static List<Component> tooltip(Library.Shot shot) {
		ShotMeta m = shot.meta;
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(dateTime(m.time)).withStyle(ChatFormatting.WHITE));
		Trigger t = m.trigger();
		lines.add(t.label().copy().withColor(t.color));
		String where = where(shot);
		if (!where.isEmpty()) {
			lines.add(Component.literal(where).withStyle(ChatFormatting.GRAY));
		}
		for (String a : m.advancements) {
			lines.add(Component.translatable("smartshots.tooltip.advancement", a).withColor(GalleryScreen.GOLD));
		}
		List<Component> met = new ArrayList<>();
		for (String tag : m.tags) {
			if (tag.startsWith("met:")) {
				met.add(PlayerNames.display(m, tag.substring(4)));
			}
		}
		if (!met.isEmpty()) {
			lines.add(Component.translatable("smartshots.tooltip.met", join(met)).withColor(Trigger.NEW_PLAYER.color));
		}
		List<Component> seen = new ArrayList<>();
		for (String p : m.players) {
			seen.add(PlayerNames.display(m, p));
		}
		int n = 0;
		for (Map.Entry<String, Integer> e : m.entities.entrySet()) {
			if (e.getKey().equals("minecraft:player") || n++ >= 4) {
				continue;
			}
			seen.add(Component.literal(Names.entity(e.getKey(), m.names) + (e.getValue() > 1 ? " ×" + e.getValue() : "")));
		}
		int ni = 0;
		for (Map.Entry<String, Integer> e : m.items.entrySet()) {
			if (ni++ >= 2) {
				break;
			}
			seen.add(Component.literal(Names.item(e.getKey(), m.names) + (e.getValue() > 1 ? " ×" + e.getValue() : "")));
		}
		if (!seen.isEmpty()) {
			lines.add(join(seen).withColor(0xFFB0C4FF));
		}
		if (!m.note.isBlank()) {
			lines.add(Component.literal("\"" + m.note + "\"").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
		}
		lines.add(Component.translatable("smartshots.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
		return lines;
	}

	/** Parts joined with ", "; unstyled parts take the color set on the result. */
	private static MutableComponent join(List<Component> parts) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(parts.get(i));
		}
		return out;
	}
}
