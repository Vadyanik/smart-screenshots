package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatEditBox;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.SeenPlayers;
import dev.vadyanik.smartshots.storage.ShotMask;
import dev.vadyanik.smartshots.storage.ShotMeta;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everyone you have met and how often each one got on your screenshots: the total from the met
 * players list ({@link SeenPlayers}, shared by all instances) and a ranking built from the shots in
 * the library. Clicking a player searches the gallery for them.
 */
public class PlayersScreen extends Screen {
	private static final int TOP_H = 32;
	private static final int STATS_H = 40;
	private static final int ROW_H = 22;

	private final GalleryScreen gallery;
	private final List<Player> all = new ArrayList<>();
	private List<Player> shown = List.of();
	private static boolean leastFirst;
	private EditBox search;
	private FlatButton sortButton;
	private int colX;
	private int searchX;
	private int searchW;
	private int colW;
	private double scroll;
	private int metTotal = -1;
	private int onShots;
	private int shotsWithPlayers;
	private int maxCount;

	/** One row; {@code meta} is the newest shot with them (skin and styled name come from it). */
	private static final class Player {
		final String key;
		String name;
		int count;
		long first;
		long last;
		ShotMeta meta;
		int rank;

		Player(String key, String name) {
			this.key = key;
			this.name = name;
		}
	}

	public PlayersScreen(GalleryScreen gallery) {
		super(Component.translatable("smartshots.players.title"));
		this.gallery = gallery;
		collectShots();
		SeenPlayers.list().thenAccept(list -> net.minecraft.client.Minecraft.getInstance().execute(() -> mergeMet(list)));
	}

	private void collectShots() {
		Library lib = GalleryScreen.library;
		if (lib == null) {
			return;
		}
		Map<String, Player> byKey = new HashMap<>();
		for (Library.Shot s : lib.shots) {
			ShotMeta m = s.meta;
			if (m.players == null || m.players.isEmpty()) {
				continue;
			}
			shotsWithPlayers++;
			for (String nick : new java.util.LinkedHashSet<>(m.players)) {
				String key = nick.toLowerCase(Locale.ROOT);
				Player p = byKey.computeIfAbsent(key, k -> {
					Player n = new Player(k, nick);
					all.add(n);
					return n;
				});
				p.count++;
				if (p.meta == null || m.time > p.last) {
					p.meta = m;
					p.name = nick;
					p.last = m.time;
				}
				if (p.first == 0 || m.time < p.first) {
					p.first = m.time;
				}
			}
		}
		onShots = all.size();
	}

	/** Adds the met players that are on no shot (count 0) and the first-meeting dates. */
	private void mergeMet(List<SeenPlayers.Met> met) {
		Map<String, Player> byKey = new HashMap<>();
		Map<String, Player> byUuid = new HashMap<>();
		for (Player p : all) {
			byKey.put(p.key, p);
			if (p.meta != null && p.meta.playerIds != null) {
				String id = p.meta.playerIds.get(p.name);
				if (id != null) {
					byUuid.put(id.toLowerCase(Locale.ROOT), p);
				}
			}
		}
		for (SeenPlayers.Met e : met) {
			Player p = e.uuid() != null ? byUuid.get(e.uuid().toLowerCase(Locale.ROOT)) : null;
			if (p == null && !e.name().isEmpty()) {
				p = byKey.get(e.name().toLowerCase(Locale.ROOT));
			}
			if (p == null) {
				if (e.name().isEmpty()) {
					continue;
				}
				p = new Player(e.name().toLowerCase(Locale.ROOT), e.name());
				byKey.put(p.key, p);
				all.add(p);
			}
			if (e.first() > 0 && (p.first == 0 || e.first() < p.first)) {
				p.first = e.first();
			}
		}
		metTotal = met.size();
		refilter();
	}

