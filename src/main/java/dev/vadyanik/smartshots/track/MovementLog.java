package dev.vadyanik.smartshots.track;

import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.storage.FileNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Movement log, independent of screenshots: where the player is, in which world and dimension,
 * how fast and how they move, with exact timestamps. One CSV per play session in
 * {@code <game dir>/movement-log/<singleplayer|multiplayer>/<world or server>/<start time>.csv},
 * meant for making a visualization later. Samples are taken every {@code trackIntervalTicks}
 * while something changes, at least every few seconds while idle, plus event rows (join, leave,
 * dimension change, death, respawn, teleport, pause).
 *
 * <p>Writing happens on a thread of its own; the render thread only formats a line.
 */
public final class MovementLog {
	public static final String DIR = "movement-log";
	/** Columns of every CSV, in order. */
	public static final String HEADER = "time_ms,time_local,session_s,game_tick,day_time,event,dimension,x,y,z,yaw,pitch,"
		+ "speed,h_speed,v_speed,mode,vehicle,on_ground,in_water,in_lava,health,food,xp_level,gamemode,biome,weather,held";
	private static final long IDLE_HEARTBEAT_MS = 5000;
	private static final long FLUSH_MS = 3000;
	/** A jump larger than this between two samples (same dimension) is a teleport. */
	private static final double TELEPORT_BLOCKS = 48;
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
	private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

