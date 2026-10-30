@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getdieter.mobile.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One refresh gesture and accessibility action for both mobile platforms. A mouse wheel or trackpad does not pull:
 * it has no release, so its overscroll at the top of a list left the indicator half drawn for good (the page in a
 * desktop browser). While one turns, the pull is off and the overscroll goes on to the screen's top bar.
 */
@Composable
internal fun RefreshableList(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    tag: String,
    topInset: Dp,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var wheeling by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }
    Box(
        modifier =
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type != PointerEventType.Scroll) continue
                            wheeling = true
                            settle?.cancel()
                            // a wheel's scrolling is animated past its last event
                            settle = scope.launch {
                                delay(500)
                                wheeling = false
                            }
                        }
                    }
                }
                .pullToRefresh(isRefreshing = refreshing, state = state, enabled = !wheeling, onRefresh = onRefresh)
                .testTag(tag)
                .semantics {
                stateDescription = if (refreshing) "Refreshing" else "Pull to refresh"
                customActions =
                    listOf(
                        CustomAccessibilityAction("Sync again") {
                            onRefresh()
                            true
                        }
                    )
            },
    ) {
        content()
        Box(Modifier.matchParentSize()) {
            val position = Modifier.align(Alignment.TopCenter).padding(top = topInset)
            if (apple)
                PullToRefreshDefaults.IndicatorBox(
                    state = state,
                    isRefreshing = refreshing,
                    modifier = position,
                    containerColor = Color.Transparent,
                    elevation = 0.dp,
                ) {
                    Spinner(
                        Modifier.size(22.dp)
                            .alpha(if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f))
                    )
                }
            else
                PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = refreshing,
                    modifier = position,
                    containerColor = colors.surfaceContainerHigh,
                    color = palette.accent,
                )
        }
    }
}
