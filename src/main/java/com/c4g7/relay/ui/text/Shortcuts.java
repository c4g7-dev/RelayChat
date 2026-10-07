package com.c4g7.relay.ui.text;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The letter of a Ctrl shortcut, read the way vanilla reads it. Minecraft 26.3+ takes it from the
 * keyboard layout ({@code KeyEvent.shortcutKey()}), so Ctrl+Z follows the key labelled Z on QWERTZ
 * or AZERTY; 26.2 has no such method, but there a letter key's code already is its ASCII letter.
 */
public final class Shortcuts {
	@Nullable
	private static final MethodHandle SHORTCUT_KEY = find();

	private Shortcuts() {
	}

	@Nullable
	private static MethodHandle find() {
		try {
			return MethodHandles.publicLookup().findVirtual(KeyEvent.class, "shortcutKey", MethodType.methodType(int.class));
		} catch (ReflectiveOperationException absent) {
			return null;
		}
	}

	/** True when the event's shortcut letter is {@code letter} (lower case). */
	public static boolean is(KeyEvent event, char letter) {
		return letter(event) == letter;
	}

	private static int letter(KeyEvent event) {
		int code = event.key();
		if (SHORTCUT_KEY != null) {
			try {
				code = (int) SHORTCUT_KEY.invokeExact(event);
			} catch (Throwable unexpected) {
				// keep the key code
			}
		}
		return code >= 'A' && code <= 'Z' ? code + ('a' - 'A') : code;
	}
}
