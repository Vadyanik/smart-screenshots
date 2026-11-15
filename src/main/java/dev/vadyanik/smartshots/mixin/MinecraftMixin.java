package dev.vadyanik.smartshots.mixin;

import dev.vadyanik.smartshots.capture.CaptureScheduler;
import dev.vadyanik.smartshots.capture.CaptureService;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.storage.Trigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** Route the screenshot key (F2) through our organizer when in a world. */
	@Redirect(method = "handleGlobalKeyPress",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Screenshot;grab(Lnet/minecraft/client/Minecraft;Z)V"))
	private void smartshots$grab(Minecraft mc, boolean debugPanorama) {
		if (!debugPanorama && SmartShotsConfig.get().organize && CaptureService.canCapture(mc)) {
			CaptureScheduler.requestManual(mc);
		} else {
			Screenshot.grab(mc, debugPanorama);
		}
	}
}
