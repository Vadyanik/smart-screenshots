package dev.vadyanik.smartshots.storage;

import dev.vadyanik.smartshots.SmartShots;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.List;

/**
 * Draws {@link EditOp}s into pixel arrays with Java2D (headless-safe: only BufferedImage drawing).
 * The viewer's image texture and the baked PNG use the same code, so they look identical.
 * Pixels are ABGR ints as in NativeImage; crop is not drawn here, see {@link #crop}.
 */
public final class EditRenderer {
	private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
	private static final float BASE_H = 1080f;

	private EditRenderer() {
	}

	/** Pixel scale of an image: sizes in {@link EditOp#s} are for a 1080p-high image. */
	public static float scale(int h) {
		return h / BASE_H;
	}

	/** Applies every op except crop to {@code abgr} in place. */
	public static void apply(int[] abgr, int w, int h, List<EditOp> ops) {
		if (ops == null || ops.isEmpty()) {
			return;
		}
		filter(abgr, w, h, EditOp.filter(ops)); // channel order doesn't matter for it except warmth: done before the swap
		swapRB(abgr, w * h); // -> ARGB for Java2D
		try {
			DataBufferInt db = new DataBufferInt(abgr, w * h);
			int[] masks = {0xFF0000, 0xFF00, 0xFF, 0xFF000000};
			WritableRaster raster = Raster.createPackedRaster(db, w, h, w, masks, null);
			DirectColorModel cm = new DirectColorModel(32, masks[0], masks[1], masks[2], masks[3]);
			BufferedImage img = new BufferedImage(cm, raster, false, null);
			float k = scale(h);
			for (EditOp op : ops) {
				if (op == null || op.t == null || op.p == null || op.is(EditOp.CROP) || op.is(EditOp.FILTER)) {
					continue;
				}
				try {
					switch (op.t) {
						case EditOp.BLUR -> blur(abgr, w, h, box(op, w, h), Math.max(2, Math.round(op.s * k)));
						case EditOp.PIXELATE -> pixelate(abgr, w, h, box(op, w, h), Math.max(2, Math.round(op.s * k)));
						default -> {
							Graphics2D g = img.createGraphics();
							try {
								hints(g);
								draw(g, op, w, h, k);
							} finally {
								g.dispose();
							}
						}
					}
				} catch (Throwable e) {
					SmartShots.LOGGER.warn("Couldn't draw edit {}", op.t, e);
				}
			}
		} finally {
			swapRB(abgr, w * h);
		}
	}

	private static void hints(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
	}

