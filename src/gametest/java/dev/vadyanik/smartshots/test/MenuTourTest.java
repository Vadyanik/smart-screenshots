package dev.vadyanik.smartshots.test;

import dev.vadyanik.smartshots.capture.CaptureService;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.CustomUploadScreen;
import dev.vadyanik.smartshots.gui.EditorScreen;
import dev.vadyanik.smartshots.gui.GalleryScreen;
import dev.vadyanik.smartshots.gui.NoteScreen;
import dev.vadyanik.smartshots.gui.SettingsScreen;
import dev.vadyanik.smartshots.gui.ViewerScreen;
import dev.vadyanik.smartshots.storage.EditOp;
import dev.vadyanik.smartshots.storage.Trigger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Path;
import java.util.List;

/**
 * Not a real test: walks through every screen of the mod and saves a screenshot of each,
 * so the UI can be reviewed remotely. Output: build/menu-tour/*.png
 */
public class MenuTourTest implements FabricClientGameTest {
	private static final Path OUT = Path.of(System.getProperty("smartshots.tourDir", "../../menu-tour")).toAbsolutePath();

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(3);
		ctx.takeScreenshot(TestScreenshotOptions.of(name).withDestinationDir(OUT).disableCounterPrefix());
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1280, 800);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(2);
			mc.options.languageCode = "ru_ru";
			mc.getLanguageManager().setSelected("ru_ru");
			mc.resizeGui();
		});
		ctx.runOnClient(mc -> mc.reloadResourcePacks());
		ctx.waitTicks(40);
		ctx.waitForScreen(TitleScreen.class);
		shot(ctx, "01_title_screen");

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set 1000");
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("gamerule spawn_mobs false");
			ctx.waitTicks(20);

			// Build a small scene in front of the player.
			sp.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 10");
			sp.getServer().runCommand("execute as @p at @s run setblock ~ ~ ~4 minecraft:diamond_ore");
			sp.getServer().runCommand("execute as @p at @s run setblock ~1 ~ ~4 minecraft:oak_log");
			sp.getServer().runCommand("execute as @p at @s run setblock ~-1 ~ ~3 minecraft:oak_sign[rotation=8]{front_text:{messages:['Мой дом','→ шахта','','']}}");
			sp.getServer().runCommand("execute as @p at @s run summon minecraft:wolf ~-1 ~ ~5 {Tags:[\"demo\"]}");
			sp.getServer().runCommand("execute as @p at @s run summon minecraft:cat ~2 ~ ~6");
			sp.getServer().runCommand("execute as @p at @s run summon minecraft:villager ~1 ~ ~8 {NoAI:1b}");
			ctx.waitTicks(30);

			// A manual shot (first person)
			ctx.runOnClient(mc -> dev.vadyanik.smartshots.capture.CaptureScheduler.requestManual(mc));
			ctx.waitTicks(20);
			shot(ctx, "02_toast_manual");

			// A third-person shot of yourself
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> dev.vadyanik.smartshots.capture.CaptureScheduler.requestManual(mc));
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));

			// An auto shot and an advancement shot
			ctx.runOnClient(mc -> CaptureService.capture(mc, Trigger.AUTO, List.of(), List.of()));
			ctx.waitTicks(10);
			sp.getServer().runCommand("advancement grant @a only minecraft:story/mine_stone");
			ctx.waitTicks(25);
			shot(ctx, "03_toast_advancement");
			ctx.waitTicks(40);

			// Toasts are suppressed during client game tests, so render ours on a plain test screen.
			ctx.setScreen(ToastPreviewScreen::new);
			ctx.waitTicks(10);
			shot(ctx, "03b_toasts");

			ctx.setScreen(() -> new PauseScreen(true));
			shot(ctx, "04_pause_menu");

			ctx.setScreen(() -> new GalleryScreen(null));
			ctx.waitFor(mc -> mc.gui.screen() instanceof GalleryScreen g && g.isLoaded(), 200);
			ctx.waitTicks(40);
			shot(ctx, "05_gallery");

			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof GalleryScreen g) {
					g.focusSearch();
				}
			});
			ctx.getInput().typeChars("вол");
			ctx.waitTicks(5);
			shot(ctx, "06_gallery_suggestions");

			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof GalleryScreen g) {
					g.searchFor("я ");
				}
			});
			ctx.waitTicks(20);
			shot(ctx, "07_gallery_search_me");

			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof GalleryScreen g) {
					g.searchFor("");
					g.openFirst();
				}
			});
			ctx.waitTicks(40);
			shot(ctx, "08_viewer");

			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof ViewerScreen v) {
					v.onClose();
				}
				if (mc.gui.screen() instanceof GalleryScreen g) {
					g.searchFor("sign:/шахт[аы]/ ");
				}
			});
			ctx.waitTicks(20);
			shot(ctx, "08b_gallery_regex_sign");
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof GalleryScreen g) {
					g.searchFor("");
					g.openFirst();
				}
			});
			ctx.waitTicks(20);

			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof ViewerScreen v) {
					v.confirmDelete();
				}
			});
			shot(ctx, "09_delete_dialog");
			ctx.runOnClient(mc -> mc.gui.screen().onClose());
			ctx.waitTicks(2);

			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof ViewerScreen v) {
					v.openNote();
				}
			});
			shot(ctx, "10_note_dialog");
			ctx.runOnClient(mc -> mc.gui.screen().onClose());
			ctx.waitTicks(2);

			// Editor with one of everything, then the viewer showing the result (cropped).
			ctx.runOnClient(mc -> {
				SmartShotsConfig.get().shareConfirmed = false; // never upload from the tour
				if (mc.gui.screen() instanceof ViewerScreen v) {
					v.openEditor();
				}
			});
			ctx.waitTicks(30);
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof EditorScreen e) {
					e.addOps(demoEdits());
				}
			});
			ctx.waitTicks(40);
			ctx.getInput().setCursorPos(900, 300);
			shot(ctx, "10b_editor");
			ctx.runOnClient(mc -> mc.gui.screen().onClose());
			ctx.waitTicks(40);
			shot(ctx, "10c_viewer_edited");
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() instanceof ViewerScreen v) {
					v.share();
				}
			});
			shot(ctx, "10d_share_confirm");
			ctx.runOnClient(mc -> mc.gui.screen().onClose());
			ctx.waitTicks(2);

			ctx.setScreen(() -> new SettingsScreen(new GalleryScreen(null)));
			shot(ctx, "11_settings");
			ctx.getInput().setCursorPos(640, 600);
			ctx.getInput().scroll(-20);
			ctx.waitTicks(3);
			shot(ctx, "12_settings_scrolled");

			ctx.getInput().scroll(-20);
			ctx.waitTicks(3);
			shot(ctx, "13_settings_share");

			ctx.runOnClient(mc -> mc.gui.setScreen(new CustomUploadScreen(mc.gui.screen())));
			shot(ctx, "14_custom_upload");

			ctx.setScreen(() -> null);
		}
	}

	private static List<EditOp> demoEdits() {
		List<EditOp> ops = new java.util.ArrayList<>();
		ops.add(new EditOp(EditOp.ARROW, 0xFFFF3B30, 7, false, new float[]{0.15f, 0.2f, 0.4f, 0.42f}));
		ops.add(new EditOp(EditOp.RECT, 0xFF34C759, 6, false, new float[]{0.42f, 0.3f, 0.62f, 0.7f}));
		ops.add(new EditOp(EditOp.ELLIPSE, 0xFF32ADE6, 5, false, new float[]{0.65f, 0.25f, 0.85f, 0.5f}));
		ops.add(new EditOp(EditOp.PEN, 0xFFFFCC00, 6, false, new float[]{0.1f, 0.7f, 0.15f, 0.75f, 0.2f, 0.68f, 0.26f, 0.78f, 0.32f, 0.7f}));
		ops.add(new EditOp(EditOp.MARKER, 0xFFFFCC00, 26, false, new float[]{0.5f, 0.82f, 0.8f, 0.82f}));
		EditOp text = new EditOp(EditOp.TEXT, 0xFFFFFFFF, 48, false, new float[]{0.08f, 0.08f});
		text.text = "Мой дом";
		ops.add(text);
		EditOp box = new EditOp(EditOp.TEXT, 0xFFFF3B30, 32, true, new float[]{0.62f, 0.6f});
		box.text = "Шахта →";
		ops.add(box);
		for (int i = 1; i <= 3; i++) {
			EditOp c = new EditOp(EditOp.COUNTER, 0xFF007AFF, 22, false, new float[]{0.88f, 0.1f + i * 0.08f});
			c.n = i;
			ops.add(c);
		}
		ops.add(new EditOp(EditOp.BLUR, 0, 16, false, new float[]{0.02f, 0.85f, 0.25f, 0.98f}));
		ops.add(new EditOp(EditOp.PIXELATE, 0, 20, false, new float[]{0.75f, 0.85f, 0.98f, 0.98f}));
		ops.add(new EditOp(EditOp.CROP, 0, 0, false, new float[]{0.04f, 0.04f, 0.96f, 0.96f}));
		return ops;
	}
}
