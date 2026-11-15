package dev.vadyanik.smartshots.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Glyph lookups for styled player names (to spot glyphs a server resource pack would provide). */
@Mixin(Font.class)
public interface FontAccessor {
	@Accessor("provider")
	Font.Provider smartshots$getProvider();
}
