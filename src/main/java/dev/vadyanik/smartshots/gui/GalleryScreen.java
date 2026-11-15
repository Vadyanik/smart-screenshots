package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import dev.vadyanik.smartshots.capture.CaptureScheduler;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatEditBox;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.search.Query;
import dev.vadyanik.smartshots.search.Suggestions;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.Trigger;
import dev.vadyanik.smartshots.storage.WorldContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** The in-game screenshot browser. */
public class GalleryScreen extends Screen {
	// Kept for other classes that still reference the old palette names.
	static final int TEXT = Theme.TEXT;
	static final int TEXT_DIM = Theme.TEXT_DIM;
	static final int TEXT_FAINT = Theme.TEXT_FAINT;
	static final int ACCENT = Theme.ACCENT;
	static final int GOLD = Theme.GOLD;

	private static final int TOP_H = 32;
	private static final int FILTER_H = 22;
	private static final int FOOTER_H = 16;
	private static final int GAP = 6;
	private static final int PAD = 10;
	private static final int TARGET_TILE_W = 120;
	private static final int ROW_H = 16;

	// State that survives closing / reopening
	private static String savedQuery = "";
	private static @Nullable String savedSelection;
	private static Filter savedFilter = Filter.ALL;
	private static boolean newestFirst = true;
	private static double savedScroll;
	private static final Set<String> EXPANDED = new HashSet<>();
	private static @Nullable String lastAutoSelectedWorld;

	private static @Nullable GalleryScreen open;
	static @Nullable Library library;
	private static @Nullable String scanError;
	private static boolean dirty = true;
	private static @Nullable CompletableFuture<Library> scanning;

