package dev.vadyanik.smartshots.track;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.vadyanik.smartshots.SmartShots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Inventory log next to the movement log: {@code <session>.inventory.jsonl}, one JSON object per
 * line. A full {@code snapshot} at the start, every few minutes and on events (death, dimension
 * change...), and a {@code change} line on every tick the inventory changed: the new content of
 * the changed slots plus the net gain / loss per item. Items are written in full (the game's own
 * item format: every component, shulker box and bundle contents included). Slots: 0-8 hotbar,
 * 9-35 main, 36-39 armor (feet, legs, chest, head), 40 offhand. An open ender chest is logged too
 * ({@code ender_chest} lines).
 */
final class InventoryLog {
	private static final long SNAPSHOT_EVERY_MS = 5 * 60_000L;

	// Render thread.
	private static ItemStack[] last;
	private static int lastSelected = -1;
	private static long lastSnapshot;

	// IO thread (MovementLog.IO).
	private static BufferedWriter out;

	private InventoryLog() {
	}

	/** Render thread: a new session; the file is opened on the IO thread. */
	static void start(Path file) {
		last = null;
		lastEnder = null;
		lastSelected = -1;
		lastSnapshot = 0;
		MovementLog.IO.execute(() -> {
			close();
			try {
				out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				SmartShots.LOGGER.warn("Couldn't start the inventory log {}", file, e);
			}
		});
	}

	static void end() {
		last = null;
		MovementLog.IO.execute(InventoryLog::close);
	}

	/** IO thread. */
	static void flush() {
		if (out != null) {
			try {
				out.flush();
			} catch (IOException ignored) {
			}
		}
	}

	/** IO thread. */
	static void close() {
		if (out != null) {
			try {
				out.close();
			} catch (IOException ignored) {
			}
			out = null;
		}
	}

	/** Every tick of a session: logs what changed since the last tick. */
	static void tick(Minecraft mc, LocalPlayer p) {
		tickEnderChest(mc, p);
		Inventory inv = p.getInventory();
		long now = System.currentTimeMillis();
		if (last == null || last.length != inv.getContainerSize() || now - lastSnapshot >= SNAPSHOT_EVERY_MS) {
			snapshot(mc, p, last == null ? "start" : "periodic");
			return;
		}
		JsonArray slots = new JsonArray();
		Map<String, Integer> before = new TreeMap<>();
		Map<String, Integer> after = new TreeMap<>();
		for (int i = 0; i < last.length; i++) {
			ItemStack now0 = inv.getItem(i);
			if (!ItemStack.matches(now0, last[i])) {
				JsonObject s = new JsonObject();
				s.addProperty("slot", i);
				s.add("item", now0.isEmpty() ? null : item(mc, now0));
				slots.add(s);
				count(before, last[i]);
				count(after, now0);
				last[i] = now0.copy();
			}
		}
		int selected = inv.getSelectedSlot();
		boolean selChanged = selected != lastSelected;
		lastSelected = selected;
		if (slots.isEmpty() && !selChanged) {
			return;
		}
		JsonObject line = header(mc, p, slots.isEmpty() ? "select" : "change");
		line.addProperty("selected", selected);
		if (!slots.isEmpty()) {
			line.add("slots", slots);
			JsonObject delta = new JsonObject();
			TreeMap<String, Integer> ids = new TreeMap<>(before);
			after.forEach(ids::putIfAbsent);
			for (String id : ids.keySet()) {
				int d = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
				if (d != 0) {
					delta.addProperty(id, d);
				}
			}
			if (!delta.isEmpty()) {
				line.add("delta", delta);
			}
		}
		write(line);
	}

	/** A full copy of the inventory ({@code reason}: start, periodic, death, dimension...). */
	static void snapshot(Minecraft mc, LocalPlayer p, String reason) {
		Inventory inv = p.getInventory();
		int size = inv.getContainerSize();
		last = new ItemStack[size];
		JsonObject items = new JsonObject();
		for (int i = 0; i < size; i++) {
			ItemStack s = inv.getItem(i);
			last[i] = s.copy();
			if (!s.isEmpty()) {
				items.add(String.valueOf(i), item(mc, s));
			}
		}
		lastSelected = inv.getSelectedSlot();
		lastSnapshot = System.currentTimeMillis();
		JsonObject line = header(mc, p, "snapshot");
		line.addProperty("reason", reason);
		line.addProperty("selected", lastSelected);
		line.add("slots", items);
		write(line);
	}

