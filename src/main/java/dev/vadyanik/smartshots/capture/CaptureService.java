package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.gui.ClipboardImage;

import dev.vadyanik.smartshots.share.ShareService;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ClipboardImage;
import dev.vadyanik.smartshots.gui.GalleryScreen;
import dev.vadyanik.smartshots.gui.ShotToast;
import dev.vadyanik.smartshots.share.ShareService;
import dev.vadyanik.smartshots.storage.Location;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMeta;
import dev.vadyanik.smartshots.storage.Trigger;
import dev.vadyanik.smartshots.storage.WorldContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes screenshots inside the frame that is being rendered, so that image, depth and scene
 * metadata describe exactly the same moment.
 *
 * <p>{@link #capture} only queues a request. During the next frame ({@code GameRendererMixin}):
 * <ul>
 *   <li>after the level is rendered, before the hand / HUD pass: the camera matrices, entity
 *   boxes and the depth buffer are captured and the scene is analyzed. "Clean" shots (interval
 *   shots with "hide HUD") also take their color here: world only, no hand, HUD or GUI, so the
 *   player never sees the HUD flicker;</li>
 *   <li>between that frame and the next one (next client tick or next frame start, whichever
 *   comes first; the main target then holds the finished frame, exactly what vanilla F2 reads):
 *   all other shots take the full frame, GUI included (the advancement toast stays on
 *   advancement shots). Clean shots read it too, as a fallback for when the in-frame image
 *   comes out black.</li>
 * </ul>
 * If the level isn't rendered in that frame the shot falls back to a plain capture without mask.
 */
public final class CaptureService {
	public static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(2500L);

	private static final List<Request> PENDING = new ArrayList<>();
	/** Frame finished; the color is read between frames (see {@link #readFinishedFrames}). */
	private static final List<Request> FINISHED = new ArrayList<>();
	private static boolean inFrame;
	/** Depth grabbed this frame before the "always on top" pass cleared it, or null. */
	private static EarlyDepth earlyDepth;
	private static volatile boolean warnedBlackClean;

	private CaptureService() {
	}

	private static final class Request {
		final ShotMeta meta;
		final Location location;
		final boolean clean;
		boolean analyzed;
		boolean colorTaken;
		MaskJob mask;
		// Clean shots: the in-frame image and the end-of-frame fallback, both arrive on the render thread.
		NativeImage cleanImage;
		NativeImage frameImage;
		boolean cleanArrived;
		boolean frameArrived;

		Request(ShotMeta meta, Location location, boolean clean) {
			this.meta = meta;
			this.location = location;
			this.clean = clean;
		}
	}

	/** True when we can sort the shot into a world folder. */
	public static boolean canCapture(Minecraft mc) {
		return mc.level != null && mc.player != null;
	}

	/** True while a requested shot waits for its frame. */
	public static boolean hasPending() {
		return !PENDING.isEmpty() || !FINISHED.isEmpty();
	}

	/**
	 * Capture the next rendered frame. Must be called on the render thread.
	 *
	 * @param advancements titles of the advancements this shot celebrates (may be empty)
	 */
	public static void capture(Minecraft mc, Trigger trigger, List<String> advancements, List<String> advancementIds) {
		if (!canCapture(mc)) {
			return;
		}
		ShotMeta meta = new ShotMeta();
		meta.trigger = trigger.id;
		meta.time = System.currentTimeMillis();
		meta.advancements.addAll(advancements);
		meta.advancementIds.addAll(advancementIds);

		Location location;
		try {
			location = WorldContext.resolve(mc);
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Couldn't resolve world folder", e);
			location = new Location(dev.vadyanik.smartshots.storage.Category.OTHER, ScreenshotStore.root(), "", ScreenshotStore.root(), "");
		}
		boolean clean = trigger == Trigger.AUTO && SmartShotsConfig.get().autoHideHud;
		if (!clean) {
			try {
				meta.chat = new ArrayList<>(ChatCapture.visible(mc));
			} catch (Exception e) {
				SmartShots.LOGGER.debug("Couldn't read the chat", e);
			}
		}
		PENDING.add(new Request(meta, location, clean));
	}

	/** A first-meeting shot ({@link PlayerMeet}): full frame, the met players recorded in the metadata. */
	static void captureMeeting(Minecraft mc, java.util.Map<java.util.UUID, String> met) {
		capture(mc, Trigger.NEW_PLAYER, List.of(), List.of());
		if (!PENDING.isEmpty() && PENDING.getLast().meta.trigger() == Trigger.NEW_PLAYER) {
			PlayerMeet.tag(PENDING.getLast().meta, met);
		}
	}

	/** After the scene analysis of a request. */
	private static void analyzed(Minecraft mc, Request r) {
		if (r.meta.trigger() == Trigger.NEW_PLAYER) {
			try {
				PlayerMeet.afterAnalysis(mc, r.meta);
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't record met players", e);
			}
		}
	}

	/** Main thread, every client tick. */
	public static void tick(Minecraft mc) {
		if (!inFrame) {
			readFinishedFrames(mc);
		}
		MaskJob.tick(mc);
	}

	// ------------------------------------------------------------------ frame hooks

	/** Start of {@code GameRenderer.render}, before the main target is cleared. */
	public static void onFrameStart() {
		readFinishedFrames(Minecraft.getInstance());
		inFrame = true;
		earlyDepth = null;
	}

	/**
	 * Inside the level's main pass, right before the "always on top" features (chunk borders,
	 * debug gizmos): that pass clears the depth buffer, so a pending shot reads it now.
	 */
	public static void beforeAlwaysOnTop(RenderTarget main) {
		if (!inFrame || earlyDepth != null || !SmartShotsConfig.get().sceneAnalysis) {
			return;
		}
		boolean waiting = false;
		for (Request r : PENDING) {
			waiting |= !r.analyzed;
		}
		if (!waiting || main == null || main.getDepthTexture() == null) {
			return;
		}
		EarlyDepth e = new EarlyDepth();
		try {
			GpuReadback.depth(main.getDepthTexture(), e::arrive);
			earlyDepth = e;
		} catch (Exception ex) {
			SmartShots.LOGGER.warn("Early depth capture failed", ex);
		}
	}

	/** A depth readback taken earlier in the frame; hands the result to whoever asks (render thread). */
	private static final class EarlyDepth {
		private final List<java.util.function.Consumer<float[]>> waiting = new ArrayList<>();
		private boolean arrived;
		private float[] depth;

		void arrive(float[] d) {
			arrived = true;
			depth = d;
			for (var c : waiting) {
				c.accept(d);
			}
			waiting.clear();
		}

		void then(java.util.function.Consumer<float[]> c) {
			if (arrived) {
				c.accept(depth);
			} else {
				waiting.add(c);
			}
		}
	}

	/** The level is rendered; the hand and HUD are not yet. {@code projection} is the one actually used (bobbing, nausea...). */
	public static void onLevelRendered(GameRenderer renderer, Matrix4f projection, CameraRenderState camera) {
		if (!inFrame || PENDING.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!canCapture(mc)) {
			return;
		}
		RenderTarget target = renderer.mainRenderTarget();
		for (Request r : PENDING) {
			if (r.analyzed) {
				continue;
			}
			r.analyzed = true;
			try {
				if (SmartShotsConfig.get().sceneAnalysis) {
					long start = System.nanoTime();
					r.mask = startMask(mc, renderer, projection, camera, target);
					SceneAnalyzer.analyze(mc, r.meta, r.mask != null);
					SmartShots.LOGGER.debug("Scene analysis took {} ms", (System.nanoTime() - start) / 1_000_000);
				} else {
					SceneAnalyzer.locate(mc, r.meta);
				}
				analyzed(mc, r);
				if (r.clean) {
					r.colorTaken = true;
					GpuReadback.color(target.getColorTexture(), image -> {
						if (!r.colorTaken) {
							// The read threw after all and the shot went the normal way: not needed.
							if (image != null) {
								image.close();
							}
							return;
						}
						r.cleanImage = image;
						r.cleanArrived = true;
						cleanReady(mc, r);
					});
				}
			} catch (Exception e) {
				SmartShots.LOGGER.warn("In-frame capture failed", e);
				r.colorTaken = false;
			}
		}
	}

	private static MaskJob startMask(Minecraft mc, GameRenderer renderer, Matrix4f projection, CameraRenderState camera, RenderTarget target) {
		try {
			FrameView view = FrameView.of(projection, camera, target.width, target.height,
				renderer.gameRenderState().levelRenderState.worldPartialTicks);
			MaskJob job = new MaskJob(mc.level, view, EntitySnapshot.take(mc, view), mc.level.dimensionType().hasSkyLight());
			if (earlyDepth != null) {
				earlyDepth.then(job::onDepth);
			} else {
				GpuReadback.depth(target.getDepthTexture(), job::onDepth);
			}
			return job;
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Depth capture failed, the shot gets no mask", e);
			return null;
		}
	}

	/** End of the frame: everything (GUI included) is in the main target. */
	public static void onFrameEnd(GameRenderer renderer) {
		inFrame = false;
		earlyDepth = null;
		if (PENDING.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		List<Request> requests = new ArrayList<>(PENDING);
		PENDING.clear();
		for (Request r : requests) {
			if (!r.analyzed) {
				try {
					// The level wasn't rendered in this frame: plain capture, no mask.
					if (SmartShotsConfig.get().sceneAnalysis) {
						SceneAnalyzer.analyze(mc, r.meta, false);
					} else {
						SceneAnalyzer.locate(mc, r.meta);
					}
				} catch (Exception e) {
					SmartShots.LOGGER.warn("Scene analysis failed", e);
				}
				analyzed(mc, r);
			}
			FINISHED.add(r);
		}
		// Our own toasts were hidden for the shot (see CaptureScheduler); the frame is complete now.
		ShotToast.suppressed = false;
	}

	/**
	 * Between frames: the main target holds the last finished frame, untouched until the next
	 * frame clears it. Reading here is what vanilla's screenshot key does.
	 */
	private static void readFinishedFrames(Minecraft mc) {
		if (FINISHED.isEmpty()) {
			return;
		}
		List<Request> requests = new ArrayList<>(FINISHED);
		FINISHED.clear();
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		for (Request r : requests) {
			boolean fallback = r.clean && r.colorTaken;
			try {
				GpuReadback.color(target.getColorTexture(), image -> {
					if (fallback) {
						r.frameImage = image;
						r.frameArrived = true;
						cleanReady(mc, r);
					} else {
						onImage(mc, r, image);
					}
				});
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Capture failed", e);
				if (fallback) {
					r.frameArrived = true;
					cleanReady(mc, r);
				} else if (r.mask != null) {
					r.mask.cancel();
				}
			}
		}
	}

	/**
	 * Render thread, clean shots: once both images are in, keep the clean one unless it came out
	 * black while the end-of-frame one didn't (a rendering mod interfering with the in-frame read).
	 */
	private static void cleanReady(Minecraft mc, Request r) {
		if (!r.cleanArrived || !r.frameArrived) {
			return;
		}
		NativeImage clean = r.cleanImage;
		NativeImage frame = r.frameImage;
		r.cleanImage = null;
		r.frameImage = null;
		ScreenshotStore.IO.execute(() -> {
			NativeImage chosen = clean;
			if (clean == null || frame != null && GpuReadback.isBlack(clean) && !GpuReadback.isBlack(frame)) {
				chosen = frame;
				if (clean != null && !warnedBlackClean) {
					warnedBlackClean = true;
					SmartShots.LOGGER.warn("The HUD-free capture came out black; using the end-of-frame image instead (HUD and hand may be visible on such shots)");
				}
			}
			if (chosen != clean && clean != null) {
				clean.close();
			}
			if (chosen != frame && frame != null) {
				frame.close();
			}
			save(mc, r, chosen);
		});
	}

	/** Render thread: the color readback arrived (null when it failed). */
	private static void onImage(Minecraft mc, Request r, NativeImage image) {
		ScreenshotStore.IO.execute(() -> save(mc, r, image));
	}

	/** IO thread. */
	private static void save(Minecraft mc, Request r, NativeImage image) {
		if (image == null) {
			if (r.mask != null) {
				r.mask.cancel();
			}
			mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(
				Component.translatable("screenshot.failure", "readback failed")));
			return;
		}
		GpuReadback.opaque(image);
		SmartShotsConfig cfg = SmartShotsConfig.get();
		int maxHeight = r.meta.trigger() == Trigger.AUTO ? cfg.autoMaxHeight : cfg.manualMaxHeight;
		ScreenshotStore.save(r.location, image, r.meta, maxHeight,
			saved -> {
				if (r.mask != null) {
					// The PNG may be smaller than the frame the mask is built from.
					r.mask.onSaved(saved.file(), saved.meta().width, saved.meta().height);
				}
				mc.execute(() -> onSaved(mc, saved));
			},
			error -> {
				if (r.mask != null) {
					r.mask.cancel();
				}
				mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(
					Component.translatable("screenshot.failure", error.getMessage())));
			});
	}

	/** The shot saved last in this session (the "copy last screenshot" key copies it). */
	public static volatile Path lastSaved;
	/** Metadata of {@link #lastSaved} (the share key remembers the link in it). */
	public static volatile ShotMeta lastSavedMeta;

	private static void onSaved(Minecraft mc, ScreenshotStore.Saved saved) {
		GalleryScreen.onScreenshotAdded();
		lastSaved = saved.file();
		lastSavedMeta = saved.meta();
		dev.vadyanik.smartshots.integration.XaeroBridge.refresh();
		Trigger trigger = saved.meta().trigger();
		SmartShotsConfig.Notify notify = switch (trigger) {
			case MANUAL -> SmartShotsConfig.get().manualNotify;
			case ADVANCEMENT, NEW_PLAYER -> SmartShotsConfig.get().autoNotify;
			default -> SmartShotsConfig.Notify.NONE; // interval shots are silent
		};
		Path file = saved.file();
		if (trigger == Trigger.MANUAL) {
			afterManualShot(mc, file, saved.meta());
		}
		String where = saved.location().instanceName() == null || saved.location().instanceName().isEmpty()
			? "" : saved.location().instanceName() + " › " + saved.location().locationName();
		switch (notify) {
			case TOAST -> {
				mc.gui.toastManager().addToast(new ShotToast(saved.preview(), saved.meta(), where));
				return;
			}
			case CHAT -> {
				MutableComponent name = Component.literal(file.getFileName().toString())
					.withStyle(ChatFormatting.UNDERLINE)
					.withStyle(s -> s.withClickEvent(new ClickEvent.OpenFile(file.toAbsolutePath()))
						.withHoverEvent(new HoverEvent.ShowText(Component.translatable("smartshots.chat.open_file"))));
				MutableComponent folder = Component.literal(where)
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
					.withStyle(s -> s.withClickEvent(new ClickEvent.OpenFile(file.getParent().toAbsolutePath()))
						.withHoverEvent(new HoverEvent.ShowText(Component.translatable("smartshots.chat.open_folder"))));
				Component msg = trigger == Trigger.MANUAL
					? Component.translatable("smartshots.chat.saved", name, folder)
					: Component.translatable("smartshots.chat.saved_auto", trigger.label(), name, folder);
				mc.gui.hud.getChat().addClientSystemMessage(msg);
			}
			case NONE -> {
			}
		}
		saved.preview().close();
	}

	/** Clipboard and upload actions for a fresh F2 shot, as configured. */
	private static void afterManualShot(Minecraft mc, Path file, ShotMeta meta) {
		SmartShotsConfig cfg = SmartShotsConfig.get();
		java.util.function.Consumer<Boolean> copied = ok -> mc.gui.hud.setOverlayMessage(
			Component.translatable(ok ? "smartshots.copy.ok" : "smartshots.copy.fail"), false);
		switch (cfg.copyOnCapture) {
			case IMAGE -> ClipboardImage.copy(file, copied);
			case FILE -> ClipboardImage.copyFile(file, ok -> mc.gui.hud.setOverlayMessage(
				Component.translatable(ok ? "smartshots.copy.file_ok" : "smartshots.copy.fail"), false));
			default -> {
			}
		}
		boolean link = cfg.copyOnCapture == SmartShotsConfig.CopyOnCapture.LINK;
		if (link || cfg.autoUploadManual) {
			ShareService.share(file, meta, link, r -> {
				ShareService.announce(r);
				if (link) {
					mc.gui.hud.setOverlayMessage(ShareService.status(r, true), false);
				}
			});
		}
	}
}
