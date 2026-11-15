package dev.vadyanik.smartshots;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vadyanik.smartshots.capture.CaptureScheduler;
import dev.vadyanik.smartshots.capture.CaptureService;
import dev.vadyanik.smartshots.gui.ClipboardImage;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.GalleryScreen;
import dev.vadyanik.smartshots.gui.ShareConfirmScreen;
import dev.vadyanik.smartshots.share.ShareService;
import dev.vadyanik.smartshots.storage.ShotArchiver;
import dev.vadyanik.smartshots.track.MovementLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartShots implements ClientModInitializer {
	public static final String MOD_ID = "smartshots";
	public static final Logger LOGGER = LoggerFactory.getLogger("Smart Screenshots");

	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("main"));
	public static KeyMapping openGalleryKey;
	public static KeyMapping toggleAutoKey;
	public static KeyMapping copyLastKey;
	public static KeyMapping shareLastKey;

	@Override
	public void onInitializeClient() {
		SmartShotsConfig.load();

		openGalleryKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.smartshots.gallery", InputConstants.KEY_G, CATEGORY));
		toggleAutoKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.smartshots.toggle_auto", InputConstants.UNKNOWN.getValue(), CATEGORY));
		copyLastKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.smartshots.copy_last", InputConstants.UNKNOWN.getValue(), CATEGORY));

		shareLastKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.smartshots.share_last", InputConstants.UNKNOWN.getValue(), CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(SmartShots::onTick);
		ShotArchiver.start();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MovementLog.shutdown());
	}

	private static void onTick(Minecraft mc) {
		while (openGalleryKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new GalleryScreen(null));
			}
		}
		while (toggleAutoKey.consumeClick()) {
			SmartShotsConfig cfg = SmartShotsConfig.get();
			cfg.autoEnabled = !cfg.autoEnabled;
			SmartShotsConfig.save();
			CaptureScheduler.notifyAutoToggled(mc, cfg.autoEnabled);
		}
		while (copyLastKey.consumeClick()) {
			java.nio.file.Path last = CaptureService.lastSaved;
			if (last == null) {
				mc.gui.hud.setOverlayMessage(Component.translatable("smartshots.copy.none"), false);
				continue;
			}
			ClipboardImage.copy(last, ok -> mc.gui.hud.setOverlayMessage(
				Component.translatable(ok ? "smartshots.copy.ok" : "smartshots.copy.fail"), false));
		}
		while (shareLastKey.consumeClick()) {
			java.nio.file.Path last = CaptureService.lastSaved;
			if (last == null) {
				mc.gui.hud.setOverlayMessage(Component.translatable("smartshots.copy.none"), false);
				continue;
			}
			ShareConfirmScreen.confirmThen(mc.gui.screen(), () -> {
				mc.gui.hud.setOverlayMessage(Component.translatable("smartshots.share.uploading", ShareService.serviceName()), false);
				ShareService.share(last, CaptureService.lastSavedMeta, true, r -> {
					mc.gui.hud.setOverlayMessage(ShareService.status(r, true), false);
					ShareService.announce(r);
				});
			});
		}
		CaptureScheduler.tick(mc);
		try {
			dev.vadyanik.smartshots.integration.XaeroBridge.tick(mc);
		} catch (Throwable e) {
			LOGGER.debug("Xaero integration failed", e);
		}
		try {
			MovementLog.tick(mc);
		} catch (Exception e) {
			LOGGER.warn("Movement log failed", e);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
