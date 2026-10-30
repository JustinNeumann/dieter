package com.getdieter.mobile.store

import com.getdieter.client.v1.*
import kotlinx.coroutines.flow.MutableStateFlow

/** A project's schedules, or every project's. */
class SchedulesModel internal constructor(private val context: StoreContext) {
    val slice = MutableStateFlow(SchedulesSlice())
    /** Schedules shows every project's; off, those of the tools' project. */
    val allProjects = MutableStateFlow(true)

    internal fun subscribe() {
        context.observeAlways(Slice.SLICE_SCHEDULES, MobileStore.SCHEDULES_SCOPE) {
            it.schedules?.let { value -> slice.value = value }
        }
    }

    /** Binds Schedules to [projectId], or to every project while [allProjects], then loads them. */
    internal fun load(projectId: String) = context.action {
        context.core.dispatch(
            Command(
                schedules =
                    SchedulesCommand(
                        scope = MobileStore.SCHEDULES_SCOPE,
                        bind =
                            ScheduleProject(
                                project_id = projectId,
                                all_projects = allProjects.value,
                            ),
                    )
            )
        )
        context.core.dispatch(
            Command(
                schedules = SchedulesCommand(scope = MobileStore.SCHEDULES_SCOPE, load = Step())
            )
        )
    }
}