	private static JsonObject header(Minecraft mc, LocalPlayer p, String type) {
		JsonObject o = new JsonObject();
		o.addProperty("time_ms", System.currentTimeMillis());
		o.addProperty("game_tick", mc.level.getGameTime());
		o.addProperty("type", type);
		o.addProperty("dimension", mc.level.dimension().identifier().toString());
		Vec3 pos = p.position();
		JsonArray xyz = new JsonArray();
		xyz.add(round(pos.x));
		xyz.add(round(pos.y));
		xyz.add(round(pos.z));
		o.add("pos", xyz);
		return o;
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private static String id(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	private static void count(Map<String, Integer> m, ItemStack s) {
		if (s != null && !s.isEmpty()) {
			m.merge(id(s), s.getCount(), Integer::sum);
		}
	}

	/**
	 * The whole stack as the game saves it (ItemStack codec): id, count and every component —
	 * contents of shulker boxes and bundles (recursively), enchantments, durability, names, lore,
	 * custom data, trims, potion contents... Plus a few flat fields for easy reading.
	 */
	static JsonObject item(Minecraft mc, ItemStack s) {
		JsonObject o = null;
		try {
			JsonElement full = ItemStack.CODEC.encodeStart(ops(mc), s).result().orElse(null);
			if (full != null && full.isJsonObject()) {
				o = full.getAsJsonObject();
			}
		} catch (Exception e) {
			SmartShots.LOGGER.debug("Couldn't encode {}", s, e);
		}
		if (o == null) {
			o = new JsonObject();
			o.addProperty("id", id(s));
			o.addProperty("count", s.getCount());
		}
		if (s.isDamageableItem()) {
			o.addProperty("durability_left", s.getMaxDamage() - s.getDamageValue());
			o.addProperty("max_durability", s.getMaxDamage());
		}
		try {
			Component name = s.getCustomName();
			if (name != null) {
				o.addProperty("display_name", name.getString());
			}
		} catch (Exception ignored) {
		}
		return o;
	}

	private static RegistryOps<JsonElement> ops;
	private static Object opsFor;

	private static RegistryOps<JsonElement> ops(Minecraft mc) {
		Object access = mc.level.registryAccess();
		if (ops == null || opsFor != access) {
			ops = RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess());
			opsFor = access;
		}
		return ops;
	}

	// ------------------------------------------------------------------ ender chest

	private static ItemStack[] lastEnder;

	/** While an ender chest is open: its content when opened and on every change. */
	static void tickEnderChest(Minecraft mc, LocalPlayer p) {
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !(screen.getMenu() instanceof ChestMenu menu)
			|| !(screen.getTitle().getContents() instanceof TranslatableContents tc) || !"container.enderchest".equals(tc.getKey())) {
			lastEnder = null;
			return;
		}
		Container c = menu.getContainer();
		int size = c.getContainerSize();
		boolean changed = lastEnder == null || lastEnder.length != size;
		if (!changed) {
			for (int i = 0; i < size; i++) {
				if (!ItemStack.matches(c.getItem(i), lastEnder[i])) {
					changed = true;
					break;
				}
			}
		}
		if (!changed) {
			return;
		}
		boolean opened = lastEnder == null;
		lastEnder = new ItemStack[size];
		JsonObject items = new JsonObject();
		for (int i = 0; i < size; i++) {
			ItemStack st = c.getItem(i);
			lastEnder[i] = st.copy();
			if (!st.isEmpty()) {
				items.add(String.valueOf(i), item(mc, st));
			}
		}
		JsonObject line = header(mc, p, "ender_chest");
		line.addProperty("reason", opened ? "opened" : "changed");
		line.add("slots", items);
		write(line);
	}

	private static void write(JsonObject line) {
		String text = line.toString();
		MovementLog.IO.execute(() -> {
			if (out == null) {
				return;
			}
			try {
				out.write(text);
				out.write('\n');
			} catch (IOException e) {
				SmartShots.LOGGER.warn("Couldn't write the inventory log", e);
				close();
			}
		});
	}

	static String lowerId(ItemStack s) {
		return s.isEmpty() ? "" : id(s).toLowerCase(Locale.ROOT);
	}
}
