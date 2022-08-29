package com.tyron.builder.ide.common.workers

import java.io.Closeable
import java.io.Serializable

/**
 * Some classes do not have access to the Gradle APIs like classes located in the builder module.
 * Yet those classes could potentially have a need for a facility similar to Gradle's WorkerExecutor
 * to submit work items for remove or parallel processing.
 *
 *
 * This interface implementation can be used by Task or other higher level implementation classes
 * to provide this facility.
 *
 * High level interaction is as follow :
 *  * Task creates a WorkerExecutorFacade object that encapsulates an Executor style facility.
 * This facade instance is passed to the classes with no access to the Gradle APIs.
 *  * Classes create an serializable instance of runnable for each work actions and submit it
 *  with the [submit] API.
 *  * Task should call [await] for synchronous wait on the action completion or [close] for
 *  submission completion. [close] has an implementation dependent behaviour where some will
 *  immediately return while associating the actions to the task. Others will basically delegate
 *  back to [await]
 */
interface WorkerExecutorFacade : AutoCloseable, Closeable {

    interface WorkAction: Runnable, Serializable

    /** Submit action that must be serializable. */
    fun submit(action: WorkAction)

    /**
     * Wait for all submitted work actions completion.
     *
     *
     * This is to be used when more work needs to be done inside the TaskAction after the await.
     * If nothing is done in the task action afterward, use [.close] instead.
     */
    fun await()

    /**
     * Indicate the task action is done.
     *
     *
     * This has a different behavior depending on the implementation. Worker based implementation
     * does nothing, while regular thread-pool implementation wait on all the threads to be done.
     */
    override fun close()
}