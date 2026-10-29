package com.getdieter.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Compose's Markdown renderer is the native text here. */
internal actual val nativeMarkdownViews: Boolean = false

@Composable
internal actual fun NativeMarkdownView(
    markdown: String,
    modifier: Modifier,
    linkHandler: (String) -> Unit,
    linkable: (String) -> Boolean,
    diagrams: Boolean,
    find: NativeFind,
    onScrolled: (Boolean) -> Unit,
) = Unit
