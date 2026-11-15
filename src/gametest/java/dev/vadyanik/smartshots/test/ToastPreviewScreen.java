package dev.vadyanik.smartshots.test;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vadyanik.smartshots.gui.ShotToast;
import dev.vadyanik.smartshots.storage.Json;
import dev.vadyanik.smartshots.storage.ScreenshotStore;
import dev.vadyanik.smartshots.storage.ShotMeta;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Shows each kind of ShotToast as it would appear in the top-right corner. */
class ToastPreviewScreen extends Screen {
	private final List<ShotToast> toasts = new ArrayList<>();

	ToastPreviewScreen() {
		super(Component.literal("toasts"));
	}

	@Override
	protected void init() {
		if (!toasts.isEmpty()) {
			return;
		}
		try (Stream<Path> files = Files.walk(ScreenshotStore.root())) {
			for (Path dir : files.filter(p -> p.getFileName().toString().equals(ScreenshotStore.SHOTS_FILE)).map(Path::getParent).toList()) {
				Map<String, ShotMeta> metas = Json.readShots(dir);
				for (Map.Entry<String, ShotMeta> e : metas.entrySet()) {
					NativeImage img;
					try (InputStream in = Files.newInputStream(dir.resolve(e.getKey()))) {
						img = NativeImage.read(in);
					}
					NativeImage small = new NativeImage(img.format(), 192, 120, false);
					img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), small);
					img.close();
					toasts.add(new ShotToast(small, e.getValue(), "Новый мир › Верхний мир"));
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int y = 0;
		for (ShotToast t : toasts) {
			g.pose().pushMatrix();
			g.pose().translate(width - t.width(), y);
			t.extractRenderState(g, font, 1500);
			g.pose().popMatrix();
			y += t.height() + 2;
		}
	}
}
