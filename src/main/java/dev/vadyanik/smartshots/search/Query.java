package dev.vadyanik.smartshots.search;

import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.Trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search query language. Words are AND-ed; each word may be:
 * <pre>
 *   diamond            free text: matches blocks, mobs, players, biome, world, advancement, tags, date...
 *   player:Notch       игрок:Notch   p:Notch
 *   block:diamond_ore  блок:алмаз    b:...
 *   mob:wolf           моб:волк      entity: / e: / сущность:
 *   biome:desert       биом:пустыня
 *   world:survival     мир: / server: / сервер:
 *   adv:"Diamonds!"    достижение:...
 *   type:auto          тип:вручную   (manual / auto / advancement / new_player)
 *   met:flower17       знакомство:flower17   shot of the first meeting with that player
 *   знакомство / meeting    any first-meeting shot
 *   tag:night          тег:ночь
 *   date:2026-11       дата:14.11
 *   sign:"welcome"     табличка: / надпись: / текст:  (text on visible signs)
 *   chat:"gg"          чат:  (chat messages visible on the shot)
 *   item:diamond       предмет:/вещь:/i:  dropped items and items in frames
 *   freecam / фрикам   shot taken in freecam
 *   fav / ★ / избранное
 *   players / игроки   any other player is on the shot
 *   -word              exclude
 *   "two words"        phrase
 *   /дом|база/         regular expression (case-insensitive) over every field; field:/regex/ for one
 *                      field (sign:/^дом/, mob:/^вол/...). The closing slash is optional while typing.
 *                      Ids are searchable with and without the namespace (minecraft:wolf and wolf).
 * </pre>
 */
public final class Query {
	public enum Field { ANY, SIGN, CHAT, PLAYER, BLOCK, ENTITY, BIOME, WORLD, ADV, TAG, TYPE, DATE, ITEM, FAV, HAS_PLAYERS, FREECAM, MET }

	/** {@code regex} is set for /pattern/ values; then {@code values} is ignored. */
	public record Term(Field field, List<String> values, boolean negate, java.util.regex.@org.jspecify.annotations.Nullable Pattern regex) {
		Term(Field field, List<String> values, boolean negate) {
			this(field, values, negate, null);
		}
	}

	private static final Map<String, Field> PREFIXES = Map.ofEntries(
		Map.entry("player", Field.PLAYER), Map.entry("p", Field.PLAYER), Map.entry("игрок", Field.PLAYER), Map.entry("ник", Field.PLAYER),
		Map.entry("block", Field.BLOCK), Map.entry("b", Field.BLOCK), Map.entry("блок", Field.BLOCK),
		Map.entry("entity", Field.ENTITY), Map.entry("mob", Field.ENTITY), Map.entry("e", Field.ENTITY), Map.entry("моб", Field.ENTITY),
		Map.entry("сущность", Field.ENTITY), Map.entry("существо", Field.ENTITY),
		Map.entry("item", Field.ITEM), Map.entry("i", Field.ITEM), Map.entry("предмет", Field.ITEM), Map.entry("вещь", Field.ITEM),
		Map.entry("biome", Field.BIOME), Map.entry("биом", Field.BIOME),
		Map.entry("world", Field.WORLD), Map.entry("server", Field.WORLD), Map.entry("мир", Field.WORLD), Map.entry("сервер", Field.WORLD),
		Map.entry("dim", Field.WORLD), Map.entry("измерение", Field.WORLD),
		Map.entry("adv", Field.ADV), Map.entry("advancement", Field.ADV), Map.entry("achievement", Field.ADV), Map.entry("достижение", Field.ADV),
		Map.entry("tag", Field.TAG), Map.entry("тег", Field.TAG),
		Map.entry("type", Field.TYPE), Map.entry("тип", Field.TYPE), Map.entry("t", Field.TYPE),
		Map.entry("met", Field.MET), Map.entry("meet", Field.MET), Map.entry("знакомство", Field.MET), Map.entry("встреча", Field.MET),
		Map.entry("date", Field.DATE), Map.entry("дата", Field.DATE),
		Map.entry("sign", Field.SIGN), Map.entry("text", Field.SIGN), Map.entry("табличка", Field.SIGN),
		Map.entry("текст", Field.SIGN), Map.entry("надпись", Field.SIGN),
		Map.entry("chat", Field.CHAT), Map.entry("чат", Field.CHAT));

