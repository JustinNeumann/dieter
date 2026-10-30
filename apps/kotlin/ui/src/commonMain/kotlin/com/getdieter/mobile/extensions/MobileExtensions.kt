package com.getdieter.mobile.extensions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.getdieter.mobile.design.Glyph
import com.getdieter.mobile.navigation.ToolPage
import com.getdieter.mobile.store.ConversationNamer
import com.getdieter.mobile.store.MobileStore

/**
 * What a host app adds to the shared screens: its own pages, sections on built-in tool pages, rows
 * at the top of the Inbox, widgets in a conversation, Projects menu actions and its own view for
 * some boards. The Home Assistant add-on uses them for features only it has (agent questions,
 * Dreamfall, Claude sign-in, gateway enrollment, ...). Apps without extensions get the empty default.
 *
 * Pages open as [com.getdieter.mobile.navigation.MobileRoute.Extension] with the page's [ExtensionPage.id].
 */
@Immutable
internal class MobileExtensions(
    /** Pages, listed in the Tools tab when [ExtensionPage.section] is set. */
    val pages: List<ExtensionPage> = emptyList(),
    /** Sections appended to a built-in tool page (Machines, Usage, Devices, Settings). */
    val toolSections: Map<ToolPage, List<ExtensionSection>> = emptyMap(),
    /** Content at the top of the Inbox, below its search and filters. */
    val inboxSections: List<ExtensionSection> = emptyList(),
    /** Widgets above a conversation's composer, e.g. an agent's open question. */
    val conversationWidgets: List<ConversationWidget> = emptyList(),
    /** More entries in the Projects tab's Add menu. */
    val projectActions: List<ExtensionAction> = emptyList(),
    /** A board view of the host's own for the boards it claims (e.g. a Dreamfall stage). */
    val boardRenderer: BoardRenderer? = null,
    /** The host's namer for new tasks and chats sent without a title, while the New task form is open. */
    val conversationNamer: @Composable (MobileStore) -> ConversationNamer? = { null },
) {
    fun page(id: String): ExtensionPage? = pages.firstOrNull { it.id == id }
}

/** Where the Tools tab lists an extension page. */
internal enum class ToolSection {
    MACHINES,
    WORKSPACE,
    ACCOUNT,
}

@Immutable
internal class ExtensionPage(
    val id: String,
    val title: String,
    val glyph: Glyph,
    val tint: Long,
    /** The Tools section listing the page; null for pages opened only by route. */
    val section: ToolSection? = null,
    /** A short value at the end of its Tools row, e.g. "2 open". */
    val value: @Composable (MobileStore) -> String? = { null },
    /** The page; [arg] is the route's argument (a board ID, an idea ID, ...). */
    val content: @Composable (store: MobileStore, arg: String) -> Unit,
)

@Immutable
internal class ExtensionSection(
    val key: String,
    val content: @Composable (MobileStore) -> Unit,
)

@Immutable
internal class ConversationWidget(
    val key: String,
    val content: @Composable (store: MobileStore, cardId: String) -> Unit,
)

@Immutable
internal class ExtensionAction(
    val id: String,
    val title: String,
    val glyph: Glyph,
    val onClick: (MobileStore) -> Unit,
)

@Immutable
internal class BoardRenderer(
    /** Whether the host draws [boardId] itself; may change as its data loads. */
    val claims: @Composable (store: MobileStore, boardId: String) -> Boolean,
    val content: @Composable (store: MobileStore, boardId: String) -> Unit,
)

internal val LocalMobileExtensions = staticCompositionLocalOf { MobileExtensions() }

/** The host's sections for a built-in page, as the last items of its list. */
internal fun androidx.compose.foundation.lazy.LazyListScope.extensionSections(
    sections: List<ExtensionSection>?,
    store: MobileStore,
) {
    sections.orEmpty().forEach { section -> item("ext-${section.key}") { section.content(store) } }
}
