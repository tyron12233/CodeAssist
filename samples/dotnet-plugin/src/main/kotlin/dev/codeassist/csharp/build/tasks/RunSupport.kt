package dev.codeassist.csharp.build.tasks

import dev.ide.build.RunConsole
import dev.ide.build.Task
import dev.ide.build.TaskGraph

/** A line logger that also writes to [console]'s output, when the Run opened one. */
internal fun alsoToConsole(log: (String) -> Unit, console: RunConsole?): (String) -> Unit = { line ->
    log(line)
    console?.io?.stdout(line + "\n")
}

/** Tasks run one after another, each depending on the one before. */
internal class LinearGraph(override val tasks: List<Task>) : TaskGraph {

    override fun dependencies(t: Task): List<Task> {
        val index = tasks.indexOf(t)
        return if (index > 0) listOf(tasks[index - 1]) else emptyList()
    }

    override fun topologicalLevels(): List<List<Task>> =
        tasks.map {
            listOf(it)
        }
}
