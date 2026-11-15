package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.search.Names;
import dev.vadyanik.smartshots.storage.Library;
import dev.vadyanik.smartshots.storage.Outlines;
import dev.vadyanik.smartshots.storage.ShotBaker;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMask;
import dev.vadyanik.smartshots.storage.ShotMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Silhouette outlines of the things on a shot (see {@link ShotMask}).
 *
 * <p>The viewer shows the pristine image with outline overlays on top (one small texture per
 * key, cropped to the key's bounding box), so hovering and toggling are instant. Persistent
 * highlights are also baked into the PNG itself on the IO thread, using the very same
 * {@link Outlines#render} so both look identical.
 *
 * <p>All state except the worker internals is touched on the render thread only.
 */
final class Highlights {
	/** Overlays kept per shot; old non-visible ones are released first. */
	private static final int MAX_OVERLAYS = 12;
	/** A queued overlay nobody asked for during this long is dropped (the mouse moved on). */
	private static final long STALE_MS = 400;
	/** Masks are written a few seconds after the shot; keep looking for that long. */
	private static final long MASK_WAIT_MS = 60_000;
	private static final long MASK_RETRY_MS = 2_000;

	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "SmartShots Outlines-" + THREAD_ID.incrementAndGet());
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});

	static final class Overlay {
		final String key;
		final boolean hover;
		final Textures.Tex tex = new Textures.Tex();
		/** Region in mask/image pixels. */
		volatile int x;
		volatile int y;
		volatile int w;
		volatile int h;
		volatile boolean queued;
		/** Computed and found nothing to draw. */
		volatile boolean empty;
		volatile long wanted;

		Overlay(String key, boolean hover) {
			this.key = key;
			this.hover = hover;
		}

		boolean ready() {
			return tex.ready();
		}
	}

	// current shot
	private static Path shot;
	private static ShotMask mask;
	private static Set<String> maskKeys = Set.of();
	private static boolean maskLoading;
	private static boolean maskMissing;
	private static long maskNextTry;
	private static int maskGen;
	private static final LinkedHashMap<String, Overlay> OVERLAYS = new LinkedHashMap<>(16, 0.75f, true);

	private Highlights() {
	}

	// ------------------------------------------------------------------ mask

	private static void switchTo(Path file) {
		if (file.equals(shot)) {
			return;
		}
		releaseAll();
		shot = file;
	}

	/** A mask was just written for {@code file}: if it's the shot on screen without one, look now. */
	static void maskWritten(Path file) {
		if (file.equals(shot) && mask == null) {
			maskMissing = false;
			maskNextTry = 0;
		}
	}

	/** The mask of the shot, or null while loading / when there is none. Call every frame. */
	static ShotMask mask(Library.Shot s) {
		switchTo(s.file);
		long now = System.currentTimeMillis();
		if (mask == null && !maskLoading && now >= maskNextTry && (!maskMissing || now - s.meta.time < MASK_WAIT_MS)) {
			maskLoading = true;
			int gen = maskGen;
			Path file = s.file;
			POOL.execute(() -> {
				ShotMask m = ShotMask.readFor(file);
				Set<String> keys = new HashSet<>();
				if (m != null) {
					for (String[] entry : m.palette) {
						Collections.addAll(keys, entry);
					}
				}
				Minecraft.getInstance().execute(() -> {
					if (gen != maskGen) {
						return;
					}
					maskLoading = false;
					mask = m;
					maskKeys = keys;
					maskMissing = m == null;
					maskNextTry = System.currentTimeMillis() + MASK_RETRY_MS;
				});
			});
		}
		return mask;
	}

	/** True once we know the shot has no mask and won't get one (old shot, analysis off). */
	static boolean unavailable(Library.Shot s) {
		return s.file.equals(shot) && mask == null && maskMissing
			&& System.currentTimeMillis() - s.meta.time >= MASK_WAIT_MS;
	}

	/** True once the mask of the current shot is loaded. */
	static boolean loaded() {
		return mask != null;
	}

	/** Whether the loaded mask of the current shot has pixels for {@code key}. */
	static boolean has(String key) {
		return mask != null && key != null && maskKeys.contains(key);
	}

	// ------------------------------------------------------------------ overlays

	/** The overlay of a key on the current shot (computed in the background), or null without a mask. */
	static Overlay overlay(String key, boolean hover) {
		if (mask == null || !maskKeys.contains(key)) {
			return null;
		}
		String id = (hover ? "h|" : "o|") + key;
		Overlay o = OVERLAYS.get(id);
		if (o == null) {
			o = new Overlay(key, hover);
			OVERLAYS.put(id, o);
			trim();
		}
		o.wanted = System.currentTimeMillis();
		if (!o.queued && !o.ready() && !o.empty && !o.tex.failed()) {
			o.queued = true;
			Overlay job = o;
			ShotMask m = mask;
			String label = shot.getFileName() + " " + key;
			POOL.execute(() -> compute(job, m, label));
		}
		return o;
	}

	private static void compute(Overlay o, ShotMask m, String label) {
		if (o.tex.released) {
			return;
		}
		if (System.currentTimeMillis() - o.wanted > STALE_MS) {
			o.queued = false;
			return;
		}
		try {
			Outlines.Region r = Outlines.render(m, o.key, o.hover);
			if (r == null) {
				o.empty = true;
				return;
			}
			NativeImage img = new NativeImage(r.w(), r.h(), false);
			int[] abgr = new int[r.argb().length];
			for (int i = 0; i < abgr.length; i++) {
				abgr[i] = toAbgr(r.argb()[i]);
			}
			MemoryUtil.memIntBuffer(img.getPointer(), abgr.length).put(abgr);
			o.x = r.x();
			o.y = r.y();
			o.w = r.w();
			o.h = r.h();
			Textures.upload(img, o.tex, "outline " + label);
		} catch (Throwable e) {
			o.tex.failed = true;
			SmartShots.LOGGER.debug("Couldn't build outline {}", o.key, e);
		}
	}

	private static void trim() {
		if (OVERLAYS.size() <= MAX_OVERLAYS) {
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Overlay> it = OVERLAYS.values().iterator();
		while (OVERLAYS.size() > MAX_OVERLAYS && it.hasNext()) {
			Overlay o = it.next();
			if (now - o.wanted > 1000) {
				it.remove();
				o.tex.release();
			}
		}
	}

	/** Draws an overlay over the image drawn at (ix, iy, iw, ih). */
	static void draw(GuiGraphicsExtractor g, Overlay o, int ix, int iy, int iw, int ih) {
		if (o == null || mask == null) {
			return;
		}
		DynamicTexture t = o.tex.texture;
		if (t == null) {
			return;
		}
		// Draw in image pixel space so the crop lines up exactly at any zoom.
		g.pose().pushMatrix();
		g.pose().translate(ix, iy);
		g.pose().scale(iw / (float) mask.width, ih / (float) mask.height);
		g.blit(t.getTextureView(), Textures.sampler(), o.x, o.y, o.x + o.w, o.y + o.h, 0, 1, 0, 1);
		g.pose().popMatrix();
	}

	/** Forgets the loaded mask and overlays when {@code file} is the current shot (it was rewritten). */
	static void fileReplaced(Path file) {
		if (file.equals(shot)) {
			releaseAll();
		}
	}

	static void releaseAll() {
		for (Overlay o : OVERLAYS.values()) {
			o.tex.release();
		}
		OVERLAYS.clear();
		shot = null;
		mask = null;
		maskKeys = Set.of();
		maskLoading = false;
		maskMissing = false;
		maskNextTry = 0;
		maskGen++;
	}

	// ------------------------------------------------------------------ toggling & baking

	/** Turns the persistent highlight of {@code key} on or off and re-bakes the PNG. */
	static void toggle(Library.Shot s, String key) {
		ShotMask m = mask;
		if (m == null || !s.file.equals(shot)) {
			return;
		}
		ShotMeta meta = s.meta;
		// Always a fresh list: the IO thread may be serializing the previous one.
		List<String> list = new ArrayList<>(meta.highlights == null ? List.of() : meta.highlights);
		if (!list.remove(key)) {
			list.add(key);
		}
		meta.highlights = list;
		ScreenshotStore.updateMeta(s.file, meta);

		Path file = s.file;
		ShotBaker.schedule(file, m, list, meta.edits, () -> {
			Textures.forgetThumb(file);
			GalleryScreen.markDirty();
		});
	}

	private static int toAbgr(int argb) {
		return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
	}

	// ------------------------------------------------------------------ keys

	/** Outline color by kind: the same colors as the info panel sections. */
	static int color(String key) {
		return Outlines.color(key);
	}

	static final int SIGN_COLOR = Outlines.SIGN_COLOR;

	/** Localized display name of a key. */
	static String label(String key, ShotMeta meta) {
		String v = ShotMask.value(key);
		return switch (ShotMask.kind(key)) {
			case ShotMask.PLAYER -> v;
			case ShotMask.ENTITY -> Names.entity(v, meta.names);
			case ShotMask.NAMED -> ShotMask.namedName(key);
			case ShotMask.ITEM -> Names.item(v, meta.names);
			case ShotMask.BLOCK -> Names.block(v, meta.names);
			case ShotMask.SIGN -> "“" + v + "”";
			default -> v;
		};
	}

	/** Gallery search query that finds shots with the same thing. */
	static String query(String key) {
		String v = ShotMask.value(key);
		return switch (ShotMask.kind(key)) {
			case ShotMask.PLAYER -> "player:" + quote(v);
			case ShotMask.ENTITY -> "mob:" + shortId(v);
			case ShotMask.NAMED -> "tag:" + quote("name:" + ShotMask.namedName(key));
			case ShotMask.ITEM -> "item:" + shortId(v);
			case ShotMask.BLOCK -> "block:" + shortId(v);
			case ShotMask.SIGN -> "sign:" + quote(v);
			default -> v;
		};
	}

	static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	static String quote(String s) {
		return s.contains(" ") ? "\"" + s + "\"" : s;
	}
}
