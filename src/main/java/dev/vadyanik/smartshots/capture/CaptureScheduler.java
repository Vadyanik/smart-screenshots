package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ShotToast;
import dev.vadyanik.smartshots.storage.Trigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives automatic shots: interval "memories", advancement shots and first meetings ({@link PlayerMeet}). Interval shots with "hide
 * HUD" are taken from the world pass of a frame (see {@link CaptureService}), so nothing on screen
 * changes for the player.
 */
public final class CaptureScheduler {
	private static Pending pending;
	private static int activeTicks;
	private static Vec3 lastAutoPos;
	private static float lastAutoYaw;
	private static String lastAutoDim;

	private CaptureScheduler() {
	}

	private static final class Pending {
		final Trigger trigger;
		int delay;
		final List<String> titles = new ArrayList<>();
		final List<String> ids = new ArrayList<>();

		Pending(Trigger trigger, int delay) {
			this.trigger = trigger;
			this.delay = delay;
		}
	}

	/** Called by the advancement mixin whenever an advancement toast pops up. */
	public static void onAdvancement(String title, String id) {
		if (!SmartShotsConfig.get().advancementEnabled) {
			return;
		}
		if (pending != null && pending.trigger == Trigger.ADVANCEMENT) {
			// Several advancements at once -> one shot that lists all of them.
			if (!pending.ids.contains(id)) {
				pending.titles.add(title);
				pending.ids.add(id);
			}
			return;
		}
		Pending p = new Pending(Trigger.ADVANCEMENT, SmartShotsConfig.get().advancementDelayTicks);
		p.titles.add(title);
		p.ids.add(id);
		pending = p; // advancement shots win over a pending interval shot
	}

	/** F2: capture now, or after a 2-tick pause if our own toasts are on screen (they would be in the shot). */
	public static void requestManual(Minecraft mc) {
		if (!ShotToast.anyVisible()) {
			CaptureService.capture(mc, Trigger.MANUAL, List.of(), List.of());
			return;
		}
		if (pending == null || pending.trigger != Trigger.ADVANCEMENT) {
			pending = new Pending(Trigger.MANUAL, 2);
		}
	}

	public static void tick(Minecraft mc) {
		CaptureService.tick(mc);
		if (!CaptureService.canCapture(mc)) {
			pending = null;
			ShotToast.suppressed = false;
			activeTicks = 0;
			PlayerMeet.reset();
			return;
		}
		tickAuto(mc);
		// A pending advancement / F2 shot goes first; the meeting shot waits for it.
		PlayerMeet.tick(mc, pending != null && pending.trigger != Trigger.AUTO);

		if (pending == null) {
			return;
		}
		if (pending.trigger == Trigger.AUTO) {
			// Don't take "memories" of menus; try again when the player is back in game.
			if (mc.gui.screen() == null && !mc.isPaused() && --pending.delay <= 0) {
				pending = null;
				CaptureService.capture(mc, Trigger.AUTO, List.of(), List.of());
			}
			return;
		}
		pending.delay--;
		// Hide our own toasts for the last ticks so they aren't on the shot. CaptureService turns
		// them back on once the frame is grabbed.
		ShotToast.suppressed = pending.delay <= 2;
		if (pending.delay <= 0) {
			Pending p = pending;
			pending = null;
			CaptureService.capture(mc, p.trigger, p.titles, p.ids);
			if (!CaptureService.hasPending()) {
				ShotToast.suppressed = false;
			}
		}
	}

	private static void tickAuto(Minecraft mc) {
		SmartShotsConfig cfg = SmartShotsConfig.get();
		if (!cfg.autoEnabled) {
			activeTicks = 0;
			return;
		}
		// Count only time actually spent playing (no menus, window focused).
		if (mc.gui.screen() != null || mc.isPaused() || !mc.isWindowActive()) {
			return;
		}
		activeTicks++;
		if (activeTicks < cfg.autoIntervalMinutes * 60 * 20) {
			return;
		}
		activeTicks = 0;
		if (pending != null) {
			return;
		}
		Vec3 pos = mc.player.position();
		String dim = mc.level.dimension().identifier().toString();
		if (cfg.autoSkipIdle && lastAutoPos != null && dim.equals(lastAutoDim)
			&& pos.distanceTo(lastAutoPos) < 4.0 && Math.abs(wrap(mc.player.getYRot() - lastAutoYaw)) < 20) {
			return; // nothing new to remember
		}
		lastAutoPos = pos;
		lastAutoYaw = mc.player.getYRot();
		lastAutoDim = dim;
		pending = new Pending(Trigger.AUTO, 1);
	}

	private static float wrap(float deg) {
		deg %= 360;
		if (deg >= 180) {
			deg -= 360;
		}
		if (deg < -180) {
			deg += 360;
		}
		return deg;
	}

	/** Seconds until the next interval shot, or -1 when disabled. */
	public static int secondsUntilAuto() {
		SmartShotsConfig cfg = SmartShotsConfig.get();
		if (!cfg.autoEnabled) {
			return -1;
		}
		return Math.max(0, (cfg.autoIntervalMinutes * 60 * 20 - activeTicks) / 20);
	}

	public static void notifyAutoToggled(Minecraft mc, boolean enabled) {
		SystemToast.addOrUpdate(mc.gui.toastManager(), CaptureService.TOAST_ID,
			Component.translatable(enabled ? "smartshots.toast.auto_on" : "smartshots.toast.auto_off"),
			enabled ? Component.translatable("smartshots.toast.auto_every", SmartShotsConfig.get().autoIntervalMinutes) : null);
	}
}