	private static void draw(Graphics2D g, EditOp op, int w, int h, float k) {
		float sw = Math.max(1f, op.s * k);
		Color color = new Color(op.c, true);
		g.setComposite(AlphaComposite.SrcOver);
		switch (op.t) {
			case EditOp.PEN, EditOp.MARKER -> {
				Path2D path = polyline(op, w, h);
				if (path == null) {
					return;
				}
				if (op.is(EditOp.MARKER)) {
					g.setColor(new Color((op.c & 0xFFFFFF) | 0x70000000, true));
					g.setStroke(new BasicStroke(sw, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
				} else {
					g.setColor(color);
					g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				}
				g.draw(path);
			}
			case EditOp.LINE -> {
				if (op.p.length < 4) {
					return;
				}
				g.setColor(color);
				g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(new Line2D.Float(op.p[0] * w, op.p[1] * h, op.p[2] * w, op.p[3] * h));
			}
			case EditOp.ARROW -> {
				if (op.p.length < 4) {
					return;
				}
				g.setColor(color);
				g.fill(arrow(op.p[0] * w, op.p[1] * h, op.p[2] * w, op.p[3] * h, sw));
			}
			case EditOp.RECT, EditOp.ELLIPSE -> {
				float[] b = box(op, w, h);
				if (b == null) {
					return;
				}
				Shape s = op.is(EditOp.RECT)
					? new Rectangle2D.Float(b[0], b[1], b[2] - b[0], b[3] - b[1])
					: new Ellipse2D.Float(b[0], b[1], b[2] - b[0], b[3] - b[1]);
				g.setColor(color);
				if (op.f) {
					g.fill(s);
				} else {
					g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g.draw(s);
				}
			}
			case EditOp.SPOTLIGHT -> {
				float[] b = box(op, w, h);
				if (b == null) {
					return;
				}
				Area dim = new Area(new Rectangle2D.Float(0, 0, w, h));
				float r = Math.min(b[2] - b[0], b[3] - b[1]) * 0.08f;
				dim.subtract(new Area(new RoundRectangle2D.Float(b[0], b[1], b[2] - b[0], b[3] - b[1], r, r)));
				g.setColor(new Color(0, 0, 0, 150));
				g.fill(dim);
			}
			case EditOp.TEXT -> text(g, op, w, h, k);
			case EditOp.COUNTER -> counter(g, op, w, h, k);
			case EditOp.STICKER -> sticker(g, op, w, h, k);
			default -> {
			}
		}
	}

	private static Path2D polyline(EditOp op, int w, int h) {
		if (op.p.length < 2) {
			return null;
		}
		Path2D.Float path = new Path2D.Float();
		path.moveTo(op.p[0] * w, op.p[1] * h);
		if (op.p.length == 2) {
			path.lineTo(op.p[0] * w + 0.01f, op.p[1] * h); // a dot
		}
		for (int i = 2; i + 1 < op.p.length; i += 2) {
			path.lineTo(op.p[i] * w, op.p[i + 1] * h);
		}
		return path;
	}

	/** Arrow as one filled shape: a shaft and a sharp head. */
	public static Shape arrow(float x0, float y0, float x1, float y1, float sw) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float len = (float) Math.hypot(dx, dy);
		if (len < 0.5f) {
			return new Ellipse2D.Float(x1 - sw / 2, y1 - sw / 2, sw, sw);
		}
		float head = Math.min(len, Math.max(sw * 4f, 10f));
		float half = head * 0.5f;
		Path2D.Float p = new Path2D.Float();
		// In local coordinates: x along the arrow, tip at x = len.
		float shaftEnd = len - head * 0.8f;
		p.moveTo(0, -sw / 2);
		p.lineTo(shaftEnd, -sw / 2);
		p.lineTo(len - head, -half);
		p.lineTo(len, 0);
		p.lineTo(len - head, half);
		p.lineTo(shaftEnd, sw / 2);
		p.lineTo(0, sw / 2);
		p.closePath();
		Area a = new Area(p);
		a.add(new Area(new Ellipse2D.Float(-sw / 2, -sw / 2, sw, sw)));
		AffineTransform t = new AffineTransform();
		t.translate(x0, y0);
		t.rotate(Math.atan2(dy, dx));
		return t.createTransformedShape(a);
	}

	// ------------------------------------------------------------------ text & counters

	private static Font font(float px) {
		return new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(Math.max(4f, px));
	}

	/** Text block layout in image pixels: lines and their baselines. */
	private record TextBlock(TextLayout[] lines, float[] baselines, float x, float y, float w, float h, float pad) {
	}

	private static TextBlock layout(EditOp op, int w, int h, float k) {
		if (op.text == null || op.text.isEmpty() || op.p.length < 2) {
			return null;
		}
		float size = Math.max(4f, op.s * k);
		Font font = font(size);
		String[] parts = op.text.split("\n", -1);
		TextLayout[] lines = new TextLayout[parts.length];
		float[] base = new float[parts.length];
		float pad = op.f ? size * 0.3f : 0;
		float x = op.p[0] * w;
		float y = op.p[1] * h;
		float lineH = size * 1.2f;
		float maxW = 0;
		for (int i = 0; i < parts.length; i++) {
			lines[i] = new TextLayout(parts[i].isEmpty() ? " " : parts[i], font, FRC);
			maxW = Math.max(maxW, lines[i].getAdvance());
			base[i] = y + pad + i * lineH + lines[i].getAscent();
		}
		float totalH = parts.length * lineH;
		return new TextBlock(lines, base, x, y, maxW + pad * 2, totalH + pad * 2, pad);
	}

	private static void text(Graphics2D g, EditOp op, int w, int h, float k) {
		TextBlock tb = layout(op, w, h, k);
		if (tb == null) {
			return;
		}
		float size = Math.max(4f, op.s * k);
		Color color = new Color(op.c, true);
		if (op.f) {
			g.setColor(color);
			float r = size * 0.35f;
			g.fill(new RoundRectangle2D.Float(tb.x, tb.y, tb.w, tb.h, r, r));
			g.setColor(contrast(op.c));
			for (int i = 0; i < tb.lines.length; i++) {
				tb.lines[i].draw(g, tb.x + tb.pad, tb.baselines[i]);
			}
			return;
		}
		g.setStroke(new BasicStroke(Math.max(1f, size * 0.14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int i = 0; i < tb.lines.length; i++) {
			Shape s = tb.lines[i].getOutline(AffineTransform.getTranslateInstance(tb.x + tb.pad, tb.baselines[i]));
			g.setColor(luminance(op.c) > 0.35 ? new Color(0, 0, 0, 200) : new Color(255, 255, 255, 220));
			g.draw(s);
			g.setColor(color);
			g.fill(s);
		}
	}

	private static void counter(Graphics2D g, EditOp op, int w, int h, float k) {
		if (op.p.length < 2) {
			return;
		}
		float r = Math.max(4f, op.s * k);
		float cx = op.p[0] * w;
		float cy = op.p[1] * h;
		g.setColor(new Color(0, 0, 0, 90));
		g.fill(new Ellipse2D.Float(cx - r * 1.12f, cy - r * 1.12f + r * 0.08f, r * 2.24f, r * 2.24f));
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Float(cx - r * 1.1f, cy - r * 1.1f, r * 2.2f, r * 2.2f));
		g.setColor(new Color(op.c, true));
		g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
		String s = String.valueOf(op.n);
		TextLayout tl = new TextLayout(s, font(r * (s.length() > 2 ? 0.8f : 1.1f)), FRC);
		Rectangle2D b = tl.getBounds();
		g.setColor(contrast(op.c));
		tl.draw(g, (float) (cx - b.getCenterX()), (float) (cy - b.getCenterY()));
	}

	/** Normalized bounds {x0, y0, x1, y1} of a text or counter op (for hit testing and selection). */
	public static float[] bounds(EditOp op, int w, int h) {
		float k = scale(h);
		if (op.is(EditOp.TEXT)) {
			TextBlock tb;
			try {
				tb = layout(op, w, h, k);
			} catch (Throwable e) {
				tb = null;
			}
			if (tb == null) {
				return null;
			}
			return new float[]{tb.x / w, tb.y / h, (tb.x + tb.w) / w, (tb.y + tb.h) / h};
		}
		if (op.is(EditOp.STICKER) && op.p.length >= 2) {
			BufferedImage im = stickerImage(op.img);
			float sh = Math.max(4f, op.s * k);
			float sw = im == null ? sh : sh * im.getWidth() / Math.max(1, im.getHeight());
			return new float[]{op.p[0] - sw / 2 / w, op.p[1] - sh / 2 / h, op.p[0] + sw / 2 / w, op.p[1] + sh / 2 / h};
		}
		if (op.is(EditOp.COUNTER) && op.p.length >= 2) {
			float r = Math.max(4f, op.s * k) * 1.1f;
			return new float[]{op.p[0] - r / w, op.p[1] - r / h, op.p[0] + r / w, op.p[1] + r / h};
		}
		return null;
	}

	private static double luminance(int argb) {
		return (0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF)) / 255.0;
	}

	private static Color contrast(int argb) {
		return luminance(argb) > 0.6 ? new Color(0x111111) : Color.WHITE;
	}

	// ------------------------------------------------------------------ stickers

	private static final java.util.Map<String, BufferedImage> STICKERS = java.util.Collections.synchronizedMap(
		new java.util.LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(java.util.Map.Entry<String, BufferedImage> e) {
				return size() > 64;
			}
		});

