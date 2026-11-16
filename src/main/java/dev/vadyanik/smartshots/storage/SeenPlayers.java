package dev.vadyanik.smartshots.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vadyanik.smartshots.SmartShots;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Players the user has already met, shared by every world, server and launcher instance: the file
 * lives in a per-user data folder outside the game directory ({@link #dir()}).
 *
 * <p>Several game instances may run at once. Writes take an OS file lock, re-read the file and
 * add only what this instance met since (union), then replace the file atomically; reads need no
 * lock. Because only new entries are added on top of the current file, "forget" from one instance
 * isn't undone by another instance's in-memory copy.
 *
 * <p>A player counts as met when either the UUID or the nickname (case-insensitive) is known:
 * offline-mode servers derive UUIDs from names, online ones don't.
 */
public final class SeenPlayers {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String FILE = "seen-players.json";
	private static final String LOCK = "seen-players.lock";

	/** Disk work (lock waits included) never runs on the game thread. */
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SmartShots seen players");
		t.setDaemon(true);
		return t;
	});

	/** Snapshot from disk plus our own additions; replaced as a whole (readers on the game thread). */
	private static volatile Known known;
	/** Met but not yet written; filled on the game thread, drained by the IO thread. */
	private static final Map<UUID, String> UNSAVED = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile FileTime lastStamp;
	private static volatile boolean refreshQueued;

	private SeenPlayers() {
	}

	private record Known(Set<UUID> uuids, Set<String> names) {
		boolean has(UUID uuid, String name) {
			return uuids.contains(uuid) || name != null && names.contains(name.toLowerCase(Locale.ROOT));
		}

		Known plus(UUID uuid, String name) {
			Set<UUID> u = new HashSet<>(uuids);
			Set<String> n = new HashSet<>(names);
			u.add(uuid);
			if (name != null && !name.isEmpty()) {
				n.add(name.toLowerCase(Locale.ROOT));
			}
			return new Known(u, n);
		}
	}

	/** On-disk format. */
	private static final class Data {
		int version = 1;
		List<Entry> players = new ArrayList<>();
	}

	private static final class Entry {
		String uuid;
		String name;
		long first;
	}

	/** Per-user data folder: XDG data dir on Linux, %APPDATA% on Windows, Application Support on macOS. */
	public static Path dir() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", ".");
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			return Path.of(appData != null && !appData.isBlank() ? appData : home, "smartshots");
		}
		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support", "smartshots");
		}
		String xdg = System.getenv("XDG_DATA_HOME");
		if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) {
			return Path.of(xdg, "smartshots");
		}
		return Path.of(home, ".local", "share", "smartshots");
	}

	public static Path file() {
		return dir().resolve(FILE);
	}

	/** False until the file was read once (don't decide anything before that). */
	public static boolean ready() {
		return known != null;
	}

	/** Game thread. Unknown until {@link #ready()}: then answers true so nothing is shot by mistake. */
	public static boolean isKnown(UUID uuid, String name) {
		Known k = known;
		if (k == null || k.has(uuid, name) || UNSAVED.containsKey(uuid)) {
			return true;
		}
		// Just met, the background thread may not have folded them into the snapshot yet.
		if (name != null && !UNSAVED.isEmpty()) {
			for (String n : UNSAVED.values()) {
				if (n.equalsIgnoreCase(name)) {
					return true;
				}
			}
		}
		return false;
	}

	public static int count() {
		Known k = known;
		return k == null ? 0 : k.uuids.size();
	}

	/** A met player as stored on disk; {@code first} is when they were met (epoch ms). */
	public record Met(String uuid, String name, long first) {
	}

	/** Everyone met, read in the background (the unsaved ones included). */
	public static java.util.concurrent.CompletableFuture<List<Met>> list() {
		return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			List<Met> out = new ArrayList<>();
			Set<String> have = new HashSet<>();
			try {
				for (Entry e : read().players) {
					if (e.uuid == null || have.add(e.uuid.toLowerCase(Locale.ROOT))) {
						out.add(new Met(e.uuid, e.name == null ? "" : e.name, e.first));
					}
				}
			} catch (IOException e) {
				SmartShots.LOGGER.warn("Couldn't read the met players list", e);
			}
			long now = System.currentTimeMillis();
			UNSAVED.forEach((uuid, name) -> {
				if (have.add(uuid.toString())) {
					out.add(new Met(uuid.toString(), name, now));
				}
			});
			return out;
		}, IO);
	}

	/** Re-read the file in the background if another instance changed it. Cheap to call often. */
	public static void refresh() {
		if (refreshQueued) {
			return;
		}
		refreshQueued = true;
		IO.execute(() -> {
			refreshQueued = false;
			try {
				FileTime stamp = Files.exists(file()) ? Files.getLastModifiedTime(file()) : null;
				if (known != null && stamp != null && stamp.equals(lastStamp)) {
					return;
				}
				Data d = read();
				lastStamp = stamp;
				known = withUnsaved(toKnown(d));
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't read the met players list", e);
				if (known == null) {
					known = new Known(Set.of(), Set.of());
				}
			}
		});
	}

	/** Game thread: remember these players (in memory now, on disk in the background). */
	public static void add(Map<UUID, String> players) {
		if (players.isEmpty()) {
			return;
		}
		players.forEach((uuid, name) -> UNSAVED.put(uuid, name == null ? "" : name));
		Known k = known == null ? new Known(Set.of(), Set.of()) : known;
		for (Map.Entry<UUID, String> e : players.entrySet()) {
			k = k.plus(e.getKey(), e.getValue());
		}
		known = k;
		IO.execute(SeenPlayers::save);
	}

	/** Forget everyone (in every instance: the file is emptied under the lock). */
	public static void forgetAll() {
		known = new Known(Set.of(), Set.of());
		UNSAVED.clear();
		IO.execute(() -> {
			try (Locked ignored = lock()) {
				write(new Data());
				lastStamp = Files.getLastModifiedTime(file());
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't clear the met players list", e);
			}
		});
	}

	/** IO thread: lock, read, add what we met, write. */
	private static void save() {
		try (Locked ignored = lock()) {
			Data d = read();
			Set<String> haveUuids = new HashSet<>();
			for (Entry e : d.players) {
				if (e.uuid != null) {
					haveUuids.add(e.uuid.toLowerCase(Locale.ROOT));
				}
			}
			long now = System.currentTimeMillis();
			Map<UUID, String> batch = new LinkedHashMap<>(UNSAVED);
			if (batch.isEmpty()) {
				return;
			}
			for (Map.Entry<UUID, String> m : batch.entrySet()) {
				if (haveUuids.add(m.getKey().toString())) {
					Entry e = new Entry();
					e.uuid = m.getKey().toString();
					e.name = m.getValue();
					e.first = now;
					d.players.add(e);
				}
			}
			write(d);
			batch.keySet().forEach(UNSAVED::remove);
			lastStamp = Files.getLastModifiedTime(file());
			known = withUnsaved(toKnown(d));
		} catch (Exception e) {
			// Stays in UNSAVED; the next save tries again.
			SmartShots.LOGGER.warn("Couldn't save the met players list", e);
		}
	}

	private static Known withUnsaved(Known k) {
		for (Map.Entry<UUID, String> e : UNSAVED.entrySet()) {
			k = k.plus(e.getKey(), e.getValue());
		}
		return k;
	}

	private static Known toKnown(Data d) {
		Set<UUID> u = new HashSet<>();
		Set<String> n = new HashSet<>();
		for (Entry e : d.players) {
			if (e.uuid != null) {
				try {
					u.add(UUID.fromString(e.uuid));
				} catch (IllegalArgumentException ignored) {
				}
			}
			if (e.name != null && !e.name.isEmpty()) {
				n.add(e.name.toLowerCase(Locale.ROOT));
			}
		}
		return new Known(u, n);
	}

	private static Data read() throws IOException {
		Path f = file();
		if (!Files.exists(f)) {
			return new Data();
		}
		try {
			Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
			if (d == null || d.players == null) {
				return new Data();
			}
			d.players.removeIf(e -> e == null);
			return d;
		} catch (com.google.gson.JsonParseException e) {
			// Keep the broken file for the user, start over.
			SmartShots.LOGGER.warn("Met players list is corrupt, starting a new one", e);
			Files.copy(f, f.resolveSibling(FILE + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			return new Data();
		}
	}

	private static void write(Data d) throws IOException {
		Path f = file();
		Files.createDirectories(f.getParent());
		Path tmp = f.resolveSibling(FILE + "." + ProcessHandle.current().pid() + ".tmp");
		Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private record Locked(FileChannel channel, FileLock lock) implements AutoCloseable {
		@Override
		public void close() throws IOException {
			try {
				lock.release();
			} finally {
				channel.close();
			}
		}
	}

	/** Blocks until no other game instance holds the lock. */
	private static Locked lock() throws IOException {
		Files.createDirectories(dir());
		FileChannel ch = FileChannel.open(dir().resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		try {
			return new Locked(ch, ch.lock());
		} catch (IOException | RuntimeException e) {
			ch.close();
			throw e;
		}
	}
}
