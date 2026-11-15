package dev.vadyanik.smartshots.storage;

import dev.vadyanik.smartshots.gui.ui.Theme;

import java.util.List;

/**
 * Highlight outlines of {@link ShotMask} keys, as plain pixel arrays (no GL): the viewer uploads
 * them as overlay textures, and baking composes them into the PNG (on the IO thread when a
 * highlight is toggled, on the archive thread when an archived shot is re-baked). Both use the
 * same {@link #render} so they look identical.
 */
public final class Outlines {
	private Outlines() {
	}

	/**
	 * Draws the outlines of {@code keys} into {@code abgr} (NativeImage layout, {@code m.width}
	 * wide). The mask must have the image's size.
	 */
	public static void bake(int[] abgr, ShotMask m, List<String> keys) {
		if (abgr.length != m.width * m.height) {
			throw new IllegalArgumentException("mask " + m.width + "x" + m.height + " doesn't match the image");
		}
		int w = m.width;
		for (String key : keys) {
			Region r = render(m, key, false);
			if (r == null) {
				continue;
			}
			for (int y = 0; y < r.h; y++) {
				int src = y * r.w;
				int dst = (r.y + y) * w + r.x;
				for (int x = 0; x < r.w; x++) {
					abgr[dst + x] = over(abgr[dst + x], r.argb[src + x]);
				}
			}
		}
	}

	/** Source-over of a non-premultiplied ARGB color onto an opaque-ish ABGR pixel. */
	private static int over(int dstAbgr, int srcArgb) {
		int sa = srcArgb >>> 24;
		if (sa == 0) {
			return dstAbgr;
		}
		int inv = 255 - sa;
		int sr = (srcArgb >> 16) & 0xFF;
		int sg = (srcArgb >> 8) & 0xFF;
		int sb = srcArgb & 0xFF;
		int dr = dstAbgr & 0xFF;
		int dg = (dstAbgr >> 8) & 0xFF;
		int db = (dstAbgr >> 16) & 0xFF;
		int da = dstAbgr >>> 24;
		int r = (sr * sa + dr * inv + 127) / 255;
		int gg = (sg * sa + dg * inv + 127) / 255;
		int b = (sb * sa + db * inv + 127) / 255;
		int a = Math.min(255, sa + (da * inv + 127) / 255);
		return (a << 24) | (b << 16) | (gg << 8) | r;
	}

	/** A cropped ARGB (non-premultiplied) overlay, top row first. */
	public record Region(int x, int y, int w, int h, int[] argb) {
	}

	/** Outline width in image pixels: thin on small shots, proportional on big ones. */
	public static float strokeWidth(int imageHeight) {
		return Math.max(2f, imageHeight / 300f);
	}

	/**
	 * Anti-aliased outer stroke around the silhouette of {@code key}, plus a thin dark edge outside
	 * the stroke for contrast on bright scenes. Uses an exact Euclidean distance transform
	 * (Felzenszwalb &amp; Huttenlocher) over the key's bounding box plus a margin, so the stroke
	 * follows the shape with round corners. {@code hover} adds a faint tint over the shape itself.
	 */
	public static Region render(ShotMask m, String key, boolean hover) {
		int[] b = m.bounds(key);
		if (b == null) {
			return null;
		}
		boolean[] sel = m.selector(key);
		float stroke = strokeWidth(m.height);
		float edge = Math.max(1f, stroke * 0.4f);
		int margin = (int) Math.ceil(stroke + edge) + 2;
		int x0 = Math.max(0, b[0] - margin);
		int y0 = Math.max(0, b[1] - margin);
		int x1 = Math.min(m.width - 1, b[2] + margin);
		int y1 = Math.min(m.height - 1, b[3] + margin);
		int w = x1 - x0 + 1;
		int h = y1 - y0 + 1;

		boolean[] inside = new boolean[w * h];
		for (int y = 0; y < h; y++) {
			int row = (y0 + y) * m.width + x0;
			for (int x = 0; x < w; x++) {
				inside[y * w + x] = sel[m.pixels[row + x]];
			}
		}
		float[] dist2 = distanceTransform(inside, w, h);

		int color = color(key);
		int rgb = color & 0xFFFFFF;
		int tint = hover ? (Math.round(255 * 0.16f) << 24) | rgb : rgb;
		float darkAlpha = 0.55f;
		int[] out = new int[w * h];
		for (int i = 0; i < out.length; i++) {
			if (inside[i]) {
				// Transparent pixels keep the stroke color so linear filtering doesn't darken the inner edge.
				out[i] = tint;
				continue;
			}
			float d2 = dist2[i];
			if (d2 == Float.POSITIVE_INFINITY) {
				continue;
			}
			// Distance from the silhouette edge (which lies half a pixel past the nearest inside pixel center).
			float e = (float) Math.sqrt(d2) - 0.5f;
			float sa = clamp01(stroke - e + 0.5f);
			float da = clamp01(stroke + edge - e + 0.5f) * darkAlpha;
			float a = sa + da * (1 - sa);
			if (a <= 0.002f) {
				continue;
			}
			// Stroke over black edge: color channels scale with the stroke's share of the coverage.
			float k = sa / a;
			int r = Math.round(((rgb >> 16) & 0xFF) * k);
			int g = Math.round(((rgb >> 8) & 0xFF) * k);
			int bl = Math.round((rgb & 0xFF) * k);
			out[i] = (Math.round(a * 255) << 24) | (r << 16) | (g << 8) | bl;
		}
		return new Region(x0, y0, w, h, out);
	}

