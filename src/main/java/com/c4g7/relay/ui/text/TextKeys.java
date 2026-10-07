package com.c4g7.relay.ui.text;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.StringUtil;

/**
 * Maps keyboard input onto a {@link TextBuffer}: arrows with Shift to select and Ctrl to jump words,
 * Home/End, Ctrl+A/C/X/V, Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z), and word-wise Backspace/Delete.
 */
public final class TextKeys {
	private TextKeys() {
	}

	/** @return true when the key was an editing key and has been applied */
	public static boolean handle(TextBuffer buffer, KeyEvent event) {
		boolean shift = event.hasShiftDown();
		boolean word = event.hasControlDownWithQuirk() || event.hasAltDown();
		if (event.isSelectAll()) {
			buffer.selectAll();
			return true;
		}
		if (event.isCopy()) {
			clipboard(buffer.hasSelection() ? buffer.selectedText() : buffer.text());
			return true;
		}
		if (event.isCut()) {
			if (buffer.hasSelection()) {
				clipboard(buffer.cut());
			}
			return true;
		}
		if (event.isPaste()) {
			buffer.insert(clean(clipboard()));
			return true;
		}
		if (event.hasControlDownWithQuirk() && !event.hasAltDown()) {
			if (Shortcuts.is(event, 'z')) {
				if (shift) {
					buffer.redo();
				} else {
					buffer.undo();
				}
				return true;
			}
			if (Shortcuts.is(event, 'y') && !shift) {
				buffer.redo();
				return true;
			}
		}
		switch (event.key()) {
			case InputConstants.KEY_LEFT -> buffer.left(shift, word);
			case InputConstants.KEY_RIGHT -> buffer.right(shift, word);
			case InputConstants.KEY_HOME -> buffer.home(shift);
			case InputConstants.KEY_END -> buffer.end(shift);
			case InputConstants.KEY_BACKSPACE -> buffer.backspace(word);
			case InputConstants.KEY_DELETE -> buffer.delete(word);
			default -> {
				return false;
			}
		}
		return true;
	}

	public static void type(TextBuffer buffer, String typed) {
		String allowed = clean(typed);
		if (!allowed.isEmpty()) {
			buffer.type(allowed);
		}
	}

	/** Drops what chat text may not contain (section signs, control characters, line breaks). */
	public static String clean(String text) {
		return text == null ? "" : StringUtil.filterText(text.replace("\r\n", " ").replace('\n', ' ').replace('\t', ' '));
	}

	public static String clipboard() {
		return Minecraft.getInstance().keyboardHandler.getClipboard();
	}

	public static void clipboard(String text) {
		if (text != null && !text.isEmpty()) {
			Minecraft.getInstance().keyboardHandler.setClipboard(text);
		}
	}
}
