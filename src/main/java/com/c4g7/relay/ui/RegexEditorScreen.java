package com.c4g7.relay.ui;

import com.c4g7.relay.Relay;
import com.c4g7.relay.chat.ChatTab;
import com.c4g7.relay.chat.ChatWindow;
import com.c4g7.relay.chat.Message;
import com.c4g7.relay.config.FilterConfig;
import com.c4g7.relay.filter.FilterMatcher;
import com.c4g7.relay.filter.RegexSyntax;
import com.c4g7.relay.ui.text.FieldView;
import com.c4g7.relay.ui.text.PatternArea;
import com.c4g7.relay.ui.text.Shortcuts;
import com.c4g7.relay.ui.text.TextBuffer;
import com.c4g7.relay.ui.text.TextKeys;
import com.c4g7.relay.util.Rect;
import com.c4g7.relay.util.Tr;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A full-screen editor for a filter's include and exclude patterns: syntax colouring, live
 * validation with the error position, quick-insert snippets, a test line, and a live preview of
 * which recent chat messages the filter would catch. Nothing is written back until you save.
 */
public final class RegexEditorScreen extends Screen {
	private static final int MAX_PATTERN = 2048;
	private static final int PANEL_MAX_WIDTH = 460;
	private static final int PANEL_MAX_HEIGHT = 360;
	private static final int ROW = 10;
	private static final int CHIP_HEIGHT = 13;
	private static final int INCLUDE_MARK = 0x6630C850;
	private static final int EXCLUDE_MARK = 0x66D04040;
	private static final long PREVIEW_FRAME_BUDGET_NANOS = 4_000_000L;

	private record Snippet(String label, String tip, Consumer<TextBuffer> apply) {
	}

	/** An option pill; {@code on} is null for a choice (drawn with an accent dot) rather than a switch. */
	private record Chip(String label, Boolean on, Runnable toggle) {
	}

	private record Layout(Rect panel, Rect header, Rect includeTab, Rect excludeTab, Rect editorBox, Rect editorText, int statusY,
		List<Rect> chipBoxes, List<Rect> snippetBoxes, Rect testBox, int testResultY, int previewHeaderY, Rect onlyMatches, Rect preview,
		Rect save, Rect cancel, int footerY) {
	}

	private final ChatWindow window;
	private final ChatTab tab;
	private final FilterConfig filter;
	/** Working copy of the options; copied onto {@link #filter} on save. */
	private final FilterConfig draft = new FilterConfig();
	private final PatternArea include;
	private final PatternArea exclude;
	private final FieldView test = new FieldView(new TextBuffer("", 512));
	private final List<Snippet> snippets = new ArrayList<>();
	private boolean editingInclude;
	private boolean testFocused;
	private boolean onlyMatches;
	private int previewScroll;
	private Preview preview = new Preview("", List.of());
	private Layout layout;

