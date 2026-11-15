package dev.vadyanik.smartshots.storage;

import com.google.common.hash.Hashing;
import com.mojang.blaze3d.platform.NativeImage;
import dev.vadyanik.smartshots.SmartShots;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * All disk access for screenshots and their metadata goes through one single-threaded executor,
 * so metadata files are never written concurrently.
 */
public final class ScreenshotStore {
	public static final String SHOTS_FILE = ".smartshots.json";
	public static final String WORLD_FILE = ".smartshots-world.json";
	public static final String CACHE_DIR = ".smartshots-cache";
	private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SmartShots IO");
		t.setDaemon(true);
		return t;
	});

	private static final Map<Path, InstanceMeta> INSTANCE_CACHE = new HashMap<>();

	private ScreenshotStore() {
	}

	public static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
	}

	public static Path cacheDir() {
		return root().resolve(CACHE_DIR);
	}

	/** Disk cache file of a shot's gallery thumbnail; keyed by path, mtime and size, so it goes stale when the file changes. */
	public static Path thumbCacheFile(Path file) {
		long mtime;
		long size;
		try {
			mtime = Files.getLastModifiedTime(file).toMillis();
			size = Files.size(file);
		} catch (Exception e) {
			mtime = 0;
			size = 0;
		}
		String key = Hashing.sha1().hashString(file.toAbsolutePath() + "|" + mtime + "|" + size, StandardCharsets.UTF_8).toString();
		return cacheDir().resolve("thumbs").resolve(key.substring(0, 2)).resolve(key + ".png");
	}

	// ---------------------------------------------------------------- instance meta

	public static synchronized InstanceMeta instanceMeta(Path instanceDir) {
		return INSTANCE_CACHE.computeIfAbsent(instanceDir, dir -> {
			InstanceMeta meta = Json.read(dir.resolve(WORLD_FILE), InstanceMeta.class);
			return meta != null ? meta : new InstanceMeta();
		});
	}

	public static synchronized void saveInstanceMeta(Path instanceDir) {
		InstanceMeta meta = INSTANCE_CACHE.get(instanceDir);
		if (meta == null) {
			return;
		}
		String json = Json.GSON.toJson(meta);
		InstanceMeta copy = Json.GSON.fromJson(json, InstanceMeta.class);
		IO.execute(() -> Json.write(instanceDir.resolve(WORLD_FILE), copy));
	}

	public static synchronized void invalidateInstanceCache() {
		INSTANCE_CACHE.clear();
	}

	// ---------------------------------------------------------------- writing

	/** {@code preview} is a small copy of the image for notifications; the receiver must close it. */
	public record Saved(Path file, ShotMeta meta, Location location, NativeImage preview) {
	}

	/**
	 * Writes the image and its metadata on the IO thread. Takes ownership of {@code image}.
	 * Images taller than {@code maxHeight} (0 = no limit) are box-downscaled first; the metadata
	 * gets the size of the saved file (the mask job scales its mask to that size).
	 */
	public static void save(Location location, NativeImage image, ShotMeta meta, int maxHeight, Consumer<Saved> onSaved, Consumer<Exception> onError) {
		IO.execute(() -> {
			try (image) {
				Path dir = location.locationDir();
				Files.createDirectories(dir);
				Path file = uniqueFile(dir, meta);
				int[] size = ImageOps.fit(image.getWidth(), image.getHeight(), maxHeight);
				int[] px = image.getPixelsABGR();
				if (size[0] != image.getWidth() || size[1] != image.getHeight()) {
					px = ImageOps.downscale(px, image.getWidth(), image.getHeight(), size[0], size[1]);
				}
				meta.width = size[0];
				meta.height = size[1];
				PngWriter.writeAtomic(file, px, size[0], size[1], PngWriter.LEVEL);
				int pw = Math.min(960, image.getWidth());
				int ph = Math.max(1, Math.round(image.getHeight() * (pw / (float) image.getWidth())));
				NativeImage preview = new NativeImage(image.format(), pw, ph, false);
				image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), preview);

				Map<String, ShotMeta> shots = Json.readShots(dir);
				shots.put(file.getFileName().toString(), meta);
				Json.write(dir.resolve(SHOTS_FILE), shots);
				onSaved.accept(new Saved(file, meta, location, preview));
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't save screenshot", e);
				onError.accept(e);
			}
		});
	}

	private static Path uniqueFile(Path dir, ShotMeta meta) {
		String base = LocalDateTime.now().format(NAME_FORMAT);
		String suffix = switch (meta.trigger()) {
			case AUTO -> "_auto";
			case ADVANCEMENT -> "_advancement";
			case NEW_PLAYER -> "_player";
			default -> "";
		};
		int n = 1;
		while (true) {
			Path p = dir.resolve(base + suffix + (n == 1 ? "" : "_" + n) + ".png");
			if (!Files.exists(p)) {
				return p;
			}
			n++;
		}
	}

	// ---------------------------------------------------------------- editing

	/** Update metadata of an existing shot (favorite, note...). */
	public static void updateMeta(Path file, ShotMeta meta) {
		// Snapshot the user-editable fields now; the caller may keep mutating its object.
		boolean favorite = meta.favorite;
		String note = meta.note;
		java.util.List<String> highlights = new java.util.ArrayList<>(meta.highlights == null ? java.util.List.of() : meta.highlights);
		java.util.List<EditOp> edits = new java.util.ArrayList<>(meta.edits == null ? java.util.List.of() : meta.edits);
		IO.execute(() -> {
			Path dir = file.getParent();
			Map<String, ShotMeta> shots = Json.readShots(dir);
			ShotMeta stored = shots.get(file.getFileName().toString());
			if (stored != null) {
				// Only copy what the user edits, so a stale in-memory copy can't wipe data the
				// background mask merge has added meanwhile (items, blocks...).
				stored.favorite = favorite;
				stored.note = note;
				stored.highlights = highlights;
				stored.edits = edits;
			} else {
				shots.put(file.getFileName().toString(), meta);
			}
			Json.write(dir.resolve(SHOTS_FILE), shots);
		});
	}

	/**
	 * Changes the stored metadata of a shot in place: {@code change} gets the version currently on
	 * disk (so concurrent edits such as a favorite toggle aren't lost). Does nothing when the shot
	 * has no metadata entry any more. {@code after} runs on the IO thread.
	 */
	public static void updateMeta(Path file, Consumer<ShotMeta> change, Runnable after) {
		IO.execute(() -> {
			try {
				Path dir = file.getParent();
				Map<String, ShotMeta> shots = Json.readShots(dir);
				ShotMeta meta = shots.get(file.getFileName().toString());
				if (meta == null) {
					return;
				}
				change.accept(meta);
				Json.write(dir.resolve(SHOTS_FILE), shots);
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't update metadata of {}", file, e);
				return;
			}
			after.run();
		});
	}

	public static void delete(Path file, Runnable after) {
		IO.execute(() -> {
			try {
				Files.deleteIfExists(file);
				// Sidecars: per-pixel mask and the untouched original (see ShotMask).
				Files.deleteIfExists(ShotMask.maskPath(file));
				Files.deleteIfExists(ShotMask.originalPath(file));
				Path dir = file.getParent();
				Map<String, ShotMeta> shots = Json.readShots(dir);
				if (shots.remove(file.getFileName().toString()) != null) {
					Json.write(dir.resolve(SHOTS_FILE), shots);
				}
			} catch (IOException e) {
				SmartShots.LOGGER.warn("Couldn't delete {}", file, e);
			}
			try {
				Files.deleteIfExists(ShotMask.dataDir(file)); // only succeeds when empty
			} catch (IOException ignored) {
			}
			Minecraft.getInstance().execute(after);
		});
	}
}
