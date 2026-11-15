package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.gui.ui.DialogScreen;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatEditBox;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.storage.Library;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Add a searchable note / caption to a screenshot. */
public class NoteScreen extends DialogScreen {
	private final ViewerScreen parent;
	private final Library.Shot shot;
	private EditBox box;

	public NoteScreen(ViewerScreen parent, Library.Shot shot) {
		super(Component.translatable("smartshots.note.title"), parent);
		this.parent = parent;
		this.shot = shot;
	}

	@Override
	protected void init() {
		layoutCard(300, 118);
		String old = box != null ? box.getValue() : shot.meta.note;
		box = FlatEditBox.create(font, cardX + 12, cardY + 30, cardW - 24, 22, false, title);
		box.setMaxLength(200);
		box.setValue(old);
		box.setHint(Component.translatable("smartshots.note.hint").withColor(Theme.TEXT_FAINT));
		addRenderableWidget(box);
		setInitialFocus(box);
		int bw = (cardW - 24 - 6) / 2;
		int by = cardY + cardH - 32;
		addRenderableWidget(FlatButton.text(cardX + 12, by, bw, CommonComponents.GUI_CANCEL, FlatButton.Style.FILLED, b -> onClose()));
		addRenderableWidget(FlatButton.text(cardX + 12 + bw + 6, by, bw, Component.translatable("smartshots.button.save"), FlatButton.Style.ACCENT, b -> save()));
	}

	private void save() {
		parent.saveNote(box.getValue().strip());
		onClose();
	}

	@Override
	protected void extractDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		FlatEditBox.drawFrame(g, box, cardX + 12, cardY + 30, cardW - 24, 22, null);
		g.text(font, Component.translatable("smartshots.note.searchable"), cardX + 12, cardY + 58, Theme.TEXT_FAINT, false);
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
