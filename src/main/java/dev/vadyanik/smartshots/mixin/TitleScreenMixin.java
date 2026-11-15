package dev.vadyanik.smartshots.mixin;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.GalleryScreen;
import dev.vadyanik.smartshots.gui.Icons;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	/** Lay the small icon row out for four buttons instead of three. */
	@ModifyArg(method = "init", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/TitleScreen;getHorizontalPosition(III)I"), index = 1)
	private int smartshots$fourIcons(int numberOfButtons) {
		return SmartShotsConfig.get().pauseMenuButton ? numberOfButtons + 1 : numberOfButtons;
	}

	/** Add the gallery button right after the accessibility button. */
	@Inject(method = "init", at = @At("TAIL"))
	private void smartshots$addGalleryButton(CallbackInfo ci) {
		if (!SmartShotsConfig.get().pauseMenuButton) {
			return;
		}
		Component accessibility = Component.translatable("options.accessibility");
		SpriteIconButton anchor = null;
		for (GuiEventListener child : this.children()) {
			if (child instanceof SpriteIconButton b && b.getMessage().equals(accessibility)) {
				anchor = b;
			}
		}
		if (anchor == null) {
			return;
		}
		Screen self = this;
		SpriteIconButton button = Icons.button(Icons.CAMERA, Component.translatable("smartshots.button.gallery_short"),
			b -> this.minecraft.gui.setScreen(new GalleryScreen(self)));
		button.setPosition(anchor.getX() + anchor.getWidth() + 4, anchor.getY());
		this.addRenderableWidget(button);
	}
}
