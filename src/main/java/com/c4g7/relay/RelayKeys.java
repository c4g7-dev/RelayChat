package com.c4g7.relay;

import com.c4g7.relay.chat.ChatWindow;
import com.c4g7.relay.ui.Settings;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;

/** "Chat settings" key (M by default): opens the chat with the first window's tab settings. */
public final class RelayKeys {
	private static KeyMapping settings;

	private RelayKeys() {
	}

	static void register() {
		settings = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.relaychat.settings", InputConstants.KEY_M, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (settings.consumeClick()) {
				openSettings(client);
			}
		});
	}

	private static void openSettings(Minecraft minecraft) {
		if (Relay.hub() == null || Relay.hub().windows().isEmpty()) {
			return;
		}
		ChatWindow window = Relay.hub().windows().getFirst();
		Settings.openTab(window, window.activeTab());
		if (minecraft.gui.screen() == null) {
			minecraft.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
		}
	}
}
