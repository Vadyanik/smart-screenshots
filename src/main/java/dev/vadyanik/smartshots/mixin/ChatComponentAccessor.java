package dev.vadyanik.smartshots.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The chat lines on screen, to record the chat that is visible on a screenshot. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("trimmedMessages")
	List<GuiMessage.Line> smartshots$getTrimmedMessages();

	@Accessor("chatScrollbarPos")
	int smartshots$getChatScrollbarPos();
}
