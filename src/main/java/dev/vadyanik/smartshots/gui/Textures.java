package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.storage.EditOp;
import dev.vadyanik.smartshots.storage.EditRenderer;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async texture loading for the gallery. Thumbnails are cached on disk (small PNGs in
 * screenshots/.smartshots-cache) so reopening the gallery is instant.
 */
public final class Textures {
	public static final int THUMB_WIDTH = 384;
	private static final int MAX_THUMBS = 300;
	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "SmartShots Thumbnails-" + THREAD_ID.incrementAndGet());
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	public static final class Tex {
		volatile DynamicTexture texture;
		volatile boolean failed;
		volatile boolean released;
		public int width;
		public int height;
		final long requested = System.currentTimeMillis();
		/** For a full-size image: the edit list drawn into it. */
		public List<EditOp> ops = List.of();
		/** A full-size image (uncropped, without outlines), not a thumbnail. */
		public boolean fullSize;
		long ready;

		public boolean ready() {
			return texture != null;
		}

		public boolean failed() {
			return failed;
		}

		/** 0..1 fade-in after load. */
		public float fade() {
			if (ready == 0) {
				return 0;
			}
			return Math.min(1f, (System.currentTimeMillis() - ready) / 180f);
		}

		void release() {
			released = true;
			if (texture != null) {
				texture.close();
				texture = null;
			}
		}
	}

	private static final Map<Path, Tex> THUMBS = new LinkedHashMap<>(64, 0.75f, true);
	private static Tex full;
	private static Tex fullPrev;
	private static Path fullPath;

	private Textures() {
	}

	static GpuSampler sampler() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	// ------------------------------------------------------------ thumbnails

	public static Tex thumb(Path file) {
		Tex t = THUMBS.get(file);
		if (t != null) {
			return t;
		}
		Tex created = new Tex();
		THUMBS.put(file, created);
		trim();
		POOL.execute(() -> loadThumb(file, created));
		return created;
	}

	private static void trim() {
		if (THUMBS.size() <= MAX_THUMBS) {
			return;
		}
		Iterator<Map.Entry<Path, Tex>> it = THUMBS.entrySet().iterator();
		while (THUMBS.size() > MAX_THUMBS && it.hasNext()) {
			Tex t = it.next().getValue();
			it.remove();
			t.release();
		}
	}

	private static void loadThumb(Path file, Tex tex) {
		if (tex.released) {
			return;
		}
		NativeImage img = null;
		try {
			Path cache = cacheFile(file);
			if (Files.isRegularFile(cache)) {
				try (InputStream in = Files.newInputStream(cache)) {
					img = NativeImage.read(in);
				} catch (Exception e) {
					img = null;
				}
			}
			if (img == null) {
				NativeImage src;
				try (InputStream in = Files.newInputStream(file)) {
					src = NativeImage.read(in);
				}
				try (src) {
					int w = Math.min(THUMB_WIDTH, src.getWidth());
					int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
					img = new NativeImage(src.format(), w, h, false);
					src.resizeSubRectTo(0, 0, src.getWidth(), src.getHeight(), img);
				}
				try {
					Files.createDirectories(cache.getParent());
					img.writeToFile(cache);
				} catch (Exception e) {
					SmartShots.LOGGER.debug("Couldn't write thumbnail cache", e);
				}
			}
			upload(img, tex, "thumb " + file.getFileName());
		} catch (Throwable e) {
			if (img != null) {
				img.close();
			}
			tex.failed = true;
			SmartShots.LOGGER.debug("Couldn't load thumbnail {}", file, e);
		}
	}

	/** Uploads an image on the render thread; takes ownership of {@code img}. Release with {@link Tex#release()}. */
	static Tex upload(NativeImage img, String label) {
		Tex tex = new Tex();
		upload(img, tex, label);
		return tex;
	}

	static void upload(NativeImage img, Tex tex, String label) {
		Minecraft.getInstance().execute(() -> {
			if (tex.released) {
				img.close();
				return;
			}
			try {
				tex.width = img.getWidth();
				tex.height = img.getHeight();
				tex.texture = new DynamicTexture(() -> "smartshots " + label, img);
				tex.ready = System.currentTimeMillis();
			} catch (Throwable e) {
				img.close();
				tex.failed = true;
			}
		});
	}

	private static Path cacheFile(Path file) {
		return ScreenshotStore.thumbCacheFile(file);
	}

	/** Drops only the thumbnail (e.g. after outlines were baked into the file); the viewer image stays. */
	public static void forgetThumb(Path file) {
		Tex t = THUMBS.remove(file);
		if (t != null) {
			t.release();
		}
	}

	/**
	 * The file was rewritten in place (archived): drop its thumbnail, its full-size texture and
	 * the viewer's loaded mask/outlines so everything reloads at the new size.
	 */
	public static void onFileReplaced(Path file) {
		forget(file);
		Highlights.fileReplaced(file);
	}

	public static void forget(Path file) {
		Tex t = THUMBS.remove(file);
		if (t != null) {
			t.release();
		}
		if (file.equals(fullPath) && full != null) {
			full.release();
			full = null;
			fullPath = null;
		}
		if (fullPrev != null && fullPath == null) {
			fullPrev.release();
			fullPrev = null;
		}
		lastPristine = null;
	}

	// ------------------------------------------------------------ full size

	/**
	 * The full-size image of a shot <i>without</i> baked outlines and crop: the pristine original
	 * ({@link ShotMask#pristine}) with {@code edits} drawn on it. The viewer draws highlight outlines
	 * on top as separate overlay textures and applies the crop itself. When the edits change (a new
	 * list instance), the previous image stays on screen until the new one is ready.
	 */
	public static Tex full(Path file, List<EditOp> edits) {
		List<EditOp> ops = edits == null ? List.of() : edits;
		if (!(file.equals(fullPath) && full != null && full.ops == ops)) {
			boolean sameFile = file.equals(fullPath) && full != null;
			if (sameFile && full.ready()) {
				if (fullPrev != null) {
					fullPrev.release();
				}
				fullPrev = full;
			} else {
				if (full != null) {
					full.release();
				}
				if (!sameFile && fullPrev != null) {
					fullPrev.release();
					fullPrev = null;
				}
			}
			Tex created = new Tex();
			created.ops = ops;
			created.fullSize = true;
			full = created;
			fullPath = file;
			List<EditOp> snapshot = List.copyOf(ops);
			POOL.execute(() -> loadFull(file, snapshot, created));
		}
		if (full.ready() || full.failed()) {
			if (fullPrev != null) {
				fullPrev.release();
				fullPrev = null;
			}
			return full;
		}
		return fullPrev != null ? fullPrev : full;
	}

	/** Shots without edits. */
	public static Tex full(Path file) {
		return full(file, null);
	}

	private static void loadFull(Path file, List<EditOp> ops, Tex tex) {
		if (tex.released) {
			return;
		}
		NativeImage img = null;
		try {
			Pristine p = pristine(file);
			if (tex.released) {
				return;
			}
			img = new NativeImage(p.w, p.h, false);
			int[] px = p.px.clone();
			EditRenderer.apply(px, p.w, p.h, ops);
			MemoryUtil.memIntBuffer(img.getPointer(), px.length).put(px);
			upload(img, tex, "full " + file.getFileName());
		} catch (Throwable e) {
			if (img != null) {
				img.close();
			}
			tex.failed = true;
			SmartShots.LOGGER.debug("Couldn't load {}", file, e);
		}
	}

	/** Decoded pristine pixels of the last shot, so redrawing edits doesn't decode the PNG again. */
	private record Pristine(Path file, long mtime, long size, int[] px, int w, int h) {
	}

	private static volatile Pristine lastPristine;

	private static Pristine pristine(Path file) throws Exception {
		Path src = ShotMask.pristine(file);
		long mtime = Files.getLastModifiedTime(src).toMillis();
		long size = Files.size(src);
		Pristine p = lastPristine;
		if (p != null && p.file.equals(src) && p.mtime == mtime && p.size == size) {
			return p;
		}
		NativeImage img;
		try (InputStream in = Files.newInputStream(src)) {
			img = NativeImage.read(in);
		}
		try (img) {
			p = new Pristine(src, mtime, size, img.getPixelsABGR(), img.getWidth(), img.getHeight());
		}
		lastPristine = p;
		return p;
	}

	public static void releaseAll() {
		Highlights.releaseAll();
		List<Tex> all = new ArrayList<>(THUMBS.values());
		THUMBS.clear();
		all.forEach(Tex::release);
		if (full != null) {
			full.release();
			full = null;
			fullPath = null;
		}
		if (fullPrev != null) {
			fullPrev.release();
			fullPrev = null;
		}
		lastPristine = null;
	}

	// ------------------------------------------------------------ drawing

	/** Draw scaled to fill the box, cropping the overflow (like CSS object-fit: cover). */
	public static void drawCover(GuiGraphicsExtractor g, Tex tex, int x, int y, int w, int h) {
		DynamicTexture t = tex.texture;
		if (t == null) {
			return;
		}
		float boxAspect = w / (float) h;
		float imgAspect = tex.width / (float) tex.height;
		float u0 = 0;
		float u1 = 1;
		float v0 = 0;
		float v1 = 1;
		if (imgAspect > boxAspect) {
			float visible = boxAspect / imgAspect;
			u0 = (1 - visible) / 2;
			u1 = u0 + visible;
		} else {
			float visible = imgAspect / boxAspect;
			v0 = (1 - visible) / 2;
			v1 = v0 + visible;
		}
		g.blit(t.getTextureView(), sampler(), x, y, x + w, y + h, u0, u1, v0, v1);
	}

	public static void drawStretched(GuiGraphicsExtractor g, Tex tex, int x0, int y0, int x1, int y1) {
		DynamicTexture t = tex.texture;
		if (t != null) {
			g.blit(t.getTextureView(), sampler(), x0, y0, x1, y1, 0, 1, 0, 1);
		}
	}
}
