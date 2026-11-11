package dev.ide.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A plugin asked the engine for the open files to be analyzed again (`dev.ide.analysis.Reanalysis`). The editor
 * answers with one new [IdeUiState.analysisEpoch], which is what reruns both the focused tab's daemon and the
 * background-tab sweep; the value the flow already holds when the session starts is not a request.
 */
class ReanalysisRequestTest {

    private val requests = MutableStateFlow(7)

    private fun state(): IdeUiState {
        val backend = object : StubBackend() {
            override val reanalysisRequests: StateFlow<Int> get() = requests
        }
        return IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun eachRequestIsOneAnalysisGeneration() {
        val state = state()
        runBlocking {
            val session = launch(Dispatchers.Unconfined) { state.runSessionEffects() }
            try {
                val start = state.analysisEpoch
                assertEquals(start, state.analysisEpoch, "the value on hand at session start is not a request")

                requests.value += 1
                assertEquals(start + 1, state.analysisEpoch)

                requests.value += 1
                assertEquals(start + 2, state.analysisEpoch)
            } finally {
                session.cancel()
            }
        }
    }
}