	/** The decoded sticker picture (cached), or null. */
	public static BufferedImage stickerImage(String b64) {
		if (b64 == null || b64.isEmpty()) {
			return null;
		}
		BufferedImage cached = STICKERS.get(b64);
		if (cached != null) {
			return cached;
		}
		try {
			BufferedImage im = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(b64)));
			if (im != null) {
				BufferedImage argb = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = argb.createGraphics();
				g.drawImage(im, 0, 0, null);
				g.dispose();
				STICKERS.put(b64, argb);
				return argb;
			}
		} catch (Exception e) {
			SmartShots.LOGGER.debug("Bad sticker image", e);
		}
		return null;
	}

	/** Encodes ARGB pixels as a base64 PNG (for {@link EditOp#img}). */
	public static String encodeSticker(int[] argb, int w, int h) {
		try {
			BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			im.setRGB(0, 0, w, h, argb, 0, w);
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(im, "png", out);
			return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Couldn't encode a sticker", e);
			return null;
		}
	}

	private static void sticker(Graphics2D g, EditOp op, int w, int h, float k) {
		BufferedImage im = stickerImage(op.img);
		if (im == null || op.p.length < 2) {
			return;
		}
		float sh = Math.max(4f, op.s * k);
		float sw = sh * im.getWidth() / Math.max(1, im.getHeight());
		float x = op.p[0] * w - sw / 2;
		float y = op.p[1] * h - sh / 2;
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		// Soft drop shadow: the silhouette, darkened, a bit down and right.
		float off = Math.max(1f, sh * 0.04f);
		BufferedImage shadow = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int yy = 0; yy < im.getHeight(); yy++) {
			for (int xx = 0; xx < im.getWidth(); xx++) {
				int a = im.getRGB(xx, yy) >>> 24;
				shadow.setRGB(xx, yy, (a * 100 / 255) << 24);
			}
		}
		g.drawImage(shadow, Math.round(x + off), Math.round(y + off), Math.round(sw), Math.round(sh), null);
		g.drawImage(im, Math.round(x), Math.round(y), Math.round(sw), Math.round(sh), null);
	}

	// ------------------------------------------------------------------ filter

	/** Brightness, contrast, saturation, warmth, B/W, sepia and vignette over the whole image. */
	private static void filter(int[] abgr, int w, int h, EditOp f) {
		if (f == null) {
			return;
		}
		float bright = f.fv(0);
		float contrast = 1 + f.fv(1);
		float sat = 1 + f.fv(2);
		float warm = f.fv(3);
		float vig = Math.clamp(f.fv(4), 0f, 1f);
		boolean bw = (f.n & EditOp.FILTER_BW) != 0;
		boolean sepia = (f.n & EditOp.FILTER_SEPIA) != 0;
		if (bright == 0 && contrast == 1 && sat == 1 && warm == 0 && vig == 0 && !bw && !sepia) {
			return;
		}
		float cx = w / 2f;
		float cy = h / 2f;
		for (int y = 0; y < h; y++) {
			float dy = (y - cy) / cy;
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				int p = abgr[i];
				float r = (p & 0xFF) / 255f;
				float g = ((p >> 8) & 0xFF) / 255f;
				float b = ((p >> 16) & 0xFF) / 255f;
				if (bright != 0) {
					float m = bright > 0 ? 1 + bright : 1 + bright * 0.9f;
					r *= m;
					g *= m;
					b *= m;
				}
				if (contrast != 1) {
					r = (r - 0.5f) * contrast + 0.5f;
					g = (g - 0.5f) * contrast + 0.5f;
					b = (b - 0.5f) * contrast + 0.5f;
				}
				float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
				if (bw || sepia) {
					r = g = b = lum;
					if (sepia) {
						r = lum * 1.07f + 0.08f;
						g = lum * 0.95f + 0.03f;
						b = lum * 0.78f;
					}
				} else if (sat != 1) {
					r = lum + (r - lum) * sat;
					g = lum + (g - lum) * sat;
					b = lum + (b - lum) * sat;
				}
				if (warm != 0) {
					r += warm * 0.08f;
					b -= warm * 0.08f;
				}
				if (vig > 0) {
					float dx = (x - cx) / cx;
					float d = (float) Math.sqrt(dx * dx + dy * dy) / 1.4142f;
					float t = Math.clamp((d - 0.35f) / 0.65f, 0f, 1f);
					float m = 1 - vig * 0.85f * t * t * (3 - 2 * t);
					r *= m;
					g *= m;
					b *= m;
				}
				abgr[i] = (p & 0xFF000000) | c(b) << 16 | c(g) << 8 | c(r);
			}
		}
	}

	private static int c(float v) {
		return v <= 0 ? 0 : v >= 1 ? 255 : Math.round(v * 255);
	}

	// ------------------------------------------------------------------ pixel effects

	/** Pixel box {x0, y0, x1, y1} of a boxed op, clamped to the image, or null when empty. */
	private static float[] box(EditOp op, int w, int h) {
		float[] b = op.box();
		if (b == null) {
			return null;
		}
		float[] r = {Math.clamp(b[0] * w, 0, w), Math.clamp(b[1] * h, 0, h), Math.clamp(b[2] * w, 0, w), Math.clamp(b[3] * h, 0, h)};
		return r[2] - r[0] >= 1 && r[3] - r[1] >= 1 ? r : null;
	}

	/** Three box-blur passes (close to a Gaussian) inside the box; works on ARGB or ABGR alike. */
	private static void blur(int[] px, int w, int h, float[] b, int radius) {
		if (b == null) {
			return;
		}
		int x0 = (int) b[0];
		int y0 = (int) b[1];
		int x1 = (int) Math.ceil(b[2]);
		int y1 = (int) Math.ceil(b[3]);
		int bw = x1 - x0;
		int bh = y1 - y0;
		int[] buf = new int[bw * bh];
		for (int y = 0; y < bh; y++) {
			System.arraycopy(px, (y0 + y) * w + x0, buf, y * bw, bw);
		}
		int[] tmp = new int[Math.max(bw, bh)];
		for (int pass = 0; pass < 3; pass++) {
			for (int y = 0; y < bh; y++) {
				boxLine(buf, y * bw, 1, bw, radius, tmp);
			}
			for (int x = 0; x < bw; x++) {
				boxLine(buf, x, bw, bh, radius, tmp);
			}
		}
		for (int y = 0; y < bh; y++) {
			System.arraycopy(buf, y * bw, px, (y0 + y) * w + x0, bw);
		}
	}

	/** Sliding-window mean over one row or column (edges clamped). */
	private static void boxLine(int[] a, int start, int step, int n, int r, int[] tmp) {
		r = Math.min(r, n);
		int div = 2 * r + 1;
		long s0 = 0;
		long s1 = 0;
		long s2 = 0;
		long s3 = 0;
		for (int i = -r; i <= r; i++) {
			int p = a[start + Math.clamp(i, 0, n - 1) * step];
			s0 += p & 0xFF;
			s1 += (p >> 8) & 0xFF;
			s2 += (p >> 16) & 0xFF;
			s3 += p >>> 24;
		}
		for (int i = 0; i < n; i++) {
			tmp[i] = (int) (s0 / div) | (int) (s1 / div) << 8 | (int) (s2 / div) << 16 | (int) (s3 / div) << 24;
			int out = a[start + Math.max(i - r, 0) * step];
			int in = a[start + Math.min(i + r + 1, n - 1) * step];
			s0 += (in & 0xFF) - (out & 0xFF);
			s1 += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
			s2 += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
			s3 += (in >>> 24) - (out >>> 24);
		}
		for (int i = 0; i < n; i++) {
			a[start + i * step] = tmp[i];
		}
	}

	private static void pixelate(int[] px, int w, int h, float[] b, int block) {
		if (b == null) {
			return;
		}
		int x0 = (int) b[0];
		int y0 = (int) b[1];
		int x1 = (int) Math.ceil(b[2]);
		int y1 = (int) Math.ceil(b[3]);
		for (int by = y0; by < y1; by += block) {
			int ey = Math.min(by + block, y1);
			for (int bx = x0; bx < x1; bx += block) {
				int ex = Math.min(bx + block, x1);
				long s0 = 0;
				long s1 = 0;
				long s2 = 0;
				long s3 = 0;
				int n = 0;
				for (int y = by; y < ey; y++) {
					for (int x = bx; x < ex; x++) {
						int p = px[y * w + x];
						s0 += p & 0xFF;
						s1 += (p >> 8) & 0xFF;
						s2 += (p >> 16) & 0xFF;
						s3 += p >>> 24;
						n++;
					}
				}
				int avg = (int) (s0 / n) | (int) (s1 / n) << 8 | (int) (s2 / n) << 16 | (int) (s3 / n) << 24;
				for (int y = by; y < ey; y++) {
					java.util.Arrays.fill(px, y * w + bx, y * w + ex, avg);
				}
			}
		}
	}

	private static void swapRB(int[] px, int n) {
		for (int i = 0; i < n; i++) {
			int p = px[i];
			px[i] = (p & 0xFF00FF00) | ((p >> 16) & 0xFF) | ((p & 0xFF) << 16);
		}
	}

	// ------------------------------------------------------------------ crop

	public record Image(int[] px, int w, int h) {
	}

	/** The pixels inside a normalized crop box (null box = unchanged). */
	public static Image crop(int[] px, int w, int h, float[] box) {
		if (box == null) {
			return new Image(px, w, h);
		}
		int x0 = Math.clamp(Math.round(box[0] * w), 0, w - 1);
		int y0 = Math.clamp(Math.round(box[1] * h), 0, h - 1);
		int x1 = Math.clamp(Math.round(box[2] * w), x0 + 1, w);
		int y1 = Math.clamp(Math.round(box[3] * h), y0 + 1, h);
		int cw = x1 - x0;
		int ch = y1 - y0;
		if (cw == w && ch == h) {
			return new Image(px, w, h);
		}
		int[] out = new int[cw * ch];
		for (int y = 0; y < ch; y++) {
			System.arraycopy(px, (y0 + y) * w + x0, out, y * cw, cw);
		}
		return new Image(out, cw, ch);
	}
}
