package dev.vadyanik.smartshots.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** A settings row: title + description on the left, a switch on the right. The whole row is clickable. */
public class FlatToggle extends Button {
	private final Supplier<Boolean> get;
	private final Component description;
	private float anim;

	public FlatToggle(int x, int y, int w, Component title, Component description, Supplier<Boolean> get, Consumer<Boolean> set) {
		super(x, y, w, 30, title, b -> set.accept(!get.get()), DEFAULT_NARRATION);
		this.get = get;
		this.description = description;
		this.anim = get.get() ? 1 : 0;
		setHeight(Ui.rowHeight(Minecraft.getInstance().font, description.getString(), textWidth(w)));
	}

	private static int textWidth(int w) {
		return w - 22 - 28;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean on = get.get();
		anim += ((on ? 1 : 0) - anim) * 0.35f;
		if (isHoveredOrFocused()) {
			Ui.rect(g, x, y, w, h, Theme.HOVER);
		}
		var font = Minecraft.getInstance().font;
		int swW = 22;
		int swH = 12;
		Ui.rowText(g, font, getMessage().getString(), description.getString(), Theme.TEXT_FAINT, x + 8, y, textWidth(w));

		int sx = x + w - swW - 8;
		int sy = y + (h - swH) / 2;
		int track = blend(Theme.SURFACE_3, Theme.ACCENT, anim);
		Ui.rect(g, sx, sy, swW, swH, track);
		int knob = 8;
		int kx = sx + 2 + Math.round((swW - knob - 4) * anim);
		Ui.rect(g, kx, sy + 2, knob, knob, on ? 0xFF0D1220 : Theme.TEXT_DIM);
	}

	static int blend(int c1, int c2, float t) {
		int a = (int) ((c1 >>> 24) + ((c2 >>> 24) - (c1 >>> 24)) * t);
		int r = (int) (((c1 >> 16) & 255) + (((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * t);
		int gg = (int) (((c1 >> 8) & 255) + (((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * t);
		int b = (int) ((c1 & 255) + ((c2 & 255) - (c1 & 255)) * t);
		return (a << 24) | (r << 16) | (gg << 8) | b;
	}
}
