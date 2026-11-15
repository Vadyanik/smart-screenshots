package dev.vadyanik.smartshots.storage;

import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.search.SearchDoc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** In-memory snapshot of every screenshot on disk, organised as a tree. */
public final class Library {
	public enum Kind {
		ALL, FAVORITES, CATEGORY, INSTANCE, LOCATION, UNSORTED,
		/** Group of the launcher's other instances ({@link OtherInstances}). */
		PACKS,
		/** One other launcher instance (its screenshots folder). */
		PACK
	}

	public static final class Node {
		public final Kind kind;
		public String name;
		public final Category category;
		public final Path dir;
		public final Node parent;
		public final List<Node> children = new ArrayList<>();
		public final List<Shot> shots = new ArrayList<>();
		public long newest;
		/** For LOCATION nodes: key of the sub-world in the instance meta (for renaming). */
		public String fingerprint;

		public Node(Kind kind, String name, Category category, Path dir, Node parent) {
			this.kind = kind;
			this.name = name;
			this.category = category;
			this.dir = dir;
			this.parent = parent;
		}

		public int count() {
			int n = shots.size();
			for (Node c : children) {
				n += c.count();
			}
			return n;
		}

		public boolean contains(Shot s) {
			for (Node n = s.location; n != null; n = n.parent) {
				if (n == this) {
					return true;
				}
			}
			return false;
		}

		/** True for nodes of another launcher instance (and their group). */
		public boolean foreign() {
			for (Node n = this; n != null; n = n.parent) {
				if (n.kind == Kind.PACK || n.kind == Kind.PACKS) {
					return true;
				}
			}
			return false;
		}

		public String id() {
			return kind + ":" + (dir == null ? name : dir.toString());
		}
	}

	public static final class Shot {
		public final Path file;
		public final ShotMeta meta;
		public final Node location;
		/** The other launcher instance this shot belongs to, or null for the current one. */
		public final Node pack;
		/** Rebuilt when background work changes the metadata ({@link #refreshDoc}). */
		public SearchDoc doc;

		Shot(Path file, ShotMeta meta, Node location) {
			this.file = file;
			this.meta = meta;
			this.location = location;
			Node p = location;
			while (p != null && p.kind != Kind.PACK) {
				p = p.parent;
			}
			this.pack = p;
			this.doc = new SearchDoc(this);
		}

		public void refreshDoc() {
			doc = new SearchDoc(this);
		}

		public Node instance() {
			Node n = location;
			while (n != null && n.kind != Kind.INSTANCE) {
				n = n.parent;
			}
			return n;
		}
	}

	/** Every shot, including those of other instances (see {@link #others}). */
	public final List<Shot> shots = new ArrayList<>();
	public final List<Node> categories = new ArrayList<>();
	public Node unsorted;
	/** Other launcher instances ({@link Kind#PACKS}, children are {@link Kind#PACK}), or null. */
	public Node others;

	/** Scan the screenshots folder. Runs off-thread. */
	public static Library scan(Path root) {
		Library lib = new Library();
		try {
			Files.createDirectories(root);
		} catch (IOException ignored) {
		}
		Node unsorted = lib.scanTree(root, null, lib.categories);
		lib.unsorted = unsorted.shots.isEmpty() ? null : unsorted;
		lib.sortShots();
		computeNewest(lib.categories);
		return lib;
	}

	/**
	 * Scan the screenshots folders of the launcher's other instances. Runs off-thread. Returns a
	 * {@link Kind#PACKS} node (to pass to {@link #attachOthers}) or null when there are none.
	 */
	public static Node scanOthers(Path gameDir, Path currentRoot) {
		Path instancesRoot = OtherInstances.instancesRoot(gameDir);
		List<OtherInstances.Source> sources = OtherInstances.find(gameDir, currentRoot);
		if (instancesRoot == null || sources.isEmpty()) {
			return null;
		}
		Library tmp = new Library();
		Node group = new Node(Kind.PACKS, "", null, instancesRoot, null);
		for (OtherInstances.Source src : sources) {
			Node pack = new Node(Kind.PACK, src.name(), null, src.screenshots(), group);
			Node unsorted = tmp.scanTree(src.screenshots(), pack, pack.children);
			computeNewest(pack.children);
			if (!unsorted.shots.isEmpty()) {
				pack.children.add(unsorted);
			}
			pack.newest = tmp.shots.stream().filter(s -> s.pack == pack).mapToLong(s -> s.meta.time).max().orElse(0);
			if (pack.count() > 0) {
				group.children.add(pack);
			}
		}
		group.children.sort(Comparator.comparingLong((Node n) -> n.newest).reversed());
		return group.children.isEmpty() ? null : group;
	}

