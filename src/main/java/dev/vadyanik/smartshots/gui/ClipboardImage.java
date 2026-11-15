package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.SmartShots;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Puts a PNG on the system clipboard as an image. The game's clipboard (GLFW) only does text and
 * AWT is headless in Minecraft, so this goes through the OS tools: wl-copy / xclip on Linux,
 * PowerShell on Windows, osascript on macOS.
 */
public final class ClipboardImage {
	private ClipboardImage() {
	}

	/** Copies in the background; {@code done} gets the result on the main thread. */
	public static void copy(Path png, Consumer<Boolean> done) {
		Path file = png.toAbsolutePath();
		Thread t = new Thread(() -> {
			boolean ok;
			try {
				ok = Files.isRegularFile(file) && run(file);
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't copy {} to the clipboard", file, e);
				ok = false;
			}
			boolean result = ok;
			Minecraft.getInstance().execute(() -> done.accept(result));
		}, "smartshots-clipboard");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Copies the file itself (not its pixels), so it can be pasted into a file manager, Discord,
	 * Telegram... as an attachment. Background; {@code done} gets the result on the main thread.
	 */
	public static void copyFile(Path png, Consumer<Boolean> done) {
		Path file = png.toAbsolutePath();
		Thread t = new Thread(() -> {
			boolean ok;
			try {
				ok = Files.isRegularFile(file) && runFile(file);
			} catch (Exception e) {
				SmartShots.LOGGER.warn("Couldn't copy {} to the clipboard as a file", file, e);
				ok = false;
			}
			boolean result = ok;
			Minecraft.getInstance().execute(() -> done.accept(result));
		}, "smartshots-clipboard");
		t.setDaemon(true);
		t.start();
	}

	private static boolean runFile(Path file) throws Exception {
		String path = file.toString();
		String uri = file.toUri().toASCIIString();
		return switch (Util.getPlatform()) {
			case WINDOWS -> exec(List.of("powershell", "-NoProfile", "-NonInteractive", "-STA", "-Command",
				"Set-Clipboard -LiteralPath '" + path.replace("'", "''") + "'"), null);
			case OSX -> exec(List.of("osascript", "-e",
				"set the clipboard to (POSIX file \"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\")"), null);
			default -> {
				Path list = Files.createTempFile("smartshots-uri", ".txt");
				try {
					Files.writeString(list, uri + "\r\n");
					if (System.getenv("WAYLAND_DISPLAY") != null && onPath("wl-copy")
						&& exec(List.of("wl-copy", "--type", "text/uri-list"), list)) {
						yield true;
					}
					if (onPath("xclip")) {
						yield exec(List.of("xclip", "-selection", "clipboard", "-t", "text/uri-list", "-i", list.toString()), null);
					}
					SmartShots.LOGGER.warn("No clipboard tool found: install wl-clipboard (Wayland) or xclip (X11)");
					yield false;
				} finally {
					Files.deleteIfExists(list);
				}
			}
		};
	}

	private static boolean run(Path file) throws Exception {
		String path = file.toString();
		return switch (Util.getPlatform()) {
			case WINDOWS -> exec(List.of("powershell", "-NoProfile", "-NonInteractive", "-STA", "-Command",
				"Add-Type -AssemblyName System.Windows.Forms,System.Drawing; "
					+ "$img = [System.Drawing.Image]::FromFile('" + path.replace("'", "''") + "'); "
					+ "[System.Windows.Forms.Clipboard]::SetImage($img); $img.Dispose()"), null);
			case OSX -> exec(List.of("osascript", "-e",
				"set the clipboard to (read (POSIX file \"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\") as «class PNGf»)"), null);
			default -> linux(file);
		};
	}

	private static boolean linux(Path file) throws Exception {
		if (System.getenv("WAYLAND_DISPLAY") != null && onPath("wl-copy")
			&& exec(List.of("wl-copy", "--type", "image/png"), file)) {
			return true;
		}
		if (onPath("xclip")) {
			return exec(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-i", file.toString()), null);
		}
		SmartShots.LOGGER.warn("No clipboard tool found: install wl-clipboard (Wayland) or xclip (X11)");
		return false;
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(File.pathSeparator)) {
			if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, tool))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Runs a tool and waits for it. wl-copy and xclip fork a child that keeps serving the
	 * clipboard; its output is discarded so nothing blocks on our pipes.
	 */
	private static boolean exec(List<String> cmd, Path stdin) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD);
		pb.redirectInput(stdin != null ? ProcessBuilder.Redirect.from(stdin.toFile()) : ProcessBuilder.Redirect.from(nullFile()));
		Process p = pb.start();
		if (!p.waitFor(15, TimeUnit.SECONDS)) {
			p.destroy();
			return false;
		}
		return p.exitValue() == 0;
	}

	private static File nullFile() {
		return new File(Util.getPlatform() == Util.OS.WINDOWS ? "NUL" : "/dev/null");
	}
}
