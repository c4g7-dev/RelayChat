package com.c4g7.relay.ui;

import com.c4g7.relay.Relay;
import com.c4g7.relay.chat.ChatHub;
import com.c4g7.relay.chat.ChatTab;
import com.c4g7.relay.chat.ChatWindow;
import com.c4g7.relay.config.FilterConfig;
import com.c4g7.relay.config.HexColor;
import com.c4g7.relay.config.RelayConfig;
import com.c4g7.relay.config.Style;
import com.c4g7.relay.config.TabConfig;
import com.c4g7.relay.filter.FilterMatcher;
import com.c4g7.relay.ui.text.FieldView;
import com.c4g7.relay.ui.text.TextBuffer;
import com.c4g7.relay.ui.text.TextKeys;
import com.c4g7.relay.util.Rect;
import com.c4g7.relay.util.Tr;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The settings panel that slides into a chat window's body: tab settings, the filter list, a filter,
 * the colour picker and Relay's own behaviour page. Layout, colours and animations match VelvetChat.
 */
public final class Settings {
	public enum Page {
		MESSAGES,
		TAB,
		FILTERS,
		FILTER,
		COLOR,
		RELAY
	}

	private enum Kind {
		TOGGLE,
		TEXT,
		SUB,
		ACTION,
		ADD,
		CYCLE,
		PAIR,
		COLOR,
		PATTERN
	}

	private record ColorTarget(IntSupplier get, IntConsumer set) {
	}

	private record Row(
		String label,
		Kind kind,
		Supplier<String> value,
		@Nullable Runnable click,
		@Nullable Consumer<String> commit,
		@Nullable BooleanSupplier on,
		@Nullable Runnable remove,
		@Nullable ColorTarget color,
		@Nullable Identifier icon
	) {
		static Row of(String label, Kind kind, Supplier<String> value, Runnable click) {
			return new Row(label, kind, value, click, null, null, null, null, null);
		}
	}

	private static final class State {
		private Page page = Page.MESSAGES;
		private ChatTab tab;
		private FilterConfig filter;
		private int scroll;
		private int editingRow = -1;
		private FieldView field;
		private ColorTarget color;
		private String colorTitle = "";
		private Page colorReturn = Page.TAB;
		private int draggedChannel = -1;
		private FieldView hexField;
		private final Map<Integer, Tween> hovers = new HashMap<>();
		private final Map<Integer, Tween> toggles = new HashMap<>();
	}

	// layout
	private static final int HEADER = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int ROW_STRIDE = 20;
	private static final int PADDING = 4;
	private static final int EDGE_GAP = 3;
	private static final int BACK_BUTTON = 14;
	private static final int BAR_HEIGHT = 9;
	private static final int BAR_STRIDE = 11;
	private static final int BAR_LABEL = 8;
	private static final int PREVIEW_HEIGHT = 12;
	private static final int MAX_TEXT = 256;

	private static final Map<ChatWindow, State> STATES = new WeakHashMap<>();
	@Nullable
	private static ChatWindow editingWindow;

	private Settings() {
	}

	private static State state(ChatWindow window) {
		return STATES.computeIfAbsent(window, key -> new State());
	}

	private static ChatHub hub() {
		return Relay.hub();
	}

	private static RelayConfig root() {
		return hub().config();
	}

	private static String tr(String key, String english) {
		return Tr.get(key, english);
	}

	// ---- navigation -----------------------------------------------------------------------------

	public static boolean isOpen(ChatWindow window) {
		return state(window).page != Page.MESSAGES;
	}

	public static boolean isEditing() {
		return editingWindow != null && state(editingWindow).editingRow >= 0;
	}

	public static void openTab(ChatWindow window, ChatTab tab) {
		State state = state(window);
		stopEditing(window, false);
		state.tab = tab;
		state.filter = null;
		go(state, Page.TAB);
	}

	/** Opens a filter page directly, used when coming back from the regex editor. */
	public static void openFilter(ChatWindow window, ChatTab tab, FilterConfig filter) {
		State state = state(window);
		stopEditing(window, false);
		state.tab = tab;
		state.filter = filter;
		go(state, Page.FILTER);
	}

	public static void close(ChatWindow window) {
		State state = state(window);
		stopEditing(window, false);
		state.filter = null;
		go(state, Page.MESSAGES);
	}

	public static void back(ChatWindow window) {
		State state = state(window);
		stopEditing(window, true);
		switch (state.page) {
			case FILTERS, RELAY -> go(state, Page.TAB);
			case FILTER -> {
				state.filter = null;
				go(state, Page.FILTERS);
			}
			case COLOR -> {
				state.color = null;
				state.hexField = null;
				state.draggedChannel = -1;
				go(state, state.colorReturn);
			}
			default -> close(window);
		}
	}

	private static void go(State state, Page page) {
		state.page = page;
		state.scroll = 0;
		state.hovers.clear();
		state.toggles.clear();
	}

	// ---- rows -----------------------------------------------------------------------------------

