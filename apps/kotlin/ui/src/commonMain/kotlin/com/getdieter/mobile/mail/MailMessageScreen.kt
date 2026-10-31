package com.getdieter.mobile.mail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.getdieter.api.v1.MailFolder
import com.getdieter.client.v1.*
import com.getdieter.core.presentation.Markdown
import com.getdieter.core.presentation.MarkdownBlock
import com.getdieter.core.presentation.MarkdownLinkTarget
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.design.Tone
import com.getdieter.mobile.navigation.*
import com.getdieter.mobile.platform.*
import com.getdieter.mobile.store.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One message: its sender and the conversation that sent it, then the Markdown body. Web and mail
 * links open outside; workspace links open the file on the sender's checkout; nothing else opens.
 */
@Composable
internal fun MailMessageScreen(
    store: MobileStore,
    route: MobileRoute.MailMessage,
    openUrl: (String) -> Unit,
) {
    val slice by store.mail.slice.collectAsState()
    val workspace by store.workspace.slice.collectAsState()
    val reader =
        slice.reader_?.takeIf { it.ref?.daemon_id == route.daemonId && it.ref?.id == route.id }
    // The listed row stands in for the header until the reading pane follows the selection.
    val row =
        slice.sections
            .asSequence()
            .flatMap { it.rows }
            .firstOrNull { it.ref?.daemon_id == route.daemonId && it.ref?.id == route.id }
    val ref = MailMessageRef(daemon_id = route.daemonId, id = route.id)
    val clipboard = LocalClipboardManager.current
    val share = rememberTextSharer()
    var deleting by remember { mutableStateOf(false) }
    val folder = reader?.folder ?: row?.folder ?: MailFolder.MAIL_FOLDER_UNSPECIFIED
    val read = reader?.read ?: row?.read ?: true
    val flagged = reader?.flagged ?: row?.flagged ?: false
    val body = reader?.body.orEmpty()
    val trash = folder == MailFolder.MAIL_FOLDER_TRASH
    val known = reader != null || row != null

    /** Leaves the message once it moved out of view, as Mail does on phones. */
    fun close() {
        if (store.routes.value.top == route) store.pop()
    }

    // Workspace links resolve against the sender's checkout on the sender's machine.
    val checkout =
        reader
            ?.takeIf { it.checkout_id.isNotEmpty() }
            ?.let { reading ->
                workspace.projects.firstNotNullOfOrNull { project ->
                    project.checkouts
                        .firstOrNull {
                            it.id == reading.checkout_id && it.daemon_id == route.daemonId
                        }
                        ?.let { project.id to it }
                }
            }
    val root = checkout?.second?.path
    val openLink: (String) -> Unit = { url ->
        when (val target = Markdown.link(url, root)) {
            is MarkdownLinkTarget.External ->
                runCatching { openUrl(target.url) }
                    .onFailure { store.error.value = "No app can open this link." }
            is MarkdownLinkTarget.File ->
                checkout?.let { (projectId, place) ->
                    store.push(
                        MobileRoute.FilePath(
                            target.path,
                            file = true,
                            daemonId = place.daemon_id,
                            projectId = projectId,
                            checkoutId = place.id,
                        )
                    )
                }
            MarkdownLinkTarget.Inert -> Unit
        }
    }
    val linkable: (String) -> Boolean = { Markdown.link(it, root) != MarkdownLinkTarget.Inert }

    val menu =
        listOf(
            ChromeAction(
                "mail-toggle-read",
                if (read) "Mark unread" else "Mark read",
                if (read) Glyph.MAIL else Glyph.MAIL_OPEN,
                enabled = known,
            ) {
                store.mail.change(listOf(ref), read = !read)
            },
            ChromeAction(
                "mail-toggle-flag",
                if (flagged) "Unflag" else "Flag",
                if (flagged) Glyph.FLAG_FILL else Glyph.FLAG,
                enabled = known,
            ) {
                store.mail.change(listOf(ref), flagged = !flagged)
            },
            ChromeAction(
                "mail-move-inbox",
                "Move to Inbox",
                Glyph.MOVE_TO_INBOX,
                enabled = known && folder != MailFolder.MAIL_FOLDER_INBOX,
            ) {
                store.mail.change(listOf(ref), folder = MailFolder.MAIL_FOLDER_INBOX)
            },
            ChromeAction("mail-copy", "Copy Markdown", Glyph.COPY, enabled = body.isNotEmpty()) {
                clipboard.setText(AnnotatedString(body))
            },
            ChromeAction("mail-share", "Share", Glyph.SHARE, enabled = body.isNotEmpty()) {
                share(reader?.subject.orEmpty(), body)
            },
            ChromeAction(
                "mail-open-conversation-menu",
                "Open conversation",
                Glyph.CHAT,
                enabled = reader?.can_open_conversation == true,
                subtitle =
                    if (reader?.can_open_conversation == true) ""
                    else reader?.open_conversation_unavailable.orEmpty(),
            ) {
                reader?.card_id?.takeIf { it.isNotEmpty() }?.let(store::openConversation)
            },
        )
    val chrome =
        ScreenChrome(
            "",
            actions =
                listOf(
                    ChromeAction(
                        "mail-archive",
                        "Archive",
                        Glyph.ARCHIVE,
                        enabled = known && folder != MailFolder.MAIL_FOLDER_ARCHIVE,
                    ) {
                        store.mail.change(listOf(ref), folder = MailFolder.MAIL_FOLDER_ARCHIVE)
                        close()
                    },
                    ChromeAction(
                        "mail-delete",
                        if (trash) "Delete permanently" else "Delete",
                        Glyph.TRASH,
                        enabled = known,
                        destructive = trash,
                    ) {
                        if (trash) deleting = true
                        else {
                            store.mail.change(listOf(ref), folder = MailFolder.MAIL_FOLDER_TRASH)
                            close()
                        }
                    },
                ) + overflow("mail-message-more", menu, "Message actions"),
        )
    // A 128 KiB body parses off the main thread; inline trees parse as blocks show.
    val blocks by
        produceState<List<MarkdownBlock>?>(null, body) {
            value =
                if (body.isEmpty()) emptyList()
                else
                    withContext(Dispatchers.Default) {
                        Markdown.parse(body)
                    }
        }
    val stale = reader?.stale ?: row?.stale.orEmpty()
    val state =
        when {
            reader == null && row == null -> BodyState.GONE
            reader != null && reader.unavailable.isNotEmpty() -> BodyState.UNAVAILABLE
            reader == null || reader.loading -> BodyState.LOADING
            else -> BodyState.READY
        }
    val openConversation = {
        reader?.card_id?.takeIf { it.isNotEmpty() }?.let(store::openConversation)
        Unit
    }
    if (nativeMarkdownViews) {
        // The body is the platform's own text (HTML on the web) and scrolls by itself; the header
        // stays above it and folds to the subject once the body is scrolled.
        val native = remember(route) { NativeFind() }
        var scrolled by remember(route) { mutableStateOf(false) }
        Screen(chrome) {
            Findable(
                body,
                { emptyList() },
                {},
                top = padding.calculateTopPadding(),
                placeholder = "Find in message",
                native = native,
            ) {
                Column(
                    Modifier.fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                        .testTag("mail-message")
                ) {
                    SelectionContainer {
                        Header(reader, row, openConversation, compact = scrolled)
                    }
                    if (stale.isNotEmpty()) StaleBanner(stale)
                    Hairline(Modifier.padding(horizontal = ScreenMargin).padding(top = 12.dp))
                    if (state == BodyState.READY && body.isNotEmpty())
                        NativeMarkdownView(
                            body,
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .padding(bottom = padding.calculateBottomPadding())
                                .testTag("mail-body"),
                            openLink,
                            linkable,
                            diagrams = true,
                            find = native,
                            onScrolled = { scrolled = it },
                        )
                    else BodyPlaceholder(if (state == BodyState.READY) BodyState.LOADING else state, reader)
                }
            }
        }
    } else {
    // A 128 KiB body parses off the main thread; inline trees parse as blocks show.
    val blocks by
        produceState<List<MarkdownBlock>?>(null, body) {
            value =
                if (body.isEmpty()) emptyList()
                else
                    withContext(Dispatchers.Default) {
                        Markdown.parse(body)
                    }
        }
    // The body's blocks follow the header, the banner and the rule.
    val bodyStart = if (stale.isNotEmpty()) 3 else 2
    val findClearance = with(LocalDensity.current) { 72.dp.roundToPx() }
    Screen(chrome) {
        Findable(
            blocks,
            { blocks.orEmpty().map(::findText) },
            { hit -> listState.revealHit(bodyStart + hit.piece, hit.fraction, findClearance) },
            top = padding.calculateTopPadding(),
            placeholder = "Find in message",
        ) {
            // One selection across the header and every block, so a copy can span paragraphs.
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("mail-message"),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = padding.calculateTopPadding(),
                            bottom = 24.dp + padding.calculateBottomPadding(),
                        ),
                ) {
                    item("header") { Header(reader, row, openConversation) }
                    if (stale.isNotEmpty()) item("stale") { StaleBanner(stale) }
                    item("rule") {
                        Hairline(Modifier.padding(horizontal = ScreenMargin, vertical = 12.dp))
                    }
                    val shown = blocks
                    if (state == BodyState.READY && shown != null)
                        markdownDocument(
                            shown,
                            openLink,
                            linkable = linkable,
                            linkKey = root,
                            key = "body-${route.daemonId}-${route.id}",
                            diagrams = true,
                            selectable = false,
                        )
                    else
                        item("placeholder") {
                            BodyPlaceholder(
                                if (state == BodyState.READY) BodyState.LOADING else state,
                                reader,
                            )
                        }
                }
            }
        }
    }
    }
    if (deleting)
        ConfirmDialog(
            "Delete this message?",
            "Deleted messages cannot be restored.",
            "Delete",
            {
                deleting = false
                store.mail.delete(listOf(ref))
                close()
            },
            { deleting = false },
            destructive = true,
        )
}

