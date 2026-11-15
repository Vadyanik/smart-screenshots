package dev.vadyanik.smartshots.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
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
	/** Width of the value column (the widest value), so tracks line up and don't move. */
	private int valueW = -1;
	private boolean draggingTrack;

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
		valueW = -1;
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

	private int valueWidth() {
		if (valueW < 0) {
			var font = Minecraft.getInstance().font;
			int n = steps > 0 ? steps : 40;
			for (int i = 0; i <= n; i++) {
				valueW = Math.max(valueW, font.width(format.apply(i / (double) n)));
			}
		}
		return valueW;
	}

	private int trackW() {
		return Math.max(40, Math.min(120, getWidth() / 2 - 20));
	}

	private int trackX() {
		return getX() + getWidth() - 16 - valueWidth() - trackW();
	}

	private boolean onTrack(double mx) {
		int tx = trackX();
		return mx >= tx - 6 && mx <= tx + trackW() + 6;
	}

	private void setFromMouse(double mx) {
		setValue(Math.clamp((mx - trackX()) / trackW(), 0.0, 1.0));
	}

	/** Only the track reacts to the mouse (the vanilla slider maps the whole row width). */
	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		draggingTrack = onTrack(event.x());
		if (draggingTrack) {
			setFromMouse(event.x());
		}
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		if (draggingTrack) {
			setFromMouse(event.x());
		}
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		if (draggingTrack) {
			draggingTrack = false;
			super.onRelease(event);
		}
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
		int trackW = trackW();
		int tx = trackX();
		g.text(font, Ui.ellipsize(font, title.getString(), tx - x - 20), x + 8, y + (h - 8) / 2, Theme.TEXT, false);
		String v = format.apply(value);
		g.text(font, v, x + w - 8 - font.width(v), y + (h - 8) / 2, Theme.ACCENT, false);

		int ty = y + h / 2;
		Ui.rect(g, tx, ty - 1, trackW, 2, Theme.SURFACE_3);
		if (steps > 1 && steps <= 20) {
			for (int i = 0; i <= steps; i++) {
				int nx = tx + Math.round(trackW * i / (float) steps);
				g.fill(nx, ty + 3, nx + 1, ty + 5, Theme.BORDER_STRONG);
			}
		}
		int fill = (int) Math.round(trackW * value);
		g.fill(tx, ty - 1, tx + fill, ty + 1, Theme.ACCENT);
		boolean hot = draggingTrack || isFocused() || isHovered() && onTrack(mouseX);
		int kx = tx + fill - 3;
		Ui.rect(g, kx, ty - 4, 6, 8, hot ? 0xFFFFFFFF : Theme.TEXT);
	}
}
