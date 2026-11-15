package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.gui.ui.DialogScreen;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.storage.Library;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class ConfirmDeleteScreen extends DialogScreen {
	private final ViewerScreen viewer;
	private final Library.Shot shot;
	private final Runnable onConfirm;

	public ConfirmDeleteScreen(ViewerScreen viewer, Library.Shot shot, Runnable onConfirm) {
		super(Component.translatable("smartshots.delete.title"), viewer);
		this.viewer = viewer;
		this.shot = shot;
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		layoutCard(260, 132);
		int bw = (cardW - 24 - 6) / 2;
		int by = cardY + cardH - 32;
		addRenderableWidget(FlatButton.text(cardX + 12, by, bw, CommonComponents.GUI_CANCEL, FlatButton.Style.FILLED, b -> onClose()));
		addRenderableWidget(FlatButton.text(cardX + 12 + bw + 6, by, bw, Component.translatable("smartshots.delete.yes"),
			FlatButton.Style.DANGER, b -> onConfirm.run()));
	}

	@Override
	protected void extractDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int thumbH = 52;
		int thumbW = thumbH * 16 / 9;
		int tx = cardX + 12;
		int ty = cardY + 28;
		Textures.Tex tex = Textures.thumb(shot.file);
		g.fill(tx, ty, tx + thumbW, ty + thumbH, Theme.SURFACE_2);
		if (tex.ready()) {
			Textures.drawCover(g, tex, tx, ty, thumbW, thumbH);
		}
		int x = tx + thumbW + 10;
		int w = cardX + cardW - 12 - x;
		int y = ty + 2;
		for (var line : font.split(Component.translatable("smartshots.delete.message", shot.file.getFileName().toString()), w)) {
			g.text(font, line, x, y, Theme.TEXT_DIM, false);
			y += 10;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			onConfirm.run();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(viewer);
	}
}
