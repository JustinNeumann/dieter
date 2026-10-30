@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.getdieter.mobile.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.getdieter.api.v1.MessagePart
import com.getdieter.api.v1.ToolOutput
import com.getdieter.client.v1.*
import com.getdieter.core.composition.Attachments
import com.getdieter.core.files.FilePaths
import com.getdieter.core.presentation.ToolImages
import com.getdieter.mobile.app.*
import com.getdieter.mobile.channels.PeerNoticeCard
import com.getdieter.mobile.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
internal fun TimelineRow(
    row: TimelineItem,
    view: ConversationSlice,
    openUrl: (String) -> Unit,
    onAttachment: (MessagePart) -> Unit,
    onTool: (TimelineStep) -> Unit,
    onCopy: () -> Unit,
    toolImages: ToolImageLoader? = null,
) {
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    if (row.activity && !expanded) {
        ActivitySummaryRow(row.summary, false) { expanded = true }
        return
    }
    if (row.user) {
        UserMessage(row, view, openUrl, onAttachment, onCopy)
        return
    }
    val menu = rememberMenuState()
    MenuAnchor(menu) {
        Column(
            Modifier.fillMaxWidth()
                .then(
                    if (row.copyable)
                        Modifier.pressable(
                            onClick = {},
                            onLongClick = {
                                menu.show(
                                    listOf(
                                        MenuSection(
                                            listOf(
                                                ChromeAction(
                                                    "copy",
                                                    "Copy",
                                                    Glyph.COPY,
                                                    onClick = onCopy,
                                                )
                                            )
                                        )
                                    )
                                )
                            },
                            role = null,
                        )
                    else Modifier
                )
                .padding(horizontal = ScreenMargin + 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (row.activity)
                ActivitySummaryRow(row.summary, true, padded = false) { expanded = false }
            row.groups.forEach { group ->
                var open by rememberSaveable(group.id) { mutableStateOf(false) }
                if (group.activity && !row.activity)
                    ActivitySummaryRow(group.summary, open, padded = false) { open = !open }
                if (!group.activity || open || row.activity)
                    group.steps.forEach { step ->
                        TimelineStepView(step, row, view, openUrl, onAttachment, onTool, toolImages)
                    }
            }
            view.conversation
                ?.task_plans
                .orEmpty()
                .filter { it.id in row.plan_ids }
                .forEach { TaskPlanCard(it) }
        }
    }
}

@Composable
private fun TimelineStepView(
    step: TimelineStep,
    row: TimelineItem,
    view: ConversationSlice,
    openUrl: (String) -> Unit,
    onAttachment: (MessagePart) -> Unit,
    onTool: (TimelineStep) -> Unit,
    toolImages: ToolImageLoader?,
) {
    val part =
        view.messages.firstOrNull { it.id == step.message_id }?.parts?.getOrNull(step.part_index)
    val text = step.text.ifEmpty { part?.text.orEmpty() }
    when (step.kind) {
        TimelineStepKind.TIMELINE_STEP_KIND_TOOL -> {
            ToolRow(step) { onTool(step) }
            if (part != null && toolImages != null && ToolImages.shown(part))
                toolImages.of(step.message_id, part).forEach { image ->
                    Picture(image, step.tool_title, Modifier.padding(top = 6.dp), 560.dp, 420.dp) {
                        onAttachment(image)
                    }
                }
        }
        TimelineStepKind.TIMELINE_STEP_KIND_REASONING ->
            if (text.isNotBlank())
                Row {
                    Box(
                        Modifier.width(2.dp)
                            .heightIn(min = 18.dp)
                            .background(palette.separator, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text,
                        style = type.subheadline.copy(fontStyle = FontStyle.Italic),
                        color = palette.secondaryLabel,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        TimelineStepKind.TIMELINE_STEP_KIND_SUBAGENTS ->
            view.conversation
                ?.subagents
                .orEmpty()
                .filter { it.id in row.subagent_ids }
                .forEach { SubagentCard(it) }
        TimelineStepKind.TIMELINE_STEP_KIND_ATTACHMENT ->
            part?.let { AttachmentTile(it) { onAttachment(it) } }
        TimelineStepKind.TIMELINE_STEP_KIND_PEER_NOTICE -> {
            val store = LocalMobileStore.current
            val notice = step.peer_notice
            if (notice != null) PeerNoticeCard(notice, openUrl) { store?.openChannel(it) }
            else if (text.isNotEmpty()) RichText(text, openUrl)
        }
        else -> if (text.isNotEmpty()) RichText(text, openUrl)
    }
}

@Composable
private fun UserMessage(
    row: TimelineItem,
    view: ConversationSlice,
    openUrl: (String) -> Unit,
    onAttachment: (MessagePart) -> Unit,
    onCopy: () -> Unit,
) {
    val menu = rememberMenuState()
    val steps = row.groups.flatMap { it.steps }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ScreenMargin),
        horizontalAlignment = Alignment.End,
    ) {
        if (row.origin_label.isNotEmpty())
            // A turn an agent channel message started, not the user.
            Row(
                Modifier.padding(bottom = 4.dp, end = 6.dp).testTag("message-origin-${row.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Glyph.TAG, null, tint = palette.info, size = 11.dp, weight = GlyphWeight.BOLD)
                Text(
                    row.origin_label,
                    style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.info,
                )
            }
        steps
            .filter { it.kind == TimelineStepKind.TIMELINE_STEP_KIND_ATTACHMENT }
            .forEach { step ->
                view.messages
                    .firstOrNull { it.id == step.message_id }
                    ?.parts
                    ?.getOrNull(step.part_index)
                    ?.let { part ->
                        AttachmentTile(part, Modifier.padding(bottom = 6.dp)) { onAttachment(part) }
                    }
            }
        val text =
            steps
                .filter { it.kind != TimelineStepKind.TIMELINE_STEP_KIND_ATTACHMENT }
                .joinToString("\n\n") { step ->
                    step.text.ifEmpty {
                        view.messages
                            .firstOrNull { it.id == step.message_id }
                            ?.parts
                            ?.getOrNull(step.part_index)
                            ?.text
                            .orEmpty()
                    }
                }
                .trim()
        if (text.isNotEmpty())
            MenuAnchor(menu) {
                Box(
                    Modifier.widthIn(max = 560.dp)
                        .fillMaxWidth(.86f)
                        .wrapContentWidth(Alignment.End)
                        .clip(RoundedCornerShape(if (apple) 20.dp else 22.dp))
                        .background(if (apple) userBubbleApple() else colors.primaryContainer)
                        .pressable(
                            onClick = {},
                            onLongClick = {
                                menu.show(
                                    listOf(
                                        MenuSection(
                                            listOf(
                                                ChromeAction(
                                                    "copy",
                                                    "Copy",
                                                    Glyph.COPY,
                                                    onClick = onCopy,
                                                )
                                            )
                                        )
                                    )
                                )
                            },
                            role = null,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides
                            if (apple) palette.label else colors.onPrimaryContainer
                    ) {
                        RichText(text, openUrl)
                    }
                }
            }
        val delivery = row.delivery
        if (
            row.delivery_label.isNotEmpty() &&
                delivery != MessageDelivery.MESSAGE_DELIVERY_SYNCED &&
                delivery != MessageDelivery.MESSAGE_DELIVERY_UNSPECIFIED
        )
            Text(
                row.delivery_label,
                Modifier.padding(top = 4.dp, end = 6.dp),
                style = type.caption,
                color =
                    if (delivery == MessageDelivery.MESSAGE_DELIVERY_FAILED) palette.destructive
                    else palette.secondaryLabel,
            )
    }
}

@Composable
private fun userBubbleApple() =
    if (palette.dark) androidx.compose.ui.graphics.Color(0xFF2C2C2E)
    else androidx.compose.ui.graphics.Color(0xFFE9E9EB)

@Composable
private fun ActivitySummaryRow(
    summary: String,
    open: Boolean,
    padded: Boolean = true,
    toggle: () -> Unit,
) {
    Row(
        Modifier.then(if (padded) Modifier.padding(horizontal = ScreenMargin) else Modifier)
            .clip(CircleShape)
            .pressable(onClick = toggle)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .semantics { contentDescription = (if (open) "Hide " else "Show ") + summary },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.SPARKLES, null, tint = palette.secondaryLabel, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            summary.ifEmpty { "Agent activity" },
            style = type.subheadline,
            color = palette.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        DisclosureChevron(open)
    }
}

@Composable
private fun ToolRow(step: TimelineStep, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.fill.copy(alpha = palette.fill.alpha * .7f))
            .pressable(onClick = onClick, highlight = true)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.TERMINAL, null, tint = palette.secondaryLabel, size = 15.dp)
        Spacer(Modifier.width(9.dp))
        Text(
            step.tool_title,
            Modifier.weight(1f),
            style = type.footnote.copy(fontWeight = FontWeight.Medium),
            color = palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            step.tool_status_label,
            style = type.caption,
            color = if (step.tool_attention) palette.destructive else palette.secondaryLabel,
            maxLines = 1,
        )
    }
}