	private RegexEditorScreen(ChatWindow window, ChatTab tab, FilterConfig filter, boolean include) {
		super(Component.literal("Relay Chat regex editor"));
		this.window = window;
		this.tab = tab;
		this.filter = filter;
		this.editingInclude = include;
		this.draft.mode = FilterConfig.Mode.REGEX;
		this.draft.caseSensitive = filter.caseSensitive;
		this.draft.regexMatch = filter.regexMatch;
		this.draft.searchJson = filter.searchJson;
		this.include = new PatternArea(new TextBuffer(filter.includeRegex, MAX_PATTERN));
		this.exclude = new PatternArea(new TextBuffer(filter.excludeRegex, MAX_PATTERN));
		this.snippets.add(new Snippet(".*", tr("relaychat.regex.snippet.any", "Anything, any length"), buffer -> buffer.insert(".*")));
		this.snippets.add(new Snippet(".+?", tr("relaychat.regex.snippet.lazy", "At least one character, as few as possible"), buffer -> buffer.insert(".+?")));
		this.snippets.add(new Snippet("\\s+", tr("relaychat.regex.snippet.space", "Whitespace"), buffer -> buffer.insert("\\s+")));
		this.snippets.add(new Snippet("\\d+", tr("relaychat.regex.snippet.digits", "Digits"), buffer -> buffer.insert("\\d+")));
		this.snippets.add(new Snippet("\\w+", tr("relaychat.regex.snippet.word", "A word (letters, digits, _)"), buffer -> buffer.insert("\\w+")));
		this.snippets.add(new Snippet("( )", tr("relaychat.regex.snippet.group", "Group (wraps the selection)"), buffer -> buffer.wrap("(", ")")));
		this.snippets.add(new Snippet("(?: )", tr("relaychat.regex.snippet.nc_group", "Group without capturing"), buffer -> buffer.wrap("(?:", ")")));
		this.snippets.add(new Snippet("[ ]", tr("relaychat.regex.snippet.class", "Any one of these characters"), buffer -> buffer.wrap("[", "]")));
		this.snippets.add(new Snippet("|", tr("relaychat.regex.snippet.or", "Or"), buffer -> buffer.insert("|")));
		this.snippets.add(new Snippet("^", tr("relaychat.regex.snippet.start", "Start of the message"), buffer -> buffer.insert("^")));
		this.snippets.add(new Snippet("$", tr("relaychat.regex.snippet.end", "End of the message"), buffer -> buffer.insert("$")));
		this.snippets.add(new Snippet("(?i)", tr("relaychat.regex.snippet.ignore_case", "Ignore case from here on"), buffer -> buffer.insert("(?i)")));
		this.snippets.add(new Snippet(tr("relaychat.regex.escape", "Escape"), tr("relaychat.regex.snippet.escape", "Turn the selected text into a literal match"),
			buffer -> {
				if (buffer.hasSelection()) {
					buffer.insert(RegexSyntax.escape(buffer.selectedText()));
				}
			}));
	}

	public static void open(ChatWindow window, ChatTab tab, FilterConfig filter, boolean include) {
		Settings.commitAll();
		Minecraft.getInstance().gui.setScreen(new RegexEditorScreen(window, tab, filter, include));
	}

	private static String tr(String key, String english) {
		return Tr.get(key, english);
	}

	private PatternArea area() {
		return this.editingInclude ? this.include : this.exclude;
	}

	private TextBuffer focusedBuffer() {
		return this.testFocused ? this.test.buffer() : this.area().buffer();
	}