	private static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	/** Squared Euclidean distance from every pixel to the nearest {@code inside} pixel (0 inside). */
	static float[] distanceTransform(boolean[] inside, int w, int h) {
		float[] grid = new float[w * h];
		int n = Math.max(w, h);
		float[] f = new float[n];
		float[] d = new float[n];
		int[] v = new int[n];
		float[] z = new float[n + 1];
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				f[y] = inside[y * w + x] ? 0 : Float.POSITIVE_INFINITY;
			}
			transform1d(f, h, d, v, z);
			for (int y = 0; y < h; y++) {
				grid[y * w + x] = d[y];
			}
		}
		for (int y = 0; y < h; y++) {
			System.arraycopy(grid, y * w, f, 0, w);
			transform1d(f, w, d, v, z);
			System.arraycopy(d, 0, grid, y * w, w);
		}
		return grid;
	}

	/** 1D squared distance transform: lower envelope of parabolas rooted at the finite samples. */
	private static void transform1d(float[] f, int n, float[] d, int[] v, float[] z) {
		int k = -1;
		for (int q = 0; q < n; q++) {
			float fq = f[q];
			if (fq == Float.POSITIVE_INFINITY) {
				continue;
			}
			if (k < 0) {
				k = 0;
				v[0] = q;
				z[0] = Float.NEGATIVE_INFINITY;
				z[1] = Float.POSITIVE_INFINITY;
				continue;
			}
			float s;
			while (true) {
				int p = v[k];
				s = ((fq + (float) q * q) - (f[p] + (float) p * p)) / (2f * (q - p));
				if (s <= z[k]) {
					k--;
				} else {
					break;
				}
			}
			k++;
			v[k] = q;
			z[k] = s;
			z[k + 1] = Float.POSITIVE_INFINITY;
		}
		if (k < 0) {
			java.util.Arrays.fill(d, 0, n, Float.POSITIVE_INFINITY);
			return;
		}
		int j = 0;
		for (int q = 0; q < n; q++) {
			while (z[j + 1] < q) {
				j++;
			}
			float dq = q - v[j];
			d[q] = dq * dq + f[v[j]];
		}
	}

	/** Outline color by kind: the same colors as the info panel sections. */
	public static int color(String key) {
		return switch (ShotMask.kind(key)) {
			case ShotMask.PLAYER -> Theme.CYAN;
			case ShotMask.ENTITY, ShotMask.NAMED -> Theme.CORAL;
			case ShotMask.ITEM -> Theme.GOLD;
			case ShotMask.BLOCK -> Theme.LIME;
			case ShotMask.SIGN -> SIGN_COLOR;
			default -> Theme.ACCENT;
		};
	}

	public static final int SIGN_COLOR = 0xFFE0B070;
}
