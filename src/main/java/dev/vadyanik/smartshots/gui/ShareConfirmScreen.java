package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ui.DialogScreen;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.share.ShareService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Asked once before the first upload: shared images are public for anyone with the link. */
public class ShareConfirmScreen extends DialogScreen {
	private final @Nullable Screen parent;
	private final Runnable onYes;

	private ShareConfirmScreen(@Nullable Screen parent, Runnable onYes) {
		super(Component.translatable("smartshots.share.confirm.title"), parent);
		this.parent = parent;
		this.onYes = onYes;
	}

	/** Runs {@code action} right away when the user already agreed, otherwise after asking. */
	public static void confirmThen(@Nullable Screen current, Runnable action) {
		if (SmartShotsConfig.get().shareConfirmed) {
			action.run();
			return;
		}
		Minecraft.getInstance().gui.setScreen(new ShareConfirmScreen(current, action));
	}

	@Override
	protected void init() {
		layoutCard(300, 120);
		int bw = (cardW - 24 - 6) / 2;
		int by = cardY + cardH - 32;
		addRenderableWidget(FlatButton.text(cardX + 12, by, bw, CommonComponents.GUI_CANCEL, FlatButton.Style.FILLED, b -> onClose()));
		addRenderableWidget(FlatButton.text(cardX + 12 + bw + 6, by, bw, Component.translatable("smartshots.share.confirm.yes"),
			FlatButton.Style.ACCENT, b -> yes()));
	}

	private void yes() {
		SmartShotsConfig.get().shareConfirmed = true;
		SmartShotsConfig.save();
		minecraft.gui.setScreen(parent);
		onYes.run();
	}

	@Override
	protected void extractDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int y = cardY + 30;
		for (var line : font.split(Component.translatable("smartshots.share.confirm.message", ShareService.serviceName()), cardW - 24)) {
			g.text(font, line, cardX + 12, y, Theme.TEXT_DIM, false);
			y += 10;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			yes();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