	// ---- lifecycle ------------------------------------------------------------------------------

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.extractTransparentBackground(graphics);
	}

	@Override
	public void onClose() {
		this.leave();
	}

	private void save() {
		this.filter.mode = FilterConfig.Mode.REGEX;
		this.filter.includeRegex = this.include.buffer().text();
		this.filter.excludeRegex = this.exclude.buffer().text();
		this.filter.caseSensitive = this.draft.caseSensitive;
		this.filter.regexMatch = this.draft.regexMatch;
		this.filter.searchJson = this.draft.searchJson;
		Relay.hub().save();
		this.leave();
	}

	/** Back to the chat, on the filter page this editor was opened from. */
	private void leave() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gui.setScreen(null);
		minecraft.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
		if (Relay.hub().windows().contains(this.window) && this.window.contains(this.tab)) {
			Settings.openFilter(this.window, this.tab, this.filter);
		}
	}

	// ---- layout ---------------------------------------------------------------------------------

	private Layout layout() {
		Font font = this.font;
		int panelWidth = Math.min(this.width - 24, PANEL_MAX_WIDTH);
		int panelHeight = Math.min(this.height - 20, PANEL_MAX_HEIGHT);
		int left = (this.width - panelWidth) / 2;
		int top = (this.height - panelHeight) / 2;
		Rect panel = new Rect(left, top, left + panelWidth, top + panelHeight);
		Rect header = new Rect(panel.x(), panel.y(), panel.right(), panel.y() + 16);
		String excludeLabel = tr("relaychat.regex.exclude", "Exclude");
		String includeLabel = tr("relaychat.regex.include", "Include");
		int excludeWidth = font.width(excludeLabel) + 12;
		int includeWidth = font.width(includeLabel) + 12;
		Rect excludeTab = new Rect(header.right() - 4 - excludeWidth, header.y(), header.right() - 4, header.bottom());
		Rect includeTab = new Rect(excludeTab.x() - 2 - includeWidth, header.y(), excludeTab.x() - 2, header.bottom());

		int innerLeft = panel.x() + 8;
		int innerRight = panel.right() - 8;
		int y = header.bottom() + 16;
		int textWidth = innerRight - innerLeft - 10;
		int rows = Math.max(3, Math.min(6, this.area().lineCount(font, textWidth)));
		Rect editorBox = new Rect(innerLeft, y, innerRight, y + rows * ROW + 6);
		Rect editorText = new Rect(editorBox.x() + 4, editorBox.y() + 4, editorBox.right() - 6, editorBox.y() + 4 + rows * ROW);
		y = editorBox.bottom() + 3;
		int statusY = y;
		y += 13;

		List<Rect> chipBoxes = new ArrayList<>();
		int x = innerLeft;
		for (Chip chip : this.chips()) {
			int width = font.width(chip.label()) + 16;
			chipBoxes.add(new Rect(x, y, x + width, y + CHIP_HEIGHT));
			x += width + 3;
		}
		y += CHIP_HEIGHT + 4;

		List<Rect> snippetBoxes = new ArrayList<>();
		x = innerLeft;
		for (Snippet snippet : this.snippets) {
			int width = font.width(snippet.label()) + 8;
			if (x + width > innerRight) {
				x = innerLeft;
				y += CHIP_HEIGHT + 2;
			}
			snippetBoxes.add(new Rect(x, y, x + width, y + CHIP_HEIGHT));
			x += width + 2;
		}
		y += CHIP_HEIGHT + 14;

		Rect testBox = new Rect(innerLeft, y, innerRight, y + 14);
		y = testBox.bottom() + 3;
		int testResultY = y;
		y += 16;
		int previewHeaderY = y;
		String onlyLabel = tr("relaychat.regex.only_matches", "Only matches");
		int onlyWidth = font.width(onlyLabel) + 16;
		Rect onlyMatches = new Rect(innerRight - onlyWidth, y - 2, innerRight, y - 2 + CHIP_HEIGHT);
		y += 13;

		int footerY = panel.bottom() - 20;
		Rect preview = new Rect(innerLeft, y, innerRight, Math.max(y, footerY - 4));
		String saveLabel = tr("relaychat.regex.save", "Save");
		String cancelLabel = tr("relaychat.regex.cancel", "Cancel");
		Rect save = new Rect(innerRight - font.width(saveLabel) - 22, footerY, innerRight, footerY + 14);
		Rect cancel = new Rect(save.x() - 4 - font.width(cancelLabel) - 22, footerY, save.x() - 4, footerY + 14);
		return new Layout(panel, header, includeTab, excludeTab, editorBox, editorText, statusY, chipBoxes, snippetBoxes, testBox, testResultY,
			previewHeaderY, onlyMatches, preview, save, cancel, footerY);
	}

	private List<Chip> chips() {
		List<Chip> chips = new ArrayList<>();
		chips.add(new Chip(tr("relaychat.settings.case_sensitive", "Case sensitive"), this.draft.caseSensitive,
			() -> this.draft.caseSensitive = !this.draft.caseSensitive));
		chips.add(new Chip(tr("relaychat.regex.match", "Match") + ": " + Settings.matchLabel(this.draft.regexMatch), null,
			() -> this.draft.regexMatch = this.draft.regexMatch == FilterConfig.RegexMatch.CONTAINS ? FilterConfig.RegexMatch.WHOLE : FilterConfig.RegexMatch.CONTAINS));
		chips.add(new Chip(tr("relaychat.settings.filter_tooltips", "Filter tooltips"), this.draft.searchJson,
			() -> this.draft.searchJson = !this.draft.searchJson));
		return chips;
	}

	// ---- rendering ------------------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		Layout layout = this.layout = this.layout();
		Font font = this.font;
		Gfx.rounded(graphics, layout.panel(), 4, Theme.PANEL);

		// header
		Gfx.fill(graphics, layout.header(), Theme.HEADER);
		Rect back = new Rect(layout.header().x(), layout.header().y(), layout.header().x() + 14, layout.header().bottom());
		Icons.draw(graphics, Icons.BACK, back, Theme.TEXT);
		String title = tr("relaychat.regex.title", "Regex") + " · " + this.filter.displayName();
		graphics.text(font, Gfx.trim(font, title, layout.includeTab().x() - back.right() - 6), back.right(), Gfx.centredText(layout.header()), Theme.TEXT, false);
		this.segment(graphics, font, layout.includeTab(), tr("relaychat.regex.include", "Include"), this.editingInclude, mouseX, mouseY);
		this.segment(graphics, font, layout.excludeTab(), tr("relaychat.regex.exclude", "Exclude"), !this.editingInclude, mouseX, mouseY);

		// pattern
		int innerLeft = layout.editorBox().x();
		graphics.text(font, this.editingInclude
			? tr("relaychat.regex.include_label", "Messages that match this pattern are caught")
			: tr("relaychat.regex.exclude_label", "…unless they also match this one"), innerLeft, layout.editorBox().y() - 11, Theme.DIM_TEXT, false);
		String pattern = this.area().buffer().text();
		FilterMatcher.Compiled compiled = FilterMatcher.compile(pattern, this.draft.caseSensitive);
		Gfx.rounded(graphics, layout.editorBox(), 4, this.testFocused ? Theme.CONTROL : Theme.EDITING);
		if (pattern.isEmpty() && this.testFocused) {
			graphics.text(font, tr("relaychat.regex.placeholder", "type a pattern…"), layout.editorText().x(), layout.editorText().y(), Theme.DIM_TEXT, false);
		}
		this.area().render(graphics, font, layout.editorText(), !this.testFocused, compiled.valid() ? -1 : compiled.errorIndex());
		this.renderStatus(graphics, font, layout, pattern, compiled);

		// options + snippets
		List<Chip> chips = this.chips();
		for (int index = 0; index < chips.size(); index++) {
			this.chip(graphics, font, layout.chipBoxes().get(index), chips.get(index).label(), chips.get(index).on(), mouseX, mouseY);
		}
		for (int index = 0; index < this.snippets.size(); index++) {
			Rect box = layout.snippetBoxes().get(index);
			boolean hovered = box.contains(mouseX, mouseY);
			Gfx.rounded(graphics, box, 3, hovered ? Theme.ROW_HOVER | 0x30000000 : Theme.CONTROL);
			Snippet snippet = this.snippets.get(index);
			graphics.text(font, snippet.label(), box.x() + 4, box.y() + 3, 0xFFFFD24A, false);
			if (hovered) {
				graphics.setTooltipForNextFrame(font, Component.literal(snippet.tip()), mouseX, mouseY);
			}
		}

		// test line
		graphics.text(font, tr("relaychat.regex.test", "Test text"), layout.testBox().x(), layout.testBox().y() - 11, Theme.DIM_TEXT, false);
		Gfx.rounded(graphics, layout.testBox(), 4, this.testFocused ? Theme.EDITING : Theme.CONTROL);
		Rect testText = new Rect(layout.testBox().x() + 4, layout.testBox().y(), layout.testBox().right() - 4, layout.testBox().bottom());
		List<int[]> marks = compiled.valid() && !pattern.isEmpty() ? spansOrEmpty(compiled.pattern(), this.test.buffer().text()) : List.of();
		this.test.render(graphics, font, testText, layout.testBox().y() + 3, this.testFocused, Theme.TEXT,
			tr("relaychat.regex.test_placeholder", "paste or type a chat line, or click one below"), marks, this.editingInclude ? INCLUDE_MARK : EXCLUDE_MARK);
		this.renderTestResult(graphics, font, layout, compiled);

		// preview
		this.renderPreview(graphics, font, layout, compiled, mouseX, mouseY);

		// footer
		graphics.text(font, Gfx.trim(font, tr("relaychat.regex.hint", "Enter saves · Esc cancels · Shift+click a message to insert it"),
			layout.cancel().x() - innerLeft - 6), innerLeft, layout.footerY() + 3, Theme.DIM_TEXT, false);
		this.button(graphics, font, layout.cancel(), tr("relaychat.regex.cancel", "Cancel"), Icons.CROSS, Theme.CONTROL, mouseX, mouseY);
		this.button(graphics, font, layout.save(), tr("relaychat.regex.save", "Save"), Icons.CHECK, 0xC02E8527, mouseX, mouseY);

		if (layout.editorBox().contains(mouseX, mouseY) || layout.testBox().contains(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.IBEAM);
		} else if (this.clickable(layout, mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	private boolean clickable(Layout layout, double mouseX, double mouseY) {
		if (layout.includeTab().contains(mouseX, mouseY) || layout.excludeTab().contains(mouseX, mouseY) || layout.save().contains(mouseX, mouseY)
			|| layout.cancel().contains(mouseX, mouseY) || layout.onlyMatches().contains(mouseX, mouseY) || layout.preview().contains(mouseX, mouseY)) {
			return true;
		}
		return layout.chipBoxes().stream().anyMatch(box -> box.contains(mouseX, mouseY))
			|| layout.snippetBoxes().stream().anyMatch(box -> box.contains(mouseX, mouseY));
	}

	private void segment(GuiGraphicsExtractor graphics, Font font, Rect box, String label, boolean active, int mouseX, int mouseY) {
		if (active) {
			Gfx.fill(graphics, box, Theme.ACTIVE_TAB);
			graphics.fill(box.x(), box.bottom() - 2, box.right(), box.bottom(), Theme.ACCENT);
		} else if (box.contains(mouseX, mouseY)) {
			Gfx.fill(graphics, box, Theme.ROW_HOVER);
		}
		graphics.text(font, label, box.x() + 6, Gfx.centredText(box) - 1, active ? Theme.TEXT : Theme.INACTIVE_TEXT, false);
	}

	private void chip(GuiGraphicsExtractor graphics, Font font, Rect box, String label, Boolean on, int mouseX, int mouseY) {
		Gfx.rounded(graphics, box, 3, box.contains(mouseX, mouseY) ? 0x80303030 : Theme.CONTROL);
		Gfx.circle(graphics, box.x() + 4, box.y() + 4, 5, on == null ? Theme.ACCENT : on ? Theme.KNOB_ON : Theme.KNOB_OFF);
		graphics.text(font, label, box.x() + 12, box.y() + 3, Theme.TEXT, false);
	}

	private void button(GuiGraphicsExtractor graphics, Font font, Rect box, String label, net.minecraft.resources.Identifier icon, int color, int mouseX, int mouseY) {
		Gfx.rounded(graphics, box, 4, color);
		if (box.contains(mouseX, mouseY)) {
			Gfx.rounded(graphics, box, 4, Theme.ROW_HOVER);
		}
		Icons.draw(graphics, icon, new Rect(box.x() + 3, box.y() + 2, box.x() + 13, box.bottom() - 2), Theme.TEXT);
		graphics.text(font, label, box.x() + 17, box.y() + 3, Theme.TEXT, false);
	}

	private void renderStatus(GuiGraphicsExtractor graphics, Font font, Layout layout, String pattern, FilterMatcher.Compiled compiled) {
		int x = layout.editorBox().x();
		int y = layout.statusY();
		int width = layout.editorBox().width();
		if (pattern.isEmpty()) {
			String empty = this.editingInclude
				? tr("relaychat.regex.empty_include", "Empty: this filter catches nothing yet")
				: tr("relaychat.regex.empty_exclude", "Empty: nothing is excluded");
			graphics.text(font, Gfx.trim(font, empty, width), x, y, Theme.DIM_TEXT, false);
		} else if (!compiled.valid()) {
			String error = "✖ " + compiled.error() + (compiled.errorIndex() >= 0 ? " (" + tr("relaychat.regex.at", "at") + " " + compiled.errorIndex() + ")" : "");
			graphics.text(font, Gfx.trim(font, error, width), x, y, Theme.ERROR, false);
		} else {
			int groups = compiled.pattern().matcher("").groupCount();
			String valid = "✔ " + tr("relaychat.regex.valid", "Valid pattern") + (groups > 0 ? " · " + groups + " " + tr("relaychat.regex.groups", "groups") : "");
			graphics.text(font, valid, x, y, 0xFF5AD05A, false);
			if (this.preview.slow) {
				String slow = tr("relaychat.regex.slow", "slow on some messages");
				graphics.text(font, slow, layout.editorBox().right() - font.width(slow), y, 0xFFFFB050, false);
			}
		}
	}

	private void renderTestResult(GuiGraphicsExtractor graphics, Font font, Layout layout, FilterMatcher.Compiled compiled) {
		String text = this.test.buffer().text();
		int x = layout.testBox().x();
		int y = layout.testResultY();
		if (text.isEmpty() || !compiled.valid() || this.area().buffer().text().isEmpty()) {
			return;
		}
		Boolean matched = FilterMatcher.test(this.draft.regexMatch, compiled.pattern(), text, FilterMatcher.MESSAGE_BUDGET_NANOS);
		boolean caught = FilterMatcher.matchesRegex(this.draft, this.include.buffer().text(), this.exclude.buffer().text(), text, () -> text);
		String result;
		int color;
		if (matched == null) {
			result = "⌛ " + tr("relaychat.regex.too_slow", "Pattern gave up (too slow)");
			color = 0xFFFFB050;
		} else {
			result = (matched ? "✔ " + tr("relaychat.regex.matches", "Pattern matches") : "✖ " + tr("relaychat.regex.no_match", "No match"))
				+ " · " + (caught ? tr("relaychat.regex.caught", "filter catches it") : tr("relaychat.regex.not_caught", "filter lets it pass"));
			color = caught ? 0xFF5AD05A : Theme.DIM_TEXT;
		}
		String groups = matched != null && matched ? groups(compiled.pattern(), text) : "";
		graphics.text(font, Gfx.trim(font, result + groups, layout.testBox().width()), x, y, color, false);
	}

	private static String groups(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		if (!matcher.find() || matcher.groupCount() == 0) {
			return "";
		}
		StringBuilder groups = new StringBuilder("  ");
		for (int group = 1; group <= Math.min(9, matcher.groupCount()); group++) {
			String value = matcher.group(group);
			groups.append(" $").append(group).append('=').append(value == null ? "∅" : '"' + value + '"');
		}
		return groups.toString();
	}

	private void renderPreview(GuiGraphicsExtractor graphics, Font font, Layout layout, FilterMatcher.Compiled compiled, int mouseX, int mouseY) {
		this.refreshPreview(compiled);
		Rect area = layout.preview();
		int total = this.preview.rows.size();
		int evaluated = this.preview.evaluated;
		String heading = tr("relaychat.regex.recent", "Recent chat") + " · " + this.preview.caught + " / " + evaluated + " "
			+ tr("relaychat.regex.caught_short", "caught") + (evaluated < total ? " …" : "");
		graphics.text(font, heading, area.x(), layout.previewHeaderY(), Theme.DIM_TEXT, false);
		this.chip(graphics, font, layout.onlyMatches(), tr("relaychat.regex.only_matches", "Only matches"), this.onlyMatches, mouseX, mouseY);
		Gfx.rounded(graphics, area, 3, 0x40000000);
		List<PreviewRow> rows = this.visibleRows();
		int capacity = Math.max(0, (area.height() - 4) / ROW);
		this.previewScroll = Math.max(0, Math.min(this.previewScroll, Math.max(0, rows.size() - capacity)));
		if (rows.isEmpty()) {
			String empty = total == 0 ? tr("relaychat.regex.no_recent", "No chat received yet this session") : tr("relaychat.regex.none_caught", "Nothing caught");
			graphics.text(font, empty, area.x() + 6, area.y() + 4, Theme.DIM_TEXT, false);
			return;
		}
		int textWidth = area.width() - 12;
		for (int slot = 0; slot < capacity; slot++) {
			int index = this.previewScroll + slot;
			if (index >= rows.size()) {
				break;
			}
			PreviewRow row = rows.get(index);
			int y = area.y() + 2 + slot * ROW;
			Rect line = new Rect(area.x() + 2, y, area.right() - 2, y + ROW);
			if (line.contains(mouseX, mouseY)) {
				Gfx.fill(graphics, line, Theme.ROW_HOVER);
			}
			if (row.caught == Boolean.TRUE) {
				graphics.fill(line.x(), y, line.x() + 2, y + ROW, Theme.KNOB_ON);
			}
			String text = font.plainSubstrByWidth(row.text, textWidth);
			int textX = area.x() + 7;
			if (row.spans != null) {
				for (int[] span : row.spans) {
					if (span[0] >= text.length()) {
						continue;
					}
					int x0 = textX + font.width(text.substring(0, span[0]));
					int x1 = textX + font.width(text.substring(0, Math.min(span[1], text.length())));
					graphics.fill(x0, y, x1, y + 9, this.editingInclude ? INCLUDE_MARK : EXCLUDE_MARK);
				}
			}
			graphics.text(font, text, textX, y + 1, row.caught == null ? Theme.DIM_TEXT : Theme.TEXT, false);
		}
		if (rows.size() > capacity && capacity > 0) {
			int track = area.height() - 4;
			int thumb = Math.max(6, track * capacity / rows.size());
			int top = area.y() + 2 + (track - thumb) * this.previewScroll / Math.max(1, rows.size() - capacity);
			graphics.fill(area.right() - 2, area.y() + 2, area.right() - 1, area.bottom() - 2, Theme.SCROLL_TRACK);
			graphics.fill(area.right() - 2, top, area.right() - 1, top + thumb, Theme.SCROLL_THUMB);
		}
	}

	private List<PreviewRow> visibleRows() {
		if (!this.onlyMatches) {
			return this.preview.rows;
		}
		List<PreviewRow> caught = new ArrayList<>();
		for (PreviewRow row : this.preview.rows) {
			if (row.caught == Boolean.TRUE) {
				caught.add(row);
			}
		}
		return caught;
	}

	private static List<int[]> spansOrEmpty(Pattern pattern, String text) {
		List<int[]> spans = FilterMatcher.spans(pattern, text, 32);
		return spans == null ? List.of() : spans;
	}

	// ---- live preview ---------------------------------------------------------------------------

	private static final class PreviewRow {
		private final Message message;
		private final String text;
		@Nullable
		private Boolean caught;
		@Nullable
		private List<int[]> spans;

		private PreviewRow(Message message) {
			this.message = message;
			this.text = message.plain();
		}
	}

	private static final class Preview {
		private final String key;
		private final List<PreviewRow> rows;
		private int evaluated;
		private int caught;
		private boolean slow;

		private Preview(String key, List<PreviewRow> rows) {
			this.key = key;
			this.rows = rows;
		}
	}

	/**
	 * Re-evaluates recent chat against the draft, a few milliseconds per frame, so even a heavy
	 * pattern over a thousand messages never stalls the game.
	 */
	private void refreshPreview(FilterMatcher.Compiled compiled) {
		var recent = Relay.hub().recent();
		String key = this.include.buffer().text() + '\u0000' + this.exclude.buffer().text() + '\u0000' + this.editingInclude + this.draft.caseSensitive
			+ this.draft.regexMatch + this.draft.searchJson + '\u0000' + recent.size() + ':' + (recent.isEmpty() ? 0 : recent.peekFirst().id());
		if (!key.equals(this.preview.key)) {
			List<PreviewRow> rows = new ArrayList<>(recent.size());
			for (Message message : recent) {
				if (!message.isDivider()) {
					rows.add(new PreviewRow(message));
				}
			}
			this.preview = new Preview(key, rows);
		}
		Preview current = this.preview;
		long deadline = System.nanoTime() + PREVIEW_FRAME_BUDGET_NANOS;
		String includePattern = this.include.buffer().text();
		String excludePattern = this.exclude.buffer().text();
		while (current.evaluated < current.rows.size() && System.nanoTime() < deadline) {
			PreviewRow row = current.rows.get(current.evaluated++);
			boolean caught = FilterMatcher.matchesRegex(this.draft, includePattern, excludePattern, row.text, row.message::json);
			row.caught = caught;
			if (caught) {
				current.caught++;
			}
			if (compiled.valid() && !this.area().buffer().text().isEmpty()) {
				row.spans = FilterMatcher.spans(compiled.pattern(), row.text, 16);
				current.slow |= row.spans == null;
			}
		}
	}

	// ---- input ----------------------------------------------------------------------------------

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_ESCAPE) {
			this.leave();
			return true;
		}
		if (event.isConfirmation() || Shortcuts.is(event, 's') && event.hasControlDownWithQuirk()) {
			this.save();
			return true;
		}
		if (key == InputConstants.KEY_TAB) {
			this.testFocused = !this.testFocused;
			return true;
		}
		if (!this.testFocused && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
			this.area().vertical(this.font, key == InputConstants.KEY_UP ? -1 : 1, event.hasShiftDown());
			return true;
		}
		return TextKeys.handle(this.focusedBuffer(), event) || super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		String typed = event.codepointAsString();
		if (typed.isEmpty()) {
			return false;
		}
		TextKeys.type(this.focusedBuffer(), typed);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Layout layout = this.layout == null ? this.layout() : this.layout;
		double x = event.x();
		double y = event.y();
		boolean shift = event.hasShiftDown();
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		if (new Rect(layout.header().x(), layout.header().y(), layout.header().x() + 14, layout.header().bottom()).contains(x, y)
			|| layout.cancel().contains(x, y)) {
			this.leave();
			return true;
		}
		if (layout.save().contains(x, y)) {
			this.save();
			return true;
		}
		if (layout.includeTab().contains(x, y) || layout.excludeTab().contains(x, y)) {
			this.editingInclude = layout.includeTab().contains(x, y);
			this.testFocused = false;
			this.previewScroll = 0;
			return true;
		}
		if (layout.editorBox().contains(x, y)) {
			this.testFocused = false;
			this.area().mouseDown(this.font, layout.editorText(), x, y, shift);
			return true;
		}
		if (layout.testBox().contains(x, y)) {
			this.testFocused = true;
			this.test.mouseDown(this.font, new Rect(layout.testBox().x() + 4, layout.testBox().y(), layout.testBox().right() - 4, layout.testBox().bottom()), x, y, shift);
			return true;
		}
		List<Chip> chips = this.chips();
		for (int index = 0; index < chips.size(); index++) {
			if (layout.chipBoxes().get(index).contains(x, y)) {
				chips.get(index).toggle().run();
				return true;
			}
		}
		for (int index = 0; index < this.snippets.size(); index++) {
			if (layout.snippetBoxes().get(index).contains(x, y)) {
				this.testFocused = false;
				this.snippets.get(index).apply().accept(this.area().buffer());
				return true;
			}
		}
		if (layout.onlyMatches().contains(x, y)) {
			this.onlyMatches = !this.onlyMatches;
			this.previewScroll = 0;
			return true;
		}
		if (layout.preview().contains(x, y)) {
			PreviewRow row = this.previewRowAt(layout, y);
			if (row != null) {
				if (shift) {
					this.testFocused = false;
					this.area().buffer().insert(RegexSyntax.escape(row.text));
				} else {
					this.test.buffer().setText(row.text);
					this.testFocused = true;
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Nullable
	private PreviewRow previewRowAt(Layout layout, double y) {
		int slot = (int) ((y - layout.preview().y() - 2) / ROW);
		List<PreviewRow> rows = this.visibleRows();
		int index = this.previewScroll + slot;
		return slot >= 0 && index < rows.size() ? rows.get(index) : null;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.layout != null) {
			if (!this.testFocused && this.area().mouseDrag(this.font, this.layout.editorText(), event.x(), event.y())) {
				return true;
			}
			if (this.testFocused && this.test.dragging()) {
				Rect box = this.layout.testBox();
				this.test.mouseDrag(this.font, new Rect(box.x() + 4, box.y(), box.right() - 4, box.bottom()), event.x());
				return true;
			}
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.include.mouseUp();
		this.exclude.mouseUp();
		this.test.mouseUp();
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.layout != null && this.layout.editorBox().contains(mouseX, mouseY)) {
			this.area().scroll(scrollY > 0 ? -1 : 1);
			return true;
		}
		if (this.layout != null && this.layout.preview().contains(mouseX, mouseY)) {
			this.previewScroll = Math.max(0, this.previewScroll + (scrollY > 0 ? -3 : 3));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
