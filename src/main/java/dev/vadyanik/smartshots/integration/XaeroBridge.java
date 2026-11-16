package dev.vadyanik.smartshots.integration;

import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.storage.Location;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.Trigger;
import dev.vadyanik.smartshots.storage.WorldContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Xaero's Minimap / World Map, through reflection (no compile-time dependency): the viewer can
 * add a waypoint where a shot was taken (a normal, saved Xaero waypoint) and open the world map
 * at a shot. Nothing is added on its own.
 */
public final class XaeroBridge {
	private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
	private static final int COLOR_SHOT = 11; // aqua
	private static final int COLOR_FAVORITE = 14; // yellow
	private static final int COLOR_ADVANCEMENT = 6; // gold
	private static final int COLOR_PLAYER = 13; // light purple

	public enum Result { ADDED, EXISTS, FAILED }

	private XaeroBridge() {
	}

	public static boolean minimapLoaded() {
		return FabricLoader.getInstance().isModLoaded("xaerominimap") || FabricLoader.getInstance().isModLoaded("xaerobetterpvp");
	}

	public static boolean worldMapLoaded() {
		return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
	}

	/** The waypoint name for a shot: its note, its advancement, or "Screenshot <date>". */
	public static String waypointName(ShotMeta s) {
		String when = NAME_TIME.format(Instant.ofEpochMilli(s.time));
		return !s.note.isBlank() ? s.note
			: !s.advancements.isEmpty() ? s.advancements.getFirst() + " · " + when
			: net.minecraft.network.chat.Component.translatable("smartshots.xaero.waypoint", when).getString();
	}

	/**
	 * Adds a saved waypoint at the shot to the current waypoint set of the world being played.
	 * The caller checks first that the shot is from this world and dimension ({@link #isCurrentPlace}).
	 */
	public static Result addWaypoint(ShotMeta s) {
		try {
			Class<?> modules = Class.forName("xaero.hud.minimap.BuiltInHudModules");
			Object module = modules.getField("MINIMAP").get(null);
			Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
			if (session == null) {
				return Result.FAILED;
			}
			Object manager = session.getClass().getMethod("getWorldManager").invoke(session);
			Object world = manager == null ? null : manager.getClass().getMethod("getCurrentWorld").invoke(manager);
			Object set = world == null ? null : world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
			if (set == null) {
				return Result.FAILED;
			}
			int x = (int) Math.floor(s.x);
			int y = (int) Math.floor(s.y);
			int z = (int) Math.floor(s.z);
			String name = waypointName(s);
			Class<?> wp = Class.forName("xaero.common.minimap.waypoints.Waypoint");
			for (Object w : (Iterable<?>) set.getClass().getMethod("getWaypoints").invoke(set)) {
				if ((int) wp.getMethod("getX").invoke(w) == x && (int) wp.getMethod("getZ").invoke(w) == z
					&& name.equals(wp.getMethod("getName").invoke(w))) {
					return Result.EXISTS;
				}
			}
			int color = s.favorite ? COLOR_FAVORITE
				: s.trigger() == Trigger.ADVANCEMENT ? COLOR_ADVANCEMENT
				: s.trigger() == Trigger.NEW_PLAYER ? COLOR_PLAYER : COLOR_SHOT;
			String symbol = s.favorite ? "★" : s.trigger() == Trigger.ADVANCEMENT ? "A" : "S";
			Constructor<?> ctor = wp.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class, int.class, boolean.class);
			Object w = ctor.newInstance(x, y, z, name, symbol, color, 0, false);
			set.getClass().getMethod("add", wp).invoke(set, w);
			Object io = session.getClass().getMethod("getWorldManagerIO").invoke(session);
			io.getClass().getMethod("saveWorld", world.getClass()).invoke(io, world);
			return Result.ADDED;
		} catch (Throwable e) {
			SmartShots.LOGGER.warn("Couldn't add a waypoint to Xaero's Minimap", e);
			return Result.FAILED;
		}
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