	static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SmartShots MovementLog");
		t.setDaemon(true);
		return t;
	});

	// Render-thread state of the current session.
	private static String worldKey;
	private static long sessionStart;
	private static int tickCounter;
	private static Vec3 lastPos;
	private static long lastTime;
	private static String lastDim;
	private static float lastYaw;
	private static float lastPitch;
	private static String lastState;
	private static long lastWrite;
	private static boolean wasDead;
	private static boolean wasPaused;

	// IO-thread state.
	private static BufferedWriter out;
	private static Path file;
	private static long lastFlush;

	private MovementLog() {
	}

	public static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(DIR);
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!SmartShotsConfig.get().trackEnabled || p == null || mc.level == null) {
			if (worldKey != null) {
				endSession("leave");
			}
			return;
		}
		String[] world = world(mc);
		String key = world[0] + "/" + world[1];
		if (!key.equals(worldKey)) {
			if (worldKey != null) {
				endSession("leave");
			}
			startSession(mc, world, key);
			sample(mc, p, "join");
			return;
		}
		InventoryLog.tick(mc, p);

		boolean paused = mc.isPaused();
		if (paused != wasPaused) {
			wasPaused = paused;
			sample(mc, p, paused ? "pause" : "resume");
			return;
		}
		if (paused) {
			return;
		}
		boolean dead = p.isDeadOrDying();
		if (dead != wasDead) {
			wasDead = dead;
			sample(mc, p, dead ? "death" : "respawn");
			return;
		}
		String dim = mc.level.dimension().identifier().toString();
		if (!dim.equals(lastDim)) {
			sample(mc, p, "dimension");
			return;
		}
		if (lastPos != null && p.position().distanceTo(lastPos) > TELEPORT_BLOCKS) {
			sample(mc, p, "teleport");
			return;
		}
		if (++tickCounter < Math.max(1, SmartShotsConfig.get().trackIntervalTicks)) {
			return;
		}
		tickCounter = 0;
		boolean moved = lastPos == null || p.position().distanceToSqr(lastPos) > 1e-6
			|| Math.abs(p.getYRot() - lastYaw) > 0.5f || Math.abs(p.getXRot() - lastPitch) > 0.5f;
		boolean stateChanged = !state(mc, p).equals(lastState);
		if (moved || stateChanged || System.currentTimeMillis() - lastWrite >= IDLE_HEARTBEAT_MS) {
			sample(mc, p, "");
		}
	}

	/** {category, key, display name} of the world being played. */
	private static String[] world(Minecraft mc) {
		IntegratedServer sp = mc.getSingleplayerServer();
		ServerData server = mc.getCurrentServer();
		if (sp != null) {
			Path worldPath = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			String folder = worldPath.getFileName() != null ? worldPath.getFileName().toString() : sp.getWorldData().getLevelName();
			return new String[]{"singleplayer", folder, sp.getWorldData().getLevelName()};
		}
		if (server != null) {
			String category = server.isRealm() ? "realms" : "multiplayer";
			String key = server.isRealm() || server.isLan() ? server.name : server.ip.toLowerCase(Locale.ROOT);
			return new String[]{category, key, server.name};
		}
		return new String[]{"other", "unknown", "Unknown"};
	}

	private static void startSession(Minecraft mc, String[] world, String key) {
		worldKey = key;
		sessionStart = System.currentTimeMillis();
		tickCounter = 0;
		lastPos = null;
		lastDim = null;
		lastState = null;
		wasDead = false;
		wasPaused = false;
		Path dir = root().resolve(world[0]).resolve(FileNames.sanitize(world[1]));
		String name = LocalDateTime.now().format(FILE_TIME) + ".csv";
		String player = mc.player != null ? mc.player.getGameProfile().name() : "";
		String meta = "# Smart Screenshots movement log\n"
			+ "# world: " + world[2].replace('\n', ' ') + "\n"
			+ "# key: " + world[0] + "/" + world[1].replace('\n', ' ') + "\n"
			+ "# player: " + player + "\n"
			+ "# started: " + LocalDateTime.now().format(ISO) + " " + ZoneId.systemDefault() + "\n"
			+ "# speed in blocks/s; yaw/pitch in degrees; day_time 0..23999 (0 = 6:00); time_ms = Unix epoch ms\n"
			+ HEADER + "\n";
		IO.execute(() -> {
			closeFile();
			try {
				Files.createDirectories(dir);
				file = dir.resolve(name);
				out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				out.write(meta);
				out.flush();
				lastFlush = System.currentTimeMillis();
			} catch (IOException e) {
				SmartShots.LOGGER.warn("Couldn't start the movement log in {}", dir, e);
				out = null;
			}
		});
		InventoryLog.start(dir.resolve(name.replace(".csv", ".inventory.jsonl")));
	}

	private static void endSession(String event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.level != null) {
			sample(mc, mc.player, event);
		} else if (lastPos != null) {
			// The world is already gone: repeat the last known place with the event.
			long now = System.currentTimeMillis();
			write(now + "," + iso(now) + "," + fmt((now - sessionStart) / 1000.0, 3) + ",,," + event + "," + csv(lastDim) + ","
				+ fmt(lastPos.x, 3) + "," + fmt(lastPos.y, 3) + "," + fmt(lastPos.z, 3) + ",,,,,,,,,,,,,,,,");
		}
		worldKey = null;
		lastPos = null;
		InventoryLog.end();
		IO.execute(MovementLog::closeFile);
	}

	/** Writes everything still buffered (game shutting down). */
	public static void shutdown() {
		if (worldKey != null) {
			endSession("quit");
		}
		IO.execute(MovementLog::closeFile);
		IO.shutdown();
		try {
			IO.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Things that make a row worth writing even without moving. */
	private static String state(Minecraft mc, LocalPlayer p) {
		return mode(p) + "|" + p.onGround() + "|" + p.isInWater() + "|" + (int) p.getHealth() + "|" + p.getFoodData().getFoodLevel()
			+ "|" + p.experienceLevel + "|" + gameMode(mc);
	}

	private static String mode(LocalPlayer p) {
		if (p.isPassenger()) {
			return "ride";
		}
		if (p.isFallFlying()) {
			return "elytra";
		}
		if (p.getAbilities().flying) {
			return "fly";
		}
		if (p.isSwimming()) {
			return "swim";
		}
		if (p.isSprinting()) {
			return "sprint";
		}
		if (p.isCrouching()) {
			return "sneak";
		}
		return p.isInWater() || p.isInLava() ? "wade" : "walk";
	}

	private static String gameMode(Minecraft mc) {
		GameType t = mc.gameMode != null ? mc.gameMode.getPlayerMode() : null;
		return t != null ? t.getName() : "";
	}

	private static void sample(Minecraft mc, LocalPlayer p, String event) {
		long now = System.currentTimeMillis();
		Vec3 pos = p.position();
		String dim = mc.level.dimension().identifier().toString();
		double speed = 0;
		double hSpeed = 0;
		double vSpeed = 0;
		if (lastPos != null && dim.equals(lastDim) && now > lastTime && !"teleport".equals(event)) {
			double dt = (now - lastTime) / 1000.0;
			double dx = pos.x - lastPos.x;
			double dy = pos.y - lastPos.y;
			double dz = pos.z - lastPos.z;
			hSpeed = Math.sqrt(dx * dx + dz * dz) / dt;
			vSpeed = dy / dt;
			speed = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
		}
		Entity vehicle = p.getVehicle();
		String vehicleId = vehicle != null ? BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString() : "";
		String biome = "";
		try {
			biome = mc.level.getBiome(BlockPos.containing(pos)).unwrapKey().map(k -> k.identifier().toString()).orElse("");
		} catch (Exception ignored) {
		}
		String weather = mc.level.isThundering() ? "thunder" : mc.level.isRaining() ? "rain" : "clear";
		long dayTime = 0;
		try {
			dayTime = Math.floorMod(mc.level.getOverworldClockTime(), 24000L);
		} catch (Exception ignored) {
		}
		String line = now + "," + iso(now) + "," + fmt((now - sessionStart) / 1000.0, 3) + "," + mc.level.getGameTime() + ","
			+ dayTime + "," + event + "," + csv(dim) + ","
			+ fmt(pos.x, 3) + "," + fmt(pos.y, 3) + "," + fmt(pos.z, 3) + ","
			+ fmt(p.getYRot(), 1) + "," + fmt(p.getXRot(), 1) + ","
			+ fmt(speed, 3) + "," + fmt(hSpeed, 3) + "," + fmt(vSpeed, 3) + ","
			+ mode(p) + "," + csv(vehicleId) + "," + (p.onGround() ? 1 : 0) + "," + (p.isInWater() ? 1 : 0) + "," + (p.isInLava() ? 1 : 0) + ","
			+ fmt(p.getHealth(), 1) + "," + p.getFoodData().getFoodLevel() + "," + p.experienceLevel + "," + gameMode(mc) + ","
			+ csv(biome) + "," + weather + "," + csv(InventoryLog.lowerId(p.getMainHandItem()));
		write(line);
		switch (event) {
			case "death", "respawn", "dimension", "teleport", "leave", "quit" -> InventoryLog.snapshot(mc, p, event);
			default -> {
			}
		}
		lastPos = pos;
		lastTime = now;
		lastDim = dim;
		lastYaw = p.getYRot();
		lastPitch = p.getXRot();
		lastState = state(mc, p);
		lastWrite = now;
	}

	private static void write(String line) {
		IO.execute(() -> {
			if (out == null) {
				return;
			}
			try {
				out.write(line);
				out.write('\n');
				long now = System.currentTimeMillis();
				if (now - lastFlush >= FLUSH_MS) {
					out.flush();
					InventoryLog.flush();
					lastFlush = now;
				}
			} catch (IOException e) {
				SmartShots.LOGGER.warn("Couldn't write the movement log {}", file, e);
				closeFile();
			}
		});
	}

	private static void closeFile() {
		InventoryLog.close();
		if (out != null) {
			try {
				out.close();
			} catch (IOException ignored) {
			}
			out = null;
		}
	}

	private static String iso(long ms) {
		return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(ISO);
	}

	private static String fmt(double v, int digits) {
		return String.format(Locale.ROOT, "%." + digits + "f", v);
	}

	private static String csv(String s) {
		if (s == null) {
			return "";
		}
		return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
	}
}
