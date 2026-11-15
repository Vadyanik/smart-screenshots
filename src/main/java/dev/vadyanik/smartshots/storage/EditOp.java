package dev.vadyanik.smartshots.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * One drawing / editing operation on a screenshot (see {@link ShotMeta#edits}). Coordinates are
 * normalized to the pristine image (0..1), sizes are in pixels of a 1080p-high image, so edits
 * survive rescaling. The baked PNG is always rebuilt from the pristine original plus the list,
 * so any operation can be removed later.
 *
 * <p>Instances are treated as immutable once they are in a list: changes create a copy.
 */
public final class EditOp {
	public static final String PEN = "pen";
	public static final String MARKER = "marker";
	public static final String LINE = "line";
	public static final String ARROW = "arrow";
	public static final String RECT = "rect";
	public static final String ELLIPSE = "ellipse";
	public static final String TEXT = "text";
	public static final String COUNTER = "counter";
	public static final String BLUR = "blur";
	public static final String PIXELATE = "pixelate";
	public static final String SPOTLIGHT = "spotlight";
	public static final String CROP = "crop";
	/** A picture (item, mob, player head) from {@link #img}, centered on the point, {@link #s} high. */
	public static final String STICKER = "sticker";
	/**
	 * Color adjustments of the whole image (the last one counts), applied before everything
	 * else: {@link #p} = brightness, contrast, saturation, warmth (each -1..1), vignette (0..1);
	 * {@link #n} bits: 1 black and white, 2 sepia.
	 */
	public static final String FILTER = "filter";
	public static final int FILTER_BW = 1;
	public static final int FILTER_SEPIA = 2;

	/** Operation type, one of the constants above. */
	public String t;
	/** ARGB color. */
	public int c = 0xFFFF3B30;
	/** Stroke width / font size / blur strength, in pixels of a 1080p-high image. */
	public float s = 6;
	/** Filled shape; for text: a colored box behind the text. */
	public boolean f;
	/** Points, normalized: x0, y0, x1, y1, ... (two corners for shapes, a polyline for pen). */
	public float[] p = new float[0];
	public String text;
	/** Sticker picture: a PNG, base64 (pixel art, scaled up without smoothing). */
	public String img;
	/** Number of a counter marker. */
	public int n;

	public EditOp() {
	}

	public EditOp(String type, int color, float size, boolean fill, float[] points) {
		this.t = type;
		this.c = color;
		this.s = size;
		this.f = fill;
		this.p = points;
	}

	public EditOp copy() {
		EditOp o = new EditOp(t, c, s, f, p == null ? new float[0] : p.clone());
		o.text = text;
		o.img = img;
		o.n = n;
		return o;
	}

	/** A copy moved by (dx, dy) in normalized units. */
	public EditOp moved(float dx, float dy) {
		EditOp o = copy();
		if (is(FILTER)) {
			return o; // its points are settings, not a place
		}
		for (int i = 0; i + 1 < o.p.length; i += 2) {
			o.p[i] += dx;
			o.p[i + 1] += dy;
		}
		return o;
	}

	public boolean is(String type) {
		return type.equals(t);
	}

	/** Region ops (blur, pixelate, spotlight, crop, rect, ellipse) use the two corners as a box. */
	public boolean boxed() {
		return switch (t == null ? "" : t) {
			case RECT, ELLIPSE, BLUR, PIXELATE, SPOTLIGHT, CROP -> true;
			default -> false;
		};
	}

	/** Normalized {x0, y0, x1, y1} with x0 <= x1, y0 <= y1 of the first two points. */
	public float[] box() {
		if (p == null || p.length < 4) {
			return null;
		}
		return new float[]{Math.min(p[0], p[2]), Math.min(p[1], p[3]), Math.max(p[0], p[2]), Math.max(p[1], p[3])};
	}

	// ------------------------------------------------------------------ list helpers

	/** The crop box of an edit list (the last crop op), clamped to the image, or null. */
	public static float[] crop(List<EditOp> ops) {
		if (ops == null) {
			return null;
		}
		for (int i = ops.size() - 1; i >= 0; i--) {
			EditOp o = ops.get(i);
			if (o != null && o.is(CROP)) {
				float[] b = o.box();
				if (b == null) {
					return null;
				}
				for (int k = 0; k < 4; k++) {
					b[k] = Math.clamp(b[k], 0f, 1f);
				}
				return b[2] - b[0] > 0.002f && b[3] - b[1] > 0.002f ? b : null;
			}
		}
		return null;
	}

	/** The filter op of an edit list (the last one), or null. */
	public static EditOp filter(List<EditOp> ops) {
		if (ops == null) {
			return null;
		}
		for (int i = ops.size() - 1; i >= 0; i--) {
			EditOp o = ops.get(i);
			if (o != null && o.is(FILTER)) {
				return o;
			}
		}
		return null;
	}

	/** Filter value {@code i} of {@link #p} (0 when missing). */
	public float fv(int i) {
		return p != null && i < p.length ? p[i] : 0;
	}

	/** Next counter number: one more than the largest one in the list. */
	public static int nextNumber(List<EditOp> ops) {
		int max = 0;
		for (EditOp o : ops) {
			if (o.is(COUNTER)) {
				max = Math.max(max, o.n);
			}
		}
		return max + 1;
	}

	/** Text of all text ops, for search. */
	public static List<String> texts(List<EditOp> ops) {
		List<String> out = new ArrayList<>();
		if (ops != null) {
			for (EditOp o : ops) {
				if (o != null && o.is(TEXT) && o.text != null && !o.text.isBlank()) {
					out.add(o.text);
				}
			}
		}
		return out;
	}

	public static boolean any(List<EditOp> ops) {
		return ops != null && !ops.isEmpty();
	}
}
