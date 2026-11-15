package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.SmartShots;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Detects shots taken from a free camera, without a compile dependency on any freecam mod.
 *
 * <p>Verified against the 26.3 Fabric jars from Modrinth: "Freecam" (mod id {@code freecam}) and
 * its fork "Legacy Freecam" (mod id {@code legacyfreecam}) both ship
 * {@code net.xolt.freecam.Freecam} with {@code public static boolean isEnabled()} (true in freecam
 * and tripod mode). The camera is {@code net.xolt.freecam.util.FreeCamera}, an
 * {@code AbstractClientPlayer} that is added to the client level and set as the camera entity
 * while the local player keeps being rendered.
 *
 * <p>Other freecam mods are caught by a heuristic: the camera entity is not the local player and
 * either its class name mentions a free camera or it isn't a real entity of the client level.
 */
public final class Freecam {
	private static final String[] MOD_IDS = {"freecam", "legacyfreecam"};
	private static final String API_CLASS = "net.xolt.freecam.Freecam";

	private static boolean apiLookedUp;
	private static Method isEnabled;

	private Freecam() {
	}

	/** True when the current frame is rendered from a free camera. Main thread only. */
	public static boolean isActive(Minecraft mc) {
		Method api = api();
		if (api != null) {
			try {
				if ((boolean) api.invoke(null)) {
					return true;
				}
			} catch (Throwable t) {
				SmartShots.LOGGER.debug("Freecam API call failed", t);
				isEnabled = null; // don't retry on every shot
			}
		}
		Entity cam = mc.getCameraEntity();
		if (cam == null || cam == mc.player || mc.level == null) {
			return false;
		}
		return looksLikeFreeCamera(cam) || mc.level.getEntity(cam.getId()) != cam;
	}

	/** The free camera's own "entity" (never shown in the frame, must not be listed). */
	public static boolean isCameraEntity(Minecraft mc, Entity e) {
		return e != mc.player && (looksLikeFreeCamera(e) || e == mc.getCameraEntity() && isActive(mc));
	}

	private static boolean looksLikeFreeCamera(Entity e) {
		String name = e.getClass().getName().toLowerCase(Locale.ROOT);
		return name.contains("freecam") || name.contains("free_cam") || name.contains("freecamera");
	}

	private static Method api() {
		if (!apiLookedUp) {
			apiLookedUp = true;
			for (String id : MOD_IDS) {
				if (!FabricLoader.getInstance().isModLoaded(id)) {
					continue;
				}
				try {
					isEnabled = Class.forName(API_CLASS).getMethod("isEnabled");
					SmartShots.LOGGER.info("Freecam support enabled ({})", id);
				} catch (Throwable t) {
					SmartShots.LOGGER.info("Mod {} found but its API is unknown, using heuristics", id);
				}
				break;
			}
		}
		return isEnabled;
	}
}
