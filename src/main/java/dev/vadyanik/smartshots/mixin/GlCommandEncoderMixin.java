package dev.vadyanik.smartshots.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes black color readbacks on the OpenGL backend.
 *
 * <p>{@code GlCommandEncoder.copyTextureToBuffer} reads through one shared framebuffer object. For
 * a depth texture it sets that FBO's read buffer to {@code GL_NONE} and never sets it back, so
 * every later color readback ({@code Screenshot.takeScreenshot} included) fails with
 * "GL_INVALID_OPERATION: No color buffer" and leaves the buffer zeroed: a black image. Our mask
 * reads depth every shot, so this restores the read buffer before each color read.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
	@Shadow
	@Final
	private int readFbo;

	@Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
		require = 0,
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_readPixels(IIIIIIJ)V"))
	private void smartshots$restoreReadBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel,
		int x, int y, int width, int height, CallbackInfo ci) {
		if (!source.getFormat().hasDepthAspect()) {
			// The color texture is attachment 0 of the read FBO. Bind it for real first: the state
			// cache may skip the bind when another mod changed the binding behind its back.
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);
			GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
		}
	}
}
