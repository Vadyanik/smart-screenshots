package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.capture.CaptureService;
import dev.vadyanik.smartshots.config.SmartShotsConfig;
import dev.vadyanik.smartshots.gui.ui.FlatButton;
import dev.vadyanik.smartshots.gui.ui.FlatSlider;
import dev.vadyanik.smartshots.gui.ui.FlatToggle;
import dev.vadyanik.smartshots.gui.ui.Theme;
import dev.vadyanik.smartshots.gui.ui.Ui;
import dev.vadyanik.smartshots.storage.ImageOps;
import dev.vadyanik.smartshots.storage.SeenPlayers;
import dev.vadyanik.smartshots.storage.ShotArchiver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Flat settings page: a centered column of sections with switch and slider rows; scrolls when tall. */
public class SettingsScreen extends Screen {
	private static final int TOP_H = 32;
	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private int colX;
	private int colW;
	private double scroll;
	private int contentH;

	private record Row(AbstractWidget widget, int y) {
	}

	private record Section(Component title, int y) {
	}

	private final List<Section> sections = new ArrayList<>();

	public SettingsScreen(Screen parent) {
		super(Component.translatable("smartshots.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rows.clear();
		sections.clear();
		SmartShotsConfig c = SmartShotsConfig.get();
		colW = Math.min(440, width - 24);
		colX = (width - colW) / 2;

		addRenderableWidget(new FlatButton(8, 6, 20, 20, Component.translatable("gui.back"), Ui.ARROW_LEFT,
			FlatButton.Style.GHOST, false, b -> onClose()));

		int y = 0;
		y = section(y, "smartshots.settings.section.auto");
		y = add(toggle("auto_enabled", () -> c.autoEnabled, v -> c.autoEnabled = v), y);
		y = add(new FlatSlider(colX, 0, colW, Component.translatable("smartshots.settings.interval_label"),
			(c.autoIntervalMinutes - 1) / 59.0,
			v -> Component.translatable("smartshots.settings.minutes", 1 + (int) Math.round(v * 59)).getString(),
			v -> c.autoIntervalMinutes = 1 + (int) Math.round(v * 59)), y);
		y = add(toggle("auto_hide_hud", () -> c.autoHideHud, v -> c.autoHideHud = v), y);
		y = add(toggle("auto_skip_idle", () -> c.autoSkipIdle, v -> c.autoSkipIdle = v), y);

		y = section(y, "smartshots.settings.section.advancements");
		y = add(toggle("advancement", () -> c.advancementEnabled, v -> c.advancementEnabled = v), y);
		y = add(new FlatSlider(colX, 0, colW, Component.translatable("smartshots.settings.adv_delay_label"),
			c.advancementDelayTicks / 60.0,
			v -> Component.translatable("smartshots.settings.seconds", String.format("%.1f", Math.round(v * 60) / 20.0)).getString(),
			v -> c.advancementDelayTicks = Mth.clamp((int) Math.round(v * 60), 0, 60)), y);
		y = add(new NotifyRow(colX, colW, "smartshots.settings.notify", true), y);

		y = section(y, "smartshots.settings.section.meet");
		y = add(toggle("meet", () -> c.meetEnabled, v -> c.meetEnabled = v), y);
		FlatSlider gather = new FlatSlider(colX, 0, colW, Component.translatable("smartshots.settings.meet_gather"),
			c.meetGatherSeconds / 5.0,
			v -> Component.translatable("smartshots.settings.seconds", fmt(Math.round(v * 10) / 2f)).getString(),
			v -> c.meetGatherSeconds = Math.round(v * 10) / 2f).choices(11);
		gather.setTooltip(Tooltip.create(Component.translatable("smartshots.settings.meet_gather.tooltip")));
		y = add(gather, y);
		y = add(choiceSlider("meet_cooldown", MEET_COOLDOWNS, c.meetCooldownSeconds,
			sec -> Component.translatable("smartshots.settings.seconds", sec).getString(), v -> c.meetCooldownSeconds = v), y);
		y = add(new ForgetRow(colX, colW), y);

		y = section(y, "smartshots.settings.section.manual");
		y = add(new NotifyRow(colX, colW, "smartshots.settings.notify_manual", false), y);

		y = section(y, "smartshots.settings.section.share");
		y = add(new ChoiceRow<>(colX, colW, "copy_on_capture", SmartShotsConfig.CopyOnCapture.values(),
			() -> c.copyOnCapture, v -> c.copyOnCapture = v), y);
		y = add(toggle("auto_upload", () -> c.autoUploadManual, v -> c.autoUploadManual = v), y);
		y = add(new ChoiceRow<>(colX, colW, "upload_service", SmartShotsConfig.UploadService.values(),
			() -> c.uploadService, v -> c.uploadService = v), y);
		y = add(choiceSlider("litterbox_hours", LITTERBOX_HOURS, c.litterboxHours,
			hrs -> Component.translatable("smartshots.settings.hours", hrs).getString(), v -> c.litterboxHours = v), y);
		y = add(new CustomUploadRow(colX, colW, this), y);

		y = section(y, "smartshots.settings.section.xaero");
		y = add(toggle("xaero_waypoints", () -> c.xaeroWaypoints, v -> {
			c.xaeroWaypoints = v;
			dev.vadyanik.smartshots.integration.XaeroBridge.refresh();
		}), y);
		y = add(toggle("xaero_auto", () -> c.xaeroIncludeAuto, v -> {
			c.xaeroIncludeAuto = v;
			dev.vadyanik.smartshots.integration.XaeroBridge.refresh();
		}), y);

		y = section(y, "smartshots.settings.section.track");
		y = add(toggle("track_enabled", () -> c.trackEnabled, v -> c.trackEnabled = v), y);
		y = add(choiceSlider("track_interval", TRACK_TICKS, c.trackIntervalTicks,
			t -> Component.translatable("smartshots.settings.seconds", fmt(t / 20f)).getString(), v -> c.trackIntervalTicks = v), y);
		y = add(new TrackFolderRow(colX, colW), y);

		y = section(y, "smartshots.settings.section.toast");
		y = add(new FlatSlider(colX, 0, colW, Component.translatable("smartshots.settings.toast_size_label"),
			(c.toastSizePercent - 3) / 27.0,
			v -> Component.translatable("smartshots.settings.toast_size", fmt(toastSize(v))).getString(),
			v -> c.toastSizePercent = toastSize(v)), y);
		y = add(new FlatSlider(colX, 0, colW, Component.translatable("smartshots.settings.toast_time_label"),
			(c.toastSeconds - 1) / 14.0,
			v -> Component.translatable("smartshots.settings.seconds", fmt(toastSeconds(v))).getString(),
			v -> c.toastSeconds = toastSeconds(v)), y);

		y = section(y, "smartshots.settings.section.library");
		y = add(toggle("organize", () -> c.organize, v -> c.organize = v), y);
		y = add(toggle("separate_dimensions", () -> c.separateDimensions, v -> c.separateDimensions = v), y);
		y = add(toggle("scene_analysis", () -> c.sceneAnalysis, v -> c.sceneAnalysis = v), y);
		y = add(toggle("pause_button", () -> c.pauseMenuButton, v -> c.pauseMenuButton = v), y);

		y = section(y, "smartshots.settings.section.storage");
		y = add(heightSlider("manual_height", c.manualMaxHeight, v -> c.manualMaxHeight = v), y);
		y = add(heightSlider("auto_height", c.autoMaxHeight, v -> c.autoMaxHeight = v), y);
		y = add(toggle("archive_enabled", () -> c.archiveEnabled, v -> c.archiveEnabled = v), y);
		y = add(choiceSlider("archive_days", ARCHIVE_DAYS, c.archiveAfterDays,
			d -> Component.translatable("smartshots.settings.days", d).getString(), v -> c.archiveAfterDays = v), y);
		y = add(heightSlider("archive_height", c.archiveMaxHeight, v -> c.archiveMaxHeight = v), y);
		y = add(toggle("archive_auto_only", () -> c.archiveAutoOnly, v -> c.archiveAutoOnly = v), y);
		y = add(new ArchiveRow(colX, colW), y);
		contentH = y + 12;
		scroll = Mth.clamp(scroll, 0, maxScroll());
		reposition();
	}

	private static final int[] ARCHIVE_DAYS = {7, 14, 30, 60, 90, 180, 365};
	private static final int[] MEET_COOLDOWNS = {0, 10, 15, 30, 60, 120, 300};
	private static final int[] LITTERBOX_HOURS = {1, 12, 24, 72};
	private static final int[] TRACK_TICKS = {1, 2, 5, 10, 20, 40};

	/** Discrete slider over {@code choices}; starts at the choice closest to {@code current}. */
	private FlatSlider choiceSlider(String key, int[] choices, int current, IntFunction<String> label, IntConsumer set) {
		int index = 0;
		for (int i = 1; i < choices.length; i++) {
			if (Math.abs(choices[i] - current) < Math.abs(choices[index] - current)) {
				index = i;
			}
		}
		int last = choices.length - 1;
		FlatSlider slider = new FlatSlider(colX, 0, colW, Component.translatable("smartshots.settings." + key),
			index / (double) last,
			v -> label.apply(choices[(int) Math.round(v * last)]),
			v -> set.accept(choices[(int) Math.round(v * last)])).choices(choices.length);
		slider.setTooltip(Tooltip.create(Component.translatable("smartshots.settings." + key + ".tooltip")));
		return slider;
	}

	/** Resolution choice: Original / 2160p / ... / 480p (see {@link ImageOps#HEIGHTS}). */
	private FlatSlider heightSlider(String key, int current, IntConsumer set) {
		return choiceSlider(key, ImageOps.HEIGHTS, current,
			h -> h == 0 ? Component.translatable("smartshots.settings.height.original").getString() : h + "p", set);
	}

	/** Slider 0..1 -> 3..30 % of the screen area, in 0.5 % steps. */
	private static float toastSize(double v) {
		return Math.round((3 + v * 27) * 2) / 2f;
	}

	/** Slider 0..1 -> 1..15 s, in 0.5 s steps. */
	private static float toastSeconds(double v) {
		return Math.round((1 + v * 14) * 2) / 2f;
	}

	private static String fmt(float f) {
		return f == Math.floor(f) ? String.valueOf((int) f) : String.valueOf(f);
	}

	private FlatToggle toggle(String key, java.util.function.Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
		return new FlatToggle(colX, 0, colW, Component.translatable("smartshots.settings." + key),
			Component.translatable("smartshots.settings." + key + ".tooltip"), get, set);
	}

	private int section(int y, String key) {
		y += sections.isEmpty() ? 6 : 14;
		sections.add(new Section(Component.translatable(key), y));
		return y + 14;
	}

	private int add(AbstractWidget w, int y) {
		rows.add(new Row(w, y));
		addRenderableWidget(w);
		return y + w.getHeight() + 2;
	}

	private int viewTop() {
		return TOP_H + 4;
	}

	private double maxScroll() {
		return Math.max(0, contentH - (height - viewTop()));
	}

	private void reposition() {
		int top = viewTop();
		for (Row r : rows) {
			int y = top + r.y - (int) scroll;
			r.widget.setY(y);
			r.widget.visible = y + r.widget.getHeight() > top && y < height;
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (minecraft.level == null) {
			extractPanorama(g, a);
		}
		extractBlurredBackground(g);
		g.fill(0, 0, width, height, Theme.OVERLAY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.text(font, title, 34, 12, Theme.TEXT, false);
		g.horizontalLine(0, width, TOP_H, Theme.BORDER);
		int top = viewTop();
		g.enableScissor(0, top, width, height);
		for (Section s : sections) {
			int y = top + s.y - (int) scroll;
			g.text(font, s.title.getString().toUpperCase(GalleryScreen.gameLocale()), colX + 8, y + 3, Theme.TEXT_FAINT, false);
		}
		// Group background cards behind consecutive rows
		int i = 0;
		while (i < rows.size()) {
			int start = i;
			while (i + 1 < rows.size() && rows.get(i + 1).y == rows.get(i).y + rows.get(i).widget.getHeight() + 2) {
				i++;
			}
			int y0 = top + rows.get(start).y - (int) scroll - 2;
			Row last = rows.get(i);
			int y1 = top + last.y + last.widget.getHeight() - (int) scroll + 2;
			Ui.card(g, colX - 2, y0, colW + 4, y1 - y0);
			i++;
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		g.disableScissor();
		if (maxScroll() > 0) {
			int viewH = height - top;
			int barH = Math.max(16, viewH * viewH / contentH);
			int barY = top + (int) ((viewH - barH) * (scroll / maxScroll()));
			Ui.rect(g, width - 5, barY, 2, barH, Theme.BORDER_STRONG);
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * 20, 0, maxScroll());
		reposition();
		return true;
	}

	@Override
	public void onClose() {
		SmartShotsConfig.save();
		minecraft.gui.setScreen(parent);
	}

	/** "Archive now" row: status line on the left, a button-like pill on the right; the whole row is clickable. */
	private static final class ArchiveRow extends net.minecraft.client.gui.components.Button {
		ArchiveRow(int x, int w) {
			super(x, 0, w, 30, Component.translatable("smartshots.settings.archive_now"), b -> start(), DEFAULT_NARRATION);
			setTooltip(Tooltip.create(Component.translatable("smartshots.settings.archive_now.tooltip")));
		}

		private static void start() {
			CompletableFuture<ShotArchiver.Result> pending = ShotArchiver.lastManual();
			if (pending != null && !pending.isDone()) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			SmartShotsConfig.save(); // run with what is on screen now
			ShotArchiver.runNow().thenAccept(r -> mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(),
				CaptureService.TOAST_ID, Component.translatable("smartshots.archive.toast.title"), status(r))));
		}

		private static Component status(ShotArchiver.Result r) {
			return r.shots() == 0
				? Component.translatable("smartshots.archive.nothing")
				: Component.translatable("smartshots.archive.done", r.shots(), String.format(Locale.ROOT, "%.1f", Math.max(0, r.freedBytes()) / 1_048_576.0));
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			var font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			int w = getWidth();
			if (isHoveredOrFocused()) {
				Ui.rect(g, x, y, w, getHeight(), Theme.HOVER);
			}
			CompletableFuture<ShotArchiver.Result> f = ShotArchiver.lastManual();
			boolean busy = f != null && !f.isDone();
			Component line;
			int lineColor = Theme.TEXT_FAINT;
			if (busy) {
				line = Component.translatable("smartshots.archive.running");
			} else if (f != null && !f.isCompletedExceptionally()) {
				line = status(f.join());
				lineColor = Theme.ACCENT;
			} else {
				line = Component.translatable("smartshots.settings.archive_now.hint");
			}
			String pill = Component.translatable(busy ? "smartshots.archive.button_busy" : "smartshots.archive.button").getString();
			int pw = font.width(pill) + 16;
			int px = x + w - 8 - pw;
			int textW = px - x - 16;
			g.text(font, Ui.ellipsize(font, getMessage().getString(), textW), x + 8, y + 6, Theme.TEXT, false);
			g.text(font, Ui.ellipsize(font, line.getString(), textW), x + 8, y + 17, lineColor, false);
			Ui.rect(g, px, y + 7, pw, 16, busy ? Theme.SURFACE_3 : isHoveredOrFocused() ? 0xFF85B7FF : Theme.ACCENT);
			g.text(font, pill, px + 8, y + 11, busy ? Theme.TEXT_DIM : 0xFF0D1220, false);
		}
	}

	/**
	 * "Forget met players": the first click arms it (the pill turns red and asks again), the second
	 * one within a few seconds clears the list.
	 */
	private static final class ForgetRow extends net.minecraft.client.gui.components.Button {
		private static final long CONFIRM_MS = 4000;
		private long armedAt = -1;
		private boolean done;

		ForgetRow(int x, int w) {
			super(x, 0, w, 30, Component.translatable("smartshots.settings.meet_forget"), b -> ((ForgetRow) b).click(), DEFAULT_NARRATION);
			setTooltip(Tooltip.create(Component.translatable("smartshots.settings.meet_forget.tooltip",
				SeenPlayers.file().toString())));
			SeenPlayers.refresh(); // for the count
		}

		private boolean armed() {
			return armedAt >= 0 && net.minecraft.util.Util.getMillis() - armedAt < CONFIRM_MS;
		}

		private void click() {
			if (armed()) {
				armedAt = -1;
				SeenPlayers.forgetAll();
				done = true;
			} else {
				armedAt = net.minecraft.util.Util.getMillis();
				done = false;
			}
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			var font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			int w = getWidth();
			if (isHoveredOrFocused()) {
				Ui.rect(g, x, y, w, getHeight(), Theme.HOVER);
			}
			boolean armed = armed();
			Component line = done
				? Component.translatable("smartshots.settings.meet_forget.done")
				: Component.translatable("smartshots.settings.meet_forget.count", SeenPlayers.count());
			String pill = Component.translatable(armed ? "smartshots.settings.meet_forget.confirm" : "smartshots.settings.meet_forget.button").getString();
			int pw = font.width(pill) + 16;
			int px = x + w - 8 - pw;
			int textW = px - x - 16;
			g.text(font, Ui.ellipsize(font, getMessage().getString(), textW), x + 8, y + 6, Theme.TEXT, false);
			g.text(font, Ui.ellipsize(font, line.getString(), textW), x + 8, y + 17, done ? Theme.ACCENT : Theme.TEXT_FAINT, false);
			Ui.rect(g, px, y + 7, pw, 16, armed ? 0xFFE05252 : Theme.SURFACE_3);
			g.text(font, pill, px + 8, y + 11, armed ? 0xFFFFFFFF : Theme.TEXT, false);
		}
	}

	/** Segmented choice for notifications. */
	private static final class NotifyRow extends net.minecraft.client.gui.components.Button {
		private final String key;
		private final boolean auto;

		NotifyRow(int x, int w, String key, boolean auto) {
			super(x, 0, w, 30, Component.translatable(key), b -> {
			}, DEFAULT_NARRATION);
			this.key = key;
			this.auto = auto;
			setHeight(Ui.rowHeight(Minecraft.getInstance().font, description(), textW()));
		}

		private String description() {
			return Component.translatable(key + ".tooltip").getString();
		}

		private int textW() {
			return getWidth() - 3 * 46 - 30;
		}

		private SmartShotsConfig.Notify get() {
			return auto ? SmartShotsConfig.get().autoNotify : SmartShotsConfig.get().manualNotify;
		}

		private void set(SmartShotsConfig.Notify n) {
			if (auto) {
				SmartShotsConfig.get().autoNotify = n;
			} else {
				SmartShotsConfig.get().manualNotify = n;
			}
		}

		private int segX(int i, int segW) {
			return getX() + getWidth() - 8 - 3 * segW + i * segW;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			var font = net.minecraft.client.Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			Ui.rowText(g, font, getMessage().getString(), description(), Theme.TEXT_FAINT, x + 8, y, textW());
			int segW = 46;
			int sy = y + (getHeight() - 14) / 2;
			Ui.rect(g, segX(0, segW) - 2, sy - 2, 3 * segW + 4, 18, Theme.SURFACE_2);
			SmartShotsConfig.Notify[] all = SmartShotsConfig.Notify.values();
			for (int i = 0; i < all.length; i++) {
				int sx = segX(i, segW);
				boolean active = get() == all[i];
				boolean hover = mouseX >= sx && mouseX < sx + segW && mouseY >= sy && mouseY < sy + 14;
				if (active) {
					Ui.rect(g, sx, sy, segW, 14, Theme.SURFACE_3);
				} else if (hover) {
					Ui.rect(g, sx, sy, segW, 14, Theme.HOVER);
				}
				String label = Component.translatable("smartshots.notify." + all[i].name().toLowerCase()).getString();
				g.text(font, label, sx + (segW - font.width(label)) / 2, sy + 3, active ? Theme.TEXT : Theme.TEXT_DIM, false);
			}
		}

		@Override
		public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
			int segW = 46;
			SmartShotsConfig.Notify[] all = SmartShotsConfig.Notify.values();
			for (int i = 0; i < all.length; i++) {
				int sx = segX(i, segW);
				if (event.x() >= sx && event.x() < sx + segW) {
					set(all[i]);
				}
			}
		}
	}

	/** Segmented choice over an enum; labels are {@code smartshots.settings.<key>.<value>}. */
	private static final class ChoiceRow<E extends Enum<E>> extends net.minecraft.client.gui.components.Button {
		private final String key;
		private final E[] values;
		private final java.util.function.Supplier<E> get;
		private final java.util.function.Consumer<E> set;

		ChoiceRow(int x, int w, String key, E[] values, java.util.function.Supplier<E> get, java.util.function.Consumer<E> set) {
			super(x, 0, w, 30, Component.translatable("smartshots.settings." + key), b -> {
			}, DEFAULT_NARRATION);
			this.key = key;
			this.values = values;
			this.get = get;
			this.set = set;
			setTooltip(Tooltip.create(Component.translatable("smartshots.settings." + key + ".tooltip")));
			setHeight(Ui.rowHeight(Minecraft.getInstance().font, hint(), textW()));
		}

		private String hint() {
			return Component.translatable("smartshots.settings." + key + ".hint").getString();
		}

		private int textW() {
			return getWidth() - 8 - values.length * segW() - 14;
		}

		private String label(E v) {
			return Component.translatable("smartshots.settings." + key + "." + v.name().toLowerCase(Locale.ROOT)).getString();
		}

		private int segW() {
			var font = Minecraft.getInstance().font;
			int w = 30;
			for (E v : values) {
				w = Math.max(w, font.width(label(v)) + 10);
			}
			return w;
		}

		private int segX(int i, int segW) {
			return getX() + getWidth() - 8 - values.length * segW + i * segW;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			var font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			int segW = segW();
			Ui.rowText(g, font, getMessage().getString(), hint(), Theme.TEXT_FAINT, x + 8, y, textW());
			int sy = y + (getHeight() - 14) / 2;
			Ui.rect(g, segX(0, segW) - 2, sy - 2, values.length * segW + 4, 18, Theme.SURFACE_2);
			for (int i = 0; i < values.length; i++) {
				int sx = segX(i, segW);
				boolean active = get.get() == values[i];
				boolean hover = mouseX >= sx && mouseX < sx + segW && mouseY >= sy && mouseY < sy + 14;
				if (active) {
					Ui.rect(g, sx, sy, segW, 14, Theme.SURFACE_3);
				} else if (hover) {
					Ui.rect(g, sx, sy, segW, 14, Theme.HOVER);
				}
				String label = label(values[i]);
				g.text(font, label, sx + (segW - font.width(label)) / 2, sy + 3, active ? Theme.TEXT : Theme.TEXT_DIM, false);
			}
		}

		@Override
		public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
			int segW = segW();
			for (int i = 0; i < values.length; i++) {
				int sx = segX(i, segW);
				if (event.x() >= sx && event.x() < sx + segW) {
					set.accept(values[i]);
				}
			}
		}
	}

	/** Opens the custom uploader dialog; the second line shows where it uploads to. */
	private static final class CustomUploadRow extends net.minecraft.client.gui.components.Button {
		CustomUploadRow(int x, int w, Screen parent) {
			super(x, 0, w, 30, Component.translatable("smartshots.settings.custom_upload"),
				b -> Minecraft.getInstance().gui.setScreen(new CustomUploadScreen(parent)), DEFAULT_NARRATION);
			setTooltip(Tooltip.create(Component.translatable("smartshots.settings.custom_upload.tooltip")));
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			var font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			int w = getWidth();
			if (isHoveredOrFocused()) {
				Ui.rect(g, x, y, w, getHeight(), Theme.HOVER);
			}
			String url = SmartShotsConfig.get().customUploadUrl;
			String pill = Component.translatable("smartshots.settings.custom_upload.button").getString();
			int pw = font.width(pill) + 16;
			int px = x + w - 8 - pw;
			int textW = px - x - 16;
			g.text(font, Ui.ellipsize(font, getMessage().getString(), textW), x + 8, y + 6, Theme.TEXT, false);
			String line = url.isBlank() ? Component.translatable("smartshots.settings.custom_upload.none").getString() : url;
			g.text(font, Ui.ellipsize(font, line, textW), x + 8, y + 17, Theme.TEXT_FAINT, false);
			Ui.rect(g, px, y + 7, pw, 16, Theme.SURFACE_3);
			g.text(font, pill, px + 8, y + 11, Theme.TEXT, false);
		}
	}

	/** Opens the movement log folder. */
	private static final class TrackFolderRow extends net.minecraft.client.gui.components.Button {
		TrackFolderRow(int x, int w) {
			super(x, 0, w, 30, Component.translatable("smartshots.settings.track_folder"), b -> {
				java.nio.file.Path dir = dev.vadyanik.smartshots.track.MovementLog.root();
				try {
					java.nio.file.Files.createDirectories(dir);
				} catch (java.io.IOException ignored) {
				}
				com.mojang.blaze3d.Blaze3D.openPath(dir);
			}, DEFAULT_NARRATION);
			setTooltip(Tooltip.create(Component.translatable("smartshots.settings.track_folder.tooltip")));
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			var font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			int w = getWidth();
			if (isHoveredOrFocused()) {
				Ui.rect(g, x, y, w, getHeight(), Theme.HOVER);
			}
			String pill = Component.translatable("smartshots.button.open_folder").getString();
			int pw = font.width(pill) + 16;
			int px = x + w - 8 - pw;
			int textW = px - x - 16;
			g.text(font, Ui.ellipsize(font, getMessage().getString(), textW), x + 8, y + 6, Theme.TEXT, false);
			g.text(font, Ui.ellipsize(font, dev.vadyanik.smartshots.track.MovementLog.DIR + "/", textW), x + 8, y + 17, Theme.TEXT_FAINT, false);
			Ui.rect(g, px, y + 7, pw, 16, Theme.SURFACE_3);
			g.text(font, pill, px + 8, y + 11, Theme.TEXT, false);
		}
	}
}
