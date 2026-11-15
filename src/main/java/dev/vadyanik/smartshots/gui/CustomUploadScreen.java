package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ui.DialogScreen;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatEditBox;
import dev.vadyanik.smartshots.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** ShareX-style custom uploader: URL, file field, headers and where the link is in the response. */
public class CustomUploadScreen extends DialogScreen {
	private static final String[] KEYS = {"url", "field", "headers", "json"};
	private static final int ROW = 36;
	private final Screen parent;
	private final EditBox[] boxes = new EditBox[KEYS.length];

	public CustomUploadScreen(Screen parent) {
		super(Component.translatable("smartshots.custom_upload.title"), parent);
		this.parent = parent;
	}

	@Override
	protected void init() {
		layoutCard(340, 30 + KEYS.length * ROW + 40);
		SmartShotsConfig c = SmartShotsConfig.get();
		String[] values = {c.customUploadUrl, c.customUploadField, c.customUploadHeaders, c.customUploadJsonPath};
		for (int i = 0; i < KEYS.length; i++) {
			String old = boxes[i] != null ? boxes[i].getValue() : values[i];
			EditBox box = FlatEditBox.create(font, cardX + 12, boxY(i), cardW - 24, 20, false,
				Component.translatable("smartshots.custom_upload." + KEYS[i]));
			box.setMaxLength(1000);
			box.setValue(old);
			box.setHint(Component.translatable("smartshots.custom_upload." + KEYS[i] + ".hint").withColor(Theme.TEXT_FAINT));
			boxes[i] = addRenderableWidget(box);
		}
		setInitialFocus(boxes[0]);
		int bw = (cardW - 24 - 6) / 2;
		int by = cardY + cardH - 32;
		addRenderableWidget(FlatButton.text(cardX + 12, by, bw, CommonComponents.GUI_CANCEL, FlatButton.Style.FILLED, b -> onClose()));
		addRenderableWidget(FlatButton.text(cardX + 12 + bw + 6, by, bw, Component.translatable("smartshots.button.save"), FlatButton.Style.ACCENT, b -> save()));
	}

	private int boxY(int i) {
		return cardY + 28 + i * ROW + 11;
	}

	private void save() {
		SmartShotsConfig c = SmartShotsConfig.get();
		c.customUploadUrl = boxes[0].getValue();
		c.customUploadField = boxes[1].getValue();
		c.customUploadHeaders = boxes[2].getValue();
		c.customUploadJsonPath = boxes[3].getValue();
		if (!c.customUploadUrl.isBlank()) {
			c.uploadService = SmartShotsConfig.UploadService.CUSTOM;
		}
		c.sanitize();
		SmartShotsConfig.save();
		onClose();
	}

	@Override
	protected void extractDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		for (int i = 0; i < KEYS.length; i++) {
			g.text(font, Component.translatable("smartshots.custom_upload." + KEYS[i]), cardX + 12, boxY(i) - 11, Theme.TEXT_DIM, false);
			FlatEditBox.drawFrame(g, boxes[i], cardX + 12, boxY(i), cardW - 24, 20, null);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
