package dev.vadyanik.smartshots.storage;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Lossless PNG encoder, noticeably smaller than {@code NativeImage.writeToFile} (stb_image_write):
 * <ul>
 *   <li>opaque images are stored as RGB instead of RGBA;</li>
 *   <li>each row gets the filter (none/sub/up/average/Paeth) with the smallest sum of absolute
 *   residuals, the libpng heuristic;</li>
 *   <li>real zlib (java.util.zip) instead of stb's minimal deflate.</li>
 * </ul>
 * Pure Java, no GL or Minecraft classes; safe on any thread.
 */
public final class PngWriter {
	/**
	 * zlib level for new shots. Measured on a 3834x2106 game screenshot: stb 5.87 MB / 1.2 s,
	 * level 6 4.08 MB / 1.2 s, level 9 3.91 MB / 6.8 s.
	 */
	public static final int LEVEL = 6;
	/** Archiving runs in the background, so it can afford the last few percent. */
	public static final int LEVEL_ARCHIVE = 9;
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final int IDAT_CHUNK = 1 << 18;

	private PngWriter() {
	}

	/**
	 * Writes {@code abgr} (NativeImage layout: R in the low byte, A in the high byte, top row
	 * first) to {@code file} through a temp file and an atomic move, so a crash never leaves a
	 * half-written PNG behind.
	 */
	public static void writeAtomic(Path file, int[] abgr, int width, int height, int level) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (OutputStream out = Files.newOutputStream(tmp)) {
				write(out, abgr, width, height, level);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	public static void write(OutputStream os, int[] abgr, int width, int height, int level) throws IOException {
		if (abgr.length < width * height || width <= 0 || height <= 0) {
			throw new IllegalArgumentException("bad image " + width + "x" + height);
		}
		boolean opaque = true;
		for (int i = 0, n = width * height; i < n; i++) {
			if (abgr[i] >>> 24 != 0xFF) {
				opaque = false;
				break;
			}
		}
		int bpp = opaque ? 3 : 4;
		int stride = width * bpp;

		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(os, 1 << 16));
		out.write(SIGNATURE);
		byte[] ihdr = new byte[13];
		putInt(ihdr, 0, width);
		putInt(ihdr, 4, height);
		ihdr[8] = 8; // bit depth
		ihdr[9] = (byte) (opaque ? 2 : 6); // truecolor / truecolor + alpha
		chunk(out, "IHDR", ihdr, ihdr.length);

		Deflater deflater = new Deflater(level);
		try {
			byte[] prev = new byte[stride];
			byte[] cur = new byte[stride];
			// filtered candidates: [type][1 + stride]
			byte[][] cand = new byte[5][1 + stride];
			byte[] buf = new byte[IDAT_CHUNK];
			int bufLen = 0;
			for (int y = 0; y < height; y++) {
				int row = y * width;
				if (opaque) {
					for (int x = 0, o = 0; x < width; x++, o += 3) {
						int p = abgr[row + x];
						cur[o] = (byte) p;
						cur[o + 1] = (byte) (p >> 8);
						cur[o + 2] = (byte) (p >> 16);
					}
				} else {
					for (int x = 0, o = 0; x < width; x++, o += 4) {
						int p = abgr[row + x];
						cur[o] = (byte) p;
						cur[o + 1] = (byte) (p >> 8);
						cur[o + 2] = (byte) (p >> 16);
						cur[o + 3] = (byte) (p >>> 24);
					}
				}
				byte[] best = filter(cur, y == 0 ? null : prev, bpp, cand);
				deflater.setInput(best, 0, best.length);
				while (!deflater.needsInput()) {
					bufLen = drain(deflater, out, buf, bufLen);
				}
				byte[] t = prev;
				prev = cur;
				cur = t;
			}
			deflater.finish();
			while (!deflater.finished()) {
				bufLen = drain(deflater, out, buf, bufLen);
			}
			if (bufLen > 0) {
				chunk(out, "IDAT", buf, bufLen);
			}
		} finally {
			deflater.end();
		}
		chunk(out, "IEND", new byte[0], 0);
		out.flush();
	}

	/** Deflates into {@code buf}; flushes a full buffer as an IDAT chunk. Returns the new fill. */
	private static int drain(Deflater deflater, DataOutputStream out, byte[] buf, int len) throws IOException {
		len += deflater.deflate(buf, len, buf.length - len);
		if (len == buf.length) {
			chunk(out, "IDAT", buf, len);
			len = 0;
		}
		return len;
	}

	/** Picks the filter with the smallest sum of |residual| (as signed bytes). */
	private static byte[] filter(byte[] cur, byte[] prev, int bpp, byte[][] cand) {
		int n = cur.length;
		for (int t = 0; t < 5; t++) {
			cand[t][0] = (byte) t;
		}
		byte[] none = cand[0];
		byte[] sub = cand[1];
		byte[] up = cand[2];
		byte[] avg = cand[3];
		byte[] paeth = cand[4];
		long s0 = 0;
		long s1 = 0;
		long s2 = 0;
		long s3 = 0;
		long s4 = 0;
		for (int i = 0; i < n; i++) {
			int x = cur[i] & 0xFF;
			int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
			int b = prev != null ? prev[i] & 0xFF : 0;
			int c = prev != null && i >= bpp ? prev[i - bpp] & 0xFF : 0;
			byte v0 = (byte) x;
			byte v1 = (byte) (x - a);
			byte v2 = (byte) (x - b);
			byte v3 = (byte) (x - ((a + b) >>> 1));
			byte v4 = (byte) (x - paethPredictor(a, b, c));
			none[i + 1] = v0;
			sub[i + 1] = v1;
			up[i + 1] = v2;
			avg[i + 1] = v3;
			paeth[i + 1] = v4;
			s0 += Math.abs(v0);
			s1 += Math.abs(v1);
			s2 += Math.abs(v2);
			s3 += Math.abs(v3);
			s4 += Math.abs(v4);
		}
		long[] score = {s0, s1, s2, s3, s4};
		int best = 0;
		for (int t = 1; t < 5; t++) {
			if (score[t] < score[best]) {
				best = t;
			}
		}
		return cand[best];
	}

	private static int paethPredictor(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		if (pa <= pb && pa <= pc) {
			return a;
		}
		return pb <= pc ? b : c;
	}

	private static void chunk(DataOutputStream out, String type, byte[] data, int len) throws IOException {
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		out.writeInt(len);
		out.write(t);
		out.write(data, 0, len);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data, 0, len);
		out.writeInt((int) crc.getValue());
	}

	private static void putInt(byte[] b, int off, int v) {
		b[off] = (byte) (v >>> 24);
		b[off + 1] = (byte) (v >>> 16);
		b[off + 2] = (byte) (v >>> 8);
		b[off + 3] = (byte) v;
	}
}
