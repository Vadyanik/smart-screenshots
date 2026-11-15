package dev.vadyanik.smartshots.storage;

/**
 * Pure image helpers on plain arrays (no GL, no NativeImage), for background threads. Pixels are
 * ABGR ints as in NativeImage (R in the low byte), row-major, top row first.
 */
public final class ImageOps {
	/** Choices for the "max height" settings; 0 = keep the original size. */
	public static final int[] HEIGHTS = {0, 2160, 1440, 1080, 720, 480};

	private ImageOps() {
	}

	/** {width, height} capped to {@code maxHeight} (0 = no cap), aspect kept, never upscaled. */
	public static int[] fit(int width, int height, int maxHeight) {
		if (maxHeight <= 0 || height <= maxHeight) {
			return new int[]{width, height};
		}
		int w = Math.max(1, (int) Math.round(width * (double) maxHeight / height));
		return new int[]{w, maxHeight};
	}

	/**
	 * Area-averaging (box filter) downscale: every destination pixel is the coverage-weighted mean
	 * of the source pixels under it, so fine detail is averaged instead of aliased. Separable:
	 * each source row is resampled horizontally once and accumulated into the destination rows it
	 * overlaps. Only meant for shrinking.
	 */
	public static int[] downscale(int[] src, int sw, int sh, int dw, int dh) {
		if (dw == sw && dh == sh) {
			return src.clone();
		}
		if (dw > sw || dh > sh) {
			throw new IllegalArgumentException("downscale only: " + sw + "x" + sh + " -> " + dw + "x" + dh);
		}
		Axis ax = new Axis(sw, dw);
		Axis ay = new Axis(sh, dh);
		int[] out = new int[dw * dh];
		float[] hrow = new float[dw * 4];
		float[] acc = new float[dw * 4];
		int cachedRow = -1;
		for (int dy = 0; dy < dh; dy++) {
			java.util.Arrays.fill(acc, 0);
			for (int k = ay.start[dy], e = ay.start[dy] + ay.count[dy]; k < e; k++) {
				float wy = ay.weight[dy][k - ay.start[dy]];
				if (wy <= 0) {
					continue;
				}
				if (k != cachedRow) {
					horizontal(src, k * sw, ax, hrow);
					cachedRow = k;
				}
				for (int i = 0; i < hrow.length; i++) {
					acc[i] += hrow[i] * wy;
				}
			}
			int row = dy * dw;
			for (int x = 0; x < dw; x++) {
				int o = x * 4;
				out[row + x] = channel(acc[o]) | channel(acc[o + 1]) << 8 | channel(acc[o + 2]) << 16 | channel(acc[o + 3]) << 24;
			}
		}
		return out;
	}

	private static void horizontal(int[] src, int rowStart, Axis ax, float[] out) {
		for (int x = 0; x < ax.start.length; x++) {
			float r = 0;
			float g = 0;
			float b = 0;
			float a = 0;
			float[] w = ax.weight[x];
			int s = rowStart + ax.start[x];
			for (int k = 0; k < w.length; k++) {
				int p = src[s + k];
				float wk = w[k];
				r += (p & 0xFF) * wk;
				g += ((p >> 8) & 0xFF) * wk;
				b += ((p >> 16) & 0xFF) * wk;
				a += (p >>> 24) * wk;
			}
			int o = x * 4;
			out[o] = r;
			out[o + 1] = g;
			out[o + 2] = b;
			out[o + 3] = a;
		}
	}

	private static int channel(float v) {
		int i = Math.round(v);
		return i < 0 ? 0 : Math.min(i, 255);
	}

	/** Per destination index: the first source index it covers and the normalized coverage weights. */
	private static final class Axis {
		final int[] start;
		final int[] count;
		final float[][] weight;

		Axis(int src, int dst) {
			start = new int[dst];
			count = new int[dst];
			weight = new float[dst][];
			double scale = src / (double) dst;
			for (int i = 0; i < dst; i++) {
				double a = i * scale;
				double b = Math.min(src, (i + 1) * scale);
				int s = (int) Math.floor(a);
				int e = Math.min(src, (int) Math.ceil(b));
				float[] w = new float[Math.max(1, e - s)];
				for (int j = s; j < e; j++) {
					w[j - s] = (float) ((Math.min(j + 1, b) - Math.max(j, a)) / scale);
				}
				start[i] = s;
				count[i] = w.length;
				weight[i] = w;
			}
		}
	}

	/**
	 * Downscales a per-pixel label map (see {@link ShotMask}): each destination pixel takes the
	 * most frequent label of the source block under it, preferring a real label over "nothing" on
	 * ties so thin things don't vanish. Palette indices are kept as they are.
	 */
	public static char[] downscaleLabels(char[] src, int sw, int sh, int dw, int dh) {
		if (dw == sw && dh == sh) {
			return src.clone();
		}
		char[] out = new char[dw * dh];
		char[] vals = new char[64];
		int[] counts = new int[64];
		for (int y = 0; y < dh; y++) {
			int y0 = (int) ((long) y * sh / dh);
			int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / dh));
			for (int x = 0; x < dw; x++) {
				int x0 = (int) ((long) x * sw / dw);
				int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / dw));
				int n = 0;
				for (int sy = y0; sy < y1; sy++) {
					int row = sy * sw;
					for (int sx = x0; sx < x1; sx++) {
						char v = src[row + sx];
						int j = 0;
						while (j < n && vals[j] != v) {
							j++;
						}
						if (j == n) {
							if (n == vals.length) {
								vals = java.util.Arrays.copyOf(vals, n * 2);
								counts = java.util.Arrays.copyOf(counts, n * 2);
							}
							vals[n] = v;
							counts[n] = 0;
							n++;
						}
						counts[j]++;
					}
				}
				int best = 0;
				for (int j = 1; j < n; j++) {
					if (counts[j] > counts[best] || counts[j] == counts[best] && vals[best] == 0) {
						best = j;
					}
				}
				out[y * dw + x] = vals[best];
			}
		}
		return out;
	}

	/** The mask scaled to {@code w x h} (same palette), or the mask itself when it already has that size. */
	public static ShotMask scale(ShotMask m, int w, int h) {
		if (m.width == w && m.height == h) {
			return m;
		}
		return new ShotMask(w, h, m.palette, downscaleLabels(m.pixels, m.width, m.height, w, h));
	}
}
