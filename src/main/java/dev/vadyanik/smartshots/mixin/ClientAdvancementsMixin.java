package dev.vadyanik.smartshots.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.vadyanik.smartshots.capture.CaptureScheduler;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientAdvancements.class)
public abstract class ClientAdvancementsMixin {
	/** Fires exactly when the game shows an "Advancement made!" toast. */
	@Inject(method = "update",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V"))
	private void smartshots$onAdvancement(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci, @Local AdvancementNode node) {
		node.advancement().display().ifPresent(display ->
			CaptureScheduler.onAdvancement(display.title().getString(), node.holder().id().toString()));
	}
}