	@Override
	protected void init() {
		colW = Math.min(440, width - 24);
		colX = (width - colW) / 2;
		addRenderableWidget(new FlatButton(8, 6, 20, 20, Component.translatable("gui.back"), Ui.ARROW_LEFT,
			FlatButton.Style.GHOST, false, b -> onClose()));

		int titleW = font.width(title) + 46;
		searchX = Math.max(titleW, colX);
		searchW = Math.max(60, colX + colW - searchX - 24);
		String old = search != null ? search.getValue() : "";
		search = FlatEditBox.create(font, searchX, 6, searchW, 20, true, Component.translatable("smartshots.players.search"));
		search.setHint(Component.translatable("smartshots.players.search").withColor(Theme.TEXT_FAINT));
		search.setValue(old);
		search.setResponder(v -> {
			scroll = 0;
			refilter();
		});
		addRenderableWidget(search);
		sortButton = FlatButton.icon(searchX + searchW + 4, 6, Ui.SORT, sortTooltip(), b -> {
			leastFirst = !leastFirst;
			b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(sortTooltip()));
			scroll = 0;
			refilter();
		});
		addRenderableWidget(sortButton);
		refilter();
	}

	private static Component sortTooltip() {
		return Component.translatable(leastFirst ? "smartshots.players.sort_least" : "smartshots.players.sort_most");
	}

	private void refilter() {
		Comparator<Player> byCount = Comparator.comparingInt(p -> p.count);
		Comparator<Player> order = (leastFirst ? byCount : byCount.reversed())
			.thenComparing(p -> p.name.toLowerCase(Locale.ROOT));
		List<Player> ranked = new ArrayList<>(all);
		ranked.sort(byCount.reversed().thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
		maxCount = ranked.isEmpty() ? 0 : ranked.getFirst().count;
		// Rank by count, ties share a place.
		for (int i = 0; i < ranked.size(); i++) {
			Player p = ranked.get(i);
			p.rank = i > 0 && ranked.get(i - 1).count == p.count ? ranked.get(i - 1).rank : i + 1;
		}
		String q = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
		List<Player> out = new ArrayList<>();
		for (Player p : ranked) {
			if (q.isEmpty() || p.key.contains(q)) {
				out.add(p);
			}
		}
		out.sort(order);
		shown = out;
		scroll = Mth.clamp(scroll, 0, maxScroll());
	}

	private int listTop() {
		return TOP_H + STATS_H;
	}

	private int maxScroll() {
		return Math.max(0, shown.size() * ROW_H + 8 - (height - listTop()));
	}

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
		g.text(font, title, 34, 12, Theme.TEXT, false);
		g.horizontalLine(0, width, TOP_H, Theme.BORDER);

		// Totals
		int sy = TOP_H + 8;
		int cellW = colW / 3;
		stat(g, colX, sy, cellW, metTotal < 0 ? "…" : String.valueOf(metTotal), "smartshots.players.stat_met", Theme.CYAN);
		stat(g, colX + cellW, sy, cellW, String.valueOf(onShots), "smartshots.players.stat_on_shots", Theme.GREEN);
		stat(g, colX + cellW * 2, sy, cellW, String.valueOf(shotsWithPlayers), "smartshots.players.stat_shots", Theme.GOLD);

		int top = listTop();
		g.horizontalLine(colX, colX + colW, top - 3, Theme.BORDER);
		g.enableScissor(0, top, width, height);
		if (shown.isEmpty()) {
			String key = all.isEmpty() ? (GalleryScreen.library == null ? "smartshots.players.loading" : "smartshots.players.empty")
				: "smartshots.players.no_match";
			String msg = Component.translatable(key).getString();
			g.text(font, msg, (width - font.width(msg)) / 2, top + 20, Theme.TEXT_FAINT, false);
		}
		int first = Math.max(0, (int) (scroll / ROW_H));
		int rankW = font.width("#" + Math.max(1, all.size())) + 6;
		for (int i = first; i < shown.size(); i++) {
			int y = top + 4 + i * ROW_H - (int) scroll;
			if (y > height) {
				break;
			}
			Player p = shown.get(i);
			boolean hover = mouseY >= Math.max(y, top) && mouseY < y + ROW_H - 2 && mouseX >= colX && mouseX < colX + colW;
			Ui.rect(g, colX, y, colW, ROW_H - 2, hover ? Theme.SURFACE_3 : Theme.SURFACE_2);
			int rankColor = p.count == 0 ? Theme.TEXT_FAINT : p.rank == 1 ? Theme.GOLD : p.rank <= 3 ? Theme.TEXT : Theme.TEXT_DIM;
			g.text(font, p.count == 0 ? "—" : "#" + p.rank, colX + 6, y + 6, rankColor, false);
			int fx = colX + 6 + rankW;
			ShotMeta meta = p.meta != null ? p.meta : new ShotMeta();
			ChipIcons.draw(g, ShotMask.PLAYER + p.name, meta, fx, y + 2, 16);

			String num = String.valueOf(p.count);
			int numW = font.width(num);
			int barMax = Math.min(90, colW / 4);
			int barX = colX + colW - 8 - numW - 6 - barMax;
			int nameX = fx + 22;
			Component styled = p.meta != null ? PlayerNames.styled(p.meta, p.name) : null;
			int nameW = barX - 8 - nameX;
			if (styled != null) {
				g.text(font, PlayerNames.fit(font, styled, nameW), nameX, y + 6, Theme.TEXT, false);
			} else {
				g.text(font, Ui.ellipsize(font, p.name, nameW), nameX, y + 6, p.count == 0 ? Theme.TEXT_DIM : Theme.TEXT, false);
			}
			if (maxCount > 0) {
				Ui.rect(g, barX, y + 8, barMax, 4, Theme.alpha(Theme.TEXT_FAINT, 0.25f));
				int bw = Math.round(barMax * (p.count / (float) maxCount));
				if (bw > 0) {
					Ui.rect(g, barX, y + 8, Math.max(2, bw), 4, Theme.alpha(Theme.CYAN, 0.8f));
				}
			}
			g.text(font, num, colX + colW - 8 - numW, y + 6, p.count == 0 ? Theme.TEXT_FAINT : Theme.TEXT, false);
			if (hover && mouseY > top) {
				g.setComponentTooltipForNextFrame(font, tooltip(p), mouseX, mouseY);
			}
		}
		g.disableScissor();
		if (maxScroll() > 0) {
			int viewH = height - top;
			int contentH = shown.size() * ROW_H + 8;
			int barH = Math.max(16, viewH * viewH / contentH);
			int barY = top + (int) ((viewH - barH) * (scroll / maxScroll()));
			Ui.rect(g, width - 5, barY, 2, barH, Theme.BORDER_STRONG);
		}
		FlatEditBox.drawFrame(g, search, searchX, 6, searchW, 20, Ui.SEARCH);
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private void stat(GuiGraphicsExtractor g, int x, int y, int w, String value, String key, int color) {
		g.text(font, value, x + 4, y, color, false);
		g.text(font, Ui.ellipsize(font, Component.translatable(key).getString(), w - 8), x + 4, y + 12, Theme.TEXT_FAINT, false);
	}

	private List<Component> tooltip(Player p) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(p.name).withStyle(net.minecraft.ChatFormatting.WHITE));
		lines.add(Component.translatable("smartshots.players.tip_count", p.count).withColor(Theme.TEXT_DIM));
		if (p.first > 0) {
			lines.add(Component.translatable("smartshots.players.tip_first", date(p.first)).withColor(Theme.TEXT_DIM));
		}
		if (p.last > 0) {
			lines.add(Component.translatable("smartshots.players.tip_last", date(p.last)).withColor(Theme.TEXT_DIM));
		}
		if (p.count > 0) {
			lines.add(Component.translatable("smartshots.players.tip_click").withColor(Theme.TEXT_FAINT));
		}
		return lines;
	}

	private static String date(long time) {
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
			.withLocale(GalleryScreen.gameLocale())
			.withZone(ZoneId.systemDefault())
			.format(Instant.ofEpochMilli(time));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		int top = listTop();
		double mx = event.x();
		double my = event.y();
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || my < top || mx < colX || mx >= colX + colW) {
			return false;
		}
		int i = (int) ((my - top - 4 + scroll) / ROW_H);
		if (i < 0 || i >= shown.size()) {
			return false;
		}
		Player p = shown.get(i);
		if (p.count == 0) {
			return false;
		}
		Button.playButtonClickSound(minecraft.getSoundManager());
		minecraft.gui.setScreen(gallery);
		gallery.searchFor(Highlights.query(ShotMask.PLAYER + p.name) + " ");
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * ROW_H * 2, 0, maxScroll());
		return true;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(gallery);
	}
}