/**
 * The images of tool calls that returned some ([ToolImages.shown]), each loaded once while the conversation is
 * open; [load] answers null when the output can't be had.
 */
@Stable
internal class ToolImageLoader(private val load: suspend (messageId: String, part: MessagePart) -> ToolOutput?) {
    private val loaded = mutableStateMapOf<String, List<MessagePart>>()

    @Composable
    fun of(messageId: String, part: MessagePart): List<MessagePart> {
        val key = "${part.tool_call_id}@${part.payload_revision}"
        LaunchedEffect(key) {
            if (key !in loaded) loaded[key] = load(messageId, part)?.let { ToolImages.find(it.output_json) }.orEmpty()
        }
        return loaded[key].orEmpty()
    }
}

/** An image part decoded for drawing; [bitmap] is null for one this platform can't draw (SVG, damaged). */
private class Decoded(val bitmap: ImageBitmap?)

/** [part] decoded, null while that runs; a part that isn't an image decodes to nothing at once. */
@Composable
private fun rememberDecoded(part: MessagePart): Decoded? {
    if (!FilePaths.isImage(part.filename, part.media_type)) return Decoded(null)
    return produceState<Decoded?>(null, part) {
        value =
            Decoded(
                withContext(Dispatchers.Default) {
                    Attachments.bytes(part)?.let { runCatching { it.toByteArray().decodeToImageBitmap() }.getOrNull() }
                }
            )
    }.value
}

