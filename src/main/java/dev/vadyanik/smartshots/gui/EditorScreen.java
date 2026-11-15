package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.share.ShareService;
import dev.vadyanik.smartshots.storage.EditOp;
import dev.vadyanik.smartshots.storage.EditRenderer;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotBaker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Screenshot editor: pen, marker, lines, arrows, shapes, text, numbered markers, blur, pixelate,
 * spotlight and crop. Every change is saved on its own (metadata + the PNG rebuilt from the
 * untouched original, see {@link ShotBaker}), and every operation stays removable later: undo,
 * the eraser, or "Clear all" — also in a later session.
 *
 * <p>Committed operations are drawn by the same Java2D code as the PNG (the image texture,
 * {@link Textures#full}); the shape being drawn and operations whose texture is still rendering
 * are previewed with GUI primitives.
 */
public class EditorScreen extends Screen {
	private static final int TOP_H = 28;
	private static final int LEFT_W = 52;
	private static final int BOTTOM_H = 26;
	private static final long SAVE_DELAY_MS = 350;
	private static final int MAX_UNDO = 200;
	private static final int[] COLORS = {
		0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6,
		0xFF007AFF, 0xFFAF52DE, 0xFFFF2D92, 0xFFFFFFFF, 0xFF111111,
	};

	enum Tool {
		MOVE(null, "move", InputConstants.KEY_V, 0, 0, 0, false, false),
		PEN(EditOp.PEN, "pen", InputConstants.KEY_P, 6, 1, 40, false, true),
		MARKER(EditOp.MARKER, "marker", InputConstants.KEY_M, 26, 6, 90, false, true),
		LINE(EditOp.LINE, "line", InputConstants.KEY_L, 6, 1, 40, false, true),
		ARROW(EditOp.ARROW, "arrow", InputConstants.KEY_A, 7, 2, 40, false, true),
		RECT(EditOp.RECT, "rect", InputConstants.KEY_R, 6, 1, 40, true, true),
		ELLIPSE(EditOp.ELLIPSE, "ellipse", InputConstants.KEY_O, 6, 1, 40, true, true),
		TEXT(EditOp.TEXT, "text", InputConstants.KEY_T, 44, 12, 200, true, true),
		COUNTER(EditOp.COUNTER, "counter", InputConstants.KEY_N, 24, 10, 90, false, true),
		STICKER(EditOp.STICKER, "sticker", InputConstants.KEY_I, 64, 12, 600, false, false),
		CAPTION(null, "caption", InputConstants.KEY_G, 0, 0, 0, false, false),
		BLUR(EditOp.BLUR, "blur", InputConstants.KEY_B, 16, 3, 60, false, false),
		PIXELATE(EditOp.PIXELATE, "pixelate", InputConstants.KEY_X, 20, 4, 90, false, false),
		SPOTLIGHT(EditOp.SPOTLIGHT, "spotlight", InputConstants.KEY_S, 0, 0, 0, false, false),
		FILTER(EditOp.FILTER, "filter", InputConstants.KEY_F, 0, 0, 0, false, false),
		CROP(EditOp.CROP, "crop", InputConstants.KEY_C, 0, 0, 0, false, false),
		ERASER(null, "eraser", InputConstants.KEY_E, 0, 0, 0, false, false);

		final String op;
		final String id;
		final int key;
		final float defSize;
		final float min;
		final float max;
		final boolean fillable;
		final boolean colored;

		Tool(String op, String id, int key, float defSize, float min, float max, boolean fillable, boolean colored) {
			this.op = op;
			this.id = id;
			this.key = key;
			this.defSize = defSize;
			this.min = min;
			this.max = max;
			this.fillable = fillable;
			this.colored = colored;
		}

		boolean sized() {
			return max > 0;
		}

		boolean dragBox() {
			return switch (this) {
				case LINE, ARROW, RECT, ELLIPSE, BLUR, PIXELATE, SPOTLIGHT, CROP -> true;
				default -> false;
			};
		}

		Identifier icon() {
			return SmartIcons.tool(id);
		}

		/** The hotkey letter (keys are physical, so it is the same on every layout). */
		String keyName() {
			return switch (this) {
				case MOVE -> "V";
				case PEN -> "P";
				case MARKER -> "M";
				case LINE -> "L";
				case ARROW -> "A";
				case RECT -> "R";
				case ELLIPSE -> "O";
				case TEXT -> "T";
				case COUNTER -> "N";
				case STICKER -> "I";
				case CAPTION -> "G";
				case FILTER -> "F";
				case BLUR -> "B";
				case PIXELATE -> "X";
				case SPOTLIGHT -> "S";
				case CROP -> "C";
				case ERASER -> "E";
			};
		}
	}

	// Tool settings are kept for the session, across shots.
	private static Tool tool = Tool.ARROW;
	private static int color = COLORS[0];
	private static final Map<Tool, Float> SIZES = new EnumMap<>(Tool.class);
	private static final Map<Tool, Boolean> FILLS = new EnumMap<>(Tool.class);
	/** The sticker picture placed by the sticker tool (base64 PNG), kept for the session. */
	private static String stickerImg;

	/** Filter panel: values being edited (see {@link EditOp#FILTER}) and whether they wait to be applied. */
	private final float[] filterValues = new float[5];
	private int filterFlags;
	private boolean filterDirty;
	private long filterChangedAt;
	private boolean filterUndoPushed;

	private final ViewerScreen viewer;
	private final Library.Shot shot;
	private List<EditOp> ops;
	private final Deque<List<EditOp>> undo = new ArrayDeque<>();
	private final Deque<List<EditOp>> redo = new ArrayDeque<>();
	private boolean dirty;
	private long changedAt;

	private float zoom = 1;
	private double panX;
	private double panY;
	private boolean panning;
	private boolean spaceDown;

	/** Shape being drawn with the mouse. */
	private EditOp draft;
	private boolean erasing;
	private boolean eraseSnapshotTaken;
	/** Lifted op being moved: where the drag started and the op as it was. */
	private EditOp moving;
	private double moveFromX;
	private double moveFromY;
	private EditOp moveOrig;
	/** Text being typed; {@code textOrig} is the text op it replaces (when editing one). */
	private EditOp textDraft;
	private EditOp textOrig;

	private final List<FlatButton> toolButtons = new ArrayList<>();
	private FlatButton undoButton;
	private FlatButton redoButton;
	private Component status;
	private int statusColor;
	private long statusAt;

	public EditorScreen(ViewerScreen viewer, Library.Shot shot) {
		super(Component.translatable("smartshots.editor.title"));
		this.viewer = viewer;
		this.shot = shot;
		if (shot.meta.edits == null) {
			shot.meta.edits = new ArrayList<>();
		}
		this.ops = shot.meta.edits;
	}

	private static float size(Tool t) {
		return SIZES.getOrDefault(t, t.defSize);
	}

	private static boolean fill(Tool t) {
		return t.fillable && FILLS.getOrDefault(t, false);
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		toolButtons.clear();
		addRenderableWidget(new FlatButton(6, 4, 20, 20, Component.translatable("smartshots.editor.done"), Ui.ARROW_LEFT,
			FlatButton.Style.GHOST, false, b -> onClose()));
		int x = width - 6 - 64;
		addRenderableWidget(FlatButton.text(x, 4, 64, Component.translatable("smartshots.editor.done"), FlatButton.Style.ACCENT, b -> onClose()));
		x -= 24;
		addRenderableWidget(FlatButton.icon(x, 4, SmartIcons.SHARE, Component.translatable("smartshots.editor.share"), b -> share()));
		x -= 22;
		addRenderableWidget(FlatButton.icon(x, 4, Ui.COPY, Component.translatable("smartshots.editor.copy"), b -> copy()));
		x -= 26;
		addRenderableWidget(FlatButton.icon(x, 4, Ui.TRASH, Component.translatable("smartshots.editor.clear"), b -> clearAll()));
		x -= 22;
		redoButton = addRenderableWidget(FlatButton.icon(x, 4, SmartIcons.REDO, Component.translatable("smartshots.editor.redo"), b -> redo()));
		x -= 22;
		undoButton = addRenderableWidget(FlatButton.icon(x, 4, SmartIcons.UNDO, Component.translatable("smartshots.editor.undo"), b -> undo()));

		Tool[] tools = Tool.values();
		for (int i = 0; i < tools.length; i++) {
			Tool t = tools[i];
			int bx = 4 + (i % 2) * 22;
			int by = TOP_H + 6 + (i / 2) * 22;
			FlatButton b = FlatButton.icon(bx, by, t.icon(), Component.translatable("smartshots.editor.tool." + t.id), btn -> selectTool(t));
			b.setTooltip(Tooltip.create(Component.translatable("smartshots.editor.tool." + t.id)
				.append(Component.literal("  " + t.keyName()).withColor(Theme.TEXT_FAINT))
				.append(Component.literal("\n"))
				.append(Component.translatable("smartshots.editor.hint." + t.id).withColor(Theme.TEXT_DIM))));
			toolButtons.add(addRenderableWidget(b));
		}
		if (tool == Tool.FILTER) {
			initFilterPanel();
		}
	}

	private static final String[] FILTER_KEYS = {"brightness", "contrast", "saturation", "warmth", "vignette"};

	private int panelX() {
		return width - 8 - 196;
	}

	private void initFilterPanel() {
		if (!filterDirty) {
			EditOp f = EditOp.filter(ops);
			for (int i = 0; i < filterValues.length; i++) {
				filterValues[i] = f != null ? f.fv(i) : 0;
			}
			filterFlags = f != null ? f.n : 0;
		}
		int x = panelX() + 4;
		int w = 188;
		int y = TOP_H + 10;
		for (int i = 0; i < FILTER_KEYS.length; i++) {
			int idx = i;
			boolean signed = i < 4;
			double v = signed ? (filterValues[i] + 1) / 2 : filterValues[i];
			addRenderableWidget(new dev.vadyanik.smartshots.gui.ui.FlatSlider(x, y, w,
				Component.translatable("smartshots.editor.filter." + FILTER_KEYS[i]), v,
				d -> {
					int pct = (int) Math.round((signed ? d * 2 - 1 : d) * 100);
					return (signed && pct > 0 ? "+" : "") + pct;
				},
				d -> {
					filterValues[idx] = (float) (signed ? Math.round((d * 2 - 1) * 100) / 100.0 : Math.round(d * 100) / 100.0);
					filterChanged();
				}));
			y += 30;
		}
		for (int bit : new int[]{EditOp.FILTER_BW, EditOp.FILTER_SEPIA}) {
			String key = bit == EditOp.FILTER_BW ? "bw" : "sepia";
			addRenderableWidget(new dev.vadyanik.smartshots.gui.ui.FlatToggle(x, y, w,
				Component.translatable("smartshots.editor.filter." + key), Component.empty(),
				() -> (filterFlags & bit) != 0,
				on -> {
					filterFlags = on ? filterFlags | bit : filterFlags & ~bit;
					if (on) {
						filterFlags &= bit; // B/W and sepia exclude each other
					}
					filterChanged();
				}));
			y += 30;
		}
		addRenderableWidget(FlatButton.text(x, y + 4, w, Component.translatable("smartshots.editor.filter.reset"), FlatButton.Style.FILLED, b -> {
			java.util.Arrays.fill(filterValues, 0);
			filterFlags = 0;
			filterChanged();
			rebuildWidgets();
		}));
	}

	private void filterChanged() {
		filterDirty = true;
		filterChangedAt = System.currentTimeMillis();
	}

	/** Puts the panel's values into the edit list (one undo step per visit of the filter tool). */
	private void applyFilter() {
		filterDirty = false;
		List<EditOp> next = new ArrayList<>();
		for (EditOp o : ops) {
			if (!o.is(EditOp.FILTER)) {
				next.add(o);
			}
		}
		boolean any = filterFlags != 0;
		for (float v : filterValues) {
			any |= v != 0;
		}
		if (any) {
			EditOp f = new EditOp(EditOp.FILTER, 0, 0, false, filterValues.clone());
			f.n = filterFlags;
			next.add(f);
		}
		if (!filterUndoPushed) {
			pushUndo();
			filterUndoPushed = true;
		}
		setOps(next);
	}

	private void selectTool(Tool t) {
		commitText();
		if (filterDirty) {
			applyFilter();
		}
		filterUndoPushed = false;
		if (t == Tool.CAPTION) {
			openCaption();
			return;
		}
		Tool old = tool;
		tool = t;
		draft = null;
		if (t == Tool.STICKER && stickerImg == null) {
			openStickerPicker();
			return;
		}
		if (old == Tool.FILTER || t == Tool.FILTER) {
			rebuildWidgets();
		}
	}

	private void openStickerPicker() {
		minecraft.gui.setScreen(new StickerScreen(this, shot.meta, img -> {
			stickerImg = img;
			tool = Tool.STICKER;
		}));
	}

	private void openCaption() {
		minecraft.gui.setScreen(new CaptionScreen(this, shot, this::addCaption));
	}

	/** A caption is a text with a dark box, placed in a corner of the (cropped) image. */
	private void addCaption(String text, int corner) {
		Textures.Tex tex = tex();
		int w = Math.max(1, tex.width > 0 ? tex.width : shot.meta.width);
		int h = Math.max(1, tex.height > 0 ? tex.height : shot.meta.height);
		EditOp op = new EditOp(EditOp.TEXT, 0xC0101216, 26, true, new float[]{0, 0});
		op.text = text;
		float[] b = EditRenderer.bounds(op, w, h);
		float bw = b != null ? b[2] - b[0] : 0.3f;
		float bh = b != null ? b[3] - b[1] : 0.05f;
		float[] area = EditOp.crop(ops);
		if (area == null) {
			area = new float[]{0, 0, 1, 1};
		}
		float my = 0.02f * (area[3] - area[1]);
		float mx = my * h / w;
		float x = corner % 2 == 0 ? area[0] + mx : area[2] - mx - bw;
		float y = corner < 2 ? area[1] + my : area[3] - my - bh;
		x = Math.max(area[0] + mx, x); // a caption wider than the image starts at the left edge
		op.p = new float[]{x, y};
		change(with(op));
	}

	/** Image area (without zoom). */
	private int[] area() {
		int ax = LEFT_W + 4;
		int ay = TOP_H + 4;
		return new int[]{ax, ay, width - ax - 8, height - ay - BOTTOM_H - 4};
	}

	/** Where the whole (uncropped) image is drawn: {x, y, w, h} in GUI coordinates. */
	private float[] imageRect(Textures.Tex tex) {
		int[] a = area();
		float aspect = tex.width / (float) Math.max(1, tex.height);
		float w = a[2];
		float h = w / aspect;
		if (h > a[3]) {
			h = a[3];
			w = h * aspect;
		}
		w *= zoom;
		h *= zoom;
		float x = a[0] + (a[2] - w) / 2 + (float) panX;
		float y = a[1] + (a[3] - h) / 2 + (float) panY;
		return new float[]{x, y, w, h};
	}

	private Textures.Tex tex() {
		return Textures.full(shot.file, ops);
	}

	// ------------------------------------------------------------------ edit list

	private void setOps(List<EditOp> next) {
		ops = next;
		shot.meta.edits = next;
		dirty = true;
		changedAt = System.currentTimeMillis();
	}

	/** A new state as one undo step. */
	private void change(List<EditOp> next) {
		pushUndo();
		setOps(next);
	}

	private void pushUndo() {
		undo.push(ops);
		while (undo.size() > MAX_UNDO) {
			undo.removeLast();
		}
		redo.clear();
	}

	private List<EditOp> with(EditOp op) {
		List<EditOp> next = new ArrayList<>(ops);
		next.add(op);
		return next;
	}

	private List<EditOp> without(EditOp op) {
		List<EditOp> next = new ArrayList<>(ops.size());
		for (EditOp o : ops) {
			if (o != op) {
				next.add(o);
			}
		}
		return next;
	}

	private void undo() {
		commitText();
		filterDirty = false;
		filterUndoPushed = false;
		if (!undo.isEmpty()) {
			redo.push(ops);
			setOps(undo.pop());
		} else if (!ops.isEmpty()) {
			// Nothing from this session: undo still removes the newest operation (from an earlier edit).
			redo.push(ops);
			setOps(new ArrayList<>(ops.subList(0, ops.size() - 1)));
		}
	}

	private void redo() {
		commitText();
		filterDirty = false;
		filterUndoPushed = false;
		if (!redo.isEmpty()) {
			undo.push(ops);
			setOps(redo.pop());
		}
	}

	private void clearAll() {
		commitText();
		if (!ops.isEmpty()) {
			change(new ArrayList<>());
			setStatus(Component.translatable("smartshots.editor.cleared"), Theme.TEXT_DIM);
		}
	}

	/** Saves the metadata and rebuilds the PNG now (it also happens on its own shortly after each change). */
	private void flush() {
		if (!dirty) {
			return;
		}
		dirty = false;
		Path file = shot.file;
		ScreenshotStore.updateMeta(file, shot.meta);
		ShotBaker.schedule(file, Highlights.mask(shot), shot.meta.highlights, ops, () -> {
			Textures.forgetThumb(file);
			GalleryScreen.markDirty();
		});
		shot.refreshDoc();
	}

	private void setStatus(Component text, int color) {
		status = text;
		statusColor = color;
		statusAt = System.currentTimeMillis();
	}

	private void copy() {
		commitText();
		flush();
		setStatus(Component.translatable("smartshots.editor.copying"), Theme.TEXT_DIM);
		// After the IO queue, so the PNG has the latest edits.
		ScreenshotStore.IO.execute(() -> minecraft.execute(() -> ClipboardImage.copy(shot.file, ok -> setStatus(
			Component.translatable(ok ? "smartshots.copy.ok" : "smartshots.copy.fail"), ok ? Theme.GREEN : Theme.DANGER))));
	}

	private void share() {
		commitText();
		flush();
		ShareConfirmScreen.confirmThen(this, () -> {
			setStatus(Component.translatable("smartshots.share.uploading", ShareService.serviceName()), Theme.TEXT_DIM);
			ShareService.share(shot.file, shot.meta, true, r -> setStatus(ShareService.status(r, true), r.ok() ? Theme.GREEN : Theme.DANGER));
		});
	}

	@Override
	public void onClose() {
		commitText();
		if (filterDirty) {
			applyFilter();
		}
		flush();
		minecraft.gui.setScreen(viewer);
	}

	@Override
	public void removed() {
		flush();
	}

	@Override
	public void tick() {
		if (filterDirty && System.currentTimeMillis() - filterChangedAt > 120) {
			applyFilter();
		}
		if (dirty && System.currentTimeMillis() - changedAt > SAVE_DELAY_MS && draft == null && moving == null && textDraft == null) {
			flush();
		}
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xFF0A0B0E);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Textures.Tex tex = tex();
		int[] ar = area();
		if (tex.ready()) {
			float[] r = imageRect(tex);
			g.enableScissor(ar[0], ar[1], ar[0] + ar[2], ar[1] + ar[3]);
			Textures.drawStretched(g, tex, Math.round(r[0]), Math.round(r[1]), Math.round(r[0] + r[2]), Math.round(r[1] + r[3]));
			if (Highlights.mask(shot) != null && shot.meta.highlights != null) {
				for (String key : shot.meta.highlights) {
					Highlights.draw(g, Highlights.overlay(key, false), Math.round(r[0]), Math.round(r[1]), Math.round(r[2]), Math.round(r[3]));
				}
			}
			// Operations the texture doesn't show yet (it is re-rendering in the background).
			Set<EditOp> shown = Collections.newSetFromMap(new IdentityHashMap<>());
			shown.addAll(tex.ops);
			for (EditOp op : ops) {
				if (!shown.contains(op) && !op.is(EditOp.CROP) && !op.is(EditOp.FILTER)) {
					drawLive(g, op, r, tex);
				}
			}
			if (moving != null) {
				drawLive(g, moving, r, tex);
			}
			if (draft != null && !draft.is(EditOp.CROP)) {
				drawLive(g, draft, r, tex);
			}
			if (textDraft != null) {
				drawLive(g, textDraft, r, tex);
				drawCaret(g, textDraft, r, tex);
			}
			drawCrop(g, r, tex);
			drawHover(g, r, tex, mouseX, mouseY);
			g.disableScissor();
		} else if (tex.failed()) {
			g.centeredText(font, Component.translatable("smartshots.viewer.failed"), ar[0] + ar[2] / 2, height / 2, Theme.DANGER);
		} else {
			g.centeredText(font, Component.translatable("smartshots.gallery.loading"), ar[0] + ar[2] / 2, height / 2, Theme.TEXT_FAINT);
		}

		// Chrome
		g.fill(0, 0, width, TOP_H, Theme.SURFACE);
		g.horizontalLine(0, width, TOP_H, Theme.BORDER);
		g.fill(0, TOP_H + 1, LEFT_W, height, Theme.SURFACE);
		g.verticalLine(LEFT_W, TOP_H, height, Theme.BORDER);
		int by = height - BOTTOM_H;
		g.fill(LEFT_W + 1, by, width, height, Theme.SURFACE);
		g.horizontalLine(LEFT_W, width, by, Theme.BORDER);

		g.text(font, title, 32, 10, Theme.TEXT, false);
		int sx = 32 + font.width(title) + 10;
		if (status != null && System.currentTimeMillis() - statusAt < 4000) {
			int maxW = (undoButton != null ? undoButton.getX() : width) - sx - 8;
			g.text(font, Ui.ellipsize(font, status.getString(), maxW), sx, 10, statusColor, false);
		} else if (dirty) {
			g.text(font, Component.translatable("smartshots.editor.saving"), sx, 10, Theme.TEXT_FAINT, false);
		}
		for (int i = 0; i < toolButtons.size(); i++) {
			toolButtons.get(i).setSelected(Tool.values()[i] == tool);
		}
		if (undoButton != null) {
			undoButton.active = !undo.isEmpty() || !ops.isEmpty();
			redoButton.active = !redo.isEmpty();
		}
		drawBottomBar(g, mouseX, mouseY);
		if (tool == Tool.FILTER) {
			int px = panelX();
			Ui.card(g, px, TOP_H + 6, 196, FILTER_KEYS.length * 30 + 2 * 30 + 32);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	// Bottom bar: color swatches, size, fill toggle, hint.
	private static final int SW = 12;

	private int swatchX(int i) {
		return LEFT_W + 8 + i * (SW + 3);
	}

	private int sizeX() {
		return swatchX(COLORS.length) + 8;
	}

	private void drawBottomBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y = height - BOTTOM_H + (BOTTOM_H - SW) / 2;
		boolean colored = tool.colored;
		for (int i = 0; i < COLORS.length; i++) {
			int x = swatchX(i);
			boolean sel = COLORS[i] == color;
			int c = colored ? COLORS[i] : Theme.alpha(COLORS[i], 0.25f);
			if (sel && colored) {
				Ui.rect(g, x - 2, y - 2, SW + 4, SW + 4, Theme.TEXT);
			}
			Ui.rect(g, x, y, SW, SW, c);
			if (COLORS[i] == 0xFF111111) {
				Ui.border(g, x, y, SW, SW, Theme.BORDER_STRONG);
			}
		}
		int x = sizeX();
		int cy = height - BOTTOM_H / 2;
		if (tool.sized()) {
			String v = Component.translatable("smartshots.editor.size", Math.round(size(tool))).getString();
			boolean hm = hit(mouseX, mouseY, x, cy - 7, 14, 14);
			Ui.rect(g, x, cy - 7, 14, 14, hm ? Theme.SURFACE_3 : Theme.SURFACE_2);
			g.centeredText(font, "−", x + 7, cy - 4, Theme.TEXT);
			g.text(font, v, x + 20, cy - 4, Theme.TEXT_DIM, false);
			int px = x + 20 + font.width(v) + 6;
			boolean hp = hit(mouseX, mouseY, px, cy - 7, 14, 14);
			Ui.rect(g, px, cy - 7, 14, 14, hp ? Theme.SURFACE_3 : Theme.SURFACE_2);
			g.centeredText(font, "+", px + 7, cy - 4, Theme.TEXT);
			x = px + 22;
		}
		if (tool == Tool.STICKER) {
			String label = Component.translatable("smartshots.editor.sticker.choose").getString();
			int w = font.width(label) + 12;
			boolean h = hit(mouseX, mouseY, x, cy - 7, w, 14);
			Ui.rect(g, x, cy - 7, w, 14, h ? Theme.SURFACE_3 : Theme.SURFACE_2);
			g.text(font, label, x + 6, cy - 4, Theme.TEXT, false);
			x += w + 8;
		}
		if (tool.fillable) {
			boolean on = fill(tool);
			String label = Component.translatable(tool == Tool.TEXT ? "smartshots.editor.background" : "smartshots.editor.fill").getString();
			int w = font.width(label) + 22;
			boolean h = hit(mouseX, mouseY, x, cy - 7, w, 14);
			Ui.rect(g, x, cy - 7, w, 14, on ? Theme.ACCENT_SOFT : h ? Theme.SURFACE_3 : Theme.SURFACE_2);
			if (on) {
				Ui.rect(g, x + 4, cy - 3, 7, 7, Theme.ACCENT);
			} else {
				Ui.border(g, x + 4, cy - 3, 7, 7, Theme.TEXT_DIM);
			}
			g.text(font, label, x + 15, cy - 4, on ? Theme.ACCENT : Theme.TEXT_DIM, false);
			x += w + 8;
		}
		String hint = Component.translatable("smartshots.editor.hint." + tool.id).getString()
			+ "  ·  " + Component.translatable("smartshots.editor.hint.common").getString();
		g.text(font, Ui.ellipsize(font, hint, width - x - 8), x, cy - 4, Theme.TEXT_FAINT, false);
	}

	private static boolean hit(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** Bottom bar clicks; true when handled. */
	private boolean clickBottomBar(double mx, double my) {
		if (my < height - BOTTOM_H || mx < LEFT_W) {
			return false;
		}
		int y = height - BOTTOM_H + (BOTTOM_H - SW) / 2;
		for (int i = 0; i < COLORS.length; i++) {
			if (hit(mx, my, swatchX(i) - 1, y - 1, SW + 2, SW + 2)) {
				color = COLORS[i];
				if (textDraft != null) {
					textDraft.c = color;
				}
				click();
				return true;
			}
		}
		int x = sizeX();
		int cy = height - BOTTOM_H / 2;
		if (tool.sized()) {
			String v = Component.translatable("smartshots.editor.size", Math.round(size(tool))).getString();
			int px = x + 20 + font.width(v) + 6;
			if (hit(mx, my, x, cy - 7, 14, 14)) {
				nudgeSize(-1);
				click();
				return true;
			}
			if (hit(mx, my, px, cy - 7, 14, 14)) {
				nudgeSize(1);
				click();
				return true;
			}
			x = px + 22;
		}
		if (tool == Tool.STICKER) {
			String label = Component.translatable("smartshots.editor.sticker.choose").getString();
			if (hit(mx, my, x, cy - 7, font.width(label) + 12, 14)) {
				click();
				openStickerPicker();
				return true;
			}
			x += font.width(label) + 12 + 8;
		}
		if (tool.fillable) {
			String label = Component.translatable(tool == Tool.TEXT ? "smartshots.editor.background" : "smartshots.editor.fill").getString();
			if (hit(mx, my, x, cy - 7, font.width(label) + 22, 14)) {
				FILLS.put(tool, !fill(tool));
				if (textDraft != null) {
					textDraft.f = fill(tool);
				}
				click();
				return true;
			}
		}
		return true; // the bar itself swallows clicks
	}

	private void nudgeSize(int dir) {
		if (!tool.sized()) {
			return;
		}
		float s = size(tool);
		float step = s < 10 ? 1 : s < 30 ? 2 : s < 80 ? 5 : 10;
		s = Mth.clamp(s + dir * step, tool.min, tool.max);
		SIZES.put(tool, s);
		if (textDraft != null) {
			textDraft.s = s;
		}
	}

	private void click() {
		Button.playButtonClickSound(minecraft.getSoundManager());
	}

	// ------------------------------------------------------------------ live drawing (GUI primitives)

	/** GUI x of a normalized image x. */
	private static float gx(float[] r, float u) {
		return r[0] + u * r[2];
	}

	private static float gy(float[] r, float v) {
		return r[1] + v * r[3];
	}

	/** GUI pixels per image size unit (sizes are for a 1080p-high image). */
	private static float unit(float[] r, Textures.Tex tex) {
		return EditRenderer.scale(tex.height) * r[3] / Math.max(1, tex.height);
	}

	private static void fillF(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int c) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(Math.min(x0, x1), Math.min(y0, y1));
		pose.scale(Math.max(0.01f, Math.abs(x1 - x0)), Math.max(0.01f, Math.abs(y1 - y0)));
		g.fill(0, 0, 1, 1, c);
		pose.popMatrix();
	}

	private static void seg(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float t, int c, boolean caps) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float len = (float) Math.hypot(dx, dy);
		float ext = caps ? t / 2 : 0;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x0, y0);
		pose.rotate((float) Math.atan2(dy, dx));
		pose.translate(-ext, -t / 2);
		pose.scale(Math.max(0.01f, len + 2 * ext), Math.max(0.01f, t));
		g.fill(0, 0, 1, 1, c);
		pose.popMatrix();
	}

	private static void ellipseFill(GuiGraphicsExtractor g, float cx, float cy, float rx, float ry, int c) {
		int n = Math.max(6, Math.min(80, Math.round(ry * 2)));
		for (int i = 0; i < n; i++) {
			float y0 = -ry + 2 * ry * i / n;
			float y1 = -ry + 2 * ry * (i + 1) / n;
			float ym = (y0 + y1) / 2 / ry;
			float half = rx * (float) Math.sqrt(Math.max(0, 1 - ym * ym));
			fillF(g, cx - half, cy + y0, cx + half, cy + y1, c);
		}
	}

	private static void ellipseLine(GuiGraphicsExtractor g, float cx, float cy, float rx, float ry, float t, int c) {
		int n = 48;
		for (int i = 0; i < n; i++) {
			double a0 = Math.PI * 2 * i / n;
			double a1 = Math.PI * 2 * (i + 1) / n;
			seg(g, cx + rx * (float) Math.cos(a0), cy + ry * (float) Math.sin(a0),
				cx + rx * (float) Math.cos(a1), cy + ry * (float) Math.sin(a1), t, c, true);
		}
	}

	private static void dashedRect(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int c) {
		float dash = 4;
		for (float x = x0; x < x1; x += dash * 2) {
			fillF(g, x, y0, Math.min(x + dash, x1), y0 + 1, c);
			fillF(g, x, y1 - 1, Math.min(x + dash, x1), y1, c);
		}
		for (float y = y0; y < y1; y += dash * 2) {
			fillF(g, x0, y, x0 + 1, Math.min(y + dash, y1), c);
			fillF(g, x1 - 1, y, x1, Math.min(y + dash, y1), c);
		}
	}

	private void drawLive(GuiGraphicsExtractor g, EditOp op, float[] r, Textures.Tex tex) {
		float u = unit(r, tex);
		float t = Math.max(1, op.s * u);
		int c = op.c;
		switch (op.t) {
			case EditOp.PEN, EditOp.MARKER -> {
				if (op.is(EditOp.MARKER)) {
					c = (c & 0xFFFFFF) | 0x70000000;
				}
				float[] p = op.p;
				if (p.length == 2) {
					fillF(g, gx(r, p[0]) - t / 2, gy(r, p[1]) - t / 2, gx(r, p[0]) + t / 2, gy(r, p[1]) + t / 2, c);
				}
				for (int i = 2; i + 1 < p.length; i += 2) {
					seg(g, gx(r, p[i - 2]), gy(r, p[i - 1]), gx(r, p[i]), gy(r, p[i + 1]), t, c, !op.is(EditOp.MARKER));
				}
			}
			case EditOp.LINE -> seg(g, gx(r, op.p[0]), gy(r, op.p[1]), gx(r, op.p[2]), gy(r, op.p[3]), t, c, true);
			case EditOp.ARROW -> {
				float x0 = gx(r, op.p[0]);
				float y0 = gy(r, op.p[1]);
				float x1 = gx(r, op.p[2]);
				float y1 = gy(r, op.p[3]);
				float len = (float) Math.hypot(x1 - x0, y1 - y0);
				if (len < 0.5f) {
					return;
				}
				float head = Math.min(len, Math.max(t * 4f, 10f * r[3] / Math.max(1, tex.height)));
				float dx = (x1 - x0) / len;
				float dy = (y1 - y0) / len;
				float bx = x1 - dx * head;
				float by = y1 - dy * head;
				seg(g, x0, y0, x1 - dx * head * 0.8f, y1 - dy * head * 0.8f, t, c, true);
				// Head: thin strips from the tip to points along its base.
				int n = 14;
				for (int i = 0; i <= n; i++) {
					float k = (i / (float) n - 0.5f) * head;
					seg(g, x1, y1, bx - dy * k, by + dx * k, Math.max(1, head / n * 1.6f), c, false);
				}
			}
			case EditOp.RECT, EditOp.ELLIPSE, EditOp.BLUR, EditOp.PIXELATE, EditOp.SPOTLIGHT -> {
				float[] b = op.box();
				if (b == null) {
					return;
				}
				float x0 = gx(r, b[0]);
				float y0 = gy(r, b[1]);
				float x1 = gx(r, b[2]);
				float y1 = gy(r, b[3]);
				switch (op.t) {
					case EditOp.RECT -> {
						if (op.f) {
							fillF(g, x0, y0, x1, y1, c);
						} else {
							seg(g, x0, y0, x1, y0, t, c, true);
							seg(g, x1, y0, x1, y1, t, c, true);
							seg(g, x1, y1, x0, y1, t, c, true);
							seg(g, x0, y1, x0, y0, t, c, true);
						}
					}
					case EditOp.ELLIPSE -> {
						if (op.f) {
							ellipseFill(g, (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2, c);
						} else {
							ellipseLine(g, (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2, t, c);
						}
					}
					case EditOp.SPOTLIGHT -> {
						int dim = 0x96000000;
						fillF(g, r[0], r[1], r[0] + r[2], y0, dim);
						fillF(g, r[0], y1, r[0] + r[2], r[1] + r[3], dim);
						fillF(g, r[0], y0, x0, y1, dim);
						fillF(g, x1, y0, r[0] + r[2], y1, dim);
					}
					default -> {
						fillF(g, x0, y0, x1, y1, op.is(EditOp.BLUR) ? 0x66C8D0E0 : 0x66808890);
						dashedRect(g, x0, y0, x1, y1, 0xFFFFFFFF);
					}
				}
			}
			case EditOp.TEXT -> drawText(g, op, r, tex);
			case EditOp.STICKER -> {
				float[] b = EditRenderer.bounds(op, tex.width, tex.height);
				if (b != null) {
					fillF(g, gx(r, b[0]), gy(r, b[1]), gx(r, b[2]), gy(r, b[3]), 0x40FFFFFF);
					dashedRect(g, gx(r, b[0]), gy(r, b[1]), gx(r, b[2]), gy(r, b[3]), 0xC0FFFFFF);
				}
			}
			case EditOp.COUNTER -> {
				float cx = gx(r, op.p[0]);
				float cy = gy(r, op.p[1]);
				float rad = Math.max(2, op.s * u);
				ellipseFill(g, cx, cy, rad * 1.1f, rad * 1.1f, 0xFFFFFFFF);
				ellipseFill(g, cx, cy, rad, rad, c);
				String s = String.valueOf(op.n);
				float sc = rad * (s.length() > 2 ? 0.8f : 1.1f) / 7f;
				Matrix3x2fStack pose = g.pose();
				pose.pushMatrix();
				pose.translate(cx, cy);
				pose.scale(sc, sc);
				g.text(font, s, -font.width(s) / 2, -4, luminance(c) > 0.6 ? 0xFF111111 : 0xFFFFFFFF, false);
				pose.popMatrix();
			}
			default -> {
			}
		}
	}

	/** Font scale that makes the GUI font about as big as the Java2D text of the op. */
	private float textScale(EditOp op, float[] r, Textures.Tex tex) {
		return Math.max(0.2f, op.s * unit(r, tex)) / 9f;
	}

	private void drawText(GuiGraphicsExtractor g, EditOp op, float[] r, Textures.Tex tex) {
		String text = op.text == null ? "" : op.text;
		String[] lines = text.split("\n", -1);
		float sc = textScale(op, r, tex);
		float x = gx(r, op.p[0]);
		float y = gy(r, op.p[1]);
		float pad = op.f ? op.s * unit(r, tex) * 0.3f : 0;
		int maxW = 0;
		for (String l : lines) {
			maxW = Math.max(maxW, font.width(l));
		}
		float lineH = 10.8f;
		if (op.f) {
			fillF(g, x, y, x + maxW * sc + pad * 2, y + lines.length * lineH * sc + pad * 2, op.c);
		}
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x + pad, y + pad);
		pose.scale(sc, sc);
		int tc = op.f ? (luminance(op.c) > 0.6 ? 0xFF111111 : 0xFFFFFFFF) : op.c;
		for (int i = 0; i < lines.length; i++) {
			g.text(font, lines[i], 0, Math.round(i * lineH + 1), tc, !op.f);
		}
		pose.popMatrix();
	}

	private void drawCaret(GuiGraphicsExtractor g, EditOp op, float[] r, Textures.Tex tex) {
		String text = op.text == null ? "" : op.text;
		String[] lines = text.split("\n", -1);
		float sc = textScale(op, r, tex);
		float pad = op.f ? op.s * unit(r, tex) * 0.3f : 0;
		float x = gx(r, op.p[0]) + pad + font.width(lines[lines.length - 1]) * sc;
		float y = gy(r, op.p[1]) + pad + (lines.length - 1) * 10.8f * sc;
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			fillF(g, x + sc, y, x + sc + Math.max(1, sc), y + 10 * sc, 0xFFFFFFFF);
		}
		int maxW = 0;
		for (String l : lines) {
			maxW = Math.max(maxW, font.width(l));
		}
		dashedRect(g, gx(r, op.p[0]) - 2, gy(r, op.p[1]) - 2,
			gx(r, op.p[0]) + Math.max(maxW, 4) * sc + pad * 2 + 3, gy(r, op.p[1]) + lines.length * 10.8f * sc + pad * 2 + 2, 0xAAFFFFFF);
	}

	/** Dims what the crop cuts off. */
	private void drawCrop(GuiGraphicsExtractor g, float[] r, Textures.Tex tex) {
		float[] b = draft != null && draft.is(EditOp.CROP) ? draft.box() : EditOp.crop(ops);
		if (b == null) {
			return;
		}
		float x0 = gx(r, Mth.clamp(b[0], 0, 1));
		float y0 = gy(r, Mth.clamp(b[1], 0, 1));
		float x1 = gx(r, Mth.clamp(b[2], 0, 1));
		float y1 = gy(r, Mth.clamp(b[3], 0, 1));
		int dim = tool == Tool.CROP ? 0xB0000000 : 0xD0080809;
		fillF(g, r[0], r[1], r[0] + r[2], y0, dim);
		fillF(g, r[0], y1, r[0] + r[2], r[1] + r[3], dim);
		fillF(g, r[0], y0, x0, y1, dim);
		fillF(g, x1, y0, r[0] + r[2], y1, dim);
		int c = tool == Tool.CROP ? 0xFFFFFFFF : 0x60FFFFFF;
		fillF(g, x0 - 1, y0 - 1, x1 + 1, y0, c);
		fillF(g, x0 - 1, y1, x1 + 1, y1 + 1, c);
		fillF(g, x0 - 1, y0, x0, y1, c);
		fillF(g, x1, y0, x1 + 1, y1, c);
		if (tool == Tool.CROP && tex.width > 0) {
			int pw = Math.round((b[2] - b[0]) * tex.width);
			int ph = Math.round((b[3] - b[1]) * tex.height);
			String s = pw + " × " + ph;
			Ui.rect(g, Math.round(x0), Math.round(y1) + 3, font.width(s) + 8, 12, 0xC0000000);
			g.text(font, s, Math.round(x0) + 4, Math.round(y1) + 5, Theme.TEXT, false);
		}
	}

	/** The eraser / move tools show what is under the cursor. */
	private void drawHover(GuiGraphicsExtractor g, float[] r, Textures.Tex tex, int mx, int my) {
		if ((tool != Tool.ERASER && tool != Tool.MOVE && tool != Tool.TEXT) || moving != null || draft != null) {
			return;
		}
		EditOp op = opAt(mx, my);
		if (op == null || tool == Tool.TEXT && !op.is(EditOp.TEXT)) {
			return;
		}
		float[] b = bounds(op, tex);
		if (b == null) {
			return;
		}
		int c = tool == Tool.ERASER ? Theme.DANGER : Theme.ACCENT;
		dashedRect(g, gx(r, b[0]) - 3, gy(r, b[1]) - 3, gx(r, b[2]) + 3, gy(r, b[3]) + 3, c);
	}

	private static double luminance(int argb) {
		return (0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF)) / 255.0;
	}

	// ------------------------------------------------------------------ hit testing

	/** Normalized bounds of an op, or null. */
	private float[] bounds(EditOp op, Textures.Tex tex) {
		if (op.is(EditOp.TEXT) || op.is(EditOp.COUNTER) || op.is(EditOp.STICKER)) {
			return EditRenderer.bounds(op, tex.width, tex.height);
		}
		if (op.is(EditOp.FILTER)) {
			return null;
		}
		if (op.is(EditOp.CROP)) {
			return op.box();
		}
		if (op.p == null || op.p.length < 2) {
			return null;
		}
		float x0 = Float.MAX_VALUE;
		float y0 = Float.MAX_VALUE;
		float x1 = -Float.MAX_VALUE;
		float y1 = -Float.MAX_VALUE;
		for (int i = 0; i + 1 < op.p.length; i += 2) {
			x0 = Math.min(x0, op.p[i]);
			y0 = Math.min(y0, op.p[i + 1]);
			x1 = Math.max(x1, op.p[i]);
			y1 = Math.max(y1, op.p[i + 1]);
		}
		float hw = op.boxed() ? 0 : op.s * EditRenderer.scale(tex.height) / 2;
		return new float[]{x0 - hw / tex.width, y0 - hw / tex.height, x1 + hw / tex.width, y1 + hw / tex.height};
	}

	/** Topmost op under a screen point. */
	private EditOp opAt(double mx, double my) {
		Textures.Tex tex = tex();
		if (!tex.ready() || !inImageArea(mx, my)) {
			return null;
		}
		float[] r = imageRect(tex);
		double W = tex.width;
		double H = tex.height;
		double px = (mx - r[0]) / r[2] * W;
		double py = (my - r[1]) / r[3] * H;
		double tol = 5 * W / r[2]; // 5 GUI pixels, in image pixels
		for (int i = ops.size() - 1; i >= 0; i--) {
			EditOp op = ops.get(i);
			if (hits(op, px, py, W, H, tol, tex)) {
				return op;
			}
		}
		return null;
	}

	private boolean hits(EditOp op, double px, double py, double W, double H, double tol, Textures.Tex tex) {
		if (op.t == null || op.p == null) {
			return false;
		}
		if (op.is(EditOp.FILTER)) {
			return false;
		}
		double half = op.s * EditRenderer.scale((int) H) / 2;
		switch (op.t) {
			case EditOp.PEN, EditOp.MARKER, EditOp.LINE, EditOp.ARROW -> {
				if (op.p.length == 2) {
					return Math.hypot(op.p[0] * W - px, op.p[1] * H - py) <= half + tol;
				}
				for (int i = 2; i + 1 < op.p.length; i += 2) {
					if (segDist(px, py, op.p[i - 2] * W, op.p[i - 1] * H, op.p[i] * W, op.p[i + 1] * H) <= half + tol) {
						return true;
					}
				}
				return false;
			}
			case EditOp.TEXT, EditOp.COUNTER, EditOp.STICKER -> {
				float[] b = EditRenderer.bounds(op, (int) W, (int) H);
				return b != null && px >= b[0] * W - tol && px <= b[2] * W + tol && py >= b[1] * H - tol && py <= b[3] * H + tol;
			}
			case EditOp.CROP -> {
				float[] b = op.box();
				// The dimmed part outside the crop.
				return b != null && (px < b[0] * W || px > b[2] * W || py < b[1] * H || py > b[3] * H);
			}
			default -> {
				float[] b = op.box();
				if (b == null) {
					return false;
				}
				double x0 = b[0] * W;
				double y0 = b[1] * H;
				double x1 = b[2] * W;
				double y1 = b[3] * H;
				boolean filled = op.f || op.is(EditOp.BLUR) || op.is(EditOp.PIXELATE) || op.is(EditOp.SPOTLIGHT);
				double m = half + tol;
				if (op.is(EditOp.ELLIPSE)) {
					double cx = (x0 + x1) / 2;
					double cy = (y0 + y1) / 2;
					double rx = Math.max(1, (x1 - x0) / 2);
					double ry = Math.max(1, (y1 - y0) / 2);
					double d = Math.hypot((px - cx) / rx, (py - cy) / ry);
					return filled ? d <= 1 + m / Math.min(rx, ry) : Math.abs(d - 1) * Math.min(rx, ry) <= m;
				}
				boolean inOuter = px >= x0 - m && px <= x1 + m && py >= y0 - m && py <= y1 + m;
				if (filled) {
					return inOuter;
				}
				boolean inInner = px > x0 + m && px < x1 - m && py > y0 + m && py < y1 - m;
				return inOuter && !inInner;
			}
		}
	}

	private static double segDist(double px, double py, double x0, double y0, double x1, double y1) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double len2 = dx * dx + dy * dy;
		double t = len2 == 0 ? 0 : Mth.clamp(((px - x0) * dx + (py - y0) * dy) / len2, 0, 1);
		return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
	}

	private boolean inImageArea(double mx, double my) {
		int[] a = area();
		return mx >= a[0] && mx < a[0] + a[2] && my >= a[1] && my < a[1] + a[3];
	}

	/** Normalized image coordinates of a screen point (clamped to the image). */
	private float[] toImage(double mx, double my) {
		Textures.Tex tex = tex();
		float[] r = imageRect(tex);
		return new float[]{Mth.clamp((float) ((mx - r[0]) / r[2]), 0f, 1f), Mth.clamp((float) ((my - r[1]) / r[3]), 0f, 1f)};
	}

	// ------------------------------------------------------------------ mouse

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (clickBottomBar(mx, my)) {
			return true;
		}
		if (!inImageArea(mx, my) || !tex().ready()) {
			return false;
		}
		int button = event.button();
		if (button == InputConstants.MOUSE_BUTTON_MIDDLE || button == InputConstants.MOUSE_BUTTON_LEFT && spaceDown) {
			panning = true;
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			commitText();
			if (tool == Tool.CROP) {
				removeCrop();
			} else {
				EditOp op = opAt(mx, my);
				if (op != null) {
					change(without(op));
				}
			}
			return true;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		float[] p = toImage(mx, my);
		if (textDraft != null) {
			commitText();
			if (tool == Tool.TEXT) {
				return true; // the click only finished the text
			}
		}
		switch (tool) {
			case MOVE -> {
				EditOp op = opAt(mx, my);
				if (op == null) {
					panning = true;
					return true;
				}
				pushUndo();
				setOps(without(op));
				moving = op;
				moveOrig = op;
				moveFromX = mx;
				moveFromY = my;
			}
			case ERASER -> {
				erasing = true;
				eraseSnapshotTaken = false;
				eraseAt(mx, my);
			}
			case TEXT -> {
				EditOp op = opAt(mx, my);
				if (op != null && op.is(EditOp.TEXT)) {
					// Edit an existing text: lift it out of the list while typing.
					pushUndo();
					setOps(without(op));
					textOrig = op;
					textDraft = op.copy();
				} else {
					textOrig = null;
					textDraft = new EditOp(EditOp.TEXT, color, size(tool), fill(tool), new float[]{p[0], p[1]});
					textDraft.text = "";
				}
			}
			case FILTER, CAPTION -> {
				return true;
			}
			case STICKER -> {
				if (stickerImg == null) {
					openStickerPicker();
					return true;
				}
				EditOp op = new EditOp(EditOp.STICKER, 0xFFFFFFFF, size(tool), false, new float[]{p[0], p[1]});
				op.img = stickerImg;
				change(with(op));
			}
			case COUNTER -> {
				EditOp op = new EditOp(EditOp.COUNTER, color, size(tool), false, new float[]{p[0], p[1]});
				op.n = EditOp.nextNumber(ops);
				change(with(op));
			}
			case PEN, MARKER -> draft = new EditOp(tool.op, color, size(tool), false, new float[]{p[0], p[1]});
			default -> draft = new EditOp(tool.op, color, size(tool), fill(tool), new float[]{p[0], p[1], p[0], p[1]});
		}
		return true;
	}

	private void eraseAt(double mx, double my) {
		EditOp op = opAt(mx, my);
		if (op == null) {
			return;
		}
		if (!eraseSnapshotTaken) {
			pushUndo(); // one undo step for the whole drag
			eraseSnapshotTaken = true;
		}
		setOps(without(op));
	}

	private void removeCrop() {
		List<EditOp> next = new ArrayList<>();
		for (EditOp o : ops) {
			if (!o.is(EditOp.CROP)) {
				next.add(o);
			}
		}
		if (next.size() != ops.size()) {
			change(next);
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		double mx = event.x();
		double my = event.y();
		if (panning) {
			panX += dx;
			panY += dy;
			clampPan();
			return true;
		}
		if (erasing) {
			eraseAt(mx, my);
			return true;
		}
		Textures.Tex tex = tex();
		if (!tex.ready()) {
			return super.mouseDragged(event, dx, dy);
		}
		float[] r = imageRect(tex);
		if (moving != null) {
			moving = moveOrig.moved((float) ((mx - moveFromX) / r[2]), (float) ((my - moveFromY) / r[3]));
			return true;
		}
		if (draft != null) {
			float[] p = toImage(mx, my);
			if (draft.is(EditOp.PEN) || draft.is(EditOp.MARKER)) {
				float[] pts = draft.p;
				float lx = gx(r, pts[pts.length - 2]);
				float ly = gy(r, pts[pts.length - 1]);
				if (Math.hypot(mx - lx, my - ly) >= 1.5) {
					float[] next = java.util.Arrays.copyOf(pts, pts.length + 2);
					next[pts.length] = p[0];
					next[pts.length + 1] = p[1];
					draft.p = next;
				}
			} else {
				float x1 = p[0];
				float y1 = p[1];
				if (event.hasShiftDown()) {
					float[] c = constrain(draft, x1, y1, tex);
					x1 = c[0];
					y1 = c[1];
				}
				draft.p[2] = x1;
				draft.p[3] = y1;
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	/** Shift: lines snap to 15°, boxes become squares (in image pixels). */
	private static float[] constrain(EditOp op, float x1, float y1, Textures.Tex tex) {
		double W = tex.width;
		double H = tex.height;
		double dx = (x1 - op.p[0]) * W;
		double dy = (y1 - op.p[1]) * H;
		if (op.is(EditOp.LINE) || op.is(EditOp.ARROW)) {
			double len = Math.hypot(dx, dy);
			double a = Math.round(Math.atan2(dy, dx) / (Math.PI / 12)) * (Math.PI / 12);
			dx = Math.cos(a) * len;
			dy = Math.sin(a) * len;
		} else {
			double s = Math.max(Math.abs(dx), Math.abs(dy));
			dx = Math.copySign(s, dx);
			dy = Math.copySign(s, dy);
		}
		return new float[]{Mth.clamp((float) (op.p[0] + dx / W), 0f, 1f), Mth.clamp((float) (op.p[1] + dy / H), 0f, 1f)};
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (panning) {
			panning = false;
			return true;
		}
		if (erasing) {
			erasing = false;
			return true;
		}
		if (moving != null) {
			EditOp m = moving;
			moving = null;
			setOps(with(m));
			return true;
		}
		if (draft != null) {
			EditOp d = draft;
			draft = null;
			commitDraft(d);
			return true;
		}
		return super.mouseReleased(event);
	}

	private void commitDraft(EditOp d) {
		Textures.Tex tex = tex();
		if (!tex.ready()) {
			return;
		}
		float[] r = imageRect(tex);
		if (d.p.length >= 4 && !d.is(EditOp.PEN) && !d.is(EditOp.MARKER)) {
			double len = Math.hypot((d.p[2] - d.p[0]) * r[2], (d.p[3] - d.p[1]) * r[3]);
			boolean boxed = d.boxed();
			double minW = Math.abs(d.p[2] - d.p[0]) * r[2];
			double minH = Math.abs(d.p[3] - d.p[1]) * r[3];
			if (boxed ? minW < 3 || minH < 3 : len < 3) {
				return; // a click, not a shape
			}
		}
		if (d.is(EditOp.CROP)) {
			List<EditOp> next = new ArrayList<>();
			for (EditOp o : ops) {
				if (!o.is(EditOp.CROP)) {
					next.add(o);
				}
			}
			next.add(d);
			change(next);
			return;
		}
		change(with(d));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)) {
			nudgeSize(scrollY > 0 ? 1 : -1);
			return true;
		}
		if (!inImageArea(x, y)) {
			return false;
		}
		zoomAt(x, y, (float) (zoom * Math.pow(1.2, scrollY)));
		return true;
	}

	private void zoomAt(double mx, double my, float newZoom) {
		newZoom = Mth.clamp(newZoom, 1f, 10f);
		Textures.Tex tex = tex();
		if (!tex.ready()) {
			return;
		}
		int[] a = area();
		double cx = a[0] + a[2] / 2.0;
		double cy = a[1] + a[3] / 2.0;
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
		Textures.Tex tex = tex();
		if (!tex.ready()) {
			return;
		}
		int[] a = area();
		float[] r = imageRect(tex);
		double maxX = Math.max(0, (r[2] - a[2]) / 2.0 + 40);
		double maxY = Math.max(0, (r[3] - a[3]) / 2.0 + 40);
		if (zoom == 1) {
			maxX = maxY = 0;
		}
		panX = Mth.clamp(panX, -maxX, maxX);
		panY = Mth.clamp(panY, -maxY, maxY);
	}

	// ------------------------------------------------------------------ keyboard

	private void commitText() {
		if (textDraft == null) {
			return;
		}
		EditOp t = textDraft;
		EditOp orig = textOrig;
		textDraft = null;
		textOrig = null;
		boolean empty = t.text == null || t.text.isBlank();
		if (orig != null) {
			// pushUndo() happened when it was lifted.
			if (!empty) {
				setOps(with(t));
			} else {
				setOps(new ArrayList<>(ops)); // deleted by clearing its text
			}
		} else if (!empty) {
			change(with(t));
		}
	}

	private void cancelText() {
		if (textDraft == null) {
			return;
		}
		if (textOrig != null && !undo.isEmpty()) {
			setOps(undo.pop());
		}
		textDraft = null;
		textOrig = null;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (textDraft != null) {
			if (event.isAllowedChatCharacter()) {
				textDraft.text = (textDraft.text == null ? "" : textDraft.text) + event.codepointAsString();
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_SPACE && textDraft == null) {
			spaceDown = true;
		}
		if (textDraft != null) {
			if (event.isEscape()) {
				cancelText();
				return true;
			}
			if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				if (event.hasShiftDown()) {
					textDraft.text += "\n";
				} else {
					commitText();
				}
				return true;
			}
			if (key == InputConstants.KEY_BACKSPACE) {
				String s = textDraft.text;
				if (s != null && !s.isEmpty()) {
					textDraft.text = event.hasControlDownWithQuirk()
						? s.replaceAll("\\S*\\s*$", "")
						: s.substring(0, s.offsetByCodePoints(s.length(), -1));
				}
				return true;
			}
			if (event.isPaste()) {
				textDraft.text += minecraft.keyboardHandler.getClipboard().replace("\r", "");
				return true;
			}
			return true; // letters go to charTyped
		}
		if (event.hasControlDownWithQuirk()) {
			if (key == InputConstants.KEY_Z) {
				if (event.hasShiftDown()) {
					redo();
				} else {
					undo();
				}
				return true;
			}
			if (key == InputConstants.KEY_Y) {
				redo();
				return true;
			}
			if (event.isCopy()) {
				copy();
				return true;
			}
			if (key == InputConstants.KEY_S) {
				flush();
				setStatus(Component.translatable("smartshots.editor.saved"), Theme.GREEN);
				return true;
			}
		}
		if (key == InputConstants.KEY_LBRACKET || key == InputConstants.KEY_MINUS) {
			nudgeSize(-1);
			return true;
		}
		if (key == InputConstants.KEY_RBRACKET || key == InputConstants.KEY_EQUALS) {
			nudgeSize(1);
			return true;
		}
		if (key == InputConstants.KEY_0) {
			zoom = 1;
			panX = panY = 0;
			return true;
		}
		if (!event.hasControlDownWithQuirk() && !event.hasAltDown()) {
			for (Tool t : Tool.values()) {
				if (t.key == key) {
					selectTool(t);
					return true;
				}
			}
			if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
				int i = key - InputConstants.KEY_1;
				if (i < COLORS.length) {
					color = COLORS[i];
				}
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (event.key() == InputConstants.KEY_SPACE) {
			spaceDown = false;
		}
		return super.keyReleased(event);
	}

	/** Adds operations as one undo step (the menu tour uses it to show the editor with content). */
	public void addOps(List<EditOp> added) {
		List<EditOp> next = new ArrayList<>(ops);
		next.addAll(added);
		change(next);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
