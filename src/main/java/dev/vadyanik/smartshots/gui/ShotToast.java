package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.Trigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.sounds.SoundEvent;
import org.jspecify.annotations.Nullable;


/**
 * "Screenshot saved" notification: just a big preview of the shot, no text. Size (share of the screen
 * area) and display time come from the config. Several toasts stack like vanilla ones.
 */
public class ShotToast implements Toast {
	/** Transparent gap below the toast so stacked toasts don't touch. */
	private static final int GAP = 4;

	private @Nullable DynamicTexture texture;
	private final int w;
	private final int h;
	private final int imgH;
	private final Trigger trigger;
	private final long displayMs;
	private Visibility visibility = Visibility.SHOW;
	private boolean released;

	/** Number of our toasts currently on screen. */
	private static int onScreen;
	/** While true our toasts draw nothing, so they don't end up on the next screenshot. */
	public static boolean suppressed;

	public static boolean anyVisible() {
		return onScreen > 0;
	}

	/** Takes ownership of {@code preview}. Must be created on the render thread. {@code where} is unused. */
	public ShotToast(@Nullable NativeImage preview, ShotMeta meta, String where) {
		this.trigger = meta.trigger();
		this.displayMs = Math.round(SmartShotsConfig.get().toastSeconds * 1000);
		var window = Minecraft.getInstance().getWindow();
		int screenW = window.getGuiScaledWidth();
		int screenH = window.getGuiScaledHeight();
		float aspect = preview != null ? preview.getWidth() / (float) Math.max(1, preview.getHeight())
			: screenW / (float) Math.max(1, screenH);
		// w * h = area * screenW * screenH with h = w / aspect
		double area = SmartShotsConfig.get().toastSizePercent / 100.0;
		int pw = (int) Math.round(Math.sqrt(area * screenW * screenH * aspect));
		pw = Math.clamp(pw, 80, Math.max(80, screenW - 8));
		int ph = Math.max(20, Math.round(pw / aspect));
		// The toast manager stacks in 32px slots; round the height to whole slots and keep a gap.
		int slots = Math.max(1, (ph + GAP + Toast.SLOT_HEIGHT / 2) / Toast.SLOT_HEIGHT);
		this.h = slots * Toast.SLOT_HEIGHT;
		this.imgH = h - GAP;
		this.w = Math.max(40, Math.round(imgH * aspect));
		if (preview != null) {
			this.texture = new DynamicTexture(() -> "smartshots toast", preview);
		}
		onScreen++;
	}

	@Override
	public Visibility getWantedVisibility() {
		return visibility;
	}

	@Override
	public void update(ToastManager manager, long fullyVisibleForMs) {
		if (fullyVisibleForMs >= displayMs * manager.getNotificationDisplayTimeMultiplier()) {
			visibility = Visibility.HIDE;
		}
	}

	@Override
	public @Nullable SoundEvent getSoundEvent() {
		return null;
	}

	@Override
	public int width() {
		return w;
	}

	@Override
	public int height() {
		return h;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, Font font, long fullyVisibleForMs) {
		if (suppressed) {
			return;
		}
		Ui.rect(g, 0, 0, w, imgH, 0xF015171C);
		if (texture != null && !released) {
			g.blit(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
				1, 1, w - 1, imgH - 1, 0, 1, 0, 1);
		}
		Ui.border(g, 0, 0, w, imgH, Theme.BORDER_STRONG);
		// Remaining time, in the color of the shot type
		float p = Math.min(1f, fullyVisibleForMs / (float) (displayMs * Minecraft.getInstance().gui.toastManager().getNotificationDisplayTimeMultiplier()));
		g.fill(1, imgH - 3, 1 + Math.round((w - 2) * (1 - p)), imgH - 1, Theme.alpha(trigger.color, 0.85f));
	}

	@Override
	public void onFinishedRendering() {
		release();
	}

	/** Free GPU memory; called once the toast has left the screen. */
	void release() {
		if (!released) {
			released = true;
			onScreen = Math.max(0, onScreen - 1);
			if (texture != null) {
				texture.close();
				texture = null;
			}
		}
	}
}
