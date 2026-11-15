package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.search.Names;
import dev.vadyanik.smartshots.integration.XaeroBridge;
import dev.vadyanik.smartshots.share.ShareService;
import dev.vadyanik.smartshots.storage.EditOp;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMask;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.Trigger;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full-size viewer with zoom, info panel and clickable "what's on the shot" tags.
 *
 * <p>Things on the shot can be outlined: hovering does nothing (no accidental flashes), LMB on a
 * chip or the image toggles a persistent highlight (also baked into the PNG, see {@link Highlights}),
 * Shift+LMB (or RMB on the image) searches the gallery for shots with the same thing.
 */
public class ViewerScreen extends Screen {
	private static final int TOP_H = 32;
	private static final int CHIP_H = 16;
	private static final int ICON = 12;
	/** A LMB click on the image waits this long so a double click (zoom) doesn't toggle anything. */
	private static final long PICK_DELAY_MS = 260;
	private static final double DRAG_SLOP = 3;
	private static boolean showInfo = true;

	private final GalleryScreen gallery;
	private final List<Library.Shot> shots;
	private int index;

	private float zoom = 1;
	private double panX;
	private double panY;
	private boolean dragging;
	private int infoW;
	private double infoScroll;
	private int infoContentH;
	private final List<TagChip> tagChips = new ArrayList<>();
	private FlatButton favButton;
	private FlatButton copyButton;
	private FlatButton shareButton;
	/** Last share result line and when it came (0 = none). */
	private Component shareStatus;
	private boolean shareOk;
	private long shareAt;
	private long copiedAt;
	/** Image copy: when it finished (0 = not yet / none) and whether it worked. */
	private long imageCopyAt;
	private boolean imageCopyOk;

	private boolean pressOnImage;
	private int pressButton;
	private double pressX;
	private double pressY;
	private boolean pressMoved;
	private String pendingPick;
	private long pendingPickAt;

	/** {@code key} is a {@link ShotMask} key for things that can be outlined, else null. */
	private record TagChip(int x, int y, int w, int h, String query, String key) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public ViewerScreen(GalleryScreen gallery, List<Library.Shot> shots, int index) {
		super(Component.translatable("smartshots.viewer.title"));
		this.gallery = gallery;
		this.shots = shots;
		this.index = Mth.clamp(index, 0, Math.max(0, shots.size() - 1));
	}

	private Library.Shot shot() {
		return shots.get(index);
	}

	@Override
	protected void init() {
		PlayerNames.clearCache();
		infoW = showInfo ? Mth.clamp(width / 3, 150, 210) : 0;
		addRenderableWidget(new FlatButton(8, 6, 20, 20, Component.translatable("smartshots.viewer.back"), Ui.ARROW_LEFT,
			FlatButton.Style.GHOST, false, b -> onClose()));
		int x = width - 8 - 20;
		addRenderableWidget(FlatButton.icon(x, 6, Ui.INFO, Component.translatable("smartshots.viewer.info"), b -> {
			showInfo = !showInfo;
			rebuildWidgets();
		}).setSelected(showInfo));
		x -= 22;
		addRenderableWidget(FlatButton.icon(x, 6, Ui.TRASH, Component.translatable("smartshots.viewer.delete"), b -> confirmDelete()));
		x -= 22;
		addRenderableWidget(FlatButton.icon(x, 6, Ui.FOLDER, Component.translatable("smartshots.viewer.open_folder"),
			b -> Blaze3D.openPath(shot().file.getParent())));
		x -= 22;
		addRenderableWidget(FlatButton.icon(x, 6, Ui.OPEN, Component.translatable("smartshots.viewer.open_file"), b -> Blaze3D.openPath(shot().file)));
		x -= 22;
		copyButton = addRenderableWidget(FlatButton.icon(x, 6, Ui.COPY, Component.translatable("smartshots.viewer.copy"), b -> copyImage()));
		x -= 22;
		shareButton = addRenderableWidget(FlatButton.icon(x, 6, SmartIcons.SHARE, Component.translatable("smartshots.viewer.share"), b -> share()));
		x -= 22;
		addRenderableWidget(FlatButton.icon(x, 6, SmartIcons.EDIT, Component.translatable("smartshots.viewer.edit"), b -> openEditor()));
		x -= 22;
		if (XaeroBridge.worldMapLoaded()) {
			addRenderableWidget(FlatButton.icon(x, 6, SmartIcons.MAP, Component.translatable("smartshots.viewer.map"), b -> openMap()));
			x -= 22;
		}
		addRenderableWidget(FlatButton.icon(x, 6, Ui.NOTE, Component.translatable("smartshots.viewer.note"),
			b -> openNote()));
		x -= 22;
		favButton = addRenderableWidget(FlatButton.icon(x, 6, Ui.STAR_EMPTY, Component.translatable("smartshots.viewer.favorite"), b -> toggleFavorite()));
		updateFavButton();
	}

