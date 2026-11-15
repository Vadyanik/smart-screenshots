package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.mixin.FontAccessor;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.StyledNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Player names styled the way the server showed them ({@link ShotMeta#playerNames}). Glyphs the
 * current fonts can't draw (a server resource pack that isn't loaded now) are dropped instead of
 * showing as boxes; if the nick itself doesn't survive, the plain nick is used.
 */
public final class PlayerNames {
	private static final FontDescription PROBE_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("smartshots", "no_such_font"));
	/** Plane 16 private use: no font ships it, so it yields the font's "missing" glyph. */
	private static final int PROBE_CODEPOINT = 0x10FFFD;
	private static final Map<String, Optional<Component>> CACHE = new HashMap<>();

	private PlayerNames() {
	}

	/** Forget resolved names (call when a screen opens: fonts may have changed since). */
	public static void clearCache() {
		CACHE.clear();
	}

	/** The styled name ready to draw, or null when there is none (or it can't be shown). */
	public static @Nullable Component styled(ShotMeta meta, String nick) {
		String json = meta.playerNames == null ? null : meta.playerNames.get(nick);
		if (json == null) {
			return null;
		}
		String key = nick + '\u0000' + json;
		Optional<Component> c = CACHE.get(key);
		if (c == null) {
			if (CACHE.size() > 1024) {
				CACHE.clear();
			}
			c = Optional.ofNullable(resolve(json, nick));
			CACHE.put(key, c);
		}
		return c.orElse(null);
	}

	/** The styled name, else the plain nick. */
	public static Component display(ShotMeta meta, String nick) {
		Component c = styled(meta, nick);
		return c != null ? c : Component.literal(nick);
	}

	/** {@code c} cut to {@code maxW} pixels with an ellipsis, styles kept. */
	public static FormattedCharSequence fit(Font font, Component c, int maxW) {
		if (font.width(c) <= maxW) {
			return c.getVisualOrderText();
		}
		FormattedText cut = font.substrByWidth(c, Math.max(0, maxW - font.width("…")));
		return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…")));
	}

	private static @Nullable Component resolve(String json, String nick) {
		try {
			Component c = StyledNames.parse(json);
			if (c == null) {
				return null;
			}
			c = dropMissingGlyphs(c, Minecraft.getInstance().font);
			if (c == null || !c.getString().toLowerCase(Locale.ROOT).contains(nick.toLowerCase(Locale.ROOT))) {
				return null;
			}
			return c;
		} catch (Exception e) {
			return null;
		}
	}

	private record Segment(Style style, StringBuilder text) {
	}

	/** {@code c} without the characters the current fonts lack (the same component when none). */
	private static @Nullable Component dropMissingGlyphs(Component c, Font font) {
		Font.Provider provider = ((FontAccessor) font).smartshots$getProvider();
		GlyphSource missingFont = provider.glyphs(PROBE_FONT);
		List<Segment> segments = new ArrayList<>();
		boolean[] dropped = {false};
		c.visit((style, text) -> {
			FontDescription fd = style.getFont();
			StringBuilder kept = new StringBuilder();
			if (fd instanceof FontDescription.Resource) {
				GlyphSource src = provider.glyphs(fd);
				boolean fontMissing = src == missingFont;
				BakedGlyph none = fontMissing ? null : src.getGlyph(PROBE_CODEPOINT);
				text.codePoints().forEach(cp -> {
					boolean ok = !fontMissing && (cp == ' ' || src.getGlyph(cp) != none);
					if (ok) {
						kept.appendCodePoint(cp);
					} else {
						dropped[0] = true;
					}
				});
			} else {
				kept.append(text); // sprite / player-head "fonts" draw their own pictures
			}
			if (!kept.isEmpty()) {
				segments.add(new Segment(style, kept));
			}
			return Optional.empty();
		}, Style.EMPTY);
		if (!dropped[0]) {
			return c;
		}
		trim(segments, true);
		trim(segments, false);
		if (segments.isEmpty()) {
			return null;
		}
		MutableComponent out = Component.empty();
		for (Segment s : segments) {
			out.append(Component.literal(s.text.toString().replaceAll(" {2,}", " ")).setStyle(s.style));
		}
		return out;
	}

	/** Strips spaces left at the start / end after glyphs were dropped. */
	private static void trim(List<Segment> segments, boolean leading) {
		while (!segments.isEmpty()) {
			Segment s = leading ? segments.getFirst() : segments.getLast();
			StringBuilder t = s.text;
			while (!t.isEmpty() && Character.isWhitespace(leading ? t.charAt(0) : t.charAt(t.length() - 1))) {
				t.deleteCharAt(leading ? 0 : t.length() - 1);
			}
			if (!t.isEmpty()) {
				return;
			}
			if (leading) {
				segments.removeFirst();
			} else {
				segments.removeLast();
			}
		}
	}
}
