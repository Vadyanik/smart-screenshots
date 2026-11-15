package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ui.DialogScreen;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatEditBox;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.search.Names;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.WorldContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Caption from a template with the shot's date, place and coordinates, placed in a corner. */
public class CaptionScreen extends DialogScreen {
	private static final String[] PRESETS = {
		"{date} {time} · {world} · {x} {y} {z} · {biome}",
		"{x} {y} {z}",
		"{date}",
		"{player} · {world} · {dimension}",
	};
	private final Library.Shot shot;
	private final BiConsumer<String, Integer> onAdd;
	private EditBox box;
	private int corner = SmartShotsConfig.get().captionCorner;

	public CaptionScreen(net.minecraft.client.gui.screens.Screen parent, Library.Shot shot, BiConsumer<String, Integer> onAdd) {
		super(Component.translatable("smartshots.caption.title"), parent);
		this.shot = shot;
		this.onAdd = onAdd;
	}

	@Override
	protected void init() {
		layoutCard(360, 196);
		String old = box != null ? box.getValue() : SmartShotsConfig.get().captionTemplate;
		box = FlatEditBox.create(font, cardX + 12, cardY + 30, cardW - 24, 20, false, title);
		box.setMaxLength(300);
		box.setValue(old);
		addRenderableWidget(box);
		setInitialFocus(box);
		int x = cardX + 12;
		int y = cardY + 56;
		for (int i = 0; i < PRESETS.length; i++) {
			String p = PRESETS[i];
			Component label = Component.translatable("smartshots.caption.preset." + i);
			int w = font.width(label) + 12;
			addRenderableWidget(FlatButton.text(x, y, w, label, FlatButton.Style.FILLED, b -> box.setValue(p)));
			x += w + 4;
		}
		int cy = cardY + 108;
		String[] corners = {"↖", "↗", "↙", "↘"};
		for (int i = 0; i < 4; i++) {
			int c = i;
			FlatButton b = addRenderableWidget(FlatButton.text(cardX + 12 + 70 + i * 26, cy, 22, Component.literal(corners[i]), FlatButton.Style.GHOST, btn -> {
				corner = c;
				rebuildWidgets();
			}));
			b.setSelected(corner == i);
		}
		int bw = (cardW - 24 - 6) / 2;
		int by = cardY + cardH - 32;
		addRenderableWidget(FlatButton.text(cardX + 12, by, bw, CommonComponents.GUI_CANCEL, FlatButton.Style.FILLED, b -> onClose()));
		addRenderableWidget(FlatButton.text(cardX + 12 + bw + 6, by, bw, Component.translatable("smartshots.caption.add"), FlatButton.Style.ACCENT, b -> add()));
	}

	private void add() {
		SmartShotsConfig c = SmartShotsConfig.get();
		c.captionTemplate = box.getValue();
		c.captionCorner = corner;
		SmartShotsConfig.save();
		String text = resolve(box.getValue(), shot);
		onClose();
		if (!text.isBlank()) {
			onAdd.accept(text, corner);
		}
	}

	/** Fills the placeholders with what is known about the shot. */
	static String resolve(String template, Library.Shot shot) {
		ShotMeta m = shot.meta;
		Locale loc = GalleryScreen.gameLocale();
		Instant t = Instant.ofEpochMilli(m.time > 0 ? m.time : System.currentTimeMillis());
		String date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(loc).withZone(ZoneId.systemDefault()).format(t);
		String time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(t);
		boolean pos = m.dimension != null;
		String player = Minecraft.getInstance().getUser() != null ? Minecraft.getInstance().getUser().getName() : "";
		return template
			.replace("{date}", date)
			.replace("{time}", time)
			.replace("{x}", pos ? String.format(Locale.ROOT, "%.0f", m.x) : "?")
			.replace("{y}", pos ? String.format(Locale.ROOT, "%.0f", m.y) : "?")
			.replace("{z}", pos ? String.format(Locale.ROOT, "%.0f", m.z) : "?")
			.replace("{world}", ShotInfo.where(shot))
			.replace("{dimension}", WorldContext.dimensionName(m.dimension))
			.replace("{biome}", m.biome != null ? Names.biome(m.biome) : "?")
			.replace("{player}", player)
			.replaceAll("\\s+·\\s+(·\\s+)+", " · ")
			.strip();
	}

	@Override
	protected void extractDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		FlatEditBox.drawFrame(g, box, cardX + 12, cardY + 30, cardW - 24, 20, null);
		g.text(font, Component.translatable("smartshots.caption.corner"), cardX + 12, cardY + 114, Theme.TEXT_DIM, false);
		g.text(font, Component.translatable("smartshots.caption.placeholders"), cardX + 12, cardY + 82, Theme.TEXT_FAINT, false);
		String preview = resolve(box.getValue(), shot);
		g.text(font, Component.translatable("smartshots.caption.preview"), cardX + 12, cardY + 136, Theme.TEXT_DIM, false);
		g.text(font, Ui.ellipsize(font, preview, cardW - 24), cardX + 12, cardY + 148, Theme.TEXT, false);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			add();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(background);
	}
}