	private static List<Row> rows(ChatWindow window) {
		State state = state(window);
		return switch (state.page) {
			case MESSAGES, COLOR -> List.of();
			case TAB -> tabRows(window, state);
			case FILTERS -> filterListRows(state);
			case FILTER -> filterRows(window, state);
			case RELAY -> relayRows();
		};
	}

	private static List<Row> tabRows(ChatWindow window, State state) {
		List<Row> rows = new ArrayList<>();
		ChatTab tab = state.tab;
		if (tab == null || !window.contains(tab)) {
			return rows;
		}
		TabConfig config = tab.config();
		Style style = config.style(root());
		rows.add(text(tr("relaychat.settings.title", "Title"), () -> config.name, value -> config.name = value));
		rows.add(Row.of(tr("relaychat.settings.filters", "Filters"), Kind.SUB, () -> String.valueOf(config.filters.size()), () -> go(state, Page.FILTERS)));
		rows.add(toggle(tr("relaychat.settings.use_global", "Use global settings"), () -> config.useGlobal, value -> config.useGlobal = value));
		rows.add(text(tr("relaychat.settings.line_limit", "Line limit"), () -> String.valueOf(style.lineLimit), value -> {
			try {
				style.lineLimit = Math.max(1, Math.min(5000, Integer.parseInt(value.strip())));
			} catch (NumberFormatException ignored) {
				// keep the old limit
			}
		}));
		rows.add(toggle(tr("relaychat.settings.combine", "Combine identical messages"), () -> style.combineDuplicates, value -> style.combineDuplicates = value));
		rows.add(toggle(tr("relaychat.settings.anti_chat_clear", "Anti chat clear"), () -> style.keepOnClear, value -> style.keepOnClear = value));
		rows.add(toggle(tr("relaychat.settings.chat_trust", "Chat trust"), () -> style.chatTrust, value -> style.chatTrust = value));
		rows.add(toggle(tr("relaychat.settings.shadow", "Text shadow"), () -> style.shadow, value -> style.shadow = value));
		rows.add(toggle(tr("relaychat.settings.background", "Background"), () -> style.background, value -> style.background = value));
		if (style.background) {
			rows.add(color(window, tr("relaychat.settings.background_colour", "Background colour"),
				new ColorTarget(() -> style.backgroundColor, value -> style.backgroundColor = value), Page.TAB));
		}
		rows.add(toggle(tr("relaychat.settings.show_marker", "Message marker"), () -> style.marker, value -> style.marker = value));
		if (style.marker) {
			rows.add(color(window, tr("relaychat.settings.marker_colour", "Marker colour"),
				new ColorTarget(() -> style.markerColor, value -> style.markerColor = value), Page.TAB));
		}
		rows.add(toggle(tr("relaychat.settings.show_timestamp", "Show time"), () -> style.timestamps, value -> style.timestamps = value));
		if (style.timestamps) {
			rows.add(color(window, tr("relaychat.settings.timestamp_colour", "Time colour"),
				new ColorTarget(() -> style.timestampColor, value -> style.timestampColor = value), Page.TAB));
		}
		rows.add(text(tr("relaychat.settings.timestamp_format", "Time format"), () -> style.timestampFormat,
			value -> style.timestampFormat = value.isBlank() ? Style.DEFAULT_TIME_FORMAT : value.strip()));
		rows.add(Row.of(tr("relaychat.settings.show_window", "Show window"), Kind.CYCLE,
			() -> tr(window.config().visibility.key, window.config().visibility.english),
			() -> {
				window.config().visibility = window.config().visibility.next();
				hub().save();
			}));
		rows.add(toggle(tr("relaychat.settings.lock_window", "Lock window"), () -> window.config().locked, value -> window.config().locked = value));
		rows.add(Row.of(tr("relaychat.settings.relay", "Relay Chat settings"), Kind.SUB, () -> "", () -> go(state, Page.RELAY)));
		if (!config.isMain()) {
			rows.add(action(tr("relaychat.settings.delete_tab", "Delete tab"), () -> {
				close(window);
				hub().deleteTab(tab);
			}));
		}
		if (hub().canDelete(window)) {
			rows.add(action(tr("relaychat.settings.delete_window", "Delete window"), () -> {
				close(window);
				hub().deleteWindow(window);
			}));
		}
		return rows;
	}

	private static List<Row> relayRows() {
		var behaviour = root().behaviour;
		List<Row> rows = new ArrayList<>();
		rows.add(toggle(tr("relaychat.settings.keep_across_servers", "Keep chat across servers"), () -> behaviour.keepAcrossServers,
			value -> behaviour.keepAcrossServers = value));
		rows.add(toggle(tr("relaychat.settings.server_divider", "Server divider"), () -> behaviour.serverDivider, value -> behaviour.serverDivider = value));
		rows.add(toggle(tr("relaychat.settings.restore", "Remember chat after restart"), () -> behaviour.restoreAfterRestart,
			value -> behaviour.restoreAfterRestart = value));
		rows.add(toggle(tr("relaychat.settings.draggable", "Draggable windows"), () -> behaviour.draggable, value -> behaviour.draggable = value));
		rows.add(toggle(tr("relaychat.settings.resizable", "Resizable windows"), () -> behaviour.resizable, value -> behaviour.resizable = value));
		return rows;
	}

