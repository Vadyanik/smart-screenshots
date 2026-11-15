package dev.vadyanik.smartshots.gui.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Base for small modal dialogs: dims the previous screen and draws a centered card. */
public abstract class DialogScreen extends Screen {
	protected final @Nullable Screen background;
	protected int cardX;
	protected int cardY;
	protected int cardW;
	protected int cardH;

	protected DialogScreen(Component title, @Nullable Screen background) {
		super(title);
		this.background = background;
	}

	protected void layoutCard(int w, int h) {
		cardW = Math.min(w, width - 20);
		cardH = h;
		cardX = (width - cardW) / 2;
		cardY = (height - cardH) / 2;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (background != null) {
			background.extractBackground(g, -1, -1, a);
			background.extractRenderState(g, -1, -1, a);
			g.nextStratum();
		} else if (minecraft.level == null) {
			extractPanorama(g, a);
		}
		g.fill(0, 0, width, height, 0xB0050608);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Ui.card(g, cardX, cardY, cardW, cardH);
		g.text(font, title, cardX + 12, cardY + 12, Theme.TEXT, false);
		extractDialog(g, mouseX, mouseY, a);
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	protected void extractDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
