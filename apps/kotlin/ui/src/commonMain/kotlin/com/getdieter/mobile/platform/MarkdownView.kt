package com.getdieter.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Whether [NativeMarkdownView] shows long Markdown documents (a mail body, a file preview) as the
 * platform's own text: real HTML on the web, where Compose draws into a canvas that the browser
 * cannot select from, search, translate or read aloud. Elsewhere Compose's renderer is native
 * enough, so the actuals are off and the view is never composed.
 */
internal expect val nativeMarkdownViews: Boolean

/**
 * [markdown] as a native document filling [modifier], scrolling on its own. Links go through
 * [linkHandler] when [linkable]; [diagrams] renders vega-lite, vega and mermaid fences offline.
 * [onScrolled] tells whether the document is scrolled away from its top.
 */
@Composable
internal expect fun NativeMarkdownView(
    markdown: String,
    modifier: Modifier,
    linkHandler: (String) -> Unit,
    linkable: (String) -> Boolean,
    diagrams: Boolean,
    find: NativeFind,
    onScrolled: (Boolean) -> Unit,
)

/** Find inside a native view: the view sets the functions, the find bar calls them. */
@Stable
internal class NativeFind {
    /** Marks every hit of a query (empty clears them) and returns how many there are. */
    var search: (String) -> Int = { 0 }

    /** Marks hit [index] as the current one and brings it into view. */
    var show: (Int) -> Unit = {}

    /** Changes with the view's content, so hits are counted again. */
    var revision by mutableStateOf(0)
}
