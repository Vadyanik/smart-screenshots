package dev.vadyanik.smartshots.capture;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/**
 * The exact camera of a captured frame: what's needed to turn a depth buffer pixel back into a
 * world position. All matrices work on camera-relative coordinates (the world is rendered relative
 * to {@link #cam}).
 *
 * <p>Depth is reverse-Z (near and far are swapped in {@code Projection}, cleared to 0 = far/sky).
 */
record FrameView(Vec3 cam, Matrix4d projView, Matrix4d inverse, int width, int height, boolean zZeroToOne, float partialTick) {
	/** Depth values at or below this are sky / nothing. */
	static final float FAR = 1e-7f;

	static FrameView of(Matrix4f projection, CameraRenderState camera, int width, int height, float partialTick) {
		Matrix4d pv = new Matrix4d(projection).mul(new Matrix4d(camera.viewRotationMatrix));
		Matrix4d inv = new Matrix4d(pv).invert();
		return new FrameView(camera.pos, pv, inv, width, height,
			RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(), partialTick);
	}

	/**
	 * Camera-relative position of the pixel ({@code x}, {@code y}) of the final image (top row
	 * first) with depth {@code depth}, written to {@code out}. Returns false when degenerate.
	 */
	boolean unproject(int x, int y, float depth, double[] out) {
		double nx = (x + 0.5) * 2.0 / width - 1.0;
		double ny = 1.0 - (y + 0.5) * 2.0 / height;
		double nz = zZeroToOne ? depth : depth * 2.0 - 1.0;
		Matrix4d m = inverse;
		double w = m.m03() * nx + m.m13() * ny + m.m23() * nz + m.m33();
		if (Math.abs(w) < 1e-12) {
			return false;
		}
		out[0] = (m.m00() * nx + m.m10() * ny + m.m20() * nz + m.m30()) / w;
		out[1] = (m.m01() * nx + m.m11() * ny + m.m21() * nz + m.m31()) / w;
		out[2] = (m.m02() * nx + m.m12() * ny + m.m22() * nz + m.m32()) / w;
		return true;
	}

	/**
	 * Screen position in pixels (top row first) of a camera-relative point, or null when it is
	 * behind (or almost at) the camera.
	 */
	double[] project(double x, double y, double z) {
		Matrix4d m = projView;
		double w = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
		if (w < 0.01) {
			return null;
		}
		double nx = (m.m00() * x + m.m10() * y + m.m20() * z + m.m30()) / w;
		double ny = (m.m01() * x + m.m11() * y + m.m21() * z + m.m31()) / w;
		return new double[]{(nx + 1) * 0.5 * width, (1 - ny) * 0.5 * height};
	}
}
