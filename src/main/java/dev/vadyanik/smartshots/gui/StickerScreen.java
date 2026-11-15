package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatEditBox;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.storage.ShotMask;
import dev.vadyanik.smartshots.storage.ShotMeta;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Picks a sticker: things from this shot, any item, any mob (spawn egg) or a player's head. */
public class StickerScreen extends Screen {
	private static final int TOP_H = 28;
	private static final int CELL = 22;

	enum Tab { SHOT, ITEMS, MOBS, PLAYERS }

	/** A choice: an item stack, or a player's skin. */
	record Entry(String label, ItemStack stack, PlayerSkin skin, String search) {
	}

	private static Tab tab = Tab.SHOT;
	private final Screen parent;
	private final ShotMeta meta;
	private final Consumer<String> onPick;
	private EditBox search;
	private List<Entry> all = List.of();
	private List<Entry> shown = List.of();
	private double scroll;
	private String status;

	public StickerScreen(Screen parent, ShotMeta meta, Consumer<String> onPick) {
		super(Component.translatable("smartshots.sticker.title"));
		this.parent = parent;
		this.meta = meta;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		addRenderableWidget(new FlatButton(6, 4, 20, 20, Component.translatable("gui.back"), Ui.ARROW_LEFT,
			FlatButton.Style.GHOST, false, b -> onClose()));
		String old = search != null ? search.getValue() : "";
		int sx = 32 + font.width(title) + 16;
		int sw = Math.min(260, width - sx - 8);
		search = FlatEditBox.create(font, sx, 4, sw, 20, true, Component.translatable("smartshots.sticker.search"));
		search.setValue(old);
		search.setHint(Component.translatable("smartshots.sticker.search").withColor(Theme.TEXT_FAINT));
		search.setResponder(s -> filter());
		addRenderableWidget(search);
		setInitialFocus(search);
		int x = 8;
		for (Tab t : Tab.values()) {
			Component label = Component.translatable("smartshots.sticker.tab." + t.name().toLowerCase(Locale.ROOT));
			int w = font.width(label) + 16;
			addRenderableWidget(FlatButton.text(x, TOP_H + 6, w, label, FlatButton.Style.GHOST, b -> {
				tab = t;
				scroll = 0;
				load();
				rebuildWidgets();
			})).setSelected(tab == t);
			x += w + 4;
		}
		load();
	}

	private int gridTop() {
		return TOP_H + 32;
	}

	private int cols() {
		return Math.max(1, (width - 16) / CELL);
	}

	private void load() {
		all = switch (tab) {
			case SHOT -> fromShot();
			case ITEMS -> items();
			case MOBS -> mobs();
			case PLAYERS -> players();
		};
		filter();
	}

