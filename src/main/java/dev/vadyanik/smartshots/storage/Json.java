package dev.vadyanik.smartshots.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.vadyanik.smartshots.SmartShots;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Json {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final Type SHOT_MAP = new TypeToken<LinkedHashMap<String, ShotMeta>>() {
	}.getType();

	private Json() {
	}

	public static <T> T read(Path path, Type type) {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return GSON.fromJson(r, type);
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Failed to read {}", path, e);
			return null;
		}
	}

	/** Atomic-ish write: write a temp file and move it over the target. */
	public static void write(Path path, Object value) {
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(value, w);
			}
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Failed to write {}", path, e);
		}
	}

	public static Map<String, ShotMeta> readShots(Path dir) {
		Map<String, ShotMeta> map = read(dir.resolve(ScreenshotStore.SHOTS_FILE), SHOT_MAP);
		return map != null ? map : new LinkedHashMap<>();
	}
}
