package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.gui.GalleryScreen;
import dev.vadyanik.smartshots.storage.ImageOps;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMask;
import dev.vadyanik.smartshots.storage.ShotMeta;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds the per-pixel {@link ShotMask} of one shot from its depth buffer.
 *
 * <ol>
 *   <li>Worker: every depth pixel is turned back into a world point P; the block positions around
 *   P (P nudged slightly into and out of the surface) are collected.</li>
 *   <li>Main thread, a few ms per tick: those positions are looked up in the level
 *   ({@link BlockTable}).</li>
 *   <li>Worker: each pixel is classified (entity hitboxes vs. block outline shapes vs. fluids).</li>
 *   <li>See-through rounds: pixels showing glass, ice, water... follow their view ray on, voxel by
 *   voxel, to the block or entity behind (worker), asking the main thread for the positions it
 *   doesn't know yet (step 2 again) until every ray is done or the round limit is hit. Such
 *   pixels get palette entries "front first, then behind", e.g. {@code [b:glass, b:chest]}.</li>
 *   <li>IO thread, once the PNG is saved: the mask is written and what it found is merged into
 *   the metadata (on disk, then in the gallery's copy).</li>
 * </ol>
 */
final class MaskJob {
	static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SmartShots Mask");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	/** Jobs waiting for main-thread block lookups, oldest first. */
	private static final Queue<MaskJob> RESOLVING = new ConcurrentLinkedQueue<>();
	private static final long TICK_BUDGET_NANOS = 3_000_000L;
	private static final double MAX_DIST = 1024;
	private static final int TILE_SHIFT = 4;
	/** Room for the most blocks the metadata lists (same cap as the scene analyzer). */
	private static final int MAX_BLOCKS = 80;

	private final ClientLevel level;
	private final FrameView view;
	private final EntitySnapshot entities;
	private final boolean skyLight;
	private volatile float[] depth;
	private volatile BlockTable blocks;
	/** Block id -> name, kept after the block table is dropped. */
	private volatile Map<String, String> blockNames = Map.of();
	private final CompletableFuture<Result> mask = new CompletableFuture<>();
	private final CompletableFuture<Path> file = new CompletableFuture<>();
	/** Size of the saved PNG (set before {@link #file} completes); the mask is scaled to it. */
	private volatile int savedWidth;
	private volatile int savedHeight;

	private record Result(ShotMask mask, Map<String, Integer> keyPixels, Map<String, Integer> instances,
		Map<String, Integer> blockCounts, int threshold, int sky) {
	}

	MaskJob(ClientLevel level, FrameView view, EntitySnapshot entities, boolean skyLight) {
		this.level = level;
		this.view = view;
		this.entities = entities;
		this.skyLight = skyLight;
		mask.thenAcceptBoth(file, (result, shot) -> ScreenshotStore.IO.execute(() -> finish(result, shot)));
	}

	/** Render thread: the depth readback arrived. */
	void onDepth(float[] depth) {
		if (depth == null) {
			cancel();
			return;
		}
		if (depth.length != view.width() * view.height()) {
			SmartShots.LOGGER.warn("Depth size mismatch, no mask");
			cancel();
			return;
		}
		this.depth = depth;
		WORKER.execute(this::collect);
	}

	/** The PNG is on disk, {@code width x height} (possibly downscaled from the frame). */
	void onSaved(Path shot, int width, int height) {
		savedWidth = width;
		savedHeight = height;
		file.complete(shot);
	}

	void cancel() {
		mask.cancel(false);
		file.cancel(false);
		depth = null;
		blocks = null;
		pass = null;
	}

	/** Main thread, every client tick: block lookups for pending jobs within a small time budget. */
	static void tick(Minecraft mc) {
		long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
		MaskJob job;
		while ((job = RESOLVING.peek()) != null) {
			if (job.mask.isDone() || mc.level != job.level) {
				RESOLVING.poll();
				job.cancel(); // left the world: the blocks we'd read are not the ones on the shot
				continue;
			}
			try {
				if (!job.blocks.resolve(mc.level, deadline)) {
					return;
				}
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Block lookup for mask failed", e);
				RESOLVING.poll();
				job.cancel();
				continue;
			}
			RESOLVING.poll();
			WORKER.execute(job::afterResolve);
			if (System.nanoTime() > deadline) {
				return;
			}
		}
	}

	// ------------------------------------------------------------------ pass 1: positions

	private static double eps(double dist) {
		return 0.01 + dist * 0.0005;
	}

	private void collect() {
		try {
			float[] d = depth;
			if (d == null) {
				return;
			}
			long start = System.nanoTime();
			long[] positions = positions(d);
			blocks = new BlockTable(positions, view.cam());
			RESOLVING.add(this);
			SmartShots.LOGGER.debug("Mask: {} block positions collected in {} ms", positions.length, (System.nanoTime() - start) / 1_000_000);
		} catch (Throwable t) {
			SmartShots.LOGGER.warn("Mask pass 1 failed", t);
			cancel();
		}
	}

	private long[] positions(float[] depth) {
		int w = view.width();
		int h = view.height();
		double cx = view.cam().x;
		double cy = view.cam().y;
		double cz = view.cam().z;
		LongOpenHashSet set = new LongOpenHashSet(1 << 16);
		double[] p = new double[3];
		long[] last = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
		long[] cur = new long[3];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float d = depth[y * w + x];
				if (d <= FrameView.FAR || !view.unproject(x, y, d, p)) {
					continue;
				}
				double dist = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
				if (dist > MAX_DIST || dist < 1e-6) {
					continue;
				}
				double s = eps(dist) / dist;
				for (int c = 0; c < 3; c++) {
					double t = 1 + (1 - c) * s;
					cur[c] = BlockPos.asLong(Mth.floor(cx + p[0] * t), Mth.floor(cy + p[1] * t), Mth.floor(cz + p[2] * t));
				}
				// Neighbouring pixels mostly hit the same blocks: skip the hashing then.
				for (long k : cur) {
					if (k != last[0] && k != last[1] && k != last[2]) {
						set.add(k);
					}
				}
				System.arraycopy(cur, 0, last, 0, 3);
			}
		}
		return set.toLongArray();
	}

	// ------------------------------------------------------------------ pass 2: classification

	private static final class Palette {
		final List<String[]> entries = new ArrayList<>();
		final Map<String, Integer> index = new HashMap<>();

		Palette() {
			entries.add(new String[0]);
		}

		int of(String... keys) {
			String k = String.join("\u0000", keys);
			Integer i = index.get(k);
			if (i == null) {
				if (entries.size() >= Character.MAX_VALUE) {
					return 0; // absurdly many distinct things: leave the rest unlabeled
				}
				i = entries.size();
				entries.add(keys);
				index.put(k, i);
			}
			return i;
		}
	}

	// See-through marching: rays leaving a glass / water surface continue to the next real thing.
	/** Only surfaces this close get looked through (block distance from the camera). */
	private static final double THROUGH_MAX_FRONT = 160;
	/** Water is only looked into when near: further away the bottom is lost in the fog anyway. */
	private static final double THROUGH_MAX_FRONT_WATER = 48;
	/** How far behind the front surface a ray looks. */
	private static final double THROUGH_MAX_BEHIND = 32;
	/** Leaves are only looked through when near (a distant forest is just green). */
	private static final double THROUGH_MAX_FRONT_LEAVES = 48;
	/**
	 * Leaf blocks a ray crosses (the front one included) before giving up: you see between the
	 * leaves of one or two layers, not through a whole canopy.
	 */
	private static final int THROUGH_MAX_LEAVES = 3;
	/** How much water a ray crosses before giving up (murky). */
	private static final double THROUGH_MAX_WATER = 10;
	/** Unknown positions a ray queues per round (the first one and a few after it, guessed). */
	private static final int THROUGH_LOOKAHEAD = 6;
	private static final int THROUGH_MAX_ROUNDS = 6;
	/** New positions per round; past this rays are dropped (keeps the main-thread work bounded). */
	private static final int THROUGH_MAX_ROUND_POSITIONS = 200_000;

	/** Classification state, kept across see-through rounds (worker only, handed over via the queue). */
	private static final class Pass {
		final Palette palette = new Palette();
		final char[] pixels;
		int sky;
		/** Block position -> pixels showing it as a block (front or seen through). */
		final Long2IntOpenHashMap posPixels = new Long2IntOpenHashMap();
		final int[][] tiles;
		final int tilesX;
		// See-through pixels (set once the front pass is done).
		int[] through;
		/** Ray distance where marching resumes; NaN once the ray is done. */
		float[] t;
		float[] water;
		/** Leaf blocks crossed so far. */
		byte[] leaves;
		/** Info index of the block found behind, or -1. */
		int[] behindInfo;
		long[] behindPos;
		float[] behindT;
		int rounds;

		Pass(int w, int h, int[][] tiles, int tilesX) {
			this.pixels = new char[w * h];
			this.tiles = tiles;
			this.tilesX = tilesX;
		}

		void setThrough(IntArrayList list) {
			through = list.toIntArray();
			int n = through.length;
			t = new float[n];
			water = new float[n];
			leaves = new byte[n];
			behindInfo = new int[n];
			behindPos = new long[n];
			behindT = new float[n];
			java.util.Arrays.fill(behindInfo, -1);
		}
	}

	private volatile Pass pass;

	/** Worker: the main thread looked up the blocks we asked for. */
	private void afterResolve() {
		if (pass == null) {
			classify();
		} else {
			march();
		}
	}

	private void classify() {
		try {
			float[] d = depth;
			BlockTable table = blocks;
			if (d == null || table == null) {
				return;
			}
			long start = System.nanoTime();
			pass = front(d, table);
			SmartShots.LOGGER.debug("Mask: front classified in {} ms, {} see-through pixels",
				(System.nanoTime() - start) / 1_000_000, pass.through.length);
			march();
		} catch (Throwable t) {
			SmartShots.LOGGER.warn("Mask pass 2 failed", t);
			cancel();
		}
	}

	/** First pass: what each pixel's depth lands on; collects the pixels looking at see-through surfaces. */
	private Pass front(float[] depth, BlockTable table) {
		int w = view.width();
		int h = view.height();
		double cx = view.cam().x;
		double cy = view.cam().y;
		double cz = view.cam().z;
		List<EntitySnapshot.Box> boxes = entities.boxes;
		int tilesX = (w >> TILE_SHIFT) + 1;
		int[][] tiles = tiles(boxes, tilesX, (h >> TILE_SHIFT) + 1);
		IntArrayList through = new IntArrayList();
		Pass pass = new Pass(w, h, tiles, tilesX);
		Palette palette = pass.palette;
		char[] pixels = pass.pixels;
		Long2IntOpenHashMap posPixels = pass.posPixels;
		double[] p = new double[3];
		int sky = 0;
		long lastSignKey = Long.MIN_VALUE;
		int lastSignIndex = 0;
		long lastPos = Long.MIN_VALUE;
		int lastRun = 0;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				float d = depth[i];
				if (d <= FrameView.FAR) {
					sky++;
					continue;
				}
				if (!view.unproject(x, y, d, p)) {
					continue;
				}
				double dist = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
				if (dist > MAX_DIST || dist < 1e-6) {
					continue;
				}
				double eps = eps(dist);
				double s = eps / dist;
				double tol = eps * 0.25;

				// Blocks: P pushed into the surface first, then P, then P pulled towards the camera.
				BlockTable.StateInfo hit = null;
				long hitKey = 0;
				boolean hitFluid = false;
				BlockTable.StateInfo any = null;
				long anyKey = 0;
				for (int c = 0; c < 3; c++) {
					double t = 1 + (1 - c) * s;
					double qx = cx + p[0] * t;
					double qy = cy + p[1] * t;
					double qz = cz + p[2] * t;
					int bx = Mth.floor(qx);
					int by = Mth.floor(qy);
					int bz = Mth.floor(qz);
					long key = BlockPos.asLong(bx, by, bz);
					int idx = table.stateAt.get(key);
					if (idx < 0) {
						continue;
					}
					BlockTable.StateInfo info = table.infos.get(idx);
					if (any == null) {
						any = info;
						anyKey = key;
					}
					double lx = qx - bx;
					double ly = qy - by;
					double lz = qz - bz;
					double[] shape = info.offset ? table.shapeAt.get(key) : info.boxes;
					if (inside(shape, lx, ly, lz, tol)) {
						hit = info;
						hitKey = key;
						break;
					}
					if (info.fluidKey != null && ly <= table.fluidAt.get(key) + tol) {
						hit = info;
						hitKey = key;
						hitFluid = true;
						break;
					}
				}

				// Entities: strict hitbox first, then the generous box as a second chance.
				EntitySnapshot.Box strict = null;
				EntitySnapshot.Box generous = null;
				int[] list = tiles[(y >> TILE_SHIFT) * tilesX + (x >> TILE_SHIFT)];
				if (list != null) {
					for (int b : list) {
						EntitySnapshot.Box box = boxes.get(b);
						if (inside(box.strict, p[0], p[1], p[2])) {
							if (strict == null || box.volume < strict.volume) {
								strict = box;
							}
						} else if (strict == null && inside(box.generous, p[0], p[1], p[2])
							&& (generous == null || box.volume < generous.volume)) {
							generous = box;
						}
					}
				}

				int index;
				EntitySnapshot.Box entity = null;
				if (strict != null && !(hit != null && !hitFluid && hit.solid)) {
					entity = strict; // unless it's the solid floor under its feet
				} else if (any == null && generous != null) {
					// Only where no block is at all: plants' crossed quads stick out of their
					// shape, and the generous box would grab that grass (a dropped arrow outlined
					// together with the grass around it).
					entity = generous;
				}
				if (entity != null) {
					if (entity.palette < 0) {
						entity.palette = palette.of(entity.keys);
					}
					entity.pixels++;
					index = entity.palette;
				} else {
					BlockTable.StateInfo info = hit != null ? hit : any;
					long key = hit != null ? hitKey : anyKey;
					if (info == null) {
						continue;
					}
					boolean fluid = hit != null ? hitFluid : info.fluidKey != null && !info.offset && info.boxes.length == 0;
					if (fluid) {
						if (info.fluidPalette < 0) {
							info.fluidPalette = palette.of(info.fluidKey);
						}
						index = info.fluidPalette;
					} else {
						if (info.sign && table.signAt.containsKey(key)) {
							if (key != lastSignKey) {
								lastSignKey = key;
								lastSignIndex = palette.of(ShotMask.SIGN + table.signAt.get(key), info.blockKey);
							}
							index = lastSignIndex;
						} else {
							if (info.blockPalette < 0) {
								info.blockPalette = palette.of(info.blockKey);
							}
							index = info.blockPalette;
						}
						// Neighbouring pixels mostly show the same block: count runs, hash once.
						if (key == lastPos) {
							lastRun++;
						} else {
							if (lastRun > 0) {
								posPixels.addTo(lastPos, lastRun);
							}
							lastPos = key;
							lastRun = 1;
						}
					}
					if (hit != null && (hitFluid ? hit.water : hit.seeThrough)
						&& dist <= (hit.water ? THROUGH_MAX_FRONT_WATER : hit.leaves ? THROUGH_MAX_FRONT_LEAVES : THROUGH_MAX_FRONT)) {
						through.add(i);
					}
				}
				pixels[i] = (char) index;
			}
		}
		if (lastRun > 0) {
			posPixels.addTo(lastPos, lastRun);
		}

		pass.sky = sky;
		pass.setThrough(through);
		for (int k = 0; k < pass.through.length; k++) {
			int i = pass.through[k];
			if (view.unproject(i % w, i / w, depth[i], p)) {
				double dist = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
				pass.t[k] = (float) (dist + eps(dist));
			} else {
				pass.t[k] = Float.NaN;
			}
		}
		return pass;
	}

	/**
	 * Worker, one round: every see-through ray walks on (voxel by voxel) until it hits something
	 * solid behind, gives up, or reaches a position the block table doesn't know yet. Those are
	 * queued for the main thread and the round ends; the next round resumes where each ray stopped.
	 */
	private void march() {
		try {
			Pass ps = pass;
			float[] d = depth;
			BlockTable table = blocks;
			if (ps == null || d == null || table == null) {
				return;
			}
			long start = System.nanoTime();
			LongOpenHashSet want = new LongOpenHashSet();
			int w = view.width();
			double[] p = new double[3];
			boolean lastRound = ps.rounds >= THROUGH_MAX_ROUNDS;
			for (int k = 0; k < ps.through.length; k++) {
				if (Float.isNaN(ps.t[k])) {
					continue;
				}
				int i = ps.through[k];
				if (!view.unproject(i % w, i / w, d[i], p)) {
					ps.t[k] = Float.NaN;
					continue;
				}
				double dist = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
				marchRay(ps, table, k, p[0] / dist, p[1] / dist, p[2] / dist, dist + THROUGH_MAX_BEHIND,
					lastRound || want.size() >= THROUGH_MAX_ROUND_POSITIONS ? null : want);
			}
			ps.rounds++;
			SmartShots.LOGGER.debug("Mask: see-through round {} in {} ms, {} new positions",
				ps.rounds, (System.nanoTime() - start) / 1_000_000, want.size());
			if (!want.isEmpty()) {
				table.add(want.toLongArray());
				RESOLVING.add(this);
				return;
			}
			Result result = assemble(ps, table);
			blockNames = table.names;
			depth = null;
			blocks = null;
			pass = null;
			SmartShots.LOGGER.debug("Mask: done, {} palette entries", result.mask.palette.length);
			mask.complete(result);
		} catch (Throwable t) {
			SmartShots.LOGGER.warn("Mask see-through pass failed", t);
			cancel();
		}
	}

	/** One ray (unit direction d, camera-relative) from {@code ps.t[k]}; {@code want == null}: no more lookups allowed. */
	private void marchRay(Pass ps, BlockTable table, int k, double dx, double dy, double dz, double tMax, LongOpenHashSet want) {
		double cx = view.cam().x;
		double cy = view.cam().y;
		double cz = view.cam().z;
		double t = ps.t[k];
		double px = cx + dx * t;
		double py = cy + dy * t;
		double pz = cz + dz * t;
		int vx = Mth.floor(px);
		int vy = Mth.floor(py);
		int vz = Mth.floor(pz);
		int sx = dx > 0 ? 1 : -1;
		int sy = dy > 0 ? 1 : -1;
		int sz = dz > 0 ? 1 : -1;
		double ddx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
		double ddy = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
		double ddz = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
		double nx = t + (dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? vx + 1 - px : px - vx) * ddx);
		double ny = t + (dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? vy + 1 - py : py - vy) * ddy);
		double nz = t + (dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? vz + 1 - pz : pz - vz) * ddz);
		double water = ps.water[k];
		int leaves = ps.leaves[k];
		long lastLeaf = Long.MIN_VALUE;
		int queued = 0;
		double stallT = Double.NaN;
		for (int step = 0; step < 96 && t <= tMax; step++) {
			double exit = Math.min(nx, Math.min(ny, nz));
			long key = BlockPos.asLong(vx, vy, vz);
			if (!table.resolved.contains(key)) {
				if (want == null) {
					break; // out of lookups: give up on this ray
				}
				want.add(key);
				if (queued++ == 0) {
					stallT = t + 1e-4; // just inside this voxel, so the next round starts in it
				}
				if (queued >= THROUGH_LOOKAHEAD) {
					break;
				}
			} else if (queued == 0) {
				int idx = table.stateAt.get(key);
				if (idx >= 0) {
					BlockTable.StateInfo info = table.infos.get(idx);
					if (!info.invisible && !info.seeThrough) {
						double[] shape = info.offset ? table.shapeAt.get(key) : info.boxes;
						double hitT = segmentHit(shape, cx - vx, cy - vy, cz - vz, dx, dy, dz, t, exit);
						if (!Double.isNaN(hitT)) {
							ps.behindInfo[k] = idx;
							ps.behindPos[k] = key;
							ps.behindT[k] = (float) hitT;
							ps.t[k] = Float.NaN;
							return;
						}
						if (info.fluidKey != null && !info.water) {
							// Lava and other opaque fluids: the ray ends at its surface.
							ps.behindInfo[k] = idx;
							ps.behindPos[k] = Long.MIN_VALUE; // fluid: not counted as a block
							ps.behindT[k] = (float) t;
							ps.t[k] = Float.NaN;
							return;
						}
					}
					if (info.leaves && key != lastLeaf) {
						lastLeaf = key;
						if (++leaves > THROUGH_MAX_LEAVES) {
							break;
						}
					}
					if (info.water) {
						water += exit - t;
						if (water > THROUGH_MAX_WATER) {
							break;
						}
					}
				}
			}
			// Next voxel.
			t = exit;
			if (nx <= ny && nx <= nz) {
				vx += sx;
				nx += ddx;
			} else if (ny <= nz) {
				vy += sy;
				ny += ddy;
			} else {
				vz += sz;
				nz += ddz;
			}
		}
		if (queued > 0) {
			ps.t[k] = (float) stallT;
			ps.water[k] = (float) water;
			ps.leaves[k] = (byte) Math.min(leaves, 127);
		} else {
			ps.t[k] = Float.NaN; // nothing behind within range (sky, too far, too much water)
			ps.behindT[k] = (float) Math.min(t, tMax);
		}
	}

	/**
	 * First ray distance in {@code [t0, t1]} at which the ray (origin o relative to the voxel,
	 * unit direction d) is inside one of the block-local boxes; NaN when it misses them all.
	 */
	private static double segmentHit(double[] boxes, double ox, double oy, double oz, double dx, double dy, double dz, double t0, double t1) {
		if (boxes == null) {
			return Double.NaN;
		}
		double best = Double.NaN;
		for (int b = 0; b + 5 < boxes.length; b += 6) {
			double lo = t0;
			double hi = t1;
			double[] o = {ox, oy, oz};
			double[] dir = {dx, dy, dz};
			for (int a = 0; a < 3 && lo <= hi; a++) {
				double min = boxes[b + a] - 1e-4;
				double max = boxes[b + 3 + a] + 1e-4;
				if (Math.abs(dir[a]) < 1e-12) {
					if (o[a] < min || o[a] > max) {
						lo = Double.POSITIVE_INFINITY;
					}
				} else {
					double ta = (min - o[a]) / dir[a];
					double tb = (max - o[a]) / dir[a];
					lo = Math.max(lo, Math.min(ta, tb));
					hi = Math.min(hi, Math.max(ta, tb));
				}
			}
			if (lo <= hi && (Double.isNaN(best) || lo < best)) {
				best = lo;
			}
		}
		return best;
	}

	/** Entry distance of the ray (origin = camera, unit d) into a camera-relative box, or NaN. */
	private static double rayBox(double[] box, double dx, double dy, double dz, double t0, double t1) {
		double lo = t0;
		double hi = t1;
		double[] dir = {dx, dy, dz};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(dir[a]) < 1e-12) {
				if (box[a] > 0 || box[3 + a] < 0) {
					return Double.NaN;
				}
				continue;
			}
			double ta = box[a] / dir[a];
			double tb = box[3 + a] / dir[a];
			lo = Math.max(lo, Math.min(ta, tb));
			hi = Math.min(hi, Math.max(ta, tb));
			if (lo > hi) {
				return Double.NaN;
			}
		}
		return lo;
	}

	/** Worker, last step: palette entries "front, then what's behind", block counts, totals. */
	private Result assemble(Pass ps, BlockTable table) {
		int w = view.width();
		int h = view.height();
		float[] d = depth;
		double[] p = new double[3];
		List<EntitySnapshot.Box> boxes = entities.boxes;
		Palette palette = ps.palette;
		Long2IntOpenHashMap combos = new Long2IntOpenHashMap();
		combos.defaultReturnValue(-1);
		for (int k = 0; k < ps.through.length; k++) {
			int i = ps.through[k];
			int front = ps.pixels[i];
			if (front == 0 || !view.unproject(i % w, i / w, d[i], p)) {
				continue;
			}
			double dist = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
			double dx = p[0] / dist;
			double dy = p[1] / dist;
			double dz = p[2] / dist;
			double end = ps.behindInfo[k] >= 0 ? ps.behindT[k] : Math.max(ps.behindT[k], dist);
			if (end <= dist) {
				end = dist + THROUGH_MAX_BEHIND;
			}
			// An entity behind the glass, in front of the block found there?
			int entity = -1;
			double entityT = end;
			int[] list = ps.tiles[((i / w) >> TILE_SHIFT) * ps.tilesX + ((i % w) >> TILE_SHIFT)];
			if (list != null) {
				for (int b : list) {
					double et = rayBox(boxes.get(b).strict, dx, dy, dz, dist, entityT);
					if (!Double.isNaN(et) && et < entityT) {
						entityT = et;
						entity = b;
					}
				}
			}
			long combo;
			String[] behind;
			if (entity >= 0) {
				EntitySnapshot.Box box = boxes.get(entity);
				box.pixels++;
				combo = ((long) front << 32) | (0x80000000L | entity);
				behind = box.keys;
			} else if (ps.behindInfo[k] >= 0) {
				BlockTable.StateInfo info = table.infos.get(ps.behindInfo[k]);
				boolean fluid = ps.behindPos[k] == Long.MIN_VALUE;
				combo = ((long) front << 32) | (fluid ? 0x40000000L : 0) | ps.behindInfo[k];
				behind = new String[]{fluid ? info.fluidKey : info.blockKey};
				if (!fluid) {
					ps.posPixels.addTo(ps.behindPos[k], 1);
				}
			} else {
				continue;
			}
			int index = combos.get(combo);
			if (index < 0) {
				String[] frontKeys = palette.entries.get(front);
				List<String> keys = new ArrayList<>(frontKeys.length + behind.length);
				Collections.addAll(keys, frontKeys);
				for (String b : behind) {
					if (!keys.contains(b)) {
						keys.add(b);
					}
				}
				index = keys.size() == frontKeys.length ? front : palette.of(keys.toArray(new String[0]));
				combos.put(combo, index);
			}
			ps.pixels[i] = (char) index;
		}

		String[][] entries = palette.entries.toArray(new String[0][]);
		int[] counts = new int[entries.length];
		for (char c : ps.pixels) {
			counts[c]++;
		}
		Map<String, Integer> keyPixels = new HashMap<>();
		for (int i = 1; i < entries.length; i++) {
			for (String k : entries[i]) {
				keyPixels.merge(k, counts[i], Integer::sum);
			}
		}
		int threshold = Math.max(20, w * h / 100_000);
		Map<String, Integer> instances = new HashMap<>();
		for (EntitySnapshot.Box box : boxes) {
			if (box.pixels >= threshold) {
				for (String k : box.keys) {
					instances.merge(k, 1, Integer::sum);
				}
			}
		}
		// A block position counts once it covers a few pixels (a sliver at the edge is noise).
		int minPositionPixels = Math.max(4, w * h / 500_000);
		Map<String, Integer> blockCounts = new HashMap<>();
		for (Long2IntMap.Entry e : ps.posPixels.long2IntEntrySet()) {
			if (e.getIntValue() < minPositionPixels) {
				continue;
			}
			int idx = table.stateAt.get(e.getLongKey());
			if (idx >= 0) {
				blockCounts.merge(table.infos.get(idx).blockKey, 1, Integer::sum);
			}
		}
		return new Result(new ShotMask(w, h, entries, ps.pixels), keyPixels, instances, blockCounts, threshold, ps.sky);
	}

	private static boolean inside(double[] b, double x, double y, double z) {
		return x >= b[0] && y >= b[1] && z >= b[2] && x <= b[3] && y <= b[4] && z <= b[5];
	}

	private static boolean inside(double[] boxes, double x, double y, double z, double tol) {
		if (boxes == null) {
			return false;
		}
		for (int i = 0; i + 5 < boxes.length; i += 6) {
			if (x >= boxes[i] - tol && y >= boxes[i + 1] - tol && z >= boxes[i + 2] - tol
				&& x <= boxes[i + 3] + tol && y <= boxes[i + 4] + tol && z <= boxes[i + 5] + tol) {
				return true;
			}
		}
		return false;
	}

	/** For each 16x16 pixel tile, the boxes whose (generous) screen rectangle overlaps it. */
	private int[][] tiles(List<EntitySnapshot.Box> boxes, int tilesX, int tilesY) {
		IntArrayList[] lists = new IntArrayList[tilesX * tilesY];
		int w = view.width();
		int h = view.height();
		for (int b = 0; b < boxes.size(); b++) {
			double[] g = boxes.get(b).generous;
			double minX = Double.MAX_VALUE;
			double minY = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			double maxY = -Double.MAX_VALUE;
			boolean behind = false;
			for (int c = 0; c < 8 && !behind; c++) {
				double[] s = view.project(g[(c & 1) == 0 ? 0 : 3], g[(c & 2) == 0 ? 1 : 4], g[(c & 4) == 0 ? 2 : 5]);
				if (s == null) {
					behind = true; // a corner is at or behind the camera: assume anywhere on screen
				} else {
					minX = Math.min(minX, s[0]);
					minY = Math.min(minY, s[1]);
					maxX = Math.max(maxX, s[0]);
					maxY = Math.max(maxY, s[1]);
				}
			}
			int x0 = 0;
			int y0 = 0;
			int x1 = w - 1;
			int y1 = h - 1;
			if (!behind) {
				if (maxX < 0 || maxY < 0 || minX >= w || minY >= h) {
					continue;
				}
				x0 = Math.max(0, (int) Math.floor(minX) - 2);
				y0 = Math.max(0, (int) Math.floor(minY) - 2);
				x1 = Math.min(w - 1, (int) Math.ceil(maxX) + 2);
				y1 = Math.min(h - 1, (int) Math.ceil(maxY) + 2);
			}
			for (int ty = y0 >> TILE_SHIFT; ty <= y1 >> TILE_SHIFT; ty++) {
				for (int tx = x0 >> TILE_SHIFT; tx <= x1 >> TILE_SHIFT; tx++) {
					int t = ty * tilesX + tx;
					if (lists[t] == null) {
						lists[t] = new IntArrayList(2);
					}
					lists[t].add(b);
				}
			}
		}
		int[][] out = new int[lists.length][];
		for (int i = 0; i < lists.length; i++) {
			if (lists[i] != null) {
				out[i] = lists[i].toIntArray();
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ finish (IO thread)

	private void finish(Result result, Path shot) {
		if (!Files.isRegularFile(shot)) {
			return; // deleted meanwhile
		}
		try {
			// The mask is built at frame size; it must match the saved PNG pixel for pixel.
			ShotMask m = savedWidth > 0 && savedHeight > 0 ? ImageOps.scale(result.mask, savedWidth, savedHeight) : result.mask;
			m.write(ShotMask.maskPath(shot));
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Couldn't write mask for {}", shot, e);
			return;
		}
		ScreenshotStore.updateMeta(shot, meta -> merge(meta, result),
			() -> Minecraft.getInstance().execute(() -> GalleryScreen.onMetaUpdated(shot, meta -> merge(meta, result))));
	}

	/**
	 * Adds what the mask found to the metadata. Entries are never removed; block weights are
	 * replaced by the mask's exact ones and block counts are set. Idempotent (it runs on the stored
	 * metadata and again on the gallery's in-memory copy).
	 */
	private void merge(ShotMeta m, Result r) {
		int total = view.width() * view.height();
		Map<String, Integer> maskBlocks = new HashMap<>();
		int players = 0;
		if (m.blockCounts == null) {
			m.blockCounts = new LinkedHashMap<>();
		}
		for (Map.Entry<String, Integer> e : r.keyPixels.entrySet()) {
			int px = e.getValue();
			if (px < r.threshold) {
				continue;
			}
			String key = e.getKey();
			String value = ShotMask.value(key);
			int count = Math.max(1, r.instances.getOrDefault(key, 0));
			switch (ShotMask.kind(key)) {
				case ShotMask.PLAYER -> {
					players++;
					if (m.players.stream().noneMatch(value::equalsIgnoreCase)) {
						m.players.add(value);
					}
					String uuid = entities.playerIds.get(value);
					if (uuid != null) {
						m.playerIds.putIfAbsent(value, uuid);
					}
					String styled = entities.playerNames.get(value);
					if (styled != null) {
						if (m.playerNames == null) {
							m.playerNames = new LinkedHashMap<>();
						}
						m.playerNames.putIfAbsent(value, styled);
					}
				}
				case ShotMask.ENTITY -> {
					m.entities.putIfAbsent(value, count);
					name(m, value, entities.names.get(value));
				}
				case ShotMask.NAMED -> {
					if (m.named == null) {
						m.named = new ArrayList<>();
					}
					if (!m.named.contains(value)) {
						m.named.add(value);
					}
					String tag = "name:" + ShotMask.namedName(value);
					if (!m.tags.contains(tag)) {
						m.tags.add(tag);
					}
				}
				case ShotMask.ITEM -> {
					m.items.putIfAbsent(value, count);
					name(m, "item|" + value, entities.names.get("item|" + value));
				}
				case ShotMask.BLOCK -> {
					maskBlocks.put(value, Math.max(1, (int) ((long) px * 1000 / total)));
					Integer blockCount = r.blockCounts.get(key);
					if (blockCount != null && blockCount > 0) {
						m.blockCounts.put(value, blockCount);
					}
					name(m, value, blockName(value));
				}
				case ShotMask.SIGN -> {
					if (!m.signs.contains(value)) {
						m.signs.add(value);
					}
				}
				default -> {
				}
			}
		}
		if (players > 0) {
			m.entities.putIfAbsent("minecraft:player", players);
			name(m, "minecraft:player", entities.names.get("minecraft:player"));
		}
		if (!maskBlocks.isEmpty()) {
			// The mask's weights are exact; the coarse grid's guesses only stay for blocks it missed.
			Map<String, Integer> merged = new HashMap<>(m.blocks);
			merged.putAll(maskBlocks);
			m.blocks = new LinkedHashMap<>();
			merged.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
					.thenComparing(en -> maskBlocks.containsKey(en.getKey()) ? 0 : 1))
				.limit(MAX_BLOCKS)
				.forEach(en -> m.blocks.put(en.getKey(), en.getValue()));
			m.blockCounts.keySet().retainAll(m.blocks.keySet());
		}
		if (skyLight && total > 0 && r.sky * 100L / total >= 25 && !m.tags.contains("sky")) {
			m.tags.add("sky");
		}
	}

	private String blockName(String id) {
		return blockNames.get(id);
	}

	private static void name(ShotMeta m, String key, String name) {
		if (name != null) {
			m.names.putIfAbsent(key, name);
		}
	}
}
