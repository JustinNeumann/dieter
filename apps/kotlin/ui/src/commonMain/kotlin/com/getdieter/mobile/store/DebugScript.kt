package com.getdieter.mobile.store

import com.getdieter.client.v1.Command
import com.getdieter.client.v1.ScheduleId
import com.getdieter.client.v1.SchedulesCommand
import com.getdieter.mobile.navigation.*
import com.getdieter.mobile.settings.DieterPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Debug builds only: replays navigation steps so screenshots of any screen can be taken without
 * touch automation. Steps are separated by ";", e.g. "tab projects; board Main; card Design; pane
 * subagents", "channel Pricing", "tab mail; mail Dependency audit" or "tab tools; tool schedules; runs
 * Backup" (a schedule's run history).
 */
internal suspend fun MobileStore.runDebugScript(script: String) {
    workspace.slice.first { it.loaded }
    delay(600)
    for (raw in script.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val verb = raw.substringBefore(' ').lowercase()
        val argument = raw.substringAfter(' ', "").trim()
        val cards = workspace.slice.value.cards
        when (verb) {
            "tab" -> selectTab(MobileTab.valueOf(argument.uppercase()))
            "project" ->
                workspace.slice.value.projects
                    .firstOrNull { it.name.startsWith(argument, true) }
                    ?.let { push(MobileRoute.Project(it.id)) }
            "board" ->
                workspace.slice.value.boards
                    .firstOrNull { it.name.startsWith(argument, true) }
                    ?.let { push(MobileRoute.Board(it.id)) }
            "channel" ->
                workspace.slice.value.projects
                    .firstOrNull { it.name.startsWith(argument, true) }
                    ?.let { openChannel(it.id) }
            "mail" ->
                mail.slice.value.sections
                    .flatMap { it.rows }
                    .firstOrNull { it.subject.startsWith(argument, true) }
                    ?.ref
                    ?.let {
                        if (routes.value.tab != MobileTab.MAIL) selectTab(MobileTab.MAIL)
                        showFrom(
                            MobileRoute.Root(MobileTab.MAIL),
                            MobileRoute.MailMessage(it.daemon_id, it.id),
                        )
                    }
            "card",
            "chat" ->
                cards
                    .firstOrNull { it.title.startsWith(argument, true) }
                    ?.let { openConversation(it.id) }
            "pane" ->
                conversation.selectedCard.value
                    .takeIf { it.isNotEmpty() }
                    ?.let { push(MobileRoute.Pane(it, CardPane.valueOf(argument.uppercase()))) }
            "tool" ->
                push(
                    MobileRoute.Tool(
                        ToolPage.valueOf(argument.uppercase()),
                        boards.currentProjectId(),
                    )
                )
            "file" -> push(MobileRoute.FilePath(argument, file = true))
            "folder" -> push(MobileRoute.FilePath(argument, file = false))
            "machine" ->
                session.slice.value.machines
                    .firstOrNull {
                        it.display_name.startsWith(argument, true) || argument.isEmpty()
                    }
                    ?.let { push(MobileRoute.Machine(it.id)) }
            "runs" ->
                schedules.slice
                    .first { it.loaded }
                    .schedules
                    .firstOrNull { it.name.startsWith(argument, true) }
                    ?.let {
                        command(
                            Command(
                                schedules =
                                    SchedulesCommand(
                                        select = ScheduleId(it.id),
                                        scope = MobileStore.SCHEDULES_SCOPE,
                                    )
                            )
                        )
                    }
            "terminal" -> push(MobileRoute.Tool(ToolPage.TERMINALS))
            "new" -> newConversation(chat = false)
            "newchat" -> newConversation(chat = true)
            "appearance" -> theme.setAppearance(argument)
            "palette" -> theme.setPalette(DieterPalette.resolve(argument))
            "back" -> pop()
            "wait" -> delay(argument.toLongOrNull() ?: 500)
        }
        delay(450)
    }
}
