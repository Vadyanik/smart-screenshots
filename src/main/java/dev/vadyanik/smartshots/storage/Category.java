package dev.vadyanik.smartshots.storage;

import net.minecraft.network.chat.Component;

public enum Category {
	SINGLEPLAYER("singleplayer"),
	MULTIPLAYER("multiplayer"),
	REALMS("realms"),
	OTHER("other");

	public final String folder;

	Category(String folder) {
		this.folder = folder;
	}

	public Component label() {
		return Component.translatable("smartshots.category." + folder);
	}

	public static Category byFolder(String folder) {
		for (Category c : values()) {
			if (c.folder.equals(folder)) {
				return c;
			}
		}
		return null;
	}
}
