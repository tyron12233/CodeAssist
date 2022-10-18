package com.tyron.builder.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Root Task class for all of AGP.
 *
 * DO NOT EXTEND THIS METHOD DIRECTLY. Instead, extend:
 * - [NewIncrementalTask] -- variant aware task
 * - [NonIncrementalTask] -- variant aware task
 * - [NonIncrementalGlobalTask] -- non variant aware task
 */
@DisableCachingByDefault
abstract class BaseTask : DefaultTask() {
    @get:Internal("only for task execution")
    abstract val projectPath: Property<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

//    @get:Internal
//    abstract val analyticsService: Property<AnalyticsService>

//    /**
//     * Called by subclasses that want to record something.
//     *
//     * The task execution will use [GradleBuildProfileSpan.ExecutionType.TASK_EXECUTION_ALL_PHASES]
//     * as the span type to record the [AndroidVariantTask.recordedTaskAction].
//     */
//    protected inline fun recordTaskAction(
//        analyticsService: AnalyticsService?,
//        crossinline block: () -> Unit
//    ) {
//        Blocks.recordSpan<Exception>(
//            path,
//            GradleBuildProfileSpan.ExecutionType.TASK_EXECUTION_ALL_PHASES,
//            analyticsService
//        ) { block() }
//    }
}