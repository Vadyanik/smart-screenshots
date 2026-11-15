package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.StyledNames;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Records what is actually visible on screen at the moment of capture.
 *
 * <ul>
 *   <li>Blocks: a grid of rays is cast through the screen (like a very coarse ray tracer). Each
 *   ray's first hit counts towards that block's share of the image. A second pass looks for small
 *   but interesting blocks near the camera (ores, chests, spawners...) that the grid may miss and
 *   confirms each with a line-of-sight ray.</li>
 *   <li>Entities: anything inside the view frustum with an unobstructed line of sight to its body
 *   or eyes. Players are recorded by name, the local player too (third person, freecam). Dropped
 *   items and items in item frames are listed as items.</li>
 * </ul>
 *
 * <p>When the shot gets a per-pixel mask ({@link MaskJob}), only a coarse grid pass runs (so the
 * shot lists its main blocks right away); the mask counts every visible block exactly about a
 * second later and refines {@code blocks}.
 */
public final class SceneAnalyzer {
	private static final int GRID_X = 64;
	private static final int GRID_Y = 36;
	/** Grid when the mask follows: just the main blocks, cheap enough for every auto shot. */
	private static final int COARSE_X = 24;
	private static final int COARSE_Y = 14;
	private static final int SCAN_RADIUS = 20;
	private static final int MAX_PROBES_PER_BLOCK = 6;
	private static final double ENTITY_RANGE = 128;

	private SceneAnalyzer() {
	}

	/**
	 * @param blocksFromMask the shot gets a mask that will fill in the visible blocks
	 */
	public static void analyze(Minecraft mc, ShotMeta meta, boolean blocksFromMask) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		boolean freecam = Freecam.isActive(mc);
		// Where the shot was taken: the free camera's position, else the player's.
		Vec3 where = freecam ? camera.position() : mc.player.position();
		meta.dimension = level.dimension().identifier().toString();
		meta.x = where.x;
		meta.y = where.y;
		meta.z = where.z;
		meta.yaw = camera.yRot();
		meta.pitch = camera.xRot();
		meta.thirdPerson = !freecam && !mc.options.getCameraType().isFirstPerson();
		if (freecam) {
			addTag(meta, "freecam");
		}
		BlockPos wherePos = BlockPos.containing(where);
		try {
			level.getBiome(wherePos).unwrapKey().ifPresent(k -> {
				meta.biome = k.identifier().toString();
			});
		} catch (Exception ignored) {
		}

