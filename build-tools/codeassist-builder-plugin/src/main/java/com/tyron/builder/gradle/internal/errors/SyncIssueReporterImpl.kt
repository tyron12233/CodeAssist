package com.tyron.builder.gradle.internal.errors

import com.android.annotations.concurrency.Immutable
import com.android.ide.common.blame.Message
import com.google.common.base.MoreObjects
import com.google.common.collect.ImmutableList
import com.google.common.collect.Maps
import com.tyron.builder.errors.EvalIssueException
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.errors.MessageReceiverImpl
import com.tyron.builder.gradle.errors.SyncIssueReporter
import com.tyron.builder.gradle.internal.ide.SyncIssueImpl
import com.tyron.builder.gradle.internal.services.ServiceRegistrationAction
import com.tyron.builder.model.SyncIssue
import com.tyron.builder.plugin.options.SyncOptions
import com.tyron.builder.plugin.options.SyncOptions.EvaluationMode
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import javax.annotation.concurrent.GuardedBy

class SyncIssueReporterImpl(
    private val mode: EvaluationMode,
    errorFormatMode: SyncOptions.ErrorFormatMode,
    logger: Logger
) : SyncIssueReporter() {

    @GuardedBy("this")
    private val _syncIssues = Maps.newHashMap<SyncIssueKey, SyncIssue>()

    @GuardedBy("this")
    private var handlerLocked = false

    private val messageReceiverImpl = MessageReceiverImpl(errorFormatMode, logger)

    @get:Synchronized
    override val syncIssues: ImmutableList<SyncIssue>
        get() = ImmutableList.copyOf(_syncIssues.values)

    @Synchronized
    private fun getAllIssuesAndClear(): ImmutableList<SyncIssue> {
        val issues = syncIssues
        _syncIssues.clear()
        return issues
    }

    private fun reportRemainingIssues() {
        lockHandler()
        val issues = getAllIssuesAndClear()
        var syncErrorToThrow: EvalIssueException? = null
        for (issue in issues) {
            when (issue.severity) {
                SyncIssue.SEVERITY_WARNING -> messageReceiverImpl.receiveMessage(Message(Message.Kind.WARNING, issue.message))
                SyncIssue.SEVERITY_ERROR -> {
                    val exception = EvalIssueException(issue.message, issue.data, issue.multiLineMessage)
                    if (syncErrorToThrow == null) {
                        syncErrorToThrow = exception
                    } else {
                        syncErrorToThrow.addSuppressed(exception)
                    }
                }
                else -> throw IllegalStateException("unexpected issue severity for $issue")
            }
        }
        if (syncErrorToThrow != null) {
            throw syncErrorToThrow
        }
    }

    @Synchronized
    override fun hasIssue(type: Type): Boolean {
        return _syncIssues.values.any { issue -> issue.type == type.type }
    }

    @Synchronized
    override fun reportIssue(
            type: Type,
            severity: Severity,
            exception: EvalIssueException) {
        val issue = SyncIssueImpl(type, severity, exception)
        when (mode) {
            EvaluationMode.STANDARD -> {
                if (severity.severity != SyncIssue.SEVERITY_WARNING) {
                    throw exception
                }
                messageReceiverImpl.receiveMessage(Message(Message.Kind.WARNING, exception.message))
            }

            EvaluationMode.IDE -> {
                if (handlerLocked) {
                    throw IllegalStateException("Issue registered after handler locked.", exception)
                }
                _syncIssues[syncIssueKeyFrom(issue)] = issue
            }
            else -> throw RuntimeException("Unknown SyncIssue type")
        }
    }

    @Synchronized
    override fun lockHandler() {
        handlerLocked = true
    }

    /**
     * Global, build-scope, sync issue reporter. This instance can be used from build services that
     * need to report sync issues, such as sdk build service.
     *
     * IMPORTANT: In order to avoid duplication of global build-wide sync issues, callers must
     * invoke [getAllIssuesAndClear] method. This will return list of global sync issues only once,
     * and any subsequent invocation will return an empty list.
     */
    abstract class GlobalSyncIssueService : BuildService<GlobalSyncIssueService.Parameters>,
        IssueReporter(), AutoCloseable {
        interface Parameters : BuildServiceParameters {
            val mode: Property<EvaluationMode>
            val errorFormatMode: Property<SyncOptions.ErrorFormatMode>
        }

        private val reporter = SyncIssueReporterImpl(
            parameters.mode.get(),
            parameters.errorFormatMode.get(),
            Logging.getLogger(GlobalSyncIssueService::class.java)
        )

        /**
         * Returns all reported sync issues for the first invocation of the method. This is to avoid
         * duplication of sync issues across project when this is queried from the model builder.
         */
        fun getAllIssuesAndClear(): ImmutableList<SyncIssue> {
            return reporter.getAllIssuesAndClear()
        }

        override fun reportIssue(type: Type, severity: Severity, exception: EvalIssueException) {
            reporter.reportIssue(type, severity, exception)
        }

        override fun hasIssue(type: Type): Boolean = reporter.hasIssue(type)

        override fun close() {
            reporter.reportRemainingIssues()
        }

        class RegistrationAction(
                project: Project,
                private val evaluationMode: EvaluationMode,
                private val errorFormatMode: SyncOptions.ErrorFormatMode
        ) :
                ServiceRegistrationAction<GlobalSyncIssueService, Parameters>(
                        project,
                        GlobalSyncIssueService::class.java
                ) {

            override fun configure(parameters: Parameters) {
                parameters.mode.set(evaluationMode)
                parameters.errorFormatMode.set(errorFormatMode)
            }
        }
    }
}

/**
 * Creates a key from a SyncIssue to use in a map.
 */
private fun syncIssueKeyFrom(syncIssue: SyncIssue): SyncIssueKey {
    // If data is not available we use the message part to disambiguate between issues with the
    // same type.
    return SyncIssueKey(syncIssue.type, syncIssue.data ?: syncIssue.message)
}

@Immutable
internal data class SyncIssueKey constructor(
        private val type: Int,
        private val data: String) {

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
                .add("type", type)
                .add("data", data)
                .toString()
    }
}
