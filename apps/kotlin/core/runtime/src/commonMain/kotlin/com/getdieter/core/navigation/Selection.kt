package com.getdieter.core.navigation

import com.getdieter.api.v1.Board
import com.getdieter.api.v1.Card
import com.getdieter.api.v1.Project
import com.getdieter.core.runtime.Timestamps

/** What the board surfaces show: a project, one of its boards, and a lane. */
data class BoardSelection(
    val projectId: String = "",
    val boardId: String = "",
    val lane: String = "",
)

object BoardSelections {
    /**
     * Keeps [current] while it still exists; otherwise falls back to the first project, its first
     * board, and that board's first lane. A retired board stays selectable so it can be restored.
     */
    fun resolve(
        projects: List<Project>,
        boards: Map<String, List<Board>>,
        retired: List<Board>,
        current: BoardSelection,
    ): BoardSelection {
        val projectId =
            current.projectId.takeIf { id -> projects.any { it.id == id } }
                ?: projects.firstOrNull()?.id.orEmpty()
        val projectBoards = boards[projectId].orEmpty()
        val boardId =
            current.boardId.takeIf { id ->
                (projectBoards + retired).any { it.id == id && it.project_id == projectId }
            } ?: projectBoards.firstOrNull()?.id.orEmpty()
        val board = projectBoards.firstOrNull { it.id == boardId }
        val lane =
            current.lane.takeIf { id -> board?.lanes?.any { it.id == id } == true }
                ?: board?.lanes?.firstOrNull()?.id.orEmpty()
        return BoardSelection(projectId, boardId, lane)
    }

    /**
     * The project worked in most recently: the one with the latest activity on a card or chat that
     * a schedule did not create (those run on their own), else the first of [projects]. Empty
     * without projects.
     */
    fun recentProject(projects: List<Project>, cards: List<Card>): String {
        val ids = projects.mapTo(HashSet()) { it.id }
        return cards
            .filter { it.project_id in ids && it.origin?.schedule_id.isNullOrEmpty() }
            .mapNotNull { card ->
                Timestamps.parse(card.last_activity_at.ifEmpty { card.updated_at })?.let {
                    it to card.project_id
                }
            }
            .maxByOrNull { it.first }
            ?.second ?: projects.firstOrNull()?.id.orEmpty()
    }
}
