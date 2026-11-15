package dev.vadyanik.smartshots.gui.ui;

/** Colors of the flat dark UI. */
public final class Theme {
	public static final int OVERLAY = 0xE60D0E12;
	public static final int SURFACE = 0xFF15171C;
	public static final int SURFACE_2 = 0xFF1C1F26;
	public static final int SURFACE_3 = 0xFF262A33;
	public static final int BORDER = 0x14FFFFFF;
	public static final int BORDER_STRONG = 0x26FFFFFF;
	public static final int HOVER = 0x14FFFFFF;
	public static final int PRESS = 0x22FFFFFF;

	public static final int TEXT = 0xFFE9EBF0;
	public static final int TEXT_DIM = 0xFF9BA1AE;
	public static final int TEXT_FAINT = 0xFF5F6573;

	public static final int ACCENT = 0xFF6EA8FE;
	public static final int ACCENT_SOFT = 0x336EA8FE;
	public static final int DANGER = 0xFFF2606E;
	public static final int DANGER_SOFT = 0x33F2606E;
	public static final int GOLD = 0xFFF2C14E;
	public static final int GREEN = 0xFF5BD37A;
	public static final int PURPLE = 0xFFB69CFF;
	public static final int CYAN = 0xFF7FD1FF;
	public static final int CORAL = 0xFFFF8F7F;
	public static final int LIME = 0xFFB5E07F;
	public static final int MINT = 0xFF7FE0C0;

	private Theme() {
	}

	public static int alpha(int color, float a) {
		int base = color >>> 24;
		return ((int) (base * a) << 24) | (color & 0xFFFFFF);
	}
}
