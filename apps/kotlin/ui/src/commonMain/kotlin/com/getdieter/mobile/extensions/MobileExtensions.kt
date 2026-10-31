package com.getdieter.mobile.extensions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.getdieter.mobile.design.Glyph
import com.getdieter.mobile.navigation.MobileRoute
import com.getdieter.mobile.navigation.ToolPage
import com.getdieter.mobile.store.ConversationNamer
import com.getdieter.mobile.store.MobileStore

/**
 * What a host app adds to the shared screens: its own pages, sections on built-in tool pages, rows
 * at the top of the Inbox, widgets in a conversation, Projects menu actions and its own view for
 * some boards. The Home Assistant add-on uses them for features only it has (agent questions,
 * Dreamfall, Claude sign-in, gateway enrollment, ...). Apps without extensions get the empty
 * default.
 *
 * Pages open as [com.getdieter.mobile.navigation.MobileRoute.Extension] with the page's
 * [ExtensionPage.id].
 */
@Immutable
internal class MobileExtensions(
    /** Pages, listed in More when [ExtensionPage.section] is set. */
    val pages: List<ExtensionPage> = emptyList(),
    /** Sections appended to a built-in page (Usage, Devices, Settings). */
    val toolSections: Map<ToolPage, List<ExtensionSection>> = emptyMap(),
    /** Content at the top of the Inbox, below its search and filters. */
    val inboxSections: List<ExtensionSection> = emptyList(),
    /** Widgets above a conversation's composer, e.g. an agent's open question. */
    val conversationWidgets: List<ConversationWidget> = emptyList(),
    /** More entries in the Projects tab's Add menu. */
    val projectActions: List<ExtensionAction> = emptyList(),
    /** A board view of the host's own for the boards it claims (e.g. a Dreamfall stage). */
    val boardRenderer: BoardRenderer? = null,
    /**
     * Every board takes the whole width of a wide layout, its lanes side by side, instead of the
     * list pane; a card opens over it as on a phone (the add-on's page on desktops and tablets).
     */
    val wideBoards: Boolean = false,
    /**
     * New task and the schedule editor offer "Allow Home Assistant access" next to vault access:
     * the host lets agent turns of such conversations use Home Assistant (the add-on's relay checks
     * each turn with the daemon).
     */
    val homeAssistantAccess: Boolean = false,
    /**
     * The host's namer for new tasks and chats sent without a title, while the New task form is
     * open.
     */
    val conversationNamer: @Composable (MobileStore) -> ConversationNamer? = { null },
    /** A board order of the host's own; without one, boards keep Dieter's order. */
    val boardOrder: @Composable (MobileStore) -> BoardOrder? = { null },
    /**
     * Boards the host lists in Projects as one row of its own (e.g. a Dreamfall board's stage
     * boards).
     */
    val boardGroups: @Composable (MobileStore) -> List<BoardGroup> = { emptyList() },
    /** Quotas the host reads itself, as more bars in the Inbox's usage strip. */
    val usageBars: @Composable (MobileStore) -> List<UsageBar> = { emptyList() },
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
    /**
     * Where More lists the page (with its places, or its account pages); null for pages opened only
     * by route.
     */
    val section: ToolSection? = null,
    /** A short value at the end of its More row, e.g. "2 open". */
    val value: @Composable (MobileStore) -> String? = { null },
    /**
     * Whether the page for [arg] takes the whole width of a wide layout (e.g. a Dreamfall board)
     * instead of opening beside the list.
     */
    val wide: (arg: String) -> Boolean = { false },
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
    /** Claimed boards take the whole width of a wide layout instead of the list pane. */
    val wide: Boolean = false,
)

/**
 * A board order the host keeps (the add-on: one order for all its devices). Projects shows boards
 * in it, and a board is dragged into place after a long press.
 */
@Immutable
internal class BoardOrder(
    /** [boards] in the host's order. */
    val sorted: (List<com.getdieter.api.v1.Board>) -> List<com.getdieter.api.v1.Board>,
    /** A project's boards, dragged into this order. */
    val move: (List<String>) -> Unit,
)

/**
 * Boards in one row of the host's: it takes the place of the first of them and is dragged as one.
 */
@Immutable
internal class BoardGroup(
    val boardIds: List<String>,
    val title: String,
    /** The row's second line on the project's page, e.g. "Dreamfall · 42 ideas". */
    val subtitle: String,
    val glyph: Glyph,
    val tint: Long,
    /** The number at the row's end. */
    val value: String,
    /** The warning badge after it; 0 for none. */
    val attention: Int,
    val route: MobileRoute,
)

/** A quota of the host's, drawn like a provider's in the Inbox: [remaining] percent left. */
@Immutable internal class UsageBar(val name: String, val remaining: Int)

internal val LocalMobileExtensions = staticCompositionLocalOf { MobileExtensions() }

/** The host's sections for a built-in page, as the last items of its list. */
internal fun androidx.compose.foundation.lazy.LazyListScope.extensionSections(
    sections: List<ExtensionSection>?,
    store: MobileStore,
) {
    sections.orEmpty().forEach { section -> item("ext-${section.key}") { section.content(store) } }
}
