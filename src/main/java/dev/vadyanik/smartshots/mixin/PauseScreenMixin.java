package dev.vadyanik.smartshots.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.GalleryScreen;
import dev.vadyanik.smartshots.gui.Icons;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) {
		super(title);
	}

	/** Adds a small square camera button to the icon row (bugs / feedback / friends / reporting). */
	@Inject(method = "createPauseMenu",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/CommonComponents;disconnectButtonLabel(Z)Lnet/minecraft/network/chat/Component;"))
	private void smartshots$addButton(CallbackInfo ci, @Local LinearLayout iconButtonRow) {
		if (!SmartShotsConfig.get().pauseMenuButton) {
			return;
		}
		Screen self = this;
		iconButtonRow.addChild(Icons.button(Icons.CAMERA, Component.translatable("smartshots.button.gallery"),
			b -> this.minecraft.gui.setScreen(new GalleryScreen(self))));
	}
}