	private static final Map<String, Field> KEYWORDS = Map.ofEntries(
		Map.entry("freecam", Field.FREECAM), Map.entry("фрикам", Field.FREECAM), Map.entry("фрикамера", Field.FREECAM),
		Map.entry("fav", Field.FAV), Map.entry("favorite", Field.FAV), Map.entry("favorites", Field.FAV), Map.entry("★", Field.FAV),
		Map.entry("избранное", Field.FAV), Map.entry("избранные", Field.FAV),
		Map.entry("знакомство", Field.MET), Map.entry("знакомства", Field.MET), Map.entry("знакомств", Field.MET),
		Map.entry("meeting", Field.MET), Map.entry("meetings", Field.MET), Map.entry("met", Field.MET),
		Map.entry("first_meet", Field.MET), Map.entry("new_player", Field.MET), Map.entry("встреча", Field.MET), Map.entry("встречи", Field.MET),
		Map.entry("players", Field.HAS_PLAYERS), Map.entry("игроки", Field.HAS_PLAYERS), Map.entry("люди", Field.HAS_PLAYERS));

	/** Everyday words -> entity ids. */
	private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
		Map.entry("dog", List.of("minecraft:wolf")), Map.entry("dogs", List.of("minecraft:wolf")),
		Map.entry("собака", List.of("minecraft:wolf")), Map.entry("собаки", List.of("minecraft:wolf")),
		Map.entry("собак", List.of("minecraft:wolf")), Map.entry("пёс", List.of("minecraft:wolf")), Map.entry("пес", List.of("minecraft:wolf")),
		Map.entry("собачка", List.of("minecraft:wolf")), Map.entry("псы", List.of("minecraft:wolf")),
		Map.entry("кот", List.of("minecraft:cat", "minecraft:ocelot")), Map.entry("коты", List.of("minecraft:cat", "minecraft:ocelot")),
		Map.entry("котик", List.of("minecraft:cat", "minecraft:ocelot")), Map.entry("cats", List.of("minecraft:cat", "minecraft:ocelot")),
		Map.entry("kitty", List.of("minecraft:cat")),
		Map.entry("horse", List.of("minecraft:horse", "minecraft:donkey", "minecraft:mule")),
		Map.entry("лошадь", List.of("minecraft:horse", "minecraft:donkey", "minecraft:mule")),
		Map.entry("конь", List.of("minecraft:horse")),
		Map.entry("dragon", List.of("minecraft:ender_dragon")), Map.entry("дракон", List.of("minecraft:ender_dragon")),
		Map.entry("boat", List.of("minecraft:oak_boat", "minecraft:spruce_boat", "minecraft:birch_boat", "minecraft:jungle_boat",
			"minecraft:acacia_boat", "minecraft:dark_oak_boat", "minecraft:mangrove_boat", "minecraft:cherry_boat", "minecraft:pale_oak_boat",
			"minecraft:bamboo_raft")),
		Map.entry("лодка", List.of("minecraft:oak_boat", "minecraft:spruce_boat", "minecraft:birch_boat", "minecraft:jungle_boat",
			"minecraft:acacia_boat", "minecraft:dark_oak_boat", "minecraft:mangrove_boat", "minecraft:cherry_boat", "minecraft:pale_oak_boat",
			"minecraft:bamboo_raft")));

	private final List<Term> terms;

	private Query(List<Term> terms) {
		this.terms = terms;
	}

	public boolean isEmpty() {
		return terms.isEmpty();
	}

	/** True when a positive type: term matches auto shots, so they must show even if hidden by default. */
	public boolean asksForAuto() {
		for (Term t : terms) {
			if (t.field == Field.TYPE && !t.negate && t.regex == null && !t.values.isEmpty()
				&& typeMatches(Trigger.AUTO, t.values.getFirst())) {
				return true;
			}
		}
		return false;
	}

	public static Query parse(String text) {
		List<Term> terms = new ArrayList<>();
		for (String raw : tokenize(text)) {
			boolean negate = false;
			String tok = raw;
			if (tok.startsWith("-") && tok.length() > 1) {
				negate = true;
				tok = tok.substring(1);
			} else if (tok.startsWith("!") && tok.length() > 1) {
				negate = true;
				tok = tok.substring(1);
			}
			String lc = tok.toLowerCase(Locale.ROOT);
			java.util.regex.Pattern bareRegex = regex(tok);
			if (bareRegex != null) {
				terms.add(new Term(Field.ANY, List.of(), negate, bareRegex));
				continue;
			}
			Field kw = KEYWORDS.get(lc);
			if (kw != null) {
				terms.add(new Term(kw, List.of(), negate));
				continue;
			}
			int colon = lc.indexOf(':');
			if (colon > 0) {
				Field f = PREFIXES.get(lc.substring(0, colon));
				if (f != null) {
					java.util.regex.Pattern re = regex(tok.substring(colon + 1));
					if (re != null) {
						terms.add(new Term(f, List.of(), negate, re));
						continue;
					}
					String value = unquote(lc.substring(colon + 1));
					if (!value.isEmpty()) {
						terms.add(new Term(f, expand(value), negate));
					}
					continue;
				}
				if (lc.startsWith("is:") || lc.startsWith("есть:")) {
					Field f2 = KEYWORDS.get(lc.substring(lc.indexOf(':') + 1));
					if (f2 != null) {
						terms.add(new Term(f2, List.of(), negate));
						continue;
					}
				}
			}
			terms.add(new Term(Field.ANY, expand(unquote(lc)), negate));
		}
		return new Query(terms);
	}

	/**
	 * "/pattern/" (or an unfinished "/pattern" while typing) -> compiled case-insensitive pattern;
	 * anything else, or an invalid pattern, -> null.
	 */
	static java.util.regex.@org.jspecify.annotations.Nullable Pattern regex(String raw) {
		String body = regexBody(raw);
		if (body == null) {
			return null;
		}
		try {
			return java.util.regex.Pattern.compile(body,
				java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
		} catch (java.util.regex.PatternSyntaxException e) {
			return null;
		}
	}

	private static @org.jspecify.annotations.Nullable String regexBody(String raw) {
		if (raw.length() < 2 || !raw.startsWith("/")) {
			return null;
		}
		String body = raw.length() >= 3 && raw.endsWith("/") && !raw.endsWith("\\/")
			? raw.substring(1, raw.length() - 1) : raw.substring(1);
		return body.isEmpty() ? null : body;
	}

	/** True when the text has a /regex/ that doesn't compile (the search box shows it in red). */
	public static boolean hasBadRegex(String text) {
		for (String tok : tokenize(text)) {
			if ((tok.startsWith("-") || tok.startsWith("!")) && tok.length() > 1) {
				tok = tok.substring(1);
			}
			int colon = tok.indexOf(':');
			if (!tok.startsWith("/") && colon > 0 && PREFIXES.containsKey(tok.substring(0, colon).toLowerCase(Locale.ROOT))) {
				tok = tok.substring(colon + 1);
			}
			if (regexBody(tok) != null && regex(tok) == null) {
				return true;
			}
		}
		return false;
	}

	private static List<String> expand(String value) {
		List<String> list = new ArrayList<>();
		list.add(value);
		List<String> syn = SYNONYMS.get(value);
		if (syn != null) {
			list.addAll(syn);
		}
		return list;
	}

	private static String unquote(String s) {
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			return s.substring(1, s.length() - 1);
		}
		return s.replace("\"", "");
	}

	static List<String> tokenize(String text) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		boolean inRegex = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inRegex) {
				cur.append(c);
				if (c == '\\' && i + 1 < text.length()) {
					cur.append(text.charAt(++i));
				} else if (c == '/') {
					inRegex = false;
				}
				continue;
			}
			if (c == '/' && !quoted && (cur.isEmpty() || cur.toString().equals("-") || cur.toString().equals("!")
				|| cur.charAt(cur.length() - 1) == ':')) {
				inRegex = true;
				cur.append(c);
				continue;
			}
			if (c == '"') {
				quoted = !quoted;
				cur.append(c);
			} else if (Character.isWhitespace(c) && !quoted) {
				if (!cur.isEmpty()) {
					out.add(cur.toString());
					cur.setLength(0);
				}
			} else {
				cur.append(c);
			}
		}
		if (!cur.isEmpty()) {
			out.add(cur.toString());
		}
		return out;
	}

	public boolean matches(Library.Shot shot) {
		for (Term t : terms) {
			if (matches(t, shot) == t.negate) {
				return false;
			}
		}
		return true;
	}

	private static boolean matches(Term t, Library.Shot shot) {
		SearchDoc d = shot.doc;
		if (t.regex != null) {
			return regexMatches(t, d);
		}
		return switch (t.field) {
			case FREECAM -> any(d.tags, List.of("freecam"));
			case MET -> t.values.isEmpty() ? d.trigger == Trigger.NEW_PLAYER : any(met(d.tags), t.values);
			case ITEM -> any(d.items, t.values);
			case FAV -> shot.meta.favorite;
			case HAS_PLAYERS -> !d.players.isEmpty();
			case PLAYER -> any(d.players, t.values);
			case BLOCK -> any(d.blocks, t.values);
			case ENTITY -> any(d.entities, t.values);
			case BIOME -> any(d.biome, t.values);
			case WORLD -> any(d.world, t.values);
			case ADV -> any(d.advancements, t.values);
			case TAG -> any(d.tags, t.values);
			case DATE -> any(d.misc, t.values);
			case SIGN -> any(d.signs, t.values);
			case CHAT -> any(d.chat, t.values);
			case TYPE -> typeMatches(d.trigger, t.values.getFirst());
			case ANY -> any(d.players, t.values) || any(d.blocks, t.values) || any(d.entities, t.values) || any(d.items, t.values)
				|| any(d.biome, t.values) || any(d.world, t.values) || any(d.advancements, t.values)
				|| any(d.tags, t.values) || any(d.signs, t.values) || any(d.chat, t.values) || any(d.misc, t.values);
		};
	}

	private static boolean regexMatches(Term t, SearchDoc d) {
		java.util.regex.Pattern p = t.regex;
		return switch (t.field) {
			case PLAYER -> find(d.players, p);
			case BLOCK -> find(d.blocks, p);
			case ENTITY -> find(d.entities, p);
			case BIOME -> find(d.biome, p);
			case WORLD -> find(d.world, p);
			case ADV -> find(d.advancements, p);
			case TAG -> find(d.tags, p);
			case SIGN -> find(d.signs, p);
			case CHAT -> find(d.chat, p);
			case DATE, TYPE -> find(d.misc, p);
			case ITEM -> find(d.items, p);
			case FREECAM -> find(d.tags, p);
			case MET -> find(met(d.tags), p);
			case FAV, HAS_PLAYERS -> false;
			case ANY -> find(d.signs, p) || find(d.players, p) || find(d.blocks, p) || find(d.entities, p) || find(d.items, p) || find(d.biome, p)
				|| find(d.world, p) || find(d.advancements, p) || find(d.tags, p) || find(d.chat, p) || find(d.misc, p);
		};
	}

	/** Nicknames from the {@code met:<nick>} tags. */
	private static List<String> met(List<String> tags) {
		List<String> out = new ArrayList<>();
		for (String t : tags) {
			if (t.startsWith("met:")) {
				out.add(t.substring(4));
			}
		}
		return out;
	}

	private static boolean find(List<String> hay, java.util.regex.Pattern p) {
		for (String h : hay) {
			if (p.matcher(h).find()) {
				return true;
			}
		}
		return false;
	}

	private static boolean typeMatches(Trigger trigger, String v) {
		Trigger wanted = switch (v) {
			case "manual", "m", "f2", "вручную", "ручной", "ручные", "руч" -> Trigger.MANUAL;
			case "auto", "a", "interval", "авто", "автоматически", "автоматические", "таймер" -> Trigger.AUTO;
			case "advancement", "adv", "achievement", "достижение", "достижения", "ачивка", "ачивки" -> Trigger.ADVANCEMENT;
			case "new_player", "newplayer", "new", "player", "players", "meet", "meeting", "met", "first_meet",
				"новый", "новый_игрок", "новый игрок", "новые", "новые_игроки", "новые игроки", "игрок", "игроки",
				"знакомство", "знакомства", "встреча", "встречи" -> Trigger.NEW_PLAYER;
			default -> null;
		};
		if (wanted == null) {
			return trigger.label().getString().toLowerCase(Locale.ROOT).contains(v);
		}
		return trigger == wanted;
	}

	private static boolean any(List<String> hay, List<String> needles) {
		for (String n : needles) {
			for (String h : hay) {
				if (fuzzyContains(h.toLowerCase(Locale.ROOT), n)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Substring match with light stemming so "алмазы" finds "алмазная руда" and "diamonds" finds "diamond ore". */
	static boolean fuzzyContains(String hay, String needle) {
		if (hay.contains(needle)) {
			return true;
		}
		String n = needle.replace(' ', '_');
		if (!n.equals(needle) && hay.contains(n)) {
			return true;
		}
		int len = needle.length();
		if (len >= 5) {
			String stem = needle.substring(0, Math.max(4, len - (len >= 7 ? 2 : 1)));
			return hay.contains(stem);
		}
		return false;
	}
}