	/** Puts the shot (with its baked highlights, as on disk) on the system clipboard. */
	public void copyImage() {
		if (shots.isEmpty()) {
			return;
		}
		imageCopyAt = 0;
		ClipboardImage.copy(shot().file, ok -> {
			imageCopyOk = ok;
			imageCopyAt = System.currentTimeMillis();
		});
	}

	public void openEditor() {
		if (!shots.isEmpty()) {
			minecraft.gui.setScreen(new EditorScreen(this, shot()));
		}
	}

	/** Uploads the shot (asks once before the first upload) and copies the link. */
	public void share() {
		if (shots.isEmpty()) {
			return;
		}
		Library.Shot s = shot();
		ShareConfirmScreen.confirmThen(this, () -> {
			shareStatus = Component.translatable("smartshots.share.uploading", ShareService.serviceName());
			shareOk = true;
			shareAt = System.currentTimeMillis();
			ShareService.share(s.file, s.meta, true, r -> {
				shareStatus = ShareService.status(r, true);
				shareOk = r.ok();
				shareAt = System.currentTimeMillis();
			});
		});
	}

	/** Xaero's World Map centered on the shot (only for the world and dimension being played). */
	public void openMap() {
		if (shots.isEmpty() || !XaeroBridge.worldMapLoaded()) {
			return;
		}
		ShotMeta m = shot().meta;
		if (m.dimension == null || !XaeroBridge.isCurrentPlace(shot().file, m)) {
			shareStatus = Component.translatable("smartshots.viewer.map.elsewhere");
			shareOk = false;
			shareAt = System.currentTimeMillis();
			return;
		}
		if (!XaeroBridge.openMap(this, (int) Math.floor(m.x), (int) Math.floor(m.z))) {
			shareStatus = Component.translatable("smartshots.viewer.map.failed");
			shareOk = false;
			shareAt = System.currentTimeMillis();
		}
	}

	public void openNote() {
		minecraft.gui.setScreen(new NoteScreen(this, shot()));
	}

	private void updateFavButton() {
		if (favButton != null && !shots.isEmpty()) {
			boolean fav = shot().meta.favorite;
			favButton.setIcon(fav ? Ui.STAR : Ui.STAR_EMPTY).setIconColor(fav ? Theme.GOLD : Theme.TEXT_DIM);
		}
	}

	private void toggleFavorite() {
		ShotMeta m = shot().meta;
		m.favorite = !m.favorite;
		ScreenshotStore.updateMeta(shot().file, m);
		XaeroBridge.refresh();
		updateFavButton();
	}

	void saveNote(String note) {
		shot().meta.note = note;
		ScreenshotStore.updateMeta(shot().file, shot().meta);
		XaeroBridge.refresh();
		GalleryScreen.markDirty();
	}

	public void confirmDelete() {
		Library.Shot s = shot();
		minecraft.gui.setScreen(new ConfirmDeleteScreen(this, s, () -> {
			Textures.forget(s.file);
			ScreenshotStore.delete(s.file, () -> {
				GalleryScreen.markDirty();
				XaeroBridge.refresh();
				if (GalleryScreen.library != null) {
					GalleryScreen.library.shots.remove(s);
					s.location.shots.remove(s);
				}
			});
			shots.remove(s);
			if (shots.isEmpty()) {
				gallery.refreshAfterEdit();
				minecraft.gui.setScreen(gallery);
				return;
			}
			index = Math.min(index, shots.size() - 1);
			resetView();
			minecraft.gui.setScreen(this);
		}));
	}

	/** Background work changed a shot's metadata; update our copies the gallery didn't cover. */
	void onMetaUpdated(java.nio.file.Path file, java.util.function.Consumer<ShotMeta> change, java.util.Set<ShotMeta> done) {
		for (Library.Shot s : shots) {
			if (s.file.equals(file) && done.add(s.meta)) {
				change.accept(s.meta);
				s.refreshDoc();
			}
		}
	}

