package dev.ide.interp.compose.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Drivers the feasibility spike interprets: they create real `androidx.compose.runtime` state and read/write it,
 * so the whole snapshot state system runs interpreted (this file's package and `androidx.compose.runtime` are
 * the only interpreted namespaces; the Kotlin/Java/atomic floor is bridged). Each returns a plain value so the
 * result can be checked against calling the same function for real.
 */
object RuntimeSpikeFixture {

    /** Create a primitive int state, then write and read it in a loop. Returns the sum of the reads. */
    @JvmStatic
    fun roundTripIntState(writes: Int): Long {
        val state = mutableIntStateOf(0)
        var sum = 0L
        for (i in 0 until writes) {
            state.intValue = i
            sum += state.intValue
        }
        return sum
    }

    /** Create a boxed state and toggle it, returning the final length (exercises the general state path). */
    @JvmStatic
    fun roundTripBoxedState(writes: Int): Int {
        var text by mutableStateOf("")
        for (i in 0 until writes) text = if (text.length > 3) "" else text + "x"
        return text.length
    }
}