	/** Main thread: replaces the other instances' shots with those under {@code group} (may be null). */
	public void attachOthers(Node group) {
		shots.removeIf(s -> s.pack != null);
		others = group;
		if (group != null) {
			collect(group, shots);
		}
		sortShots();
	}

	/** Shots of the current instance only. */
	public int localCount() {
		int n = 0;
		for (Shot s : shots) {
			if (s.pack == null) {
				n++;
			}
		}
		return n;
	}

	private static void collect(Node node, List<Shot> out) {
		out.addAll(node.shots);
		for (Node c : node.children) {
			collect(c, out);
		}
	}

	private void sortShots() {
		shots.sort(Comparator.comparingLong((Shot s) -> s.meta.time).reversed());
	}

	/**
	 * Reads one screenshots folder: category/world/sub-world tree into {@code catsOut} (nodes get
	 * {@code parent} as parent), flat files into the returned {@link Kind#UNSORTED} node.
	 */
	private Node scanTree(Path root, Node parent, List<Node> catsOut) {
		for (Category cat : Category.values()) {
			Path catDir = root.resolve(cat.folder);
			if (!Files.isDirectory(catDir)) {
				continue;
			}
			Node catNode = new Node(Kind.CATEGORY, cat.label().getString(), cat, catDir, parent);
			for (Path instDir : subDirs(catDir)) {
				InstanceMeta im = Json.read(instDir.resolve(ScreenshotStore.WORLD_FILE), InstanceMeta.class);
				String instName = im != null && im.displayName != null ? im.displayName : instDir.getFileName().toString();
				Node inst = new Node(Kind.INSTANCE, instName, cat, instDir, catNode);
				readShots(instDir, inst);
				for (Path locDir : subDirs(instDir)) {
					String locName = locDir.getFileName().toString();
					String fingerprint = null;
					if (im != null) {
						for (Map.Entry<String, InstanceMeta.SubWorld> e : im.worlds.entrySet()) {
							if (locName.equals(e.getValue().folder)) {
								locName = WorldContext.subWorldName(e.getValue());
								fingerprint = e.getKey();
							}
						}
					}
					Node loc = new Node(Kind.LOCATION, locName, cat, locDir, inst);
					loc.fingerprint = fingerprint;
					readShots(locDir, loc);
					if (!loc.shots.isEmpty()) {
						inst.children.add(loc);
					}
				}
				if (inst.count() > 0) {
					catNode.children.add(inst);
				}
			}
			if (!catNode.children.isEmpty()) {
				catsOut.add(catNode);
			}
		}
		Node unsorted = new Node(Kind.UNSORTED, "", null, root, parent);
		readShots(root, unsorted);
		return unsorted;
	}

	private static void computeNewest(List<Node> categories) {
		for (Node c : categories) {
			for (Node i : c.children) {
				for (Node l : i.children) {
					l.newest = l.shots.stream().mapToLong(s -> s.meta.time).max().orElse(0);
					l.children.sort(Comparator.comparingLong((Node n) -> n.newest).reversed());
				}
				i.newest = Math.max(i.shots.stream().mapToLong(s -> s.meta.time).max().orElse(0),
					i.children.stream().mapToLong(n -> n.newest).max().orElse(0));
				i.children.sort(Comparator.comparingLong((Node n) -> n.newest).reversed());
			}
			c.children.sort(Comparator.comparingLong((Node n) -> n.newest).reversed());
		}
	}

	private void readShots(Path dir, Node node) {
		Map<String, ShotMeta> metas = Json.readShots(dir);
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") && Files.isRegularFile(p))
				.forEach(p -> {
					ShotMeta meta = metas.get(p.getFileName().toString());
					if (meta == null) {
						meta = new ShotMeta();
						meta.trigger = Trigger.UNKNOWN.id;
						try {
							meta.time = Files.getLastModifiedTime(p).toMillis();
						} catch (IOException e) {
							meta.time = 0;
						}
					}
					migrateSelf(meta);
					Shot shot = new Shot(p, meta, node);
					node.shots.add(shot);
					shots.add(shot);
				});
		} catch (IOException e) {
			SmartShots.LOGGER.warn("Couldn't list {}", dir, e);
		}
	}

	/** Old metadata flagged the local player with {@code self}; now they are listed by nickname in players. */
	@SuppressWarnings("deprecation")
	private static void migrateSelf(ShotMeta meta) {
		if (!meta.self) {
			return;
		}
		meta.self = false;
		var user = net.minecraft.client.Minecraft.getInstance().getUser();
		String name = user == null ? null : user.getName();
		if (name != null && !name.isEmpty() && !meta.players.contains(name)) {
			meta.players.add(name);
		}
	}

	private static List<Path> subDirs(Path dir) {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(Files::isDirectory)
				.filter(p -> !p.getFileName().toString().startsWith("."))
				.sorted()
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}
}
