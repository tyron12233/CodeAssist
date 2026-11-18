package dev.codeassist.csharp.util

internal object RestoreNotice {

    /**
     * Hint appended to every failure.
     */
    const val NET_HINT =
        "If the network is fine, check that codeassist_plugin.toml lists \"net\" under capabilities."

    /** Builds a notification with the module, first problem, count of additional problems, and hint. */
    fun failure(module: String, problems: List<String>): String {
        val more = problems.size - 1
        val rest = if (more > 0) " (+$more more, see the log)" else ""
        return "NuGet restore failed for $module: ${problems.first()}$rest. $NET_HINT"
    }

    /** Builds the warning shown at the top of a `.cs` file while its module's packages are not restored. */
    fun fileWarning(problems: List<String>): String {
        val more = problems.size - 1
        val rest = if (more > 0) " (+$more more)" else ""
        return "NuGet restore failed: ${problems.first()}$rest. " +
            "Save the .csproj to retry, or run Restore from the Run picker."
    }
}