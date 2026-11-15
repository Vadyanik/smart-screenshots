package dev.vadyanik.smartshots.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Per-pixel "what is here" map of a screenshot, built from the depth buffer at capture time.
 *
 * <p>Every pixel stores an index into {@link #palette}; index 0 means "nothing" (sky, hand, far
 * away). A palette entry is a list of keys, most specific first, e.g. an item frame holding a
 * diamond is {@code ["i:minecraft:diamond", "e:minecraft:item_frame"]} and a sign is
 * {@code ["s:Welcome home", "b:minecraft:oak_sign"]}.
 *
 * <p>Key format (shared by capture, info panel chips and {@link ShotMeta#highlights}):
 * <ul>
 *   <li>{@code p:<nickname>} – a player (the local player too)</li>
 *   <li>{@code e:<entity type id>} – an entity (not players, not dropped items)</li>
 *   <li>{@code n:<entity type id>|<name>} – one mob renamed with a name tag (its pixels also carry
 *       the {@code e:} key of its type)</li>
 *   <li>{@code i:<item id>} – a dropped item or an item in an item frame</li>
 *   <li>{@code b:<block id>} – a block (fluids included: minecraft:water)</li>
 *   <li>{@code s:<sign text>} – a sign, text exactly as in {@link ShotMeta#signs}</li>
 * </ul>
 *
 * <p>Files live next to the shot in a hidden folder that the library scanner skips:
 * {@code <dir>/.smartshots-data/<file>.mask} and the untouched original image
 * {@code <dir>/.smartshots-data/<file>} (same name as the shot, written before the first
 * outline is baked into the shot).
 */
public final class ShotMask {
	public static final String DATA_DIR = ".smartshots-data";
	private static final int MAGIC = 0x53534D4B; // "SSMK"
	private static final int VERSION = 1;

	public static final String PLAYER = "p:";
	public static final String ENTITY = "e:";
	public static final String ITEM = "i:";
	public static final String BLOCK = "b:";
	public static final String SIGN = "s:";
	public static final String NAMED = "n:";

	/** Key of a mob renamed with a name tag. */
	public static String named(String typeId, String name) {
		return NAMED + typeId + "|" + name;
	}

	/** Entity type id of a {@link #NAMED} key (or of its {@code value}). */
	public static String namedType(String key) {
		String v = key.startsWith(NAMED) ? value(key) : key;
		int bar = v.indexOf('|');
		return bar < 0 ? v : v.substring(0, bar);
	}

	/** Custom name of a {@link #NAMED} key (or of its {@code value}). */
	public static String namedName(String key) {
		String v = key.startsWith(NAMED) ? value(key) : key;
		int bar = v.indexOf('|');
		return bar < 0 ? v : v.substring(bar + 1);
	}

	public final int width;
	public final int height;
	/** palette[0] is always the empty entry. */
	public final String[][] palette;
	/** Row-major, top row first (same orientation as the PNG), length width * height. */
	public final char[] pixels;

	public ShotMask(int width, int height, String[][] palette, char[] pixels) {
		if (pixels.length != width * height) {
			throw new IllegalArgumentException("pixels length " + pixels.length + " != " + width + "x" + height);
		}
		if (palette.length == 0 || palette.length > Character.MAX_VALUE) {
			throw new IllegalArgumentException("bad palette size " + palette.length);
		}
		this.width = width;
		this.height = height;
		this.palette = palette;
		this.pixels = pixels;
	}

	// ---------------------------------------------------------------- paths

	public static Path dataDir(Path shot) {
		return shot.getParent().resolve(DATA_DIR);
	}

	public static Path maskPath(Path shot) {
		return dataDir(shot).resolve(shot.getFileName().toString() + ".mask");
	}

	public static Path originalPath(Path shot) {
		return dataDir(shot).resolve(shot.getFileName().toString());
	}

	/** The image without baked outlines: the saved original when there is one, else the shot itself. */
	public static Path pristine(Path shot) {
		Path orig = originalPath(shot);
		return Files.isRegularFile(orig) ? orig : shot;
	}

	// ---------------------------------------------------------------- queries

	public String[] keysAt(int x, int y) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return palette[0];
		}
		return palette[pixels[y * width + x]];
	}

	/** The most specific key at a pixel, or null for nothing. */
	public String keyAt(int x, int y) {
		String[] keys = keysAt(x, y);
		return keys.length == 0 ? null : keys[0];
	}

	/** For each palette index, whether it contains {@code key}. */
	public boolean[] selector(String key) {
		boolean[] sel = new boolean[palette.length];
		for (int i = 1; i < palette.length; i++) {
			for (String k : palette[i]) {
				if (k.equals(key)) {
					sel[i] = true;
					break;
				}
			}
		}
		return sel;
	}

	public boolean contains(String key) {
		boolean[] sel = selector(key);
		for (boolean b : sel) {
			if (b) {
				return true;
			}
		}
		return false;
	}

	/** {minX, minY, maxX, maxY} (inclusive) of the pixels with {@code key}, or null when absent. */
	public int[] bounds(String key) {
		boolean[] sel = selector(key);
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < height; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) {
				if (sel[pixels[row + x]]) {
					if (x < minX) {
						minX = x;
					}
					if (x > maxX) {
						maxX = x;
					}
					if (y < minY) {
						minY = y;
					}
					maxY = y;
				}
			}
		}
		return maxX < 0 ? null : new int[]{minX, minY, maxX, maxY};
	}

	public static String kind(String key) {
		return key.length() >= 2 ? key.substring(0, 2) : "";
	}

	public static String value(String key) {
		return key.length() >= 2 ? key.substring(2) : key;
	}

	// ---------------------------------------------------------------- io

	public void write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
			new DeflaterOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			out.writeInt(width);
			out.writeInt(height);
			out.writeInt(palette.length);
			for (String[] entry : palette) {
				out.writeShort(entry.length);
				for (String k : entry) {
					out.writeUTF(k);
				}
			}
			// Run-length encoding: (run length, index) pairs.
			int i = 0;
			int n = pixels.length;
			while (i < n) {
				char v = pixels[i];
				int j = i + 1;
				while (j < n && pixels[j] == v) {
					j++;
				}
				out.writeInt(j - i);
				out.writeChar(v);
				i = j;
			}
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static ShotMask read(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(
			new InflaterInputStream(Files.newInputStream(file)), 1 << 16))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("Not a SmartShots mask: " + file);
			}
			int version = in.readInt();
			if (version != VERSION) {
				throw new IOException("Unsupported mask version " + version);
			}
			int w = in.readInt();
			int h = in.readInt();
			int ps = in.readInt();
			String[][] palette = new String[ps][];
			for (int p = 0; p < ps; p++) {
				int nk = in.readUnsignedShort();
				String[] keys = new String[nk];
				for (int k = 0; k < nk; k++) {
					keys[k] = in.readUTF();
				}
				palette[p] = keys;
			}
			char[] pixels = new char[w * h];
			int i = 0;
			while (i < pixels.length) {
				int run = in.readInt();
				char v = in.readChar();
				java.util.Arrays.fill(pixels, i, Math.min(pixels.length, i + run), v);
				i += run;
			}
			return new ShotMask(w, h, palette, pixels);
		}
	}

	/** Reads the mask of a shot, or null when the shot has none (old shots, analysis off). */
	public static ShotMask readFor(Path shot) {
		Path p = maskPath(shot);
		if (!Files.isRegularFile(p)) {
			return null;
		}
		try {
			return read(p);
		} catch (Exception e) {
			dev.vadyanik.smartshots.SmartShots.LOGGER.warn("Couldn't read mask {}", p, e);
			return null;
		}
	}

	/** Helper for building palettes. */
	public static String[] entry(List<String> keys) {
		return keys.toArray(new String[0]);
	}
}
