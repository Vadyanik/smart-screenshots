package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.storage.ShotMask;
import dev.vadyanik.smartshots.storage.StyledNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity bounding boxes at their rendered (interpolated) positions, taken on the main thread in
 * the captured frame so the mask worker never touches the level.
 */
final class EntitySnapshot {
	private static final double RANGE = 160;
	/** Strict match: the hitbox, barely inflated. */
	private static final double STRICT = 0.05;
	/** Second chance: models stick out of hitboxes (spider legs, held items, tails...). */
	private static final double GENEROUS = 0.3;

	static final class Box {
		/** Camera-relative {minX, minY, minZ, maxX, maxY, maxZ}. */
		final double[] strict;
		final double[] generous;
		final double volume;
		final String[] keys;
		int palette = -1;
		int pixels;

		Box(double[] strict, double[] generous, String[] keys) {
			this.strict = strict;
			this.generous = generous;
			this.keys = keys;
			this.volume = (strict[3] - strict[0]) * (strict[4] - strict[1]) * (strict[5] - strict[2]);
		}
	}

	final List<Box> boxes = new ArrayList<>();
	/** Same keys as {@link dev.vadyanik.smartshots.storage.ShotMeta#names}. */
	final Map<String, String> names = new HashMap<>();
	final Map<String, String> playerIds = new HashMap<>();
	/** Nickname -> styled name JSON ({@link dev.vadyanik.smartshots.storage.StyledNames}). */
	final Map<String, String> playerNames = new HashMap<>();

	static EntitySnapshot take(Minecraft mc, FrameView view) {
		EntitySnapshot snap = new EntitySnapshot();
		ClientLevel level = mc.level;
		if (level == null) {
			return snap;
		}
		Entity cameraEntity = mc.getCameraEntity();
		boolean detached = mc.gameRenderer.mainCamera().isDetached();
		Vec3 cam = view.cam();
		for (Entity e : level.entitiesForRendering()) {
			if (SceneAnalyzer.skipEntity(mc, e, cameraEntity, detached)) {
				continue;
			}
			Vec3 render = e.getPosition(view.partialTick());
			AABB box = e.getBoundingBox().move(render.subtract(e.position()));
			if (box.getCenter().distanceToSqr(cam) > RANGE * RANGE) {
				continue;
			}
			String[] keys = keys(mc, e, snap);
			if (keys == null) {
				continue;
			}
			AABB rel = box.move(-cam.x, -cam.y, -cam.z);
			AABB generous = e instanceof ItemEntity
				// Dropped items bob and spin well outside their tiny hitbox.
				? rel.inflate(0.35, 0.2, 0.35).expandTowards(0, 0.35, 0)
				: rel.inflate(GENEROUS);
			snap.boxes.add(new Box(array(rel.inflate(STRICT)), array(generous), keys));
		}
		return snap;
	}

	private static String[] keys(Minecraft mc, Entity e, EntitySnapshot snap) {
		if (e instanceof ItemEntity item) {
			String id = itemId(item.getItem(), snap.names);
			return id == null ? null : new String[]{ShotMask.ITEM + id};
		}
		EntityType<?> type = e.getType();
		String typeId = EntityType.getKey(type).toString();
		snap.names.putIfAbsent(typeId, type.getDescription().getString());
		if (e instanceof Player p) {
			String nick = p.getPlainTextName();
			snap.playerIds.put(nick, p.getUUID().toString());
			if (!snap.playerNames.containsKey(nick)) {
				String json = StyledNames.capture(mc, nick, p.getUUID(), p);
				if (json != null) {
					snap.playerNames.put(nick, json);
				}
			}
			return new String[]{ShotMask.PLAYER + nick};
		}
		String named = e.hasCustomName() && e.getCustomName() != null
			? ShotMask.named(typeId, e.getCustomName().getString()) : null;
		if (e instanceof ItemFrame frame) {
			String id = itemId(frame.getItem(), snap.names);
			if (id != null) {
				return named != null
					? new String[]{ShotMask.ITEM + id, named, ShotMask.ENTITY + typeId}
					: new String[]{ShotMask.ITEM + id, ShotMask.ENTITY + typeId};
			}
		}
		return named != null
			? new String[]{named, ShotMask.ENTITY + typeId}
			: new String[]{ShotMask.ENTITY + typeId};
	}

	/** Item id of a non-empty stack; records its name under "item|&lt;id&gt;". */
	static String itemId(ItemStack stack, Map<String, String> names) {
		if (stack.isEmpty()) {
			return null;
		}
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		names.computeIfAbsent("item|" + id, k -> stack.getItem().getName(stack.getItem().getDefaultInstance()).getString());
		return id;
	}

	private static double[] array(AABB b) {
		return new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
	}
}
