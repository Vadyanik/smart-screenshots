package dev.vadyanik.smartshots.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * Player names as the server styles them (TAB list display name, else the team-formatted name):
 * colors, gradients, prefixes, resource-pack glyphs. Stored in {@link ShotMeta#playerNames} as
 * component JSON; the nickname itself stays the identity everywhere else.
 */
public final class StyledNames {
	/** Longer JSON is not worth keeping (a tab name is normally well under 1 KB). */
	private static final int MAX_JSON = 4096;

	private StyledNames() {
	}

	/** Game thread: records the styled name of a player on the shot (first one wins). */
	public static void record(ShotMeta meta, Minecraft mc, String nick, UUID uuid, @Nullable Player player) {
		if (meta.playerNames == null) {
			meta.playerNames = new LinkedHashMap<>();
		}
		if (meta.playerNames.containsKey(nick)) {
			return;
		}
		String json = capture(mc, nick, uuid, player);
		if (json != null) {
			meta.playerNames.put(nick, json);
		}
	}

	/** Game thread: the styled name as JSON, or null when there is nothing beyond the plain nick. */
	public static @Nullable String capture(Minecraft mc, String nick, UUID uuid, @Nullable Player player) {
		try {
			Component c = null;
			ClientPacketListener conn = mc.getConnection();
			PlayerInfo info = conn != null ? conn.getPlayerInfo(uuid) : null;
			if (info != null) {
				c = info.getTabListDisplayName();
			}
			if (c == null && player != null) {
				c = player.getDisplayName();
			}
			if (c == null && info != null) {
				PlayerTeam team = info.getTeam();
				if (team != null) {
					c = team.getFormattedName(Component.literal(nick));
				}
			}
			if (c == null) {
				return null;
			}
			c = strip(c);
			if (isPlain(c, nick)) {
				return null;
			}
			DynamicOps<JsonElement> ops = mc.level != null ? RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess()) : JsonOps.INSTANCE;
			JsonElement json = ComponentSerialization.CODEC.encodeStart(ops, c).result().orElse(null);
			if (json == null) {
				return null;
			}
			String s = json.toString();
			return s.length() > MAX_JSON ? null : s;
		} catch (Exception e) {
			return null;
		}
	}

	/** Parses stored JSON; null on any error. Thread-safe (no registries needed: events are stripped). */
	public static @Nullable Component parse(@Nullable String json) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		try {
			return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	/** Visible text of the stored styled name, or null. */
	public static @Nullable String plainText(ShotMeta meta, String nick) {
		if (meta.playerNames == null) {
			return null;
		}
		Component c = parse(meta.playerNames.get(nick));
		return c == null ? null : c.getString();
	}

	/** Copy without click / hover events and insertion (they only bloat the JSON). */
	private static Component strip(Component c) {
		Style st = c.getStyle().withClickEvent(null).withHoverEvent(null).withInsertion(null);
		MutableComponent out = MutableComponent.create(c.getContents()).setStyle(st);
		for (Component sibling : c.getSiblings()) {
			out.append(strip(sibling));
		}
		return out;
	}

	private static boolean isPlain(Component c, String nick) {
		if (!c.getString().equals(nick)) {
			return false;
		}
		boolean[] styled = {false};
		c.visit((style, text) -> {
			if (!text.isEmpty() && !style.equals(Style.EMPTY)) {
				styled[0] = true;
				return Optional.of(Boolean.TRUE);
			}
			return Optional.empty();
		}, Style.EMPTY);
		return !styled[0];
	}
}
