package dev.vadyanik.smartshots.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vadyanik.smartshots.capture.CaptureService;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The "always on top" pass (chunk borders, debug gizmos) clears the main depth buffer before it
 * draws. Grab the depth of the world right before that, or the mask only sees those lines.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "executeAlwaysOnTop", require = 0, at = @At("HEAD"))
	private void smartshots$beforeAlwaysOnTop(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) RenderTarget mainTarget) {
		CaptureService.beforeAlwaysOnTop(mainTarget);
	}
}
