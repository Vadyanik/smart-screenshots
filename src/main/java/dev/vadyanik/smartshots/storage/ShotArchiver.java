package dev.vadyanik.smartshots.storage;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.GalleryScreen;
import dev.vadyanik.smartshots.gui.Textures;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Shrinks old shots to save disk space: downscale to {@code archiveMaxHeight} and re-encode with
 * {@link PngWriter} at its best level. The file keeps its name and stays a PNG, so metadata,
 * search and highlights keep working; the mask is scaled along, and shots with baked highlights
 * get their pristine original scaled and the outlines re-baked at the new size.
 *
 * <p>The heavy work (decode, scale, encode into temp files) runs on a low-priority thread of its
 * own; only the final swap runs on {@link ScreenshotStore#IO}, after checking that nothing touched
 * the shot meanwhile (a highlight toggle, a favorite, deletion) — otherwise the shot is left for
 * the next run. Every file is replaced by an atomic move, and the {@code archived} flag is written
 * last, so a crash at any point leaves a valid shot that is simply processed again.
 */
public final class ShotArchiver {
	private static final long FIRST_RUN_MINUTES = 2;
	private static final long PERIOD_MINUTES = 30;
	/** Shots per background run, with a pause between them so the disk and CPU stay quiet. */
	private static final int BATCH = 20;
	private static final long PAUSE_MS = 1500;
	private static final String TEMP = ".archiving";

	private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "SmartShots Archive");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static final AtomicBoolean RUNNING = new AtomicBoolean();
	/** The pending "Archive now" request, so repeated clicks don't queue more runs. */
	private static volatile CompletableFuture<Result> manual;

	public record Result(int shots, long freedBytes) {
	}

	private record Candidate(Path file, long time) {
	}

	private ShotArchiver() {
	}

	/** Schedules the periodic background runs (they do nothing while archiving is off). */
	public static void start() {
		EXEC.scheduleWithFixedDelay(() -> {
			if (SmartShotsConfig.get().archiveEnabled) {
				run(BATCH, PAUSE_MS);
			}
		}, FIRST_RUN_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES);
	}

	/** True while a run (background or manual) is in progress. */
	public static boolean running() {
		return RUNNING.get();
	}

	/**
	 * Archives every eligible shot now (current settings, even when automatic archiving is off).
	 * While a request is pending, returns that one. Main thread.
	 */
	public static CompletableFuture<Result> runNow() {
		CompletableFuture<Result> f = manual;
		if (f == null || f.isDone()) {
			f = CompletableFuture.supplyAsync(() -> run(Integer.MAX_VALUE, 0), EXEC);
			manual = f;
		}
		return f;
	}

	/** The last "Archive now" request (pending or finished), or null. */
	public static CompletableFuture<Result> lastManual() {
		return manual;
	}

	private static Result run(int limit, long pauseMs) {
		RUNNING.set(true);
		int done = 0;
		long freed = 0;
		try {
			SmartShotsConfig cfg = SmartShotsConfig.get();
			long cutoff = System.currentTimeMillis() - cfg.archiveAfterDays * 86_400_000L;
			List<Candidate> list = candidates(ScreenshotStore.root(), cutoff, cfg.archiveAutoOnly);
			for (int i = 0; i < list.size() && done < limit; i++) {
				if (i > 0 && pauseMs > 0) {
					Thread.sleep(pauseMs);
				}
				Path file = list.get(i).file;
				try {
					long f = archive(file, list.get(i).time, cfg.archiveMaxHeight);
					if (f != Long.MIN_VALUE) {
						done++;
						freed += f;
						Minecraft.getInstance().execute(() -> Textures.onFileReplaced(file));
					}
				} catch (Exception e) {
					SmartShots.LOGGER.warn("Couldn't archive {}", file, e);
				}
			}
			if (done > 0) {
				SmartShots.LOGGER.info("Archived {} screenshots, freed {} MB", done, String.format(Locale.ROOT, "%.1f", freed / 1e6));
				Minecraft.getInstance().execute(GalleryScreen::onScreenshotAdded);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Archiving failed", e);
		} finally {
			RUNNING.set(false);
		}
		return new Result(done, freed);
	}

	// ------------------------------------------------------------------ selection

	/** Eligible shots, oldest first. Hidden folders (data, cache) are skipped. */
	private static List<Candidate> candidates(Path root, long cutoff, boolean autoOnly) throws IOException {
		List<Candidate> out = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return out;
		}
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(root) && dir.getFileName().toString().startsWith(".")) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				collect(dir, cutoff, autoOnly, out);
				return FileVisitResult.CONTINUE;
			}
		});
		out.sort(Comparator.comparingLong(Candidate::time));
		return out;
	}

	private static void collect(Path dir, long cutoff, boolean autoOnly, List<Candidate> out) {
		Map<String, ShotMeta> metas = Json.readShots(dir);
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") && Files.isRegularFile(p))
				.forEach(p -> {
					ShotMeta m = metas.get(p.getFileName().toString());
					long time;
					if (m != null) {
						// Edited shots are curated by hand: leave them (and their edit coordinates) alone.
						if (m.favorite || m.archived || EditOp.any(m.edits) || autoOnly && m.trigger() != Trigger.AUTO) {
							return;
						}
						time = m.time;
					} else {
						// A shot we know nothing about (vanilla F2, copied in): only when archiving everything.
						if (autoOnly) {
							return;
						}
						try {
							time = Files.getLastModifiedTime(p).toMillis();
						} catch (IOException e) {
							return;
						}
					}
					if (time < cutoff) {
						out.add(new Candidate(p, time));
					}
				});
		} catch (IOException e) {
			SmartShots.LOGGER.debug("Couldn't list {}", dir, e);
		}
	}

	// ------------------------------------------------------------------ one shot

	/** mtime + size of a file, or {@code missing} when it doesn't exist. */
	private record Stamp(boolean exists, long mtime, long size) {
		static Stamp of(Path p) {
			try {
				BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
				return new Stamp(true, a.lastModifiedTime().toMillis(), a.size());
			} catch (IOException e) {
				return new Stamp(false, 0, 0);
			}
		}

		long bytes() {
			return exists ? size : 0;
		}
	}

	/** Archives one shot. Returns the bytes freed (may be negative), or {@link Long#MIN_VALUE} when skipped. */
	private static long archive(Path file, long time, int maxHeight) throws Exception {
		Path dir = file.getParent();
		String name = file.getFileName().toString();
		Path orig = ShotMask.originalPath(file);
		Path maskFile = ShotMask.maskPath(file);
		Path shotTmp = dir.resolve(name + TEMP);
		Path origTmp = ShotMask.dataDir(file).resolve(name + TEMP);
		Path maskTmp = ShotMask.dataDir(file).resolve(name + ".mask" + TEMP);

		Stamp shot0 = Stamp.of(file);
		Stamp orig0 = Stamp.of(orig);
		Stamp mask0 = Stamp.of(maskFile);
		ShotMeta meta0 = Json.readShots(dir).get(name);
		List<String> highlights = meta0 == null || meta0.highlights == null ? List.of() : List.copyOf(meta0.highlights);

		try {
			// Pristine pixels, scaled.
			int[] size;
			int[] base;
			int srcW;
			int srcH;
			try (NativeImage img = read(orig0.exists ? orig : file)) {
				srcW = img.getWidth();
				srcH = img.getHeight();
				size = ImageOps.fit(srcW, srcH, maxHeight);
				base = ImageOps.downscale(img.getPixelsABGR(), srcW, srcH, size[0], size[1]);
			}
			boolean resized = size[0] != srcW || size[1] != srcH;

			ShotMask mask = mask0.exists ? ShotMask.readFor(file) : null;
			ShotMask scaledMask = mask != null ? ImageOps.scale(mask, size[0], size[1]) : null;
			if (scaledMask != null && scaledMask != mask) {
				scaledMask.write(maskTmp);
			}

			int[] shotPx = base;
			if (orig0.exists) {
				writePng(origTmp, base, size);
				if (scaledMask != null && !highlights.isEmpty()) {
					shotPx = base.clone();
					Outlines.bake(shotPx, scaledMask, highlights);
				} else {
					// Can't re-bake without a mask: scale the baked file itself.
					try (NativeImage img = read(file)) {
						shotPx = ImageOps.downscale(img.getPixelsABGR(), img.getWidth(), img.getHeight(), size[0], size[1]);
					}
				}
			}
			writePng(shotTmp, shotPx, size);

			Path oldThumb = ScreenshotStore.thumbCacheFile(file);
			return ScreenshotStore.IO.submit(() -> commit(file, time, size, resized, highlights,
				shot0, orig0, mask0, shotTmp, origTmp, maskTmp, oldThumb)).get();
		} finally {
			Files.deleteIfExists(shotTmp);
			Files.deleteIfExists(origTmp);
			Files.deleteIfExists(maskTmp);
		}
	}

	/** IO thread: swaps the new files in if the shot is unchanged since we read it. */
	private static long commit(Path file, long time, int[] size, boolean resized, List<String> highlights,
							   Stamp shot0, Stamp orig0, Stamp mask0, Path shotTmp, Path origTmp, Path maskTmp, Path oldThumb) throws IOException {
		Path dir = file.getParent();
		String name = file.getFileName().toString();
		Path orig = ShotMask.originalPath(file);
		Path maskFile = ShotMask.maskPath(file);
		Map<String, ShotMeta> shots = Json.readShots(dir);
		ShotMeta meta = shots.get(name);
		List<String> nowHighlights = meta == null || meta.highlights == null ? List.of() : meta.highlights;
		if (!Stamp.of(file).equals(shot0) || !Stamp.of(orig).equals(orig0) || !Stamp.of(maskFile).equals(mask0)
			|| meta != null && (meta.favorite || meta.archived || EditOp.any(meta.edits)) || !Objects.equals(nowHighlights, highlights)) {
			SmartShots.LOGGER.debug("{} changed while archiving; retrying later", file);
			return Long.MIN_VALUE;
		}

		// Same size: only take the re-encode when it is actually smaller.
		if (resized || Files.size(shotTmp) < shot0.size) {
			move(shotTmp, file);
		}
		if (orig0.exists && Files.isRegularFile(origTmp) && (resized || Files.size(origTmp) < orig0.size)) {
			move(origTmp, orig);
		}
		if (Files.isRegularFile(maskTmp)) {
			move(maskTmp, maskFile);
		}

		if (meta == null) {
			meta = new ShotMeta();
			meta.trigger = Trigger.UNKNOWN.id;
			meta.time = time;
			shots.put(name, meta);
		}
		meta.archived = true;
		meta.width = size[0];
		meta.height = size[1];
		Json.write(dir.resolve(ScreenshotStore.SHOTS_FILE), shots);

		// The thumbnail cache is keyed by mtime + size: the old entry would never be used again.
		if (!Stamp.of(file).equals(shot0)) {
			Files.deleteIfExists(oldThumb);
		}
		return shot0.bytes() + orig0.bytes() + mask0.bytes()
			- Stamp.of(file).bytes() - Stamp.of(orig).bytes() - Stamp.of(maskFile).bytes();
	}

	private static NativeImage read(Path p) throws IOException {
		try (InputStream in = Files.newInputStream(p)) {
			return NativeImage.read(in);
		}
	}

	private static void writePng(Path tmp, int[] px, int[] size) throws IOException {
		Files.createDirectories(tmp.getParent());
		try (OutputStream out = Files.newOutputStream(tmp)) {
			PngWriter.write(out, px, size[0], size[1], PngWriter.LEVEL_ARCHIVE);
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