/** An image shown as itself, fitted into [maxWidth] × [maxHeight]; nothing while it decodes or when it can't be. */
@Composable
private fun Picture(
    part: MessagePart,
    description: String,
    modifier: Modifier,
    maxWidth: Dp,
    maxHeight: Dp,
    onClick: () -> Unit,
) {
    val bitmap = rememberDecoded(part)?.bitmap ?: return
    Image(
        bitmap,
        description,
        modifier
            .widthIn(max = maxWidth)
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(14.dp))
            .pressable(onClick = onClick, highlight = true),
        contentScale = ContentScale.Fit,
    )
}

/** An attachment: an image shows as itself, anything else (or an image that can't be drawn) as a file tile. */
@Composable
private fun AttachmentTile(part: MessagePart, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val decoded = rememberDecoded(part) ?: return
    if (decoded.bitmap != null) {
        Picture(part, part.filename.ifEmpty { "Image" }, modifier, 280.dp, 280.dp, onClick)
        return
    }
    Row(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.cell)
            .border(.5.dp, palette.separator.copy(alpha = .5f), RoundedCornerShape(14.dp))
            .pressable(onClick = onClick, highlight = true)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(palette.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (part.media_type.startsWith("image/")) Glyph.IMAGE else Glyph.FILE_PLAIN,
                null,
                tint = palette.secondaryLabel,
                size = 18.dp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                part.filename.ifEmpty { "Attachment" },
                style = type.subheadline.copy(fontWeight = FontWeight.Medium),
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                com.getdieter.core.composition.Attachments.details(part),
                style = type.caption,
                color = palette.secondaryLabel,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ToolOutputSheet(tool: ToolOutput, onImage: (MessagePart) -> Unit = {}, onDismiss: () -> Unit) {
    val images = remember(tool) { ToolImages.find(tool.output_json) }
    Sheet(tool.tool_name.ifEmpty { "Tool" }, onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tool.state, style = type.subheadline, color = palette.secondaryLabel)
            images.forEach { image ->
                Picture(image, image.filename, Modifier, 560.dp, 420.dp) { onImage(image) }
            }
            listOf(
                    "Input" to tool.input_json.utf8(),
                    // images stand above; their base64 shows as a placeholder
                    "Output" to if (images.isEmpty()) tool.output_json.utf8() else ToolImages.text(tool.output_json),
                    "Error" to tool.error_text,
                )
                .filter { it.second.isNotEmpty() }
                .forEach { (title, body) ->
                    FormLabel(title)
                    SelectionContainer {
                        Text(
                            body,
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.cell)
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            style = type.mono,
                            color = if (title == "Error") palette.destructive else palette.label,
                        )
                    }
                }
        }
    }
}