		View view = new View(camera, mc);
		try {
			analyzeBlocks(level, view, meta, mc, blocksFromMask);
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Block analysis failed", e);
		}
		try {
			analyzeSigns(level, view, meta);
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Sign analysis failed", e);
		}
		try {
			analyzeEntities(mc, level, view, meta);
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Entity analysis failed", e);
		}
		addEnvironmentTags(mc, level, camera, wherePos, meta);
	}

	/** Basic location only (scene analysis off). */
	public static void locate(Minecraft mc, ShotMeta meta) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		Vec3 where = Freecam.isActive(mc) ? mc.gameRenderer.mainCamera().position() : mc.player.position();
		meta.dimension = mc.level.dimension().identifier().toString();
		meta.x = where.x;
		meta.y = where.y;
		meta.z = where.z;
	}

	// ------------------------------------------------------------------ view math

	/** Minimal perspective projection matching the game camera (also used by {@link PlayerMeet}). */
	record View(Vec3 eye, Vec3 forward, Vec3 up, Vec3 right, double tanH, double tanV) {
		View(Camera camera, Minecraft mc) {
			this(camera.position(), vec(camera.forwardVector()), vec(camera.upVector()), vec(camera.leftVector()).scale(-1),
				tanH(camera, mc), tanV(camera, mc));
		}

		private static double tanV(Camera camera, Minecraft mc) {
			float fov = camera.getFov();
			if (fov <= 1 || fov >= 179) {
				fov = mc.options.fov().get();
			}
			return Math.tan(Math.toRadians(fov) / 2);
		}

		private static double tanH(Camera camera, Minecraft mc) {
			double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
			return tanV(camera, mc) * aspect;
		}

		/** Returns normalized screen coords [-1..1] or null when behind the camera. */
		double[] project(Vec3 p) {
			Vec3 d = p.subtract(eye);
			double z = d.dot(forward);
			if (z < 0.05) {
				return null;
			}
			return new double[]{d.dot(right) / (z * tanH), d.dot(up) / (z * tanV)};
		}

		boolean onScreen(Vec3 p, double margin) {
			double[] s = project(p);
			return s != null && Math.abs(s[0]) <= 1 + margin && Math.abs(s[1]) <= 1 + margin;
		}

		Vec3 ray(double nx, double ny) {
			return forward.add(right.scale(nx * tanH)).add(up.scale(ny * tanV)).normalize();
		}
	}

	private static Vec3 vec(Vector3fc v) {
		return new Vec3(v.x(), v.y(), v.z());
	}

	// ------------------------------------------------------------------ blocks

	/**
	 * @param coarse the mask fills in the details later: a small grid only (no near scan, no sky
	 * tag), so the shot isn't blockless meanwhile
	 */
	private static void analyzeBlocks(ClientLevel level, View view, ShotMeta meta, Minecraft mc, boolean coarse) {
		double maxDist = Math.min(mc.options.getEffectiveRenderDistance() * 16.0, coarse ? 128.0 : 256.0);
		int gridX = coarse ? COARSE_X : GRID_X;
		int gridY = coarse ? COARSE_Y : GRID_Y;
		boolean underFluid = mc.gameRenderer.mainCamera().getFluidInCamera() != FogType.NONE
			&& mc.gameRenderer.mainCamera().getFluidInCamera() != FogType.ATMOSPHERIC;
		ClipContext.Fluid fluidMode = underFluid ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY;

		Map<String, Integer> counts = new HashMap<>();
		int sky = 0;
		int total = gridX * gridY;
		for (int gy = 0; gy < gridY; gy++) {
			for (int gx = 0; gx < gridX; gx++) {
				double nx = 2.0 * (gx + 0.5) / gridX - 1.0;
				double ny = 1.0 - 2.0 * (gy + 0.5) / gridY;
				Vec3 dir = view.ray(nx, ny);
				BlockHitResult hit = level.clip(new ClipContext(view.eye, view.eye.add(dir.scale(maxDist)),
					ClipContext.Block.VISUAL, fluidMode, CollisionContext.empty()));
				if (hit.getType() != HitResult.Type.BLOCK) {
					sky++;
					continue;
				}
				BlockState state = level.getBlockState(hit.getBlockPos());
				String id = blockId(state, meta);
				if (id != null) {
					counts.merge(id, 1, Integer::sum);
				}
			}
		}
		Map<String, Integer> weights = new HashMap<>();
		counts.forEach((id, c) -> weights.put(id, Math.max(1, c * 1000 / total)));
		if (coarse) {
			putSorted(meta, weights);
			return;
		}
		if (sky * 100 / total >= 25 && level.dimensionType().hasSkyLight()) {
			meta.tags.add("sky");
		}

		// Second pass: small things near the camera the coarse grid can miss.
		BlockPos center = BlockPos.containing(view.eye);
		Map<String, List<BlockPos>> candidates = new HashMap<>();
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
		int r = SCAN_RADIUS;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dy * dy + dz * dz > r * r) {
						continue;
					}
					mpos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = level.getBlockState(mpos);
					if (state.isAir()) {
						continue;
					}
					String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
					if (weights.containsKey(id)) {
						continue;
					}
					if (!view.onScreen(Vec3.atCenterOf(mpos), 0)) {
						continue;
					}
					candidates.computeIfAbsent(id, k -> new ArrayList<>()).add(mpos.immutable());
				}
			}
		}
		for (Map.Entry<String, List<BlockPos>> e : candidates.entrySet()) {
			List<BlockPos> list = e.getValue();
			list.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(view.eye)));
			int probes = 0;
			for (BlockPos pos : list) {
				if (probes++ >= MAX_PROBES_PER_BLOCK) {
					break;
				}
				if (blockVisible(level, view.eye, pos)) {
					blockId(level.getBlockState(pos), meta);
					weights.put(e.getKey(), 0);
					break;
				}
			}
		}

		putSorted(meta, weights);
	}

	/** Keep the most visible blocks first; cap the list so metadata stays small. */
	private static void putSorted(ShotMeta meta, Map<String, Integer> weights) {
		meta.blocks = new LinkedHashMap<>();
		weights.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(80)
			.forEach(en -> meta.blocks.put(en.getKey(), en.getValue()));
	}

	private static String blockId(BlockState state, ShotMeta meta) {
		if (state.isAir()) {
			return null;
		}
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		meta.names.computeIfAbsent(id, k -> state.getBlock().getName().getString());
		return id;
	}

	private static boolean blockVisible(ClientLevel level, Vec3 eye, BlockPos pos) {
		Vec3 target = Vec3.atCenterOf(pos);
		// Aim slightly towards the camera so we hit the face we can see.
		Vec3 toEye = eye.subtract(target).normalize().scale(0.45);
		// OUTLINE so thin blocks without a visual shape (signs, torches, flowers) can be hit too.
		BlockHitResult hit = level.clip(new ClipContext(eye, target.add(toEye.scale(-0.2)),
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	// ------------------------------------------------------------------ signs

	private static final double SIGN_RANGE = 28;

	/** Reads the text of every sign that is on screen, close enough to read and not hidden behind blocks. */
	private static void analyzeSigns(ClientLevel level, View view, ShotMeta meta) {
		int cx = SectionPos.blockToSectionCoord(view.eye.x);
		int cz = SectionPos.blockToSectionCoord(view.eye.z);
		int r = (int) Math.ceil(SIGN_RANGE / 16) + 1;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				ChunkAccess chunk = level.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
				if (!(chunk instanceof LevelChunk lc)) {
					continue;
				}
				for (BlockEntity be : lc.getBlockEntities().values()) {
					if (!(be instanceof SignBlockEntity sign)) {
						continue;
					}
					BlockPos pos = sign.getBlockPos();
					Vec3 center = Vec3.atCenterOf(pos);
					if (center.distanceTo(view.eye) > SIGN_RANGE || !view.onScreen(center, 0.05)) {
						continue;
					}
					if (!blockVisible(level, view.eye, pos)) {
						continue;
					}
					String text = signText(sign.getText(facingSlot(sign, view.eye)));
					if (text.isEmpty()) {
						continue;
					}
					if (!meta.signs.contains(text)) {
						meta.signs.add(text);
					}
				}
			}
		}
	}

	static SignTextSlot facingSlot(SignBlockEntity sign, Vec3 eye) {
		if (sign.getBlockState().getBlock() instanceof SignBlock block) {
			Vec3 off = block.getSignHitboxCenterPosition(sign.getBlockState());
			double xd = eye.x - (sign.getBlockPos().getX() + off.x);
			double zd = eye.z - (sign.getBlockPos().getZ() + off.z);
			float signYRot = block.getYRotationDegrees(sign.getBlockState());
			float camYRot = (float) (Mth.atan2(zd, xd) * 180.0F / (float) Math.PI) - 90.0F;
			return Mth.degreesDifferenceAbs(signYRot, camYRot) <= 90.0F ? SignTextSlot.FRONT : SignTextSlot.BACK;
		}
		return SignTextSlot.FRONT;
	}

	/** Sign text as stored in {@link ShotMeta#signs}: non-empty lines joined with spaces. */
	static String signText(SignText text) {
		List<String> lines = new ArrayList<>();
		for (Component c : text.getMessages(false)) {
			String s = c.getString().replaceAll("\u00A7.", "").strip();
			if (!s.isEmpty()) {
				lines.add(s);
			}
		}
		return String.join(" ", lines);
	}

	// ------------------------------------------------------------------ entities

	/** Entities never listed: the camera itself (first person / freecam), invisible ones, spectators. */
	static boolean skipEntity(Minecraft mc, Entity e, Entity cameraEntity, boolean detached) {
		if (e == cameraEntity && !detached) {
			return true;
		}
		return e.isInvisible() || e.isSpectator() || Freecam.isCameraEntity(mc, e);
	}

	private static void analyzeEntities(Minecraft mc, ClientLevel level, View view, ShotMeta meta) {
		Entity cameraEntity = mc.getCameraEntity();
		boolean detached = mc.gameRenderer.mainCamera().isDetached();
		TreeSet<String> players = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		players.addAll(meta.players);
		Map<String, Integer> entities = new HashMap<>();
		Map<String, Integer> items = new HashMap<>();
		for (Entity e : level.entitiesForRendering()) {
			if (skipEntity(mc, e, cameraEntity, detached)) {
				continue;
			}
			Vec3 c = e.getBoundingBox().getCenter();
			if (c.distanceTo(view.eye) > ENTITY_RANGE) {
				continue;
			}
			if (!entityVisible(level, view, e)) {
				continue;
			}
			if (e instanceof ItemEntity item) {
				String id = EntitySnapshot.itemId(item.getItem(), meta.names);
				if (id != null) {
					items.merge(id, 1, Integer::sum);
				}
				continue; // dropped items are items, not "minecraft:item" entities
			}
			EntityType<?> type = e.getType();
			String id = EntityType.getKey(type).toString();
			entities.merge(id, 1, Integer::sum);
			meta.names.computeIfAbsent(id, k -> type.getDescription().getString());
			if (e instanceof ItemFrame frame) {
				String item = EntitySnapshot.itemId(frame.getItem(), meta.names);
				if (item != null) {
					items.merge(item, 1, Integer::sum);
				}
			}
			if (e instanceof Player p) {
				String nick = p.getPlainTextName();
				players.add(nick);
				meta.playerIds.put(nick, p.getUUID().toString());
				StyledNames.record(meta, mc, nick, p.getUUID(), p);
			} else if (e.hasCustomName() && e.getCustomName() != null) {
				String name = e.getCustomName().getString();
				addTag(meta, "name:" + name);
				if (meta.named == null) {
					meta.named = new ArrayList<>();
				}
				String named = id + "|" + name;
				if (!meta.named.contains(named)) {
					meta.named.add(named);
				}
			}
			if (e instanceof TamableAnimal t && t.isTame()) {
				addTag(meta, "tamed");
			}
		}
		meta.players = new ArrayList<>(players);
		meta.entities = sortedByCount(entities);
		meta.items = sortedByCount(items);
	}

	private static Map<String, Integer> sortedByCount(Map<String, Integer> counts) {
		Map<String, Integer> out = new LinkedHashMap<>();
		counts.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.forEach(en -> out.put(en.getKey(), en.getValue()));
		return out;
	}

	/** Line of sight from the camera to {@code p}, blocks only (glass and other see-through shapes let it pass). */
	static boolean lineOfSight(ClientLevel level, Vec3 eye, Vec3 p) {
		BlockHitResult hit = level.clip(new ClipContext(eye, p, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(eye) >= p.distanceToSqr(eye) - 0.25;
	}

	static boolean entityVisible(ClientLevel level, View view, Entity e) {
		var box = e.getBoundingBox();
		Vec3[] points = {box.getCenter(), e.getEyePosition(), new Vec3(box.getCenter().x, box.minY + 0.1, box.getCenter().z)};
		double margin = Math.max(box.getXsize(), box.getYsize()) / Math.max(1.0, box.getCenter().distanceTo(view.eye)) * 0.5;
		for (Vec3 p : points) {
			if (!view.onScreen(p, margin)) {
				continue;
			}
			if (lineOfSight(level, view.eye, p)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ environment

	private static void addEnvironmentTags(Minecraft mc, ClientLevel level, Camera camera, BlockPos where, ShotMeta meta) {
		if (meta.thirdPerson) {
			addTag(meta, "third_person");
		}
		FogType fog = camera.getFluidInCamera();
		if (fog == FogType.WATER) {
			addTag(meta, "underwater");
		} else if (fog == FogType.LAVA) {
			addTag(meta, "in_lava");
		}
		if (level.dimensionType().defaultClock().isPresent()) {
			long t = Math.floorMod(level.getDefaultClockTime(), 24000L);
			if (t < 12000) {
				addTag(meta, "day");
			} else if (t < 13800) {
				addTag(meta, "sunset");
			} else if (t < 22200) {
				addTag(meta, "night");
			} else {
				addTag(meta, "sunrise");
			}
		}
		if (level.dimensionType().hasSkyLight()) {
			if (level.isThundering()) {
				addTag(meta, "thunder");
			} else if (level.isRaining()) {
				addTag(meta, "rain");
			}
		}
		if (where.getY() < level.getSeaLevel() - 10 && !level.canSeeSky(where)) {
			addTag(meta, "underground");
		}
		if (mc.getCurrentServer() != null) {
			try {
				Objective obj = level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
				if (obj != null) {
					String s = obj.getDisplayName().getString().replaceAll("§.", "").strip();
					if (!s.isEmpty() && s.length() <= 40) {
						addTag(meta, "sidebar:" + s);
					}
				}
			} catch (Exception ignored) {
			}
		}
	}

	private static void addTag(ShotMeta meta, String tag) {
		if (!meta.tags.contains(tag)) {
			meta.tags.add(tag);
		}
	}
}