	private static List<Row> filterListRows(State state) {
		List<Row> rows = new ArrayList<>();
		ChatTab tab = state.tab;
		if (tab == null) {
			return rows;
		}
		for (FilterConfig existing : tab.config().filters) {
			rows.add(new Row(existing.displayName(), Kind.PAIR, () -> "", () -> {
				state.filter = existing;
				go(state, Page.FILTER);
			}, null, null, () -> {
				tab.config().filters.remove(existing);
				hub().save();
			}, null, null));
		}
		rows.add(new Row(tr("relaychat.settings.add_filter", "Add filter"), Kind.ADD, () -> "", () -> {
			FilterConfig created = new FilterConfig();
			tab.config().filters.add(created);
			state.filter = created;
			go(state, Page.FILTER);
			hub().save();
		}, null, null, null, null, Icons.PLUS));
		return rows;
	}

	private static List<Row> filterRows(ChatWindow window, State state) {
		List<Row> rows = new ArrayList<>();
		FilterConfig filter = state.filter;
		ChatTab tab = state.tab;
		if (filter == null || tab == null) {
			return rows;
		}
		rows.add(text(tr("relaychat.settings.name", "Name"), () -> filter.name, value -> filter.name = value));
		rows.add(toggle(tr("relaychat.settings.advanced", "Advanced (regex)"), filter::regex,
			value -> filter.mode = value ? FilterConfig.Mode.REGEX : FilterConfig.Mode.WORDS));
		if (filter.regex()) {
			rows.add(pattern(tr("relaychat.settings.include_regex", "Include regex"), () -> filter.includeRegex, filter,
				() -> RegexEditorScreen.open(window, tab, filter, true)));
			rows.add(pattern(tr("relaychat.settings.exclude_regex", "Exclude regex"), () -> filter.excludeRegex, filter,
				() -> RegexEditorScreen.open(window, tab, filter, false)));
			rows.add(Row.of(tr("relaychat.settings.regex_match", "Regex match"), Kind.CYCLE, () -> matchLabel(filter.regexMatch), () -> {
				filter.regexMatch = filter.regexMatch == FilterConfig.RegexMatch.CONTAINS ? FilterConfig.RegexMatch.WHOLE : FilterConfig.RegexMatch.CONTAINS;
				hub().save();
			}));
		} else {
			rows.add(text(tr("relaychat.settings.must_contain", "Must contain"), () -> String.join(", ", filter.include),
				value -> filter.include = words(value)));
			rows.add(text(tr("relaychat.settings.must_not_contain", "Must not contain"), () -> String.join(", ", filter.exclude),
				value -> filter.exclude = words(value)));
		}
		rows.add(toggle(tr("relaychat.settings.change_background", "Change background"), () -> filter.highlight, value -> filter.highlight = value));
		if (filter.highlight) {
			rows.add(color(window, tr("relaychat.settings.background_colour", "Background colour"),
				new ColorTarget(() -> filter.highlightColor, value -> filter.highlightColor = value), Page.FILTER));
		}
		rows.add(toggle(tr("relaychat.settings.play_sound", "Play sound"), () -> filter.sound, value -> filter.sound = value));
		rows.add(toggle(tr("relaychat.settings.keep_out_of_main", "Keep out of Main"), () -> filter.exclusive, value -> filter.exclusive = value));
		rows.add(toggle(tr("relaychat.settings.hide_message", "Hide message"), () -> filter.hide, value -> filter.hide = value));
		rows.add(toggle(tr("relaychat.settings.filter_tooltips", "Filter tooltips"), () -> filter.searchJson, value -> filter.searchJson = value));
		rows.add(toggle(tr("relaychat.settings.case_sensitive", "Case sensitive"), () -> filter.caseSensitive, value -> filter.caseSensitive = value));
		rows.add(action(tr("relaychat.settings.delete_filter", "Delete filter"), () -> {
			tab.config().filters.remove(filter);
			state.filter = null;
			go(state, Page.FILTERS);
			hub().save();
		}));
		return rows;
	}

	static String matchLabel(FilterConfig.RegexMatch match) {
		return match == FilterConfig.RegexMatch.WHOLE
			? tr("relaychat.regex.match.whole", "Whole message")
			: tr("relaychat.regex.match.contains", "Contains");
	}

	private static List<String> words(String value) {
		return Arrays.stream(value.split(",")).map(String::strip).filter(word -> !word.isEmpty()).collect(Collectors.toCollection(ArrayList::new));
	}

