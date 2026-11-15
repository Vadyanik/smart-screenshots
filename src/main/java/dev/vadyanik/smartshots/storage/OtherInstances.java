package dev.vadyanik.smartshots.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the screenshots folders of the launcher's other instances.
 *
 * <p>Prism Launcher / MultiMC keep each instance in {@code <instances>/<folder>/} with an
 * {@code instance.cfg} next to the game dir ({@code minecraft/} or {@code .minecraft/}). When the
 * game dir doesn't look like that, we aren't running from such a launcher and nothing is found.
 *
 * <p>Several instances can share one real screenshots folder (symlinks, e.g. prism-sync), so
 * folders are deduplicated by real path, and the current instance's own folder is never returned.
 */
public final class OtherInstances {
	private static final String CFG = "instance.cfg";

	/** One real screenshots folder; {@code name} lists every instance that uses it. */
	public record Source(String name, Path screenshots) {
	}

	private OtherInstances() {
	}

	/** The launcher's instances folder, or null when the game doesn't run from a Prism/MultiMC instance. */
	public static Path instancesRoot(Path gameDir) {
		for (Path dir : candidates(gameDir)) {
			String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
			Path inst = dir.getParent();
			if ((n.equals("minecraft") || n.equals(".minecraft")) && inst != null
				&& Files.isRegularFile(inst.resolve(CFG)) && inst.getParent() != null) {
				return inst.getParent();
			}
		}
		return null;
	}

	private static List<Path> candidates(Path gameDir) {
		List<Path> list = new ArrayList<>();
		list.add(gameDir.toAbsolutePath().normalize());
		try {
			Path real = gameDir.toRealPath();
			if (!list.contains(real)) {
				list.add(real);
			}
		} catch (IOException ignored) {
		}
		return list;
	}

	/** Runs off-thread: touches the disk. */
	public static List<Source> find(Path gameDir, Path currentShots) {
		Path root = instancesRoot(gameDir);
		if (root == null) {
			return List.of();
		}
		Path own = realOrNull(currentShots);
		Map<Path, List<String>> byReal = new LinkedHashMap<>();
		List<Path> instances;
		try (Stream<Path> s = Files.list(root)) {
			instances = s.filter(p -> Files.isRegularFile(p.resolve(CFG))).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
		for (Path inst : instances) {
			for (String gd : new String[] {"minecraft", ".minecraft"}) {
				Path shots = inst.resolve(gd).resolve("screenshots");
				if (!Files.isDirectory(shots)) {
					continue;
				}
				Path real = realOrNull(shots);
				if (real != null && !real.equals(own)) {
					byReal.computeIfAbsent(real, k -> new ArrayList<>()).add(displayName(inst));
				}
				break;
			}
		}
		List<Source> out = new ArrayList<>();
		byReal.forEach((real, names) -> out.add(new Source(String.join(", ", names), real)));
		return out;
	}

	private static Path realOrNull(Path p) {
		try {
			return p.toRealPath();
		} catch (IOException e) {
			return null;
		}
	}

	/** {@code name=} from instance.cfg, falling back to the folder name. */
	static String displayName(Path inst) {
		try {
			for (String line : Files.readAllLines(inst.resolve(CFG), StandardCharsets.UTF_8)) {
				if (line.startsWith("name=")) {
					String v = line.substring(5).strip();
					if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
						v = v.substring(1, v.length() - 1);
					}
					if (!v.isEmpty()) {
						return v;
					}
				}
			}
		} catch (IOException | RuntimeException ignored) {
		}
		return inst.getFileName().toString();
	}
}
