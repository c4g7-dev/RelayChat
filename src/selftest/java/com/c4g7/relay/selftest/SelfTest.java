package com.c4g7.relay.selftest;

import com.c4g7.relay.Relay;
import com.c4g7.relay.chat.ChatHub;
import com.c4g7.relay.chat.ChatTab;
import com.c4g7.relay.chat.ChatWindow;
import com.c4g7.relay.config.FilterConfig;
import com.c4g7.relay.ui.Frame;
import com.c4g7.relay.ui.RegexEditorScreen;
import com.c4g7.relay.ui.Settings;
import com.c4g7.relay.util.Rect;
import com.mojang.blaze3d.platform.InputConstants;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only: joins a world, drives Relay through real chat-screen input and writes
 * screenshots plus PASS/FAIL lines to the log. Never shipped in the release jar.
 */
public final class SelfTest implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("Relay/self-test");
	private static final long SETTLE_MILLIS = 1800L;
	/** Frames to render between steps, so screenshots show the new state even at a few FPS (shaders on llvmpipe). */
	private static final int SETTLE_FRAMES = 3;
	/** Time for the chunks around the showcase view to load and mesh after the teleport. */
	private static final long SCENE_MILLIS = 30000L;

	/**
	 * The README backdrop: a meadow hill in the {@code RelayTest} world, seen from the air in
	 * spectator mode (no hand, hotbar or crosshair) with the mid-morning light frozen.
	 */
	private static final List<String> SCENE = List.of(
		"gamemode spectator @a",
		"gamerule advance_time false",
		"gamerule advance_weather false",
		"weather clear",
		"time set 4000",
		"tp @a -1532 124 974 -22 10");

	/** Back in the game for the gameplay shot: creative (so flying keeps the view) with a Bed Wars hotbar. */
	private static final List<String> PLAYING = List.of(
		"gamemode creative @a",
		"item replace entity @a hotbar.0 with minecraft:diamond_sword",
		"item replace entity @a hotbar.1 with minecraft:bow",
		"item replace entity @a hotbar.2 with minecraft:red_wool 64",
		"item replace entity @a hotbar.3 with minecraft:golden_apple 3",
		"item replace entity @a hotbar.4 with minecraft:ender_pearl 2",
		"item replace entity @a hotbar.5 with minecraft:tnt 4",
		"item replace entity @a hotbar.6 with minecraft:fire_charge 8");

	private static final String BLOCKED = "You can't break blocks in the lobby!";

	/** Rendered frames so far, counted by {@link com.c4g7.relay.selftest.mixin.MinecraftMixin}. */
	public static volatile int frames;

	private final List<Runnable> steps = new ArrayList<>();
	private int next;
	private long nextAt;
	private int nextFrame;
	private long extraDelay;
	private boolean started;
	private int failures;

	@Override
	public void onInitializeClient() {
		this.plan();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.level == null) {
				skipUpgradePrompt(client);
				return;
			}
			long now = System.currentTimeMillis();
			if (!this.started) {
				this.started = true;
				this.nextAt = now + 6000L;
				return;
			}
			if (now < this.nextAt || frames < this.nextFrame || this.next >= this.steps.size()) {
				return;
			}
			try {
				this.steps.get(this.next++).run();
			} catch (Throwable failed) {
				this.failures++;
				LOGGER.error("RELAY-SELFTEST FAIL: step {} threw", this.next, failed);
			}
			this.nextAt = System.currentTimeMillis() + SETTLE_MILLIS + this.extraDelay;
			this.nextFrame = frames + SETTLE_FRAMES;
			this.extraDelay = 0;
		});
	}

	/** The test world may come from an older version; confirm vanilla's "back it up first?" prompt. */
	private static void skipUpgradePrompt(Minecraft client) {
		Screen current = client.gui.screen();
		if (current == null) {
			return;
		}
		String type = current.getClass().getSimpleName();
		String skip;
		if (type.contains("BackupConfirm")) {
			skip = Component.translatable("selectWorld.backupJoinSkipButton").getString();
		} else if (type.contains("Confirm")) {
			// "Upgrading world completed, join now?"
			skip = Component.translatable("gui.yes").getString();
		} else {
			return;
		}
		for (var child : current.children()) {
			if (child instanceof AbstractWidget widget && widget.getMessage().getString().equals(skip)) {
				current.mouseClicked(new MouseButtonEvent(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0,
					new MouseButtonInfo(0, 0)), false);
				LOGGER.info("RELAY-SELFTEST skipped the world upgrade backup prompt");
				return;
			}
		}
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static ChatHub hub() {
		return Relay.hub();
	}

	private void check(String what, boolean passed) {
		if (passed) {
			LOGGER.info("RELAY-SELFTEST PASS: {}", what);
		} else {
			this.failures++;
			LOGGER.error("RELAY-SELFTEST FAIL: {}", what);
		}
	}

	private static void shot(String name) {
		Screenshot.grab(mc().gameDirectory, "relay-" + name + ".png", mc().gameRenderer.mainRenderTarget(), 1, message -> {});
		LOGGER.info("RELAY-SELFTEST shot {} ({} fps)", name, mc().getFps());
	}

	/** Runs a command as the integrated server, silently (no "set game mode" lines in the chat). */
	private static void command(String command) {
		MinecraftServer server = mc().getSingleplayerServer();
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
	}

	private static void say(Component message) {
		mc().gui.hud.getChat().addClientSystemMessage(message);
	}

	private static MutableComponent text(String text, ChatFormatting... style) {
		return Component.literal(text).withStyle(style);
	}

	/** A player chat line the way network chat plugins format it: {@code [RANK] Name » text}. */
	private static Component player(String rank, ChatFormatting rankColour, String name, String message) {
		return Component.empty()
			.append(text("[" + rank + "] ", rankColour))
			.append(text(name, ChatFormatting.WHITE))
			.append(text(" » ", ChatFormatting.DARK_GRAY))
			.append(text(message, ChatFormatting.GRAY));
	}

	/** A Conduit service lifecycle line, as Conduit prints it to staff. */
	private static Component conduit(String service, String state, ChatFormatting stateColour) {
		return Component.empty()
			.append(text("[", ChatFormatting.DARK_GRAY))
			.append(text("ℹ", ChatFormatting.AQUA))
			.append(text("] ", ChatFormatting.DARK_GRAY))
			.append(text("⌞Conduit⌝ ", ChatFormatting.GOLD))
			.append(text("ᐅ ", ChatFormatting.GRAY, ChatFormatting.BOLD))
			.append(text("Service " + service + " (Paper 26.2) ", ChatFormatting.WHITE))
			.append(text(state, stateColour));
	}

	private static Screen screen() {
		return mc().gui.screen();
	}

	private static void click(double x, double y, boolean shift) {
		screen().mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, shift ? InputConstants.MOD_SHIFT : 0)), false);
		screen().mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
	}

	private static void drag(double fromX, double fromY, double toX, double toY) {
		screen().mouseClicked(new MouseButtonEvent(fromX, fromY, new MouseButtonInfo(0, 0)), false);
		screen().mouseDragged(new MouseButtonEvent(toX, toY, new MouseButtonInfo(0, 0)), toX - fromX, toY - fromY);
		screen().mouseReleased(new MouseButtonEvent(toX, toY, new MouseButtonInfo(0, 0)));
	}

	private static void key(int key, int modifiers) {
		key(key, '\0', modifiers);
	}

	/**
	 * A key with the letter the keyboard layout gives it. 26.3+ reads Ctrl shortcuts from that
	 * letter (the event's second field); on 26.2 the field is the unused scancode.
	 */
	private static void key(int key, char letter, int modifiers) {
		screen().keyPressed(new KeyEvent(key, letter, modifiers));
	}

	private static void type(String text) {
		text.codePoints().forEach(codepoint -> screen().charTyped(new CharacterEvent(codepoint)));
	}

	private static ChatWindow mainWindow() {
		return hub().windows().stream().filter(hub()::isMainWindow).findFirst().orElseThrow();
	}

	private static ChatWindow lifecyclesWindow() {
		return hub().windows().stream().filter(window -> window.tabs().stream().anyMatch(tab -> "Lifecycles".equals(tab.config().name))).findFirst()
			.orElse(null);
	}

	private void plan() {
		this.steps.add(() -> {
			this.check("VelvetChat config was imported (2 windows)", hub().windows().size() == 2);
			SCENE.forEach(SelfTest::command);
			// Only as tall as the lifecycle lines need, so more of the view shows.
			lifecyclesWindow().config().geometry.height = 90;
			this.extraDelay = SCENE_MILLIS;
		});
		this.steps.add(() -> {
			// Start from empty tabs (no "→ RelayTest" join divider), then play a short lobby-to-game session.
			mc().gui.hud.getChat().clearMessages(false);
			say(text("Welcome back, c4g7! 3 of your friends are online.", ChatFormatting.YELLOW));
			say(conduit("lobby-1", "is starting…", ChatFormatting.YELLOW));
			say(conduit("lobby-1", "is online!", ChatFormatting.GREEN));
			say(player("VIP", ChatFormatting.GREEN, "Alex", "anyone up for bedwars?"));
			say(player("MVP", ChatFormatting.AQUA, "Steve", "yeah, queue 4v4 in a sec"));
			say(Component.empty()
				.append(text("[Tip] ", ChatFormatting.GOLD))
				.append(text("Relay Chat is open source: ", ChatFormatting.GRAY))
				.append(Component.literal("github.com/c4g7-dev/RelayChat").withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create("https://github.com/c4g7-dev/RelayChat")))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open in your browser"))))));
			for (int i = 0; i < 3; i++) {
				say(text(BLOCKED, ChatFormatting.RED));
			}
			say(conduit("bedwars-2", "is starting…", ChatFormatting.YELLOW));
			say(conduit("bedwars-2", "is online!", ChatFormatting.GREEN));
			hub().divider(text("⇄ bedwars-2 · 20:41", ChatFormatting.GRAY));
			say(Component.empty().append(text("Bed Wars ▸ ", ChatFormatting.GOLD)).append(text("The game starts in 5 seconds!", ChatFormatting.YELLOW)));
			say(player("MVP", ChatFormatting.AQUA, "Steve", "gl hf"));
			say(player("VIP", ChatFormatting.GREEN, "Alex", "does anyone know if the diamond generators are in the middle on this map, or out on the islands?"));
		});
		this.steps.add(() -> {
			ChatTab main = mainWindow().activeTab();
			ChatWindow lifecycles = lifecyclesWindow();
			this.check("lifecycle lines were kept out of Main", main.entries().stream().noneMatch(entry -> entry.message().plain().contains("Conduit")));
			this.check("Lifecycles tab caught all four lifecycle lines",
				lifecycles != null && lifecycles.activeTab().entries().stream().filter(entry -> entry.message().plain().contains("Conduit")).count() == 4);
			this.check("the server divider reached both tabs",
				main.entries().stream().anyMatch(entry -> entry.message().isDivider())
					&& lifecycles.activeTab().entries().stream().anyMatch(entry -> entry.message().isDivider()));
			this.check("identical messages were combined",
				main.entries().stream().anyMatch(entry -> entry.repeats() == 3 && entry.message().plain().equals(BLOCKED)));
			mc().gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
		});
		this.steps.add(() -> shot("01-chat"));
		this.steps.add(() -> {
			Rect bounds = Frame.boundsOf(mainWindow());
			click(bounds.right() - 5, bounds.y() + 7, false);
		});
		this.steps.add(() -> shot("02-menu"));
		this.steps.add(() -> {
			Rect bounds = Frame.boundsOf(mainWindow());
			// Third item: Settings
			click(bounds.right() - 45, bounds.y() + 14 + 1 + 2 * 12 + 6, false);
			this.check("menu opened the tab settings", Settings.isOpen(mainWindow()));
		});
		this.steps.add(() -> shot("03-tab-settings"));
		this.steps.add(() -> {
			Rect bounds = Frame.boundsOf(mainWindow());
			int rowY = bounds.y() + 14 + 16 + 3 + 9;
			click(bounds.right() - 30, rowY, false);
			type("Main chat");
			key(InputConstants.KEY_LEFT, InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT);
			this.check("title field is being edited", Settings.isEditing());
		});
		this.steps.add(() -> shot("04-text-selection"));
		this.steps.add(() -> {
			key(InputConstants.KEY_A, 'a', InputConstants.MOD_CONTROL);
			type("Server");
			key(InputConstants.KEY_Z, 'z', InputConstants.MOD_CONTROL);
			key(InputConstants.KEY_Y, 'y', InputConstants.MOD_CONTROL);
			key(InputConstants.KEY_RETURN, 0);
			this.check("title committed through select-all, typing, undo and redo", "Server".equals(mainWindow().activeTab().config().name));
			mainWindow().activeTab().config().name = "";
			Settings.close(mainWindow());
			ChatWindow lifecycles = lifecyclesWindow();
			ChatTab tab = lifecycles.activeTab();
			// Tall enough again to show the whole filter page.
			lifecycles.config().geometry.height = 150;
			Settings.openFilter(lifecycles, tab, tab.config().filters.getFirst());
		});
		this.steps.add(() -> shot("05-filter"));
		this.steps.add(() -> {
			ChatWindow lifecycles = lifecyclesWindow();
			ChatTab tab = lifecycles.activeTab();
			FilterConfig filter = tab.config().filters.getFirst();
			RegexEditorScreen.open(lifecycles, tab, filter, true);
		});
		this.steps.add(() -> {
			this.check("regex editor is open", screen() instanceof RegexEditorScreen);
			key(InputConstants.KEY_TAB, 0);
			type("[ℹ] ⌞Conduit⌝ ᐅ Service bedwars-2 (Paper 26.2) is stopping…");
			key(InputConstants.KEY_TAB, 0);
			key(InputConstants.KEY_HOME, 0);
			for (int i = 0; i < 6; i++) {
				key(InputConstants.KEY_RIGHT, InputConstants.MOD_SHIFT);
			}
		});
		this.steps.add(() -> shot("06-regex-editor"));
		this.steps.add(() -> {
			key(InputConstants.KEY_END, 0);
			type("(");
		});
		this.steps.add(() -> shot("07-regex-error"));
		this.steps.add(() -> {
			key(InputConstants.KEY_BACKSPACE, 0);
			key(InputConstants.KEY_ESCAPE, 0);
			this.check("Esc returns to the chat screen", screen() instanceof ChatScreen);
			this.check("cancel kept the original pattern", lifecyclesWindow().activeTab().config().filters.getFirst().includeRegex.startsWith("(?is)"));
		});
		this.steps.add(() -> {
			Settings.close(lifecyclesWindow());
			ChatWindow main = mainWindow();
			main.config().locked = true;
			Rect before = Frame.boundsOf(main);
			int grabX = before.x() + before.width() / 2;
			drag(grabX, before.y() + 5, grabX + 100, before.y() - 60);
			this.check("a locked window does not move", Frame.boundsOf(main).equals(before));
			main.config().locked = false;
			drag(grabX, before.y() + 5, grabX + 30, before.y() - 20);
			this.check("an unlocked window moves", !Frame.boundsOf(main).equals(before));
			main.config().geometry.store(before, Frame.screen().width(), Frame.screen().height());
			main.config().locked = true;
		});
		this.steps.add(() -> shot("08-locked"));
		this.steps.add(() -> {
			ChatComponent chat = mc().gui.hud.getChat();
			int before = mainWindow().activeTab().entries().size();
			hub().config().behaviour.keepAcrossServers = false;
			ChatComponent.State state = chat.storeState();
			chat.clearMessages(true);
			int cleared = mainWindow().activeTab().entries().size();
			chat.restoreState(state);
			int restored = mainWindow().activeTab().entries().size();
			this.check("proxy switch restores the tabs (" + before + " -> " + cleared + " -> " + restored + ")", cleared == 0 && restored == before);
			hub().config().behaviour.keepAcrossServers = true;
			chat.clearMessages(true);
			this.check("keep-across-servers survives a disconnect clear", mainWindow().activeTab().entries().size() == before);
			chat.clearMessages(false);
			this.check("F3+D still clears everything", mainWindow().activeTab().entries().isEmpty());
			mainWindow().config().locked = false;
			hub().save();
		});
		// Gameplay shot: chat closed, hotbar and held item in view, fresh lines showing in the windows.
		this.steps.add(() -> {
			mc().gui.setScreen(null);
			lifecyclesWindow().config().geometry.height = 90;
			PLAYING.forEach(SelfTest::command);
		});
		this.steps.add(() -> {
			mc().player.getAbilities().flying = true;
			mc().player.onUpdateAbilities();
			mc().player.getInventory().setSelectedSlot(0);
			// Drop the "game mode updated" line and the recipe toasts the creative switch brings.
			mc().gui.hud.getChat().clearMessages(false);
			mc().gui.toastManager().clear();
			say(conduit("bedwars-3", "is starting…", ChatFormatting.YELLOW));
			say(Component.empty().append(text("Bed Wars ▸ ", ChatFormatting.GOLD)).append(text("Red's bed was destroyed by Alex!", ChatFormatting.RED)));
			say(player("MVP", ChatFormatting.AQUA, "Steve", "noo, our bed!"));
			say(conduit("bedwars-3", "is online!", ChatFormatting.GREEN));
			say(player("VIP", ChatFormatting.GREEN, "Alex", "gg, two teams left"));
			say(Component.empty().append(text("Bed Wars ▸ ", ChatFormatting.GOLD)).append(text("+12 coins (Final Kill)", ChatFormatting.YELLOW)));
		});
		this.steps.add(() -> {
			this.check("the windows show new messages with the chat closed",
				!(screen() instanceof ChatScreen) && hub().windows().stream().allMatch(window -> window.config().visibility.drawnWhile(false)));
			mc().gui.toastManager().clear();
			shot("09-playing");
		});
		this.steps.add(() -> {
			LOGGER.info("RELAY-SELFTEST DONE with {} failure(s)", this.failures);
			mc().stop();
		});
	}
}
