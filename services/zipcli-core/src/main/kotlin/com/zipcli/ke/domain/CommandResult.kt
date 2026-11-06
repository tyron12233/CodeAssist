package com.zipcli.ke.domain

sealed interface CommandResult {
    /** Trigger the system file picker for SAF */
    object RequestSafPermission : CommandResult

    data class Success(
        val lines: List<String> = emptyList(),
        val newCwd: String? = null,
        /** Exit status seen by scripts; 0 = success. Non-zero makes '&&' / '||' and errexit react. */
        val status: Int = 0,
    ) : CommandResult

    /** A blank [message] means "failed, nothing to report" (used by `test` and `false`). */
    data class Failure(val message: String, val code: String? = null) : CommandResult

    data class Confirmation(
        val prompt: String,
        val onConfirm: suspend () -> CommandResult,
    ) : CommandResult

    /** Clear the terminal log (used by `clear`). */
    object Clear : CommandResult

    /** Open the in-app editor sheet (used by `edit` and `update`). */
    data class OpenEditor(
        val path: String,
        val initialContent: String,
    ) : CommandResult

    /** Stop the running script (used by `exit`). */
    data class Exit(val code: Int = 0) : CommandResult
}