private enum class BodyState {
    GONE,
    UNAVAILABLE,
    LOADING,
    READY,
}

@Composable
private fun StaleBanner(stale: String) =
    Banner(
        "May be out of date",
        stale,
        Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp),
        tone = Tone.WARNING,
        glyph = Glyph.OFFLINE,
    )

/** What shows instead of a body: why there is none, or that it loads. */
@Composable
private fun BodyPlaceholder(state: BodyState, reader: MailReader?) {
    when (state) {
        BodyState.GONE ->
            EmptyState(
                Glyph.MAIL,
                "Message unavailable",
                "It was deleted or its machine is no longer in your account.",
            )
        BodyState.UNAVAILABLE ->
            EmptyState(
                Glyph.OFFLINE,
                "The message can't be shown",
                reader?.unavailable.orEmpty(),
                Modifier.testTag("mail-unavailable"),
            )
        else ->
            Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Spinner(Modifier.size(26.dp))
            }
    }
}

/** [compact] keeps the subject only, for a body that scrolls on its own. */
@Composable
private fun Header(
    reader: MailReader?,
    row: MailRow?,
    onOpenConversation: () -> Unit,
    compact: Boolean = false,
) {
    val subject = reader?.subject ?: row?.subject.orEmpty()
    val important = reader?.important ?: row?.important ?: false
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ScreenMargin + 4.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            subject,
            Modifier.testTag("mail-subject"),
            style = if (apple) type.title2 else type.title3.copy(fontWeight = FontWeight.SemiBold),
            color = palette.label,
        )
        if (important)
            Text(
                "Important",
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(palette.destructive.copy(alpha = .14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = palette.destructive,
            )
        AnimatedVisibility(!compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MailAvatar(
                reader?.initials ?: row?.initials.orEmpty(),
                reader?.sender_style ?: row?.sender_style ?: MailSenderStyle.MAIL_SENDER_STYLE_CARD,
                reader?.harness ?: row?.harness.orEmpty(),
                row?.sender_key ?: reader?.sender.orEmpty(),
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    reader?.sender ?: row?.sender.orEmpty(),
                    style = type.headline,
                    color = palette.label,
                )
                val detail = reader?.sender_detail ?: row?.sender_detail.orEmpty()
                if (detail.isNotEmpty())
                    Text(detail, style = type.footnote, color = palette.secondaryLabel)
                val sent = reader?.sent_label ?: row?.time_label.orEmpty()
                if (sent.isNotEmpty())
                    Text(sent, style = type.footnote, color = palette.secondaryLabel)
            }
        }
        if (reader != null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DButton(
                    "Open conversation",
                    onOpenConversation,
                    Modifier.testTag("mail-open-conversation"),
                    kind = ButtonKind.TONAL,
                    glyph = Glyph.CHAT,
                    enabled = reader.can_open_conversation,
                )
                if (
                    !reader.can_open_conversation &&
                        reader.open_conversation_unavailable.isNotEmpty()
                )
                    Text(
                        reader.open_conversation_unavailable,
                        style = type.footnote,
                        color = palette.secondaryLabel,
                    )
            }
        }
        }
    }
}
