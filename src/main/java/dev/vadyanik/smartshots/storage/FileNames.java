package dev.vadyanik.smartshots.storage;

import java.util.Locale;
import java.util.Set;

public final class FileNames {
	private static final Set<String> RESERVED = Set.of(
		"con", "prn", "aux", "nul",
		"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
		"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	private FileNames() {
	}

	/** Make a string safe as a folder name on Windows, macOS and Linux. Keeps Unicode. */
	public static String sanitize(String raw) {
		if (raw == null) {
			raw = "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c < 32 || "<>:\"/\\|?*§".indexOf(c) >= 0) {
				sb.append('_');
			} else {
				sb.append(c);
			}
		}
		String s = sb.toString().strip();
		while (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1).strip();
		}
		while (s.startsWith(".")) {
			s = s.substring(1);
		}
		if (s.length() > 60) {
			s = s.substring(0, 60).strip();
		}
		if (s.isEmpty()) {
			s = "unnamed";
		}
		if (RESERVED.contains(s.toLowerCase(Locale.ROOT))) {
			s = "_" + s;
		}
		return s;
	}
}