	private void filter() {
		String q = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			shown = all;
		} else {
			List<Entry> out = new ArrayList<>();
			for (Entry e : all) {
				if (e.search.contains(q)) {
					out.add(e);
				}
			}
			shown = out;
		}
		scroll = Mth.clamp(scroll, 0, maxScroll());
	}

	private static Entry item(ItemStack s) {
		Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
		String label = s.getHoverName().getString();
		return new Entry(label, s, null, (label + " " + id).toLowerCase(Locale.ROOT));
	}

	private static Entry mob(EntityType<?> type) {
		Optional<Holder<Item>> egg = SpawnEggItem.byId(type);
		if (egg.isEmpty()) {
			return null;
		}
		String label = type.getDescription().getString();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return new Entry(label, new ItemStack(egg.get()), null, (label + " " + id).toLowerCase(Locale.ROOT));
	}

	private List<Entry> fromShot() {
		List<Entry> out = new ArrayList<>();
		if (meta == null) {
			return out;
		}
		for (String p : meta.players) {
			out.add(new Entry(p, null, ChipIcons.skin(p, meta), p.toLowerCase(Locale.ROOT)));
		}
		Set<String> types = new LinkedHashSet<>(meta.entities.keySet());
		if (meta.named != null) {
			for (String n : meta.named) {
				types.add(ShotMask.namedType(n));
			}
		}
		for (String id : types) {
			Identifier rl = Identifier.tryParse(id);
			if (rl != null && !id.equals("minecraft:player")) {
				BuiltInRegistries.ENTITY_TYPE.getOptional(rl).map(StickerScreen::mob).ifPresent(out::add);
			}
		}
		Set<String> things = new LinkedHashSet<>();
		if (meta.items != null) {
			things.addAll(meta.items.keySet());
		}
		things.addAll(meta.blocks.keySet());
		for (String id : things) {
			Identifier rl = Identifier.tryParse(id);
			if (rl == null) {
				continue;
			}
			Item it = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
			if (it == Items.AIR) {
				it = BuiltInRegistries.BLOCK.getOptional(rl).map(b -> b.asItem()).orElse(Items.AIR);
			}
			if (it != Items.AIR) {
				out.add(item(new ItemStack(it)));
			}
		}
		return out;
	}

	private static List<Entry> items() {
		List<Entry> out = new ArrayList<>();
		for (Item it : BuiltInRegistries.ITEM) {
			if (it != Items.AIR) {
				out.add(item(new ItemStack(it)));
			}
		}
		return out;
	}

	private static List<Entry> mobs() {
		List<Entry> out = new ArrayList<>();
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
			Entry e = mob(t);
			if (e != null) {
				out.add(e);
			}
		}
		out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
		return out;
	}

	private List<Entry> players() {
		List<Entry> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		if (minecraft.player != null) {
			String me = minecraft.player.getGameProfile().name();
			seen.add(me);
			out.add(new Entry(me, null, minecraft.player.getSkin(), me.toLowerCase(Locale.ROOT)));
		}
		ClientPacketListener conn = minecraft.getConnection();
		if (conn != null) {
			for (PlayerInfo info : conn.getOnlinePlayers()) {
				String n = info.getProfile().name();
				if (seen.add(n)) {
					out.add(new Entry(n, null, info.getSkin(), n.toLowerCase(Locale.ROOT)));
				}
			}
		}
		if (meta != null) {
			for (String p : meta.players) {
				if (seen.add(p)) {
					out.add(new Entry(p, null, ChipIcons.skin(p, meta), p.toLowerCase(Locale.ROOT)));
				}
			}
		}
		return out;
	}

	private double maxScroll() {
		int rows = (shown.size() + cols() - 1) / cols();
		return Math.max(0, rows * CELL - (height - gridTop() - 8));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xF00A0B0E);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, TOP_H, Theme.SURFACE);
		g.horizontalLine(0, width, TOP_H, Theme.BORDER);
		g.text(font, title, 32, 10, Theme.TEXT, false);
		FlatEditBox.drawFrame(g, search, search.getX() - 20, 4, search.getWidth() + 26, 20, Ui.SEARCH);
		int top = gridTop();
		int cols = cols();
		int x0 = (width - cols * CELL) / 2;
		g.enableScissor(0, top, width, height);
		Entry hover = null;
		int first = (int) (scroll / CELL) * cols;
		for (int i = Math.max(0, first); i < shown.size(); i++) {
			int col = i % cols;
			int row = i / cols;
			int x = x0 + col * CELL;
			int y = top + row * CELL - (int) scroll;
			if (y > height) {
				break;
			}
			Entry e = shown.get(i);
			boolean h = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL && mouseY >= top;
			Ui.rect(g, x + 1, y + 1, CELL - 2, CELL - 2, h ? Theme.SURFACE_3 : Theme.SURFACE_2);
			if (e.stack != null) {
				g.item(e.stack, x + 3, y + 3);
			} else if (e.skin != null) {
				PlayerFaceExtractor.extractRenderState(g, e.skin, x + 3, y + 3, 16);
			}
			if (h) {
				hover = e;
			}
		}
		g.disableScissor();
		if (shown.isEmpty()) {
			g.centeredText(font, Component.translatable("smartshots.gallery.no_results"), width / 2, top + 20, Theme.TEXT_FAINT);
		}
		if (status != null) {
			g.centeredText(font, status, width / 2, height - 14, Theme.GOLD);
		}
		if (hover != null) {
			g.setTooltipForNextFrame(font, Component.literal(hover.label), mouseX, mouseY);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		int top = gridTop();
		if (event.y() < top) {
			return false;
		}
		int cols = cols();
		int x0 = (width - cols * CELL) / 2;
		int col = (int) Math.floor((event.x() - x0) / CELL);
		int row = (int) Math.floor((event.y() - top + scroll) / CELL);
		if (col < 0 || col >= cols || row < 0) {
			return false;
		}
		int i = row * cols + col;
		if (i >= shown.size()) {
			return false;
		}
		Entry e = shown.get(i);
		String img = e.stack != null ? Stickers.item(e.stack) : Stickers.head(e.skin);
		if (img == null) {
			status = Component.translatable(e.stack != null ? "smartshots.sticker.failed" : "smartshots.sticker.skin_loading").getString();
			return true;
		}
		minecraft.gui.setScreen(parent);
		onPick.accept(img);
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * CELL * 2, 0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation() && !shown.isEmpty()) {
			Entry e = shown.getFirst();
			String img = e.stack != null ? Stickers.item(e.stack) : Stickers.head(e.skin);
			if (img != null) {
				minecraft.gui.setScreen(parent);
				onPick.accept(img);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
