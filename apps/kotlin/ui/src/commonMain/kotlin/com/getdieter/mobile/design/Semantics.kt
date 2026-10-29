package com.getdieter.mobile.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

/**
 * Reads as [label] instead of what it shows, e.g. "Last activity 3m" for "3m". Set as text, not as a
 * content description: inside a clickable card or row the descriptions of merged children can name
 * the whole node (the web names it by them alone), which would hide the card's title. Put it on a
 * container around the text, so the text's own semantics are the ones cleared.
 */
internal fun Modifier.readAs(label: String): Modifier =
    clearAndSetSemantics { text = AnnotatedString(label) }

/** Decorative: a letter avatar or a mark whose meaning the text beside it already carries. */
internal fun Modifier.decorative(): Modifier = clearAndSetSemantics {}
