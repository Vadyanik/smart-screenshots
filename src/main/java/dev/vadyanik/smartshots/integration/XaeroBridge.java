package dev.vadyanik.smartshots.integration;

import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.storage.Json;
import dev.vadyanik.smartshots.storage.Location;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.Trigger;
import dev.vadyanik.smartshots.storage.WorldContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Xaero's Minimap / World Map, through reflection (no compile-time dependency): screenshots of
 * the current world and dimension appear as temporary waypoints (Xaero's third-party waypoint
 * API, never written to Xaero's files), and the viewer can open the world map at a shot.
 */
public final class XaeroBridge {
	private static final Identifier KEY = SmartShots.id("screenshots");
	private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
	private static final int COLOR_SHOT = 11; // aqua
	private static final int COLOR_FAVORITE = 14; // yellow
	private static final int COLOR_ADVANCEMENT = 6; // gold
	private static final int COLOR_PLAYER = 13; // light purple

	private static boolean failed;
	private static String lastKey;
	private static volatile boolean refreshRequested;

	private XaeroBridge() {
	}

	public static boolean minimapLoaded() {
		return FabricLoader.getInstance().isModLoaded("xaerominimap") || FabricLoader.getInstance().isModLoaded("xaerobetterpvp");
	}

	public static boolean worldMapLoaded() {
		return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
	}

	/** Shots changed (new, deleted, favorite...): rebuild the waypoints soon. */
	public static void refresh() {
		refreshRequested = true;
	}

	/** Every client tick: rebuilds the waypoints when the world or dimension changed or a refresh was asked for. */
	public static void tick(Minecraft mc) {
		if (failed || !minimapLoaded() || mc.level == null || mc.player == null) {
			lastKey = null;
			return;
		}
		String key = mc.level.dimension().identifier() + "@" + System.identityHashCode(mc.level);
		if (!key.equals(lastKey) || refreshRequested) {
			lastKey = key;
			refreshRequested = false;
			sync(mc);
		}
	}

	private static void sync(Minecraft mc) {
		SmartShotsConfig cfg = SmartShotsConfig.get();
		Object map;
		try {
			map = customWaypoints();
		} catch (Throwable e) {
			// The session isn't up yet right after joining: try again on the next refresh.
			SmartShots.LOGGER.debug("Xaero waypoints not available yet", e);
			lastKey = null;
			return;
		}
		if (map == null) {
			lastKey = null;
			return;
		}
		if (!cfg.xaeroWaypoints) {
			clear(map);
			return;
		}
		Location loc;
		try {
			loc = WorldContext.resolve(mc);
		} catch (Exception e) {
			return;
		}
		String dim = mc.level.dimension().identifier().toString();
		Path dir = loc.locationDir();
		boolean includeAuto = cfg.xaeroIncludeAuto;
		ScreenshotStore.IO.execute(() -> {
			Map<String, ShotMeta> shots = Json.readShots(dir);
			List<Object[]> points = new ArrayList<>();
			for (Map.Entry<String, ShotMeta> e : shots.entrySet()) {
				ShotMeta m = e.getValue();
				if (m == null || m.dimension == null || !m.dimension.equals(dim)) {
					continue;
				}
				if (m.trigger() == Trigger.AUTO && !includeAuto && !m.favorite) {
					continue;
				}
				points.add(new Object[]{m, e.getKey()});
			}
			mc.execute(() -> apply(map, points));
		});
	}

	@SuppressWarnings("unchecked")
	private static void apply(Object map, List<Object[]> points) {
		try {
			Map<Integer, Object> m = (Map<Integer, Object>) map;
			m.clear();
			Class<?> wp = Class.forName("xaero.common.minimap.waypoints.Waypoint");
			Constructor<?> ctor = wp.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class, int.class, boolean.class);
			int i = 0;
			for (Object[] p : points) {
				ShotMeta s = (ShotMeta) p[0];
				String when = NAME_TIME.format(Instant.ofEpochMilli(s.time));
				String name = !s.note.isBlank() ? s.note
					: !s.advancements.isEmpty() ? s.advancements.getFirst() + " · " + when
					: net.minecraft.network.chat.Component.translatable("smartshots.xaero.waypoint", when).getString();
				int color = s.favorite ? COLOR_FAVORITE
					: s.trigger() == Trigger.ADVANCEMENT ? COLOR_ADVANCEMENT
					: s.trigger() == Trigger.NEW_PLAYER ? COLOR_PLAYER : COLOR_SHOT;
				String symbol = s.favorite ? "★" : s.trigger() == Trigger.ADVANCEMENT ? "A" : "S";
				Object w = ctor.newInstance((int) Math.floor(s.x), (int) Math.floor(s.y), (int) Math.floor(s.z), name, symbol, color, 0, true);
				m.put(i++, w);
			}
		} catch (Throwable e) {
			failed = true;
			SmartShots.LOGGER.warn("Couldn't add screenshot waypoints to Xaero's Minimap; turning the integration off for this session", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static void clear(Object map) {
		((Map<Integer, Object>) map).clear();
	}

	/** Xaero's map of our custom waypoints for the current session, or null. */
	private static Object customWaypoints() throws Exception {
		Class<?> modules = Class.forName("xaero.hud.minimap.BuiltInHudModules");
		Object module = modules.getField("MINIMAP").get(null);
		Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
		if (session == null) {
			return null;
		}
		Object manager = session.getClass().getMethod("getWorldManager").invoke(session);
		if (manager == null) {
			return null;
		}
		Method get = manager.getClass().getMethod("getCustomWaypoints", Identifier.class);
		return get.invoke(manager, KEY);
	}

	/** Opens Xaero's World Map centered on (x, z). Returns false when that isn't possible. */
	public static boolean openMap(Screen parent, int x, int z) {
		if (!worldMapLoaded()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		try {
			Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
			Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
			if (session == null) {
				return false;
			}
			Object processor = sessionClass.getMethod("getMapProcessor").invoke(session);
			Class<?> guiClass = Class.forName("xaero.map.gui.GuiMap");
			Class<?> processorClass = Class.forName("xaero.map.MapProcessor");
			Object gui = guiClass.getConstructor(Screen.class, Screen.class, processorClass, Entity.class)
				.newInstance(parent, parent, processor, mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player);
			Field dest = guiClass.getDeclaredField("cameraDestination");
			dest.setAccessible(true);
			dest.set(gui, new int[]{x, z});
			try {
				Field attached = guiClass.getDeclaredField("attachedCamera");
				attached.setAccessible(true);
				attached.setBoolean(null, false);
			} catch (NoSuchFieldException ignored) {
			}
			mc.gui.setScreen((Screen) gui);
			return true;
		} catch (Throwable e) {
			SmartShots.LOGGER.warn("Couldn't open Xaero's World Map", e);
			return false;
		}
	}

	/** Whether a shot is in the world and dimension being played now (so the map shows the right place). */
	public static boolean isCurrentPlace(Path shotFile, ShotMeta meta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || meta.dimension == null || !meta.dimension.equals(mc.level.dimension().identifier().toString())) {
			return false;
		}
		try {
			Location loc = WorldContext.resolve(mc);
			return shotFile.getParent().toAbsolutePath().normalize().equals(loc.locationDir().toAbsolutePath().normalize());
		} catch (Exception e) {
			return false;
		}
	}
}
