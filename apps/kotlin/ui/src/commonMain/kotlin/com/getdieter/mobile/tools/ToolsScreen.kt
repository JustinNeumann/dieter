@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.getdieter.mobile.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.getdieter.core.schedules.SchedulePresentations
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.extensions.*
import com.getdieter.mobile.navigation.*
import com.getdieter.mobile.screens.*
import com.getdieter.mobile.settings.DevicesScreen
import com.getdieter.mobile.settings.SettingsScreen
import com.getdieter.mobile.store.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Project" and "Checkout" menus for project-scoped tools; with [allProjects], the project menu
 * also offers every project.
 */
internal fun projectScopeMenu(
    store: MobileStore,
    allProjects: MutableStateFlow<Boolean>? = null,
    rebind: () -> Unit,
): List<ChromeAction> {

    val workspace = store.workspace.slice.value
    val current = store.boards.currentProjectId()
    val project = workspace.projects.firstOrNull { it.id == current }
    val checkout =
        store.boards.selectedCheckout.value
            .ifEmpty { store.creation.defaults.value.checkouts[current].orEmpty() }
            .ifEmpty { project?.checkouts?.singleOrNull()?.id.orEmpty() }
    val machines = store.session.slice.value.machines
    val all = allProjects?.value == true
    return listOfNotNull(
        ChromeAction(
            "scope-project",
            "Project",
            Glyph.FOLDER,
            subtitle = if (all) SchedulePresentations.ALL_PROJECTS else project?.name.orEmpty(),
            menu =
                listOfNotNull(
                    allProjects?.let { flag ->
                        MenuSection(
                            listOf(
                                ChromeAction(
                                    "scope-project-all",
                                    SchedulePresentations.ALL_PROJECTS,
                                    checked = all,
                                ) {
                                    flag.value = true
                                    rebind()
                                }
                            )
                        )
                    },
                    MenuSection(
                        workspace.projects.map { item ->
                            ChromeAction(
                                "scope-project-${item.id}",
                                item.name,
                                checked = !all && item.id == current,
                            ) {
                                allProjects?.value = false
                                store.boards.selectedProject.value = item.id

                                store.boards.selectedCheckout.value = ""
                                rebind()
                            }
                        }
                    ),
                ),
        ),
        if ((project?.checkouts?.size ?: 0) > 1)
            ChromeAction(
                "scope-checkout",
                "Checkout",
                Glyph.MACHINE,
                menu =
                    listOf(
                        MenuSection(
                            project!!.checkouts.map { item ->
                                ChromeAction(
                                    "scope-checkout-${item.id}",
                                    (machines.firstOrNull { it.id == item.daemon_id }?.display_name
                                        ?: item.name) + " · " + item.name,
                                    checked = item.id == checkout,
                                ) {
                                    store.boards.selectedCheckout.value = item.id
                                    rebind()
                                }
                            }
                        )
                    ),
            )
        else null,
    )
}

@Composable
internal fun ToolScreen(store: MobileStore, page: ToolPage) {
    when (page) {
        ToolPage.MACHINES -> MachinesScreen(store)
        ToolPage.TERMINALS -> TerminalsScreen(store)
        ToolPage.SCREENS -> ScreensScreen(store)
        ToolPage.FILES -> FilesScreen(store)
        ToolPage.CHANGES -> ProjectChangesScreen(store)
        ToolPage.SCHEDULES -> SchedulesScreen(store)
        ToolPage.USAGE -> UsageScreen(store)
        ToolPage.DEVICES -> DevicesScreen(store)
        ToolPage.SETTINGS -> SettingsScreen(store)
    }
}

@Composable
internal fun ToolsScreen(store: MobileStore) {
    val session by store.session.slice.collectAsState()
    val workspace by store.workspace.slice.collectAsState()
    val quotas by store.machines.quotas.collectAsState()
    val selectedProject by store.boards.selectedProject.collectAsState()
    val extensions = LocalMobileExtensions.current
    val project = workspace.projects.firstOrNull { it.id == store.boards.currentProjectId() }
    val online = session.machines.count { it.online }
    val lowest =
        quotas.group_rows.filter { it.lowest_remaining >= 0 }.minOfOrNull { it.lowest_remaining }
    fun open(page: ToolPage) {
        val scoped =
            page == ToolPage.FILES || page == ToolPage.CHANGES || page == ToolPage.SCHEDULES
        store.showFrom(
            MobileRoute.Root(MobileTab.TOOLS),
            if (scoped) MobileRoute.Tool(page, selectedProject.ifEmpty { project?.id.orEmpty() })
            else MobileRoute.Tool(page),
        )
    }
    Screen(ScreenChrome("Tools", large = true)) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("tools-list"),
            state = listState,
            contentPadding = padding,
        ) {
            titleHeader()
            item { ConnectionNotice(store) }
            for (section in ToolSection.entries) {
                val builtIn =
                    when (section) {
                        ToolSection.MACHINES ->
                            listOf(ToolPage.MACHINES, ToolPage.TERMINALS, ToolPage.SCREENS)
                        ToolSection.WORKSPACE ->
                            listOf(ToolPage.FILES, ToolPage.CHANGES, ToolPage.SCHEDULES)
                        ToolSection.ACCOUNT ->
                            listOf(ToolPage.USAGE, ToolPage.DEVICES, ToolPage.SETTINGS)
                    }
                val rows: List<Any> = builtIn + extensions.pages.filter { it.section == section }
                item("header-${section.name}") {
                    when (section) {
                        ToolSection.MACHINES -> SectionHeader("Machines")
                        ToolSection.WORKSPACE ->
                            SectionHeader(
                                "Workspace",
                                trailing = {
                                    if (project != null)
                                        Text(
                                            project.name,
                                            style = type.footnote,
                                            color = palette.secondaryLabel,
                                        )
                                },
                            )
                        ToolSection.ACCOUNT -> SectionHeader("Account")
                    }
                }
                item("group-${section.name}") {
                    Group(rows) { row, position ->
                        when (row) {
                            is ToolPage ->
                                ListRow(
                                    row.title,
                                    Modifier.testTag("tool-${row.name.lowercase()}"),
                                    position = position,
                                    glyph = row.glyph,
                                    tile = row.color,
                                    value =
                                        when (row) {
                                            ToolPage.MACHINES ->
                                                if (session.machines.isEmpty()) ""
                                                else "$online of ${session.machines.size} online"
                                            ToolPage.USAGE -> lowest?.let { "$it% left" }
                                            else -> null
                                        },
                                    accessory = Accessory.CHEVRON,
                                    onClick = { open(row) },
                                )
                            is ExtensionPage ->
                                ListRow(
                                    row.title,
                                    Modifier.testTag("tool-${row.id}"),
                                    position = position,
                                    glyph = row.glyph,
                                    tile = Color(row.tint),
                                    value = row.value(store),
                                    accessory = Accessory.CHEVRON,
                                    onClick = {
                                        store.showFrom(
                                            MobileRoute.Root(MobileTab.TOOLS),
                                            MobileRoute.Extension(row.id),
                                        )
                                    },
                                )
                        }
                    }
                }
            }
        }
    }
}