	private static Row toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
		return new Row(label, Kind.TOGGLE,
			() -> get.getAsBoolean() ? tr("relaychat.settings.on", "On") : tr("relaychat.settings.off", "Off"),
			() -> {
				set.accept(!get.getAsBoolean());
				hub().save();
			}, null, get, null, null, null);
	}

	private static Row text(String label, Supplier<String> get, Consumer<String> set) {
		return new Row(label, Kind.TEXT, get, null, value -> {
			set.accept(value);
			hub().save();
		}, null, null, null, null);
	}

	private static Row pattern(String label, Supplier<String> get, FilterConfig filter, Runnable open) {
		return new Row(label, Kind.PATTERN, get, open, null, () -> FilterMatcher.compile(get.get(), filter.caseSensitive).valid(), null, null, Icons.REGEX);
	}

	private static Row action(String label, Runnable click) {
		return new Row(label, Kind.ACTION, () -> "", click, null, null, null, null, Icons.BIN);
	}

	private static Row color(ChatWindow window, String label, ColorTarget target, Page returnsTo) {
		return new Row(label, Kind.COLOR, () -> "", () -> {
			State state = state(window);
			state.color = target;
			state.colorTitle = label;
			state.colorReturn = returnsTo;
			state.hexField = null;
			go(state, Page.COLOR);
		}, null, null, null, target, null);
	}

	private static String title(State state) {
		return switch (state.page) {
			case MESSAGES -> "";
			case TAB -> tr("relaychat.settings.panel.tab", "Chat tab");
			case FILTERS -> tr("relaychat.settings.filters", "Filters");
			case FILTER -> tr("relaychat.settings.panel.filter", "Chat filter");
			case COLOR -> state.colorTitle;
			case RELAY -> tr("relaychat.settings.relay", "Relay Chat settings");
		};
	}

	// ---- layout ---------------------------------------------------------------------------------

	private record Placed(int index, Rect bounds, Rect control) {
		Rect firstButton() {
			int size = buttonSize();
			int right = this.control.right() - 2 - size - 2;
			return new Rect(right - size, this.control.y(), right, this.control.bottom());
		}

		Rect secondButton() {
			int size = buttonSize();
			int right = this.control.right() - 2;
			return new Rect(right - size, this.control.y(), right, this.control.bottom());
		}

		Rect actionButton() {
			int size = Math.max(1, Math.min(this.control.height(), this.control.width() - 2));
			int right = this.control.right() - 2;
			return new Rect(right - size, this.control.y(), right, this.control.bottom());
		}

		Rect textArea() {
			return new Rect(this.control.x() + 3, this.control.y(), this.control.right() - 5, this.control.bottom());
		}

		private int buttonSize() {
			return Math.max(1, Math.min(this.control.height(), (this.control.width() - 4) / 2));
		}
	}

	private static Rect header(Rect body) {
		return new Rect(body.x(), body.y(), body.right(), body.y() + HEADER);
	}

	private static Rect backButton(Rect body) {
		return new Rect(body.x(), body.y(), body.x() + BACK_BUTTON, body.y() + HEADER);
	}

	private static int visibleRows(Rect body) {
		int usable = body.height() - HEADER - 2 * EDGE_GAP;
		return usable < ROW_HEIGHT ? 0 : 1 + (usable - ROW_HEIGHT) / ROW_STRIDE;
	}

	private static List<Placed> place(Rect body, int count, int scroll) {
		List<Placed> placed = new ArrayList<>();
		int rowWidth = body.width() - 2 * PADDING;
		if (rowWidth <= 0) {
			return placed;
		}
		int controlWidth = Math.max(28, Math.min(140, (int) (rowWidth * 0.42F)));
		controlWidth = Math.min(controlWidth, Math.max(1, rowWidth / 2));
		int top = body.y() + HEADER + EDGE_GAP;
		int left = body.x() + PADDING;
		for (int index = scroll; index < count; index++) {
			int y = top + (index - scroll) * ROW_STRIDE;
			if (y + ROW_HEIGHT + EDGE_GAP > body.bottom()) {
				break;
			}
			Rect bounds = new Rect(left, y, left + rowWidth, y + ROW_HEIGHT);
			placed.add(new Placed(index, bounds, new Rect(bounds.right() - controlWidth, y + 2, bounds.right(), y + ROW_HEIGHT - 2)));
		}
		return placed;
	}

	@Nullable
	private static Placed placedAt(List<Placed> placed, double x, double y) {
		for (Placed row : placed) {
			if (row.bounds().contains(x, y)) {
				return row;
			}
		}
		return null;
	}

	private static Rect pickerArea(Rect body) {
		return new Rect(body.x() + 4, body.y() + HEADER + 4, body.right() - 4, body.bottom());
	}

	private static Rect bar(Rect area, int channel) {
		int top = area.y() + channel * BAR_STRIDE;
		return new Rect(area.x() + BAR_LABEL, top, area.right(), top + BAR_HEIGHT);
	}

	private static Rect preview(Rect area) {
		int top = area.y() + 4 * BAR_STRIDE;
		return new Rect(area.x(), top, area.right(), top + PREVIEW_HEIGHT);
	}

	// ---- rendering ------------------------------------------------------------------------------

	public static void render(ChatWindow window, GuiGraphicsExtractor graphics, Font font, Rect body, double mouseX, double mouseY) {
		State state = state(window);
		if (state.page == Page.MESSAGES) {
			return;
		}
		Rect header = header(body);
		Gfx.fill(graphics, header, Theme.HEADER);
		Icons.draw(graphics, Icons.BACK, backButton(body), Theme.TEXT);
		graphics.text(font, Gfx.trim(font, title(state), header.width() - BACK_BUTTON - 4), header.x() + BACK_BUTTON, Gfx.centredText(header), Theme.TEXT, false);
		if (state.page == Page.COLOR) {
			renderPicker(graphics, font, body, state, mouseX, mouseY);
			return;
		}

		long now = System.currentTimeMillis();
		List<Row> rows = rows(window);
		int visible = visibleRows(body);
		state.scroll = Math.max(0, Math.min(state.scroll, Math.max(0, rows.size() - visible)));
		for (Placed placed : place(body, rows.size(), state.scroll)) {
			Row row = rows.get(placed.index());
			Rect bounds = placed.bounds();
			Rect control = placed.control();
			Tween hover = state.hovers.computeIfAbsent(placed.index(), key -> new Tween(0.0F, Theme.ROW_HOVER_FADE));
			float lit = hover.value(now);
			hover.target(bounds.contains(mouseX, mouseY) ? 1.0F : 0.0F, now);
			Gfx.rounded(graphics, bounds, 3, Theme.ROW);
			if (lit > 0.0F) {
				Gfx.rounded(graphics, bounds, 3, Gfx.alpha(Theme.ROW_HOVER, lit));
			}
			Gfx.leftBar(graphics, bounds.x(), bounds.y(), bounds.x() + 4, bounds.bottom(), 3, barColor(row));
			int labelLeft = bounds.x() + 6;
			graphics.text(font, Gfx.trim(font, row.label(), control.x() - labelLeft - 4), labelLeft, Gfx.centredText(bounds), Theme.TEXT, false);

			boolean editing = editingWindow == window && state.editingRow == placed.index();
			switch (row.kind()) {
				case TEXT, CYCLE, PATTERN -> {
					Gfx.rounded(graphics, control.x(), control.y(), control.right() - 2, control.bottom(), 4, editing ? Theme.EDITING : Theme.CONTROL);
					if (editing) {
						state.field.render(graphics, font, placed.textArea(), Gfx.fieldText(control), true, Theme.TEXT, null);
					} else if (row.kind() == Kind.PATTERN) {
						renderPatternPreview(graphics, font, placed, row);
					} else {
						graphics.text(font, Gfx.trim(font, row.value().get(), control.width() - 6), control.x() + 3, Gfx.fieldText(control), Theme.TEXT, false);
					}
				}
				case COLOR -> {
					if (row.color() != null) {
						Gfx.rounded(graphics, control.x(), control.y(), control.right() - 2, control.bottom(), 4, Theme.CHECKER);
						Gfx.rounded(graphics, control.x(), control.y(), control.right() - 2, control.bottom(), 4, row.color().get().getAsInt());
					}
				}
				case PAIR -> {
					Gfx.rounded(graphics, placed.firstButton(), 4, Theme.CONTROL);
					Gfx.rounded(graphics, placed.secondButton(), 4, Theme.CONTROL);
					Icons.draw(graphics, Icons.GEAR, placed.firstButton(), Theme.TEXT);
					Icons.draw(graphics, Icons.BIN, placed.secondButton(), Theme.TEXT);
				}
				case ACTION, ADD -> {
					Gfx.rounded(graphics, placed.actionButton(), 4, Theme.CONTROL);
					Icons.draw(graphics, row.icon() == null ? Icons.CROSS : row.icon(), placed.actionButton(), Theme.TEXT);
				}
				case SUB -> {
					String value = row.value().get();
					if (!value.isEmpty()) {
						int right = placed.actionButton().x() - 3;
						graphics.text(font, value, right - font.width(value), Gfx.fieldText(control), Theme.DIM_TEXT, false);
					}
					Gfx.rounded(graphics, placed.actionButton(), 4, Theme.CONTROL);
					Icons.draw(graphics, Icons.FORWARD, placed.actionButton(), Theme.TEXT);
				}
				case TOGGLE -> renderToggle(graphics, control, row.on() != null && row.on().getAsBoolean(), state, placed.index(), now);
			}
		}
		renderScrollbar(graphics, body, rows.size(), visible, state.scroll);
	}

	private static void renderPatternPreview(GuiGraphicsExtractor graphics, Font font, Placed placed, Row row) {
		Rect control = placed.control();
		String pattern = row.value().get();
		Rect area = new Rect(control.x() + 3, control.y(), control.right() - 16, control.bottom());
		if (pattern.isEmpty()) {
			graphics.text(font, Gfx.trim(font, tr("relaychat.settings.regex_empty", "click to edit"), area.width()), area.x(), Gfx.fieldText(control), Theme.DIM_TEXT, false);
		} else {
			RegexPaint.line(graphics, font, pattern, area.x(), Gfx.fieldText(control), area.width());
		}
		boolean valid = row.on() == null || row.on().getAsBoolean();
		Icons.draw(graphics, Icons.REGEX, new Rect(control.right() - 14, control.y(), control.right() - 3, control.bottom()), valid ? Theme.DIM_TEXT : Theme.ERROR);
	}

	private static void renderToggle(GuiGraphicsExtractor graphics, Rect control, boolean on, State state, int index, long now) {
		float height = Math.min(control.height() - 2.0F, 14.0F);
		float width = Math.max(height, Math.min(control.width() - 2.0F, 32.0F));
		float left = control.right() - width - 2.0F;
		float top = control.y() + (control.height() - height) / 2.0F;
		Tween slide = state.toggles.computeIfAbsent(index, key -> {
			Tween created = new Tween(on ? 1.0F : 0.0F, Theme.TOGGLE_SLIDE);
			created.snap(on ? 1.0F : 0.0F);
			return created;
		});
		float position = slide.value(now);
		slide.target(on ? 1.0F : 0.0F, now);
		Gfx.rounded(graphics, (int) left, (int) top, (int) (left + width), (int) (top + height), 3, Theme.CONTROL);
		float knob = height - 4.0F;
		float knobLeft = left + 2.0F + (width - 4.0F - knob) * position;
		float knobTop = top + 2.0F;
		Gfx.rounded(graphics, (int) knobLeft, (int) knobTop, (int) (knobLeft + knob), (int) (knobTop + knob), Math.min(4, Math.round(knob / 2.0F)),
			Gfx.blend(Theme.KNOB_OFF, Theme.KNOB_ON, position));
		float centreX = knobLeft + knob / 2.0F;
		float centreY = knobTop + knob / 2.0F;
		if (position > 0.5F) {
			graphics.fill(Math.round(centreX - 1.0F), Math.round(centreY - 3.0F), Math.round(centreX + 1.0F), Math.round(centreY + 3.0F), Theme.KNOB_MARK);
		} else {
			Gfx.circle(graphics, centreX - 2.5F, centreY - 2.5F, 5.0F, Theme.KNOB_MARK);
		}
	}

	private static void renderPicker(GuiGraphicsExtractor graphics, Font font, Rect body, State state, double mouseX, double mouseY) {
		if (state.color == null) {
			return;
		}
		Rect area = pickerArea(body);
		int color = state.color.get().getAsInt();
		String[] labels = {"A", "R", "G", "B"};
		for (int channel = 0; channel < 4; channel++) {
			Rect bar = bar(area, channel);
			int value = channelValue(color, channel);
			graphics.text(font, labels[channel], area.x(), bar.y() + 2, Theme.DIM_TEXT, false);
			Gfx.fill(graphics, bar, Theme.CONTROL);
			int filled = bar.x() + Math.round(bar.width() * value / 255.0F);
			graphics.fill(bar.x(), bar.y(), filled, bar.bottom(), Theme.CHANNELS[channel]);
			graphics.fill(Math.min(filled, bar.right() - 1), bar.y() - 1, Math.min(filled + 1, bar.right()), bar.bottom() + 1, Theme.TEXT);
			graphics.text(font, String.valueOf(value), bar.right() - 20, bar.y() + 2, Theme.TEXT, false);
		}
		Rect preview = preview(area);
		Gfx.fill(graphics, preview, Theme.CHECKER);
		Gfx.fill(graphics, preview, color);
		Rect hexArea = new Rect(preview.x() + 3, preview.y() + 1, preview.right() - 3, preview.bottom() - 1);
		if (state.hexField != null) {
			graphics.fill(preview.x(), preview.y(), preview.right(), preview.bottom(), 0xA0000000);
			state.hexField.render(graphics, font, hexArea, preview.y() + 3, true, Theme.TEXT, null);
		} else {
			graphics.text(font, String.format("%08X", color), preview.x() + 3, preview.y() + 3, Theme.TEXT, false);
			if (preview.contains(mouseX, mouseY)) {
				String hint = tr("relaychat.settings.hex_hint", "click to type");
				graphics.text(font, hint, preview.right() - 3 - font.width(hint), preview.y() + 3, Theme.DIM_TEXT, false);
			}
		}
	}

	private static void renderScrollbar(GuiGraphicsExtractor graphics, Rect body, int count, int visible, int scroll) {
		if (count <= visible || visible <= 0) {
			return;
		}
		int top = body.y() + HEADER + 4;
		int bottom = body.bottom() - 4;
		int height = bottom - top;
		if (height <= 0) {
			return;
		}
		int right = body.right() - 1;
		int left = right - 2;
		graphics.fill(left, top, right, bottom, Theme.SCROLL_TRACK);
		int thumb = Math.max(6, height * visible / count);
		int maxScroll = count - visible;
		int thumbTop = top + (maxScroll <= 0 ? 0 : (height - thumb) * scroll / maxScroll);
		graphics.fill(left, thumbTop, right, thumbTop + thumb, Theme.SCROLL_THUMB);
	}

	private static int barColor(Row row) {
		return switch (row.kind()) {
			case TOGGLE -> row.on() != null && row.on().getAsBoolean() ? Theme.ON : Theme.OFF;
			case PATTERN -> row.on() != null && !row.on().getAsBoolean() ? Theme.OFF : Theme.NEUTRAL_BAR;
			case TEXT, SUB, CYCLE, PAIR, COLOR -> Theme.NEUTRAL_BAR;
			case ACTION -> Theme.OFF;
			case ADD -> Theme.ON;
		};
	}

	private static int channelValue(int color, int channel) {
		return color >> (24 - channel * 8) & 0xFF;
	}

	private static int withChannel(int color, int channel, int value) {
		int shift = 24 - channel * 8;
		return color & ~(0xFF << shift) | Math.max(0, Math.min(255, value)) << shift;
	}

	// ---- input ----------------------------------------------------------------------------------

	@Nullable
	public static CursorType cursorAt(ChatWindow window, Rect body, double mouseX, double mouseY) {
		State state = state(window);
		if (state.page == Page.MESSAGES || !body.contains(mouseX, mouseY)) {
			return null;
		}
		if (backButton(body).contains(mouseX, mouseY)) {
			return CursorTypes.POINTING_HAND;
		}
		if (mouseY < body.y() + HEADER) {
			return CursorTypes.ARROW;
		}
		if (state.page == Page.COLOR) {
			Rect area = pickerArea(body);
			if (preview(area).contains(mouseX, mouseY)) {
				return CursorTypes.IBEAM;
			}
			return channelAt(area, mouseX, mouseY) >= 0 ? CursorTypes.POINTING_HAND : CursorTypes.ARROW;
		}
		List<Row> rows = rows(window);
		Placed hit = placedAt(place(body, rows.size(), state.scroll), mouseX, mouseY);
		if (hit == null) {
			return CursorTypes.ARROW;
		}
		return rows.get(hit.index()).kind() == Kind.TEXT ? CursorTypes.IBEAM : CursorTypes.POINTING_HAND;
	}

	public static boolean mouseClicked(ChatWindow window, Rect body, double mouseX, double mouseY, boolean shift) {
		State state = state(window);
		if (state.page == Page.MESSAGES || !body.contains(mouseX, mouseY)) {
			return false;
		}
		if (backButton(body).contains(mouseX, mouseY)) {
			back(window);
			return true;
		}
		if (mouseY < body.y() + HEADER) {
			return true;
		}
		if (state.page == Page.COLOR) {
			return clickPicker(state, body, mouseX, mouseY, shift);
		}
		List<Row> rows = rows(window);
		Placed hit = placedAt(place(body, rows.size(), state.scroll), mouseX, mouseY);
		if (hit == null) {
			stopEditing(window, true);
			return true;
		}
		Row row = rows.get(hit.index());
		if (row.kind() == Kind.TEXT && row.commit() != null) {
			Font font = Minecraft.getInstance().font;
			if (!(editingWindow == window && state.editingRow == hit.index())) {
				stopEditing(window, true);
				state.editingRow = hit.index();
				state.field = new FieldView(new TextBuffer(row.value().get(), MAX_TEXT));
				editingWindow = window;
			}
			state.field.mouseDown(font, hit.textArea(), mouseX, mouseY, shift);
			return true;
		}
		stopEditing(window, true);
		// The row list may have changed when committing, so look the row up again.
		rows = rows(window);
		if (hit.index() >= rows.size()) {
			return true;
		}
		row = rows.get(hit.index());
		if (row.kind() == Kind.PAIR && hit.secondButton().contains(mouseX, mouseY)) {
			if (row.remove() != null) {
				row.remove().run();
			}
		} else if (row.click() != null) {
			row.click().run();
		}
		return true;
	}

	private static boolean clickPicker(State state, Rect body, double mouseX, double mouseY, boolean shift) {
		if (state.color == null) {
			return true;
		}
		Rect area = pickerArea(body);
		Rect preview = preview(area);
		if (preview.contains(mouseX, mouseY)) {
			if (state.hexField == null) {
				TextBuffer buffer = new TextBuffer(String.format("%08X", state.color.get().getAsInt()), 9);
				buffer.selectAll();
				state.hexField = new FieldView(buffer);
			} else {
				state.hexField.mouseDown(Minecraft.getInstance().font, new Rect(preview.x() + 3, preview.y() + 1, preview.right() - 3, preview.bottom() - 1), mouseX, mouseY, shift);
			}
			return true;
		}
		commitHex(state);
		int channel = channelAt(area, mouseX, mouseY);
		if (channel >= 0) {
			state.draggedChannel = channel;
			applyChannel(state, area, channel, mouseX);
		}
		return true;
	}

	private static void commitHex(State state) {
		if (state.hexField != null && state.color != null) {
			int parsed = HexColor.parse(state.hexField.buffer().text(), state.color.get().getAsInt());
			state.color.set().accept(parsed);
			state.hexField = null;
			hub().save();
		}
	}

	private static int channelAt(Rect area, double mouseX, double mouseY) {
		for (int channel = 0; channel < 4; channel++) {
			Rect bar = bar(area, channel);
			if (mouseY >= bar.y() && mouseY < bar.bottom() && mouseX >= area.x() && mouseX < bar.right()) {
				return channel;
			}
		}
		return -1;
	}

	private static void applyChannel(State state, Rect area, int channel, double mouseX) {
		Rect bar = bar(area, channel);
		double ratio = bar.width() <= 0 ? 0.0 : (mouseX - bar.x()) / bar.width();
		int value = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * 255.0);
		state.color.set().accept(withChannel(state.color.get().getAsInt(), channel, value));
	}

	public static boolean mouseDragged(ChatWindow window, Rect body, double mouseX) {
		State state = state(window);
		if (state.page == Page.COLOR && state.draggedChannel >= 0 && state.color != null) {
			applyChannel(state, pickerArea(body), state.draggedChannel, mouseX);
			return true;
		}
		if (editingWindow == window && state.field != null && state.field.dragging()) {
			List<Placed> placed = place(body, rows(window).size(), state.scroll);
			for (Placed row : placed) {
				if (row.index() == state.editingRow) {
					state.field.mouseDrag(Minecraft.getInstance().font, row.textArea(), mouseX);
				}
			}
			return true;
		}
		if (state.page == Page.COLOR && state.hexField != null && state.hexField.dragging()) {
			Rect preview = preview(pickerArea(body));
			state.hexField.mouseDrag(Minecraft.getInstance().font, new Rect(preview.x() + 3, preview.y() + 1, preview.right() - 3, preview.bottom() - 1), mouseX);
			return true;
		}
		return false;
	}

	public static boolean mouseReleased(ChatWindow window) {
		State state = state(window);
		boolean handled = false;
		if (state.field != null && state.field.dragging()) {
			state.field.mouseUp();
			handled = true;
		}
		if (state.hexField != null && state.hexField.dragging()) {
			state.hexField.mouseUp();
			handled = true;
		}
		if (state.draggedChannel >= 0) {
			state.draggedChannel = -1;
			hub().save();
			handled = true;
		}
		return handled;
	}

	public static boolean mouseScrolled(ChatWindow window, Rect body, double amount) {
		State state = state(window);
		if (state.page == Page.MESSAGES) {
			return false;
		}
		int count = rows(window).size();
		int visible = visibleRows(body);
		state.scroll = Math.max(0, Math.min(state.scroll - (int) Math.signum(amount), Math.max(0, count - visible)));
		return true;
	}

	/** Keyboard input while the chat screen is open; returns true when Relay consumed the key. */
	public static boolean keyPressed(KeyEvent event) {
		if (isEditing()) {
			ChatWindow window = editingWindow;
			State state = state(window);
			if (event.key() == InputConstants.KEY_ESCAPE) {
				stopEditing(window, false);
			} else if (event.isConfirmation()) {
				stopEditing(window, true);
			} else if (event.key() == InputConstants.KEY_TAB) {
				editNextText(window, event.hasShiftDown());
			} else {
				TextKeys.handle(state.field.buffer(), event);
			}
			// Swallow everything else too, so typing never leaks into the chat box underneath.
			return true;
		}
		for (Entry<ChatWindow, State> entry : STATES.entrySet()) {
			State state = entry.getValue();
			if (state.page == Page.COLOR && state.hexField != null) {
				if (event.key() == InputConstants.KEY_ESCAPE) {
					state.hexField = null;
				} else if (event.isConfirmation()) {
					commitHex(state);
				} else {
					TextKeys.handle(state.hexField.buffer(), event);
				}
				return true;
			}
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			for (Entry<ChatWindow, State> entry : STATES.entrySet()) {
				if (entry.getValue().page != Page.MESSAGES) {
					back(entry.getKey());
					return true;
				}
			}
		}
		return false;
	}

	public static boolean charTyped(String typed) {
		if (isEditing()) {
			TextKeys.type(state(editingWindow).field.buffer(), typed);
			return true;
		}
		for (State state : STATES.values()) {
			if (state.page == Page.COLOR && state.hexField != null) {
				TextKeys.type(state.hexField.buffer(), typed);
				return true;
			}
		}
		return false;
	}

	/** Tab / Shift+Tab: commit this field and jump to the next text row on the page. */
	private static void editNextText(ChatWindow window, boolean backwards) {
		State state = state(window);
		int from = state.editingRow;
		stopEditing(window, true);
		List<Row> rows = rows(window);
		for (int step = 1; step <= rows.size(); step++) {
			int index = Math.floorMod(from + (backwards ? -step : step), rows.size());
			Row row = rows.get(index);
			if (row.kind() == Kind.TEXT && row.commit() != null) {
				state.editingRow = index;
				TextBuffer buffer = new TextBuffer(row.value().get(), MAX_TEXT);
				buffer.selectAll();
				state.field = new FieldView(buffer);
				editingWindow = window;
				return;
			}
		}
	}

	/** Leaves the text field, optionally writing its value back. */
	public static void stopEditing(ChatWindow window, boolean commit) {
		State state = state(window);
		if (state.editingRow >= 0 && commit && state.field != null) {
			List<Row> rows = rows(window);
			if (state.editingRow < rows.size() && rows.get(state.editingRow).commit() != null) {
				rows.get(state.editingRow).commit().accept(state.field.buffer().text());
			}
		}
		state.editingRow = -1;
		state.field = null;
		if (editingWindow == window) {
			editingWindow = null;
		}
	}

	/** Commits any open field; called when the chat closes or the click lands outside every window. */
	public static void commitAll() {
		for (Entry<ChatWindow, State> entry : new ArrayList<>(STATES.entrySet())) {
			stopEditing(entry.getKey(), true);
			commitHex(entry.getValue());
		}
	}
}
