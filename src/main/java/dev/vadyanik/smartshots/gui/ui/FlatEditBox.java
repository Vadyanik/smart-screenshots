package dev.vadyanik.smartshots.gui.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Borderless EditBox; {@link #drawFrame} paints the flat field behind it. */
public final class FlatEditBox {
	private FlatEditBox() {
	}

	/** Creates a text field placed inside a frame at (x, y, w, h); leaves room for an optional leading icon. */
	public static EditBox create(Font font, int x, int y, int w, int h, boolean leadingIcon, Component narration) {
		int pad = leadingIcon ? 20 : 7;
		EditBox box = new EditBox(font, x + pad, y + (h - 8) / 2, w - pad - 6, 10, narration);
		box.setBordered(false);
		box.setTextColor(Theme.TEXT);
		return box;
	}

	public static void drawFrame(GuiGraphicsExtractor g, EditBox box, int x, int y, int w, int h, @Nullable Identifier icon) {
		boolean focused = box.isFocused();
		Ui.rect(g, x, y, w, h, Theme.SURFACE_2);
		Ui.border(g, x, y, w, h, focused ? Theme.alpha(Theme.ACCENT, 0.8f) : Theme.BORDER);
		if (icon != null) {
			int[] s = Ui.size(icon);
			Ui.icon(g, icon, x + 7, y + (h - s[1]) / 2, s[0], s[1], focused ? Theme.ACCENT : Theme.TEXT_FAINT);
		}
	}
}
