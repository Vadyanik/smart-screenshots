package dev.vadyanik.smartshots.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.vadyanik.smartshots.capture.CaptureService;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Services pending screenshot requests inside the frame (see {@link CaptureService}). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void smartshots$frameStart(CallbackInfo ci) {
		CaptureService.onFrameStart();
	}

	/**
	 * World rendered, depth still intact: the hand pass clears it right after. Optional: if a
	 * rendering mod replaces this method, shots fall back to end-of-frame capture without mask.
	 */
	@Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void smartshots$levelRendered(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		CaptureService.onLevelRendered((GameRenderer) (Object) this, projectionMatrix, cameraState);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void smartshots$frameEnd(CallbackInfo ci) {
		CaptureService.onFrameEnd((GameRenderer) (Object) this);
	}
}
