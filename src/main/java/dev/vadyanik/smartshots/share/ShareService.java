package dev.vadyanik.smartshots.share;

import com.google.common.hash.Hashing;
import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMeta;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * "Share": uploads a shot (as it is on disk, edits and highlights included) and copies the link.
 * The link is remembered in the metadata and reused while the file is unchanged and the link
 * hasn't expired, so sharing the same shot twice doesn't upload it twice.
 */
public final class ShareService {
	/** A link that expires sooner than this is uploaded again. */
	private static final long EXPIRY_MARGIN_MS = 10 * 60_000L;
	private static final ExecutorService UPLOADS = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SmartShots Upload");
		t.setDaemon(true);
		return t;
	});
	private static final Map<Path, Boolean> BUSY = new ConcurrentHashMap<>();

	public record Result(boolean ok, String url, boolean reused, String error) {
	}

	private ShareService() {
	}

	public static boolean busy(Path file) {
		return BUSY.containsKey(file);
	}

	/**
	 * Uploads {@code file} (or reuses its link) on background threads. Runs after the IO queue, so
	 * pending edits are baked first. {@code done} gets the result on the main thread; with
	 * {@code copy} the link is put on the clipboard first.
	 */
	public static void share(Path file, ShotMeta meta, boolean copy, Consumer<Result> done) {
		if (BUSY.putIfAbsent(file, Boolean.TRUE) != null) {
			return;
		}
		String oldUrl = meta != null ? meta.shareUrl : null;
		String oldHash = meta != null ? meta.shareHash : null;
		long oldExpires = meta != null ? meta.shareExpires : 0;
		ScreenshotStore.IO.execute(() -> {
			byte[] png;
			String hash;
			try {
				png = Files.readAllBytes(file);
				hash = Hashing.sha1().hashBytes(png).toString();
			} catch (Exception e) {
				finish(file, meta, copy, done, new Result(false, null, false, e.getMessage()), null, 0);
				return;
			}
			if (oldUrl != null && hash.equals(oldHash)
				&& (oldExpires == 0 || oldExpires - System.currentTimeMillis() > EXPIRY_MARGIN_MS)) {
				finish(file, meta, copy, done, new Result(true, oldUrl, true, null), null, 0);
				return;
			}
			UPLOADS.execute(() -> {
				try {
					Uploader.Link link = Uploader.upload(png, file.getFileName().toString());
					finish(file, meta, copy, done, new Result(true, link.url(), false, null), hash, link.expires());
				} catch (Exception e) {
					if (e instanceof InterruptedException) {
						Thread.currentThread().interrupt();
					}
					SmartShots.LOGGER.warn("Couldn't upload {}", file, e);
					String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
					finish(file, meta, copy, done, new Result(false, null, false, msg), null, 0);
				}
			});
		});
	}

	private static void finish(Path file, ShotMeta meta, boolean copy, Consumer<Result> done, Result r, String hash, long expires) {
		if (r.ok() && !r.reused()) {
			String url = r.url();
			ScreenshotStore.updateMeta(file, m -> {
				m.shareUrl = url;
				m.shareHash = hash;
				m.shareExpires = expires;
			}, () -> {
			});
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			BUSY.remove(file);
			if (r.ok()) {
				if (meta != null && !r.reused()) {
					meta.shareUrl = r.url();
					meta.shareHash = hash;
					meta.shareExpires = expires;
				}
				if (copy) {
					mc.keyboardHandler.setClipboard(r.url());
				}
			}
			if (done != null) {
				done.accept(r);
			}
		});
	}

	/** Name of the configured host, for messages. */
	public static String serviceName() {
		SmartShotsConfig c = SmartShotsConfig.get();
		if (c.uploadService.host != null) {
			return c.uploadService.host;
		}
		try {
			String host = URI.create(c.customUploadUrl.strip()).getHost();
			return host != null ? host : "?";
		} catch (Exception e) {
			return "?";
		}
	}

	/** Chat line with the link: click opens it, the [copy] part copies it. */
	public static void announce(Result r) {
		Minecraft mc = Minecraft.getInstance();
		if (r.ok()) {
			MutableComponent link = Component.literal(r.url())
				.withStyle(ChatFormatting.UNDERLINE, ChatFormatting.AQUA)
				.withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(r.url())))
					.withHoverEvent(new HoverEvent.ShowText(Component.translatable("smartshots.share.open"))));
			MutableComponent copy = Component.translatable("smartshots.share.copy_chat")
				.withStyle(ChatFormatting.GRAY)
				.withStyle(s -> s.withClickEvent(new ClickEvent.CopyToClipboard(r.url()))
					.withHoverEvent(new HoverEvent.ShowText(Component.translatable("smartshots.share.copy_hover"))));
			mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("smartshots.share.chat", link, copy));
		} else {
			mc.gui.hud.getChat().addClientSystemMessage(
				Component.translatable("smartshots.share.failed", r.error()).withStyle(ChatFormatting.RED));
		}
	}

	/** Short status line for the action bar / viewer. */
	public static Component status(Result r, boolean copied) {
		if (!r.ok()) {
			return Component.translatable("smartshots.share.failed", r.error());
		}
		return Component.translatable(copied ? "smartshots.share.copied" : "smartshots.share.uploaded");
	}
}
