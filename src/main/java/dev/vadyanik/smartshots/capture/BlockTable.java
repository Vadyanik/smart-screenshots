package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.storage.ShotMask;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block states, outline shapes, fluid heights and sign texts of the block positions a mask needs.
 * The level isn't thread-safe, so this is filled on the main thread a few milliseconds per tick
 * ({@link #resolve}); in between it's read by the mask worker only. The worker may queue more
 * positions ({@link #add}) for another round (rays continuing through glass).
 */
final class BlockTable {
	static final class StateInfo {
		final String blockKey;
		/** Key of the fluid in this state (water, lava, waterlogged blocks) or null. */
		final String fluidKey;
		/** Flattened {minX, minY, minZ, maxX, maxY, maxZ}* of the outline shape (block-local). */
		final double[] boxes;
		/** The shape is offset per position (flowers, grass...): see {@link #shapeAt}. */
		final boolean offset;
		/** Has collision: wins over an entity hitbox it overlaps (the floor under a mob's feet). */
		final boolean solid;
		final boolean sign;
		/** Things behind it stay visible: glass, panes, ice, slime... (see {@link #seeThrough}). */
		final boolean seeThrough;
		/** Its fluid is water (see-through too); false for lava and dry blocks. */
		final boolean water;
		/** Not drawn at all (barrier, light): rays pass. */
		final boolean invisible;
		/** Leaves: see-through, but a ray only crosses a few of them (see MaskJob.THROUGH_MAX_LEAVES). */
		final boolean leaves;
		int blockPalette = -1;
		int fluidPalette = -1;

		StateInfo(String blockKey, String fluidKey, double[] boxes, boolean offset, boolean solid, boolean sign,
			boolean seeThrough, boolean water, boolean invisible, boolean leaves) {
			this.blockKey = blockKey;
			this.fluidKey = fluidKey;
			this.boxes = boxes;
			this.offset = offset;
			this.solid = solid;
			this.sign = sign;
			this.seeThrough = seeThrough;
			this.water = water;
			this.invisible = invisible;
			this.leaves = leaves;
		}
	}

	private long[] positions;
	private int next;
	/** Every position looked up so far, air included. */
	final LongOpenHashSet resolved = new LongOpenHashSet();
	private final Vec3 cam;

	/** Position -> index into {@link #infos}; absent = air. */
	final Long2IntOpenHashMap stateAt = new Long2IntOpenHashMap();
	final List<StateInfo> infos = new ArrayList<>();
	final Long2ObjectOpenHashMap<double[]> shapeAt = new Long2ObjectOpenHashMap<>();
	final Long2FloatOpenHashMap fluidAt = new Long2FloatOpenHashMap();
	final Long2ObjectOpenHashMap<String> signAt = new Long2ObjectOpenHashMap<>();
	/** Block id -> localized name. */
	final Map<String, String> names = new HashMap<>();
	private final Reference2IntOpenHashMap<BlockState> stateIndex = new Reference2IntOpenHashMap<>();

	BlockTable(long[] positions, Vec3 cam) {
		this.positions = positions;
		this.cam = cam;
		stateAt.defaultReturnValue(-1);
		stateIndex.defaultReturnValue(-1);
	}

	int remaining() {
		return positions.length - next;
	}

	/** Queues more positions (a later round); already resolved ones are skipped. */
	void add(long[] more) {
		LongArrayList list = new LongArrayList(more.length);
		for (long k : more) {
			if (!resolved.contains(k)) {
				list.add(k);
			}
		}
		positions = list.toLongArray();
		next = 0;
	}

	/** Resolves positions until {@code deadline} (System.nanoTime). Returns true when done. */
	boolean resolve(ClientLevel level, long deadline) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		while (next < positions.length) {
			if ((next & 127) == 0 && System.nanoTime() > deadline) {
				return false;
			}
			long key = positions[next++];
			if (!resolved.add(key)) {
				continue;
			}
			m.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
			BlockState state = level.getBlockState(m);
			if (state.isAir()) {
				continue;
			}
			int idx = stateIndex.getInt(state);
			if (idx < 0) {
				idx = infos.size();
				infos.add(info(level, m, state));
				stateIndex.put(state, idx);
			}
			stateAt.put(key, idx);
			StateInfo info = infos.get(idx);
			if (info.offset) {
				shapeAt.put(key, boxes(state.getShape(level, m)));
			}
			if (info.fluidKey != null) {
				fluidAt.put(key, state.getFluidState().getHeight(level, m));
			}
			if (info.sign && level.getBlockEntity(m) instanceof SignBlockEntity sign) {
				String text = SceneAnalyzer.signText(sign.getText(SceneAnalyzer.facingSlot(sign, cam)));
				if (!text.isEmpty()) {
					signAt.put(key, text);
				}
			}
		}
		return true;
	}

	private StateInfo info(ClientLevel level, BlockPos pos, BlockState state) {
		Block block = state.getBlock();
		String id = BuiltInRegistries.BLOCK.getKey(block).toString();
		names.computeIfAbsent(id, k -> block.getName().getString());
		String fluidKey = null;
		FluidState fluid = state.getFluidState();
		boolean water = fluid.getType() instanceof WaterFluid;
		if (!fluid.isEmpty()) {
			Block fluidBlock = fluid.createLegacyBlock().getBlock();
			String fid = BuiltInRegistries.BLOCK.getKey(fluidBlock).toString();
			names.computeIfAbsent(fid, k -> fluidBlock.getName().getString());
			fluidKey = ShotMask.BLOCK + fid;
		}
		boolean offset = state.hasOffsetFunction();
		double[] boxes = offset ? new double[0] : boxes(state.getShape(level, pos));
		boolean solid = !state.getCollisionShape(level, pos).isEmpty();
		boolean invisible = fluid.isEmpty() && state.getRenderShape() == RenderShape.INVISIBLE;
		boolean leaves = isLeaves(state);
		return new StateInfo(ShotMask.BLOCK + id, fluidKey, boxes, offset, solid, block instanceof SignBlock,
			leaves || seeThrough(block, water), water, invisible, leaves);
	}

	/**
	 * Blocks you look through: glass of every kind (tinted too), panes and bars, ice, slime and
	 * honey, copper grates, and water. Leaves are handled apart ({@link #isLeaves}): see-through
	 * too, but rays give up after a few of them so a forest doesn't list everything behind it.
	 */
	private static boolean seeThrough(Block block, boolean water) {
		return block instanceof HalfTransparentBlock
			|| block instanceof IronBarsBlock
			|| water && block instanceof LiquidBlock;
	}

	/** Vanilla and modded leaves (the tag, or anything extending LeavesBlock). */
	static boolean isLeaves(BlockState state) {
		return state.getBlock() instanceof LeavesBlock || state.is(BlockTags.LEAVES);
	}

	private static double[] boxes(VoxelShape shape) {
		List<AABB> list = shape.toAabbs();
		double[] out = new double[list.size() * 6];
		for (int i = 0; i < list.size(); i++) {
			AABB b = list.get(i);
			out[i * 6] = b.minX;
			out[i * 6 + 1] = b.minY;
			out[i * 6 + 2] = b.minZ;
			out[i * 6 + 3] = b.maxX;
			out[i * 6 + 4] = b.maxY;
			out[i * 6 + 5] = b.maxZ;
		}
		return out;
	}
}
