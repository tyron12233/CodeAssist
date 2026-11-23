package dev.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.holiday.HolidayActivity
import dev.ide.ui.holiday.HolidayLedger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Adds a Build star when a build is seen running and then succeeding. The ledger keeps it to one a day,
 * so this can fire on every build. Draws nothing.
 */
@Composable
internal fun HolidayBuildRecorder(backend: IdeBackend, ledger: HolidayLedger) {
    LaunchedEffect(backend, ledger) {
        var last: RunStatus? = null
        backend.build.buildState
            .map { it.status }
            .distinctUntilChanged()
            .collect { status ->
                if (last == RunStatus.Running && status == RunStatus.Succeeded) ledger.record(HolidayActivity.Build)
                last = status
            }
    }
}
