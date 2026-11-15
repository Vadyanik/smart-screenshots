package dev.vadyanik.smartshots.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stored as {@code .smartshots-world.json} inside each world/server folder.
 * Maps "fingerprints" of sub-worlds to stable folder names.
 */
public class InstanceMeta {
	public String displayName;
	/** Server address or singleplayer level folder id. */
	public String key;
	public String category;
	public long lastSeen;
	/** fingerprint -> sub-world */
	public Map<String, SubWorld> worlds = new LinkedHashMap<>();

	public static class SubWorld {
		public String folder;
		public String displayName;
		/** True when the player renamed it; we then never overwrite the name. */
		public boolean renamed;
		public String dimension;
		public long firstSeen;
	}
}
