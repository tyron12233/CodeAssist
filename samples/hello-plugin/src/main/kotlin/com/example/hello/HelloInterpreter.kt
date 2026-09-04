package com.example.hello

import dev.ide.interp.api.CODE_INTERPRETER
import dev.ide.interp.api.InterpretConfig
import dev.ide.interp.api.LowerRequest
import dev.ide.interp.api.LowerResult
import dev.ide.platform.ServiceLookup
import java.nio.file.Paths

/**
 * Runs a function out of the open project through the IDE's interpreter, with no compile step.
 *
 * This sits between the plugin's two facets. The **engine** facet is the one with access to services
 * ([ServiceLookup] comes from its `PluginRegistration`), and the **UI** facet is the one that needs an answer
 * to draw. They load off the same APK on the same classloader, so an object like this is all it takes to get
 * from one to the other; nothing is serialized and nothing crosses a boundary.
 *
 * A framework plugin does more than this with the same API: it instantiates the user's class and drives its
 * lifecycle, handing it to the real framework through `InterpretedObject.proxy`. The shape is the same, and
 * so are the two rules worth copying here: resolve the service lazily rather than at registration, and treat
 * `NotReady` as "come back later" rather than as a failure.
 */
object HelloInterpreter {

    /** Set by the engine facet ([HelloPlugin]) when it registers. Empty until then. */
    var services: ServiceLookup = ServiceLookup.Empty

    /** What the preview pane shows: whatever `greeting()` in the edited file returns. */
    sealed interface Outcome {
        data class Value(val text: String) : Outcome

        /** Not yet: the project is still indexing, or there is nothing open. The pane retries. */
        data class Waiting(val why: String) : Outcome

        data class Problem(val reasons: List<String>) : Outcome

        /** This IDE has no interpreter, which is the normal answer on a host that predates it. */
        object Unsupported : Outcome
    }

    fun greeting(path: String, buffer: String): Outcome {
        val interp = services.getServiceOrNull(CODE_INTERPRETER) ?: return Outcome.Unsupported
        val request = LowerRequest(file = Paths.get(path), entry = "greeting", text = buffer)
        val program = when (val result = interp.lower(request)) {
            is LowerResult.Lowered -> result.program
            is LowerResult.NotReady -> return Outcome.Waiting(result.message)
            is LowerResult.Failed -> return Outcome.Problem(result.problems)
        }
        // The default config is the conservative one: the project's own preview sandbox, and a statement that
        // could not be interpreted is skipped rather than failing the whole run.
        val session = interp.openSource(program, InterpretConfig())
        return try {
            val value = session.call(program.entry)
            val problems = session.problems.map { it.toString() }
            if (problems.isEmpty()) Outcome.Value(value?.toString() ?: "null")
            else Outcome.Problem(problems)
        } catch (e: Exception) {
            Outcome.Problem(listOf(e.message ?: e.toString()))
        } finally {
            // One session per pass. Re-opening is cheap, and it is what makes each render start clean.
            session.dispose()
        }
    }
}
