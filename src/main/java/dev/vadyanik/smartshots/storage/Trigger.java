package dev.vadyanik.smartshots.storage;

import net.minecraft.network.chat.Component;

public enum Trigger {
	MANUAL("manual", 0xFF4FA3FF),
	AUTO("auto", 0xFF5BD37A),
	ADVANCEMENT("advancement", 0xFFF2C14E),
	/** First time the player saw another player (see capture.PlayerMeet). */
	NEW_PLAYER("new_player", 0xFFE879C9),
	UNKNOWN("unknown", 0xFF9A9A9A);

	public final String id;
	public final int color;

	Trigger(String id, int color) {
		this.id = id;
		this.color = color;
	}

	public Component label() {
		return Component.translatable("smartshots.trigger." + id);
	}

	public static Trigger byId(String id) {
		for (Trigger t : values()) {
			if (t.id.equals(id)) {
				return t;
			}
		}
		return UNKNOWN;
	}
}
