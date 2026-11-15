package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ShotToast;
import dev.vadyanik.smartshots.storage.SeenPlayers;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.StyledNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "First meeting" shots: the first time ever the user sees another player (any world, server or
 * launcher instance, see {@link SeenPlayers}), a full-frame shot is taken.
 *
 * <p>Only when the newcomer is properly in view ({@link #wellFramed}). The first well-framed
 * newcomer opens a short gather window so others arriving together end up on the same shot;
 * after a meeting shot a cooldown runs, and newcomers seen meanwhile get one shot after it.
 * Everyone new visible on the shot is remembered; a newcomer that never got well framed isn't.
 */
public final class PlayerMeet {
	/** Max distance from the camera. */
	private static final double RANGE = 32;
	/** The whole bounding box must project inside this share of the screen (normalized coords). */
	private static final double FRAME = 0.85;
	/** Min on-screen height of the bounding box, as a share of the screen height. */
	private static final double MIN_HEIGHT = 0.05;
	/** Body points that must have a clear line of sight (out of {@link #POINTS}). */
	private static final int MIN_VISIBLE = 4;
	private static final int POINTS = 6;
	private static final int SCAN_EVERY = 4;
	private static final int REFRESH_EVERY = 100;

	private enum State { IDLE, WAIT, SHUTTER }

	private static State state = State.IDLE;
	private static long fireAt;
	private static long cooldownUntil;
	private static int shutterTicks;
	private static int ticks;

	private PlayerMeet() {
	}

	/** Not in a world: drop whatever was going on (the cooldown stays). */
	static void reset() {
		if (state == State.SHUTTER) {
			ShotToast.suppressed = false;
		}
		state = State.IDLE;
	}

	/** Main thread, every client tick while in a world (from {@link CaptureScheduler#tick}). */
	static void tick(Minecraft mc, boolean schedulerBusy) {
		SmartShotsConfig cfg = SmartShotsConfig.get();
		if (!cfg.meetEnabled || mc.level == null || mc.player == null) {
			reset();
			return;
		}
		if (ticks++ % REFRESH_EVERY == 0 || !SeenPlayers.ready()) {
			SeenPlayers.refresh(); // picks up players met in other instances; no-op when the file is unchanged
		}
		if (!SeenPlayers.ready()) {
			return;
		}
		if (mc.gui.screen() != null || mc.isPaused()) {
			if (state == State.SHUTTER) {
				ShotToast.suppressed = false;
				state = State.WAIT; // try again once the menu is closed
			}
			return;
		}
		long now = Util.getMillis();
		switch (state) {
			case IDLE -> {
				if (ticks % SCAN_EVERY != 0 || !anyWellFramed(mc)) {
					return;
				}
				state = State.WAIT;
				fireAt = Math.max(now + Math.round(cfg.meetGatherSeconds * 1000), cooldownUntil);
				SeenPlayers.refresh(); // decide on fresh data
			}
			case WAIT -> {
				if (now < fireAt || schedulerBusy) {
					return; // gathering, cooling down, or an advancement / F2 shot goes first
				}
				if (!anyWellFramed(mc)) {
					state = State.IDLE; // they walked off; shot next time they're properly in view
					return;
				}
				if (ShotToast.anyVisible()) {
					// Our own preview toast would be on the shot: hide it for a couple of ticks first.
					ShotToast.suppressed = true;
					shutterTicks = 2;
					state = State.SHUTTER;
					return;
				}
				fire(mc, now);
			}
			case SHUTTER -> {
				if (--shutterTicks <= 0) {
					fire(mc, now);
				}
			}
		}
	}

	private static void fire(Minecraft mc, long now) {
		Map<UUID, String> met = new LinkedHashMap<>();
		boolean framed = collect(mc, met);
		state = State.IDLE;
		if (!framed) {
			ShotToast.suppressed = false;
			return;
		}
		CaptureService.captureMeeting(mc, met);
		SeenPlayers.add(met);
		cooldownUntil = now + SmartShotsConfig.get().meetCooldownSeconds * 1000L;
	}

	/**
	 * Called after the scene analysis of a meeting shot: other newcomers it found visible are
	 * met too (and get their tag).
	 */
	static void afterAnalysis(Minecraft mc, ShotMeta meta) {
		Map<UUID, String> extra = new LinkedHashMap<>();
		UUID self = mc.player != null ? mc.player.getUUID() : null;
		for (Map.Entry<String, String> e : meta.playerIds.entrySet()) {
			UUID uuid;
			try {
				uuid = UUID.fromString(e.getValue());
			} catch (IllegalArgumentException ex) {
				continue;
			}
			if (uuid.equals(self) || !realName(e.getKey()) || uuid.version() == 2 || SeenPlayers.isKnown(uuid, e.getKey())) {
				continue;
			}
			extra.put(uuid, e.getKey());
		}
		if (!extra.isEmpty()) {
			tag(meta, extra);
			SeenPlayers.add(extra);
		}
	}

	/** Records the met players on the shot: tag {@code met:<nick>}, plus players / playerIds. */
	static void tag(ShotMeta meta, Map<UUID, String> met) {
		for (Map.Entry<UUID, String> e : met.entrySet()) {
			String nick = e.getValue();
			String tag = "met:" + nick;
			if (!meta.tags.contains(tag)) {
				meta.tags.add(tag);
			}
			if (meta.players.stream().noneMatch(nick::equalsIgnoreCase)) {
				meta.players.add(nick);
			}
			meta.playerIds.putIfAbsent(nick, e.getKey().toString());
			Minecraft mc = Minecraft.getInstance();
			StyledNames.record(meta, mc, nick, e.getKey(), mc.level != null ? mc.level.getPlayerByUUID(e.getKey()) : null);
		}
	}

	// ------------------------------------------------------------------ checks

	private static boolean anyWellFramed(Minecraft mc) {
		return collect(mc, null);
	}

	/**
	 * Looks at every newcomer in range. With {@code out == null}: stops at the first well-framed
	 * one. Otherwise fills {@code out} with every newcomer that is well framed or at least visible.
	 *
	 * @return whether at least one newcomer is well framed
	 */
	private static boolean collect(Minecraft mc, Map<UUID, String> out) {
		ClientLevel level = mc.level;
		var camera = mc.gameRenderer.mainCamera();
		if (level == null || !camera.isInitialized()) {
			return false;
		}
		SceneAnalyzer.View view = null;
		Vec3 eye = camera.position();
		boolean framed = false;
		for (Player p : level.players()) {
			if (!isCandidate(mc, p) || p.getBoundingBox().getCenter().distanceToSqr(eye) > RANGE * RANGE) {
				continue;
			}
			String name = p.getPlainTextName();
			if (SeenPlayers.isKnown(p.getUUID(), name)) {
				continue;
			}
			if (view == null) {
				view = new SceneAnalyzer.View(camera, mc);
			}
			boolean ok = wellFramed(level, view, p);
			if (ok) {
				framed = true;
				if (out == null) {
					return true;
				}
			}
			if (out != null && (ok || SceneAnalyzer.entityVisible(level, view, p))) {
				out.put(p.getUUID(), name);
			}
		}
		return framed;
	}

	private static boolean isCandidate(Minecraft mc, Player p) {
		if (p == mc.player || p.getUUID().equals(mc.player.getUUID())) {
			return false;
		}
		if (p.isSpectator() || p.isInvisible() || p.isRemoved() || Freecam.isCameraEntity(mc, p)) {
			return false;
		}
		// NPC plugins (Citizens and the like) use version-2 UUIDs and odd names.
		return p.getUUID().version() != 2 && realName(p.getPlainTextName());
	}

	private static boolean realName(String name) {
		if (name == null || name.isEmpty() || name.length() > 32) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isWhitespace(c) || c == '§') {
				return false;
			}
		}
		return true;
	}

	/** Whole box in the central part of the screen, big enough, and mostly not hidden behind blocks. */
	private static boolean wellFramed(ClientLevel level, SceneAnalyzer.View view, Player p) {
		AABB box = p.getBoundingBox();
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			double[] s = view.project(new Vec3((i & 1) == 0 ? box.minX : box.maxX,
				(i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ));
			if (s == null) {
				return false; // partly behind the camera
			}
			minX = Math.min(minX, s[0]);
			maxX = Math.max(maxX, s[0]);
			minY = Math.min(minY, s[1]);
			maxY = Math.max(maxY, s[1]);
		}
		if (minX < -FRAME || maxX > FRAME || minY < -FRAME || maxY > FRAME) {
			return false;
		}
		if ((maxY - minY) / 2 < MIN_HEIGHT) {
			return false; // a dot in the distance
		}
		double h = box.getYsize();
		Vec3 c = box.getCenter();
		Vec3 side = view.right().scale(box.getXsize() * 0.35);
		Vec3 shoulders = new Vec3(c.x, box.minY + h * 0.75, c.z);
		Vec3[] points = {
			p.getEyePosition(),
			c,
			shoulders.add(side),
			shoulders.subtract(side),
			new Vec3(c.x, box.minY + h * 0.3, c.z),
			new Vec3(c.x, box.minY + 0.1, c.z),
		};
		int visible = 0;
		for (int i = 0; i < POINTS; i++) {
			if (SceneAnalyzer.lineOfSight(level, view.eye(), points[i])) {
				visible++;
			} else if (i + 1 - visible > POINTS - MIN_VISIBLE) {
				return false;
			}
		}
		return visible >= MIN_VISIBLE;
	}
}
