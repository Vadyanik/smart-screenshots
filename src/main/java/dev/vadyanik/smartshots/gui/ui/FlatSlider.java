package dev.vadyanik.smartshots.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Settings row with a title on the left and a thin slider + value on the right. */
public class FlatSlider extends AbstractSliderButton {
	private final Component title;
	private final DoubleFunction<String> format;
	private final DoubleConsumer apply;
	/** Number of intervals for a discrete slider (value snaps to i / steps), 0 = continuous. */
	private int steps;

	public FlatSlider(int x, int y, int w, Component title, double value, DoubleFunction<String> format, DoubleConsumer apply) {
		super(x, y, w, 30, Component.empty(), value);
		this.title = title;
		this.format = format;
		this.apply = apply;
		updateMessage();
	}

	/** Discrete slider with {@code count} choices; the value is then always {@code index / (count - 1)}. */
	public FlatSlider choices(int count) {
		this.steps = Math.max(1, count - 1);
		this.value = Math.round(value * steps) / (double) steps;
		updateMessage();
		return this;
	}

	@Override
	protected void setValue(double newValue) {
		if (steps > 0) {
			double snapped = Math.round(newValue * steps) / (double) steps;
			// Arrow keys nudge by a pixel's worth: move a whole notch instead of getting stuck.
			if (snapped == value && newValue != value && Minecraft.getInstance().getLastInputType().isKeyboard()) {
				snapped = value + Math.signum(newValue - value) / steps;
			}
			newValue = snapped;
		}
		super.setValue(newValue);
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(title.getString() + ": " + format.apply(value)));
	}

	@Override
	protected void applyValue() {
		apply.accept(value);
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		if (isHoveredOrFocused()) {
			Ui.rect(g, x, y, w, h, Theme.HOVER);
		}
		var font = Minecraft.getInstance().font;
		g.text(font, title, x + 8, y + 11, Theme.TEXT, false);
		String v = format.apply(value);
		int vw = font.width(v);
		g.text(font, v, x + w - 8 - vw, y + 11, Theme.ACCENT, false);

		int trackW = Math.min(110, w / 2 - 20);
		int tx = x + w - 16 - vw - trackW;
		int ty = y + h / 2;
		Ui.rect(g, tx, ty - 1, trackW, 2, Theme.SURFACE_3);
		int fill = (int) Math.round(trackW * value);
		g.fill(tx, ty - 1, tx + fill, ty + 1, Theme.ACCENT);
		int kx = tx + fill - 3;
		Ui.rect(g, kx, ty - 4, 6, 8, isHoveredOrFocused() ? 0xFFFFFFFF : Theme.TEXT);
	}
}
