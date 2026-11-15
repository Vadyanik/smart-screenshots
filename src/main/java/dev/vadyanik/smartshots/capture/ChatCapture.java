package dev.vadyanik.smartshots.capture;

import dev.vadyanik.smartshots.mixin.ChatComponentAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The chat messages visible on screen at capture time, oldest first: the lines the chat draws
 * (all of the open chat's page, or the recent, not yet faded ones when it is closed), one entry
 * per message even when it wraps over several lines.
 */
final class ChatCapture {
	/** Closed chat lines fade out after this many GUI ticks (see ChatComponent). */
	private static final int FADE_TICKS = 200;

	private ChatCapture() {
	}

	static List<String> visible(Minecraft mc) {
		if (mc.gui.hud.isHidden() || mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
			return List.of();
		}
		ChatComponent chat = mc.gui.hud.getChat();
		ChatComponentAccessor acc = (ChatComponentAccessor) chat;
		List<GuiMessage.Line> lines = acc.smartshots$getTrimmedMessages();
		boolean open = chat.isChatFocused();
		int now = mc.gui.hud.getGuiTicks();
		int start = open ? acc.smartshots$getChatScrollbarPos() : 0;
		int end = Math.min(lines.size(), start + chat.getLinesPerPage());
		List<String> out = new ArrayList<>();
		GuiMessage prev = null;
		for (int i = start; i < end; i++) { // newest first
			GuiMessage.Line line = lines.get(i);
			if (!open && now - line.addedTime() >= FADE_TICKS) {
				continue;
			}
			GuiMessage msg = line.parent();
			if (msg == prev) {
				continue;
			}
			prev = msg;
			String text = msg.content().getString().strip();
			if (!text.isEmpty()) {
				out.add(text);
			}
		}
		Collections.reverse(out);
		return out;
	}
}
