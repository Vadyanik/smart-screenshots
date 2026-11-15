package dev.vadyanik.smartshots.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.vadyanik.smartshots.SmartShots;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Consumer;

/**
 * Asynchronous texture readbacks. The copy is recorded right away (so it sees the texture as it is
 * at this point of the frame); the callback runs on the render thread a frame or two later, when
 * the GPU is done. Callbacks only do bulk memory copies (vertical flip included) so they don't
 * stall the frame; per-pixel work happens on the worker. A failed readback calls back with null.
 *
 * <p>Readback rows are bottom-up (like {@code Screenshot.takeScreenshot}); results are top row first,
 * the orientation of the saved PNG.
 */
final class GpuReadback {
	private GpuReadback() {
	}

	/** RGBA color; the receiver owns the image. Alpha is NOT fixed up yet (see {@link #opaque}). */
	static void color(GpuTexture texture, Consumer<NativeImage> callback) {
		int w = texture.getWidth(0);
		int h = texture.getHeight(0);
		int bpp = texture.getFormat().blockSize();
		if (bpp != 4) {
			throw new IllegalStateException("Unexpected color format " + texture.getFormat());
		}
		read(texture, "SmartShots color readback", w, h, bpp, view -> {
			NativeImage image = new NativeImage(w, h, false);
			long src = MemoryUtil.memAddress0(view.data()); // index 0, like vanilla's absolute getInt
			long dst = image.getPointer();
			long row = (long) w * 4;
			for (int y = 0; y < h; y++) {
				MemoryUtil.memCopy(src + y * row, dst + (long) (h - 1 - y) * row, row);
			}
			return image;
		}, callback);
	}

	/** D32_FLOAT depth as a float array, {@code width * height}, top row first. */
	static void depth(GpuTexture texture, Consumer<float[]> callback) {
		int w = texture.getWidth(0);
		int h = texture.getHeight(0);
		int bpp = texture.getFormat().blockSize();
		if (bpp != 4) {
			throw new IllegalStateException("Unexpected depth format " + texture.getFormat());
		}
		read(texture, "SmartShots depth readback", w, h, bpp, view -> {
			float[] out = new float[w * h];
			FloatBuffer fb = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
			for (int y = 0; y < h; y++) {
				fb.get(y * w, out, (h - 1 - y) * w, w);
			}
			return out;
		}, callback);
	}

	/**
	 * True when the image is (almost) entirely black: what a failed read of the frame looks like.
	 * Samples a sparse grid, so it's cheap enough for every shot.
	 */
	static boolean isBlack(NativeImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		long p = image.getPointer();
		int samples = 0;
		int lit = 0;
		int stepX = Math.max(1, w / 64);
		int stepY = Math.max(1, h / 36);
		for (int y = stepY / 2; y < h; y += stepY) {
			for (int x = stepX / 2; x < w; x += stepX) {
				int rgba = MemoryUtil.memGetInt(p + ((long) y * w + x) * 4);
				int r = rgba & 0xFF;
				int g = (rgba >> 8) & 0xFF;
				int b = (rgba >> 16) & 0xFF;
				samples++;
				if (Math.max(r, Math.max(g, b)) > 6) {
					lit++;
				}
			}
		}
		return samples > 0 && lit * 200 < samples; // under 0.5 % lit
	}

	/** Forces alpha to 255 (the main target keeps the clear alpha of 0 where there's sky). */
	static void opaque(NativeImage image) {
		long p = image.getPointer();
		long n = (long) image.getWidth() * image.getHeight();
		for (long i = 0; i < n; i++) {
			MemoryUtil.memPutByte(p + i * 4 + 3, (byte) 0xFF);
		}
	}

	private interface Reader<T> {
		T read(GpuBufferSlice.MappedView view);
	}

	private static <T> void read(GpuTexture texture, String label, int w, int h, int bpp, Reader<T> reader, Consumer<T> callback) {
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> label,
			GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * bpp);
		try {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
				T result;
				try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
					result = reader.read(view);
				} catch (Throwable t) {
					SmartShots.LOGGER.warn("Couldn't read back {}", label, t);
					result = null;
				} finally {
					buffer.close();
				}
				callback.accept(result);
			}, 0);
		} catch (RuntimeException e) {
			buffer.close();
			throw e;
		}
	}
}