	// Other launcher instances: scanned on their own thread after the local scan, kept across rescans.
	private static final ExecutorService OTHERS_IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SmartShots other instances");
		t.setDaemon(true);
		return t;
	});
	private static final long OTHERS_RESCAN_MS = 20_000;
	private static Library.@Nullable Node others;
	private static long othersScannedAt;
	private static @Nullable CompletableFuture<Library.@Nullable Node> scanningOthers;

	enum Filter {
		ALL("all", null), MANUAL("manual", Trigger.MANUAL), ADVANCEMENT("advancement", Trigger.ADVANCEMENT),
		NEW_PLAYER("new_player", Trigger.NEW_PLAYER);

		final String key;
		final @Nullable Trigger trigger;

		Filter(String key, @Nullable Trigger trigger) {
			this.key = key;
			this.trigger = trigger;
		}
	}

	private final @Nullable Screen parent;
	private EditBox search;
	/** Tile under the mouse in the last frame (Ctrl+C copies it). */
	private Library.@Nullable Shot hoveredShot;
	private long copyAt;
	private boolean copyOk;
	private int searchX;
	private int searchW;
	private Query query = Query.parse("");
	private @Nullable Suggestions suggestions;
	private List<Suggestions.Entry> currentSuggestions = List.of();
	private Library.@Nullable Node selected;
	/** Saved selection that wasn't found yet (an other instance's node before that scan is done). */
	private @Nullable String pendingSelection;
	/** Shots in the selected scope before filters (footer "x of y"). */
	private int scopeCount;
	private final List<Library.Shot> visible = new ArrayList<>();
	private final List<LayoutItem> layout = new ArrayList<>();
	private int contentHeight;
	private double scroll;
	private double scrollTarget;
	private double sidebarScroll;
	private final List<SidebarRow> sidebarRows = new ArrayList<>();
	private int sidebarW;
	private int gridX;
	private int gridY;
	private int gridW;
	private int gridH;
	private int tileW;
	private int tileH;
	private final List<Hit> hits = new ArrayList<>();

	public GalleryScreen(@Nullable Screen parent) {
		super(Component.translatable("smartshots.gallery.title"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ lifecycle

	/** Called whenever a new screenshot has been written. */
	public static void onScreenshotAdded() {
		dirty = true;
		if (open != null) {
			open.rescan();
		}
	}

	static void markDirty() {
		dirty = true;
	}

	/**
	 * Main thread: background work (the mask merge) changed a shot's stored metadata. Applies the
	 * same change to the in-memory copies (library, open viewer) so they show it without a rescan.
	 */
	public static void onMetaUpdated(Path file, Consumer<ShotMeta> change) {
		Set<ShotMeta> done = Collections.newSetFromMap(new IdentityHashMap<>());
		if (library != null) {
			for (Library.Shot s : library.shots) {
				if (s.file.equals(file) && done.add(s.meta)) {
					change.accept(s.meta);
					s.refreshDoc();
				}
			}
		}
		boolean inLibrary = !done.isEmpty();
		if (Minecraft.getInstance().gui.screen() instanceof ViewerScreen viewer) {
			viewer.onMetaUpdated(file, change, done);
		}
		Highlights.maskWritten(file);
		if (!inLibrary) {
			// Not in the loaded library (or a scan is running): pick it up from disk.
			onScreenshotAdded();
		} else if (open != null) {
			open.refreshAfterEdit();
		}
	}

	@Override
	protected void init() {
		PlayerNames.clearCache();
		open = this;
		sidebarW = Mth.clamp(this.width / 4, 120, 180);
		gridX = sidebarW + PAD;
		gridY = TOP_H + FILTER_H + 6;
		gridW = this.width - gridX - PAD;
		gridH = this.height - gridY - FOOTER_H;

		searchX = sidebarW + PAD;
		searchW = this.width - searchX - PAD - 3 * 22 - 4;
		String old = search != null ? search.getValue() : savedQuery;
		search = FlatEditBox.create(this.font, searchX, 6, searchW, 20, true, Component.translatable("smartshots.search.narration"));
		search.setMaxLength(256);
		search.setHint(Component.translatable("smartshots.search.hint").withColor(Theme.TEXT_FAINT));
		search.setValue(old);
		search.setResponder(this::onSearchChanged);
		addRenderableWidget(search);

		int bx = this.width - PAD - 3 * 22 + 2;
		addRenderableWidget(FlatButton.icon(bx, 6, Ui.SETTINGS, Component.translatable("smartshots.button.settings"),
			b -> minecraft.gui.setScreen(new SettingsScreen(this))));
		addRenderableWidget(FlatButton.icon(bx + 22, 6, Ui.FOLDER, Component.translatable("smartshots.button.open_folder"), b -> openFolder()));
		addRenderableWidget(FlatButton.icon(bx + 44, 6, Ui.CLOSE, Component.translatable("smartshots.button.close"), b -> onClose()));

		if (library == null || dirty) {
			rescan();
		} else {
			onLibraryReady();
		}
		scanOthers();
	}

	private void openFolder() {
		Path dir = selected != null && selected.dir != null ? selected.dir : ScreenshotStore.root();
		if (!Files.isDirectory(dir)) {
			dir = ScreenshotStore.root();
		}
		Blaze3D.openPath(dir);
	}

	private void rescan() {
		if (scanning != null && !scanning.isDone()) {
			return;
		}
		dirty = false;
		scanning = CompletableFuture.supplyAsync(() -> Library.scan(ScreenshotStore.root()), ScreenshotStore.IO);
		scanning.whenComplete((lib, err) -> minecraft.execute(() -> {
			if (err != null) {
				dev.vadyanik.smartshots.SmartShots.LOGGER.error("Couldn't scan the screenshots folder", err);
				scanError = String.valueOf(err.getCause() != null ? err.getCause() : err);
				return;
			}
			scanError = null;
			lib.attachOthers(others);
			library = lib;
			if (open != null) {
				open.onLibraryReady();
				if (dirty) {
					open.rescan(); // something changed on disk while scanning
				}
			}
		}));
	}

	/** Scans the other launcher instances in the background, at most every {@link #OTHERS_RESCAN_MS}. */
	private void scanOthers() {
		if (scanningOthers != null && !scanningOthers.isDone()
			|| others != null && System.currentTimeMillis() - othersScannedAt < OTHERS_RESCAN_MS) {
			return;
		}
		othersScannedAt = System.currentTimeMillis();
		Path gameDir = minecraft.gameDirectory.toPath();
		Path root = ScreenshotStore.root();
		scanningOthers = CompletableFuture.supplyAsync(() -> Library.scanOthers(gameDir, root), OTHERS_IO);
		scanningOthers.whenComplete((group, err) -> minecraft.execute(() -> {
			if (err != null) {
				dev.vadyanik.smartshots.SmartShots.LOGGER.warn("Couldn't scan other instances", err);
				return;
			}
			others = group;
			othersScannedAt = System.currentTimeMillis();
			if (library != null) {
				library.attachOthers(group);
				if (open != null) {
					open.onOthersReady();
				}
			}
		}));
	}

	private void onOthersReady() {
		if (library == null) {
			return;
		}
		suggestions = new Suggestions(library);
		// The nodes were replaced: find the selection again by id.
		String id = pendingSelection != null ? pendingSelection : selected != null ? selected.id() : null;
		Library.Node n = findNode(id);
		if (n != null) {
			selected = n;
			pendingSelection = null;
		} else if (selected != null && selected.foreign()) {
			selected = null;
		}
		rebuildSidebar();
		applyFilters();
	}

	private void onLibraryReady() {
		if (library == null) {
			return;
		}
		suggestions = new Suggestions(library);
		String id = selected != null ? selected.id() : savedSelection;
		selected = findNode(id);
		pendingSelection = selected == null ? id : null;
		autoSelectCurrentWorld();
		rebuildSidebar();
		applyFilters();
		scroll = scrollTarget = Mth.clamp(savedScroll, 0, maxScroll());
	}

	/** First time the gallery is opened in a world, jump to that world's folder. */
	private void autoSelectCurrentWorld() {
		if (minecraft.level == null || library == null) {
			return;
		}
		try {
			var loc = WorldContext.resolve(minecraft);
			String key = loc.instanceDir().toString();
			if (key.equals(lastAutoSelectedWorld)) {
				return;
			}
			for (Library.Node cat : library.categories) {
				for (Library.Node inst : cat.children) {
					if (inst.dir.equals(loc.instanceDir())) {
						lastAutoSelectedWorld = key;
						selected = inst;
						EXPANDED.add(inst.id());
						savedScroll = 0;
						return;
					}
				}
			}
		} catch (Exception ignored) {
		}
	}

	@Override
	public void removed() {
		savedQuery = search.getValue();
		savedSelection = selected != null ? selected.id() : null;
		savedScroll = scroll;
		if (open == this) {
			open = null;
		}
	}

	@Override
	public void onClose() {
		Textures.releaseAll();
		minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ filtering

	private void onSearchChanged(String text) {
		query = Query.parse(text);
		search.setTextColor(Query.hasBadRegex(text) ? Theme.DANGER : Theme.TEXT);
		updateSuggestions();
		applyFilters();
		scroll = scrollTarget = 0;
	}

	private void updateSuggestions() {
		currentSuggestions = List.of();
		if (suggestions == null || !search.isFocused()) {
			return;
		}
		String text = search.getValue();
		if (text.isEmpty() || text.endsWith(" ")) {
			return;
		}
		int space = text.lastIndexOf(' ');
		String word = text.substring(space + 1);
		if (word.startsWith("-")) {
			word = word.substring(1);
		}
		if (word.contains("/")) {
			return; // a /regex/: nothing to complete, and Tab must not replace it
		}
		currentSuggestions = suggestions.complete(word, 7);
		if (currentSuggestions.size() == 1 && currentSuggestions.getFirst().insert().equalsIgnoreCase(word)) {
			currentSuggestions = List.of();
		}
	}

	private void acceptSuggestion(Suggestions.Entry e) {
		String text = search.getValue();
		int space = text.lastIndexOf(' ');
		String word = text.substring(space + 1);
		String prefix = word.startsWith("-") ? "-" : "";
		search.setValue(text.substring(0, space + 1) + prefix + e.insert() + " ");
		search.moveCursorToEnd(false);
		currentSuggestions = List.of();
	}

	void applyFilters() {
		visible.clear();
		if (library == null) {
			return;
		}
		Filter filter = savedFilter;
		boolean showAuto = SmartShotsConfig.get().galleryShowAuto || query.asksForAuto();
		scopeCount = 0;
		for (Library.Shot s : library.shots) {
			if (selected == null || selected.kind == Library.Kind.ALL || selected.kind == Library.Kind.FAVORITES) {
				// Other instances only show under their own sidebar node.
				if (s.pack != null) {
					continue;
				}
				if (selected != null && selected.kind == Library.Kind.FAVORITES && !s.meta.favorite) {
					continue;
				}
			} else if (!selected.contains(s)) {
				continue;
			}
			scopeCount++;
			if (filter.trigger != null && s.meta.trigger() != filter.trigger) {
				continue;
			}
			if (!showAuto && s.meta.trigger() == Trigger.AUTO) {
				continue;
			}
			if (!query.isEmpty() && !query.matches(s)) {
				continue;
			}
			visible.add(s);
		}
		if (!newestFirst) {
			visible.sort(Comparator.comparingLong(s -> s.meta.time));
		}
		relayout();
	}

	// ------------------------------------------------------------------ layout

	private sealed interface LayoutItem permits Header, Tile {
	}

	private record Header(String text, int y, int count) implements LayoutItem {
	}

	private record Tile(Library.Shot shot, int index, int x, int y) implements LayoutItem {
	}

	private void relayout() {
		layout.clear();
		int cols = Math.max(1, (gridW + GAP) / (TARGET_TILE_W + GAP));
		tileW = (gridW - GAP * (cols - 1)) / cols;
		tileH = tileW * 9 / 16;
		int y = 0;
		LocalDate currentDay = null;
		int col = 0;
		Header pendingHeader = null;
		int headerIndex = -1;
		int countInDay = 0;
		for (int i = 0; i < visible.size(); i++) {
			Library.Shot s = visible.get(i);
			LocalDate day = Instant.ofEpochMilli(s.meta.time).atZone(ZoneId.systemDefault()).toLocalDate();
			if (!day.equals(currentDay)) {
				if (headerIndex >= 0) {
					layout.set(headerIndex, new Header(pendingHeader.text, pendingHeader.y, countInDay));
				}
				if (col != 0) {
					y += tileH + GAP;
					col = 0;
				}
				if (currentDay != null) {
					y += 6;
				}
				currentDay = day;
				pendingHeader = new Header(dayLabel(day), y, 0);
				headerIndex = layout.size();
				layout.add(pendingHeader);
				countInDay = 0;
				y += 16;
			}
			layout.add(new Tile(s, i, gridX + col * (tileW + GAP), y));
			countInDay++;
			col++;
			if (col >= cols) {
				col = 0;
				y += tileH + GAP;
			}
		}
		if (headerIndex >= 0) {
			layout.set(headerIndex, new Header(pendingHeader.text, pendingHeader.y, countInDay));
		}
		if (col != 0) {
			y += tileH + GAP;
		}
		contentHeight = y;
		scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
		scroll = Mth.clamp(scroll, 0, maxScroll());
	}

	private String dayLabel(LocalDate day) {
		LocalDate today = LocalDate.now();
		if (day.equals(today)) {
			return Component.translatable("smartshots.date.today").getString();
		}
		if (day.equals(today.minusDays(1))) {
			return Component.translatable("smartshots.date.yesterday").getString();
		}
		return day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(gameLocale()));
	}

	static Locale gameLocale() {
		String code = Minecraft.getInstance().options.languageCode;
		String[] parts = code.split("_");
		return parts.length == 2 ? Locale.of(parts[0], parts[1].toUpperCase(Locale.ROOT)) : Locale.of(parts[0]);
	}

	private double maxScroll() {
		return Math.max(0, contentHeight - gridH + 4);
	}

	// ------------------------------------------------------------------ sidebar

	private record SidebarRow(Library.Node node, String label, int indent, int count, boolean header, boolean expandable,
							  boolean current, @Nullable Identifier icon, boolean align) {
		SidebarRow(Library.Node node, String label, int indent, int count, boolean header, boolean expandable,
				   boolean current, @Nullable Identifier icon) {
			this(node, label, indent, count, header, expandable, current, icon, false);
		}
	}

	private Library.@Nullable Node allNode;
	private Library.@Nullable Node favNode;

	private void rebuildSidebar() {
		sidebarRows.clear();
		if (library == null) {
			return;
		}
		allNode = new Library.Node(Library.Kind.ALL, Component.translatable("smartshots.sidebar.all").getString(), null, null, null);
		favNode = new Library.Node(Library.Kind.FAVORITES, Component.translatable("smartshots.sidebar.favorites").getString(), null, null, null);
		if (selected == null) {
			selected = savedSelection != null && savedSelection.startsWith("FAVORITES") ? favNode : allNode;
		} else if (selected.kind == Library.Kind.ALL) {
			selected = allNode;
		} else if (selected.kind == Library.Kind.FAVORITES) {
			selected = favNode;
		}
		long favs = library.shots.stream().filter(s -> s.pack == null && s.meta.favorite).count();
		sidebarRows.add(new SidebarRow(allNode, allNode.name, 0, library.localCount(), false, false, false, Ui.ALL));
		sidebarRows.add(new SidebarRow(favNode, favNode.name, 0, (int) favs, false, false, false, Ui.STAR_SMALL));

		Path currentLoc = null;
		if (minecraft.level != null) {
			try {
				currentLoc = WorldContext.resolve(minecraft).locationDir();
			} catch (Exception ignored) {
			}
		}
		for (Library.Node cat : library.categories) {
			sidebarRows.add(new SidebarRow(cat, cat.name.toUpperCase(gameLocale()), 0, cat.count(), true, false, false, null));
			for (Library.Node inst : cat.children) {
				boolean expandable = inst.children.size() > 1 || (inst.children.size() == 1 && !inst.shots.isEmpty());
				boolean isCurrent = currentLoc != null && currentLoc.startsWith(inst.dir);
				sidebarRows.add(new SidebarRow(inst, inst.name, 0, inst.count(), false, expandable, isCurrent, null));
				if (expandable && EXPANDED.contains(inst.id())) {
					for (Library.Node loc : inst.children) {
						sidebarRows.add(new SidebarRow(loc, loc.name, 1, loc.count(), false, false, loc.dir.equals(currentLoc), null));
					}
				}
			}
		}
		if (library.unsorted != null) {
			library.unsorted.name = Component.translatable("smartshots.sidebar.unsorted").getString();
			sidebarRows.add(new SidebarRow(library.unsorted, "", 0, 0, true, false, false, null));
			sidebarRows.add(new SidebarRow(library.unsorted, library.unsorted.name, 0, library.unsorted.count(), false, false, false, Ui.FOLDER));
		}
		addOtherInstanceRows(library.others);
	}

	/**
	 * Collapsible "Other instances" group: one row per instance, below it that instance's worlds
	 * (categories flattened), their sub-worlds and its unsorted shots.
	 */
	private void addOtherInstanceRows(Library.@Nullable Node group) {
		if (group == null) {
			return;
		}
		group.name = Component.translatable("smartshots.sidebar.others").getString();
		sidebarRows.add(new SidebarRow(group, "", 0, 0, true, false, false, null));
		sidebarRows.add(new SidebarRow(group, group.name, 0, group.count(), false, true, false, Ui.FOLDER, true));
		if (!EXPANDED.contains(group.id())) {
			return;
		}
		String unsortedName = Component.translatable("smartshots.sidebar.unsorted").getString();
		for (Library.Node pack : group.children) {
			List<Library.Node> worlds = new ArrayList<>();
			Library.Node unsorted = null;
			for (Library.Node c : pack.children) {
				if (c.kind == Library.Kind.UNSORTED) {
					unsorted = c;
				} else {
					worlds.addAll(c.children);
				}
			}
			worlds.sort(Comparator.comparingLong((Library.Node n) -> n.newest).reversed());
			boolean packExp = !worlds.isEmpty();
			sidebarRows.add(new SidebarRow(pack, pack.name, 1, pack.count(), false, packExp, false, null, true));
			if (!packExp || !EXPANDED.contains(pack.id())) {
				continue;
			}
			for (Library.Node inst : worlds) {
				boolean expandable = inst.children.size() > 1 || (inst.children.size() == 1 && !inst.shots.isEmpty());
				sidebarRows.add(new SidebarRow(inst, inst.name, 2, inst.count(), false, expandable, false, null, true));
				if (expandable && EXPANDED.contains(inst.id())) {
					for (Library.Node loc : inst.children) {
						sidebarRows.add(new SidebarRow(loc, loc.name, 3, loc.count(), false, false, false, null, true));
					}
				}
			}
			if (unsorted != null) {
				unsorted.name = unsortedName;
				sidebarRows.add(new SidebarRow(unsorted, unsorted.name, 2, unsorted.count(), false, false, false, null, true));
			}
		}
	}

	private Library.@Nullable Node findNode(@Nullable String id) {
		if (id == null || library == null) {
			return null;
		}
		if (id.startsWith("ALL")) {
			return new Library.Node(Library.Kind.ALL, "", null, null, null);
		}
		if (id.startsWith("FAVORITES")) {
			return new Library.Node(Library.Kind.FAVORITES, "", null, null, null);
		}
		if (library.unsorted != null && library.unsorted.id().equals(id)) {
			return library.unsorted;
		}
		for (Library.Node cat : library.categories) {
			Library.Node n = findIn(cat, id);
			if (n != null) {
				return n;
			}
		}
		return library.others != null ? findIn(library.others, id) : null;
	}

	private static Library.@Nullable Node findIn(Library.Node node, String id) {
		if (node.id().equals(id)) {
			return node;
		}
		for (Library.Node c : node.children) {
			Library.Node n = findIn(c, id);
			if (n != null) {
				return n;
			}
		}
		return null;
	}

	private int sidebarTop() {
		return TOP_H + 4;
	}

	private int rowHeight(SidebarRow r) {
		return r.header ? 20 : ROW_H;
	}

	private int sidebarContentHeight() {
		int h = 0;
		for (SidebarRow r : sidebarRows) {
			h += rowHeight(r);
		}
		return h + 6;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (minecraft.level == null) {
			extractPanorama(g, a);
		}
		extractBlurredBackground(g);
		g.fill(0, 0, width, height, Theme.OVERLAY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		scroll += (scrollTarget - scroll) * 0.45;
		if (Math.abs(scrollTarget - scroll) < 0.5) {
			scroll = scrollTarget;
		}
		hits.clear();

		// Sidebar panel + title
		g.fill(0, 0, sidebarW, height, Theme.SURFACE);
		g.verticalLine(sidebarW, 0, height, Theme.BORDER);
		g.text(font, title, PAD + 2, 12, Theme.TEXT, false);

		FlatEditBox.drawFrame(g, search, searchX, 6, searchW, 20, Ui.SEARCH);

		renderSidebar(g, mouseX, mouseY);
		renderFilters(g, mouseX, mouseY);
		Library.Shot hovered = renderGrid(g, mouseX, mouseY);
		hoveredShot = hovered;
		renderFooter(g);

		super.extractRenderState(g, mouseX, mouseY, a);
		renderSuggestions(g, mouseX, mouseY);

		if (hovered != null && currentSuggestions.isEmpty()) {
			g.setComponentTooltipForNextFrame(font, ShotInfo.tooltip(hovered), mouseX, mouseY);
		}
	}

	private void renderSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int top = sidebarTop();
		int bottom = height - 4;
		g.enableScissor(0, top, sidebarW, bottom);
		int y = top - (int) sidebarScroll;
		for (SidebarRow row : sidebarRows) {
			int h = rowHeight(row);
			if (row.header) {
				if (!row.label.isEmpty()) {
					g.text(font, row.label, PAD + 2, y + 9, Theme.TEXT_FAINT, false);
				}
				y += h;
				continue;
			}
			boolean isSel = selected != null && row.node.id().equals(selected.id());
			boolean hover = mouseX >= 0 && mouseX < sidebarW && mouseY >= Math.max(y, top) && mouseY < Math.min(y + h, bottom);
			int rx = 6;
			int rw = sidebarW - 12;
			if (isSel) {
				Ui.rect(g, rx, y, rw, h, Theme.ACCENT_SOFT);
			} else if (hover) {
				Ui.rect(g, rx, y, rw, h, Theme.HOVER);
			}
			int x = rx + 6 + row.indent * 10;
			int textColor = isSel ? Theme.ACCENT : hover ? Theme.TEXT : Theme.TEXT_DIM;
			if (row.expandable) {
				boolean exp = EXPANDED.contains(row.node.id());
				Identifier chev = exp ? Ui.EXPAND_DOWN : Ui.EXPAND_RIGHT;
				int[] s = Ui.size(chev);
				Ui.icon(g, chev, x + (5 - s[0]) / 2, y + (h - s[1]) / 2, s[0], s[1], Theme.TEXT_FAINT);
				x += 9;
			} else if (row.align || row.indent == 0 && row.icon == null) {
				x += 9;
			}
			if (row.icon != null) {
				int[] s = Ui.size(row.icon);
				Ui.icon(g, row.icon, x, y + (h - s[1]) / 2, s[0], s[1], isSel ? Theme.ACCENT : Theme.TEXT_FAINT);
				x += s[0] + 5;
			}
			String count = String.valueOf(row.count);
			int countW = font.width(count);
			int markerW = row.current ? 9 : 0;
			int avail = rx + rw - 6 - countW - 6 - markerW - x;
			g.text(font, Ui.ellipsize(font, row.label, avail), x, y + 4, textColor, false);
			int lw = Math.min(font.width(row.label), avail);
			if (row.current) {
				Ui.icon(g, Ui.DOT, x + lw + 4, y + 6, 5, 5, Theme.GREEN);
			}
			g.text(font, count, rx + rw - 6 - countW, y + 4, isSel ? Theme.ACCENT : Theme.TEXT_FAINT, false);
			y += h;
		}
		g.disableScissor();
		int total = sidebarContentHeight();
		int viewH = bottom - top;
		if (total > viewH) {
			int barH = Math.max(12, viewH * viewH / total);
			int barY = top + (int) ((viewH - barH) * (sidebarScroll / (total - viewH)));
			Ui.rect(g, sidebarW - 4, barY, 2, barH, Theme.BORDER_STRONG);
		}
	}

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private void renderFilters(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y = TOP_H + 2;
		int h = 18;
		// Segmented control
		int x = gridX;
		int segX = x;
		int totalW = 0;
		for (Filter f : Filter.values()) {
			totalW += segmentWidth(f);
		}
		Ui.rect(g, segX, y, totalW + 4, h, Theme.SURFACE);
		Ui.border(g, segX, y, totalW + 4, h, Theme.BORDER);
		x += 2;
		for (Filter f : Filter.values()) {
			int w = segmentWidth(f);
			boolean active = savedFilter == f;
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
			if (active) {
				Ui.rect(g, x, y + 2, w, h - 4, Theme.SURFACE_3);
			} else if (hover) {
				Ui.rect(g, x, y + 2, w, h - 4, Theme.HOVER);
			}
			int tx = x + 8;
			if (f.trigger != null) {
				Ui.icon(g, Ui.DOT, tx, y + 7, 5, 5, active ? f.trigger.color : Theme.alpha(f.trigger.color, 0.6f));
				tx += 9;
			}
			g.text(font, label(f), tx, y + 5, active ? Theme.TEXT : hover ? Theme.TEXT : Theme.TEXT_DIM, false);
			hits.add(new Hit(x, y, w, h, () -> {
				savedFilter = f;
				applyFilters();
				scroll = scrollTarget = 0;
			}));
			x += w;
		}

		// Show-auto toggle
		String autoLabel = Component.translatable("smartshots.filter.show_auto").getString();
		boolean autoOn = SmartShotsConfig.get().galleryShowAuto;
		int sortW = font.width(Component.translatable(newestFirst ? "smartshots.sort.newest" : "smartshots.sort.oldest").getString()) + 24;
		int ax = segX + totalW + 4 + 6;
		int aw = font.width(autoLabel) + 25;
		boolean compact = ax + aw + 6 + sortW > width - PAD;
		if (compact) {
			aw = 22;
		}
		boolean ahover = mouseX >= ax && mouseX < ax + aw && mouseY >= y && mouseY < y + h;
		Ui.rect(g, ax, y, aw, h, autoOn ? Theme.alpha(Theme.GREEN, 0.18f) : ahover ? Theme.HOVER : Theme.SURFACE);
		Ui.border(g, ax, y, aw, h, autoOn ? Theme.alpha(Theme.GREEN, 0.7f) : Theme.BORDER);
		Ui.icon(g, Ui.CLOCK, ax + 7, y + 4, 9, 9, autoOn ? Theme.GREEN : Theme.TEXT_FAINT);
		if (!compact) {
			g.text(font, autoLabel, ax + 19, y + 5, autoOn ? Theme.TEXT : ahover ? Theme.TEXT : Theme.TEXT_DIM, false);
		}
		hits.add(new Hit(ax, y, aw, h, () -> {
			SmartShotsConfig.get().galleryShowAuto = !SmartShotsConfig.get().galleryShowAuto;
			SmartShotsConfig.save();
			applyFilters();
			scroll = scrollTarget = 0;
		}));
		if (ahover) {
			g.setTooltipForNextFrame(font, Component.translatable("smartshots.filter.show_auto.tooltip"), mouseX, mouseY);
		}

		// Sort toggle
		String sort = Component.translatable(newestFirst ? "smartshots.sort.newest" : "smartshots.sort.oldest").getString();
		int sw = font.width(sort) + 24;
		int sx = width - PAD - sw;
		boolean hover = mouseX >= sx && mouseX < sx + sw && mouseY >= y && mouseY < y + h;
		if (hover) {
			Ui.rect(g, sx, y, sw, h, Theme.HOVER);
		}
		Ui.icon(g, Ui.SORT, sx + 7, y + 5, 6, 8, newestFirst ? Theme.TEXT_FAINT : Theme.ACCENT);
		g.text(font, sort, sx + 18, y + 5, hover ? Theme.TEXT : Theme.TEXT_DIM, false);
		hits.add(new Hit(sx, y, sw, h, () -> {
			newestFirst = !newestFirst;
			applyFilters();
		}));
	}

	private String label(Filter f) {
		return Component.translatable("smartshots.filter." + f.key).getString();
	}

	private int segmentWidth(Filter f) {
		return font.width(label(f)) + 16 + (f.trigger != null ? 9 : 0);
	}

	private Library.@Nullable Shot renderGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Library.Shot hovered = null;
		int cx = gridX + gridW / 2;
		int cy = gridY + gridH / 2;
		if (library == null) {
			if (scanError != null) {
				g.centeredText(font, Component.translatable("smartshots.gallery.scan_failed"), cx, cy - 6, Theme.TEXT_DIM);
				g.centeredText(font, font.plainSubstrByWidth(scanError, gridW - 20), cx, cy + 8, Theme.TEXT_FAINT);
				return null;
			}
			g.centeredText(font, Component.translatable("smartshots.gallery.loading"), cx, cy, Theme.TEXT_FAINT);
			return null;
		}
		if (visible.isEmpty()) {
			boolean empty = scopeCount == 0;
			Ui.iconCentered(g, empty ? Ui.ALL : Ui.SEARCH, cx - 10, cy - 34, 20, 20, Theme.TEXT_FAINT);
			g.centeredText(font, Component.translatable(empty ? "smartshots.gallery.empty" : "smartshots.gallery.no_results"), cx, cy - 8, Theme.TEXT_DIM);
			if (!empty) {
				g.centeredText(font, Component.translatable("smartshots.gallery.search_help"), cx, cy + 6, Theme.TEXT_FAINT);
			}
			return null;
		}
		boolean mouseInGrid = mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH;
		g.enableScissor(gridX - 2, gridY, gridX + gridW + 2, gridY + gridH);
		int off = gridY - (int) scroll;
		int preload = tileH * 2;
		for (LayoutItem item : layout) {
			if (item instanceof Header h) {
				int y = off + h.y;
				if (y > gridY + gridH || y + 16 < gridY) {
					continue;
				}
				g.text(font, h.text, gridX, y + 4, Theme.TEXT, false);
				g.text(font, String.valueOf(h.count), gridX + font.width(h.text) + 6, y + 4, Theme.TEXT_FAINT, false);
				continue;
			}
			Tile t = (Tile) item;
			int y = off + t.y;
			if (y > gridY + gridH + preload || y + tileH < gridY - preload) {
				continue;
			}
			Textures.Tex tex = Textures.thumb(t.shot.file);
			if (y > gridY + gridH || y + tileH < gridY) {
				continue;
			}
			boolean hover = mouseInGrid && mouseX >= t.x && mouseX < t.x + tileW && mouseY >= y && mouseY < y + tileH;
			drawTile(g, t.shot, tex, t.x, y, hover);
			if (hover) {
				hovered = t.shot;
			}
		}
		g.disableScissor();

		double max = maxScroll();
		if (max > 0) {
			int barH = Math.max(16, (int) (gridH * (gridH / (double) contentHeight)));
			int barY = gridY + (int) ((gridH - barH) * (scroll / max));
			Ui.rect(g, width - 5, barY, 2, barH, Theme.BORDER_STRONG);
		}
		return hovered;
	}

	private void drawTile(GuiGraphicsExtractor g, Library.Shot shot, Textures.Tex tex, int x, int y, boolean hover) {
		Ui.rect(g, x, y, tileW, tileH, Theme.SURFACE_2);
		if (tex.ready()) {
			Textures.drawCover(g, tex, x, y, tileW, tileH);
			float fade = tex.fade();
			if (fade < 1) {
				g.fill(x, y, x + tileW, y + tileH, Theme.alpha(Theme.SURFACE_2, 1 - fade));
			}
		} else if (tex.failed()) {
			Ui.iconCentered(g, Ui.CLOSE, x, y, tileW, tileH, Theme.TEXT_FAINT);
		} else {
			long t = System.currentTimeMillis() / 5 % (tileW + 60);
			int sx = x - 30 + (int) t;
			g.fill(Math.max(x, sx), y, Math.min(x + tileW, sx + 30), y + tileH, 0x0AFFFFFF);
		}

		// Badges
		Trigger trig = shot.meta.trigger();
		int bx = x + 4;
		if (trig == Trigger.ADVANCEMENT) {
			bx += badge(g, bx, y + 4, Ui.TROPHY, null, Theme.GOLD);
		} else if (trig == Trigger.AUTO) {
			bx += badge(g, bx, y + 4, Ui.CLOCK, null, Theme.GREEN);
		} else if (trig == Trigger.NEW_PLAYER) {
			bx += badge(g, bx, y + 4, Ui.PERSON, null, trig.color);
		}
		if (shot.meta.tags.contains("freecam")) {
			bx += badge(g, bx, y + 4, Ui.FREECAM, null, Theme.PURPLE);
		}
		if (!shot.meta.players.isEmpty()) {
			badge(g, bx, y + 4, Ui.PERSON, String.valueOf(shot.meta.players.size()), Theme.CYAN);
		}
		if (shot.meta.favorite) {
			Ui.rect(g, x + tileW - 17, y + 4, 13, 12, 0xB0000000);
			Ui.icon(g, Ui.STAR_SMALL, x + tileW - 15, y + 6, 9, 8, Theme.GOLD);
		}

		if (hover) {
			String time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(shot.meta.time));
			Library.Node inst = shot.instance();
			String where = inst != null ? inst.name : shot.pack != null ? shot.pack.name : "";
			g.fillGradient(x, y + tileH - 22, x + tileW, y + tileH, 0x00000000, 0xC0000000);
			g.text(font, time, x + 5, y + tileH - 11, Theme.TEXT, false);
			int tw = font.width(time);
			g.text(font, Ui.ellipsize(font, where, tileW - tw - 16), x + 11 + tw, y + tileH - 11, Theme.TEXT_DIM, false);
			Ui.border(g, x - 1, y - 1, tileW + 2, tileH + 2, Theme.alpha(Theme.ACCENT, 0.9f));
		}
	}

	private int badge(GuiGraphicsExtractor g, int x, int y, Identifier icon, @Nullable String text, int color) {
		int[] s = Ui.size(icon);
		int w = s[0] + 6 + (text != null ? font.width(text) + 3 : 0);
		Ui.rect(g, x, y, w, 12, 0xB0000000);
		Ui.icon(g, icon, x + 3, y + (12 - s[1]) / 2, s[0], s[1], color);
		if (text != null) {
			g.text(font, text, x + s[0] + 5, y + 2, color, false);
		}
		return w + 3;
	}

	private void renderFooter(GuiGraphicsExtractor g) {
		int y = height - FOOTER_H + 4;
		String left = library == null ? "" : Component.translatable("smartshots.gallery.count", visible.size(), scopeCount).getString();
		g.text(font, left, gridX, y, Theme.TEXT_FAINT, false);
		if (System.currentTimeMillis() - copyAt < 2500) {
			String msg = Component.translatable(copyOk ? "smartshots.copy.ok" : "smartshots.copy.fail").getString();
			g.text(font, msg, gridX + font.width(left) + 12, y, copyOk ? Theme.GREEN : Theme.DANGER, false);
		}
		int secs = CaptureScheduler.secondsUntilAuto();
		String right = secs < 0
			? Component.translatable("smartshots.gallery.auto_off").getString()
			: Component.translatable("smartshots.gallery.auto_next", secs / 60, String.format("%02d", secs % 60)).getString();
		int rw = font.width(right);
		Ui.icon(g, Ui.CLOCK, width - PAD - rw - 13, y - 1, 9, 9, secs < 0 ? Theme.TEXT_FAINT : Theme.GREEN);
		g.text(font, right, width - PAD - rw, y, Theme.TEXT_FAINT, false);
	}

	private void renderSuggestions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (currentSuggestions.isEmpty() || !search.isFocused()) {
			return;
		}
		g.nextStratum();
		int x = searchX;
		int y = 6 + 20 + 3;
		int w = Math.min(searchW, 280);
		int rowH = 14;
		int h = currentSuggestions.size() * rowH + 6;
		Ui.rect(g, x, y, w, h, Theme.SURFACE_2);
		Ui.border(g, x, y, w, h, Theme.BORDER_STRONG);
		int ry = y + 3;
		for (Suggestions.Entry e : currentSuggestions) {
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH;
			if (hover) {
				Ui.rect(g, x + 3, ry, w - 6, rowH, Theme.HOVER);
			}
			int color = kindColor(e.kind());
			Ui.icon(g, Ui.DOT, x + 8, ry + 4, 5, 5, color);
			String kind = Component.translatable("smartshots.kind." + e.kind()).getString();
			String count = String.valueOf(e.count());
			int kindW = font.width(kind);
			int countW = font.width(count);
			g.text(font, Ui.ellipsize(font, e.label(), w - kindW - countW - 40), x + 18, ry + 3, Theme.TEXT, false);
			g.text(font, kind, x + w - countW - kindW - 16, ry + 3, Theme.TEXT_FAINT, false);
			g.text(font, count, x + w - countW - 8, ry + 3, Theme.TEXT_DIM, false);
			ry += rowH;
		}
	}

	private static int kindColor(String kind) {
		return switch (kind) {
			case "player" -> Theme.CYAN;
			case "mob" -> Theme.CORAL;
			case "block" -> Theme.LIME;
			case "biome" -> Theme.MINT;
			case "adv" -> Theme.GOLD;
			case "special" -> Theme.PURPLE;
			case "sign" -> 0xFFC8A060;
			default -> Theme.TEXT_DIM;
		};
	}

	String ellipsize(String s, int maxW) {
		return Ui.ellipsize(font, s, maxW);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		if (!currentSuggestions.isEmpty() && search.isFocused()) {
			int x = searchX;
			int y = 6 + 20 + 3 + 3;
			int w = Math.min(searchW, 280);
			if (mx >= x && mx < x + w && my >= y && my < y + currentSuggestions.size() * 14) {
				acceptSuggestion(currentSuggestions.get((int) ((my - y) / 14)));
				return true;
			}
		}
		// Clicking anywhere on the search frame focuses the field.
		if (mx >= searchX && mx < searchX + searchW && my >= 6 && my < 26) {
			setFocused(search);
			search.setFocused(true);
			super.mouseClicked(event, doubleClick);
			updateSuggestions();
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			updateSuggestions();
			return true;
		}
		setFocused(null);
		search.setFocused(false);
		currentSuggestions = List.of();

		for (Hit h : hits) {
			if (h.contains(mx, my)) {
				playClick();
				h.action.run();
				return true;
			}
		}

		if (mx < sidebarW && my >= sidebarTop()) {
			int y = sidebarTop() - (int) sidebarScroll;
			for (SidebarRow row : sidebarRows) {
				int h = rowHeight(row);
				if (my >= y && my < y + h && !row.header) {
					onSidebarClick(row, mx, button);
					return true;
				}
				y += h;
			}
			return false;
		}

		if (mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
			int off = gridY - (int) scroll;
			for (LayoutItem item : layout) {
				if (item instanceof Tile t) {
					int y = off + t.y;
					if (mx >= t.x && mx < t.x + tileW && my >= y && my < y + tileH) {
						playClick();
						if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
							t.shot.meta.favorite = !t.shot.meta.favorite;
							ScreenshotStore.updateMeta(t.shot.file, t.shot.meta);
							rebuildSidebar();
						} else {
							minecraft.gui.setScreen(new ViewerScreen(this, new ArrayList<>(visible), t.index));
						}
						return true;
					}
				}
			}
		}
		return false;
	}

	private void onSidebarClick(SidebarRow row, double mx, int button) {
		playClick();
		if (button == InputConstants.MOUSE_BUTTON_RIGHT
			&& (row.node.kind == Library.Kind.INSTANCE || row.node.kind == Library.Kind.LOCATION)) {
			minecraft.gui.setScreen(new RenameScreen(this, row.node));
			return;
		}
		pendingSelection = null;
		boolean wasSelected = selected != null && selected.id().equals(row.node.id());
		if (row.expandable && (mx < 6 + 6 + row.indent * 10 + 9 || wasSelected)) {
			String id = row.node.id();
			if (!EXPANDED.remove(id)) {
				EXPANDED.add(id);
			}
		} else if (row.expandable) {
			EXPANDED.add(row.node.id());
		}
		selected = row.node;
		rebuildSidebar();
		applyFilters();
		scroll = scrollTarget = 0;
	}

	private void playClick() {
		Button.playButtonClickSound(minecraft.getSoundManager());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x < sidebarW) {
			int viewH = height - 4 - sidebarTop();
			sidebarScroll = Mth.clamp(sidebarScroll - scrollY * 20, 0, Math.max(0, sidebarContentHeight() - viewH));
			return true;
		}
		scrollTarget = Mth.clamp(scrollTarget - scrollY * (tileH + GAP) * 0.75, 0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (search.isFocused() && !currentSuggestions.isEmpty() && event.key() == InputConstants.KEY_TAB) {
			acceptSuggestion(currentSuggestions.getFirst());
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_F) {
			setFocused(search);
			search.setFocused(true);
			return true;
		}
		if (event.isCopy() && !search.isFocused() && hoveredShot != null) {
			copyAt = 0;
			ClipboardImage.copy(hoveredShot.file, ok -> {
				copyOk = ok;
				copyAt = System.currentTimeMillis();
			});
			return true;
		}
		if (event.isEscape() && search.isFocused() && !search.getValue().isEmpty()) {
			search.setValue("");
			return true;
		}
		boolean handled = super.keyPressed(event);
		updateSuggestions();
		return handled;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		boolean r = super.charTyped(event);
		updateSuggestions();
		return r;
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	// ------------------------------------------------------------------ API for other screens and tests

	/** Used by the viewer: clicking a tag searches for it. */
	public void searchFor(String text) {
		search.setValue(text);
		pendingSelection = null;
		selected = allNode;
		savedSelection = "ALL";
		rebuildSidebar();
		applyFilters();
	}

	void refreshAfterEdit() {
		rebuildSidebar();
		applyFilters();
	}

	/** For tests: open the viewer on the first visible shot. */
	public @Nullable ViewerScreen openFirst() {
		if (visible.isEmpty()) {
			return null;
		}
		ViewerScreen v = new ViewerScreen(this, new ArrayList<>(visible), 0);
		minecraft.gui.setScreen(v);
		return v;
	}

	public boolean isLoaded() {
		return library != null && (scanning == null || scanning.isDone());
	}

	public void focusSearch() {
		setFocused(search);
		search.setFocused(true);
		updateSuggestions();
	}
}
