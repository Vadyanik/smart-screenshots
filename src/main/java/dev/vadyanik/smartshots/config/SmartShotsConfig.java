package dev.vadyanik.smartshots.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.storage.ImageOps;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plain JSON config stored in config/smartshots.json. */
public final class SmartShotsConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static SmartShotsConfig instance = new SmartShotsConfig();

	/** Sort screenshots into singleplayer/multiplayer/world folders. */
	public boolean organize = true;
	/** In singleplayer, keep separate folders for Overworld / Nether / End. */
	public boolean separateDimensions = true;

	public boolean autoEnabled = true;
	public int autoIntervalMinutes = 10;
	public boolean autoHideHud = true;
	/** Skip an interval shot when the player has barely moved since the last one. */
	public boolean autoSkipIdle = true;

	public boolean advancementEnabled = true;
	/** Delay so the advancement toast is visible on the shot. */
	public int advancementDelayTicks = 25;

	/** Take a shot the first time ever another player is properly in view (see capture.PlayerMeet). */
	public boolean meetEnabled = true;
	/** After the first newcomer is in frame, wait this long so others arriving together get on the same shot. */
	public float meetGatherSeconds = 1.5f;
	/** Min time between two meeting shots; newcomers seen meanwhile share one shot after it. */
	public int meetCooldownSeconds = 30;

	/** Worlds / servers / sub-worlds where new players are ignored (see storage.MeetBlacklist). */
	public java.util.List<String> meetBlacklist = new java.util.ArrayList<>();
	/** Ids of the built-in blocked places (server lobbies) the user turned back on. */
	public java.util.List<String> meetBuiltinOff = new java.util.ArrayList<>();

	/** Record visible blocks / entities / players for search. */
	public boolean sceneAnalysis = true;

	/** Show interval ("auto") shots in the gallery. Off by default: they only appear via the gallery toggle or type:auto. */
	public boolean galleryShowAuto = false;

	/** NONE, TOAST or CHAT for advancement and meeting shots. Interval (auto) shots never notify. */
	public Notify autoNotify = Notify.TOAST;
	/** Preview toast size: share of the screen area, in percent. */
	public float toastSizePercent = 12.5f;
	/** How long the preview toast stays on screen, in seconds. */
	public float toastSeconds = 4.5f;

	/** How to report F2 screenshots. */
	public Notify manualNotify = Notify.TOAST;

	public boolean pauseMenuButton = true;

	/** Max height of F2 and advancement shots; 0 = full frame size. One of {@link ImageOps#HEIGHTS}. */
	public int manualMaxHeight = 0;
	/** Max height of interval shots; 0 = full frame size. */
	public int autoMaxHeight = 1080;

	/** Shrink and recompress old shots in the background (favorites are never touched). */
	public boolean archiveEnabled = false;
	public int archiveAfterDays = 30;
	/** Max height of archived shots; 0 = keep the size, only recompress. */
	public int archiveMaxHeight = 1080;
	/** Archive interval shots only (not F2 / advancement / foreign shots). */
	public boolean archiveAutoOnly = true;

	/** Where "Share" uploads to. */
	public UploadService uploadService = UploadService.CATBOX;
	/** Litterbox link lifetime in hours: 1, 12, 24 or 72. */
	public int litterboxHours = 72;
	/** Custom uploader (ShareX-style): multipart POST of the image to this URL. */
	public String customUploadUrl = "";
	/** Form field that carries the file. */
	public String customUploadField = "file";
	/** Extra headers, "Name: value" separated by ";" or new lines (e.g. Authorization: Client-ID ...). */
	public String customUploadHeaders = "";
	/** Dot path to the link in a JSON response (e.g. data.link); empty = the first URL in the response. */
	public String customUploadJsonPath = "";
	/** Upload F2 shots right after they are taken. */
	public boolean autoUploadManual = false;
	/** What goes to the clipboard right after an F2 shot. */
	public CopyOnCapture copyOnCapture = CopyOnCapture.OFF;
	/** The user agreed that "Share" puts images on a public host (asked once). */
	public boolean shareConfirmed = false;

	/** Editor caption template ({date}, {time}, {x}, {y}, {z}, {world}, {dimension}, {biome}, {player}). */
	public String captionTemplate = "{date} {time} · {world} · {x} {y} {z} · {biome}";
	/** Caption corner: 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
	public int captionCorner = 3;

	public enum Notify { NONE, TOAST, CHAT }

	public enum CopyOnCapture { OFF, IMAGE, FILE, LINK }

	public enum UploadService {
		CATBOX("catbox.moe"), LITTERBOX("litterbox"), ZEROX0("0x0.st"), CUSTOM(null);

		public final String host;

		UploadService(String host) {
			this.host = host;
		}
	}

	public static SmartShotsConfig get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("smartshots.json");
	}

	public static void load() {
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				SmartShotsConfig loaded = GSON.fromJson(r, SmartShotsConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Failed to read config, using defaults", e);
			}
		}
		instance.sanitize();
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Failed to save config", e);
		}
	}

	/** The closest allowed height choice (0 stays "original"). */
	private static int snapHeight(int h) {
		if (h <= 0) {
			return 0;
		}
		int best = ImageOps.HEIGHTS[1];
		for (int c : ImageOps.HEIGHTS) {
			if (c > 0 && Math.abs(c - h) < Math.abs(best - h)) {
				best = c;
			}
		}
		return best;
	}

	public void sanitize() {
		autoIntervalMinutes = Math.clamp(autoIntervalMinutes, 1, 120);
		advancementDelayTicks = Math.clamp(advancementDelayTicks, 0, 100);
		toastSizePercent = Math.clamp(toastSizePercent, 3f, 30f);
		toastSeconds = Math.clamp(toastSeconds, 1f, 15f);
		manualMaxHeight = snapHeight(manualMaxHeight);
		autoMaxHeight = snapHeight(autoMaxHeight);
		archiveMaxHeight = snapHeight(archiveMaxHeight);
		archiveAfterDays = Math.clamp(archiveAfterDays, 7, 365);
		meetGatherSeconds = Math.clamp(meetGatherSeconds, 0f, 5f);
		meetCooldownSeconds = Math.clamp(meetCooldownSeconds, 0, 300);
		if (meetBlacklist == null) {
			meetBlacklist = new java.util.ArrayList<>();
		}
		if (meetBuiltinOff == null) {
			meetBuiltinOff = new java.util.ArrayList<>();
		}
		if (autoNotify == null) {
			autoNotify = Notify.TOAST;
		}
		if (manualNotify == null) {
			manualNotify = Notify.TOAST;
		}
		if (uploadService == null) {
			uploadService = UploadService.CATBOX;
		}
		if (copyOnCapture == null) {
			copyOnCapture = CopyOnCapture.OFF;
		}
		if (litterboxHours != 1 && litterboxHours != 12 && litterboxHours != 24) {
			litterboxHours = 72;
		}
		captionTemplate = captionTemplate == null ? "" : captionTemplate;
		captionCorner = Math.clamp(captionCorner, 0, 3);
		customUploadUrl = customUploadUrl == null ? "" : customUploadUrl.strip();
		customUploadField = customUploadField == null || customUploadField.isBlank() ? "file" : customUploadField.strip();
		customUploadHeaders = customUploadHeaders == null ? "" : customUploadHeaders;
		customUploadJsonPath = customUploadJsonPath == null ? "" : customUploadJsonPath.strip();
	}
}
