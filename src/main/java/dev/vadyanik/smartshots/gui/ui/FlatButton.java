package dev.vadyanik.smartshots.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Flat button: text and/or a line icon, ghost / filled / accent / danger variants. */
public class FlatButton extends Button {
	public enum Style { GHOST, FILLED, ACCENT, DANGER }

	private final Style style;
	private @Nullable Identifier icon;
	private int iconColor = Theme.TEXT_DIM;
	private boolean selected;
	private final boolean showLabel;

	public FlatButton(int x, int y, int w, int h, Component label, @Nullable Identifier icon, Style style, boolean showLabel, OnPress onPress) {
		super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
		this.icon = icon;
		this.style = style;
		this.showLabel = showLabel;
	}

	public static FlatButton icon(int x, int y, Identifier icon, Component tooltip, OnPress onPress) {
		FlatButton b = new FlatButton(x, y, 20, 20, tooltip, icon, Style.GHOST, false, onPress);
		b.setTooltip(Tooltip.create(tooltip));
		return b;
	}

	public static FlatButton text(int x, int y, int w, Component label, Style style, OnPress onPress) {
		return new FlatButton(x, y, w, 20, label, null, style, true, onPress);
	}

	public FlatButton setIcon(Identifier icon) {
		this.icon = icon;
		return this;
	}

	public FlatButton setIconColor(int color) {
		this.iconColor = color;
		return this;
	}

	public FlatButton setSelected(boolean selected) {
		this.selected = selected;
		return this;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean hot = isHoveredOrFocused() && active;
		int bg;
		int fg;
		switch (style) {
			case ACCENT -> {
				bg = hot ? 0xFF85B7FF : Theme.ACCENT;
				fg = 0xFF0D1220;
			}
			case DANGER -> {
				bg = hot ? 0x55F2606E : Theme.DANGER_SOFT;
				fg = Theme.DANGER;
			}
			case FILLED -> {
				bg = hot ? Theme.SURFACE_3 : Theme.SURFACE_2;
				fg = Theme.TEXT;
			}
			default -> {
				bg = selected ? Theme.ACCENT_SOFT : hot ? Theme.HOVER : 0;
				fg = selected ? Theme.ACCENT : hot ? Theme.TEXT : Theme.TEXT_DIM;
			}
		}
		if (!active) {
			fg = Theme.TEXT_FAINT;
		}
		if (bg != 0) {
			Ui.rect(g, x, y, w, h, bg);
		}
		if (style == Style.FILLED) {
			Ui.border(g, x, y, w, h, Theme.BORDER);
		}
		if (isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard()) {
			Ui.border(g, x, y, w, h, Theme.ACCENT);
		}
		var font = Minecraft.getInstance().font;
		int iconW = 0;
		int[] is = icon != null ? Ui.size(icon) : new int[]{0, 0};
		if (icon != null) {
			iconW = is[0];
		}
		int ic = style == Style.GHOST && !selected ? (hot ? Theme.TEXT : iconColor) : fg;
		if (showLabel) {
			String label = getMessage().getString();
			int gap = icon != null ? 5 : 0;
			int textW = Math.min(font.width(label), w - 8 - iconW - gap);
			int total = iconW + gap + textW;
			int sx = x + (w - total) / 2;
			if (icon != null) {
				Ui.icon(g, icon, sx, y + (h - is[1]) / 2, is[0], is[1], ic);
			}
			g.text(font, Ui.ellipsize(font, label, textW + 1), sx + iconW + gap, y + (h - 8) / 2, fg, false);
		} else if (icon != null) {
			Ui.iconCentered(g, icon, x, y, w, h, ic);
		}
	}
}