	private void go(int delta) {
		if (shots.isEmpty()) {
			return;
		}
		index = Math.floorMod(index + delta, shots.size());
		resetView();
		pendingPick = null;
		updateFavButton();
	}

	private void resetView() {
		zoom = 1;
		panX = 0;
		panY = 0;
		infoScroll = 0;
	}

	@Override
	public void onClose() {
		Highlights.releaseAll();
		gallery.refreshAfterEdit();
		minecraft.gui.setScreen(gallery);
	}

	private void search(String query) {
		Highlights.releaseAll();
		gallery.searchFor(query + " ");
		minecraft.gui.setScreen(gallery);
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xFF0A0B0E);
	}

	/**
	 * {x, y, w, h} of the visible (cropped) image, the area {x, y, w, h}, and where the whole
	 * uncropped image goes {x, y, w, h}.
	 */
	private int[] imageRect(Textures.Tex tex) {
		int areaX = 10;
		int areaY = TOP_H + 4;
		int areaW = width - infoW - 20;
		int areaH = height - areaY - 10;
		float[] c = crop(tex);
		float cw = c == null ? 1 : c[2] - c[0];
		float ch = c == null ? 1 : c[3] - c[1];
		float imgAspect = tex.width * cw / Math.max(1f, tex.height * ch);
		int w = areaW;
		int h = Math.round(w / imgAspect);
		if (h > areaH) {
			h = areaH;
			w = Math.round(h * imgAspect);
		}
		w = Math.round(w * zoom);
		h = Math.round(h * zoom);
		int x = areaX + (areaW - w) / 2 + (int) panX;
		int y = areaY + (areaH - h) / 2 + (int) panY;
		if (c == null) {
			return new int[]{x, y, w, h, areaX, areaY, areaW, areaH, x, y, w, h};
		}
		int fw = Math.round(w / cw);
		int fh = Math.round(h / ch);
		return new int[]{x, y, w, h, areaX, areaY, areaW, areaH, x - Math.round(c[0] * fw), y - Math.round(c[1] * fh), fw, fh};
	}

	/** The crop to apply when drawing {@code tex}: the full image is uncropped, the thumbnail already is. */
	private float[] crop(Textures.Tex tex) {
		if (shots.isEmpty() || !tex.fullSize) {
			return null;
		}
		return EditOp.crop(shot().meta.edits);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (shots.isEmpty()) {
			super.extractRenderState(g, mouseX, mouseY, a);
			return;
		}
		Library.Shot s = shot();
		ShotMask mask = Highlights.mask(s);
		if (pendingPick != null && System.currentTimeMillis() - pendingPickAt > PICK_DELAY_MS) {
			String k = pendingPick;
			pendingPick = null;
			if (Highlights.has(k)) {
				Highlights.toggle(s, k);
			}
		}

		// Top bar
		int tx = 34;
		String date = ShotInfo.dateTime(s.meta.time);
		g.text(font, date, tx, 12, Theme.TEXT, false);
		tx += font.width(date) + 8;
		Trigger t = s.meta.trigger();
		Ui.icon(g, Ui.DOT, tx, 13, 5, 5, t.color);
		g.text(font, t.label(), tx + 8, 12, Theme.TEXT_DIM, false);
		tx += font.width(t.label()) + 16;
		String counter = (index + 1) + " / " + shots.size();
		g.text(font, counter, tx, 12, Theme.TEXT_FAINT, false);
		boolean copyFresh = System.currentTimeMillis() - imageCopyAt < 2500;
		boolean busy = ShareService.busy(s.file);
		if (busy || shareStatus != null && System.currentTimeMillis() - shareAt < 4000) {
			int sx = tx + font.width(counter) + 12;
			int maxW = (shareButton != null ? Math.min(shareButton.getX(), favButton.getX()) : width) - sx - 6;
			Component msg = busy ? Component.translatable("smartshots.share.uploading", ShareService.serviceName()) : shareStatus;
			g.text(font, Ui.ellipsize(font, msg.getString(), maxW), sx, 12, busy ? Theme.TEXT_DIM : shareOk ? Theme.GREEN : Theme.DANGER, false);
		} else if (copyFresh) {
			String msg = Component.translatable(imageCopyOk ? "smartshots.copy.ok" : "smartshots.copy.fail").getString();
			g.text(font, msg, tx + font.width(counter) + 12, 12, imageCopyOk ? Theme.GREEN : Theme.DANGER, false);
		}
		if (copyButton != null) {
			copyButton.setIcon(copyFresh && imageCopyOk ? Ui.CHECK : Ui.COPY)
				.setIconColor(copyFresh ? (imageCopyOk ? Theme.GREEN : Theme.DANGER) : Theme.TEXT_DIM);
		}

		// Image (hi-res when ready, the thumbnail meanwhile). The full image is the pristine one with
		// the edits drawn in; outlines are separate overlays so toggling is instant, and the crop
		// is applied here (the scissor shows only the cropped part of the full image).
		Textures.Tex img = currentTex();
		int areaCx = (width - infoW) / 2;
		if (img.ready()) {
			int[] r = imageRect(img);
			int sx0 = Math.max(r[4], r[0]);
			int sy0 = Math.max(r[5], r[1]);
			int sx1 = Math.min(r[4] + r[6], r[0] + r[2]);
			int sy1 = Math.min(r[5] + r[7], r[1] + r[3]);
			g.enableScissor(sx0, sy0, Math.max(sx0, sx1), Math.max(sy0, sy1));
			Textures.drawStretched(g, img, r[8], r[9], r[8] + r[10], r[9] + r[11]);
			if (mask != null && img.fullSize) { // the thumbnail already has them baked in
				for (String key : s.meta.highlights != null ? s.meta.highlights : List.<String>of()) {
					Highlights.draw(g, Highlights.overlay(key, false), r[8], r[9], r[10], r[11]);
				}
			}
			g.disableScissor();
			if (zoom != 1) {
				String z = Math.round(zoom * 100) + "%";
				Ui.rect(g, r[4] + 6, r[5] + r[7] - 20, font.width(z) + 10, 14, 0xC0000000);
				g.text(font, z, r[4] + 11, r[5] + r[7] - 17, Theme.TEXT, false);
			}
		} else if (img.failed()) {
			g.centeredText(font, Component.translatable("smartshots.viewer.failed"), areaCx, height / 2, Theme.DANGER);
		} else {
			g.centeredText(font, Component.translatable("smartshots.gallery.loading"), areaCx, height / 2, Theme.TEXT_FAINT);
		}

		// Prev / next
		int areaW = width - infoW;
		if (shots.size() > 1) {
			arrow(g, 10, Ui.ARROW_LEFT, mouseX < 44 && mouseY > TOP_H && mouseX < areaW);
			arrow(g, areaW - 38, Ui.ARROW_RIGHT, mouseX > areaW - 44 && mouseX < areaW && mouseY > TOP_H);
		}

		if (showInfo) {
			renderInfo(g, s, mask, mouseX, mouseY);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private Textures.Tex currentTex() {
		Textures.Tex full = Textures.full(shot().file, shot().meta.edits);
		return full.ready() ? full : Textures.thumb(shot().file);
	}

	private boolean inArrowZone(double mx, double my) {
		int areaW = width - infoW;
		return shots.size() > 1 && my > TOP_H && (mx < 44 || mx > areaW - 44 && mx < areaW);
	}

	/** The most specific mask key under a screen point, or null. */
	private String keyAt(double mx, double my) {
		ShotMask mask = Highlights.mask(shot());
		Textures.Tex tex = currentTex();
		if (mask == null || !tex.ready() || my <= TOP_H || mx >= width - infoW) {
			return null;
		}
		int[] r = imageRect(tex);
		if (mx < r[4] || my < r[5] || mx >= r[4] + r[6] || my >= r[5] + r[7]
			|| mx < r[0] || my < r[1] || mx >= r[0] + r[2] || my >= r[1] + r[3]) {
			return null;
		}
		double u = (mx - r[8]) / r[10];
		double v = (my - r[9]) / r[11];
		if (u < 0 || v < 0 || u >= 1 || v >= 1) {
			return null;
		}
		return mask.keyAt((int) (u * mask.width), (int) (v * mask.height));
	}

	private void arrow(GuiGraphicsExtractor g, int x, Identifier icon, boolean hover) {
		int cy = (TOP_H + height) / 2;
		Ui.rect(g, x, cy - 14, 28, 28, hover ? 0xC0000000 : 0x60000000);
		Ui.iconCentered(g, icon, x, cy - 14, 28, 28, hover ? Theme.TEXT : Theme.TEXT_DIM);
	}

	private void renderInfo(GuiGraphicsExtractor g, Library.Shot s, ShotMask mask, int mouseX, int mouseY) {
		int x0 = width - infoW;
		g.fill(x0, 0, width, height, Theme.SURFACE);
		g.verticalLine(x0, 0, height, Theme.BORDER);
		tagChips.clear();
		g.enableScissor(x0, TOP_H, width, height);
		int pad = 12;
		int x = x0 + pad;
		int w = infoW - pad * 2;
		int y = TOP_H + 6 - (int) infoScroll;
		int startY = y;
		ShotMeta m = s.meta;

		y = section(g, x, y, "smartshots.info.where");
		y = wrapped(g, ShotInfo.where(s), x, y, w, Theme.TEXT);
		if (m.dimension != null) {
			String coords = String.format("%.0f, %.0f, %.0f", m.x, m.y, m.z);
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
			boolean copied = System.currentTimeMillis() - copiedAt < 1500;
			Ui.icon(g, copied ? Ui.CHECK : Ui.COPY, x, y + (copied ? 2 : 0), 9, copied ? 6 : 9, copied ? Theme.GREEN : hover ? Theme.ACCENT : Theme.TEXT_FAINT);
			g.text(font, coords, x + 14, y + 1, hover ? Theme.ACCENT : Theme.TEXT_DIM, false);
			tagChips.add(new TagChip(x, y, w, 12, "\u0000copy", null));
			y += 14;
		}
		if (m.biome != null) {
			y = chipRow(g, m, x, y + 2, w, List.of(new ChipData(Names.biome(m.biome), "biome:" + Highlights.shortId(m.biome), Theme.MINT, null)), mouseX, mouseY);
		}
		y += 8;

		if (!m.advancements.isEmpty()) {
			y = section(g, x, y, "smartshots.info.advancements");
			List<ChipData> adv = new ArrayList<>();
			for (String a : m.advancements) {
				adv.add(new ChipData(a, "adv:" + Highlights.quote(a), Theme.GOLD, null));
			}
			y = chipRow(g, m, x, y, w, adv, mouseX, mouseY) + 8;
		}

		// Things on the shot: each chip can be outlined on the image.
		List<ChipData> players = new ArrayList<>();
		for (String p : m.players) {
			String key = ShotMask.PLAYER + p;
			players.add(new ChipData(p, Highlights.query(key), Highlights.color(key), key, PlayerNames.styled(m, p)));
		}
		List<ChipData> entities = new ArrayList<>();
		// Mobs renamed with a name tag first: each one is its own chip.
		java.util.Set<String> namedTags = new java.util.HashSet<>();
		for (String n : m.named != null ? m.named : List.<String>of()) {
			String key = ShotMask.NAMED + n;
			entities.add(keyChip(key, ShotMask.namedName(n), m));
			namedTags.add("name:" + ShotMask.namedName(n));
		}
		for (Map.Entry<String, Integer> e : m.entities.entrySet()) {
			if (e.getKey().equals("minecraft:player")) {
				continue;
			}
			entities.add(keyChip(ShotMask.ENTITY + e.getKey(), Names.entity(e.getKey(), m.names) + count(e.getValue()), m));
		}
		List<ChipData> items = new ArrayList<>();
		if (m.items != null) {
			for (Map.Entry<String, Integer> e : m.items.entrySet()) {
				items.add(keyChip(ShotMask.ITEM + e.getKey(), Names.item(e.getKey(), m.names) + count(e.getValue()), m));
			}
		}
		List<ChipData> blocks = new ArrayList<>();
		for (String id : m.blocks.keySet()) {
			if (blocks.size() >= 24) {
				break;
			}
			Integer n = m.blockCounts != null ? m.blockCounts.get(id) : null;
			blocks.add(keyChip(ShotMask.BLOCK + id, Names.block(id, m.names) + (n != null ? count(n) : ""), m));
		}
		boolean signs = m.signs != null && !m.signs.isEmpty();

		if (!players.isEmpty() || !entities.isEmpty() || !items.isEmpty() || !blocks.isEmpty() || signs) {
			if (mask != null) {
				y = wrapped(g, Component.translatable("smartshots.info.hint").getString(), x, y, w, Theme.TEXT_FAINT) + 4;
			} else if (Highlights.unavailable(s)) {
				y = wrapped(g, Component.translatable("smartshots.info.no_mask").getString(), x, y, w, Theme.TEXT_FAINT) + 4;
			}
		}
		if (!players.isEmpty()) {
			y = section(g, x, y, "smartshots.info.players");
			y = chipRow(g, m, x, y, w, players, mouseX, mouseY) + 8;
		}
		if (!entities.isEmpty()) {
			y = section(g, x, y, "smartshots.info.entities");
			y = chipRow(g, m, x, y, w, entities, mouseX, mouseY) + 8;
		}
		if (!items.isEmpty()) {
			y = section(g, x, y, "smartshots.info.items");
			y = chipRow(g, m, x, y, w, items, mouseX, mouseY) + 8;
		}
		if (!blocks.isEmpty()) {
			y = section(g, x, y, "smartshots.info.blocks");
			y = chipRow(g, m, x, y, w, blocks, mouseX, mouseY) + 8;
		}

		if (signs) {
			y = section(g, x, y, "smartshots.info.signs");
			for (String text : m.signs) {
				String key = ShotMask.SIGN + text;
				List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal("“" + text + "”"), w - 12);
				int bh = lines.size() * 10 + 6;
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + bh && mouseY > TOP_H;
				boolean selected = highlighted(m, key);
				Ui.rect(g, x, y, w, bh, hover || selected ? Theme.SURFACE_3 : Theme.SURFACE_2);
				if (selected) {
					Ui.border(g, x, y, w, bh, Highlights.SIGN_COLOR);
				}
				g.fill(x, y + 1, x + 2, y + bh - 1, Highlights.SIGN_COLOR);
				int ly = y + 4;
				for (var line : lines) {
					g.text(font, line, x + 8, ly, hover || selected ? Theme.TEXT : Theme.TEXT_DIM, false);
					ly += 10;
				}
				tagChips.add(new TagChip(x, y, w, bh, Highlights.query(key), key));
				y += bh + 3;
			}
			y += 6;
		}

		if (m.chat != null && !m.chat.isEmpty()) {
			y = section(g, x, y, "smartshots.info.chat");
			for (String text : m.chat) {
				List<FormattedCharSequence> lines = font.split(Component.literal(text), w - 8);
				int bh = lines.size() * 10 + 4;
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + bh && mouseY > TOP_H;
				if (hover) {
					Ui.rect(g, x, y, w, bh, Theme.SURFACE_2);
				}
				int ly = y + 2;
				for (var line : lines) {
					g.text(font, line, x + 4, ly, hover ? Theme.TEXT : Theme.TEXT_DIM, false);
					ly += 10;
				}
				String q = text.length() > 40 ? text.substring(0, 40) : text;
				tagChips.add(new TagChip(x, y, w, bh, "chat:\"" + q.replace("\"", "") + "\"", null));
				y += bh + 1;
			}
			y += 8;
		}

		if (m.tags.stream().anyMatch(tg -> !namedTags.contains(tg))) {
			y = section(g, x, y, "smartshots.info.tags");
			List<ChipData> tags = new ArrayList<>();
			for (String tg : m.tags) {
				if (namedTags.contains(tg)) {
					continue; // shown as a mob chip
				}
				tags.add(new ChipData(Names.tag(tg), "tag:" + Highlights.quote(tg), Theme.TEXT_DIM, null));
			}
			y = chipRow(g, m, x, y, w, tags, mouseX, mouseY) + 8;
		}

		if (!m.note.isBlank()) {
			y = section(g, x, y, "smartshots.info.note");
			y = wrapped(g, m.note, x, y, w, Theme.TEXT) + 8;
		}

		y = section(g, x, y, "smartshots.info.file");
		y = wrapped(g, s.file.getFileName().toString(), x, y, w, Theme.TEXT_FAINT);
		if (m.width > 0) {
			g.text(font, m.width + " × " + m.height, x, y, Theme.TEXT_FAINT, false);
			y += 11;
		}
		g.disableScissor();
		infoContentH = y - startY + 12;
	}

	/** {@code key} is a {@link ShotMask} key when the chip stands for something outlinable. */
	private record ChipData(String label, String query, int color, String key, Component styled) {
		ChipData(String label, String query, int color, String key) {
			this(label, query, color, key, null);
		}
	}

	private static ChipData keyChip(String key, String label, ShotMeta m) {
		return new ChipData(label, Highlights.query(key), Highlights.color(key), key);
	}

	private static String count(int n) {
		return n > 1 ? "  " + n : "";
	}

	private static boolean highlighted(ShotMeta m, String key) {
		return m.highlights != null && m.highlights.contains(key);
	}

	private int section(GuiGraphicsExtractor g, int x, int y, String key) {
		g.text(font, Component.translatable(key).getString().toUpperCase(GalleryScreen.gameLocale()), x, y, Theme.TEXT_FAINT, false);
		return y + 12;
	}

	private int wrapped(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
		for (var line : font.split(Component.literal(text), w)) {
			g.text(font, line, x, y, color, false);
			y += 10;
		}
		return y + 2;
	}

	private int chipRow(GuiGraphicsExtractor g, ShotMeta m, int x0, int y, int w, List<ChipData> chips, int mouseX, int mouseY) {
		int x = x0;
		int h = CHIP_H;
		for (ChipData c : chips) {
			boolean pic = c.key != null && ChipIcons.has(c.key);
			// left padding + picture/dot + gap ... right padding
			int lead = pic ? 3 + ICON + 4 : 13;
			String label = c.label;
			FormattedCharSequence styled = c.styled != null ? c.styled.getVisualOrderText() : null;
			int cw = (styled != null ? font.width(styled) : font.width(label)) + lead + 5;
			if (cw > w) {
				if (styled != null) {
					styled = PlayerNames.fit(font, c.styled, w - lead - 5);
				} else {
					label = Ui.ellipsize(font, label, w - lead - 5);
				}
				cw = w;
			}
			if (x + cw > x0 + w && x != x0) {
				x = x0;
				y += h + 3;
			}
			boolean overChip = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + h && mouseY > TOP_H;
			// The mask is loaded but has no pixels for it: listed (met nearby, behind a wall...) yet not visible.
			boolean absent = c.key != null && Highlights.loaded() && !Highlights.has(c.key);
			boolean hover = overChip;
			if (overChip && absent) {
				g.setTooltipForNextFrame(font, font.split(Component.translatable("smartshots.info.not_on_shot"), 200), mouseX, mouseY);
			} else if (overChip && styled != null) {
				g.setTooltipForNextFrame(font, Component.literal(c.label), mouseX, mouseY); // the plain nick
			}
			boolean selected = c.key != null && highlighted(m, c.key);
			Ui.rect(g, x, y, cw, h, hover || selected ? Theme.SURFACE_3 : Theme.SURFACE_2);
			if (selected) {
				Ui.border(g, x, y, cw, h, c.color);
			}
			if (pic) {
				ChipIcons.draw(g, c.key, m, x + 3, y + (h - ICON) / 2, ICON);
			} else {
				Ui.icon(g, Ui.DOT, x + 5, y + (h - 5) / 2, 5, 5, c.color);
			}
			int textColor = absent ? Theme.TEXT_FAINT : hover || selected ? Theme.TEXT : Theme.TEXT_DIM;
			if (styled != null && !absent) {
				g.text(font, styled, x + lead, y + (h - 8) / 2, textColor, false);
			} else {
				g.text(font, styled != null ? styled : Component.literal(label).getVisualOrderText(), x + lead, y + (h - 8) / 2, textColor, false);
			}
			tagChips.add(new TagChip(x, y, cw, h, c.query, c.key));
			x += cw + 3;
		}
		return y + h + 2;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		int button = event.button();
		if (shots.isEmpty()) {
			return false;
		}
		if (showInfo && mx >= width - infoW && my > TOP_H) {
			for (TagChip c : tagChips) {
				if (c.hit(mx, my)) {
					if (c.query.equals("\u0000copy")) {
						if (button != InputConstants.MOUSE_BUTTON_LEFT) {
							return false;
						}
						Button.playButtonClickSound(minecraft.getSoundManager());
						ShotMeta m = shot().meta;
						minecraft.keyboardHandler.setClipboard(String.format("%.0f %.0f %.0f", m.x, m.y, m.z));
						copiedAt = System.currentTimeMillis();
						return true;
					}
					if (button != InputConstants.MOUSE_BUTTON_LEFT) {
						return false;
					}
					// Shift+LMB searches every shot; a plain click only outlines the thing on this one.
					if (event.hasShiftDown()) {
						Button.playButtonClickSound(minecraft.getSoundManager());
						search(c.query);
						return true;
					}
					if (Highlights.has(c.key)) {
						Button.playButtonClickSound(minecraft.getSoundManager());
						Highlights.toggle(shot(), c.key);
						return true;
					}
					return false;
				}
			}
			return false;
		}
		int areaW = width - infoW;
		if (button == InputConstants.MOUSE_BUTTON_LEFT && inArrowZone(mx, my)) {
			go(mx < 44 ? -1 : 1);
			return true;
		}
		if (doubleClick && button == InputConstants.MOUSE_BUTTON_LEFT) {
			// The first click of the pair queued a pick: a double click zooms instead.
			pendingPick = null;
			if (zoom != 1) {
				resetView();
			} else {
				zoomAt(mx, my, 2.5f);
			}
			return true;
		}
		if (my > TOP_H && mx < areaW) {
			pressOnImage = true;
			pressButton = button;
			pressX = mx;
			pressY = my;
			pressMoved = false;
			dragging = button == InputConstants.MOUSE_BUTTON_LEFT;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean click = pressOnImage && !pressMoved && event.button() == pressButton;
		pressOnImage = false;
		dragging = false;
		if (click) {
			// A click without drag picks the thing under the cursor.
			String key = keyAt(event.x(), event.y());
			if (key != null) {
				if (pressButton == InputConstants.MOUSE_BUTTON_RIGHT
					|| pressButton == InputConstants.MOUSE_BUTTON_LEFT && event.hasShiftDown()) {
					Button.playButtonClickSound(minecraft.getSoundManager());
					search(Highlights.query(key));
				} else if (pressButton == InputConstants.MOUSE_BUTTON_LEFT) {
					pendingPick = key;
					pendingPickAt = System.currentTimeMillis();
				}
				return true;
			}
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (pressOnImage && !pressMoved && Math.hypot(event.x() - pressX, event.y() - pressY) > DRAG_SLOP) {
			pressMoved = true;
		}
		if (dragging && zoom > 1) {
			panX += dx;
			panY += dy;
			clampPan();
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (showInfo && x >= width - infoW) {
			int viewH = height - TOP_H;
			infoScroll = Mth.clamp(infoScroll - scrollY * 16, 0, Math.max(0, infoContentH - viewH));
			return true;
		}
		zoomAt(x, y, (float) (zoom * Math.pow(1.2, scrollY)));
		return true;
	}

	private void zoomAt(double mx, double my, float newZoom) {
		newZoom = Mth.clamp(newZoom, 1f, 8f);
		Textures.Tex tex = currentTex();
		if (!tex.ready()) {
			return;
		}
		int[] r = imageRect(tex);
		double cx = r[4] + r[6] / 2.0;
		double cy = r[5] + r[7] / 2.0;
		double relX = mx - cx - panX;
		double relY = my - cy - panY;
		double k = newZoom / zoom;
		panX -= relX * (k - 1);
		panY -= relY * (k - 1);
		zoom = newZoom;
		if (zoom == 1) {
			panX = panY = 0;
		}
		clampPan();
	}

	private void clampPan() {
		Textures.Tex tex = currentTex();
		if (!tex.ready()) {
			return;
		}
		int[] r = imageRect(tex);
		double maxX = Math.max(0, (r[2] - r[6]) / 2.0);
		double maxY = Math.max(0, (r[3] - r[7]) / 2.0);
		panX = Mth.clamp(panX, -maxX, maxX);
		panY = Mth.clamp(panY, -maxY, maxY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isLeft()) {
			go(-1);
			return true;
		}
		if (event.isRight()) {
			go(1);
			return true;
		}
		int key = event.key();
		if (event.isCopy()) {
			copyImage();
			return true;
		}
		if (key == InputConstants.KEY_DELETE) {
			confirmDelete();
			return true;
		}
		if (key == InputConstants.KEY_F) {
			toggleFavorite();
			return true;
		}
		if (key == InputConstants.KEY_E) {
			openEditor();
			return true;
		}
		if (key == InputConstants.KEY_M) {
			openMap();
			return true;
		}
		if (key == InputConstants.KEY_U) {
			share();
			return true;
		}
		if (key == InputConstants.KEY_I) {
			showInfo = !showInfo;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
