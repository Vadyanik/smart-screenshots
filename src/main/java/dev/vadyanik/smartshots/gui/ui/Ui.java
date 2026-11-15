package dev.vadyanik.smartshots.gui.ui;

import dev.vadyanik.smartshots.SmartShots;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Drawing helpers: soft-cornered rectangles and tinted line icons. */
public final class Ui {
	public static final Identifier SETTINGS = icon("settings");
	public static final Identifier FOLDER = icon("folder");
	public static final Identifier CLOSE = icon("close");
	public static final Identifier INFO = icon("info");
	public static final Identifier TRASH = icon("trash");
	public static final Identifier OPEN = icon("open");
	public static final Identifier STAR = icon("star");
	public static final Identifier STAR_EMPTY = icon("star_empty");
	public static final Identifier STAR_SMALL = icon("star_small");
	public static final Identifier NOTE = icon("note");
	public static final Identifier ARROW_LEFT = icon("arrow_left");
	public static final Identifier ARROW_RIGHT = icon("arrow_right");
	public static final Identifier EXPAND_RIGHT = icon("expand_right");
	public static final Identifier EXPAND_DOWN = icon("expand_down");
	public static final Identifier CLOCK = icon("clock");
	public static final Identifier TROPHY = icon("trophy");
	public static final Identifier PERSON = icon("person");
	public static final Identifier ALL = icon("all");
	public static final Identifier DOT = icon("dot");
	public static final Identifier SEARCH = icon("search");
	public static final Identifier CHECK = icon("check");
	public static final Identifier SORT = icon("sort");
	public static final Identifier FREECAM = icon("freecam");
	public static final Identifier COPY = icon("copy");

	private Ui() {
	}

	private static Identifier icon(String name) {
		return SmartShots.id("ui/" + name);
	}

	/** Filled rectangle with 1px rounded corners. */
	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w <= 2 || h <= 2) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x + 1, y, x + w - 1, y + 1, color);
		g.fill(x, y + 1, x + w, y + h - 1, color);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
	}

	/** 1px outline with rounded corners. */
	public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x + 1, y, x + w - 1, y + 1, color);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		rect(g, x, y, w, h, Theme.SURFACE);
		border(g, x, y, w, h, Theme.BORDER);
	}

	/** Draws a white line icon in the given color at its native size. */
	public static void icon(GuiGraphicsExtractor g, Identifier icon, int x, int y, int w, int h, int color) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, icon, x, y, w, h, color);
	}

	public static int[] size(Identifier icon) {
		String p = icon.getPath();
		return switch (p.substring(p.lastIndexOf('/') + 1)) {
			case "settings", "info", "trash", "open", "star", "star_empty" -> new int[]{11, 11};
			case "folder" -> new int[]{11, 9};
			case "note" -> new int[]{11, 10};
			case "arrow_left", "arrow_right" -> new int[]{6, 11};
			case "expand_right" -> new int[]{3, 5};
			case "expand_down" -> new int[]{5, 3};
			case "star_small" -> new int[]{9, 8};
			case "dot" -> new int[]{5, 5};
			case "check" -> new int[]{9, 6};
			case "freecam" -> new int[]{9, 7};
			case "sort" -> new int[]{6, 8};
			default -> new int[]{9, 9};
		};
	}

	/** Icon centered in a box. */
	public static void iconCentered(GuiGraphicsExtractor g, Identifier icon, int x, int y, int w, int h, int color) {
		int[] s = size(icon);
		icon(g, icon, x + (w - s[0]) / 2, y + (h - s[1]) / 2, s[0], s[1], color);
	}

	public static String ellipsize(Font font, String s, int maxW) {
		if (maxW <= 0) {
			return "";
		}
		if (font.width(s) <= maxW) {
			return s;
		}
		return font.plainSubstrByWidth(s, Math.max(0, maxW - font.width("…"))) + "…";
	}

	/** Height of a settings row whose description wraps to fit {@code textW}: 30 for one line, +10 per extra line. */
	public static int rowHeight(Font font, String description, int textW) {
		int lines = description == null || description.isEmpty() ? 1 : Math.max(1, font.split(net.minecraft.network.chat.Component.literal(description), Math.max(20, textW)).size());
		return 20 + 10 * lines;
	}

	/** Title (ellipsized) and a wrapped description, as in every settings row. */
	public static void rowText(GuiGraphicsExtractor g, Font font, String title, String description, int color, int x, int y, int textW) {
		g.text(font, ellipsize(font, title, textW), x, y + 6, Theme.TEXT, false);
		if (description == null || description.isEmpty()) {
			return;
		}
		int ly = y + 17;
		for (var line : font.split(net.minecraft.network.chat.Component.literal(description), Math.max(20, textW))) {
			g.text(font, line, x, ly, color, false);
			ly += 10;
		}
	}
}
