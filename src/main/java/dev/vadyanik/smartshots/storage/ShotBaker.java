package dev.vadyanik.smartshots.storage;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vadyanik.smartshots.SmartShots;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rebuilds a shot's PNG from its untouched original ({@link ShotMask#originalPath}): edits
 * ({@link ShotMeta#edits}) first, then highlight outlines, then the crop. When there is nothing
 * left to apply, the original is moved back and the sidecar removed. Runs on
 * {@link ScreenshotStore#IO}; when several bakes of a shot are queued, only the last one writes.
 */
public final class ShotBaker {
	private static final Map<Path, AtomicLong> GEN = new ConcurrentHashMap<>();

	private ShotBaker() {
	}

	/**
	 * Queues a bake. {@code mask} may be null: it is then read from disk when outlines are needed.
	 * {@code afterOnMain} runs on the render thread once the file is written (or skipped).
	 */
	public static void schedule(Path file, ShotMask mask, List<String> highlights, List<EditOp> edits, Runnable afterOnMain) {
		List<String> keys = highlights == null ? List.of() : List.copyOf(highlights);
		List<EditOp> ops = edits == null ? List.of() : List.copyOf(edits);
		long gen = GEN.computeIfAbsent(file, f -> new AtomicLong()).incrementAndGet();
		ScreenshotStore.IO.execute(() -> {
			if (GEN.get(file).get() != gen) {
				return; // a newer bake is queued behind us
			}
			try {
				bake(file, mask, keys, ops);
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't rebuild {}", file, e);
			}
			if (afterOnMain != null) {
				Minecraft.getInstance().execute(afterOnMain);
			}
		});
	}

	/** IO thread. */
	public static void bake(Path file, ShotMask mask, List<String> keys, List<EditOp> ops) throws IOException {
		Path orig = ShotMask.originalPath(file);
		if (keys.isEmpty() && ops.isEmpty()) {
			if (Files.isRegularFile(orig)) {
				copyAtomic(orig, file, ShotMask.dataDir(file).resolve(file.getFileName() + ".restore"));
				Files.deleteIfExists(orig);
			}
			return;
		}
		if (!Files.isRegularFile(orig)) {
			Files.createDirectories(orig.getParent());
			copyAtomic(file, orig, orig.resolveSibling(orig.getFileName() + ".tmp"));
		}
		NativeImage img;
		try (InputStream in = Files.newInputStream(orig)) {
			img = NativeImage.read(in);
		}
		int w;
		int h;
		int[] px;
		try (img) {
			w = img.getWidth();
			h = img.getHeight();
			px = img.getPixelsABGR();
		}
		EditRenderer.Image out = compose(px, w, h, file, mask, keys, ops);
		Path tmp = ShotMask.dataDir(file).resolve(file.getFileName() + ".baking");
		try (OutputStream os = Files.newOutputStream(tmp)) {
			PngWriter.write(os, out.px(), out.w(), out.h(), PngWriter.LEVEL);
		}
		move(tmp, file);
	}

	/** Pristine pixels -> final image (modifies {@code px}). */
	public static EditRenderer.Image compose(int[] px, int w, int h, Path file, ShotMask mask, List<String> keys, List<EditOp> ops) {
		EditRenderer.apply(px, w, h, ops);
		if (!keys.isEmpty()) {
			ShotMask m = mask != null ? mask : ShotMask.readFor(file);
			if (m != null && m.width == w && m.height == h) {
				Outlines.bake(px, m, keys);
			} else if (m != null) {
				SmartShots.LOGGER.warn("Mask size {}x{} doesn't match {} ({}x{})", m.width, m.height, file, w, h);
			}
		}
		return EditRenderer.crop(px, w, h, EditOp.crop(ops));
	}

	private static void copyAtomic(Path from, Path to, Path tmp) throws IOException {
		Files.createDirectories(tmp.getParent());
		Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
		move(tmp, to);
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
