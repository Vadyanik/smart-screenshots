package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.gui.ui.DialogScreen;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatEditBox;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.storage.InstanceMeta;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Rename a world/server or a sub-world. Only the display name changes; folders stay stable. */
public class RenameScreen extends DialogScreen {
	private final GalleryScreen parent;
	private final Library.Node node;
	private EditBox box;

	public RenameScreen(GalleryScreen parent, Library.Node node) {
		super(Component.translatable(node.kind == Library.Kind.INSTANCE ? "smartshots.rename.world" : "smartshots.rename.subworld"), parent);
		this.parent = parent;
		this.node = node;
	}

	@Override
	protected void init() {
		layoutCard(280, 118);
		String old = box != null ? box.getValue() : node.name;
		box = FlatEditBox.create(font, cardX + 12, cardY + 30, cardW - 24, 22, false, title);
		box.setMaxLength(64);
		box.setValue(old);
		addRenderableWidget(box);
		setInitialFocus(box);
		int bw = (cardW - 24 - 6) / 2;
		int by = cardY + cardH - 32;
		addRenderableWidget(FlatButton.text(cardX + 12, by, bw, CommonComponents.GUI_CANCEL, FlatButton.Style.FILLED, b -> onClose()));
		addRenderableWidget(FlatButton.text(cardX + 12 + bw + 6, by, bw, Component.translatable("smartshots.button.save"), FlatButton.Style.ACCENT, b -> save()));
	}

	private void save() {
		String name = box.getValue().strip();
		if (!name.isEmpty()) {
			if (node.kind == Library.Kind.INSTANCE) {
				InstanceMeta meta = ScreenshotStore.instanceMeta(node.dir);
				meta.displayName = name;
				ScreenshotStore.saveInstanceMeta(node.dir);
			} else if (node.kind == Library.Kind.LOCATION && node.parent != null) {
				InstanceMeta meta = ScreenshotStore.instanceMeta(node.parent.dir);
				InstanceMeta.SubWorld sub = node.fingerprint != null ? meta.worlds.get(node.fingerprint) : null;
				if (sub == null) {
					sub = new InstanceMeta.SubWorld();
					sub.folder = node.dir.getFileName().toString();
					meta.worlds.put("manual:" + sub.folder, sub);
				}
				sub.displayName = name;
				sub.renamed = true;
				ScreenshotStore.saveInstanceMeta(node.parent.dir);
			}
			node.name = name;
			GalleryScreen.markDirty();
		}
		onClose();
	}

	@Override
	protected void extractDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		FlatEditBox.drawFrame(g, box, cardX + 12, cardY + 30, cardW - 24, 22, null);
		g.text(font, Component.translatable("smartshots.rename.hint"), cardX + 12, cardY + 58, Theme.TEXT_FAINT, false);
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
		parent.refreshAfterEdit();
		minecraft.gui.setScreen(parent);
	}
}
