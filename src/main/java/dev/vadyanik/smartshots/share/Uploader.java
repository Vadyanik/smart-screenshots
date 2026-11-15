package dev.vadyanik.smartshots.share;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.config.SmartShotsConfig.UploadService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Anonymous image hosts: one multipart POST, the link comes back in the response. */
public final class Uploader {
	private static final String USER_AGENT = "SmartScreenshots (Minecraft mod)";
	private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	/** A link and when it stops working (0 = never / unknown). */
	public record Link(String url, long expires) {
	}

	private Uploader() {
	}

	/** Uploads a PNG with the configured service. Blocking; never call on the render thread. */
	public static Link upload(byte[] png, String fileName) throws IOException, InterruptedException {
		SmartShotsConfig c = SmartShotsConfig.get();
		UploadService service = c.uploadService;
		Multipart form = new Multipart();
		String url;
		String field;
		long expires = 0;
		long now = System.currentTimeMillis();
		switch (service) {
			case CATBOX -> {
				url = "https://catbox.moe/user/api.php";
				field = "fileToUpload";
				form.field("reqtype", "fileupload");
			}
			case LITTERBOX -> {
				url = "https://litterbox.catbox.moe/resources/internals/api.php";
				field = "fileToUpload";
				form.field("reqtype", "fileupload");
				form.field("time", c.litterboxHours + "h");
				expires = now + c.litterboxHours * 3_600_000L;
			}
			case ZEROX0 -> {
				url = "https://0x0.st";
				field = "file";
				expires = now + 30L * 86_400_000L; // kept 30 days to a year depending on size
			}
			default -> {
				url = c.customUploadUrl;
				field = c.customUploadField;
				if (url == null || url.isBlank()) {
					throw new IOException("no upload URL set");
				}
			}
		}
		form.file(field, fileName, "image/png", png);

		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url.strip()))
			.timeout(Duration.ofSeconds(120))
			.header("User-Agent", USER_AGENT)
			.header("Content-Type", "multipart/form-data; boundary=" + form.boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(form.finish()));
		if (service == UploadService.CUSTOM) {
			for (String line : c.customUploadHeaders.split("[;\\n]")) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					req.header(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
				}
			}
		}
		HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		String body = resp.body() == null ? "" : resp.body().strip();
		if (resp.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + resp.statusCode() + (body.isEmpty() ? "" : ": " + shorten(body)));
		}
		String link = service == UploadService.CUSTOM && !c.customUploadJsonPath.isBlank()
			? jsonPath(body, c.customUploadJsonPath)
			: firstUrl(body);
		if (link == null) {
			throw new IOException("no link in the response: " + shorten(body));
		}
		return new Link(link, expires);
	}

	private static String firstUrl(String body) {
		Matcher m = URL.matcher(body);
		return m.find() ? m.group() : null;
	}

	private static String jsonPath(String body, String path) {
		try {
			JsonElement e = JsonParser.parseString(body);
			for (String part : path.split("\\.")) {
				if (e == null) {
					return null;
				}
				if (e.isJsonArray()) {
					e = e.getAsJsonArray().get(Integer.parseInt(part));
				} else if (e.isJsonObject()) {
					e = e.getAsJsonObject().get(part);
				} else {
					return null;
				}
			}
			return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
		} catch (Exception ex) {
			return firstUrl(body);
		}
	}

	private static String shorten(String s) {
		s = s.replaceAll("\\s+", " ");
		return s.length() > 120 ? s.substring(0, 120) + "…" : s;
	}

	private static final class Multipart {
		final String boundary = "----SmartShots" + UUID.randomUUID().toString().replace("-", "");
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		void field(String name, String value) {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
		}

		void file(String name, String fileName, String type, byte[] data) {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
				+ fileName.replace("\"", "") + "\"\r\nContent-Type: " + type + "\r\n\r\n");
			out.writeBytes(data);
			write("\r\n");
		}

		byte[] finish() {
			write("--" + boundary + "--\r\n");
			return out.toByteArray();
		}

		private void write(String s) {
			out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
		}
	}
}
