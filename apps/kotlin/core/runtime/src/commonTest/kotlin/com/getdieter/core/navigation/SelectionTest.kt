package com.getdieter.core.navigation

import com.getdieter.api.v1.Card
import com.getdieter.api.v1.CardOrigin
import com.getdieter.api.v1.Project
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionTest {
    private val projects =
        listOf(
            Project(id = "a", name = "A"),
            Project(id = "b", name = "B"),
            Project(id = "c", name = "C"),
        )

    @Test
    fun theRecentProjectIsWhereSomeoneLastWorkedNotWhereASchedulePostedLast() {
        val cards =
            listOf(
                Card(id = "1", project_id = "b", last_activity_at = "2026-10-30T09:00:00Z"),
                Card(id = "2", project_id = "c", updated_at = "2026-10-30T10:00:00.5Z"),
                Card(id = "3", project_id = "c", last_activity_at = "2026-10-30T10:00:00Z"),
                Card(
                    id = "4",
                    project_id = "a",
                    last_activity_at = "2026-10-30T11:00:00Z",
                    origin = CardOrigin(kind = "schedule", schedule_id = "sch_1"),
                ),
                Card(id = "5", project_id = "gone", last_activity_at = "2026-10-30T12:00:00Z"),
                Card(id = "6", project_id = "b", last_activity_at = "not a time"),
            )
        assertEquals("c", BoardSelections.recentProject(projects, cards))
        assertEquals("a", BoardSelections.recentProject(projects, emptyList()), "else the first")
        assertEquals("", BoardSelections.recentProject(emptyList(), cards))
    }
}
