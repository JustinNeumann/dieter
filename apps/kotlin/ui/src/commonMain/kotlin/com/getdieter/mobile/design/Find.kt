package com.getdieter.mobile.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.getdieter.core.presentation.Markdown
import com.getdieter.core.presentation.MarkdownBlock
import com.getdieter.mobile.platform.NativeFind
import com.getdieter.mobile.platform.SystemBackHandler

/*
 * Find on screen. Where text is drawn rather than laid out by the platform (a canvas on the web,
 * where the browser's find sees nothing), a screen offers its text as pieces, one per list item or
 * Markdown block. Ctrl/⌘+F opens a bar that counts the hits, steps through them and brings each
 * one's piece into view; Markdown highlights the query through [LocalFindQuery].
 */

/** The query being found on screen; empty while find is closed. */
internal val LocalFindQuery = compositionLocalOf { "" }

/** A hit: the piece it is in, where it starts there, and how far down the piece that is (0–1). */
internal data class FindHit(val piece: Int, val offset: Int, val fraction: Float = 0f)

@Stable
internal class FindState {
    var open by mutableStateOf(false)
    var input by mutableStateOf(TextFieldValue(""))
    var current by mutableStateOf(0)
    var focusRequests by mutableStateOf(0)

    val query: String
        get() = input.text

    /** Opens the bar, or focuses it again with the query selected. */
    fun show() {
        open = true
        input = input.copy(selection = TextRange(0, input.text.length))
        focusRequests++
    }

    fun close() {
        open = false
    }
}

/**
 * Ctrl/⌘+F: opens find on the screen that registered last (the detail pane over its list). The
 * platform calls [request]; false means no screen can find, so the shortcut is left alone.
 */
internal object FindRequests {
    private val targets = mutableListOf<FindState>()

    fun register(state: FindState) {
        targets += state
    }

    fun unregister(state: FindState) {
        targets -= state
    }

    val available: Boolean
        get() = targets.isNotEmpty()

    fun request(): Boolean {
        val target = targets.lastOrNull() ?: return false
        target.show()
        return true
    }
}

