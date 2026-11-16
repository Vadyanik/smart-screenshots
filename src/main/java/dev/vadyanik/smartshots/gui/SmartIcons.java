package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.SmartShots;
import net.minecraft.resources.Identifier;

/** Line icons of the editor and sharing (9x9 sprites in textures/gui/sprites/ui, made by tools/icons.py). */
public final class SmartIcons {
	public static final Identifier EDIT = ui("edit");
	public static final Identifier SHARE = ui("share");
	public static final Identifier UNDO = ui("undo");
	public static final Identifier REDO = ui("redo");
	public static final Identifier MAP = ui("map");
	public static final Identifier WAYPOINT = ui("waypoint");

	private SmartIcons() {
	}

	static Identifier tool(String id) {
		return ui("tool_" + id);
	}

	private static Identifier ui(String name) {
		return SmartShots.id("ui/" + name);
	}
}