/** Case-insensitive hits of [query] in [pieces], in order. */
internal fun findHits(pieces: List<String>, query: String, limit: Int = 5000): List<FindHit> {
    if (query.isBlank()) return emptyList()
    val hits = ArrayList<FindHit>()
    pieces.forEachIndexed { piece, text ->
        var at = text.indexOf(query, ignoreCase = true)
        while (at >= 0 && hits.size < limit) {
            hits += FindHit(piece, at, at.toFloat() / text.length)
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
    }
    return hits
}

/** The text a reader sees in [block], the way the Markdown renderer lays it out. */
internal fun findText(block: MarkdownBlock): String =
    when (block) {
        is MarkdownBlock.Paragraph -> Markdown.plain(block.inlines)
        is MarkdownBlock.Heading -> Markdown.plain(block.inlines)
        is MarkdownBlock.Code -> block.text
        is MarkdownBlock.Quote -> block.blocks.joinToString("\n", transform = ::findText)
        is MarkdownBlock.ListBlock ->
            block.items.flatMap { it.blocks }.joinToString("\n", transform = ::findText)
        MarkdownBlock.Rule -> ""
        is MarkdownBlock.Table ->
            (block.header + block.rows.flatten()).joinToString("\n") {
                Markdown.plain(Markdown.inline(it))
            }
    }

/**
 * Scrolls item [index] in under the find bar ([clearance] px from the top); for an item taller than
 * the list, on to the hit [fraction] of the way down it.
 */
internal suspend fun LazyListState.revealHit(index: Int, fraction: Float, clearance: Int) {
    animateScrollToItem(index, -clearance)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    if (item.size <= viewport - clearance) return
    val by = item.size * fraction - viewport / 3f
    if (by > 0) animateScrollBy(by)
}

/** The colour hits are marked with. */
@Composable
internal fun findHighlight(): Color = palette.warning.copy(alpha = if (palette.dark) .45f else .32f)

/** [this] with every case-insensitive occurrence of [query] marked. */
internal fun AnnotatedString.highlighting(query: String, color: Color): AnnotatedString {
    if (query.isBlank()) return this
    var at = text.indexOf(query, ignoreCase = true)
    if (at < 0) return this
    val source = this
    return buildAnnotatedString {
        append(source)
        while (at >= 0) {
            addStyle(SpanStyle(background = color), at, at + query.length)
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
    }
}

/** Marks the find query in a plain text field (offsets stay the same). */
internal class FindTransformation(private val query: String, private val color: Color) :
    VisualTransformation {
    override fun filter(text: AnnotatedString) =
        TransformedText(text.highlighting(query, color), OffsetMapping.Identity)

    override fun equals(other: Any?) =
        other is FindTransformation && other.query == query && other.color == color

    override fun hashCode() = query.hashCode() * 31 + color.hashCode()
}

/**
 * Makes [content] findable: registers it for Ctrl/⌘+F and draws the find bar over it, [top] below
 * the screen's top edge. [pieces] is read only while find is open and must change with [key];
 * [reveal] brings a hit into view. A [native] view (see NativeMarkdownView) finds in its own text
 * instead, and [pieces] and [reveal] go unused.
 */
@Composable
internal fun Findable(
    key: Any?,
    pieces: () -> List<String>,
    reveal: suspend (FindHit) -> Unit,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    placeholder: String = "Find",
    native: NativeFind? = null,
    content: @Composable () -> Unit,
) {
    val state = remember { FindState() }
    DisposableEffect(state) {
        FindRequests.register(state)
        onDispose { FindRequests.unregister(state) }
    }
    val query = if (state.open) state.query else ""
    val text = remember(key, state.open) { if (state.open && native == null) pieces() else emptyList() }
    val hits = remember(text, query) { findHits(text, query) }
    val current = hits.getOrNull(state.current)
    LaunchedEffect(current, state.open) { current?.let { reveal(it) } }
    var nativeCount by remember { mutableStateOf(0) }
    if (native != null) {
        // A new search clears the marks, the current one included: show it again either way.
        LaunchedEffect(native, query, native.revision) {
            nativeCount = native.search(query)
            if (nativeCount > 0) native.show(state.current.coerceIn(0, nativeCount - 1))
        }
        LaunchedEffect(native, state.current) {
            if (nativeCount > 0) native.show(state.current.coerceIn(0, nativeCount - 1))
        }
    }
    SystemBackHandler(state.open) { state.close() }
    Box(modifier) {
        CompositionLocalProvider(LocalFindQuery provides query) { content() }
        if (state.open)
            FindBar(
                state,
                if (native != null) nativeCount else hits.size,
                placeholder,
                Modifier.align(Alignment.TopCenter)
                    .padding(top = top + 8.dp, start = 12.dp, end = 12.dp),
            )
    }
}

@Composable
private fun FindBar(state: FindState, count: Int, placeholder: String, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.focusRequests) { runCatching { focus.requestFocus() } }
    fun step(by: Int) {
        if (count > 0) state.current = ((state.current + by) % count + count) % count
    }
    val shape = RoundedCornerShape(if (apple) 12.dp else 16.dp)
    Row(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(6.dp, shape)
            .clip(shape)
            .background(if (apple) palette.cell else colors.surfaceContainerHigh)
            .height(48.dp)
            .padding(start = 14.dp, end = 4.dp)
            .floating()
            .testTag("find-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.SEARCH, null, tint = palette.secondaryLabel, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.query.isEmpty())
                Text(placeholder, style = type.body, color = palette.secondaryLabel, maxLines = 1)
            BasicTextField(
                state.input,
                {
                    if (it.text != state.input.text) state.current = 0
                    state.input = it
                },
                Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("find-field")
                    .semantics { contentDescription = placeholder }
                    .onPreviewKeyEvent { event ->
                        val down = event.type == KeyEventType.KeyDown
                        when (event.key) {
                            Key.Enter,
                            Key.NumPadEnter -> {
                                if (down) step(if (event.isShiftPressed) -1 else 1)
                                true
                            }
                            Key.DirectionDown -> {
                                if (down) step(1)
                                true
                            }
                            Key.DirectionUp -> {
                                if (down) step(-1)
                                true
                            }
                            Key.Escape -> {
                                if (down) state.close()
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                textStyle = type.body.copy(color = palette.label),
                cursorBrush = SolidColor(if (apple) palette.info else colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { step(1) }),
            )
        }
        Text(
            when {
                state.query.isBlank() -> ""
                count == 0 -> "No results"
                else -> "${state.current + 1}/$count"
            },
            Modifier.padding(horizontal = 8.dp).testTag("find-count").semantics {
                liveRegion = LiveRegionMode.Polite
            },
            style = type.footnote,
            color = palette.secondaryLabel,
            maxLines = 1,
        )
        IconButton({ step(-1) }, Modifier.testTag("find-previous"), enabled = count > 0) {
            Icon(Glyph.ARROW_UP, "Previous match", size = 20.dp)
        }
        IconButton({ step(1) }, Modifier.testTag("find-next"), enabled = count > 0) {
            Icon(Glyph.ARROW_DOWN, "Next match", size = 20.dp)
        }
        IconButton({ state.close() }, Modifier.testTag("find-close")) {
            Icon(Glyph.CLOSE, "Close find", size = 20.dp)
        }
    }
}
